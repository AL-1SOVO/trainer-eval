package com.trainereval.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.trainereval.rag.entity.RagChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagChunkMapper extends BaseMapper<RagChunk> {

    /**
     * 通道 A：ngram 全文检索（中文按 2-gram 切分，等价于轻量 BM25 相关度）。
     * 用 NATURAL LANGUAGE MODE 才能拿到相关度分值用于排序与融合。
     */
    @Select("""
            SELECT id, material_id, course_id, chapter_id, chunk_index, title, content, char_len,
                   MATCH(title, content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS ft_score
            FROM rag_chunk
            WHERE MATCH(title, content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY ft_score DESC
            LIMIT #{limit}
            """)
    List<RagChunk> searchFulltext(@Param("query") String query, @Param("limit") int limit);

    /**
     * 通道 B：子串匹配。用于两类全文检索覆盖不到的场景 ——
     * ① 查询短于 ngram_token_size(2) ② 精确短语（如"最小权限原则"）需要字面命中。
     */
    @Select("""
            SELECT id, material_id, course_id, chapter_id, chunk_index, title, content, char_len
            FROM rag_chunk
            WHERE content LIKE CONCAT('%', #{keyword}, '%')
               OR title   LIKE CONCAT('%', #{keyword}, '%')
            ORDER BY char_len ASC
            LIMIT #{limit}
            """)
    List<RagChunk> searchLike(@Param("keyword") String keyword, @Param("limit") int limit);

    @Delete("DELETE FROM rag_chunk WHERE material_id = #{materialId}")
    int deleteByMaterialId(@Param("materialId") Long materialId);

    /**
     * 按顺序取某条资料的全部切块正文，用于推送给外部知识库（MimirQ）。
     * 显式 ORDER BY chunk_index：切块顺序决定 page_number 与引用序号，顺序错了引用就会错位。
     */
    @Select("SELECT content FROM rag_chunk WHERE material_id = #{materialId} ORDER BY chunk_index ASC")
    List<String> listContentsByMaterial(@Param("materialId") Long materialId);

    /** 统计每个资料已切块数量，用于重建时判断是否需要重建。 */
    @Select("SELECT material_id, COUNT(*) AS n FROM rag_chunk GROUP BY material_id")
    List<java.util.Map<String, Object>> countGroupByMaterial();
}
