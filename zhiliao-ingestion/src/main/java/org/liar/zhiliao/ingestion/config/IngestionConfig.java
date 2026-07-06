package org.liar.zhiliao.ingestion.config;

import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 文档摄入配置 - 负责文档加载、分割、向量化并存入向量存储。
 * <p>
 * 职责归属 {@code zhiliao-ingestion} 模块，与 {@code zhiliao-retrieval} 的检索职责分离。
 * 本模块拥有 {@link EmbeddingStore} bean 的生命周期，retrieval 模块只消费它来构建检索器。
 *
 * @author Pei
 * @since 2026-07-06
 */
@Configuration
@AllArgsConstructor
public class IngestionConfig {

    /**
     * Langchain4j自动注入的向量模型，如果在yaml中配置的话就默认使用
     */
    private final EmbeddingModel embeddingModel;

    /**
     * Langchain4j自动注入的向量存储对象，如果在yaml中配置的话就默认使用
     */
    private final RedisEmbeddingStore embeddingStore;

    /**
     * 创建向量存储对象，用于初始化向量数据库
     * <p>
     * 首次启动时从 classpath:docs/ 加载预置文档，经过分割、embedding 后存入内存向量存储。
     * 后续可替换为 Milvus 等持久化向量数据库。
     *
     * @return 向量存储对象
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        // 1. 加载文件进内存
        List<Document> docs = ClassPathDocumentLoader.loadDocuments("docs/");

        // 2. 构建文档分割器对象：最大的分片大小为500，冗余100（防止切割后前后文无关联）
        DocumentSplitter documentSplitter = DocumentSplitters.recursive(500, 100);

        // 3. 构建EmbeddingStoreIngestor-向量存储对象导入器，绑定分割器与向量模型
        // InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                // 绑定向量存储对象，分割后的片段存入此对象中
                .embeddingStore(embeddingStore)
                // 绑定文档分割器对象
                .documentSplitter(documentSplitter)
                // 绑定向量模型对象，用于存储
                .embeddingModel(embeddingModel)
                .build();

        // 4. 对文档进行分割、向量化并存入存储
        ingestor.ingest(docs);

        return embeddingStore;
    }

}
