# OAuth 回调跳转前端独立站点 — 设计

## 背景

现有 OAuth 回调(`OAuth2Controller.buildCallbackHtml`)在返回的 HTML 脚本里写死 `window.location.href='/'`,假设前端与后端同源(都跑在 8080)。实际前端是独立站点(开发环境 `http://localhost:5173`,生产环境另有地址)。

直接把跳转地址改成前端 URL 后,出现跨域 localStorage 隔离问题:后端在 8080 域下用 `localStorage.setItem` 写的 token,5173 域读不到,前端 `stores/auth.js` 初始化时 `accessToken` 为空 → 路由守卫放行进 Chat → Chat 调 API 401 → `tryRefresh` 也空 → `clearAuthAndRedirect` 跳 `/login`。

## 目标

OAuth 回调成功后,把 token 传递给前端独立站点(5173),由前端在自己的域下写入 localStorage 并跳转首页。前端地址由配置驱动,支持开发/生产环境差异。

## 非目标

- 不改 `redirect-uri`(OAuth provider 回到后端 8080 的地址,与前端无关)
- 不改钉钉流程(前端拿 `authUrl` 自己跳)
- 不做"跳回原始路径"的透传方案(本次只需固定跳首页)
- 不引入 postMessage / 弹窗模式(当前是整页跳转,不适用)

## 设计

### 1. 新增配置项 `zhiliao.auth.web-frontend-base-url`

`AuthProperties` 增加字段:

```java
/** 前端站点根地址,OAuth 回调跳转用 */
private String webFrontendBaseUrl;
```

`application.yaml`:

```yaml
zhiliao:
  auth:
    web-frontend-base-url: ${WEB_FRONTEND_BASE_URL:http://localhost:5173}
```

- 开发环境默认 `http://localhost:5173`(由 application.yaml 的 `${WEB_FRONTEND_BASE_URL:http://localhost:5173}` 兜底)
- 生产环境通过 `WEB_FRONTEND_BASE_URL` 环境变量覆盖

配置项放在 `AuthProperties` 而非 `OAuth2Config`,因为前端地址是应用级配置,与 OAuth provider 无关。

### 2. 后端回调改为 302 重定向,token 走 URL fragment

`OAuth2Controller.callback` 在签发 token 后,不再返回 HTML,而是 302 重定向到前端:

```
{webFrontendBaseUrl}/#/oauth/callback?accessToken=...&refreshToken=...&username=...
```

每个参数值用 `URLEncoder.encode(value, StandardCharsets.UTF_8)` 编码。

```java
String redirectUrl = authProps.getWebFrontendBaseUrl()
        + "/#/oauth/callback"
        + "?accessToken=" + URLEncoder.encode(pair.accessToken(), StandardCharsets.UTF_8)
        + "&refreshToken=" + URLEncoder.encode(pair.refreshToken(), StandardCharsets.UTF_8)
        + "&username=" + URLEncoder.encode(pair.user().username(), StandardCharsets.UTF_8);
response.sendRedirect(redirectUrl);
```

**为何用 fragment (`#`) 而非 query (`?`):** fragment 不会发到服务器,不会被代理/CDN/访问日志记录,也不会出现在 Referer 头里,泄露面比 query 小。

**为何不用 HTML 脚本写 localStorage:** 跨域隔离。后端 8080 写的 localStorage,前端 5173 读不到。改由前端在自己的域下写。

**删除的代码:** `buildCallbackHtml` 和 `jsString` 两个辅助方法不再需要,一并删除;`MediaType`、`HttpHeaders` import 清理。

### 3. 前端新增 `/oauth/callback` 路由与组件

**路由**(`src/router/index.js`):新增 `/oauth/callback` 路由,指向 `OAuthCallback.vue`,不带 `requiresAuth`(它自己写 token)。

**组件**(`src/views/OAuthCallback.vue`):
- `onMounted` 从 `window.location.hash` 解析 `accessToken` / `refreshToken` / `username` 三个参数
- 三个参数任一缺失 → 提示错误并跳 `/login`
- 参数齐全 → 调 `auth.setTokens(payload)` 写入 store + localStorage → `router.replace({name:'Chat'})`
- 不调 `initAuth`,由 `App.vue` 的 `onMounted` 负责调 `/api/auth/me` 恢复 user 信息

**store**(`src/stores/auth.js`):新增 `setTokens(payload)` 方法,封装"写 store ref + 写 localStorage",供 OAuthCallback 调用。

### 4. 时序

Vue 子组件 `onMounted` 先于父组件执行,所以:

1. 浏览器被 302 到 `http://localhost:5173/#/oauth/callback?...`
2. Vue 应用启动,路由守卫 `beforeEach` 见 `!auth.initialized` 放行
3. `OAuthCallback` 挂载,`onMounted` 跑:写 token → `router.replace({name:'Chat'})`
4. 路由跳转,守卫仍因 `!auth.initialized` 放行,进入 Chat
5. `App.vue` 的 `onMounted` 跑:调 `auth.initAuth()` → token 已存在 → 调 `/me` 恢复 user → `initialized=true`

### 5. 不改的部分

| 项 | 原因 |
|----|------|
| `oauth2.*.redirect-uri` | 这是 OAuth provider 回到后端的地址,与前端无关 |
| 钉钉流程 | 前端拿 `authUrl` 自己跳,回调仍回 8080 |
| `SessionFilter` 放行规则 | `/oauth2/` 路径不变 |
| 前端 axios 拦截器 / refresh 逻辑 | refresh 走 `/api/auth/refresh` JSON,与 OAuth 回调无关 |

## 影响面

| 文件 | 改动 |
|------|------|
| `AuthProperties.java` | 加 `webFrontendBaseUrl` 字段 |
| `OAuth2Controller.java` | `callback` 改 302;删 `buildCallbackHtml`、`jsString`;清理 import |
| `application.yaml` | 加 `web-frontend-base-url` 配置项 |
| 前端 `router/index.js` | 加 `/oauth/callback` 路由 |
| 前端 `stores/auth.js` | 加 `setTokens` 方法 |
| 前端 `views/OAuthCallback.vue` | 新建 |
| `docs/superpowers/specs/2026-07-14-sso-oauth2-enterprise-design.md` | 修订回调返回方式与跳转描述 |

## 测试

- 开发环境:`WEB_FRONTEND_BASE_URL` 不设,GitHub OAuth 授权后浏览器地址变为 `http://localhost:5173/#/chat`,localStorage(5173 域)有 `accessToken` / `refreshToken` / `username`,Chat 页面正常加载
- 生产环境:设 `WEB_FRONTEND_BASE_URL=https://your-frontend.example.com`,回调后跳到该地址的 `/#/chat`
- 参数缺失:手动访问 `/oauth/callback` 不带参数 → 提示"登录回调参数缺失" → 跳 `/login`
- token 无效(伪造):callback 写入 token 后,App.vue 调 `/me` 返回 401 → `initAuth` 的 catch 清掉 token → 用户在 Chat 页触发 API 时 401 → 跳 `/login`

## 安全考量

- token 在 URL fragment 中短暂出现,前端写入 localStorage 后建议清除地址栏(本次未实现,因 `router.replace` 已改变 hash,原 URL 不再可见)
- fragment 不会被服务器日志记录,但浏览器历史会保留一条。用户可手动清除,或前端在 callback 完成后用 `history.replaceState` 清理
- access token TTL 15min,refresh token TTL 7day,泄露窗口有上限
