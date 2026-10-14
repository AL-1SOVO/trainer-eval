package com.trainereval.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切块服务的边界测试。
 * <p>
 * 这里锁住的是 {@link ChunkingService} 类注释里<b>承诺过的行为</b>，而不是当前实现的快照。
 * 写这组用例的当天就靠 {@link #paragraphBoundariesArePreserved()} 抓出一个真实缺陷：
 * 归一化用 {@code \s+ -> " "} 把段落边界一起折叠了，导致「段落优先」分支成了死代码。
 */
class ChunkingServiceTest {

    /**
     * split / previewChunks 是纯函数，四个 Mapper 全程不参与；
     * 这里显式传 null，反过来也证明了切块逻辑确实没有偷偷依赖数据库。
     */
    private final ChunkingService service = new ChunkingService(null, null, null, null);

    @Test
    @DisplayName("空输入与纯空白返回空列表，不产生空块")
    void blankInputProducesNothing() {
        assertThat(service.previewChunks(null)).isEmpty();
        assertThat(service.previewChunks("")).isEmpty();
        assertThat(service.previewChunks("   \n\t  ")).isEmpty();
    }

    @Test
    @DisplayName("RICH_TEXT 素材的 HTML 标签必须被剥掉，否则污染全文索引并浪费 token")
    void htmlTagsAreStripped() {
        List<String> chunks = service.previewChunks("<p>数据保护三大原则</p><br/>最小权限原则详解");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).doesNotContain("<").doesNotContain(">");
        assertThat(chunks.get(0)).contains("数据保护三大原则").contains("最小权限原则详解");
    }

    @Test
    @DisplayName("常见 HTML 实体被还原成字符")
    void htmlEntitiesAreDecoded() {
        assertThat(service.previewChunks("A&nbsp;B&amp;C&lt;D&gt;E").get(0))
                .isEqualTo("A B&C<D>E");
    }

    @Test
    @DisplayName("段落边界必须保留：两段各 300 字切成 2 块，而不是并成 1 块 601 字")
    void paragraphBoundariesArePreserved() {
        String first = "甲".repeat(300);
        String second = "乙".repeat(300);

        List<String> chunks = service.previewChunks(first + "\n\n" + second);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).isEqualTo(first);
        assertThat(chunks.get(1)).isEqualTo(second);
    }

    @Test
    @DisplayName("超长段落按句末标点切分，并保留 80 字重叠以保证跨块论述连续")
    void longParagraphSplitsOnSentenceBoundaryWithOverlap() {
        String s1 = "一".repeat(199) + "。";
        String s2 = "二".repeat(199) + "。";
        String s3 = "三".repeat(199) + "。";

        List<String> chunks = service.previewChunks(s1 + s2 + s3);

        assertThat(chunks).hasSize(2);
        String expectedOverlap = chunks.get(0).substring(chunks.get(0).length() - 80);
        assertThat(chunks.get(1)).startsWith(expectedOverlap);
        assertThat(chunks.get(1)).contains("三");
    }

    @Test
    @DisplayName("过短的尾块并入前一块，不产生无意义碎片")
    void shortTrailingFragmentIsMergedIntoPreviousChunk() {
        String first = "甲".repeat(399);
        String second = "乙".repeat(399);

        List<String> chunks = service.previewChunks(first + "\n\n" + second + "\n\n丙丙");

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1)).endsWith("丙丙");
    }

    @Test
    @DisplayName("单段短文本只产生 1 块，保持原样")
    void singleShortParagraphStaysIntact() {
        String text = "最小权限原则要求只授予完成任务所必需的最小权限。";
        assertThat(service.previewChunks(text)).containsExactly(text);
    }
}
