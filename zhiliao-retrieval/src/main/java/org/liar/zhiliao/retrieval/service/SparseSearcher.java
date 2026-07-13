package org.liar.zhiliao.retrieval.service;

import org.liar.zhiliao.retrieval.records.SparseSearchResult;

import java.util.List;

/**
 * 稀疏检索接口。
 * 当前：PgBm25Searcher — PG tsvector BM25，零额外依赖
 * 未来：EsBm25Searcher — Elasticsearch BM25，部署 ES 后切换
 */
public interface SparseSearcher {
    List<SparseSearchResult> search(String query, int topK);
}
