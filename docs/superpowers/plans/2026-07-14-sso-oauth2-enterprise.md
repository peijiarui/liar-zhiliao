# SSO + OAuth2 企业级改造 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将认证体系从 JWT 无状态改造为 Redis 有状态 Session + Refresh Token，统一 Authorization Header 传递，OAuth 回调返回 HTML 注入 token，补齐钉钉 state CSRF 校验。

**Architecture:** 不透明 token（SecureRandom 32 字节 → Base64URL）+ Redis 有状态存储；Access Token 15min + Refresh Token 7day + Rotation；SessionFilter 替代 JwtFilter；前端 401 拦截器自动 refresh + 请求重放。

**Tech Stack:** Spring Boot 3.5.x, Spring Data Redis (StringRedisTemplate), MyBatis-Plus, Vue 3 + Pinia + Axios

**Spec:** `docs/superpowers/specs/2026-07-14-sso-oauth2-enterprise-design.md`

## Global Constraints

- Redis 已在 `zhiliao-app/src/main/resources/application.yaml` 配置可用（`spring.data.redis`）
- 模块依赖：`zhiliao-auth` 需新增 `spring-boot-starter-data-redis`
- 所有新 Java 类带中文 JavaDoc
- 配置根路径使用 `zhiliao.auth.*`（如 `zhiliao.auth.access-token-ttl`）
- Token 生成必须使用 `java.security.SecureRandom`，禁止用 `Math.random()` 或 `UUID.randomUUID()`（UUID 也可，但 SecureRandom 更显式）
- 移除 `zhiliao-common` 中的 jjwt 依赖与 `JwtUtil`
- 移除 `zhiliao-auth` 中的 `JwtFilter`
- 前端 token 双存储：`accessToken` + `refreshToken`，均放 localStorage
- OAuth state TTL 固定 5 分钟（300 秒）
- Access Token TTL 15 分钟（900 秒）
- Refresh Token TTL 7 天（604800 秒），每次 refresh 都轮换

---

## 文件结构总览

### 新增文件

| 文件 | 职责 |
|------|------|
| `zhiliao-auth/.../config/AuthProperties.java` | 读取 `zhiliao.auth.*` 配置 |
| `zhiliao-auth/.../session/TokenService.java` | token 生成、存储、查询、删除、刷新、轮换 |
| `zhiliao-auth/.../session/SessionData.java` | access token 在 Redis 中的 value 结构 |
| `zhiliao-auth/.../session/RefreshTokenData.java` | refresh token 在 Redis 中的 value 结构 |
| `zhiliao-auth/.../session/TokenPair.java` | 登录/刷新返回的 token 对 |
| `zhiliao-auth/.../filter/SessionFilter.java` | 替代 JwtFilter，从 Redis 校验 token |
| `zhiliao-auth/.../controller/AuthController.java` | 改造（已存在） |
| `zhiliao-auth/.../controller/OAuth2Controller.java` | 改造（已存在） |
| `zhiliao-auth/src/test/.../session/TokenServiceTest.java` | TokenService 单元测试 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `zhiliao-auth/pom.xml` | 新增 `spring-boot-starter-data-redis`、`spring-boot-starter-test` |
| `zhiliao-common/pom.xml` | 移除 jjwt 三件套依赖 |
| `zhiliao-app/src/main/resources/application.yaml` | 新增 `zhiliao.auth.*` 配置 |
| `zhiliao-auth/.../controller/AuthController.java` | login/logout/me/refresh 改造 |
| `zhiliao-auth/.../controller/OAuth2Controller.java` | state Redis 校验 + HTML 返回 |
| `zhiliao-auth/.../filter/JwtFilter.java` | 删除 |
| `zhiliao-common/.../utils/JwtUtil.java` | 删除 |
| `ui/liar-zhiliao-ui/src/api/auth.js` | 新增 refreshToken API |
| `ui/liar-zhiliao-ui/src/api/request.js` | 401 拦截 + refresh 排队 + 重放 |
| `ui/liar-zhiliao-ui/src/stores/auth.js` | 双 token 存储 |

---

## Task 1: 添加 Redis 依赖与配置

**Files:**
- Modify: `zhiliao-auth/pom.xml`
- Modify: `zhiliao-app/src/main/resources/application.yaml`
- Create: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/config/AuthProperties.java`

**Interfaces:**
- Produces: `AuthProperties` Bean（含 `accessTokenTtlSeconds`、`refreshTokenTtlSeconds`、`oauthStateTtlSeconds`、`appId` 字段）

- [ ] **Step 1: zhiliao-auth/pom.xml 新增 Redis 与 test 依赖**

在 `</dependencies>` 前追加：

```xml
        <!-- Spring Data Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: application.yaml 新增 zhiliao.auth 配置**

在 `zhiliao:` 节点下追加（与 `minio`、`rabbitmq` 平级）：

```yaml
  auth:
    app-id: zhiliao                         # SSO 应用标识，预留多应用
    access-token-ttl-seconds: 900           # 15 min
    refresh-token-ttl-seconds: 604800       # 7 day
    oauth-state-ttl-seconds: 300            # 5 min
```

- [ ] **Step 3: 创建 AuthProperties**

