# 安全与多租户设计文档

> 版本：v1.0 | 日期：2026-07-11 | 作者：Pei
> 基于：企业级 RAG 知识库架构设计文档 (v1.0)

## 1. 概述

本文档覆盖"企业级 RAG 知识库"第二阶段**安全与多租户**的设计方案。当前 MVP 仅有基本的 JWT 用户名密码认证，tenant_id/dept_id 字段预留但未使用。阶段二新增 OAuth2 SSO（GitHub + 微信扫码）、RBAC 部门级权限、多租户数据隔离和输入输出安全过滤。

## 2. OAuth2 认证

### 2.1 支持的登录方式

| 方式 | 实现 | 状态 |
|------|------|------|
| 用户名密码（现有） | JwtFilter + BCrypt | 保留，不变 |
| GitHub OAuth2 | OAuth2 授权码流程 | 新增 |
| 微信扫码 | 微信开放平台扫码登录 | 新增 |

### 2.2 登录流程

```
┌─────────┐    ┌──────────┐    ┌──────────────┐
│ GitHub  │    │  微信扫码 │    │ 用户名密码    │
│ /oauth2/│    │ /oauth2/ │    │ /api/auth/   │
│ github  │    │ wechat   │    │ login（保留） │
└────┬────┘    └────┬─────┘    └──────┬───────┘
     │              │                 │
     ▼              ▼                 ▼
  OAuth2 回调   微信回调         直接验证密码
     │              │                 │
     │ 获取邮箱     │ 获取邮箱        │
     └──────┬───────┘                 │
            │                         │
            ▼                         ▼
    邮箱已存在？                 签发 JWT
    ├── 是 → 绑定已有用户
    └── 否 → 自动创建用户（使用 OAuth 用户名）
            │
            ▼
        签发 JWT
```

### 2.3 OAuth2 抽象接口

```java
/**
 * OAuth2 认证器接口。
 * 每个 OAuth 提供商实现一个子类，通过 provider() 名称区分。
 */
public interface OAuth2Authenticator {
    /** OAuth 提供商名称，如 "github"、"wechat" */
    String provider();

    /** 用授权码换取用户信息（含邮箱） */
    OAuth2UserInfo authenticate(String authorizationCode);
}

/** OAuth2 用户信息，用于邮箱合并判定 */
public record OAuth2UserInfo(
    String providerUserId,  // GitHub/微信的用户 ID
    String email,           // 用于邮箱关联合并
    String name             // 显示名称
) {}
```

### 2.4 邮箱合并逻辑

```
OAuth2UserInfo.email
    ├── 为空 → 创建新用户（无合并可能），记录 provider_id
    └── 非空 →
        ├── sys_oauth_link 已有记录 → 签发 JWT
        └── 无记录 →
            ├── sys_user 中邮箱已存在 → 关联到该用户
            └── sys_user 中邮箱不存在 → 创建新用户再关联
```

**设计意图**：用户初次用 GitHub 登录、之后用微信扫码时，只要邮箱一致，自动视为同一用户。

### 2.5 新增数据库表

```sql
CREATE TABLE IF NOT EXISTS sys_oauth_link (
    id                BIGSERIAL       PRIMARY KEY,
    user_id           BIGINT          NOT NULL,        -- 关联 sys_user.id
    provider          VARCHAR(20)     NOT NULL,        -- 'github' | 'wechat'
    provider_user_id  VARCHAR(100)    NOT NULL,        -- GitHub/微信的用户 ID
    provider_email    VARCHAR(200),                    -- OAuth 返回的邮箱
    created_at        TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (provider, provider_user_id)
);
COMMENT ON TABLE  sys_oauth_link IS 'OAuth 关联表：GitHub/微信账号到本地用户的映射';
COMMENT ON COLUMN sys_oauth_link.provider IS 'OAuth 提供商：github 或 wechat';
COMMENT ON COLUMN sys_oauth_link.provider_user_id IS '第三方平台中的用户唯一ID';
COMMENT ON COLUMN sys_oauth_link.provider_email IS 'OAuth 返回的邮箱，用于跨提供商自动合并';
```

### 2.6 需要更新的配置

```yaml
zhiliao:
  oauth2:
    github:
      client-id: ${GITHUB_CLIENT_ID}
      client-secret: ${GITHUB_CLIENT_SECRET}
      redirect-uri: http://localhost:8080/oauth2/github/callback
    wechat:
      app-id: ${WECHAT_APP_ID}
      app-secret: ${WECHAT_APP_SECRET}
      redirect-uri: http://localhost:8080/oauth2/wechat/callback
```

