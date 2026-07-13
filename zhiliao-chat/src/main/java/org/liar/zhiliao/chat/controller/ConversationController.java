package org.liar.zhiliao.chat.controller;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.entity.Conversation;
import org.liar.zhiliao.chat.model.MessageResponse;
import org.liar.zhiliao.chat.repository.CustomChatMemoryStore;
import org.liar.zhiliao.chat.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final CustomChatMemoryStore chatMemoryStore;

    @GetMapping
    public ResponseEntity<List<Conversation>> list() {
        return ResponseEntity.ok(conversationService.listConversations());
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create() {
        Conversation conversation = conversationService.createConversation();
        return ResponseEntity.ok(Map.of("memoryId", conversation.getMemoryId()));
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(@PathVariable String memoryId) {
        conversationService.deleteConversation(memoryId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{memoryId}/title")
    public ResponseEntity<Void> updateTitle(@PathVariable String memoryId, @RequestBody Map<String, String> body) {
        conversationService.updateTitle(memoryId, body.get("title"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{memoryId}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(@PathVariable String memoryId) {
        List<ChatMessage> messages = chatMemoryStore.getMessages(memoryId);
        List<MessageResponse> result = messages.stream()
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
                    return new MessageResponse(role, content);
                })
                .toList();
        return ResponseEntity.ok(result);
    }
}
