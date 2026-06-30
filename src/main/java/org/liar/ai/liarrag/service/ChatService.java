package org.liar.ai.liarrag.service;

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
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT, //EXPLICIT为手动配置
        chatModel = "openAiChatModel", //非流式调用时使用这个模型
        streamingChatModel = "openAiStreamingChatModel", //流式调用时使用这个模型
        chatMemory = "chatMemory",   //指定会话记忆对象
        chatMemoryProvider = "chatMemoryProvider" //指定会话记忆对象提供者
)
//@AiService  //不指定wiringMode时会自动配置
public interface ChatService {

    //    @SystemMessage("你是一个企业知识库助手，名叫知了知了，负责答疑")
//    @UserMessage("你是一个企业知识库助手，名叫知了知了，负责答疑。{{it}}") //{{it}}是固定写法，也可以使用@V修饰参数，如@V("msg") String userMessage {{msg}}
    @SystemMessage(fromResource = "system-prompt.md")
    Flux<String> chat(@MemoryId String memoryId, @UserMessage String userMessage);  //流式调用需要返回Flux，使用的chatModel是OpenAiStreamingChatModel

}
