# 对话列表与会话管理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为知了 AI 知识库增加对话列表管理功能，支持新建/切换/删除对话、查看历史消息、AI 自动生成标题。

**Architecture:** 后端在 `zhiliao-chat` 模块新增 MyBatis-Plus entity/mapper/service/controller，使用已有 `zl_conversation` PostgreSQL 表 + Redis 消息存储。前端在 Chat.vue 内新增 ConversationSidebar 组件。

**Tech Stack:** Spring Boot 3.5 + MyBatis-Plus 3.5.9 + PostgreSQL 16 + Redis (Lettuce) + Vue 3 + Naive UI + Vite

## Global Constraints

- 所有新表使用现有 `zl_` 前缀和 `dept_id`/`tenant_id` 多租户字段（默认值：dept_id=1, tenant_id='default'）
- 实体遵循现有模式：`@Data` `@Builder` `@NoArgsConstructor` `@AllArgsConstructor` + MyBatis-Plus `@TableName` `@TableId`
- `memory_id` 格式：`conv-{UUID}`
- 所有新 API 路径以 `/api/` 开头，需 JWT 认证
- Redis 消息格式保持不变（LangChain4j `ChatMessageSerializer` JSON）
- 标题生成使用现有 DeepSeek 模型（`openAiChatModel`），异步执行
- MyBatis-Plus 依赖版本从根 pom 继承（3.5.9）

---

### Task 1: 数据库 Schema 迁移 + zhiliao-chat 模块依赖

**Files:**
- Modify: `zhiliao-app/src/main/resources/sql/schema.sql`
- Modify: `zhiliao-chat/pom.xml`

**Interfaces:**
- Consumes: 现有 `zl_conversation` 表（已有 `id, memory_id, user_id, title, message_count, dept_id, tenant_id, created_at`）
- Produces: 新的 `updated_at` 字段 + `UNIQUE(memory_id)` 约束

- [ ] **Step 1: 修改 schema.sql，为 zl_conversation 表补充字段**

在 schema.sql 的 `zl_conversation` 表下方添加 ALTER TABLE 语句：

```sql
-- 6. Conversations (chat sessions) — 追加字段
ALTER TABLE zl_conversation ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE zl_conversation ADD UNIQUE (memory_id);

COMMENT ON COLUMN zl_conversation.updated_at IS '最后活动时间，每次收发消息时更新，对话列表按此字段降序排列';
```

- [ ] **Step 2: 在 zhiliao-chat/pom.xml 添加 MyBatis-Plus 依赖**

