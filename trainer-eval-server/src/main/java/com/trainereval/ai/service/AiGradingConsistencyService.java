package com.trainereval.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 评分与教师评分的一致性度量。
 *
 * <h3>为什么这个指标比"AI 打分准不准"这个问法更有用</h3>
 * "准不准"需要一个绝对标准，而主观题根本不存在绝对标准分数。可行的做法是
 * <b>把教师终评分当作参照</b>，量化 AI 建议分与它的偏离 —— 这直接回答了一个可决策的问题：
 * <b>"AI 评分能不能先自动给分、教师只复核？"</b>
 *
 * <h3>四个指标各自回答什么</h3>
 * <ul>
 *   <li><b>Pearson 相关系数</b>：AI 与教师的排序是否一致。它高说明 AI 能区分好答案与差答案，
 *       是"让 AI 排序/推荐复核优先级"的前提。</li>
 *   <li><b>MAE / RMSE</b>：绝对偏离的均值 / 均方根。RMSE 明显大于 MAE 说明存在少数偏离很大的样本
 *       （比"整体偏一点"危险得多，因为那几条会被漏掉）。</li>
 *   <li><b>平均偏差（AI − 教师）</b>：系统性倾向。显著为正说明 AI 普遍给高，
 *       可以用一个校准量抵消；接近 0 而 MAE 大，说明是随机的、不能用简单偏移修正。</li>
 *   <li><b>一致率</b>：按"满分的 ±10% / ±20%"统计落在容差内的比例 ——
 *       这是能直接转成业务流程的数字（例如"±10% 达 90% 才允许 AI 先评"）。</li>
 * </ul>
 *
 * <h3>样本量与相关系数的诚实处理</h3>
 * 主观题 + 人工复核的样本在真实系统里通常很少（十几条）。Pearson 在 n &lt; 3 时无意义，
 * 且小样本下极易被单个离群点主导。所以：
 * <ul>
 *   <li>n &lt; 3 不报相关系数（返回 null 并说明原因），而不是报一个看起来可用、实则无意义的数。</li>
 *   <li>返回 {@code sampleWarning}，把"样本太少，结论仅供参考"显式写在响应里，
 *       避免数字被当成结论用。</li>
 *   <li>同时返回 {@code outliers}：偏离最大的若干条，把"AI 评错的那几条"直接指出来 ——
 *       这才是复核时真正要看的，而不是一个总体系数。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGradingConsistencyService {

    private final ExExamAnswerMapper answerMapper;

    /** 一致率容差档（占题目满分的比例）。 */
    private static final double[] TOLERANCES = {0.10, 0.20};

    /** 最少样本量：低于它不计算相关系数。3 是数学下限（两点必然共线）。 */
    private static final int MIN_SAMPLES_FOR_CORRELATION = 3;

    /** 小样本提醒阈值：低于它给出"仅供参考"的警告。 */
    private static final int SAMPLE_WARNING_THRESHOLD = 30;

    public Map<String, Object> report(Integer sinceDays) {
        LambdaQueryWrapper<ExExamAnswer> wrapper = new LambdaQueryWrapper<ExExamAnswer>()
                .isNotNull(ExExamAnswer::getAiScore)
                .isNotNull(ExExamAnswer::getManualScore)
                .isNotNull(ExExamAnswer::getQuestionScore)
                .gt(ExExamAnswer::getQuestionScore, BigDecimal.ZERO)
                .orderByDesc(ExExamAnswer::getGradeTime)
                .last("LIMIT 5000");
        if (sinceDays != null && sinceDays > 0) {
            wrapper.ge(ExExamAnswer::getGradeTime, LocalDateTime.now().minusDays(sinceDays));
        }
        List<ExExamAnswer> answers = answerMapper.selectList(wrapper);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("metricDefinition", "以教师终评分(manual_score)为参照，度量 AI 建议分(ai_score)的偏离程度");
        report.put("sampleSize", answers.size());
        report.put("sinceDays", sinceDays);

        if (answers.isEmpty()) {
            report.put("available", false);
            report.put("message", "暂无同时具备 AI 建议分与教师终评分的样本。"
                    + "需要先让 AI 评分跑过、再由教师在阅卷中心完成终评，本报告才有数据。");
            return report;
        }
        report.put("available", true);

        int n = answers.size();
        double sumAi = 0;
        double sumManual = 0;
        double sumAbsDiff = 0;
        double sumSqDiff = 0;
        double sumDiff = 0;
        double[] aiNormalized = new double[n];
        double[] manualNormalized = new double[n];
        int[] withinTolerance = new int[TOLERANCES.length];
        int exact = 0;

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ExExamAnswer answer = answers.get(i);
            double ai = answer.getAiScore().doubleValue();
            double manual = answer.getManualScore().doubleValue();
            double full = answer.getQuestionScore().doubleValue();
            double diff = ai - manual;

            sumAi += ai;
            sumManual += manual;
            sumAbsDiff += Math.abs(diff);
            sumSqDiff += diff * diff;
            sumDiff += diff;

            aiNormalized[i] = clamp01(ai / full);
            manualNormalized[i] = clamp01(manual / full);

            for (int t = 0; t < TOLERANCES.length; t++) {
                if (Math.abs(diff) <= full * TOLERANCES[t]) {
                    withinTolerance[t]++;
                }
            }
            if (Math.abs(diff) <= 0.5) {
                exact++;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("answerId", answer.getId());
            row.put("recordId", answer.getRecordId());
            row.put("questionType", answer.getQuestionType());
            row.put("fullScore", round(full));
            row.put("aiScore", round(ai));
            row.put("manualScore", round(manual));
            row.put("diff", round(diff));
            row.put("absDiff", round(Math.abs(diff)));
            rows.add(row);
        }

        double mae = sumAbsDiff / n;
        double rmse = Math.sqrt(sumSqDiff / n);
        double bias = sumDiff / n;

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("meanAiScore", round(sumAi / n));
        summary.put("meanManualScore", round(sumManual / n));
        summary.put("mae", round(mae));
        summary.put("rmse", round(rmse));
        summary.put("meanBias", round(bias));
        summary.put("biasDirection", describeBias(bias, mae));
        summary.put("exactAgreementRate", rate(exact, n));
        for (int t = 0; t < TOLERANCES.length; t++) {
            summary.put("within" + (int) (TOLERANCES[t] * 100) + "PercentRate", rate(withinTolerance[t], n));
        }
        report.put("summary", summary);

        // Pearson：只有样本够、且两侧都有方差时才算。否则会得到 NaN 或 -1/1 这种假极端值。
        if (n < MIN_SAMPLES_FOR_CORRELATION) {
            report.put("pearson", null);
            report.put("pearsonNote", "样本量 " + n + " < " + MIN_SAMPLES_FOR_CORRELATION
                    + "，相关系数无统计意义，已省略");
        } else {
            Double pearson = pearson(aiNormalized, manualNormalized);
            report.put("pearson", pearson == null ? null : round(pearson));
            report.put("pearsonNote", pearson == null
                    ? "AI 或教师评分在本批样本中无变化（方差为 0），相关系数无定义"
                    : "按题目满分归一化后计算，衡量 AI 与教师的排序一致性");
            report.put("interpretation", interpret(pearson));
        }

        // 按题型拆分：客观题与主观题的一致性表现差异极大，合并看会互相掩盖
        report.put("byQuestionType", byQuestionType(answers));

        // 混淆分档：把归一化分数分 4 档，看 AI 与教师落在同一档的比例。
        // 比"相关系数"更贴近业务：教师关心的是"档位是否一致"，而不是精确到小数。
        report.put("bandConfusion", bandConfusion(aiNormalized, manualNormalized));

        // 偏离最大的样本：这才是复核时要看的清单
        rows.sort((a, b) -> Double.compare((Double) b.get("absDiff"), (Double) a.get("absDiff")));
        report.put("outliers", rows.subList(0, Math.min(rows.size(), 10)));

        if (n < SAMPLE_WARNING_THRESHOLD) {
            report.put("sampleWarning", "样本量仅 " + n + " 条，指标波动会很大（单个离群点即可显著改变 MAE 与相关系数），"
                    + "建议积累到 " + SAMPLE_WARNING_THRESHOLD + " 条以上再据此调整策略。");
        }
        report.put("decisionHint", decide(summary, n));
        return report;
    }

    private Map<String, Object> byQuestionType(List<ExExamAnswer> answers) {
        Map<String, List<ExExamAnswer>> grouped = new LinkedHashMap<>();
        for (ExExamAnswer answer : answers) {
            String type = answer.getQuestionType() == null ? "UNKNOWN" : answer.getQuestionType();
            grouped.computeIfAbsent(type, k -> new ArrayList<>()).add(answer);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        grouped.forEach((type, list) -> {
            int count = list.size();
            double absSum = 0;
            double diffSum = 0;
            int within10 = 0;
            for (ExExamAnswer answer : list) {
                double ai = answer.getAiScore().doubleValue();
                double manual = answer.getManualScore().doubleValue();
                double full = answer.getQuestionScore().doubleValue();
                double diff = ai - manual;
                absSum += Math.abs(diff);
                diffSum += diff;
                if (Math.abs(diff) <= full * 0.10) {
                    within10++;
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sampleSize", count);
            item.put("mae", round(absSum / count));
            item.put("meanBias", round(diffSum / count));
            item.put("within10PercentRate", rate(within10, count));
            result.put(type, item);
        });
        return result;
    }

    private Map<String, Object> bandConfusion(double[] ai, double[] manual) {
        String[] labels = {"0-60%", "60-75%", "75-90%", "90-100%"};
        int[][] matrix = new int[labels.length][labels.length];
        int same = 0;
        for (int i = 0; i < ai.length; i++) {
            int aiBand = band(ai[i]);
            int manualBand = band(manual[i]);
            matrix[manualBand][aiBand]++;
            if (aiBand == manualBand) {
                same++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bands", List.of(labels));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("manualBand", labels[i]);
            List<Integer> counts = new ArrayList<>();
            for (int j = 0; j < labels.length; j++) {
                counts.add(matrix[i][j]);
            }
            row.put("aiCounts", counts);
            rows.add(row);
        }
        result.put("rows", rows);
        result.put("note", "行=教师档位，列=AI 档位（百分比按题目满分归一化）");
        result.put("sameBandRate", rate(same, ai.length));
        return result;
    }

    private int band(double normalized) {
        if (normalized < 0.60) {
            return 0;
        }
        if (normalized < 0.75) {
            return 1;
        }
        if (normalized < 0.90) {
            return 2;
        }
        return 3;
    }

    /** Pearson 相关系数；任一侧方差为 0 时返回 null（避免 0/0 得到 NaN）。 */
    private Double pearson(double[] x, double[] y) {
        int n = x.length;
        double meanX = 0;
        double meanY = 0;
        for (int i = 0; i < n; i++) {
            meanX += x[i];
            meanY += y[i];
        }
        meanX /= n;
        meanY /= n;
        double cov = 0;
        double varX = 0;
        double varY = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            cov += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX <= 1e-9 || varY <= 1e-9) {
            return null;
        }
        return cov / Math.sqrt(varX * varY);
    }

    private String interpret(Double pearson) {
        if (pearson == null) {
            return "无法解读（见 pearsonNote）";
        }
        double abs = Math.abs(pearson);
        if (abs >= 0.8) {
            return "强一致：AI 与教师对答案好坏的排序高度吻合，可作为复核排序依据";
        }
        if (abs >= 0.6) {
            return "中等一致：可用于缩小教师复核范围，但不宜直接采信 AI 分数";
        }
        if (abs >= 0.3) {
            return "弱一致：AI 与教师判断差异明显，建议只把 AI 分数作为参考展示";
        }
        return "基本不一致：当前规则/提示词下 AI 评分与教师判断没有可比性，不应进入自动给分流程";
    }

    private String describeBias(double bias, double mae) {
        if (mae < 1e-6) {
            return "无偏差（两者完全一致）";
        }
        double relative = Math.abs(bias) / mae;
        if (relative < 0.3) {
            return "无系统性倾向：偏差方向随机，不能用统一的加减分修正（只能改提示词/规则）";
        }
        return bias > 0
                ? "系统性偏高：AI 普遍给分高于教师，可考虑整体下调或收紧给分标准"
                : "系统性偏低：AI 普遍给分低于教师，可考虑整体上调或放宽给分标准";
    }

    private String decide(Map<String, Object> summary, int n) {
        Object within10 = summary.get("within10PercentRate");
        double rate = within10 instanceof Double d ? d : 0;
        if (n < SAMPLE_WARNING_THRESHOLD) {
            return "样本不足，暂不建议据此调整线上策略；先积累样本。";
        }
        if (rate >= 0.90) {
            return "±10% 一致率已达 " + Math.round(rate * 100) + "%，可考虑 AI 先评 + 教师抽检；"
                    + "务必保留 outlier 清单供教师重点复核。";
        }
        if (rate >= 0.75) {
            return "±10% 一致率 " + Math.round(rate * 100) + "%，适合 AI 预填分数、教师逐条确认。";
        }
        return "±10% 一致率仅 " + Math.round(rate * 100) + "%，建议 AI 只做辅助提示（给理由不给分数）。";
    }

    private double rate(int part, int total) {
        return total == 0 ? 0 : round((double) part / total);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
