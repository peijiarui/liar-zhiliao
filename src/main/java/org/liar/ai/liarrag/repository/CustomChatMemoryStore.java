package org.liar.ai.liarrag.repository;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
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
@AllArgsConstructor
public class CustomChatMemoryStore implements ChatMemoryStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {

        // 获取会话消息
        // 1. 从Redis中获取消息对应的JSON 数据
        String jsonData = redisTemplate.opsForValue().get(memoryId.toString());

        // 2. 借助 ChatMessageSerializer（由langchain4j提供） 将 JSON 数据反序列化为 消息列表 并返回
        return ChatMessageDeserializer.messagesFromJson(jsonData);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 更新会话消息
        // 1. 借助 ChatMessageSerializer（由langchain4j提供） 将 消息列表序列化为 JSON
        String jsonData = ChatMessageSerializer.messagesToJson(messages);
        //2. 将 JSON 数据写入Redis
        redisTemplate.opsForValue().set(memoryId.toString(), jsonData, Duration.ofDays(1));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        // 删除会话消息
        redisTemplate.delete(memoryId.toString());
    }
}
