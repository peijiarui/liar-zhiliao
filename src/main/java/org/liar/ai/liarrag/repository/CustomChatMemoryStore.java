package org.liar.ai.liarrag.repository;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.liar.ai.liarrag.util.LocalFileStore;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 创建自定义ChatMemoryStore，用于存储会话记忆，默认的是SingleSlotChatMemoryStore，内部用的是内存存储，服务重启后就没了
 * 在自定义的ChatMemoryStore中管理会话记忆，并持久化
 *
 * @author Pei
 * @since 2026-06-30
 */
@Slf4j
@Repository
public class CustomChatMemoryStore implements ChatMemoryStore {

    LocalFileStore localFileStore = new LocalFileStore();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 根据 memoryId 从本地文件存储中读取会话记忆数据
        // 如果存在数据，则将其从 JSON 格式反序列化为 ChatMessage 列表
        // 如果不存在数据（Optional 为空），则记录日志并返回一个空的 ChatMessage 列表
        return localFileStore.read(memoryId.toString())
                .map(ChatMessageDeserializer::messagesFromJson)
                .orElseGet(() -> {
                    log.info("没有memoryId为【{}】的会话记忆", memoryId);
                    return List.of();
                });
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        localFileStore.save(memoryId.toString(), json);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        localFileStore.delete(memoryId.toString());
    }
}
