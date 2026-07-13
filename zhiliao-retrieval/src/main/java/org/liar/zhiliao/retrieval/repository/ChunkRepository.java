package org.liar.zhiliao.retrieval.repository;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
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

    /** BM25 全文搜索 + 部门可见性过滤 */
    public List<SparseSearchResult> searchBm25WithDeptFilter(String queryText, int topK, List<Long> visibleDeptIds) {
        if (visibleDeptIds == null || visibleDeptIds.isEmpty()) {
            return List.of();
        }

        String placeholders = String.join(",", Collections.nCopies(visibleDeptIds.size(), "?"));
        String sql = """
            SELECT c.id, c.content, ts_rank(to_tsvector('zh', c.content), plainto_tsquery('zh', ?)) AS score
            FROM zl_chunk c
            JOIN zl_document d ON c.doc_id = d.id
            JOIN zl_kb_dept_visibility v ON d.kb_id = v.kb_id
            WHERE c.chunk_type = 'child'
              AND to_tsvector('zh', c.content) @@ plainto_tsquery('zh', ?)
              AND v.dept_id IN (%s)
            ORDER BY score DESC
            LIMIT ?
            """.formatted(placeholders);

        Object[] params = new Object[3 + visibleDeptIds.size()];
        params[0] = queryText;
        params[1] = queryText;
        int idx = 2;
        for (Long deptId : visibleDeptIds) {
            params[idx++] = deptId;
        }
        params[idx] = topK;

        return jdbcTemplate.query(sql,
                new DataClassRowMapper<>(SparseSearchResult.class),
                params);
    }

    /** 根据 ID 查询 chunk 内容（用于 parent 内容替换） */
    public String findContentById(Long id) {
        String sql = "SELECT content FROM zl_chunk WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }
}