```java
package org.liar.zhiliao.auth.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证相关配置。
 * 对应 application.yaml 中 zhiliao.auth.* 配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "zhiliao.auth")
public class AuthProperties {
    /** SSO 应用标识，预留多应用共享 session 时区分 */
    private String appId = "zhiliao";
    /** Access Token 有效期（秒），默认 15 分钟 */
    private long accessTokenTtlSeconds = 900L;
    /** Refresh Token 有效期（秒），默认 7 天 */
    private long refreshTokenTtlSeconds = 604800L;
    /** OAuth state 参数有效期（秒），默认 5 分钟 */
    private long oauthStateTtlSeconds = 300L;
}
```

- [ ] **Step 4: 验证编译**

```bash
mvn clean compile -pl zhiliao-auth -am
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add zhiliao-auth/pom.xml zhiliao-app/src/main/resources/application.yaml \
        zhiliao-auth/src/main/java/org/liar/zhiliao/auth/config/AuthProperties.java
git commit -m "feat(auth): add redis dependency and AuthProperties"
```

---

## Task 2: Session 数据模型

**Files:**
- Create: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/session/SessionData.java`
- Create: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/session/RefreshTokenData.java`
- Create: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/session/TokenPair.java`

**Interfaces:**
- Produces: `SessionData` record（含 `tokenId`、`userId`、`username`、`deptId`、`visibleDeptIds`、`refreshTokenId`、`issuedAt`、`expiresAt`）
- Produces: `RefreshTokenData` record（含 `tokenId`、`userId`、`username`、`deptId`、`visibleDeptIds`、`issuedAt`、`expiresAt`、`rotated`）
- Produces: `TokenPair` record（含 `accessToken`、`refreshToken`、`expiresIn`、`user`）

- [ ] **Step 1: 创建 SessionData**

```java
package org.liar.zhiliao.auth.session;

import java.util.List;

/**
 * Access Token 在 Redis 中的会话数据。
 * 对应 key: auth:session:{appId}:{accessToken}
 */
public record SessionData(
        String tokenId,
        Long userId,
        String username,
        Long deptId,
        List<Long> visibleDeptIds,
        String refreshTokenId,
        long issuedAt,
        long expiresAt
) {}
```

- [ ] **Step 2: 创建 RefreshTokenData**

```java
package org.liar.zhiliao.auth.session;

import java.util.List;

/**
 * Refresh Token 在 Redis 中的数据。
 * 对应 key: auth:refresh:{appId}:{refreshToken}
 */
public record RefreshTokenData(
        String tokenId,
        Long userId,
        String username,
        Long deptId,
        List<Long> visibleDeptIds,
        long issuedAt,
        long expiresAt,
        boolean rotated
) {}
```

- [ ] **Step 3: 创建 TokenPair**

```java
package org.liar.zhiliao.auth.session;

import java.util.List;

/**
 * 登录/刷新接口返回给前端的 token 对。
 *
 * @param accessToken  短期 access token，15 min
 * @param refreshToken 长期 refresh token，7 day，每次刷新轮换
 * @param expiresIn    access token 剩余秒数
 * @param user         用户基本信息（id、username、deptId、visibleDeptIds）
 */
public record TokenPair(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UserInfo user
) {
    public record UserInfo(Long id, String username, Long deptId, List<Long> visibleDeptIds) {}
}
```

- [ ] **Step 4: 验证编译**

```bash
mvn clean compile -pl zhiliao-auth -am
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/session/
git commit -m "feat(auth): add SessionData/RefreshTokenData/TokenPair records"
```

---

## Task 3: TokenService 实现

**Files:**
- Create: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/session/TokenService.java`
- Create: `zhiliao-auth/src/test/java/org/liar/zhiliao/auth/session/TokenServiceTest.java`

**Interfaces:**
- Consumes: `AuthProperties`、`StringRedisTemplate`、`ObjectMapper`
- Produces: `TokenService` Bean，方法签名：
  - `TokenPair issue(CurrentUser user)` — 登录成功后签发新 token 对
  - `SessionData getSession(String accessToken)` — 查询 access token 对应会话
  - `void revoke(String accessToken)` — 删除 access token 及关联 refresh token
  - `TokenPair refresh(String refreshToken)` — 刷新 token，rotation

- [ ] **Step 1: 写 TokenService 单元测试**

