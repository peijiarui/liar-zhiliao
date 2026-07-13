package org.liar.zhiliao.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.entity.Conversation;
import org.liar.zhiliao.chat.mapper.ConversationMapper;
import org.liar.zhiliao.chat.repository.CustomChatMemoryStore;
import org.liar.zhiliao.chat.service.ConversationService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation> implements ConversationService {

    private final CustomChatMemoryStore chatMemoryStore;

    @Override
    public List<Conversation> listConversations() {
        CurrentUser user = UserContextHolder.get();
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, user.id())
                .orderByDesc(Conversation::getUpdatedAt);
        return list(wrapper);
    }

    @Override
    public Conversation createConversation() {
        CurrentUser user = UserContextHolder.get();
        Conversation conversation = Conversation.builder()
                .memoryId("conv-" + UUID.randomUUID())
                .userId(user.id())
                .title("新对话")
                .messageCount(0)
                .deptId(user.deptId() != null ? user.deptId() : 1L)
                .tenantId("default")
                .updatedAt(OffsetDateTime.now())
                .build();
        save(conversation);
        return conversation;
    }

    @Override
    @Transactional
    public void deleteConversation(String memoryId) {
        CurrentUser user = UserContextHolder.get();
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getMemoryId, memoryId)
                .eq(Conversation::getUserId, user.id());
        boolean deleted = remove(wrapper);
        if (deleted) {
            chatMemoryStore.deleteMessages(memoryId);
        }
    }

    @Override
    public void updateTitle(String memoryId, String title) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getMemoryId, memoryId);
        Conversation conversation = getOne(wrapper);
        if (conversation != null) {
            conversation.setTitle(title);
            conversation.setUpdatedAt(OffsetDateTime.now());
            updateById(conversation);
        }
    }

    @Override
    public void touchConversation(String memoryId) {
        // 更新 updated_at 表示会话活跃
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getMemoryId, memoryId);
        Conversation conversation = getOne(wrapper);
        if (conversation != null) {
            conversation.setUpdatedAt(OffsetDateTime.now());
            updateById(conversation);
        }
    }

    @Override
    public Conversation getByMemoryId(String memoryId) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getMemoryId, memoryId);
        return getOne(wrapper);
    }

    @Override
    @Transactional
    public boolean tryUpdateTitleIfDefault(String memoryId, String newTitle) {
        LambdaUpdateWrapper<Conversation> wrapper = new LambdaUpdateWrapper<Conversation>()
                .eq(Conversation::getMemoryId, memoryId)
                .eq(Conversation::getTitle, "新对话")
                .set(Conversation::getTitle, newTitle)
                .set(Conversation::getUpdatedAt, OffsetDateTime.now());
        return update(wrapper);
    }
}
