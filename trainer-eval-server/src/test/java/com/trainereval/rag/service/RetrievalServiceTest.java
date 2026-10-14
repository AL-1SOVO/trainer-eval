package com.trainereval.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.entity.RagChunk;
import com.trainereval.rag.entity.RagRetrievalTrace;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.mapper.RagRetrievalTraceMapper;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
import com.trainereval.training.mapper.TraCourseMapper;
import com.trainereval.training.mapper.TraMaterialMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 混合检索的核心行为测试：RRF 融合、规则重排的多样性上限、课程过滤、查询清洗、轨迹留痕。
 * <p>
 * 这些是「面试时会被追问、且答错就说明没做过」的地方，所以优先给它们上锁。
 * 全部是纯单元测试：Mapper 用 Mockito 打桩，不需要数据库、Redis 或 MimirQ。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    private static final String QUERY = "数据保护三大原则";
    private static final String TITLE = "数据保护三大原则";
    private static final String CONTENT =
            "数据保护三大原则包括最小权限原则、纵深防御原则与安全审计原则，它们是数据安全体系的核心基石，"
                    + "需要同时在制度层面与技术层面落地，并且要能被审计与追溯。";

    @Mock
    private RagChunkMapper chunkMapper;
    @Mock
    private RagRetrievalTraceMapper traceMapper;
    @Mock
    private TraMaterialMapper materialMapper;
    @Mock
    private TraChapterMapper chapterMapper;
    @Mock
    private TraCourseMapper courseMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RetrievalService service;

    @BeforeEach
    void setUp() {
        service = new RetrievalService(chunkMapper, traceMapper, materialMapper, chapterMapper, courseMapper,
                objectMapper);
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("RRF 融合：两个通道都命中的块必须排在只被单通道命中的块之前")
    void rrfRanksDualChannelHitAboveSingleChannelHit() {
        RagChunk dualChannel = chunk(1L, 11L, 0, TITLE, CONTENT);
        RagChunk fulltextOnly = chunk(2L, 12L, 0, TITLE, CONTENT);

        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(List.of(fulltextOnly, dualChannel));
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of(dualChannel));
        stubFlatCatalog();

        List<RetrievalHit> hits = service.retrieve(7L, "unit-test", QUERY, null, 5);

        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).chunkId()).isEqualTo(1L);
        assertThat(hits.get(0).channels()).isEqualTo("FT+LIKE");
        assertThat(hits.get(1).chunkId()).isEqualTo(2L);
        assertThat(hits.get(1).channels()).isEqualTo("FT");
        // RRF 只吃排名、不吃分数：1/(60+2) + 1/(60+1) 必须严格大于单通道的 1/(60+1)
        assertThat(hits.get(0).score()).isGreaterThan(hits.get(1).score());
    }

    @Test
    @DisplayName("引用编号形如 M{materialId}#{chunkIndex}，保证每个结论可回溯")
    void citationIsStableAndTraceable() {
        RagChunk only = chunk(9L, 11L, 3, TITLE, CONTENT);
        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(List.of(only));
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of(only));
        stubFlatCatalog();

        List<RetrievalHit> hits = service.retrieve(7L, "unit-test", QUERY, null, 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).citation()).isEqualTo("M11#3");
        assertThat(hits.get(0).chunkIndex()).isEqualTo(3);
    }

    @Test
    @DisplayName("同一资料最多贡献 2 条，避免单一资料霸榜")
    void atMostTwoChunksPerMaterial() {
        List<RagChunk> allFromSameMaterial = List.of(
                chunk(1L, 11L, 0, TITLE, CONTENT),
                chunk(2L, 11L, 1, TITLE, CONTENT),
                chunk(3L, 11L, 2, TITLE, CONTENT));

        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(allFromSameMaterial);
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of());
        stubFlatCatalog();

        List<RetrievalHit> hits = service.retrieve(7L, "unit-test", QUERY, null, 5);

        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.materialId()).isEqualTo(11L));
    }

    @Test
    @DisplayName("topK 上限生效")
    void topKLimitIsRespected() {
        RagChunk dualChannel = chunk(1L, 11L, 0, TITLE, CONTENT);
        RagChunk fulltextOnly = chunk(2L, 12L, 0, TITLE, CONTENT);

        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(List.of(dualChannel, fulltextOnly));
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of(dualChannel));
        stubFlatCatalog();

        assertThat(service.retrieve(7L, "unit-test", QUERY, null, 1)).hasSize(1);
    }

    @Test
    @DisplayName("指定 courseId 时，其它课程的块必须被过滤掉")
    void courseIdFiltersOutOtherCourses() {
        RagChunk inCourse = chunk(1L, 11L, 0, TITLE, CONTENT);
        RagChunk otherCourse = chunk(2L, 12L, 0, TITLE, CONTENT);

        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(List.of(inCourse, otherCourse));
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of());

        when(materialMapper.selectBatchIds(any())).thenReturn(List.of(
                material(11L, TITLE, 10L), material(12L, TITLE, 20L)));
        when(chapterMapper.selectBatchIds(any())).thenReturn(List.of(
                chapter(10L, 100L, "第一章 数据安全"), chapter(20L, 200L, "第二章 终端安全")));
        when(courseMapper.selectBatchIds(any())).thenReturn(List.of(
                course(100L, "数据安全基础")));

        List<RetrievalHit> hits = service.retrieve(7L, "unit-test", QUERY, 100L, 5);

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).materialId()).isEqualTo(11L);
        assertThat(hits.get(0).courseId()).isEqualTo(100L);
        assertThat(hits.get(0).courseName()).isEqualTo("数据安全基础");
    }

    @Test
    @DisplayName("清洗后短于 2 字的查询走不了 ngram 通道，必须靠子串通道兜住")
    void singleCharQueryFallsBackToSubstringChannelOnly() {
        RagChunk single = chunk(1L, 11L, 0, "权限管理", "权限最小化是安全基线要求，任何岗位都不应长期持有超出职责范围的权限。");

        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of(single));
        stubFlatCatalog();

        List<RetrievalHit> hits = service.retrieve(7L, "unit-test", "权", null, 5);

        verify(chunkMapper, never()).searchFulltext(anyString(), anyInt());
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).channels()).isEqualTo("LIKE");
    }

    @Test
    @DisplayName("查询清洗：去标点、去疑问词，保留实词顺序")
    void normalizeQueryStripsStopwordsAndPunctuation() {
        assertThat(service.normalizeQuery("请问  如何   实现   数据加密！！！")).isEqualTo("实现 数据加密");
        assertThat(service.normalizeQuery(null)).isEmpty();
        assertThat(service.normalizeQuery("？？？")).isEmpty();
        assertThat(service.normalizeQuery("   ")).isEmpty();
    }

    @Test
    @DisplayName("查询被清洗成空时直接返回空、不打数据库，但轨迹仍要落库")
    void blankQueryShortCircuitsButStillWritesTrace() {
        List<RetrievalHit> hits = service.retrieve(7L, "unit-test", "请问一下", null, 5);

        assertThat(hits).isEmpty();
        verify(chunkMapper, never()).searchFulltext(anyString(), anyInt());
        verify(chunkMapper, never()).searchLike(anyString(), anyInt());
        // 类注释写的是「检索不到结果恰恰是最需要被看到的失败」——空结果也必须留痕
        verify(traceMapper).insert(any(RagRetrievalTrace.class));
    }

    @Test
    @DisplayName("门面调用时 writeTrace=false，不得重复写轨迹")
    void traceWritingCanBeSuppressed() {
        RagChunk dualChannel = chunk(1L, 11L, 0, TITLE, CONTENT);
        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(List.of(dualChannel));
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of(dualChannel));
        stubFlatCatalog();

        service.retrieve(7L, "unit-test", QUERY, null, 5, false);

        verify(traceMapper, never()).insert(any(RagRetrievalTrace.class));
    }

    @Test
    @DisplayName("轨迹必须落成合法 JSON 且字段齐全（该列是 JSON 类型，写坏会整条丢失）")
    void tracePayloadIsValidJson() throws Exception {
        RagChunk dualChannel = chunk(1L, 11L, 0, TITLE, CONTENT);
        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(List.of(dualChannel));
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of(dualChannel));
        stubFlatCatalog();

        service.retrieve(7L, "unit-test", QUERY, null, 5);

        ArgumentCaptor<RagRetrievalTrace> captor = ArgumentCaptor.forClass(RagRetrievalTrace.class);
        verify(traceMapper).insert(captor.capture());
        RagRetrievalTrace trace = captor.getValue();

        assertThat(trace.getUserId()).isEqualTo(7L);
        assertThat(trace.getSessionId()).isEqualTo("unit-test");
        assertThat(trace.getQueryText()).isEqualTo(QUERY);
        assertThat(trace.getStrategy()).isEqualTo(RagRetrievalTrace.STRATEGY_HYBRID);
        assertThat(trace.getCandidateCount()).isEqualTo(1);
        assertThat(trace.getHitCount()).isEqualTo(1);
        assertThat(trace.getLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0);

        JsonNode topHits = objectMapper.readTree(trace.getTopHits());
        assertThat(topHits.isArray()).isTrue();
        assertThat(topHits).hasSize(1);
        assertThat(topHits.get(0).get("citation").asText()).isEqualTo("M11#0");
        assertThat(topHits.get(0).get("channels").asText()).isEqualTo("FT+LIKE");
    }

    @Test
    @DisplayName("所有通道都无命中时返回空列表，不抛异常")
    void emptyChannelsProduceEmptyResult() {
        when(chunkMapper.searchFulltext(anyString(), anyInt())).thenReturn(List.of());
        when(chunkMapper.searchLike(anyString(), anyInt())).thenReturn(List.of());

        assertThat(service.retrieve(7L, "unit-test", QUERY, null, 5)).isEmpty();
    }

    // ---------------------------------------------------------------- 辅助

    private static RagChunk chunk(long id, long materialId, int chunkIndex, String title, String content) {
        RagChunk c = new RagChunk();
        c.setId(id);
        c.setMaterialId(materialId);
        c.setChunkIndex(chunkIndex);
        c.setTitle(title);
        c.setContent(content);
        c.setCharLen(content.length());
        return c;
    }

    private static TraMaterial material(long id, String title, Long chapterId) {
        TraMaterial m = new TraMaterial();
        m.setId(id);
        m.setTitle(title);
        m.setChapterId(chapterId);
        return m;
    }

    private static TraChapter chapter(long id, long courseId, String title) {
        TraChapter c = new TraChapter();
        c.setId(id);
        c.setCourseId(courseId);
        c.setTitle(title);
        return c;
    }

    private static TraCourse course(long id, String name) {
        TraCourse c = new TraCourse();
        c.setId(id);
        c.setCourseName(name);
        return c;
    }

    /** 资料 11 / 12 同挂章节 10（课程 100）—— 多数用例共享的目录结构。 */
    private void stubFlatCatalog() {
        lenient().when(materialMapper.selectBatchIds(any())).thenReturn(List.of(
                material(11L, TITLE, 10L), material(12L, TITLE, 10L)));
        lenient().when(chapterMapper.selectBatchIds(any())).thenReturn(List.of(
                chapter(10L, 100L, "第一章 数据安全")));
        lenient().when(courseMapper.selectBatchIds(any())).thenReturn(List.of(
                course(100L, "数据安全基础")));
    }
}
