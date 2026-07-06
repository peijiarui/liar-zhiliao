//package org.liar.zhiliao.retrieval.config;
//
//import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
//import io.milvus.param.IndexType;
//import io.milvus.param.MetricType;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//
///**
// * Milvus 配置 - 创建 Milvus collection（如不存在）并提供 {@link MilvusEmbeddingStore} Bean。
// * <p>
// * 该 Bean 会被注入 {@code zhiliao-retrieval} 模块的 MilvusVectorStore 以及
// * {@code zhiliao-ingestion} 模块的写入流程和 {@code zhiliao-chat} 的检索流程。
// *
// * @author Pei
// * @since 2026-07-06
// */
////@Configuration
//public class MilvusConfig {
//
//    @Value("${zhiliao.milvus.host:localhost}")
//    private String host;
//
//    @Value("${zhiliao.milvus.port:19530}")
//    private int port;
//
//    @Value("${zhiliao.milvus.collection-name:zhiliao_chunks}")
//    private String collectionName;
//
//    @Value("${zhiliao.milvus.dimension:1024}")
//    private int dimension;
//
////    @Bean
//    public MilvusEmbeddingStore milvusEmbeddingStore() {
//        return MilvusEmbeddingStore.builder()
//                .host(host)
//                .port(port)
//                .collectionName(collectionName)
//                .dimension(dimension)
//                .indexType(IndexType.HNSW)
//                .metricType(MetricType.COSINE)
//                .build();
//    }
//}