在 `</dependencies>` 之前添加：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
```

- [ ] **Step 3: 验证构建**

```bash
cd /Users/liar/Java/project/liar-zhiliao
mvn clean compile -pl zhiliao-chat -am
```
Expected: BUILD SUCCESS

---

### Task 2: 后端 Conversation CRUD（Entity + Mapper + Service + Controller + Message DTO）

> **注意：** 创建文件前确保目录存在，需手动 `mkdir -p zhiliao-chat/src/main/java/org/liar/zhiliao/chat/entity/` 等。

**Files:**
- Create: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/entity/Conversation.java`
- Create: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/mapper/ConversationMapper.java`
- Create: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/service/ConversationService.java`
- Create: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/controller/ConversationController.java`

**Interfaces:**
- Consumes: `zl_conversation` 表, `CurrentUser` (`UserContextHolder`), `CustomChatMemoryStore`
- Produces: REST API 端点 (list, create, delete, rename, get messages)

- [ ] **Step 1: 创建 Conversation entity**

```java
package org.liar.zhiliao.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("zl_conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memoryId;

    @TableField(fill = FieldFill.INSERT)
    private Long userId;

    private String title;

    @Builder.Default
    private Integer messageCount = 0;

    @Builder.Default
    private Long deptId = 1L;

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 2: 创建 ConversationMapper**

```java
package org.liar.zhiliao.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.liar.zhiliao.chat.entity.Conversation;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
```

- [ ] **Step 3: 创建 ConversationService**

```java
package org.liar.zhiliao.chat.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.entity.Conversation;
import org.liar.zhiliao.chat.mapper.ConversationMapper;
import org.liar.zhiliao.chat.repository.CustomChatMemoryStore;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConversationService extends ServiceImpl<ConversationMapper, Conversation> {

    private final CustomChatMemoryStore chatMemoryStore;

    public List<Conversation> listConversations() {
        CurrentUser user = UserContextHolder.get();
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, user.id())
                .orderByDesc(Conversation::getUpdatedAt);
        return list(wrapper);
    }

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

    @Transactional
    public void deleteConversation(String memoryId) {
        CurrentUser user = UserContextHolder.get();
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getMemoryId, memoryId)
                .eq(Conversation::getUserId, user.id());
        remove(wrapper);
        chatMemoryStore.deleteMessages(memoryId);
    }

    public void updateTitle(String memoryId, String title) {
        CurrentUser user = UserContextHolder.get();
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getMemoryId, memoryId)
                .eq(Conversation::getUserId, user.id());
        Conversation conversation = getOne(wrapper);
        if (conversation != null) {
            conversation.setTitle(title);
            updateById(conversation);
        }
    }

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
}
```

- [ ] **Step 4: 创建 MessageResponse record（DTO，避免 LangChain4j ChatMessage 的 Jackson 序列化问题）**

```java
package org.liar.zhiliao.chat.model;

public record MessageResponse(String role, String content) {}
```

- [ ] **Step 5: 创建 ConversationController**

```java
package org.liar.zhiliao.chat.controller;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.entity.Conversation;
import org.liar.zhiliao.chat.vo.response.MessageResponse;
import org.liar.zhiliao.chat.repository.CustomChatMemoryStore;
import org.liar.zhiliao.chat.service.ConversationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final CustomChatMemoryStore chatMemoryStore;

    @GetMapping
    public ResponseEntity<List<Conversation>> list() {
        return ResponseEntity.ok(conversationService.listConversations());
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> create() {
        Conversation conversation = conversationService.createConversation();
        return ResponseEntity.ok(Map.of("memoryId", conversation.getMemoryId()));
    }

    @DeleteMapping("/{memoryId}")
    public ResponseEntity<Void> delete(@PathVariable String memoryId) {
        conversationService.deleteConversation(memoryId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{memoryId}/title")
    public ResponseEntity<Void> updateTitle(@PathVariable String memoryId, @RequestBody Map<String, String> body) {
        conversationService.updateTitle(memoryId, body.get("title"));
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{memoryId}/messages")
    public ResponseEntity<List<MessageResponse>> getMessages(@PathVariable String memoryId) {
        List<ChatMessage> messages = chatMemoryStore.getMessages(memoryId);
        List<MessageResponse> result = messages.stream()
                .map(m -> new MessageResponse(
                        m.type() == ChatMessageType.USER ? "user" : "assistant",
                        m.text()
                ))
                .toList();
        return ResponseEntity.ok(result);
    }
}
```

> **注意：** `ChatMessage.type()` 不是 Java Bean getter（无 `get` 前缀），Jackson 不会自动序列化。因此使用 `MessageResponse` record 作为响应 DTO，手动映射 `role` 和 `content` 字段。

- [ ] **Step 6: 构建验证**

```bash
cd /Users/liar/Java/project/liar-zhiliao
mvn clean compile -pl zhiliao-chat -am
```
Expected: BUILD SUCCESS

---

### Task 3: 异步标题生成 + ChatController 更新

**Files:**
- Create: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/config/AsyncConfig.java`
- Create: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/service/TitleGenerationService.java`
- Modify: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/controller/ChatController.java`

**Interfaces:**
- Consumes: `ConversationService`, DeepSeek `ChatLanguageModel` (langchain4j 自动装配的 `openAiChatModel`)
- Produces: 首条消息回复后自动更新对话标题

- [ ] **Step 1: 创建 AsyncConfig**

```java
package org.liar.zhiliao.chat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {
}
```

- [ ] **Step 2: 创建 TitleGenerationService**

```java
package org.liar.zhiliao.chat.service;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TitleGenerationService {

    private final ChatLanguageModel openAiChatModel;
    private final ConversationService conversationService;

    @Async
    public void generateTitleAsync(String memoryId, String firstUserMessage) {
        try {
            Prompt prompt = PromptTemplate.from(
                    "根据用户的第一个问题，生成一个简短的对话标题（4-8个字），不要标点，直接返回标题内容。\\n用户问题：{{message}}"
            ).apply(Map.of("message", firstUserMessage));

            String title = openAiChatModel.generate(prompt.text());
            // 清理可能的引号或多余空格
            title = title.trim().replaceAll("^[\"']|[\"']$", "");
            if (title.length() > 100) {
                title = title.substring(0, 100);
            }
            conversationService.updateTitle(memoryId, title);
            log.info("Generated title '{}' for conversation {}", title, memoryId);
        } catch (Exception e) {
            log.error("Failed to generate title for conversation {}: {}", memoryId, e.getMessage());
        }
    }
}
```

- [ ] **Step 3: 修改 ChatController，增加 conversation 追踪和标题生成触发**

```java
package org.liar.zhiliao.chat.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.chat.service.ChatService;
import org.liar.zhiliao.chat.service.ConversationService;
import org.liar.zhiliao.chat.service.impl.TitleGenerationServiceImpl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService assistant;
    private final ConversationService conversationService;
    private final TitleGenerationService titleGenerationService;

    @GetMapping(produces = "text/html;charset=utf-8")
    public Flux<String> chat(String memoryId, String message) {
        // 更新会话最后活动时间
        conversationService.touchConversation(memoryId);

        return assistant.chat(memoryId, message)
                .doOnComplete(() -> {
                    // 首次对话后异步生成标题（仅当标题仍为默认值时）
                    var conv = conversationService.getByMemoryId(memoryId);
                    if (conv != null && "新对话".equals(conv.getTitle())) {
                        titleGenerationService.generateTitleAsync(memoryId, message);
                    }
                });
    }
}
```

- [ ] **Step 4: 在 ConversationService 中添加 getByMemoryId 方法**

在 `ConversationService.java` 中添加：

```java
public Conversation getByMemoryId(String memoryId) {
    LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
            .eq(Conversation::getMemoryId, memoryId);
    return getOne(wrapper);
}
```

- [ ] **Step 5: 验证构建**

```bash
cd /Users/liar/Java/project/liar-zhiliao
mvn clean compile -pl zhiliao-chat -am
```
Expected: BUILD SUCCESS

---

### Task 4: 前端 Conversation API + ConversationSidebar 组件

> **注意：** `useDialog()` 需要 `<n-dialog-provider>` 祖先组件。需要在 App.vue 中添加。

**Files:**
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/App.vue`
- Create: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/api/conversation.js`
- Create: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/components/ConversationSidebar.vue`

