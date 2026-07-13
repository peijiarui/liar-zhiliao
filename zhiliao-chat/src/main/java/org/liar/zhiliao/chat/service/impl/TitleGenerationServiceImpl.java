package org.liar.zhiliao.chat.service.impl;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.chat.service.ConversationService;
import org.liar.zhiliao.chat.service.TitleGenerationService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TitleGenerationServiceImpl implements TitleGenerationService {

    private final ChatModel openAiChatModel;
    private final ConversationService conversationService;

    @Async
    @Override
    public void generateTitleAsync(String memoryId, String firstUserMessage) {
        try {
            Prompt prompt = PromptTemplate.from(
                    "根据用户的第一个问题，生成一个简短的对话标题（4-8个字），不要标点，直接返回标题内容。\\n用户问题：{{message}}"
            ).apply(Map.of("message", firstUserMessage));

            String title = openAiChatModel.chat(prompt.text());
            // 清理可能的引号或多余空格
            title = title.trim().replaceAll("^[\"']|[\"']$", "");
            if (title.length() > 100) {
                title = title.substring(0, 100);
            }
            conversationService.updateTitle(memoryId, title);
            log.info("Generated title '{}' for conversation {}", title, memoryId);
        } catch (Exception e) {
            log.error("Failed to generate title for conversation {}: {}", memoryId, e.getMessage());
            try {
                conversationService.updateTitle(memoryId, "新对话");
            } catch (Exception ex) {
                log.warn("Failed to restore default title for conversation {}", memoryId, ex);
            }
        }
    }
}
