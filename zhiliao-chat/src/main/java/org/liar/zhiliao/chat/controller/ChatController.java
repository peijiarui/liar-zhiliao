package org.liar.zhiliao.chat.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.service.ChatService;
import org.liar.zhiliao.chat.service.TitleGenerationService;
import org.liar.zhiliao.chat.service.ConversationService;
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
                    // 原子性检查并更新标题，防止并发竞态
                    if (conversationService.tryUpdateTitleIfDefault(memoryId, "生成中...")) {
                        titleGenerationService.generateTitleAsync(memoryId, message);
                    }
                });
    }


}
