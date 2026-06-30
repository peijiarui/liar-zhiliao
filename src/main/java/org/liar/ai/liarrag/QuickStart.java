//package org.liar.ai.liarrag;
//
//import dev.langchain4j.model.openai.OpenAiChatModel;
//import lombok.extern.slf4j.Slf4j;
//import org.liar.ai.liarrag.enums.AiModelEnum;
//
///**
// * @author liar
// * @since 29/06/26
// */
//@Slf4j
//public class QuickStart {
//
//    public static void main(String[] args) {
//        String apiKey = System.getenv("DEEPSEEK_API_KEY");
//
//        log.info("apiKey: {}", apiKey);
//
//        OpenAiChatModel model = OpenAiChatModel.builder()
//                .baseUrl(AiModelEnum.DEEPSEEK_V4_FLASH.getProvider().getOpenAiBaseUrl())
//                .apiKey(apiKey)
//                .modelName(AiModelEnum.DEEPSEEK_V4_FLASH.getModel())
//                .logRequests(true)
//                .logResponses(true)
//                .build();
//
//        String answer = model.chat("你是谁");
//        log.info("answer: {}", answer);
//
//    }
//
//}
