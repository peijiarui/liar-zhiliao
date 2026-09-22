package org.liar.zhiliao.chat.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.chat.entity.Conversation;
import org.liar.zhiliao.chat.security.InputFilter;
import org.liar.zhiliao.chat.service.ChatService;
import org.liar.zhiliao.chat.service.ConversationService;
import org.liar.zhiliao.chat.service.TitleGenerationService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.liar.zhiliao.retrieval.tools.KnowledgeRetrievalTool;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话所有权校验测试：本人通过 / 他人拒绝 / ADMIN 可访问任意会话。
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock ChatService assistant;
    @Mock ConversationService conversationService;
    @Mock TitleGenerationService titleGenerationService;
    @Mock InputFilter inputFilter;
    @Mock KnowledgeRetrievalTool knowledgeRetrievalTool;

    ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(assistant, conversationService,
                titleGenerationService, inputFilter, knowledgeRetrievalTool);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private CurrentUser user(Long id, String role) {
        return CurrentUser.of(id, "u" + id, "用户" + id, role, 1L, List.of(1L));
    }

    private Conversation conversation(Long userId) {
        return Conversation.builder().memoryId("conv-1").userId(userId).build();
    }

    @Test
    void ownerShouldChatNormally() {
        UserContextHolder.set(user(1L, "USER"));
        when(conversationService.getByMemoryId("conv-1")).thenReturn(conversation(1L));
        when(assistant.chat(eq("conv-1"), anyString())).thenReturn(Flux.just("hi"));

        String reply = controller.chat("conv-1", "你好").blockFirst();

        assertEquals("hi", reply);
        verify(conversationService).touchConversation("conv-1");
        verify(assistant).chat(eq("conv-1"), eq("你好"));
    }

    @Test
    void nonOwnerShouldBeDenied() {
        UserContextHolder.set(user(2L, "USER"));
        when(conversationService.getByMemoryId("conv-1")).thenReturn(conversation(1L));

        String reply = controller.chat("conv-1", "你好").blockFirst();

        assertEquals("无权访问该会话，或会话不存在。", reply);
        // 拒绝后不得触碰会话、不得进入 LLM 流程或降级检索，防止冒用他人身份
        verify(conversationService, never()).touchConversation(any());
        verify(assistant, never()).chat(any(), any());
        verifyNoInteractions(knowledgeRetrievalTool);
    }

    @Test
    void adminShouldAccessAnyConversation() {
        UserContextHolder.set(user(9L, "ADMIN"));
        when(conversationService.getByMemoryId("conv-1")).thenReturn(conversation(1L));
        when(assistant.chat(eq("conv-1"), anyString())).thenReturn(Flux.just("hi"));

        String reply = controller.chat("conv-1", "你好").blockFirst();

        assertEquals("hi", reply);
        verify(assistant).chat(eq("conv-1"), eq("你好"));
    }
}
