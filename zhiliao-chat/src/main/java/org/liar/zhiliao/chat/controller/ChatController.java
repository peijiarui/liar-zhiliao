package org.liar.zhiliao.chat.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.service.ChatService;
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

    @GetMapping(produces = "text/html;charset=utf-8")
    public Flux<String> chat(String memoryId, String message) {
        return assistant.chat(memoryId, message);
    }


}
