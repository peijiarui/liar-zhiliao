package org.liar.zhiliao.chat.service;

/**
 * @author liar
 * @since 13/07/26
 */
public interface TitleGenerationService {

    /**
     * 异步生成对话标题
     *
     * @param memoryId         会话内存 ID
     * @param firstUserMessage 用户的第一条消息
     */
    void generateTitleAsync(String memoryId, String firstUserMessage);

}