**Interfaces:**
- Consumes: 后端 `/api/conversations/*` 端点
- Produces: ConversationSidebar 组件（接收 `activeMemoryId` props，通过事件通知父组件切换/创建/删除对话）

- [ ] **Step 0: 在 App.vue 中添加 `<n-dialog-provider>` 包裹**

修改 App.vue 的 template，在 `<n-notification-provider>` 内添加 `<n-dialog-provider>`：

```html
<n-loading-bar-provider>
  <n-message-provider>
    <n-notification-provider>
      <n-dialog-provider>
        <!-- existing layout content -->
      </n-dialog-provider>
    </n-notification-provider>
  </n-message-provider>
</n-loading-bar-provider>
```

- [ ] **Step 1: 创建 `src/api/conversation.js`**

```javascript
import request from './request'

export function getConversations() {
  return request.get('/api/conversations')
}

export function createConversation() {
  return request.post('/api/conversations')
}

export function deleteConversation(memoryId) {
  return request.delete(`/api/conversations/${encodeURIComponent(memoryId)}`)
}

export function updateTitle(memoryId, title) {
  return request.put(`/api/conversations/${encodeURIComponent(memoryId)}/title`, { title })
}

export function getMessages(memoryId) {
  return request.get(`/api/conversations/${encodeURIComponent(memoryId)}/messages`)
}
```

- [ ] **Step 2: 创建 ConversationSidebar.vue 组件**

