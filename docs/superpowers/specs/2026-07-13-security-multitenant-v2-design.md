# 安全与多租户设计文档 v2

> 版本：v2.0 | 日期：2026-07-13 | 作者：Pei
> 基于：2026-07-11-security-multitenant-design.md (v1.0)
> 变更：微信扫码 → 钉钉扫码（微信空壳预留），配置扁平化，输入过滤增强

## 1. 概述

v1 原计划 GitHub + 微信扫码登录。因微信开放平台需企业认证、备案域名、应用审核，对 MVP 阶段门槛过高，v2 将微信替换为**钉钉扫码**（个人开发者零门槛接入），同时保留微信空壳实现和配置项以便后续切换。

## 2. OAuth2 认证

### 2.1 支持的登录方式

| 方式 | 实现 | 状态 |
|------|------|------|
| 用户名密码 | JwtFilter + BCrypt | 现有，不变 |
| GitHub OAuth2 | OAuth2 授权码流程 | 新增 |
| 钉钉扫码 | OAuth2 授权码流程（扫码确认） | 新增 |
| 微信扫码 | 空壳实现 + TODO | 预留 |

### 2.2 登录流程

```
┌─────────┐    ┌──────────┐    ┌──────────────┐
│ GitHub  │    │ 钉钉扫码  │    │ 用户名密码    │
│ /oauth2/│    │ /oauth2/ │    │ /api/auth/   │
│ github  │    │ dingtalk │    │ login（保留） │
└────┬────┘    └────┬─────┘    └──────┬───────┘
     │              │                 │
     ▼              ▼                 ▼
  OAuth2 回调   钉钉回调         直接验证密码
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

### 2.3 GitHub OAuth2 用户视角

用户点击"GitHub 登录" → 浏览器 302 跳转 GitHub 授权页 → 用户确认授权 → GitHub 302 回调我方后端 → 后端换取 token 获取用户信息 → 邮箱合并 → 签发 JWT → 302 重定向到首页（已登录）。

### 2.4 钉钉扫码用户视角

前端展示钉钉二维码 → 用户手机钉钉扫码确认 → 钉钉 302 回调我方 redirectUri 并携带 authCode → 后端用 authCode 换取 accessToken → 获取用户信息（unionId、昵称、头像）→ 邮箱合并 → 签发 JWT。

### 2.5 OAuth2 抽象接口（沿用 v1）

```java
/**
 * OAuth2 认证器接口。
 * 每个 OAuth 提供商实现一个子类，通过 provider() 名称区分。
 */
public interface OAuth2Authenticator {
    String provider();
    OAuth2UserInfo authenticate(String authorizationCode);
}

public record OAuth2UserInfo(
    String providerUserId,
    String email,
    String name
) {}
```

### 2.6 邮箱合并逻辑（沿用 v1）

```
OAuth2UserInfo.email
    ├── 为空 → 创建新用户，记录 provider_id
    └── 非空 →
        ├── sys_oauth_link 已有记录 → 签发 JWT
        └── 无记录 →
            ├── sys_user 中邮箱已存在 → 关联到该用户
            └── sys_user 中邮箱不存在 → 创建新用户再关联
```

### 2.7 新增数据库表

```sql
CREATE TABLE IF NOT EXISTS sys_oauth_link (
    id                BIGSERIAL       PRIMARY KEY,
    user_id           BIGINT          NOT NULL,
    provider          VARCHAR(20)     NOT NULL,
    provider_user_id  VARCHAR(100)    NOT NULL,
    provider_email    VARCHAR(200),
    created_at        TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (provider, provider_user_id)
);
COMMENT ON TABLE  sys_oauth_link IS 'OAuth 关联表';
COMMENT ON COLUMN sys_oauth_link.provider IS 'OAuth 提供商：github / dingtalk / wechat(预留)';
COMMENT ON COLUMN sys_oauth_link.provider_user_id IS '第三方平台中的用户唯一ID';
COMMENT ON COLUMN sys_oauth_link.provider_email IS 'OAuth 返回的邮箱，用于跨提供商自动合并';
```

### 2.8 配置

```yaml
oauth2:
  github:
    client-id: ${GITHUB_CLIENT_ID}
    client-secret: ${GITHUB_CLIENT_SECRET}
    redirect-uri: http://localhost:8080/oauth2/github/callback
  dingtalk:
    app-id: ${DINGTALK_APP_ID}
    app-secret: ${DINGTALK_APP_SECRET}
    redirect-uri: http://localhost:8080/oauth2/dingtalk/callback
  wechat:
    # 预留，暂不启用
    app-id: ${WECHAT_APP_ID:}
    app-secret: ${WECHAT_APP_SECRET:}
    redirect-uri: http://localhost:8080/oauth2/wechat/callback
