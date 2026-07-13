package org.liar.zhiliao.chat.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.service.ChatService;
import org.liar.zhiliao.chat.service.ConversationService;
import org.liar.zhiliao.chat.service.TitleGenerationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * @author Pei
 * @since 2026-06-30
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService assistant;
    private final ConversationService conversationService;
    private final TitleGenerationService titleGenerationService;

    @GetMapping(produces = "text/html;charset=utf-8")
    public Flux<String> chat(String memoryId, String message) {
        // 更新会话最后活动时间
        conversationService.touchConversation(memoryId);

        return assistant.chat(memoryId, message)
                .doOnComplete(() -> {
                    // 首次对话后异步生成标题（仅当标题仍为默认值时）
                    var conv = conversationService.getByMemoryId(memoryId);
                    if (conv != null && "新对话".equals(conv.getTitle())) {
                        titleGenerationService.generateTitleAsync(memoryId, message);
                    }
                });
    }


}