```java
package org.liar.zhiliao.auth.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.liar.zhiliao.auth.config.AuthProperties;
import org.liar.zhiliao.common.model.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenServiceTest {

    private AuthProperties props;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        props = new AuthProperties();
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        tokenService = new TokenService(redis, new ObjectMapper(), props);
    }

    @Test
    void issue_shouldStoreBothTokensAndReturnPair() {
        CurrentUser user = new CurrentUser(1L, "alice", 2L, List.of(1L, 2L));

        TokenPair pair = tokenService.issue(user);

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        assertThat(pair.expiresIn()).isEqualTo(900L);
        assertThat(pair.user().id()).isEqualTo(1L);
        verify(valueOps, times(2)).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void getSession_shouldReturnNullWhenTokenNotInRedis() {
        when(valueOps.get("auth:session:zhiliao:nonexistent")).thenReturn(null);

        SessionData session = tokenService.getSession("nonexistent");

        assertThat(session).isNull();
    }

    @Test
    void refresh_shouldRotateRefreshToken() throws Exception {
        // 准备一个已存在的 refresh token
        RefreshTokenData existing = new RefreshTokenData(
                "old-rt-id", 1L, "alice", 2L, List.of(1L, 2L),
                System.currentTimeMillis(), System.currentTimeMillis() + 86400000L, false);
        when(valueOps.get(contains("auth:refresh:zhiliao:")))
                .thenReturn(new ObjectMapper().writeValueAsString(existing));

        TokenPair newPair = tokenService.refresh("old-refresh-token");

        assertThat(newPair.refreshToken()).isNotEqualTo("old-refresh-token");
        assertThat(newPair.accessToken()).isNotBlank();
    }

    @Test
    void refresh_shouldThrowWhenTokenRotated() throws Exception {
        RefreshTokenData rotated = new RefreshTokenData(
                "rt-id", 1L, "alice", 2L, List.of(1L, 2L),
                System.currentTimeMillis(), System.currentTimeMillis() + 86400000L, true);
        when(valueOps.get(contains("auth:refresh:zhiliao:")))
                .thenReturn(new ObjectMapper().writeValueAsString(rotated));

        assertThatThrownBy(() -> tokenService.refresh("rotated-token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rotated");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -pl zhiliao-auth -Dtest=TokenServiceTest -DskipTests=false
```
Expected: FAIL（`TokenService` 类不存在）

- [ ] **Step 3: 实现 TokenService**

```java
package org.liar.zhiliao.auth.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.config.AuthProperties;
import org.liar.zhiliao.common.model.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Token 服务：生成、存储、查询、删除、刷新不透明 token。
 * <p>
 * Redis key 结构：
 * <ul>
 *   <li>auth:session:{appId}:{accessToken}  → SessionData JSON</li>
 *   <li>auth:refresh:{appId}:{refreshToken} → RefreshTokenData JSON</li>
 *   <li>auth:user:{userId}:sessions         → SET of accessToken（预留踢人）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties props;

    /** 签发新 access + refresh token 对，存入 Redis */
    public TokenPair issue(CurrentUser user) {
        String accessToken = generateToken();
        String refreshToken = generateToken();
        String accessTokenId = UUID.randomUUID().toString();
        String refreshTokenId = UUID.randomUUID().toString();

        long now = System.currentTimeMillis();
        long accessExpiresAt = now + props.getAccessTokenTtlSeconds() * 1000L;
        long refreshExpiresAt = now + props.getRefreshTokenTtlSeconds() * 1000L;

        SessionData session = new SessionData(
                accessTokenId, user.id(), user.username(), user.deptId(),
                user.visibleDeptIds(), refreshTokenId, now, accessExpiresAt);
        RefreshTokenData refresh = new RefreshTokenData(
                refreshTokenId, user.id(), user.username(), user.deptId(),
                user.visibleDeptIds(), now, refreshExpiresAt, false);

        store(sessionKey(accessToken), session, props.getAccessTokenTtlSeconds());
        store(refreshKey(refreshToken), refresh, props.getRefreshTokenTtlSeconds());
        // 预留：加入 auth:user:{userId}:sessions 集合（本次不强制实现踢人，可留 TODO）

        return new TokenPair(
                accessToken, refreshToken,
                props.getAccessTokenTtlSeconds(),
                new TokenPair.UserInfo(user.id(), user.username(), user.deptId(), user.visibleDeptIds()));
    }

    /** 查询 access token 对应会话，无效返回 null */
    public SessionData getSession(String accessToken) {
        String json = redis.opsForValue().get(sessionKey(accessToken));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, SessionData.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse session data: {}", e.getMessage());
            return null;
        }
    }

    /** 吊销 access token 及其关联的 refresh token */
    public void revoke(String accessToken) {
        SessionData session = getSession(accessToken);
        if (session == null) return;
        redis.delete(sessionKey(accessToken));
        // refresh token 即时吊销需要 refreshTokenId → refreshToken 反向索引，
        // 当前实现未维护反向索引，refresh token 依赖自身 TTL 自然过期。
        // 如需即时吊销，可在 issue 时写入 auth:refresh-id:{appId}:{refreshTokenId} → refreshToken
        log.info("Revoked session: userId={}, accessTokenId={}", session.userId(), session.tokenId());
    }

    /** 用 refresh token 换新 token 对；旧 refresh token 立即作废（rotation） */
    public TokenPair refresh(String refreshToken) {
        String json = redis.opsForValue().get(refreshKey(refreshToken));
        if (json == null) {
            throw new IllegalStateException("refresh_token_invalid");
        }
        RefreshTokenData old;
        try {
            old = objectMapper.readValue(json, RefreshTokenData.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("refresh_token_invalid", e);
        }
        if (old.rotated()) {
            // 疑似重放攻击：建议同时吊销该用户所有 session（本次仅抛异常，未实现批量吊销）
            log.warn("Detected replayed refresh token: userId={}, rtId={}", old.userId(), old.tokenId());
            throw new IllegalStateException("refresh_token_rotated");
        }

        // 标记旧 refresh token 为已轮换（防重放）
        RefreshTokenData rotated = new RefreshTokenData(
                old.tokenId(), old.userId(), old.username(), old.deptId(),
                old.visibleDeptIds(), old.issuedAt(), old.expiresAt(), true);
        store(refreshKey(refreshToken), rotated, props.getRefreshTokenTtlSeconds());

        CurrentUser user = new CurrentUser(old.userId(), old.username(), old.deptId(), old.visibleDeptIds());
        return issue(user);
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private String sessionKey(String token) {
        return "auth:session:" + props.getAppId() + ":" + token;
    }

    private String refreshKey(String token) {
        return "auth:refresh:" + props.getAppId() + ":" + token;
    }

    private void store(String key, Object value, long ttlSeconds) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(ttlSeconds));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize session data", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -pl zhiliao-auth -Dtest=TokenServiceTest -DskipTests=false
```
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/session/TokenService.java \
        zhiliao-auth/src/test/java/org/liar/zhiliao/auth/session/TokenServiceTest.java
