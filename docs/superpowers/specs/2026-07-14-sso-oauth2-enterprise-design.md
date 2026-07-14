# SSO + OAuth2 企业级改造设计文档

> 版本：v1.0 | 日期：2026-07-14 | 作者：Pei
> 基于：2026-07-13-security-multitenant-v2-design.md
> 变更：JWT 无状态 → Redis 有状态 Session + Refresh Token；Cookie 传递 → 纯 Authorization Header；OAuth 回调返回 HTML 注入 token

## 1. 概述

### 1.1 背景

当前认证体系存在以下问题：

1. **JWT 无状态导致 logout 失效不彻底**：客户端清掉 token 后，旧 JWT 在过期前仍可使用，无法真正吊销。
2. **Cookie 与 Header 混用**：用户名密码登录走 Header（前端 localStorage 存），OAuth 回调走 HttpOnly Cookie，logout 时前端 JS 清不掉 HttpOnly Cookie。
3. **无续期机制**：JWT 默认 24h 过期，过期后用户必须重新登录，体验差。
4. **钉钉 OAuth 缺少 state 校验**：存在 CSRF 风险。
5. **OAuth 回调通过 setCookie 传递 token**：与 Header 方案不一致，混合方案增加复杂度。

### 1.2 目标

- 统一采用 **Authorization Header + localStorage** 传递 token，**摒弃 Cookie/Session**
- 引入 **Redis 有状态 Session**，支持 token 真正吊销
- 引入 **Access Token + Refresh Token** 双 token 机制，避免活跃用户频繁重登
- OAuth 回调统一通过 **HTML 页面注入 token** 返回前端
- 补齐 **钉钉 state CSRF 校验**
- 预留 **SSO 扩展点**（多应用共享登录态）

### 1.3 范围

**本次改造包含：**
- Token 模型重构（access + refresh）
- Redis Session 存储与查询
- AuthController / OAuth2Controller 接口改造
- SessionFilter（替代 JwtFilter）
- 前端登录/登出/token 刷新流程适配
- 钉钉 state 校验补齐

**不在本次范围（YAGNI）：**
- 多应用 SSO 跳转中间页（sso.liar.com → 各应用）
- SAML / OIDC 协议接入
- 设备指纹、IP 风控
- 多因素认证（MFA）
- 第三方 IdP 联邦（Okta、Auth0 等）
- 移动端 SDK

## 2. 现状分析

### 2.1 当前架构

```
登录方式              Token 载体                验证方式
─────────────        ────────────              ─────────
用户名密码            JWT (localStorage)        JwtFilter 验签
GitHub OAuth          JWT (HttpOnly Cookie)     JwtFilter 验签
钉钉扫码              JWT (HttpOnly Cookie)     JwtFilter 验签
```

### 2.2 关键问题点

| 问题 | 影响 |
|------|------|
| JWT 无状态 | logout 后旧 token 仍可用至过期 |
| Cookie/Header 混用 | OAuth 用户 logout 后 cookie 残留，刷新页面自动恢复登录 |
| 无 refresh token | 24h 过期必须重登，无静默续期 |
| 钉钉无 state 校验 | CSRF 攻击可伪造钉钉登录 |
| OAuth 回调 setCookie | 与 Header 方案冲突，跨域场景难扩展 |

## 3. 总体架构

### 3.1 新架构

```
                         ┌──────────────────┐
                         │   Redis          │
                         │   Session Store  │
                         └────────▲─────────┘
                                  │ 查询/续期/删除
┌─────────┐   login     ┌─────────┴─────────┐   Bearer token   ┌──────────────┐
│ 前端    │ ──────────► │ AuthController    │ ◄──────────────► │ 业务接口     │
│ SPA     │ ◄────────── │ OAuth2Controller  │                  │ (Chat等)     │
│         │  token      │ SessionFilter     │                  │ SessionFilter│
└─────────┘             └───────────────────┘                  └──────────────┘
     │                                                                     │
     └──────────────── Authorization: Bearer <access_token> ────────────────┘

                              401 token_expired
前端拦截器 ──────────────────► POST /api/auth/refresh
                              (携带 refresh_token)
                              └─► 返回新 access_token（可选新 refresh_token）
```

### 3.2 Token 模型

| Token 类型 | 载体 | TTL | 用途 |
|------------|------|-----|------|
| **Access Token** | `Authorization: Bearer xxx` | 15 min | 业务接口鉴权 |
| **Refresh Token** | 请求体字段 `refreshToken` | 7 day | access 过期后换新 access |

