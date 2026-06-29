package org.liar.ai.liarrag.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author liar
 * @since 2026/06/29
 */
@Getter
@AllArgsConstructor
public enum AiModelEnum {

    DEEPSEEK_V4_FLASH(Provider.DEEPSEEK, "deepseek-v4-flash"),
    DEEPSEEK_V4_PRO(Provider.DEEPSEEK, "deepseek-v4-pro"),

    ZHIPU_GML_5_1(Provider.ZHIPU, "GLM-5.1"),
    ZHIPU_GML_5_2(Provider.ZHIPU, "GLM-5.2"),

    CLAUDE_SONNET_4_6(Provider.CLAUDE, "claude-sonnet-4.6");

    private final Provider provider;
    private final String model;

    @Getter
    @AllArgsConstructor
    public enum Provider {
        DEEPSEEK("DeepSeek", "https://api.deepseek.com", "https://api.deepseek.com/anthropic"),
        ZHIPU("ZhiPu", "https://open.bigmodel.cn/api/paas/v4/", "https://open.bigmodel.cn/api/anthropic"),
        CLAUDE("Claude", "https://api.anthropic.com", "https://api.anthropic.com");

        private final String name;
        private final String openAiBaseUrl;
        private final String anthropicBaseUrl;
    }

}
