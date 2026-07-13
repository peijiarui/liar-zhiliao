package org.liar.zhiliao.retrieval.repository;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    /** BM25 全文搜索，按 ts_rank 降序取 topK */
    public List<SparseSearchResult> searchBm25(String queryText, int topK) {
        String sql = """
            SELECT id, content, ts_rank(to_tsvector('zh', content), plainto_tsquery('zh', ?)) AS score
            FROM zl_chunk
            WHERE chunk_type = 'child'
              AND to_tsvector('zh', content) @@ plainto_tsquery('zh', ?)
            ORDER BY score DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql,
                new DataClassRowMapper<>(SparseSearchResult.class),
                queryText, queryText, topK);
    }

    /** 根据 ID 查询 chunk 内容（用于 parent 内容替换） */
    public String findContentById(Long id) {
        String sql = "SELECT content FROM zl_chunk WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }
}