**为什么不用 JWT？**
- 服务端需在 Redis 校验 token 是否仍有效（支持吊销），JWT 自验签的能力用不上
- 改用 **不透明 token（opaque token）**：32 字节 SecureRandom → Base64URL，token 本身无意义，所有信息存 Redis
- 简化代码、避免 JWT 解析错误、避免 JWT payload 泄露内部字段

### 3.3 SSO 扩展点

虽然本次不实现多应用 SSO，但 Redis key 设计预留扩展：

```
auth:session:{appId}:{tokenId}     # 当前 appId = "zhiliao"
auth:refresh:{appId}:{tokenId}
auth:user:{userId}:sessions        # SET，记录用户所有活跃 session（便于踢人）
```

未来接入第二个应用时，只需新增 `appId` 维度，登录跳转走 SSO 中心即可。

## 4. Redis 存储结构

### 4.1 Access Token

```
key:     auth:session:zhiliao:{accessToken}
value:   {
  "userId": 1,
  "username": "alice",
  "deptId": 2,
  "visibleDeptIds": [1, 2, 3],
  "refreshTokenId": "uuid-xxx",     # 关联的 refresh token
  "issuedAt": 1730000000,
  "expiresAt": 1730000900           # 15 min
}
TTL:     15 min（不滑动续期，强制用 refresh 换新）
```

### 4.2 Refresh Token

```
key:     auth:refresh:zhiliao:{refreshToken}
value:   {
  "userId": 1,
  "username": "alice",
  "deptId": 2,
  "visibleDeptIds": [1, 2, 3],
  "issuedAt": 1730000000,
  "expiresAt": 1730604800,          # 7 day
  "rotated": false                  # 是否已被轮换（一次性使用）
}
TTL:     7 day
```

### 4.3 用户活跃 Session 索引

```
key:     auth:user:1:sessions
value:   SET of accessTokenIds
TTL:     7 day（与最长 refresh token 对齐）
用途:    管理员踢人 / 用户查看在线设备 / 修改密码后批量吊销
```

### 4.4 OAuth state 临时存储

```
key:     auth:oauth:state:{state}
value:   "github" | "dingtalk"
TTL:     5 min
用途:    CSRF 保护，回调时校验后立即删除
```

## 5. 接口契约

### 5.1 用户名密码登录

```
POST /api/auth/login
req:  { "username": "alice", "password": "xxx" }
res 200: {
  "accessToken": "xxx",
  "refreshToken": "yyy",
  "expiresIn": 900,
  "user": { "id": 1, "username": "alice", "deptId": 2, "visibleDeptIds": [1,2,3] }
}
res 401: { "error": "Invalid credentials" }
```

### 5.2 退出登录

```
POST /api/auth/logout
header: Authorization: Bearer <accessToken>
res 200: { "success": true }

后端逻辑:
  1. 从 UserContextHolder 取 accessToken
  2. 删除 Redis: auth:session:zhiliao:{accessToken}
  3. 删除关联的 auth:refresh:zhiliao:{refreshToken}
  4. 从 auth:user:{userId}:sessions 移除
```

### 5.3 获取当前用户

```
GET /api/auth/me
header: Authorization: Bearer <accessToken>
res 200: {
  "id": 1, "username": "alice", "deptId": 2,
  "visibleDeptIds": [1,2,3], "expiresAt": 1730000900
}
res 401: { "error": "token_invalid" }
```

### 5.4 刷新 Token（新增）

```
POST /api/auth/refresh
req:  { "refreshToken": "yyy" }
res 200: {
  "accessToken": "new-xxx",
  "refreshToken": "new-yyy",   # 可选：refresh token 轮换（推荐）
  "expiresIn": 900
}
res 401: { "error": "refresh_token_invalid" }

后端逻辑 (Refresh Token Rotation):
  1. 查 Redis auth:refresh:zhiliao:{refreshToken}
  2. 不存在或 rotated=true → 401（疑似重放攻击，建议同时吊销该用户所有 session）
  3. 标记旧 refresh token 为 rotated（或直接删除）
  4. 生成新 access token + 新 refresh token，存 Redis
  5. 更新 auth:user:{userId}:sessions
  6. 返回新 token 对
```

### 5.5 OAuth2 授权启动

```
GET /oauth2/github
  → 302 跳转 https://github.com/login/oauth/authorize?...&state={state}
  → Redis 写入 auth:oauth:state:{state} = "github" (TTL 5min)

GET /oauth2/dingtalk/authorize
  → 200 { "authUrl": "https://login.dingtalk.com/...&state={state}" }
  → Redis 写入 auth:oauth:state:{state} = "dingtalk" (TTL 5min)
  （前端拿到 URL 后自己跳转或生成二维码，state 由后端下发）
```