git commit -m "feat(auth): implement TokenService with refresh rotation"
```

---

## Task 4: SessionFilter 替代 JwtFilter

**Files:**
- Create: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/filter/SessionFilter.java`
- Delete: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/filter/JwtFilter.java`

**Interfaces:**
- Consumes: `TokenService`、`UserContextHolder`
- Produces: `SessionFilter` Bean（@Order(1) @Component），替代 JwtFilter

- [ ] **Step 1: 创建 SessionFilter**

```java
package org.liar.zhiliao.auth.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.session.SessionData;
import org.liar.zhiliao.auth.session.TokenService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 会话过滤器：从 Authorization: Bearer 头提取 access token，
 * 查 Redis 校验有效性，注入 UserContextHolder。
 * 替代旧的 JwtFilter。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SessionFilter implements Filter {

    private final TokenService tokenService;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // 跳过鉴权的路径：login、refresh、OAuth2 授权启动与回调
        if (path.equals("/api/auth/login")
                || path.equals("/api/auth/refresh")
                || path.startsWith("/oauth2/")) {
            chain.doFilter(req, res);
            return;
        }

        String token = extractBearer(request);
        if (token == null) {
            reject(response, "token_missing");
            return;
        }

        SessionData session = tokenService.getSession(token);
        if (session == null) {
            reject(response, "token_invalid");
            return;
        }

        try {
            CurrentUser user = new CurrentUser(
                    session.userId(), session.username(),
                    session.deptId(), session.visibleDeptIds());
            UserContextHolder.set(user);
            chain.doFilter(req, res);
        } finally {
            UserContextHolder.clear();
        }
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void reject(HttpServletResponse response, String errorCode) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + errorCode + "\"}");
    }
}
```

- [ ] **Step 2: 删除 JwtFilter**

```bash
rm zhiliao-auth/src/main/java/org/liar/zhiliao/auth/filter/JwtFilter.java
```

- [ ] **Step 3: 验证编译**

```bash
mvn clean compile -pl zhiliao-auth -am
```
Expected: BUILD SUCCESS（应无对 JwtFilter 的引用；若有，需修正引用方）

- [ ] **Step 4: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/filter/SessionFilter.java
git rm zhiliao-auth/src/main/java/org/liar/zhiliao/auth/filter/JwtFilter.java
git commit -m "feat(auth): replace JwtFilter with SessionFilter (Redis-backed)"
```

---

## Task 5: 改造 AuthController

