package com.trainereval.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.agent.service.AgentReflection.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具结果反思的判定表测试。
 * <p>
 * 这个类最容易出错的不是逻辑分支，而是<b>输入形态</b>：Spring AI 会把工具返回值按 JSON 字符串
 * 再编码一次，到达这里时是带转义的形态。类注释里明确写了「这里踩过一次」，
 * 所以 {@link #escapedJsonPayloadIsDetected()} 就是这个坑的回归用例。
 */
class AgentReflectionTest {

    private static final String SEARCH_TOOL = "searchCourseMaterials";
    private static final String RECORDS_TOOL = "getExamRecords";

    private AgentReflection reflection;

    @BeforeEach
    void setUp() {
        reflection = new AgentReflection(new ObjectMapper());
        ReflectionTestUtils.setField(reflection, "maxToolRetries", 2);
    }

    // ---------------------------------------------------------------- 输入形态

    @Test
    @DisplayName("空字符串结果一律判为「没拿到有用信息」")
    void blankResultIsProblematicAndEmpty() {
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "   ");

        assertThat(outcome.problematic()).isTrue();
        assertThat(outcome.empty()).isTrue();
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.reason()).isEqualTo("工具返回为空");
    }

    @Test
    @DisplayName("回归：Spring AI 二次编码的转义 JSON 也必须能识别出空结果")
    void escapedJsonPayloadIsDetected() {
        // 真实到达的形态是 "\"<data>{\\\"materials\\\":[]}</data>\""：
        // 直接匹配 "materials":[] 永远匹配不上，必须先按 JSON 字符串还原一次转义。
        String escaped = "\"<data>{\\\"materials\\\":[]}</data>\"";

        Outcome outcome = reflection.inspect(SEARCH_TOOL, escaped);

        assertThat(outcome.problematic()).isTrue();
        assertThat(outcome.empty()).isTrue();
        assertThat(outcome.retryable()).isTrue();
        assertThat(outcome.reason()).isEqualTo("资料检索无结果");
    }

    @Test
    @DisplayName("未二次编码的原始 JSON 同样能识别（两条路径都要通）")
    void plainJsonPayloadIsDetected() {
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "<data>{\"materials\":[]}</data>");

        assertThat(outcome.empty()).isTrue();
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    @DisplayName("空白无关紧要：[] 与 [ ] 必须等价")
    void whitespaceInsideEmptyCollectionIsTolerated() {
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "<data>{ \"materials\" : [ ] }</data>");

        assertThat(outcome.empty()).isTrue();
        assertThat(outcome.reason()).isEqualTo("资料检索无结果");
    }

    // ---------------------------------------------------------------- 判定表

    @Test
    @DisplayName("检索类工具的空结果可重试：大概率是措辞问题，换词能命中")
    void emptySearchResultIsRetryable() {
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "<data>{\"materials\":[]}</data>");

        assertThat(outcome.retryable()).isTrue();
        assertThat(reflection.retryNudge(SEARCH_TOOL, outcome, 1)).isNotNull();
    }

    @Test
    @DisplayName("成绩类工具的空结果不可重试：数据本来就没有，重试只是烧 token")
    void emptyRecordsAreNotRetryable() {
        Outcome outcome = reflection.inspect(RECORDS_TOOL, "<data>{\"records\":[]}</data>");

        assertThat(outcome.problematic()).isTrue();
        assertThat(outcome.empty()).isTrue();
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.reason()).isEqualTo("暂无考试成绩记录");
        assertThat(reflection.retryNudge(RECORDS_TOOL, outcome, 1)).isNull();
    }

    @Test
    @DisplayName("错题 / 已报名课程 / 资料无正文这三类空结果同样不可重试")
    void otherEmptyCollectionsAreNotRetryable() {
        assertThat(reflection.inspect(RECORDS_TOOL, "<data>{\"wrongAnswers\":[]}</data>").reason())
                .isEqualTo("暂无错题记录");
        assertThat(reflection.inspect(RECORDS_TOOL, "<data>{\"courses\":[]}</data>").reason())
                .isEqualTo("暂无已报名课程");
        assertThat(reflection.inspect(SEARCH_TOOL, "<data>{\"chunks\":[]}</data>").reason())
                .isEqualTo("该资料没有可用内容");

        assertThat(reflection.inspect(RECORDS_TOOL, "<data>{\"courses\":[]}</data>").retryable()).isFalse();
        assertThat(reflection.inspect(SEARCH_TOOL, "<data>{\"chunks\":[]}</data>").retryable()).isFalse();
    }

    @Test
    @DisplayName("业务错误与空结果必须区分开：错误不等于「没有数据」")
    void errorIsProblematicButNotEmpty() {
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "<data>{\"error\":\"课程不存在\"}</data>");

        assertThat(outcome.problematic()).isTrue();
        assertThat(outcome.empty()).isFalse();
        assertThat(outcome.reason()).isEqualTo("工具返回错误");
    }

    @Test
    @DisplayName("拿到内容时判为正常，不做任何干预")
    void nonEmptyResultIsFine() {
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "<data>{\"materials\":[{\"id\":3}]}</data>");

        assertThat(outcome.problematic()).isFalse();
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.reason()).isNull();
    }

    // ---------------------------------------------------------------- 重试预算

    @Test
    @DisplayName("重试指引受 agent.tutor.max-tool-retries 约束")
    void retryNudgeRespectsConfiguredBudget() {
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "<data>{\"materials\":[]}</data>");

        assertThat(reflection.maxRetries()).isEqualTo(2);
        assertThat(reflection.retryNudge(SEARCH_TOOL, outcome, 1)).isNotNull();
        assertThat(reflection.retryNudge(SEARCH_TOOL, outcome, 2)).isNotNull();
        assertThat(reflection.retryNudge(SEARCH_TOOL, outcome, 3)).isNull();
    }

    @Test
    @DisplayName("配置成负数时重试预算收敛到 0，而不是放开成无限")
    void negativeRetryBudgetClampsToZero() {
        ReflectionTestUtils.setField(reflection, "maxToolRetries", -5);
        Outcome outcome = reflection.inspect(SEARCH_TOOL, "<data>{\"materials\":[]}</data>");

        assertThat(reflection.maxRetries()).isZero();
        assertThat(reflection.retryNudge(SEARCH_TOOL, outcome, 1)).isNull();
    }

    @Test
    @DisplayName("预算用尽后的收尾指引要求如实说明，而不是硬答")
    void exhaustedNudgeDemandsHonesty() {
        Outcome searchOutcome = reflection.inspect(SEARCH_TOOL, "<data>{\"materials\":[]}</data>");
        assertThat(reflection.exhaustedNudge(SEARCH_TOOL, searchOutcome))
                .contains("未找到相关内容")
                .contains("禁止编造");

        Outcome recordsOutcome = reflection.inspect(RECORDS_TOOL, "<data>{\"records\":[]}</data>");
        assertThat(reflection.exhaustedNudge(RECORDS_TOOL, recordsOutcome))
                .contains("暂无考试成绩记录")
                .contains("不要猜测");
    }
}