### 5.6 OAuth2 回调

```
GET /oauth2/{provider}/callback?code=xxx&state=xxx
  → 校验 state（Redis 查询 + 删除）
  → 换取 access_token、userInfo、email
  → UserLinkService 合并/创建用户
  → 生成自家 access + refresh token，存 Redis
  → 返回 HTML 页面:

    <!DOCTYPE html>
    <html><body>
    <script>
      localStorage.setItem('token', '<accessToken>');
      localStorage.setItem('refreshToken', '<refreshToken>');
      localStorage.setItem('username', 'alice');
      window.location.href = '/';
    </script>
    </body></html>
```

**为什么返回 HTML 而不是 JSON 或 302+token？**
- 浏览器接收到 OAuth 回调响应后会渲染或跟随跳转
- JSON 响应会被当文本显示，无法自动写入 localStorage 也无法跳转
- 302 + `?token=xxx` 会让 token 出现在 URL、浏览器历史、服务器日志、Referer 头，安全风险高
- 302 + `#token=xxx`（fragment）稍好但仍会进浏览器历史，且第三方 JS 可能读取
- **HTML + 内嵌 script**：token 仅存在于响应体，浏览器执行脚本写入 localStorage 后立即跳转，URL 干净，最安全

## 6. SessionFilter 设计

### 6.1 路由策略

```java
// 跳过鉴权的路径
/api/auth/login       POST
/api/auth/refresh     POST
/oauth2/**            GET（授权启动 + 回调）
/actuator/**          监控（生产应另行鉴权）

// 其他所有路径必须携带有效 access token
```

### 6.2 处理流程

```
1. 提取 Authorization: Bearer <token>
   - 缺失 → 401 {error: "token_missing"}
2. 查 Redis auth:session:zhiliao:{token}
   - 不存在 → 401 {error: "token_invalid"}
3. 注入 UserContextHolder
4. chain.doFilter
5. finally: UserContextHolder.clear()
```

**不做滑动续期**：access token 15 分钟硬过期，强制前端走 refresh 接口换新。这样 access token 泄露窗口有上限。

### 6.3 401 错误码区分

```
token_missing      未携带 token
token_invalid      token 不存在或已登出
token_expired      access token 已过期（前端应尝试 refresh）
refresh_invalid    refresh token 无效（前端必须重新登录）
```

前端根据错误码决定是走 refresh 流程还是直接跳登录页。

## 7. OAuth2 流程详解

### 7.1 GitHub 流程（不变 + state 改造）

```
1. 前端点击 "GitHub 登录" → window.location = '/oauth2/github'
2. 后端生成 state（UUID），写入 Redis auth:oauth:state:{state}="github" (TTL 5min)
3. 后端 302 跳转 GitHub 授权页（带 state）
4. 用户授权 → GitHub 302 回调 /oauth2/github/callback?code=xxx&state=xxx
5. 后端校验 state（Redis 查 → 删）
6. 用 code 换 access_token → 拉 userInfo → 拉 emails
7. UserLinkService 合并或创建用户
8. 生成自家 access + refresh token，存 Redis
9. 302 重定向到前端 `/#/oauth/callback?accessToken=...&refreshToken=...&username=...`，前端组件写入 localStorage 并跳首页（`zhiliao.auth.web-frontend-base-url` 配置，见 2026-07-14-oauth-frontend-redirect-design.md）
```

### 7.2 钉钉流程（新增 state）

```
1. 前端点击 "钉钉登录" → 调 GET /oauth2/dingtalk/authorize
2. 后端生成 state，写入 Redis，返回 {authUrl: "https://login.dingtalk.com/...&state=xxx"}
3. 前端用 authUrl 生成二维码展示（已有 DingTalkQrModal 组件）
4. 用户扫码确认 → 钉钉 302 回调 /oauth2/dingtalk/callback?authCode=xxx&state=xxx
5. 后端校验 state
6. 用 authCode 换 accessToken → 拉 userInfo
7. UserLinkService 合并或创建用户
8. 生成自家 access + refresh token，存 Redis
9. 返回 HTML 页面
```

**注意**：钉钉的 state 参数需要前端把后端返回的 authUrl 中的 state 原样带回，或在回调 URL 中拼接。具体由 DingTalkQrModal 在生成二维码时保留 state，钉钉回调时透传。

## 8. 前端适配

### 8.1 token 存储

```javascript
// stores/auth.js
const accessToken = ref(localStorage.getItem('accessToken') || '')
const refreshToken = ref(localStorage.getItem('refreshToken') || '')
const user = ref(null)