```

### 2.9 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `OAuth2Authenticator.java` | zhiliao-auth | 新建，OAuth2 认证接口 |
| `OAuth2UserInfo.java` | zhiliao-auth | 新建，OAuth 用户信息 record |
| `GitHubAuthenticator.java` | zhiliao-auth | 新建，GitHub OAuth 实现 |
| `DingTalkAuthenticator.java` | zhiliao-auth | 新建，钉钉扫码实现 |
| `WeChatAuthenticator.java` | zhiliao-auth | 新建，空壳 + TODO 注释 |
| `OAuth2Controller.java` | zhiliao-auth | 新建，/oauth2/github, /oauth2/dingtalk/callback |
| `SysOauthLink.java` | zhiliao-auth | 新建，MyBatis-Plus 实体 |
| `SysOauthLinkMapper.java` | zhiliao-auth | 新建，MyBatis-Plus Mapper |
| `UserLinkService.java` | zhiliao-auth | 新建，邮箱合并逻辑 |
| `OAuth2Config.java` | zhiliao-auth | 新建，@ConfigurationProperties(prefix="oauth2") |
| `schema.sql` | zhiliao-app | 追加 sys_oauth_link 建表 DDL |
| `application.yaml` | zhiliao-app | 追加 oauth2 配置 |

## 3. RBAC + 多租户隔离（沿用 v1）

### 3.1 权限模型

```
用户 → 所属部门(dept_id) → 知识库可见性(zl_kb_dept_visibility) → 检索仅返回可见文档
```

部门级粗粒度权限，不做到文档级别。

### 3.2 新增数据库表

```sql
CREATE TABLE IF NOT EXISTS zl_kb_dept_visibility (
    id         BIGSERIAL       PRIMARY KEY,
    kb_id      BIGINT          NOT NULL,
    dept_id    BIGINT          NOT NULL,
    created_at TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (kb_id, dept_id)
);
COMMENT ON TABLE  zl_kb_dept_visibility IS '知识库-部门可见性';
COMMENT ON COLUMN zl_kb_dept_visibility.kb_id IS '关联 zl_knowledge_base.id';
COMMENT ON COLUMN zl_kb_dept_visibility.dept_id IS '关联 sys_department.id';
```

### 3.3 检索过滤

```
用户请求 → JwtFilter → UserContextHolder.set(CurrentUser)
  → ChatService → KnowledgeRetrievalTool
    → PG BM25 搜索（追加 dept_id 过滤）
    → RRF 融合 → 父替换
```

MVP 在 PG BM25 搜索侧做 dept_id 过滤，适用 ~10 万 chunk 规模。

### 3.4 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `CurrentUser.java` | zhiliao-common | 扩展，新增 visibleDeptIds |
| `UserContextHolder.java` | zhiliao-common | 不变 |
| `ZlKbDeptVisibility.java` | zhiliao-auth | 新建 |
| `ZlKbDeptVisibilityMapper.java` | zhiliao-auth | 新建 |
| `DeptPermissionService.java` | zhiliao-auth | 新建 |
| `schema.sql` | zhiliao-app | 追加 zl_kb_dept_visibility DDL |

## 4. 输入输出安全

### 4.1 输入长度限制

在 ChatController 中，调用 ChatService 之前对用户消息截断：

```java
if (message != null && message.length() > 2000) {
    message = message.substring(0, 2000);
}
```

### 4.2 InputFilter（Prompt 注入检测）

敏感词配置从 application.yaml 改为 classpath 文件加载（`sensitive-words.txt`，一行一词）。

```java
@Component
public class InputFilter {
    /** 返回 null 表示通过，返回非空字符串为拒绝原因 */
    public String check(String userMessage);
}
```

检测规则：

| 类型 | 匹配模式 |
|------|----------|
| 系统 prompt 覆写 | `忽略.*(指令\|提示\|system\|规则)` |
| 角色伪装 | `你现在是\|你扮演` |
| 敏感主题 | classpath:sensitive-words.txt 中的词 |

### 4.3 OutputFilter（预留）

```java
public interface OutputFilter {
    boolean check(String aiResponse);
}
```

MVP 仅实现 InputFilter，OutputFilter 留接口不实现。

### 4.4 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `sensitive-words.txt` | zhiliao-chat resources | 新建 |
| `InputFilter.java` | zhiliao-chat | 新建 |
| `OutputFilter.java` | zhiliao-chat | 新建，预留接口 |
| `ChatController.java` | zhiliao-chat | 修改，长度截断 + 调用 InputFilter |

## 5. 与 v1 的差异汇总

| 模块 | v1 | v2 |
|------|----|----|
| 扫码登录 | 微信扫码 | 钉钉扫码（微信空壳预留） |
| 配置根路径 | `zhiliao.oauth2.*` | `oauth2.*` |
| 输入长度 | 无限制 | 2000 字符截断 |
| 敏感词配置 | application.yaml | classpath:sensitive-words.txt |
| 其余 | — | 不变 |

## 6. 不影响已有功能

- RecursiveDocumentSplitter / DocumentConsumerProcessor 不修改
- SparseSearcher / Reranker 接口不修改
- ChatService / system-prompt.md 不修改
- Phase 1 检索链路不修改
