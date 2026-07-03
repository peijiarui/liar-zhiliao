package org.liar.ai.liarrag.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.ClassPathDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.AllArgsConstructor;
import org.liar.ai.liarrag.repository.CustomChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * @author Pei
 * @since 2026-06-30
 */
@Configuration
@AllArgsConstructor
public class BeansConfig {

    private final CustomChatMemoryStore customChatMemoryStore;

    // 构建会话记忆对象
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    /**
     * 建会话记忆Provider
     * 1. 先根据memoryId获取会话记忆对象
     * 2. 如果获取不到，则根据memoryId创建会话记忆对象
     *
     * @return ChatMemoryProvider
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return new ChatMemoryProvider() {
            @Override
            public ChatMemory get(Object memoryId) {
                return MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(20)
                        .chatMemoryStore(customChatMemoryStore)
                        .build();
            }
        };
    }

    /**
     * 创建向量存储对象
     *
     * @return EmbeddingStore
     */
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {

        //1.加载文件进内存
        List<Document> docs = ClassPathDocumentLoader.loadDocuments("docs/");

        //2.构建EmbeddingStoreIngestor-向量存储对象导入器
        InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                //绑定内存向量存储对象，分割后的片段存入此对象中
                .embeddingStore(embeddingStore)
                .build();

        //3.对内存中的文档进行分割
        ingestor.ingest(docs);

        return embeddingStore;
    }

    /**
     * 创建内容检索器
     *
     * @param embeddingStore spring容器中的向量存储对象
     * @return ContentRetriever
     */
    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore) {
        return EmbeddingStoreContentRetriever.builder()
                //接收的向量存储对象
                .embeddingStore(embeddingStore)
                //设置最小的余弦相似度。0-1
                .minScore(0.5)
                //设置最大的返回数量
                .maxResults(3)
                .build();
    }

}