```vue
<template>
  <div class="conversation-sidebar">
    <div class="sidebar-header">
      <n-button block @click="handleNew" type="primary" ghost>
        <template #icon><n-icon><add-icon /></n-icon></template>
        + 新建对话
      </n-button>
    </div>
    <div class="sidebar-list">
      <div
        v-for="conv in conversations"
        :key="conv.memoryId"
        :class="['conv-item', { active: conv.memoryId === activeMemoryId }]"
        @click="$emit('select', conv.memoryId)"
      >
        <div class="conv-title">{{ conv.title }}</div>
        <div class="conv-time">{{ formatTime(conv.updatedAt || conv.createdAt) }}</div>
        <n-button
          class="conv-delete"
          quaternary circle size="tiny"
          @click.stop="handleDelete(conv.memoryId)"
        >
          <template #icon><n-icon><trash-icon /></n-icon></template>
        </n-button>
      </div>
      <div v-if="!conversations.length" class="sidebar-empty">暂无对话</div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, h } from 'vue'
import { getConversations, createConversation, deleteConversation } from '../api/conversation'
import { NButton, NIcon, useDialog, useMessage } from 'naive-ui'
import { Add as AddIcon, Trash as TrashIcon } from '@vicons/ionicons5'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  activeMemoryId: { type: String, default: '' }
})

const emit = defineEmits(['select', 'created', 'deleted'])

const dialog = useDialog()
const message = useMessage()

async function handleNew() {
  try {
    const res = await createConversation()
    emit('created', res.data.memoryId)
  } catch (e) {
    message.error('创建对话失败')
  }
}

function handleDelete(memoryId) {
  dialog.warning({
    title: '删除对话',
    content: '确定要删除该对话吗？对话消息将一并删除。',
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteConversation(memoryId)
        emit('deleted', memoryId)
        message.success('已删除')
      } catch (e) {
        message.error('删除失败')
      }
    }
  })
}

function formatTime(timeStr) {
  if (!timeStr) return ''
  const date = new Date(timeStr)
  const now = new Date()
  const diff = now - date
  if (diff < 60000) return '刚刚'
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`
  if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`
  return `${date.getMonth() + 1}/${date.getDate()}`
}
</script>

<style scoped>
.conversation-sidebar {
  width: 260px;
  min-width: 260px;
  background: #fafafa;
  border-right: 1px solid #eee;
  display: flex;
  flex-direction: column;
  height: 100%;
}
.sidebar-header {
  padding: 12px;
  border-bottom: 1px solid #eee;
}
.sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.conv-item {
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  position: relative;
  margin-bottom: 4px;
  transition: background 0.15s;
}
.conv-item:hover { background: #e8e8e8; }
.conv-item.active { background: #d0e8ff; }
.conv-title {
  font-size: 14px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: 24px;
}
.conv-time { font-size: 11px; color: #999; margin-top: 2px; }
.conv-delete {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  opacity: 0;
  transition: opacity 0.15s;
}
.conv-item:hover .conv-delete { opacity: 1; }
.sidebar-empty {
  text-align: center;
  color: #999;
  padding: 24px;
  font-size: 13px;
}
</style>
```

---

### Task 5: 前端 Chat.vue 重构 + App.vue 布局调整

**Files:**
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/views/Chat.vue`

**Interfaces:**
- Consumes: `ConversationSidebar`, `conversation.js` API, `streamChat`
- Produces: 完整的对话管理 UI

- [ ] **Step 1: 重写 Chat.vue**

```vue
<template>
  <div class="chat-layout">
    <ConversationSidebar
      :conversations="conversations"
      :activeMemoryId="activeMemoryId"
      @select="handleSelect"
      @created="handleCreated"
      @deleted="handleDeleted"
    />
    <div class="chat-container">
      <div class="chat-messages" ref="messagesRef">
        <div v-if="!messages.length" class="chat-empty">
          <h2>开始对话</h2>
          <p>输入问题开始与 AI 知识库对话</p>
        </div>
        <div v-for="(msg, idx) in messages" :key="idx" :class="['msg', msg.role]">
          <div class="msg-avatar">{{ msg.role === 'user' ? 'U' : 'AI' }}</div>
          <div class="msg-content">
            <div class="msg-bubble">{{ msg.content }}</div>
          </div>
        </div>
        <div v-if="streaming" class="msg assistant">
          <div class="msg-avatar">AI</div>
          <div class="msg-content">
            <div class="msg-bubble">{{ streamingContent }}<span class="cursor-blink">|</span></div>
          </div>
        </div>
      </div>
      <div class="chat-input-bar">
        <n-input
          v-model:value="inputMessage"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 4 }"
          placeholder="输入问题..."
          @keyup.enter.prevent="sendMessage"
          :disabled="streaming"
        />
        <n-button type="primary" :loading="streaming" @click="sendMessage" style="margin-left: 10px;">
          <template #icon><n-icon><send-icon /></n-icon></template>
        </n-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, onUnmounted, h } from 'vue'
import { useMessage } from 'naive-ui'
import { streamChat } from '../api/chat'
import { getConversations, getMessages, createConversation } from '../api/conversation'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import { NIcon, NButton, NInput } from 'naive-ui'
import { Send as SendIcon } from '@vicons/ionicons5'

const message = useMessage()
const conversations = ref([])
const activeMemoryId = ref('')
const messages = ref([])
const inputMessage = ref('')
const streaming = ref(false)
const streamingContent = ref('')
const messagesRef = ref(null)
let cancelStream = null

onMounted(async () => {
  await loadConversations()
  if (conversations.value.length > 0) {
    await switchConversation(conversations.value[0].memoryId)
  }
})

onUnmounted(() => cancelStream?.())

async function loadConversations() {
  try {
    const res = await getConversations()
    conversations.value = res.data
  } catch (e) {
    // 忽略，可能尚未登录
  }
}