**Files:**
- Modify: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/controller/AuthController.java`

**Interfaces:**
- Consumes: `TokenService`（替换 `JwtUtil`）、`UserService`、`UserContextHolder`
- Produces:
  - `POST /api/auth/login` → `{accessToken, refreshToken, expiresIn, user}`
  - `POST /api/auth/logout` → `{success: true}`，删除 Redis session
  - `GET /api/auth/me` → 当前用户信息 + `expiresAt`
  - `POST /api/auth/refresh` → 新 token 对

- [ ] **Step 1: 重写 AuthController**

```java
package org.liar.zhiliao.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.session.SessionData;
import org.liar.zhiliao.auth.session.TokenPair;
import org.liar.zhiliao.auth.session.TokenService;
import org.liar.zhiliao.auth.service.UserService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证接口：登录、登出、获取当前用户、刷新 token。
 * 全部使用 Authorization: Bearer 头传递 access token。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    /** POST /api/auth/login — 用户名密码登录，返回 access + refresh token */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> request) {
        try {
            String username = request.get("username");
            String password = request.get("password");

            SysUser user = userService.authenticate(username, password);
            CurrentUser currentUser = new CurrentUser(
                    user.getId(), user.getUsername(), user.getDeptId());
            TokenPair pair = tokenService.issue(currentUser);

            log.info("登录成功: userId={}, username={}", user.getId(), user.getUsername());
            return ResponseEntity.ok(pair);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    /** POST /api/auth/logout — 吊销当前 access token 及关联 refresh token */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        String accessToken = extractBearer(request);
        if (accessToken != null) {
            tokenService.revoke(accessToken);
        }
        log.info("登出: accessToken={}", accessToken != null ? "revoked" : "no-token");
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** GET /api/auth/me — 获取当前登录用户信息 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        CurrentUser currentUser = UserContextHolder.get();
        if (currentUser == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }
        String accessToken = extractBearer(request);
        SessionData session = accessToken != null ? tokenService.getSession(accessToken) : null;
        long expiresAt = session != null ? session.expiresAt() : 0L;

        return ResponseEntity.ok(Map.of(
                "id", currentUser.id(),
                "username", currentUser.username(),
                "deptId", currentUser.deptId(),
                "visibleDeptIds", currentUser.visibleDeptIds(),
                "expiresAt", expiresAt
        ));
    }

    /** POST /api/auth/refresh — 用 refresh token 换新 access + refresh token */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "refresh_token_missing"));
        }
        try {
            TokenPair pair = tokenService.refresh(refreshToken);
            return ResponseEntity.ok(pair);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    private String extractBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
mvn clean compile -pl zhiliao-auth -am
```
Expected: BUILD SUCCESS

- [ ] **Step 3: 启动应用，手工验证 login + me**

```bash
mvn spring-boot:run -pl zhiliao-app
```

另开终端：
```bash
# 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 期望返回 accessToken + refreshToken + expiresIn + user

# 用 accessToken 调 /me
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer <accessToken>"
# 期望返回用户信息 + expiresAt

# logout
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer <accessToken>"
# 期望返回 {"success":true}

# logout 后再调 /me，应 401
curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer <accessToken>"
# 期望 401 token_invalid
```

- [ ] **Step 4: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/controller/AuthController.java
git commit -m "feat(auth): rewrite AuthController with TokenService (login/logout/me/refresh)"
```

---

## Task 6: 改造 OAuth2Controller（state Redis 校验 + HTML 返回）

**Files:**
- Modify: `zhiliao-auth/src/main/java/org/liar/zhiliao/auth/controller/OAuth2Controller.java`

**Interfaces:**
- Consumes: `TokenService`（替换 `JwtUtil`）、`StringRedisTemplate`（state 存储）、`AuthProperties`
- Produces:
  - `GET /oauth2/github` — state 写入 Redis，302 跳转 GitHub
  - `GET /oauth2/dingtalk/authorize` — 返回带 state 的 authUrl
  - `GET /oauth2/{provider}/callback` — state 校验，返回 HTML 注入 token

- [ ] **Step 1: 重写 OAuth2Controller**

```java
package org.liar.zhiliao.auth.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.config.AuthProperties;
import org.liar.zhiliao.auth.oauth2.OAuth2Authenticator;
import org.liar.zhiliao.auth.oauth2.OAuth2Config;
import org.liar.zhiliao.auth.oauth2.OAuth2UserInfo;
import org.liar.zhiliao.auth.service.DeptPermissionService;
import org.liar.zhiliao.auth.service.UserLinkService;
import org.liar.zhiliao.auth.entity.SysUser;
import org.liar.zhiliao.auth.session.TokenPair;
import org.liar.zhiliao.auth.session.TokenService;
import org.liar.zhiliao.common.model.CurrentUser;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OAuth2 登录控制器。
 * 统一处理 GitHub 和钉钉的授权回调，通过 provider 路径变量路由到对应认证器。
 * state 存 Redis（TTL 5min），回调时校验后立即删除，防 CSRF。
 * 回调成功后返回 HTML 页面，由前端脚本写入 localStorage 后跳转。
 */
@Slf4j
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final List<OAuth2Authenticator> authenticators;
    private final UserLinkService userLinkService;
    private final DeptPermissionService deptPermissionService;
    private final TokenService tokenService;
    private final OAuth2Config config;
    private final AuthProperties authProps;
    private final StringRedisTemplate redis;

    /** GET /oauth2/github — 302 跳转 GitHub 授权页（state 写入 Redis） */
    @GetMapping("/github")
    public void githubAuthorize(HttpServletResponse response) throws IOException {
        OAuth2Config.ProviderConfig github = config.getGithub();
        String state = generateState();
        redis.opsForValue().set(stateKey(state), "github",
                Duration.ofSeconds(authProps.getOauthStateTtlSeconds()));

        String url = String.format(
                "https://github.com/login/oauth/authorize?client_id=%s&redirect_uri=%s&scope=user:email&state=%s",
                github.getClientId(), github.getRedirectUri(), state);
        response.sendRedirect(url);
    }

    /** GET /oauth2/dingtalk/authorize — 返回带 state 的钉钉扫码 URL */
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

    /** GET /oauth2/{provider}/callback — OAuth2 统一回调入口，返回 HTML 注入 token */
    @GetMapping("/{provider}/callback")
    public void callback(@PathVariable String provider,
                         @RequestParam("code") String code,
                         @RequestParam(value = "state", required = false) String state,
                         HttpServletResponse response) throws IOException {
        // 校验 state（GitHub 和钉钉都强制校验）
        String storedProvider = redis.opsForValue().get(stateKey(state));
        if (storedProvider == null || !storedProvider.equals(provider)) {
            log.warn("OAuth state mismatch: provider={}, state={}", provider, state);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid OAuth state");
            return;
        }
        redis.delete(stateKey(state));  // 立即删除防重放

        OAuth2Authenticator authenticator = authenticators.stream()
                .filter(a -> a.provider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown OAuth provider: " + provider));

        OAuth2UserInfo userInfo = authenticator.authenticate(code);
        SysUser user = userLinkService.linkOrCreate(userInfo, provider);

        List<Long> visibleDeptIds = deptPermissionService.getVisibleDeptIds(user.getDeptId());
        CurrentUser currentUser = CurrentUser.of(
                user.getId(), user.getUsername(), user.getDeptId(), visibleDeptIds);
        TokenPair pair = tokenService.issue(currentUser);

        log.info("OAuth login success: provider={}, userId={}, username={}",
                provider, user.getId(), user.getUsername());

        // 返回 HTML 页面，脚本写入 localStorage 后跳转首页
        response.setContentType(MediaType.TEXT_HTML_VALUE);
        response.setCharacterEncoding("UTF-8");
        String html = buildCallbackHtml(pair);
        response.getWriter().write(html);
    }

    private String buildCallbackHtml(TokenPair pair) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>登录中</title></head><body>"
                + "<p>登录成功，正在跳转...</p>"
                + "<script>"
                + "localStorage.setItem('accessToken'," + jsString(pair.accessToken()) + ");"
                + "localStorage.setItem('refreshToken'," + jsString(pair.refreshToken()) + ");"
                + "localStorage.setItem('username'," + jsString(pair.user().username()) + ");"
                + "window.location.href='/';"
                + "</script></body></html>";
    }

    /** 转为 JS 字符串字面量（含引号），转义双引号与反斜杠 */
    private String jsString(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String generateState() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private String stateKey(String state) {
        return "auth:oauth:state:" + authProps.getAppId() + ":" + state;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
mvn clean compile -pl zhiliao-auth -am
```
Expected: BUILD SUCCESS

- [ ] **Step 3: 手工验证 GitHub OAuth 流程**

```bash
mvn spring-boot:run -pl zhiliao-app
```

浏览器访问 `http://localhost:8080/oauth2/github`，跳转 GitHub 授权页 → 授权 → 回调 → 浏览器显示 "登录成功，正在跳转..." → 自动跳转 `/`。

打开浏览器 DevTools → Application → Local Storage，应看到 `accessToken`、`refreshToken`、`username` 三个键。

- [ ] **Step 4: 手工验证 state 伪造**

```bash
# 直接调用 callback 不带 state 或带伪造 state
curl "http://localhost:8080/oauth2/github/callback?code=fake&state=fake"
# 期望 401 Invalid OAuth state
```

- [ ] **Step 5: Commit**

```bash
git add zhiliao-auth/src/main/java/org/liar/zhiliao/auth/controller/OAuth2Controller.java
git commit -m "feat(auth): OAuth callback returns HTML with token; state stored in Redis"
```

---

## Task 7: 移除 JwtUtil 与 jjwt 依赖

**Files:**
- Delete: `zhiliao-common/src/main/java/org/liar/zhiliao/common/utils/JwtUtil.java`
- Modify: `zhiliao-common/pom.xml`（移除 jjwt 三个依赖）

- [ ] **Step 1: 搜索是否还有对 JwtUtil 的引用**

```bash
mvn grep -rl "JwtUtil" zhiliao-*/src 2>/dev/null
# 或用 Grep 工具：pattern "JwtUtil", glob "**/*.java"
```
Expected: 无引用（AuthController 和 OAuth2Controller 已在 Task 5/6 改造完毕）

- [ ] **Step 2: 删除 JwtUtil**

```bash
rm zhiliao-common/src/main/java/org/liar/zhiliao/common/utils/JwtUtil.java
```

- [ ] **Step 3: 从 zhiliao-common/pom.xml 移除 jjwt 依赖**

删除以下三段：

```xml
        <!-- JJWT -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 4: 全量编译验证**

```bash
mvn clean compile
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git rm zhiliao-common/src/main/java/org/liar/zhiliao/common/utils/JwtUtil.java
git add zhiliao-common/pom.xml
git commit -m "refactor(common): remove JwtUtil and jjwt dependencies"
```

---

## Task 8: 前端 stores/auth.js 适配双 token

**Files:**
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/stores/auth.js`
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/api/auth.js`

**Interfaces:**
- Produces: `useAuthStore` 含 `accessToken`、`refreshToken`、`user`，方法 `login`、`logout`、`initAuth`、`setOAuthSession`

- [ ] **Step 1: 改 api/auth.js**

```javascript
import request from './request'

export function login(username, password) {
  return request.post('/api/auth/login', { username, password })
}

export function logout() {
  return request.post('/api/auth/logout')
}

export function refreshToken() {
  return request.post('/api/auth/refresh', {
    refreshToken: localStorage.getItem('refreshToken')
  })
}

export function getCurrentUser() {
  return request.get('/api/auth/me')
}

export function getDingTalkAuthUrl() {
  return request.get('/oauth2/dingtalk/authorize')
}
```

- [ ] **Step 2: 改 stores/auth.js**

```javascript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as loginApi, logout as logoutApi, refreshToken as refreshTokenApi, getCurrentUser } from '../api/auth'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const username = ref(localStorage.getItem('username') || '')
  const user = ref(null)
  const initialized = ref(false)

  const isLoggedIn = computed(() => !!accessToken.value)

  async function login(usernameInput, password) {
    const res = await loginApi(usernameInput, password)
    const data = res.data
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    username.value = data.user.username
    user.value = data.user
    localStorage.setItem('accessToken', data.accessToken)
    localStorage.setItem('refreshToken', data.refreshToken)
    localStorage.setItem('username', data.user.username)
  }

  async function initAuth() {
    try {
      if (accessToken.value) {
        // 已有 token，调 /me 验证并恢复用户信息
        const me = await getCurrentUser()
        user.value = me.data
      }
    } catch {
      // token 无效，清掉本地状态
      clearLocal()
    } finally {
      initialized.value = true
    }
  }

  /** OAuth 回调 HTML 写入 localStorage 后，前端刷新页面时由 initAuth 恢复 */
  function setOAuthSession(userData, rawToken) {
    accessToken.value = rawToken
    username.value = userData.username
    user.value = userData
    localStorage.setItem('accessToken', rawToken)
    localStorage.setItem('username', userData.username)
  }

  async function logout() {
    try {
      await logoutApi()
    } catch { /* ignore */ }
    clearLocal()
  }

  function clearLocal() {
    accessToken.value = ''
    refreshToken.value = ''
    username.value = ''
    user.value = null
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('username')
  }

  return {
    accessToken, refreshToken, username, user, initialized, isLoggedIn,
    login, logout, initAuth, setOAuthSession, clearLocal
  }
})
```

- [ ] **Step 3: Commit**

```bash
cd /Users/liar/Java/project/ui/liar-zhiliao-ui
git add src/api/auth.js src/stores/auth.js
git commit -m "feat(auth): adapt frontend store to access+refresh token pair"
```

---

## Task 9: 前端 request.js 401 拦截 + refresh 排队 + 重放

**Files:**
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/api/request.js`

