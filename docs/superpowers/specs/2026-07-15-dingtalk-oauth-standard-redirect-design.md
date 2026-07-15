# 钉钉 OAuth 登录改造：标准 302 重定向模式

## 概述

将钉钉扫码登录从"前端自渲染二维码弹窗"模式改为标准 OAuth 2.0 Authorization Code 流程，与现有 GitHub 登录方案一致。点击按钮后整页跳转到钉钉授权页，钉钉直接在 PC 浏览器上展示二维码，扫码授权后回调结果回到 PC 浏览器。

## 背景

当前实现存在根本性缺陷：

1. 前端从后端获取钉钉授权 URL，用 `qrcode` 库自行渲染二维码弹窗
2. 手机扫码后，钉钉的 OAuth 重定向发生在**手机浏览器**中
3. 后端回调成功后 302 到前端 `http://localhost:5173/...`——从手机看 `localhost` 是手机自己
4. 结果：手机显示 -1004 网络错误，Token 落在手机浏览器，PC 前端无反应

**根因**：把标准 OAuth 2.0 的同设备重定向流程强行套在了跨设备扫码场景上，绕过钉钉自带的跨设备桥接能力。

## 新流程

```
Login.vue
  │ 点击「钉钉登录」
  │ window.location.href = '/oauth2/dingtalk/authorize'
  ▼
OAuth2Controller.dingtalkAuthorize()
  │ 生成 state → 存 Redis（TTL 300s，防 CSRF 重放）
  │ 302 → https://login.dingtalk.com/oauth2/auth?...&state=...
  ▼
PC 浏览器打开钉钉授权页
  │ 钉钉页面在 PC 屏幕显示二维码
  │ 用户手机钉钉扫码 → 手机确认授权
  ▼
钉钉将 PC 浏览器重定向到 redirect_uri
  │ GET /oauth2/dingtalk/callback?code=xxx&state=yyy
  ▼
OAuth2Controller.callback("dingtalk", code, state)
  │ 1. 校验 state → 删除（防重放）
  │ 2. DingTalkAuthenticator.authenticate(code) → accessToken → 用户信息
  │ 3. UserLinkService.linkOrCreate → 关联/创建本地用户
  │ 4. TokenService.issueToken → 签发 tokenPair
  │ 5. 302 → /#/oauth/callback?accessToken=...&refreshToken=...
  ▼
OAuthCallback.vue
  │ 从 hash 解析 token → 写 localStorage → router.replace('/chat')
  ▼
App.vue onMounted → initAuth() → 恢复会话 → 进入 Chat
```

**核心变化**：整个 OAuth 流程的用户代理始终是 PC 浏览器。手机只负责扫码和授权确认，钉钉服务端将认证结果送回 PC 浏览器，不存在跨设备 token 传递问题。

## 改动清单

### 后端（1 处）

| 文件 | 行号 | 改动 |
|------|------|------|
| `OAuth2Controller.java` | 71-82 | `dingtalkAuthorizeUrl()` 从返回 JSON `{authUrl}` 改为 `response.sendRedirect(url)` |

签名和实现与 `githubAuthorize()` 完全对称。

### 前端（3 处）

| 文件 | 行号 | 改动 |
|------|------|------|
| `OAuthButtons.vue` | 39-41 | `handleDingTalk()` 从 `emit('open-dingtalk-qr')` 改为 `window.location.href = '/oauth2/dingtalk/authorize'` |
| `Login.vue` | 21, 24-27, 48 | 移除 `DingTalkQrModal` 组件、`showDingTalkQr` 状态、`@open-dingtalk-qr` 事件 |
| `DingTalkQrModal.vue` | 整文件 | 删除（不再使用） |

### 完全不动

| 组件 | 不动的原因 |
|------|-----------|
| `OAuth2Controller.callback()` | 统一回调入口，参数校验、认证器路由、token 签发、重定向逻辑不变 |
| `DingTalkAuthenticator` | code → accessToken → 用户信息的 API 调用不变 |
| `UserLinkService` | 用户关联/创建逻辑不变 |
| `TokenService` | access/refresh token 签发逻辑不变 |
| `SessionFilter` | `/oauth2/` 路径已白名单，无鉴权问题 |
| `OAuthCallback.vue` | hash 解析 token、写 localStorage、跳转 chat 逻辑不变 |
| GitHub OAuth | `/oauth2/github` 和 GitHub callback 不受影响 |

## 边界情况

| 场景 | 处理 |
|------|------|
| 用户点击后在钉钉页取消授权 | 钉钉不会回调，PC 浏览器留在钉钉页面，用户可手动返回 |
| state 过期（5min 后回调） | 后端校验 Redis key 不存在 → 401 `Invalid OAuth state` |
| state 重放攻击 | callback 校验后立即删除 key，二次使用直接拒绝 |
| 手机扫码后断网 | 钉钉端提示网络错误，PC 浏览器无影响（停留在钉钉页面） |
| redirect_uri 不可达 | 钉钉开放平台侧配置问题，需确保 `192.168.1.13:8080` 可达 |

## 回滚方案

如果改造后出现问题，恢复方式：
1. 后端：将 `dingtalkAuthorize()` 改回 `ResponseEntity<Map>` 返回 JSON
2. 前端：恢复 `DingTalkQrModal.vue`、`showDingTalkQr` 状态、事件绑定
