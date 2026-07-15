# 钉钉 OAuth 登录改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将钉钉登录从"前端自渲染二维码弹窗"改为标准 OAuth 2.0 302 跳转模式，与 GitHub 登录方案一致

**Architecture:** 后端 `/oauth2/dingtalk/authorize` 从返回 JSON `{authUrl}` 改为 `response.sendRedirect(url)`（与 `/oauth2/github` 对称）；前端钉钉按钮从打开弹窗改为整页跳转；移除不再需要的 `DingTalkQrModal` 组件

**Tech Stack:** Spring Boot (Java), Vue 3 + Naive UI (Vite)

---

## 改动总览

| 文件 | 操作 | 说明 |
|------|------|------|
| `OAuth2Controller.java:71-82` | 修改 | `dingtalkAuthorize()` 改为 `response.sendRedirect()` |
| `OAuthButtons.vue:39-41` | 修改 | `handleDingTalk()` 改为 `window.location.href` |
| `Login.vue:21,24-27,48` | 修改 | 移除 `DingTalkQrModal` 引用和状态 |
| `DingTalkQrModal.vue` | 删除 | 不再使用 |

### 不动

`OAuth2Controller.callback()`、`DingTalkAuthenticator`、`UserLinkService`、`TokenService`、`SessionFilter`、`OAuthCallback.vue`、GitHub 整套流程。

---

### Task 1: 后端 — 改 dingtalkAuthorize() 为 302 重定向

**Files:**
- Modify: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/controller/OAuth2Controller.java:71-82`

**Interfaces:**
- Consumes: 无（从 `OAuth2Config` 和 `StringRedisTemplate` 读取配置，已在构造函数中注入）
- Produces: `GET /oauth2/dingtalk/authorize` 改为 302 重定向，不再返回 JSON

- [ ] **Step 1: 修改方法签名和实现**

当前代码（第 71-82 行）：
```java
@GetMapping("/dingtalk/authorize")
public ResponseEntity<Map<String, String>> dingtalkAuthorizeUrl() {
    OAuth2Config.ProviderConfig dingtalk = config.getDingtalk();
    String state = generateState();
    redis.opsForValue().set(stateKey(state), "dingtalk",
            Duration.ofSeconds(authProps.getOauthStateTtlSeconds()));

    String url = String.format(
            "https://login.dingtalk.com/oauth2/auth?redirect_uri=%s&response_type=code&client_id=%s&scope=openid&prompt=consent&state=%s",
            dingtalk.getRedirectUri(), dingtalk.getClientId(), state);
    return ResponseEntity.ok(Map.of("authUrl", url));
}
```

改为（与 `githubAuthorize()` 对称）：
```java
@GetMapping("/dingtalk/authorize")
public void dingtalkAuthorize(HttpServletResponse response) throws IOException {
    OAuth2Config.ProviderConfig dingtalk = config.getDingtalk();
    String state = generateState();
    redis.opsForValue().set(stateKey(state), "dingtalk",
            Duration.ofSeconds(authProps.getOauthStateTtlSeconds()));

    String url = String.format(
            "https://login.dingtalk.com/oauth2/auth?redirect_uri=%s&response_type=code&client_id=%s&scope=openid&prompt=consent&state=%s",
            dingtalk.getRedirectUri(), dingtalk.getClientId(), state);
    response.sendRedirect(url);
}
```

需要新增 import：`jakarta.servlet.http.HttpServletResponse`（检查是否已有，第 3 行已导入 `jakarta.servlet.http.HttpServletResponse`）

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl zhiliao-auth -am -q
```
预期：BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/controller/OAuth2Controller.java
git commit -m "fix(auth): 钉钉 OAuth 改为 302 重定向模式，与 GitHub 方案一致"
```

---

### Task 2: 前端 — 按钮改整页跳转 + 移除二维码弹窗

**Files:**
- Modify: `../ui/liar-zhiliao-ui/src/components/OAuthButtons.vue:39-41`
- Modify: `../ui/liar-zhiliao-ui/src/views/Login.vue`
- Delete: `../ui/liar-zhiliao-ui/src/components/DingTalkQrModal.vue`

**Interfaces:**
- Consumes: 来自于 Task 1 的 `GET /oauth2/dingtalk/authorize`（现在返回 302）
- Produces: 钉钉登录按钮执行整页跳转，无弹窗

- [ ] **Step 1: 改 OAuthButtons.vue — handleDingTalk 整页跳转**

当前第 39-41 行：
```js
function handleDingTalk() {
  emit('open-dingtalk-qr')
}
```

改为：
```js
function handleDingTalk() {
  window.location.href = '/oauth2/dingtalk/authorize'
}
```

同时可以移除 `defineEmits` 中的 `'open-dingtalk-qr'`：
```js
const emit = defineEmits(['open-dingtalk-qr'])  // 改为空数组
// 但保留 emit 变量以便 GitHub 使用（实际 GitHub 不用 emit，但为安全可以不删）
```

- [ ] **Step 2: 改 Login.vue — 移除 DingTalkQrModal 相关代码**

三处改动：

第 21 行：
```vue
      <OAuthButtons @open-dingtalk-qr="showDingTalkQr = true" />
```
改为：
```vue
      <OAuthButtons />
```

第 24-27 行，移除整个 DingTalkQrModal 模板：
```vue
      <!-- 钉钉扫码弹窗 -->
      <DingTalkQrModal
        :show="showDingTalkQr"
        @close="showDingTalkQr = false"
      />
```

第 40 行：
```js
import DingTalkQrModal from '../components/DingTalkQrModal.vue'
```

第 48 行：
```js
const showDingTalkQr = ref(false)
```

- [ ] **Step 3: 删除 DingTalkQrModal.vue**

```bash
rm ../ui/liar-zhiliao-ui/src/components/DingTalkQrModal.vue
```

- [ ] **Step 4: 前端编译验证**

```bash
cd ../ui/liar-zhiliao-ui && npx vite build 2>&1 | tail -5
```
预期：构建成功，无报错

- [ ] **Step 5: Commit**

```bash
git add ../ui/liar-zhiliao-ui/src/components/OAuthButtons.vue \
        ../ui/liar-zhiliao-ui/src/views/Login.vue \
        ../ui/liar-zhiliao-ui/src/components/DingTalkQrModal.vue
git commit -m "fix(ui): 钉钉登录改为整页跳转，移除二维码弹窗组件"
```