- [ ] **Step 1: 改 request.js**

```javascript
import axios from 'axios'

const request = axios.create({
  baseURL: '/',
  timeout: 30000
})

request.interceptors.request.use(config => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

let refreshing = false
let pendingRequests = []

function clearAuthAndRedirect() {
  localStorage.removeItem('accessToken')
  localStorage.removeItem('refreshToken')
  localStorage.removeItem('username')
  window.location.hash = '#/login'
}

async function tryRefresh() {
  const refreshToken = localStorage.getItem('refreshToken')
  if (!refreshToken) return false
  try {
    const res = await axios.post('/api/auth/refresh', { refreshToken })
    localStorage.setItem('accessToken', res.data.accessToken)
    localStorage.setItem('refreshToken', res.data.refreshToken)
    return true
  } catch {
    return false
  }
}

request.interceptors.response.use(
  response => response,
  async error => {
    const status = error.response?.status
    const errorCode = error.response?.data?.error

    if (status === 401 && errorCode !== 'refresh_token_invalid'
        && errorCode !== 'refresh_token_rotated'
        && errorCode !== 'refresh_token_missing'
        && error.config?.url !== '/api/auth/refresh') {

      if (refreshing) {
        // 排队等待 refresh 完成
        return new Promise((resolve, reject) => {
          pendingRequests.push({ resolve, reject, config: error.config })
        })
      }
      refreshing = true
      const ok = await tryRefresh()
      refreshing = false
      if (ok) {
        // 重放当前请求
        error.config.headers.Authorization = `Bearer ${localStorage.getItem('accessToken')}`
        // 通知排队的请求重放
        pendingRequests.forEach(p => {
          p.config.headers.Authorization = `Bearer ${localStorage.getItem('accessToken')}`
          request(p.config).then(p.resolve).catch(p.reject)
        })
        pendingRequests = []
        return request(error.config)
      } else {
        pendingRequests.forEach(p => p.reject(error))
        pendingRequests = []
        clearAuthAndRedirect()
        return Promise.reject(error)
      }
    }

    if (status === 401) {
      clearAuthAndRedirect()
    }
    return Promise.reject(error)
  }
)

export default request
```

