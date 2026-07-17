package org.liar.zhiliao.chat.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.entity.Conversation;
import org.liar.zhiliao.chat.repository.CustomChatMemoryStore;
import org.liar.zhiliao.chat.service.ConversationService;
import org.liar.zhiliao.chat.vo.response.MessageResponse;
import org.liar.zhiliao.common.result.ResponseResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final CustomChatMemoryStore chatMemoryStore;

    /**
     * 列出所有会话
     *
     * @return 会话列表
     */
    @GetMapping
    public List<Conversation> list() {
        return conversationService.listConversations();
    }

    /**
     * 创建会话
     *
     * @return 会话记忆id
     */
    @PostMapping
    public String create() {
        Conversation conversation = conversationService.createConversation();
        return conversation.getMemoryId();
    }

    /**
     * 删除会话
     *
     * @param memoryId 记忆id
     * @return 响应结果
     */
    @DeleteMapping("/{memoryId}")
    public ResponseResult<Void> delete(@PathVariable String memoryId) {
        conversationService.deleteConversation(memoryId);
        return ResponseResult.ok();
    }

    /**
     * 更新会话标题
     *
     * @param memoryId 记忆id
     * @param title    标题
     * @return 响应结果
     */
    @PutMapping("/{memoryId}/title")
    public ResponseResult<Void> updateTitle(@PathVariable String memoryId, @RequestParam String title) {
        conversationService.updateTitle(memoryId, title);
        return ResponseResult.ok();
    }

    /**
     * 获取会话消息
     *
     * @param memoryId 记忆id
     * @return 会话消息列表
     */
    @GetMapping("/{memoryId}/messages")
    public List<MessageResponse> getMessages(@PathVariable String memoryId) {
        return chatMemoryStore.getMessages(memoryId).stream()
                .filter(m -> m.type() == ChatMessageType.USER
                        || (m instanceof AiMessage ai && ai.text() != null && !ai.text().isBlank()))
                .map(m -> {
                    String role = m.type() == ChatMessageType.USER ? "user" : "assistant";
                    String content;
                    if (m instanceof UserMessage userMessage) {
                        content = userMessage.singleText();
                    } else {
                        content = ((AiMessage) m).text();
                    }
                    return MessageResponse.of(role, content);
                })
                .toList();
    }
}
