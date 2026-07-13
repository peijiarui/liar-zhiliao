package org.liar.zhiliao.retrieval.service.impl;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.retrieval.records.RankedChunk;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;
import org.liar.zhiliao.retrieval.service.Reranker;

import java.util.*;

@Slf4j
public class RrfReranker implements Reranker {

    private static final int K = 60;  // RRF 常数

    @Override
    public List<RankedChunk> rerank(
            String query,
            List<EmbeddingMatch<TextSegment>> denseResults,
            List<SparseSearchResult> sparseResults,
            int topK) {

        // 收集所有候选的 RRF 得分
        Map<Long, RrfEntry> scoreMap = new HashMap<>();

        // 处理稠密结果：从 TextSegment.metadata 取 chunkId
        for (int rank = 0; rank < denseResults.size(); rank++) {
            EmbeddingMatch<TextSegment> match = denseResults.get(rank);
            Metadata meta = match.embedded().metadata();
            Long chunkId = Long.parseLong(meta.getString("chunkId"));
            String parentIdStr = meta.getString("parentId");

            double score = 1.0 / (K + rank + 1);   // RRF: 1/(k+rank), rank 从 0 开始
            RrfEntry entry = scoreMap.getOrDefault(chunkId,
                    new RrfEntry(0, match.embedded().text(),
                            parentIdStr.isEmpty() ? null : Long.parseLong(parentIdStr)));
            entry.score += score;
            scoreMap.put(chunkId, entry);
        }

        // 处理稀疏结果：SparseSearchResult 本身带 id
        for (int rank = 0; rank < sparseResults.size(); rank++) {
            SparseSearchResult result = sparseResults.get(rank);
            double score = 1.0 / (K + rank + 1);
            RrfEntry entry = scoreMap.getOrDefault(result.id(),
                    new RrfEntry(0, result.content(), null));
            entry.score += score;
            scoreMap.put(result.id(), entry);
        }

        // 按 RRF 分数降序取 topK
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, RrfEntry>comparingByValue(
                        Comparator.comparingDouble(e -> e.score)).reversed())
                .limit(topK)
                .map(e -> new RankedChunk(e.getKey(), e.getValue().content,
                        e.getValue().parentId, e.getValue().score))
                .toList();
    }

    private static class RrfEntry {
        double score;
        String content;
        Long parentId;

        RrfEntry(double score, String content, Long parentId) {
            this.score = score;
            this.content = content;
            this.parentId = parentId;
        }
    }
}