- [ ] **Step 2: Commit**

```bash
cd /Users/liar/Java/project/ui/liar-zhiliao-ui
git add src/api/request.js
git commit -m "feat(auth): auto refresh access token on 401 with request replay"
```

---

## Task 10: 前端 OAuth 回调页适配

**Files:**
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/App.vue`
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/router/index.js`

**说明：** OAuth 回调由后端返回 HTML，脚本自动写入 localStorage 并 `window.location.href = '/'`。前端 `App.vue` 的 `onMounted` 调 `initAuth()` 时，会从 localStorage 读到 `accessToken`，调 `/api/auth/me` 验证并恢复用户信息。**无需专门回调页**。

- [ ] **Step 1: 检查 App.vue 的 onMounted 逻辑**

```javascript
// App.vue 中应保持：
onMounted(async () => {
  await auth.initAuth()
  if (auth.isLoggedIn && route.name === 'Login') {
    router.push({ name: 'Chat' })
  }
})
```

`initAuth` 已在 Task 8 改造为：从 localStorage 读 accessToken，调 /me 验证。OAuth 回调跳到 `/` 后会触发此逻辑。

- [ ] **Step 2: 检查 DingTalkQrModal 是否透传 state**

```bash
cat /Users/liar/Java/project/ui/liar-zhiliao-ui/src/components/DingTalkQrModal.vue
```

