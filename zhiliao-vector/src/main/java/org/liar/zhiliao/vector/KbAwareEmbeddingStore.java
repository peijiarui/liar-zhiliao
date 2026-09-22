package org.liar.zhiliao.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * 带知识库语义的向量存储。
 * <p>kbId 是顶层 Int64 分区键字段，不进入 metadata JSON。</p>
 */
public interface KbAwareEmbeddingStore extends EmbeddingStore<TextSegment> {

    /**
     * 写入单个文档的切片（一个文档只有一个 kbId）。
     *
     * @param kbId 知识库 ID，落为 kb_id 分区键字段
     * @return 与 embeddings 一一对应的向量 ID
     */
    List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, long kbId);

    /**
     * 按 kbId 范围检索。
     *
     * @param kbIds null 表示不过滤（admin，扫全部分区）；单值 → {@code kb_id == x}；多值 → {@code kb_id in [...]}
     */
    EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request, List<Long> kbIds);
}