### 2.7 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `OAuth2Authenticator.java` | zhiliao-auth | 新建，OAuth2 认证接口 |
| `OAuth2UserInfo.java` | zhiliao-auth | 新建，OAuth 用户信息 record |
| `GitHubAuthenticator.java` | zhiliao-auth | 新建，GitHub OAuth 实现 |
| `WeChatAuthenticator.java` | zhiliao-auth | 新建，微信扫码实现 |
| `OAuth2Controller.java` | zhiliao-auth | 新建，/oauth2/github, /oauth2/wechat/callback |
| `SysOauthLink.java` | zhiliao-auth | 新建，MyBatis-Plus 实体 |
| `SysOauthLinkMapper.java` | zhiliao-auth | 新建，MyBatis-Plus Mapper |
| `UserLinkService.java` | zhiliao-auth | 新建，邮箱合并逻辑 |
| `OAuth2Config.java` | zhiliao-auth | 新建，@ConfigurationProperties |
| `schema.sql` | zhiliao-app | 追加 sys_oauth_link 建表 DDL |
| `application.yaml` | zhiliao-app | 追加 oauth2 配置 |

## 3. RBAC + 多租户隔离

### 3.1 权限模型

```
用户 → 所属部门(dept_id) → 可查看的知识库(zl_kb_dept_visibility)
                                         → 知识库内的文档
                                         → 检索仅返回可见文档的 chunks
```

### 3.2 新增数据库表

```sql
CREATE TABLE IF NOT EXISTS zl_kb_dept_visibility (
    id         BIGSERIAL       PRIMARY KEY,
    kb_id      BIGINT          NOT NULL,        -- 知识库 ID
    dept_id    BIGINT          NOT NULL,        -- 可见部门 ID
    created_at TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (kb_id, dept_id)
);
COMMENT ON TABLE  zl_kb_dept_visibility IS '知识库-部门可见性：一个知识库可被多个部门查看';
COMMENT ON COLUMN zl_kb_dept_visibility.kb_id IS '关联 zl_knowledge_base.id';
COMMENT ON COLUMN zl_kb_dept_visibility.dept_id IS '关联 sys_department.id';
```

### 3.3 检索过滤

```
用户请求 → JwtFilter → UserContextHolder.set(CurrentUser)
  → ChatService → KnowledgeRetrievalTool
    → Milvus 检索（全部 chunks）
    → PG BM25 搜索（追加 dept_id 过滤）
    → RRF 融合
    → 父替换后所有结果来自允许范围
```

**当前方案**：检索后按 dept_id 过滤（适用 MVP 规模，~10 万 chunk）。Mlivus 支持 Partition Key 或标量过滤，未来可改为检索时直接过滤。

### 3.4 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `CurrentUser.java` | zhiliao-common | 扩展，新增 visibleDeptIds |
| `UserContextHolder.java` | zhiliao-common | 现有，不变 |
| `ZlKbDeptVisibility.java` | zhiliao-auth | 新建，MyBatis-Plus 实体 |
| `ZlKbDeptVisibilityMapper.java` | zhiliao-auth | 新建，MyBatis-Plus Mapper |
| `DeptPermissionService.java` | zhiliao-auth | 新建，查询用户可见 dept 列表 |
| `schema.sql` | zhiliao-app | 追加 zl_kb_dept_visibility DDL |

## 4. 输入输出安全

### 4.1 输入过滤

在 ChatController 调用 ChatService 之前，对用户输入做 Prompt 注入检测。

```java
/**
 * 用户输入过滤器。
 * 检测 Prompt 注入、越权查询、敏感主题。
 */
@Component
public class InputFilter {
    /** 返回 null 表示通过，返回非空字符串为拒绝原因 */
    public String check(String userMessage);
}
```

**检测规则**（通过配置维护）：

| 类型 | 匹配模式 |
|------|----------|
| 系统 prompt 覆写 | `忽略.*(指令\|提示\|system\|规则)` |
| 角色伪装 | `你现在是\|你扮演` |
| 敏感主题 | 自定义敏感词列表（通过 application.yaml 配置）|

### 4.2 输出过滤（预留）

```java
/**
 * AI 输出审核器。
 * 当前版本预留接口，未来集成内容审核服务。
 */
public interface OutputFilter {
    /** 返回 true = 安全，false = 需拦截 */
    boolean check(String aiResponse);
}
```

MVP 阶段仅实现 `InputFilter`，`OutputFilter` 留接口不实现。

### 4.3 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `InputFilter.java` | zhiliao-chat | 新建，输入检测 |
| `OutputFilter.java` | zhiliao-chat | 新建，输出审核接口 |
| `ChatController.java` | zhiliao-chat | 修改，调用 InputFilter |

## 5. 数据流转示意

```
用户登录：
  用户名密码 → AuthController → JwtUtil → JWT
  GitHub      → OAuth2Controller → UserLinkService(邮箱合并) → JWT
  微信扫码     → OAuth2Controller → UserLinkService(邮箱合并) → JWT

用户对话：
  ChatController → InputFilter.check(message)
    → ChatService (@AiService + KnowledgeRetrievalTool)
      → Milvus + PG BM25（带 dept 过滤）
      → RRF → 父替换 → LLM 回答
```

## 6. 不影响已有功能

- `RecursiveDocumentSplitter` / `DocumentConsumerProcessor` 不修改
- `SparseSearcher` / `Reranker` 接口不修改
- ChatService / system-prompt.md 不修改
- Docker Compose 不修改
