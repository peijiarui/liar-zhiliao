package org.liar.ai.liarrag;

import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.liar.ai.liarrag.constants.AiModelConstants;

/**
 * @author liar
 * @since 29/06/26
 */
@Slf4j
public class QuickStart {

    public static void main(String[] args) {
        String apiKey = System.getenv(AiModelConstants.DEEPSEEK_API_KEY_ENV);

        log.info("apiKey: {}", apiKey);

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(AiModelConstants.DEEPSEEK_BASE_URL)
                .apiKey(apiKey)
                .modelName(AiModelConstants.DEEPSEEK_V4_FLASH_MODEL)
                .logRequests(true)
                .logResponses(true)
                .build();

        String answer = model.chat("你是谁");
        log.info("answer: {}", answer);

    }

}