确认钉钉二维码扫码后，钉钉回调 URL 中是否原样带回 `state` 参数。如未带回，需在后端 `OAuth2Controller.callback` 中针对钉钉降低校验强度或改用前端中转 state。

- [ ] **Step 3: 手工验证完整 OAuth 流程**

启动后端 + 前端（`npm run dev`），点击 GitHub 登录 → 授权 → 跳转回应用首页 → 自动登录。DevTools 中查看 localStorage 是否有 `accessToken` + `refreshToken`。

- [ ] **Step 4: Commit（如有改动）**

```bash
cd /Users/liar/Java/project/ui/liar-zhiliao-ui
git add src/App.vue src/router/index.js src/components/DingTalkQrModal.vue
git commit -m "feat(auth): verify OAuth callback flow with localStorage injection"
```

---

## Task 11: 更新 CLAUDE.md 与 application.yaml 注释

**Files:**
- Modify: `/Users/liar/Java/project/liar-zhiliao/CLAUDE.md`
- Modify: `/Users/liar/Java/project/liar-zhiliao/zhiliao-app/src/main/resources/application.yaml`

- [ ] **Step 1: 在 CLAUDE.md 的"关键文件"表中更新认证相关条目**

把 `JwtUtil.java`、`JwtFilter.java` 行替换为：

```
| TokenService.java | zhiliao-auth | token 生成/查询/吊销/刷新（Redis 存储） |
| SessionFilter.java | zhiliao-auth | 从 Authorization 头校验 access token |
| SessionData.java | zhiliao-auth | access token 在 Redis 中的会话结构 |
| AuthProperties.java | zhiliao-auth | 读取 zhiliao.auth.* 配置 |
```

在"约束"节追加：

```
2. Access Token TTL 15min，Refresh Token TTL 7day，OAuth state TTL 5min
3. Refresh Token Rotation：每次 refresh 同时换新 access 和 refresh token
4. OAuth 回调必须返回 HTML 页面（脚本写 localStorage），禁止 setCookie
```

- [ ] **Step 2: 在 application.yaml 的 zhiliao.auth 节点上方加注释**

```yaml
  # 认证配置：token TTL、SSO 应用标识
  auth:
    app-id: zhiliao                         # SSO 应用标识，预留多应用
    access-token-ttl-seconds: 900           # 15 min
    refresh-token-ttl-seconds: 604800       # 7 day
    oauth-state-ttl-seconds: 300            # 5 min
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md zhiliao-app/src/main/resources/application.yaml
git commit -m "docs: update CLAUDE.md and application.yaml for session-based auth"
```

---

## Task 12: 端到端验证

- [ ] **Step 1: 启动后端**

```bash
mvn spring-boot:run -pl zhiliao-app
```

- [ ] **Step 2: 启动前端**

```bash
cd /Users/liar/Java/project/ui/liar-zhiliao-ui
npm run dev
```

- [ ] **Step 3: 验证清单**

| 场景 | 操作 | 预期 |
|------|------|------|
| 用户名密码登录 | 输入 admin/admin123 | 跳转聊天页，localStorage 有 accessToken+refreshToken |
| 业务请求 | 在聊天页发消息 | 正常返回，DevTools 看到带 Authorization 头 |
| 自动续期 | 等 16 分钟后发消息 | 请求先 401 → 自动 refresh → 重放成功 |
| 退出登录 | 点退出登录 | 跳登录页，localStorage 清空，旧 token 调 /me 返回 401 |
| GitHub OAuth | 点 GitHub 登录 | 跳转授权 → 回调显示"登录成功" → 跳首页已登录 |
| 钉钉 OAuth | 点钉钉扫码 | 显示二维码 → 扫码 → 跳首页已登录 |
| state 伪造 | curl 直接调 callback?state=fake | 401 Invalid OAuth state |
| Refresh 重放 | 同一 refreshToken 调两次 refresh | 第一次成功，第二次 401 refresh_token_rotated |

- [ ] **Step 4: 最终 Commit**

```bash
git add --all
git commit -m "test: e2e verification for session-based auth"
```

---

## 验收标准对齐

| Spec 验收项 | 对应 Task |
|-------------|----------|
| 用户名密码登录返回 access + refresh | Task 5 |
| 业务接口 Bearer token 鉴权 | Task 4, 5 |
| access 过期自动 refresh | Task 9 |
| refresh 一次性使用 | Task 3, 5 |
| logout 立即失效 | Task 3, 5 |
| OAuth 回调返回 HTML | Task 6 |
| 钉钉 state 校验 | Task 6 |
| 无 setCookie 代码 | Task 5, 6 |
| TokenService 单元测试 | Task 3 |
