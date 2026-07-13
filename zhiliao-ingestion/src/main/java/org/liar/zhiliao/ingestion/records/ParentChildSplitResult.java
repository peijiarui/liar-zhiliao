package org.liar.zhiliao.ingestion.records;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 父子文档分割结果。
 * parentSegments — 2048 token parent chunks（写 PG）
 * childSegments  — 512 token child chunks（写 PG + Milvus）
 * childParentMapping — child 在 List 中的 index → parent 在 List 中的 index
 */
public record ParentChildSplitResult(
        List<TextSegment> parentSegments,
        List<TextSegment> childSegments,
        int[] childParentMapping
) {}
