package org.liar.zhiliao.chat.service;

import org.liar.zhiliao.chat.entity.Conversation;

import java.util.List;

/**
 * @author liar
 * @since 13/07/26
 */
public interface ConversationService {

    /**
     * 列出所有会话
     *
     * @return 会话列表
     */
    List<Conversation> listConversations();

    /**
     * 创建一个会话
     *
     * @return 会话
     */
    Conversation createConversation();

    /**
     * 删除一个会话
     *
     * @param memoryId 会话ID
     */
    void deleteConversation(String memoryId);

    /**
     * 更新会话标题
     *
     * @param memoryId 会话ID
     * @param title    标题
     */
    void updateTitle(String memoryId, String title);

    /**
     * 刷新会话
     *
     * @param memoryId 会话ID
     */
    void touchConversation(String memoryId);

    /**
     * 获取一个会话
     *
     * @param memoryId 会话ID
     * @return 会话
     */
    Conversation getByMemoryId(String memoryId);

    /**
     * 尝试更新会话标题，如果标题是默认标题则更新，否则返回false
     *
     * @param memoryId 会话ID
     * @param newTitle 新标题
     * @return 是否更新成功
     */
    boolean tryUpdateTitleIfDefault(String memoryId, String newTitle);


}
