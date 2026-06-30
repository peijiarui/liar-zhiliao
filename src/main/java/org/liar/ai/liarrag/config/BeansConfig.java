package org.liar.ai.liarrag.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.AllArgsConstructor;
import org.liar.ai.liarrag.repository.CustomChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

}