const isLoggedIn = computed(() => !!accessToken.value)
```

### 8.2 请求拦截器

```javascript
// api/request.js
request.interceptors.request.use(config => {
  const token = localStorage.getItem('accessToken')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  response => response,
  async error => {
    if (error.response?.status === 401) {
      const errorCode = error.response.data?.error
      if (errorCode === 'token_invalid' || errorCode === 'token_expired') {
        // 尝试 refresh
        const refreshed = await tryRefresh()
        if (refreshed) {
          // 重放原请求
          return request(error.config)
        }
      }
      // refresh 失败或 refresh_invalid → 跳登录
      clearAuth()
      window.location.hash = '#/login'
    }
    return Promise.reject(error)
  }
)

let refreshing = false
let pendingRequests = []
async function tryRefresh() {
  if (refreshing) {
    // 排队等待 refresh 完成
    return new Promise(resolve => pendingRequests.push(resolve))
  }
  refreshing = true
  try {
    const res = await axios.post('/api/auth/refresh', {
      refreshToken: localStorage.getItem('refreshToken')
    })
    localStorage.setItem('accessToken', res.data.accessToken)
    localStorage.setItem('refreshToken', res.data.refreshToken)
    pendingRequests.forEach(r => r(true))
    return true
  } catch {
    pendingRequests.forEach(r => r(false))
    return false
  } finally {
    refreshing = false
    pendingRequests = []
  }
}
```

### 8.3 OAuth 回调页（302 方案，见 2026-07-14-oauth-frontend-redirect-design.md）

后端 OAuth 回调不再返回 HTML，改为 302 重定向到前端 `/#/oauth/callback?accessToken=...&refreshToken=...&username=...`（地址由 `zhiliao.auth.web-frontend-base-url` 配置驱动，开发环境默认 `http://localhost:5173`）。前端 `OAuthCallback.vue` 从 hash 解析参数，调 `auth.setTokens` 写入 localStorage，跳转 `/chat`。`App.vue` 的 `initAuth` 在挂载时从 localStorage 读取 token，调 `/api/auth/me` 验证并恢复用户信息。

### 8.4 退出登录

```javascript
async function logout() {
  try {
    await logoutApi()  // 后端删 Redis session
  } catch { /* ignore */ }
  accessToken.value = ''
  refreshToken.value = ''
  user.value = null
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
}
```

## 9. 安全考虑

### 9.1 Refresh Token Rotation

每次 refresh 接口调用后，旧 refresh token 立即作废，返回新 refresh token。**防范 refresh token 被盗用后的重放攻击**：若攻击者使用被盗的 refresh token，合法用户下次使用时会发现已被轮换，可触发告警并吊销该用户所有 session。

### 9.2 OAuth state CSRF 保护

- state 由后端生成（SecureRandom），存 Redis TTL 5min
- 回调时校验后**立即删除**（防重放）
- 钉钉的 state 在 `/oauth2/dingtalk/authorize` 接口生成并拼入 authUrl

### 9.3 Token 生成

- 使用 `java.security.SecureRandom` 生成 32 字节随机数
- Base64URL 编码（无 `+/=`，URL 安全）
- 不可预测、不可枚举

### 9.4 HTTPS 强制

生产环境必须 HTTPS，否则 Authorization Header 中的 token 会被中间人截获。`application.yaml` 中预留 `zhiliao.security.force-https` 配置项（本次不实现，部署时通过反向代理保证）。

### 9.5 修改密码 / 权限变更后吊销

预留接口 `POST /api/auth/revoke-all`：删除 `auth:user:{userId}:sessions` 中所有 session，强制用户重新登录。本次不实现接口，但 Redis 结构已支持。

### 9.6 XSS 与 localStorage

localStorage 中的 token 易受 XSS 攻击。缓解措施：
- CSP 头部限制脚本来源
- 输入过滤（已有 InputFilter）
- 定期安全审计

如果未来对安全要求更高，可切换为 HttpOnly Cookie + SameSite=Strict + CSRF Token 方案，但需要前后端较大改造。

## 10. 配置变更

### 10.1 application.yaml 新增

```yaml
zhiliao:
  auth:
    app-id: zhiliao                    # SSO 应用标识
    access-token-ttl: 15m              # 900 秒
    refresh-token-ttl: 7d              # 604800 秒
    oauth-state-ttl: 5m                # 300 秒
    # 钉钉回调 state 透传参数名（钉钉默认为 state）
    dingtalk-state-param: state
```

### 10.2 依赖新增

`zhiliao-common/pom.xml` 已有 jjwt，本次改造**移除 jjwt 依赖**（不再使用 JWT），改用 Spring 自带 `SecureRandom` + `Base64`。Redis 通过 `spring-boot-starter-data-redis` 引入（已在 `zhiliao-app` 中），`zhiliao-auth` 模块需新增对该 starter 的依赖。

## 11. 改造步骤（实施清单）

按依赖顺序执行：

### 阶段 1：基础设施

1. `zhiliao-auth/pom.xml` 新增 `spring-boot-starter-data-redis` 依赖
2. 新增 `TokenService`（生成、存储、查询、删除、刷新、轮换）
3. 新增 `AuthProperties`（读取 `zhiliao.auth.*` 配置）
4. 新增 `SessionFilter`（替代 `JwtFilter`），先与 JwtFilter 并存
5. 切换 `SecurityConfig` / Filter 注册，禁用 JwtFilter

### 阶段 2：接口改造

6. 改 `AuthController.login`：返回 access + refresh + user
7. 改 `AuthController.logout`：调 `TokenService.revoke`
8. 改 `AuthController.me`：从 `UserContextHolder` 读，附带 `expiresAt`
9. 新增 `AuthController.refresh`：实现 refresh token rotation

### 阶段 3：OAuth 改造

10. `OAuth2Controller.githubAuthorize`：state 存 Redis
11. `OAuth2Controller.dingtalkAuthorize`：生成 state 拼入 authUrl，存 Redis
12. `OAuth2Controller.callback`：state 校验、返回 HTML 页面（不再 setCookie）
13. 钉钉 `DingTalkAuthenticator` 确认 state 透传机制

### 阶段 4：前端适配

14. `api/auth.js` 新增 `refreshToken` API
15. `stores/auth.js`：accessToken + refreshToken 双存储
16. `api/request.js`：401 拦截 + refresh 排队 + 重放请求
17. `App.vue`：`handleLogout` 已是 async，无需改
18. `OAuthButtons` / `DingTalkQrModal`：保留现有交互（state 由后端拼入 authUrl）

### 阶段 5：清理与文档

19. 移除 `JwtUtil`、`JwtFilter`、`zhiliao-common` 中的 jjwt 依赖
20. 更新 `CLAUDE.md` 中认证相关章节
21. 更新 `application.yaml` 注释

## 12. 验收标准

- [ ] 用户名密码登录返回 access + refresh token，localStorage 持久化
- [ ] 业务接口请求携带 Bearer token，无 token 或 token 无效返回 401
- [ ] access token 过期后，前端自动调 refresh 接口换新，无感续期
- [ ] refresh token 一次性使用，第二次使用返回 401
- [ ] logout 后旧 access + refresh token 立即失效（Redis 已删）
- [ ] GitHub OAuth 登录后回调 302 重定向到前端 `/#/oauth/callback`，前端写入 localStorage 并跳转 `/chat`
- [ ] 钉钉 OAuth 流程中 state 经 Redis 校验，伪造 state 返回 401
- [ ] 不再有任何 `setCookie` / `Cookie` 读写代码（前端后端均无）
- [ ] 单元测试覆盖：TokenService 的生成/查询/吊销/轮换，SessionFilter 的鉴权分支

## 13. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| Redis 故障导致全部用户掉线 | 高 | 部署 Redis 主从；故障时降级返回 503 而非 401 |
| access token 15min 过短增加 refresh 负载 | 中 | 监控 refresh 接口 QPS，必要时调整为 30min |
| refresh token rotation 并发问题 | 中 | 用 Redis Lua 脚本保证原子性（查询+标记+签发） |
| localStorage XSS 风险 | 中 | CSP + 输入过滤 + 安全审计 |
| 钉钉 state 透传机制未确认 | 中 | 实施阶段优先验证钉钉回调是否原样带回 state |

## 14. 后续演进（不在本次范围）

- 多应用 SSO：引入 sso-center 服务，统一登录跳转，各应用通过 appId 查询 Redis 共享 session
- 设备管理：基于 `auth:user:{userId}:sessions` 记录设备信息，支持踢人
- MFA：refresh 接口增加二次验证
- 风控：异常 IP、地理位置检测，触发强制重登
- 切换 HttpOnly Cookie：若 XSS 风险升高，可切换为 Cookie + CSRF Token 方案
