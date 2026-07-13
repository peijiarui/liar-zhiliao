package org.liar.zhiliao.retrieval.records;

public record RankedChunk(
        Long chunkId,
        String content,
        Long parentId,
        double rrfScore
) {}
