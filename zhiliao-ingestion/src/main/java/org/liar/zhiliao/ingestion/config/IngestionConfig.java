package org.liar.zhiliao.ingestion.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 文档摄入配置 - 启动时从 classpath:docs/ 加载预置文档并初始化向量存储。
 * <p>
 * EmbeddingStore 由 langchain4j-milvus-spring-boot-starter 自动装配，
 * 本类只负责消费它进行初始化，不再重复声明 Bean。
 *
 * @author Pei
 * @since 2026-07-06
 */
@Slf4j
@Configuration
@AllArgsConstructor
//@Import(MilvusEmbeddingStoreAutoConfiguration.class)    //将MilvusEmbeddingStoreAutoConfiguration的自动配置提前，否则默认会优先注入embeddingStore->InMemoryEmbeddingStore，导致Bean：embeddingStore的类型为InMemoryEmbeddingStore，或者从容器中获取时使用Bean：milvusEmbeddingStore
public class IngestionConfig {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> milvusEmbeddingStore;

    //    @PostConstruct
    public void init() {
        // 1. 加载文件进内存
        List<Document> docs = ClassPathDocumentLoader.loadDocuments("docs/");
        if (docs.isEmpty()) {
            log.info("No pre-loaded documents found in classpath:docs/, skipping initialization");
            return;
        }
        // 2. 构建文档分割器对象：最大的分片大小为500，冗余100（防止切割后前后文无关联）
        DocumentSplitter documentSplitter = DocumentSplitters.recursive(500, 100);
        // 3. 构建EmbeddingStoreIngestor-向量存储对象导入器，绑定分割器与向量模型
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                // 绑定向量存储对象，分割后的片段存入此对象中
                .embeddingStore(milvusEmbeddingStore)
                // 绑定文档分割器对象
                .documentSplitter(documentSplitter)
                // 绑定向量模型对象，用于存储
                .embeddingModel(embeddingModel)
                .build();
        ingestor.ingest(docs);

        log.info("Ingested {} pre-loaded documents into vector store", docs.size());
    }
}
