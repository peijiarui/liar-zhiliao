package org.liar.zhiliao.retrieval.service;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * Vector store interface for writing document chunks into vector database.
 * <p>
 * MVP: {@code MilvusVectorStore} (Milvus)
 * Future: {@code HybridVectorStore} (Milvus + ES for dual writing)
 *
 * @author Pei
 * @since 2026-07-06
 */
public interface VectorStore {

    /**
     * 存储单个向量及其对应的文本段。
     *
     * @param vector  嵌入向量
     * @param segment 文本段
     * @return 向量数据库中生成的 ID
     */
    String store(float[] vector, TextSegment segment);

    /**
     * 批量存储向量及其对应的文本段。
     *
     * @param vectors  嵌入向量列表
     * @param segments 文本段列表
     * @return 向量数据库中生成的 ID 列表
     */
    List<String> storeBatch(List<float[]> vectors, List<TextSegment> segments);
}
