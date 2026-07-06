package org.liar.zhiliao.chat.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

/**
 * @author Pei
 * @since 2026-06-30
 */
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",  //非流式调用时使用这个模型
        streamingChatModel = "openAiStreamingChatModel",    //流式调用时使用这个模型
        chatMemory = "chatMemory",  //指定会话记忆对象
        chatMemoryProvider = "chatMemoryProvider",  //指定会话记忆对象提供者
        contentRetriever = "contentRetriever"   //配置内容检索器
)
//@AiService  //不指定wiringMode时会自动配置
public interface ChatService {

    @SystemMessage(fromResource = "system-prompt.md")
    Flux<String> chat(@MemoryId String memoryId, @UserMessage String userMessage);

}
