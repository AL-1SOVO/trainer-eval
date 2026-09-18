package com.trainereval.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.questionbank.entity.QbExamPaper;
import com.trainereval.questionbank.mapper.QbExamPaperMapper;
import com.trainereval.report.dto.ReportDataset;
import com.trainereval.report.dto.ReportRequest;
import com.trainereval.system.entity.SysDept;
import com.trainereval.system.entity.SysUser;
import com.trainereval.system.mapper.SysDeptMapper;
import com.trainereval.system.mapper.SysUserMapper;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraEnrollment;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
import com.trainereval.training.mapper.TraCourseMapper;
import com.trainereval.training.mapper.TraEnrollmentMapper;
import com.trainereval.training.mapper.TraMaterialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 报表数据集装配 —— 查库 + 聚合 + 权限限定，产出与渲染无关的 {@link ReportDataset}。
 *
 * <h3>为什么实体上那些 userName/courseName 用不上</h3>
 * 它们在实体里是 {@code @TableField(exist = false)}，且项目里没有任何 MyBatis XML 映射，
 * 所以 {@code selectList} 出来的对象这些字段**永远是 null**。
 * 报表要显示人名/课程名就必须自己按 id 查一次并做映射 —— 本类把那一步集中做掉。
 *
 * <h3>权限限定（原来是个数据泄露点）</h3>
 * 生成接口对 EMPLOYEE 也开放，而"部门培训报表""课程统计报表"是**全量**数据。
 * 也就是说任何登录用户都能导出一份"所有人的培训/成绩汇总"。
 * 现在按角色限定了数据范围：
 * <ul>
 *   <li>MANAGER：全量</li>
 *   <li>其他角色：只能生成与自己相关的报表（个人培训/个人考试），且自动限定为自己那几行；
 *       部门/课程级报表需要 MANAGER，直接拒绝而不是"悄悄给全量"。</li>
 * </ul>
 * 拒绝时给的是明确文案，而不是静默返回空表 —— 空表会让人以为"没有数据"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportDatasetService {

    private final TraEnrollmentMapper enrollmentMapper;
    private final TraCourseMapper courseMapper;
    private final TraChapterMapper chapterMapper;
    private final TraMaterialMapper materialMapper;
    private final ExExamRecordMapper examRecordMapper;
    private final QbExamPaperMapper paperMapper;
    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 生成报表时的调用者上下文。**必须在请求线程里取好再传进来** —— 见下方说明。 */
    public record Requester(Long userId, Set<String> roles) {

        public boolean isManager() {
            return roles != null && roles.stream()
                    .anyMatch(r -> r != null && r.toUpperCase(Locale.ROOT).endsWith("MANAGER"));
        }

        public String roleLabel() {
            if (isManager()) {
                return "管理员（全量数据）";
            }
            return "普通用户（仅本人数据）";
        }
    }

    public ReportDataset load(String reportType, Map<String, Object> params, Requester requester) {
        String keyword = str(params, "keyword");
        LocalDateTime now = LocalDateTime.now();
        return switch (reportType) {
            case ReportRequest.TYPE_USER_TRAINING -> userTraining(keyword, requester, now);
            case ReportRequest.TYPE_USER_EXAM -> userExam(keyword, requester, now);
            case ReportRequest.TYPE_DEPT_TRAINING -> deptTraining(keyword, requester, now);
            case ReportRequest.TYPE_COURSE_STATS -> courseStats(keyword, requester, now);
            default -> throw new IllegalArgumentException("不支持的报表类型: " + reportType);
        };
    }

    // ------------------------------------------------------------------ 员工培训报表

    private ReportDataset userTraining(String keyword, Requester requester, LocalDateTime now) {
        LambdaQueryWrapper<TraEnrollment> wrapper = new LambdaQueryWrapper<TraEnrollment>()
                .orderByDesc(TraEnrollment::getEnrollTime);
        if (!requester.isManager()) {
            wrapper.eq(TraEnrollment::getUserId, requester.userId());
        }
        List<TraEnrollment> enrollments = enrollmentMapper.selectList(wrapper);

        Map<Long, TraCourse> courses = courseMap(enrollments.stream()
                .map(TraEnrollment::getCourseId).collect(Collectors.toSet()));
        Map<Long, SysUser> users = userMap(enrollments.stream()
                .map(TraEnrollment::getUserId).collect(Collectors.toSet()));
        Map<Long, String> depts = deptNames(users.values().stream()
                .map(SysUser::getDeptId).collect(Collectors.toSet()));

        List<List<Object>> rows = new ArrayList<>();
        BigDecimal progressSum = BigDecimal.ZERO;
        int completed = 0;
        for (TraEnrollment e : enrollments) {
            TraCourse course = courses.get(e.getCourseId());
            SysUser user = users.get(e.getUserId());
            String courseName = course == null ? ("课程#" + e.getCourseId()) : course.getCourseName();
            String userName = user == null ? ("用户#" + e.getUserId()) : displayName(user);
            String deptName = user == null ? "" : depts.getOrDefault(user.getDeptId(), "");

            if (!matches(keyword, userName, courseName, deptName)) {
                continue;
            }
            BigDecimal progress = e.getProgress() == null ? BigDecimal.ZERO : e.getProgress();
            boolean done = e.getStatus() != null && e.getStatus() == 1;
            progressSum = progressSum.add(progress);
            if (done) {
                completed++;
            }
            rows.add(List.of(
                    userName,
                    deptName,
                    courseName,
                    fmtPercent(progress),
                    done ? "已完成" : "学习中",
                    fmtTime(e.getEnrollTime()),
                    fmtTime(e.getCompleteTime())));
        }

        List<String> summary = new ArrayList<>();
        summary.add("记录数：" + rows.size());
        summary.add("已完成：" + completed + " 条");
        summary.add("平均进度：" + fmtPercent(rows.isEmpty() ? BigDecimal.ZERO
                : progressSum.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP)));
        return new ReportDataset(
                "员工培训报表",
                subtitle(now, requester, keyword),
                List.of("学员", "部门", "课程", "学习进度", "状态", "报名时间", "完成时间"),
                rows,
                summary,
                "数据来源：培训报名表 tra_enrollment（已按当前账号权限限定范围）。");
    }

    // ------------------------------------------------------------------ 考试成绩报表

    private ReportDataset userExam(String keyword, Requester requester, LocalDateTime now) {
        LambdaQueryWrapper<ExExamRecord> wrapper = new LambdaQueryWrapper<ExExamRecord>()
                .orderByDesc(ExExamRecord::getCreateTime);
        if (!requester.isManager()) {
            wrapper.eq(ExExamRecord::getUserId, requester.userId());
        }
        List<ExExamRecord> records = examRecordMapper.selectList(wrapper);

        Map<Long, QbExamPaper> papers = paperMap(records.stream()
                .map(ExExamRecord::getPaperId).collect(Collectors.toSet()));
        Map<Long, TraCourse> courses = courseMap(records.stream()
                .map(ExExamRecord::getCourseId).collect(Collectors.toSet()));
        Map<Long, SysUser> users = userMap(records.stream()
                .map(ExExamRecord::getUserId).collect(Collectors.toSet()));
        Map<Long, String> depts = deptNames(users.values().stream()
                .map(SysUser::getDeptId).collect(Collectors.toSet()));

        List<List<Object>> rows = new ArrayList<>();
        int graded = 0;
        int passed = 0;
        int scoreSum = 0;
        for (ExExamRecord r : records) {
            SysUser user = users.get(r.getUserId());
            QbExamPaper paper = papers.get(r.getPaperId());
            TraCourse course = courses.get(r.getCourseId());
            String userName = user == null ? ("用户#" + r.getUserId()) : displayName(user);
            String paperName = paper == null ? ("试卷#" + r.getPaperId()) : paper.getPaperName();
            String courseName = course == null ? "" : course.getCourseName();
            String deptName = user == null ? "" : depts.getOrDefault(user.getDeptId(), "");

            if (!matches(keyword, userName, paperName, courseName, deptName)) {
                continue;
            }
            boolean isGraded = "GRADED".equals(r.getStatus());
            int total = r.getTotalScore() == null ? 0 : r.getTotalScore();
            if (isGraded) {
                graded++;
                scoreSum += total;
                Integer passScore = paper == null ? null : paper.getPassScore();
                if (passScore != null && total >= passScore) {
                    passed++;
                }
            }
            rows.add(List.of(
                    userName,
                    deptName,
                    paperName,
                    courseName,
                    // 分数传 Integer 而不是 String：Excel 里要能排序、求和、做条件格式。
                    // 进度列刻意保持 "50%" 文本 —— 那是给人看的比例，不是可计算量。
                    valueOrEmpty(r.getObjectiveScore()),
                    valueOrEmpty(r.getSubjectiveScore()),
                    valueOrEmpty(r.getTotalScore()),
                    statusLabel(r.getStatus()),
                    fmtTime(r.getSubmitTime())));
        }

        List<String> summary = new ArrayList<>();
        summary.add("记录数：" + rows.size());
        summary.add("已批改：" + graded + " 条");
        if (graded > 0) {
            summary.add("平均总分：" + BigDecimal.valueOf(scoreSum)
                    .divide(BigDecimal.valueOf(graded), 1, RoundingMode.HALF_UP));
            summary.add("及格：" + passed + " 条（及格线取自各试卷配置）");
        }
        return new ReportDataset(
                "考试成绩报表",
                subtitle(now, requester, keyword),
                List.of("学员", "部门", "试卷", "课程", "客观分", "主观分", "总分", "状态", "提交时间"),
                rows,
                summary,
                "数据来源：考试记录表 ex_exam_record。仅统计状态为 GRADED 的记录计入平均分与及格数。");
    }

    // ------------------------------------------------------------------ 部门培训报表

    private ReportDataset deptTraining(String keyword, Requester requester, LocalDateTime now) {
        requireManager(requester, "部门培训报表");

        List<TraEnrollment> enrollments = enrollmentMapper.selectList(null);
        Map<Long, SysUser> users = userMap(enrollments.stream()
                .map(TraEnrollment::getUserId).collect(Collectors.toSet()));
        Map<Long, String> depts = deptNames(users.values().stream()
                .map(SysUser::getDeptId).collect(Collectors.toSet()));

        // 按部门聚合：报名数 / 参与人数 / 完成数 / 平均进度
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> progressSum = new LinkedHashMap<>();
        Map<String, Set<Long>> participants = new LinkedHashMap<>();
        for (TraEnrollment e : enrollments) {
            SysUser user = users.get(e.getUserId());
            String dept = user == null ? "（未分配部门）" : depts.getOrDefault(user.getDeptId(), "（未分配部门）");
            counts.computeIfAbsent(dept, k -> new int[2]);
            counts.get(dept)[0]++;
            if (e.getStatus() != null && e.getStatus() == 1) {
                counts.get(dept)[1]++;
            }
            BigDecimal p = e.getProgress() == null ? BigDecimal.ZERO : e.getProgress();
            progressSum.merge(dept, p, BigDecimal::add);
            participants.computeIfAbsent(dept, k -> new LinkedHashSet<>()).add(e.getUserId());
        }

        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            String dept = entry.getKey();
            if (!matches(keyword, dept)) {
                continue;
            }
            int total = entry.getValue()[0];
            int done = entry.getValue()[1];
            BigDecimal avg = progressSum.getOrDefault(dept, BigDecimal.ZERO)
                    .divide(BigDecimal.valueOf(Math.max(total, 1)), 2, RoundingMode.HALF_UP);
            rows.add(List.of(
                    dept,
                    participants.getOrDefault(dept, Set.of()).size(),
                    total,
                    done,
                    total - done,
                    fmtPercent(avg),
                    fmtPercent(total == 0 ? BigDecimal.ZERO
                            : BigDecimal.valueOf(done * 100.0 / total))));
        }
        rows.sort(Comparator.comparing(o -> String.valueOf(o.get(0))));

        return new ReportDataset(
                "部门培训报表",
                subtitle(now, requester, keyword),
                List.of("部门", "参与人数", "报名记录数", "已完成", "进行中", "平均进度", "完成率"),
                rows,
                List.of("部门数：" + rows.size(),
                        "报名记录合计：" + counts.values().stream().mapToInt(v -> v[0]).sum()),
                "数据来源：tra_enrollment + sys_user.dept_id。未分配部门的学员归入「（未分配部门）」。");
    }

    // ------------------------------------------------------------------ 课程统计报表

    private ReportDataset courseStats(String keyword, Requester requester, LocalDateTime now) {
        requireManager(requester, "课程统计报表");

        List<TraCourse> courses = courseMapper.selectList(null);
        List<TraEnrollment> enrollments = enrollmentMapper.selectList(null);
        List<TraChapter> chapters = chapterMapper.selectList(null);
        List<TraMaterial> materials = materialMapper.selectList(null);

        Map<Long, Integer> chapterCount = new LinkedHashMap<>();
        for (TraChapter c : chapters) {
            chapterCount.merge(c.getCourseId(), 1, Integer::sum);
        }
        Map<Long, Integer> materialCount = new LinkedHashMap<>();
        for (TraMaterial m : materials) {
            TraChapter chapter = chapters.stream()
                    .filter(c -> c.getId().equals(m.getChapterId())).findFirst().orElse(null);
            if (chapter != null) {
                materialCount.merge(chapter.getCourseId(), 1, Integer::sum);
            }
        }
        Map<Long, List<TraEnrollment>> byCourse = enrollments.stream()
                .filter(e -> e.getCourseId() != null)
                .collect(Collectors.groupingBy(TraEnrollment::getCourseId));

        List<List<Object>> rows = new ArrayList<>();
        for (TraCourse course : courses) {
            if (!matches(keyword, course.getCourseName())) {
                continue;
            }
            List<TraEnrollment> list = byCourse.getOrDefault(course.getId(), List.of());
            int done = (int) list.stream().filter(e -> e.getStatus() != null && e.getStatus() == 1).count();
            BigDecimal avg = list.isEmpty() ? BigDecimal.ZERO
                    : list.stream().map(e -> e.getProgress() == null ? BigDecimal.ZERO : e.getProgress())
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(list.size()), 2, RoundingMode.HALF_UP);
            rows.add(List.of(
                    course.getCourseName(),
                    course.getStatus() != null && course.getStatus() == 1 ? "已发布" : "未发布",
                    chapterCount.getOrDefault(course.getId(), 0),
                    materialCount.getOrDefault(course.getId(), 0),
                    list.size(),
                    done,
                    fmtPercent(avg)));
        }
        // 有正文的资料数单独统计：它直接决定这门课能不能被检索到，是运维最关心的口径
        long searchable = materials.stream()
                .filter(m -> m.getContent() != null && !m.getContent().isBlank())
                .count();

        return new ReportDataset(
                "课程统计报表",
                subtitle(now, requester, keyword),
                List.of("课程", "状态", "章节数", "资料数", "报名人数", "完成人数", "平均进度"),
                rows,
                List.of("课程数：" + rows.size(),
                        "资料总数：" + materials.size() + "（其中有正文、可被检索的：" + searchable + "）"),
                "数据来源：tra_course / tra_chapter / tra_material / tra_enrollment。"
                        + "「资料数」为总量，有正文的资料才能进入检索索引。");
    }

    // ------------------------------------------------------------------ 工具

    /** 非管理员生成本报表是数据越权，明确拒绝而不是给全量或空表。 */
    private void requireManager(Requester requester, String reportName) {
        if (!requester.isManager()) {
            throw new IllegalStateException(reportName + "包含全公司数据，仅管理员可生成。"
                    + "如需个人数据请选择「员工培训报表」或「考试成绩报表」。");
        }
    }

    private String subtitle(LocalDateTime now, Requester requester, String keyword) {
        StringBuilder sb = new StringBuilder();
        sb.append("生成时间：").append(now.format(DT));
        sb.append("　　范围：").append(requester.roleLabel());
        if (keyword != null && !keyword.isBlank()) {
            sb.append("　　筛选：").append(keyword);
        }
        return sb.toString();
    }

    /** 关键字做包含匹配（忽略大小写），多个字段任一命中即可。空关键字视为不过滤。 */
    private boolean matches(String keyword, String... fields) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        for (String f : fields) {
            if (f != null && f.toLowerCase(Locale.ROOT).contains(k)) {
                return true;
            }
        }
        return false;
    }

    private Map<Long, TraCourse> courseMap(Set<Long> ids) {
        Set<Long> clean = clean(ids);
        if (clean.isEmpty()) {
            return Map.of();
        }
        return courseMapper.selectBatchIds(clean).stream()
                .collect(Collectors.toMap(TraCourse::getId, c -> c, (a, b) -> a));
    }

    private Map<Long, QbExamPaper> paperMap(Set<Long> ids) {
        Set<Long> clean = clean(ids);
        if (clean.isEmpty()) {
            return Map.of();
        }
        return paperMapper.selectBatchIds(clean).stream()
                .collect(Collectors.toMap(QbExamPaper::getId, p -> p, (a, b) -> a));
    }

    private Map<Long, SysUser> userMap(Set<Long> ids) {
        Set<Long> clean = clean(ids);
        if (clean.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(clean).stream()
                .collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
    }

    private Map<Long, String> deptNames(Set<Long> ids) {
        Set<Long> clean = clean(ids);
        if (clean.isEmpty()) {
            return Map.of();
        }
        return deptMapper.selectBatchIds(clean).stream()
                .collect(Collectors.toMap(SysDept::getId, d -> d.getDeptName() == null ? "" : d.getDeptName(),
                        (a, b) -> a));
    }

    private Set<Long> clean(Set<Long> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }

    private String displayName(SysUser user) {
        if (user.getRealName() != null && !user.getRealName().isBlank()) {
            return user.getRealName();
        }
        return user.getUsername() == null ? ("用户#" + user.getId()) : user.getUsername();
    }

    private String statusLabel(String status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case "GRADED" -> "已批改";
            case "SUBMITTED" -> "待批改";
            case "IN_PROGRESS" -> "进行中";
            case "PENDING_REVIEW" -> "待复核";
            default -> status;
        };
    }

    private String fmtPercent(BigDecimal value) {
        if (value == null) {
            return "0%";
        }
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private String fmtTime(LocalDateTime time) {
        return time == null ? "" : time.format(DT);
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 数值列：保留数值类型（Excel 才能排序/求和），空值给空串以避免显示成 0。 */
    private Object valueOrEmpty(Number value) {
        return value == null ? "" : value;
    }

    private String str(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
