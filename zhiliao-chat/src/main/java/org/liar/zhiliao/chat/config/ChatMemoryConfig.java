package org.liar.zhiliao.chat.config;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.repository.CustomChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Pei
 * @since 2026-07-05
 */
@Configuration
@RequiredArgsConstructor
public class ChatMemoryConfig {

    private final CustomChatMemoryStore customChatMemoryStore;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatMemoryProvider chatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .chatMemoryStore(customChatMemoryStore)
                .build();
    }

}
