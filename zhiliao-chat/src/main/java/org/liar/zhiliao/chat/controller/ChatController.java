package org.liar.zhiliao.chat.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.security.InputFilter;
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
    private final InputFilter inputFilter;

    @GetMapping(produces = "text/html;charset=utf-8")
    public Flux<String> chat(String memoryId, String message) {
        // 输入长度限制
        if (message != null && message.length() > 2000) {
            message = message.substring(0, 2000);
        }

        // Prompt 注入检测
        String rejection = inputFilter.check(message);
        if (rejection != null) {
            return Flux.just("输入被拒绝：" + rejection);
        }

        final String finalMessage = message;
        conversationService.touchConversation(memoryId);

        return assistant.chat(memoryId, finalMessage)
                .doOnComplete(() -> {
                    if (conversationService.tryUpdateTitleIfDefault(memoryId, "生成中...")) {
                        titleGenerationService.generateTitleAsync(memoryId, finalMessage);
                    }
                });
    }

}
