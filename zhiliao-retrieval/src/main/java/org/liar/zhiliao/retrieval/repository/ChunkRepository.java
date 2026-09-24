package org.liar.zhiliao.retrieval.repository;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.retrieval.records.RetrievalPrincipal;
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

    /** 按 memoryId 解析会话归属用户（join sys_user 取实时 role/deptId）；会话或用户不存在返回 null */
    public RetrievalPrincipal findPrincipalByMemoryId(String memoryId) {
        String sql = """
            SELECT u.id AS userId, u.role AS role, u.dept_id AS deptId
            FROM zl_conversation c
            JOIN sys_user u ON u.id = c.user_id
            WHERE c.memory_id = ?
            LIMIT 1
            """;
        List<RetrievalPrincipal> rows = jdbcTemplate.query(
                sql, new DataClassRowMapper<>(RetrievalPrincipal.class), memoryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 多个部门可见的知识库 ID 集合（来自 zl_kb_dept_visibility，dept_id IN 过滤） */
    public List<Long> findVisibleKbIds(Long... deptIds) {
        if (deptIds == null || deptIds.length == 0) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(deptIds.length, "?"));
        String sql = "SELECT distinct kb_id FROM zl_kb_dept_visibility WHERE dept_id IN (%s)"
                .formatted(placeholders);
        // Long[] 直接作为 Object[] varargs 传递，与 IN 占位符数量一致；强转 (Object) 会把数组折叠成单个参数
        return jdbcTemplate.queryForList(sql, Long.class, deptIds);
    }
}
