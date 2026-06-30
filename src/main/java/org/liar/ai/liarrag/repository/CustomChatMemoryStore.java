package org.liar.ai.liarrag.repository;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.util.List;

/**
 * 创建自定义ChatMemoryStore，用于存储会话记忆，默认的是SingleSlotChatMemoryStore，内部用的是内存存储，服务重启后就没了
 * 在自定义的ChatMemoryStore中管理会话记忆，并持久化
 *
 * @author Pei
 * @since 2026-06-30
 */
public class CustomChatMemoryStore implements ChatMemoryStore {



    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return List.of();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {

    }

    @Override
    public void deleteMessages(Object memoryId) {

    }
}
