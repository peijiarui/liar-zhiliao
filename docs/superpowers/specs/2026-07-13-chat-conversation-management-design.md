# 对话列表与会话管理设计

## 概述

为知了 AI 知识库增加对话列表管理功能，解决两个问题：
1. 同窗口多条消息 memoryId 不一致，导致消息分散在不同会话
2. 聊天记录有存 Redis 但前端无法展示历史消息

实现类似 ChatGPT 的对话列表体验：新建对话、切换对话、AI 自动生成标题、删除对话。

---

## 数据模型

### conversation 表（PostgreSQL）

```sql
CREATE TABLE zhiliao_public.conversation (
    id BIGSERIAL PRIMARY KEY,
    memory_id VARCHAR(255) NOT NULL UNIQUE,    -- 会话唯一标识，格式 conv-{uuid}，也是 Redis 消息 key
    title VARCHAR(255) NOT NULL DEFAULT '新对话', -- 会话标题，首次对话后由 LLM 自动生成
    user_id BIGINT NOT NULL,                    -- 所属用户 ID，关联用户表
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 创建时间
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP  -- 最后活动时间，用于列表排序
);

COMMENT ON TABLE zhiliao_public.conversation IS 'AI 对话会话表';
COMMENT ON COLUMN zhiliao_public.conversation.memory_id IS '会话唯一标识，格式 conv-{uuid}，同时作为 Redis 中聊天消息的 key';
COMMENT ON COLUMN zhiliao_public.conversation.title IS '会话标题，新建时默认为"新对话"，首轮对话后由 LLM 自动生成简短标题';
COMMENT ON COLUMN zhiliao_public.conversation.user_id IS '所属用户 ID，从 JWT token 解析';
COMMENT ON COLUMN zhiliao_public.conversation.created_at IS '会话创建时间';
COMMENT ON COLUMN zhiliao_public.conversation.updated_at IS '最后活动时间，每次收发消息时更新，对话列表按此字段降序排列';

CREATE INDEX idx_conversation_user_id ON zhiliao_public.conversation(user_id);
CREATE INDEX idx_conversation_updated_at ON zhiliao_public.conversation(updated_at DESC);
```

### Redis（不变）

现有的 `CustomChatMemoryStore` 继续按 `memoryId` 存储聊天消息。每个 conversation 对应一个 memoryId。

---

## API 设计

所有接口均需 JWT 认证。

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/conversations` | 获取当前用户的对话列表，按 `updated_at DESC` |
| `POST` | `/api/conversations` | 新建对话，返回 `{ memoryId }` |
| `DELETE` | `/api/conversations/{memoryId}` | 删除对话，同时清除 Redis 中的消息 |
| `PUT` | `/api/conversations/{memoryId}/title` | 手动更新标题 |
| `GET` | `/api/conversations/{memoryId}/messages` | 从 Redis 获取历史消息，返回 `[{role, content}, ...]` |

### 关键行为

- `POST /api/conversations` 只创建空会话，返回 `memoryId`。前端拿到后存入当前会话状态，用户开始聊天时传该 memoryId。
- `GET /api/conversations/{memoryId}/messages` 从 `CustomChatMemoryStore` 读取消息并返回，前端直接渲染为历史消息列表。
- 删除操作需同时清理 PostgreSQL 记录和 Redis 中的消息。
- 已有的 `GET /chat?memoryId=X&message=Y` 接口不变，前端继续用它发消息。

---

## 标题自动生成

- **触发时机**：用户在该对话中发送的第一条消息的 AI 回复流式完成后
- **调用方式**：Spring `@Async`，不阻塞响应
- **实现**：调用 DeepSeek，提示词为「用 4-8 个字概括用户的问题，不要标点」
- **内容**：基于该对话的第一条用户消息生成
- **去重**：仅对首次对话触发，后续消息不再生成
- **前端**：无需特殊轮询，流式 `onEnd` 回调中主动刷新一次对话列表即可

---

## 前端改动

### ConversationSidebar.vue（新增）

左侧对话列表侧边栏：
- 固定在左侧，宽度约 260px
- 顶部「+ 新建对话」按钮
- 对话列表，每项显示标题 + 时间（刚刚 / N 分钟前 / 日期）
- 当前选中的对话高亮
- 悬停显示删除按钮，点击弹出确认弹窗
- 标题自动更新时刷新列表项

### Chat.vue 修改

- 移除 `'web-' + Date.now()` 的 memoryId 逻辑
- 新增 `activeMemoryId` ref，由当前选中的 conversation 提供
- `onMounted` 时获取对话列表，默认选中第一个
- 切换对话时调用 `GET /api/conversations/{memoryId}/messages` 加载历史消息
- 流式 `onEnd` 后刷新对话列表（获取新标题）
- 消息发送使用稳定的 `activeMemoryId`

### conversation.js（新增 API 文件）

封装 5 个对话相关 API 调用。

### App.vue 布局调整

从简单的 `<RouterView>` 改为侧边栏 + 主内容区布局。侧边栏仅在登录后显示。

---

## 完整数据流

```
新建对话：
  用户点「+」→ POST /api/conversations → { memoryId }
    → 切换 activeMemoryId → 清空 messages
    → 左侧新增「新对话」

发送消息：
  用户输入 → GET /chat?memoryId=xxx&message=...
    → 后端处理 → 流式返回 → 前端渲染
    → 完成后刷新对话列表

切换对话：
  点击某对话 → GET /api/conversations/{memoryId}/messages
    → 渲染历史消息

标题生成：
  首条 AI 回复完成 → 异步 LLM 调用 → 更新 title
    → 前端 onEnd 刷新列表

删除对话：
  确认删除 → DELETE /api/conversations/{memoryId}
    → 删 PostgreSQL + Redis → 列表移除 → 自动切换到最近对话
```

---

## 实施要点

- **后端**：需要新增 `ConversationController`、`ConversationService`、`Conversation` 实体
- **新建对话模块**：对话管理属于核心功能，建议放在 `zhiliao-chat` 模块或新建子包
- **数据库迁移**：使用 SQL 脚本或 JPA `ddl-auto` 创建表
- **无新增依赖**：PostgreSQL + Redis 均为现有基础设施
