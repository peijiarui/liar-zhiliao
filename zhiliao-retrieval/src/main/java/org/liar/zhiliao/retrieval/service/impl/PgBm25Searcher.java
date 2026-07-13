package org.liar.zhiliao.retrieval.service.impl;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;
import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.liar.zhiliao.retrieval.service.SparseSearcher;

import java.util.List;

@RequiredArgsConstructor
public class PgBm25Searcher implements SparseSearcher {

    private final ChunkRepository chunkRepository;

    @Override
    public List<SparseSearchResult> search(String query, int topK) {
        return chunkRepository.searchBm25(query, topK);
    }
}