async function switchConversation(memoryId) {
  activeMemoryId.value = memoryId
  messages.value = []
  try {
    const res = await getMessages(memoryId)
    // 后端 MessageResponse 已格式化为 { role, content }
    messages.value = res.data.map(m => ({
      role: m.role,
      content: m.content
    }))
  } catch (e) {
    message.error('加载历史消息失败')
  }
  nextTick(scrollToBottom)
}

async function handleSelect(memoryId) {
  if (memoryId !== activeMemoryId.value) {
    await switchConversation(memoryId)
  }
}

async function handleCreated(memoryId) {
  activeMemoryId.value = memoryId
  messages.value = []
  await loadConversations()
}

async function handleDeleted(deletedMemoryId) {
  conversations.value = conversations.value.filter(c => c.memoryId !== deletedMemoryId)
  if (activeMemoryId.value === deletedMemoryId) {
    if (conversations.value.length > 0) {
      await switchConversation(conversations.value[0].memoryId)
    } else {
      activeMemoryId.value = ''
      messages.value = []
    }
  }
}

async function sendMessage() {
  const msg = inputMessage.value.trim()
  if (!msg || streaming.value) return

  // 如果没有活跃对话，自动创建一个
  if (!activeMemoryId.value) {
    try {
      const res = await createConversation()
      activeMemoryId.value = res.data.memoryId
      await loadConversations()
    } catch (e) {
      message.error('创建对话失败')
      return
    }
  }

  messages.value.push({ role: 'user', content: msg })
  inputMessage.value = ''
  streaming.value = true
  streamingContent.value = ''
  scrollToBottom()

  cancelStream = streamChat(
    activeMemoryId.value,
    msg,
    (data) => {
      streamingContent.value = data
      scrollToBottom()
    },
    (err) => {
      console.error('Stream error:', err)
      streaming.value = false
    },
    () => {
      messages.value.push({ role: 'assistant', content: streamingContent.value })
      streaming.value = false
      streamingContent.value = ''
      scrollToBottom()
      // 刷新对话列表（获取新标题）
      loadConversations()
    }
  )
}

function scrollToBottom() {
  nextTick(() => {
    const el = messagesRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: 100vh;
}
.chat-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: #f5f5f5;
}
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 10%;
}
.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
}
.chat-empty h2 { font-size: 22px; color: #333; margin-bottom: 6px; }
.chat-empty p { font-size: 14px; }
.msg {
  display: flex;
  margin-bottom: 20px;
  gap: 10px;
}
.msg.user { flex-direction: row-reverse; }
.msg-avatar {
  width: 34px; height: 34px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 13px; flex-shrink: 0;
}
.msg.user .msg-avatar { background: #18a058; color: #fff; }
.msg.assistant .msg-avatar { background: #2080f0; color: #fff; }
.msg-content { max-width: 75%; }
.msg-bubble {
  padding: 12px 16px; border-radius: 12px; line-height: 1.6;
  font-size: 14px; white-space: pre-wrap; word-break: break-word;
}
.msg.user .msg-bubble { background: #18a058; color: #fff; border-radius: 12px 4px 12px 12px; }
.msg.assistant .msg-bubble { background: #fff; color: #333; border-radius: 4px 12px 12px 12px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.chat-input-bar {
  display: flex; align-items: flex-end;
  padding: 16px 10%; background: #fff; border-top: 1px solid #eee;
}
.cursor-blink { animation: blink 1s infinite; }
@keyframes blink { 0%,100% { opacity: 1; } 50% { opacity: 0; } }
</style>
```

---

## Spec 覆盖率检查

| Spec 需求 | 对应 Task |
|-----------|-----------|
| `zl_conversation` 表 `updated_at` + UNIQUE | Task 1 |
| Entity/Mapper/Service/Controller | Task 2 |
| `GET /api/conversations` 列表 | Task 2, Step 4 |
| `POST /api/conversations` 创建对话 | Task 2, Step 4 |
| `DELETE /api/conversations/{memoryId}` 删除对话+Redis | Task 2, Step 4 |
| `PUT /api/conversations/{memoryId}/title` 更新标题 | Task 2, Step 4 |
| `GET /api/conversations/{memoryId}/messages` 历史消息 | Task 2, Step 4 |
| 标题自动生成（异步 DeepSeek 调用） | Task 3 |
| 聊天时更新 conversation.updated_at | Task 3 |
| 前端 ConversationSidebar 组件 | Task 4 |
| 前端 Chat.vue 重构（memoryId 会话级，历史加载） | Task 5 |
| 前端自动创建对话 | Task 5 |
| 切换对话加载历史 | Task 5 |
| 流式完成后刷新列表获取新标题 | Task 5 |
