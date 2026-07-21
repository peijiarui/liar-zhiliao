package org.liar.zhiliao.retrieval.records;

/**
 * 检索结果，包含 chunkId、content、parentId 和 rrfScore
 */
public record RankedChunk(
        Long chunkId,
        String content,
        Long parentId,
        double rrfScore
) {}
