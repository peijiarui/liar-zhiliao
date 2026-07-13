package org.liar.zhiliao.chat.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户输入过滤器。
 * 检测 Prompt 注入、角色伪装和敏感主题，拒绝恶意输入。
 */
@Slf4j
@Component
public class InputFilter {

    private static final Pattern SYSTEM_PROMPT_OVERRIDE = Pattern.compile("忽略.*(指令|提示|system|规则)");
    private static final Pattern ROLE_IMPERSONATION = Pattern.compile("你现在是|你扮演");

    private List<String> sensitiveWords = new ArrayList<>();

    @PostConstruct
    void loadSensitiveWords() {
        try {
            ClassPathResource resource = new ClassPathResource("sensitive-words.txt");
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                sensitiveWords = reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
            log.info("Loaded {} sensitive words", sensitiveWords.size());
        } catch (Exception e) {
            log.warn("Failed to load sensitive-words.txt: {}", e.getMessage());
        }
    }

    /**
     * 检查用户输入是否安全。
     *
     * @param userMessage 用户输入
     * @return null 表示通过，非空字符串为拒绝原因
     */
    public String check(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }

        if (SYSTEM_PROMPT_OVERRIDE.matcher(userMessage).find()) {
            return "检测到 Prompt 注入尝试";
        }
        if (ROLE_IMPERSONATION.matcher(userMessage).find()) {
            return "检测到角色伪装尝试";
        }
        for (String word : sensitiveWords) {
            if (userMessage.contains(word)) {
                return "输入包含敏感内容";
            }
        }
        return null;
    }
}
