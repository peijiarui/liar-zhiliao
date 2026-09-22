package org.liar.zhiliao.chat.repository;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

/**
 * @author Pei
 * @since 2026-06-30
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CustomChatMemoryStore implements ChatMemoryStore {

    private static final String TOOL_RESULT_PLACEHOLDER = "[工具结果已省略]";

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        //获取会话消息
        //1.从Redis中获取消息对应的JSON 数据
        String jsonData = redisTemplate.opsForValue().get(memoryId.toString());
        //2.借助 ChatMessageSerializer（由langchain4j提供） 将 JSON 数据反序列化为 消息列表 并返回
        return ChatMessageDeserializer.messagesFromJson(jsonData);
    }

    /**
     * 每条消息 add 时触发，尾部消息类型标识当前所处阶段。
     * 当轮工具结果必须原样写入（LangChain4j 写入后会立即从 store 读回并再次请求模型，
     * 若在写入时截断或替换，模型只能看到被裁剪的内容，损伤 RAG 回答质量）；
     * 本轮最终回答写入时替换历史工具结果为占位符 —— AiServices 在下一轮 add(用户消息)
     * 之前就会 messages() 读走列表组装请求，因此必须在本轮结束的写入时就完成替换；
     * 新用户消息写入时再兜底一次，覆盖流中断导致最终回答未写入的残留场景。
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        List<ChatMessage> processedMessages = replaceHistoricalToolResults(messages);
        //1.借助 ChatMessageSerializer（由langchain4j提供） 将 消息列表序列化为 JSON
        String jsonData = ChatMessageSerializer.messagesToJson(processedMessages);
        //2.将 JSON 数据写入Redis
        redisTemplate.opsForValue().set(memoryId.toString(), jsonData, Duration.ofDays(1));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        //删除会话消息
        redisTemplate.delete(memoryId.toString());
    }

    private List<ChatMessage> replaceHistoricalToolResults(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        boolean roundEnded = last instanceof UserMessage
                || (last instanceof AiMessage aiMessage && !aiMessage.hasToolExecutionRequests());
        if (!roundEnded) {
            return messages;
        }
        log.info("处理记忆中的tool返回======");
        return messages.stream()
                .map(m -> m instanceof ToolExecutionResultMessage toolMsg
                        ? toolMsg.toBuilder()
                                .contents(List.of(TextContent.from(TOOL_RESULT_PLACEHOLDER)))
                                .build()
                        : m)
                .toList();
    }
}
