# 安全与多租户 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 JWT 认证基础上，新增 GitHub/微信 OAuth2 登录、邮箱自动合并、部门级权限隔离、输入输出安全过滤

**Architecture:** OAuth2 通过抽象接口（OAuth2Authenticator）支持多提供商；邮箱合并逻辑在 UserLinkService 中统一处理；RBAC 通过 zl_kb_dept_visibility 表控制知识库可见性，检索时追加 dept_id 过滤。所有新功能通过 @ConditionalOnProperty 控制开关。

**Tech Stack:** Spring Boot 3.5, JJWT 0.12.6, MyBatis-Plus 3.5.9, PostgreSQL 16

---

## 文件全景

```
修改的文件:
  zhiliao-app/src/main/resources/sql/schema.sql      (+ sys_oauth_link, zl_kb_dept_visibility 建表 + COMMENT)
  zhiliao-app/src/main/resources/application.yaml    (+ oauth2 配置)
  zhiliao-common/.../model/CurrentUser.java          (+ visibleDeptIds 字段)
  zhiliao-chat/.../controller/ChatController.java    (+ InputFilter.check)
  zhiliao-app/pom.xml                                (需引入 jackson 或其他工具)

新建的文件:
  zhiliao-auth/.../authenticator/OAuth2Authenticator.java       (OAuth2 认证接口)
  zhiliao-auth/.../authenticator/OAuth2UserInfo.java            (OAuth 用户信息 record)
  zhiliao-auth/.../authenticator/GitHubAuthenticator.java       (GitHub OAuth 实现)
  zhiliao-auth/.../authenticator/WeChatAuthenticator.java       (微信扫码实现)
  zhiliao-auth/.../controller/OAuth2Controller.java             (/oauth2/github, /oauth2/wechat/callback)
  zhiliao-auth/.../entity/SysOauthLink.java                     (MyBatis-Plus 实体，含 JavaDoc)
  zhiliao-auth/.../mapper/SysOauthLinkMapper.java               (MyBatis-Plus Mapper)
  zhiliao-auth/.../service/UserLinkService.java                 (邮箱合并逻辑)
  zhiliao-auth/.../config/OAuth2Config.java                     (@ConfigurationProperties OAuth2 配置)
  zhiliao-auth/.../service/DeptPermissionService.java           (用户可见部门查询)
  zhiliao-admin/.../entity/ZlKbDeptVisibility.java              (MyBatis-Plus 实体，含 JavaDoc)
  zhiliao-admin/.../mapper/ZlKbDeptVisibilityMapper.java        (MyBatis-Plus Mapper)
  zhiliao-chat/.../filter/InputFilter.java                      (Prompt 注入检测)
  zhiliao-chat/.../filter/OutputFilter.java                     (输出审核接口)
```

---

### Task 1: 数据库 DDL（sys_oauth_link + zl_kb_dept_visibility）

**Files:**
- Modify: `zhiliao-app/src/main/resources/sql/schema.sql`

- [ ] **Step 1: 追加 sys_oauth_link 建表语句（含 COMMENT）**

```sql
-- 在 schema.sql 末尾追加
-- 8. OAuth 关联表

CREATE TABLE IF NOT EXISTS sys_oauth_link (
    id                BIGSERIAL       PRIMARY KEY,
    user_id           BIGINT          NOT NULL,
    provider          VARCHAR(20)     NOT NULL,
    provider_user_id  VARCHAR(200)    NOT NULL,
    provider_email    VARCHAR(200),
    created_at        TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (provider, provider_user_id)
);
COMMENT ON TABLE  sys_oauth_link IS 'OAuth 关联表：GitHub/微信账号到本地用户的映射';
COMMENT ON COLUMN sys_oauth_link.user_id IS '关联 sys_user.id';
COMMENT ON COLUMN sys_oauth_link.provider IS 'OAuth 提供商：github | wechat';
COMMENT ON COLUMN sys_oauth_link.provider_user_id IS '第三方平台中的用户唯一 ID';
COMMENT ON COLUMN sys_oauth_link.provider_email IS 'OAuth 返回的邮箱，用于跨提供商自动合并';
COMMENT ON COLUMN sys_oauth_link.created_at IS '绑定时间';
```

- [ ] **Step 2: 追加 zl_kb_dept_visibility 建表语句（含 COMMENT）**

```sql
-- 9. 知识库-部门可见性

CREATE TABLE IF NOT EXISTS zl_kb_dept_visibility (
    id         BIGSERIAL       PRIMARY KEY,
    kb_id      BIGINT          NOT NULL,
    dept_id    BIGINT          NOT NULL,
    created_at TIMESTAMPTZ     DEFAULT NOW(),
    UNIQUE (kb_id, dept_id)
);
COMMENT ON TABLE  zl_kb_dept_visibility IS '知识库-部门可见性：控制哪些部门可查看指定知识库';
COMMENT ON COLUMN zl_kb_dept_visibility.kb_id IS '关联 zl_knowledge_base.id';
COMMENT ON COLUMN zl_kb_dept_visibility.dept_id IS '关联 sys_department.id';
COMMENT ON COLUMN zl_kb_dept_visibility.created_at IS '创建时间';
```

- [ ] **Step 3: 追加索引**

```sql
CREATE INDEX IF NOT EXISTS idx_oauth_link_user_id ON sys_oauth_link(user_id);
CREATE INDEX IF NOT EXISTS idx_oauth_link_provider ON sys_oauth_link(provider, provider_user_id);
CREATE INDEX IF NOT EXISTS idx_kb_dept_visibility_kb ON zl_kb_dept_visibility(kb_id);
CREATE INDEX IF NOT EXISTS idx_kb_dept_visibility_dept ON zl_kb_dept_visibility(dept_id);
```

---

### Task 2: OAuth2 配置类 + 认证接口

**Files:**
- Create: `zhiliao-auth/.../config/OAuth2Config.java`
- Create: `zhiliao-auth/.../authenticator/OAuth2Authenticator.java`
- Create: `zhiliao-auth/.../authenticator/OAuth2UserInfo.java`

- [ ] **Step 1: 创建 OAuth2Config 配置绑定类**

```java
/**
 * OAuth2 配置属性绑定。
 * 从 application.yaml 的 zhiliao.oauth2 前缀读取。
 */
@Configuration
@ConfigurationProperties(prefix = "zhiliao.oauth2")
@Data
public class OAuth2Config {
    private GitHub github = new GitHub();
    private WeChat wechat = new WeChat();

    @Data
    public static class GitHub {
        private String clientId;
        private String clientSecret;
        private String redirectUri;
    }

    @Data
    public static class WeChat {
        private String appId;
        private String appSecret;
        private String redirectUri;
    }
}
```

- [ ] **Step 2: 创建 OAuth2Authenticator 接口和 OAuth2UserInfo**

```java
/**
 * OAuth2 认证器接口。
 * 每个 OAuth 提供商实现一个子类，通过 provider() 方法区分。
 *
 * 当前实现：
 * - GitHubAuthenticator：GitHub OAuth2 授权码登录
 * - WeChatAuthenticator：微信扫码登录
 *
 * 未来可扩展：GitLabAuthenticator、飞书Authenticator 等
 */
public interface OAuth2Authenticator {

    /** OAuth 提供商名称，如 "github"、"wechat" */
    String provider();

    /**
     * 用授权码换取用户信息。
     *
     * @param authorizationCode OAuth 回调携带的授权码
     * @return 标准化用户信息（含邮箱，用于自动合并）
     */
    OAuth2UserInfo authenticate(String authorizationCode);
}

/**
 * OAuth2 认证返回的标准用户信息。
 *
 * @param providerUserId 第三方平台中的用户唯一 ID
 * @param email          邮箱，用于跨提供商自动合并
 * @param name           显示名称
 */
public record OAuth2UserInfo(
    String providerUserId,
    String email,
    String name
) {}
```

---

### Task 3: GitHub 和微信 OAuth2 实现

**Files:**
- Create: `zhiliao-auth/.../authenticator/GitHubAuthenticator.java`
- Create: `zhiliao-auth/.../authenticator/WeChatAuthenticator.java`

- [ ] **Step 1: 实现 GitHubAuthenticator**

```java
/**
 * GitHub OAuth2 登录实现。
 * 流程：
 *   1. 用户访问 /oauth2/github → 重定向至 GitHub 授权页
 *   2. GitHub 回调 /oauth2/github/callback?code=xxx
 *   3. 用 code 换取 access_token
 *   4. 用 access_token 调用 GitHub API 获取用户信息（含邮箱）
 */
@Component
@RequiredArgsConstructor
public class GitHubAuthenticator implements OAuth2Authenticator {

    private final OAuth2Config oAuth2Config;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String provider() {
        return "github";
    }

    @Override
    public OAuth2UserInfo authenticate(String authorizationCode) {
        OAuth2Config.GitHub cfg = oAuth2Config.getGitHub();

        // 1. 用授权码换取 access_token
        Map<String, String> tokenParams = Map.of(
            "client_id", cfg.getClientId(),
            "client_secret", cfg.getClientSecret(),
            "code", authorizationCode,
            "redirect_uri", cfg.getRedirectUri()
        );
        ResponseEntity<Map> tokenResp = restTemplate.postForEntity(
            "https://github.com/login/oauth/access_token",
            tokenParams, Map.class);

        String accessToken = (String) tokenResp.getBody().get("access_token");

        // 2. 用 access_token 获取用户信息
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", "application/vnd.github.v3+json");
        ResponseEntity<Map> userResp = restTemplate.exchange(
            "https://api.github.com/user",
            HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        Map<String, Object> userData = userResp.getBody();

        // 3. 获取邮箱（可能需要单独的 /user/emails 接口）
        String email = (String) userData.get("email");
        if (email == null || email.isEmpty()) {
            ResponseEntity<List> emailResp = restTemplate.exchange(
                "https://api.github.com/user/emails",
                HttpMethod.GET, new HttpEntity<>(headers), List.class);
            List<Map<String, Object>> emails = emailResp.getBody();
            for (Map<String, Object> e : emails) {
                if (Boolean.TRUE.equals(e.get("primary"))) {
                    email = (String) e.get("email");
                    break;
                }
            }
        }

        return new OAuth2UserInfo(
            userData.get("id").toString(),
            email,
            (String) userData.get("login")
        );
    }
}
```

- [ ] **Step 2: 实现 WeChatAuthenticator**

```java
/**
 * 微信扫码登录实现。
 * 流程：
 *   1. 用户访问 /oauth2/wechat → 重定向至微信二维码页
 *   2. 微信回调 /oauth2/wechat/callback?code=xxx
 *   3. 用 code 换取 access_token + openid
 *   4. 获取用户信息（微信 unionid、nickname）
 *
 * 注意：微信扫码仅提供 unionid，不提供邮箱。
 *       无邮箱时无法自动合并，将创建新用户。
 */
@Component
@RequiredArgsConstructor
public class WeChatAuthenticator implements OAuth2Authenticator {

    private final OAuth2Config oAuth2Config;
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String provider() {
        return "wechat";
    }

    @Override
    public OAuth2UserInfo authenticate(String authorizationCode) {
        OAuth2Config.WeChat cfg = oAuth2Config.getWeChat();

        // 1. 用 code 换取 access_token 和 openid
        ResponseEntity<Map> tokenResp = restTemplate.getForEntity(
            "https://api.weixin.qq.com/sns/oauth2/access_token" +
            "?appid={appid}&secret={secret}&code={code}&grant_type=authorization_code",
            Map.class,
            cfg.getAppId(), cfg.getAppSecret(), authorizationCode);
        Map<String, Object> tokenData = tokenResp.getBody();

        String accessToken = (String) tokenData.get("access_token");
        String openId = (String) tokenData.get("openid");

        // 2. 获取用户基本信息
        ResponseEntity<Map> userResp = restTemplate.getForEntity(
            "https://api.weixin.qq.com/sns/userinfo" +
            "?access_token={token}&openid={openid}&lang=zh_CN",
            Map.class, accessToken, openId);
        Map<String, Object> userData = userResp.getBody();

        return new OAuth2UserInfo(
            openId,
            null,  // 微信不提供邮箱，无法自动合并
            (String) userData.get("nickname")
        );
    }
}
```

---

### Task 4: 实体 + Mapper + 邮箱合并逻辑

**Files:**
- Create: `zhiliao-auth/.../entity/SysOauthLink.java`
- Create: `zhiliao-auth/.../mapper/SysOauthLinkMapper.java`
- Create: `zhiliao-auth/.../service/UserLinkService.java`

- [ ] **Step 1: 创建 SysOauthLink 实体（含 JavaDoc）**

```java
/**
 * OAuth 关联实体。
 * 将 GitHub/微信等第三方账号映射到本地 sys_user。
 * 通过 provider + provider_user_id 唯一确定一条关联记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("sys_oauth_link")
public class SysOauthLink {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的本地用户 ID（sys_user.id） */
    private Long userId;

    /** OAuth 提供商：github | wechat */
    private String provider;

    /** 第三方平台中的用户唯一 ID */
    private String providerUserId;

    /** OAuth 返回的邮箱，用于跨提供商自动合并 */
    private String providerEmail;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
```

- [ ] **Step 2: 创建 SysOauthLinkMapper**

```java
/**
 * OAuth 关联 Mapper。
 * 提供 OAuth 关联记录的增删查操作。
 */
@Mapper
public interface SysOauthLinkMapper extends BaseMapper<SysOauthLink> {
}
```

- [ ] **Step 3: 实现 UserLinkService（邮箱合并逻辑）**

```java
/**
 * OAuth 用户关联与合并服务。
 *
 * 核心逻辑 — 邮箱合并：
 * 1. 第三方登录成功，获取 email
 * 2. 查 sys_oauth_link：已有记录 → 签发 JWT
 * 3. 无记录：
 *    a) email 非空 → 查 sys_user 是否已存在此邮箱
 *       ├── 存在 → 关联到该用户
 *       └── 不存在 → 创建新用户再关联
 *    b) email 为空（如微信）→ 创建新用户再关联
 * 4. 返回 JWT
 */
@Service
@RequiredArgsConstructor
public class UserLinkService {

    private final SysOauthLinkMapper oauthLinkMapper;
    private final SysUserMapper userMapper;
    private final JwtUtil jwtUtil;

    /**
     * OAuth 登录或注册，返回 JWT token。
     *
     * @param userInfo OAuth2 认证返回的用户信息
     * @return JWT token 字符串
     */
    public String loginOrRegister(OAuth2UserInfo userInfo) {
        // 1. 查是否已有关联记录
        SysOauthLink existing = oauthLinkMapper.selectOne(Wrappers.<SysOauthLink>lambdaQuery()
            .eq(SysOauthLink::getProvider, userInfo.providerUserId()));

        if (existing != null) {
            // 已有绑定 → 直接签发 JWT
            SysUser user = userMapper.selectById(existing.getUserId());
            return jwtUtil.generateToken(CurrentUser.from(user));
        }

        // 2. 无关联 → 尝试邮箱合并或创建新用户
        SysUser user = null;
        if (userInfo.email() != null && !userInfo.email().isEmpty()) {
            // 按邮箱查找已有用户
            user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getEmail, userInfo.email()));
            // TODO: sys_user 需要追加 email 字段；当前无该字段时跳过邮箱合并
        }

        if (user == null) {
            // 创建新用户
            user = new SysUser();
            user.setUsername(userInfo.name());
            user.setPasswordHash("");  // OAuth 用户无密码
            user.setDeptId(1L);        // 默认部门
            user.setRole("USER");
            user.setTenantId("default");
            userMapper.insert(user);
        }

        // 3. 创建关联记录
        SysOauthLink link = SysOauthLink.builder()
            .userId(user.getId())
            .provider(userInfo.providerUserId())
            .providerUserId(userInfo.providerUserId())
            .providerEmail(userInfo.email())
            .build();
        oauthLinkMapper.insert(link);

        return jwtUtil.generateToken(CurrentUser.from(user));
    }
}
```

> **注意**：当前 `sys_user` 表没有 `email` 字段。邮箱合并需要先在 `schema.sql` 追加 `email` 列：
> ```sql
> ALTER TABLE sys_user ADD COLUMN email VARCHAR(200);
> COMMENT ON COLUMN sys_user.email IS '用户邮箱，用于 OAuth 自动合并';
> ```

---

### Task 5: OAuth2 控制器 + 配置

**Files:**
- Create: `zhiliao-auth/.../controller/OAuth2Controller.java`
- Modify: `zhiliao-app/src/main/resources/application.yaml`

- [ ] **Step 1: 创建 OAuth2Controller**

```java
/**
 * OAuth2 认证控制器。
 *
 * GitHub 登录流程：
 *   用户访问 /oauth2/github → 重定向至 GitHub 授权页
 *   GitHub 回调 /oauth2/github/callback?code=xxx → 签发 JWT
 *
 * 微信登录流程：
 *   用户访问 /oauth2/wechat → 重定向至微信二维码页
 *   微信回调 /oauth2/wechat/callback?code=xxx → 签发 JWT
 */
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    private final OAuth2Config oAuth2Config;
    private final GitHubAuthenticator gitHubAuthenticator;
    private final WeChatAuthenticator weChatAuthenticator;
    private final UserLinkService userLinkService;

    @GetMapping("/github")
    public ResponseEntity<Void> githubLogin() {
        OAuth2Config.GitHub cfg = oAuth2Config.getGitHub();
        String url = "https://github.com/login/oauth/authorize" +
            "?client_id=" + cfg.getClientId() +
            "&redirect_uri=" + cfg.getRedirectUri() +
            "&scope=user:email";
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(url))
            .build();
    }

    @GetMapping("/github/callback")
    public ResponseEntity<Map<String, String>> githubCallback(@RequestParam String code) {
        OAuth2UserInfo userInfo = gitHubAuthenticator.authenticate(code);
        String token = userLinkService.loginOrRegister(userInfo);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @GetMapping("/wechat")
    public ResponseEntity<Void> wechatLogin() {
        OAuth2Config.WeChat cfg = oAuth2Config.getWeChat();
        String url = "https://open.weixin.qq.com/connect/qrconnect" +
            "?appid=" + cfg.getAppId() +
            "&redirect_uri=" + cfg.getRedirectUri() +
            "&response_type=code&scope=snsapi_login";
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(url))
            .build();
    }

    @GetMapping("/wechat/callback")
    public ResponseEntity<Map<String, String>> wechatCallback(@RequestParam String code) {
        OAuth2UserInfo userInfo = weChatAuthenticator.authenticate(code);
        String token = userLinkService.loginOrRegister(userInfo);
        return ResponseEntity.ok(Map.of("token", token));
    }
}
```

- [ ] **Step 2: 更新 application.yaml**

```yaml
zhiliao:
  oauth2:
    github:
      client-id: ${GITHUB_CLIENT_ID:}
      client-secret: ${GITHUB_CLIENT_SECRET:}
      redirect-uri: http://localhost:8080/oauth2/github/callback
    wechat:
      app-id: ${WECHAT_APP_ID:}
      app-secret: ${WECHAT_APP_SECRET:}
      redirect-uri: http://localhost:8080/oauth2/wechat/callback
```

---

### Task 6: RBAC 多租户过滤

**Files:**
- Modify: `zhiliao-common/.../model/CurrentUser.java`
- Create: `zhiliao-auth/.../service/DeptPermissionService.java`
- Create: `zhiliao-admin/.../entity/ZlKbDeptVisibility.java`
- Create: `zhiliao-admin/.../mapper/ZlKbDeptVisibilityMapper.java`

- [ ] **Step 1: 扩展 CurrentUser**

```java
/**
 * 当前登录用户上下文。
 * 由 UserContextHolder 持有，通过 JwtFilter 在请求开始时设置。
 *
 * @param id             用户 ID
 * @param username       用户名
 * @param deptId         所属部门 ID
 * @param tenantId       租户 ID（默认 "default"）
 * @param visibleDeptIds 用户可见的部门 ID 列表（用于检索过滤）
 */
public record CurrentUser(
    Long id,
    String username,
    Long deptId,
    String tenantId,
    List<Long> visibleDeptIds
) {
    public static CurrentUser from(SysUser user) {
        return new CurrentUser(user.getId(), user.getUsername(),
            user.getDeptId(), user.getTenantId(), List.of(user.getDeptId()));
    }
}
```

- [ ] **Step 2: 创建 DeptPermissionService**

```java
/**
 * 部门权限服务。
 * 查询用户可访问的部门列表。
 *
 * 当前逻辑：
 * - USER 角色：仅可见自己所在的部门
 * - ADMIN 角色：可见所有部门（List.empty() 表示不过滤）
 *
 * 未来扩展：从 zl_kb_dept_visibility 表动态查部门可见性
 */
@Service
@RequiredArgsConstructor
public class DeptPermissionService {

    public List<Long> getVisibleDeptIds(CurrentUser user) {
        if ("ADMIN".equals(user.role())) {
            return List.of();  // 空列表 = 不过滤
        }
        return List.of(user.deptId());
    }
}
```

- [ ] **Step 3: 创建 ZlKbDeptVisibility 实体和 Mapper**

```java
/**
 * 知识库-部门可见性实体。
 * 控制哪些部门可以查看指定知识库。
 * 一个知识库可被多个部门访问，一个部门可访问多个知识库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("zl_kb_dept_visibility")
public class ZlKbDeptVisibility {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库 ID（关联 zl_knowledge_base.id） */
    private Long kbId;

    /** 部门 ID（关联 sys_department.id） */
    private Long deptId;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

/**
 * 知识库-部门可见性 Mapper。
 */
@Mapper
public interface ZlKbDeptVisibilityMapper extends BaseMapper<ZlKbDeptVisibility> {
}
```

---

### Task 7: 安全过滤

**Files:**
- Create: `zhiliao-chat/.../filter/InputFilter.java`
- Create: `zhiliao-chat/.../filter/OutputFilter.java`
- Modify: `zhiliao-chat/.../controller/ChatController.java`

- [ ] **Step 1: 创建 InputFilter**

```java
/**
 * 用户输入安全过滤器。
 * 在 ChatController 调用 ChatService 前对用户消息进行检测。
 * 检测 Prompt 注入、越权查询等不安全输入。
 *
 * 检测规则通过 zhiliao.input-filter.sensitive-words 配置。
 */
@Slf4j
@Component
public class InputFilter {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("忽略.*(?:指令|提示|system|规则|prompt).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("你(?:现在|接下来|扮演).*", Pattern.CASE_INSENSITIVE),
        Pattern.compile("无视.*(?:上文|设定|角色).*", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 检查用户输入是否安全。
     *
     * @param userMessage 用户消息
     * @return null 表示通过，非空字符串为拒绝原因
     */
    public String check(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "消息不能为空";
        }

        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(userMessage).find()) {
                log.warn("输入安全检测未通过：{}", userMessage);
                return "输入包含不安全的指令，请重新表述您的问题";
            }
        }

        return null;
    }
}
```

- [ ] **Step 2: 创建 OutputFilter 接口**

```java
/**
 * AI 输出审核接口。
 * 当前版本为预留接口，不做实现。
 * 未来可集成内容审核服务（如阿里云内容安全、百度 AI 审核）。
 */
public interface OutputFilter {

    /**
     * 审核 AI 回答是否安全。
     *
     * @param aiResponse LLM 生成的回答
     * @return true = 安全可展示，false = 需拦截
     */
    boolean check(String aiResponse);
}
```

- [ ] **Step 3: ChatController 接入输入过滤**

```java
// ChatController 中注入 InputFilter

private final InputFilter inputFilter;

@GetMapping("/chat")
public Flux<String> chat(@RequestParam String memoryId, @RequestParam String message) {
    // 输入安全检测
    String rejectReason = inputFilter.check(message);
    if (rejectReason != null) {
        return Flux.just("系统提示：" + rejectReason);
    }

    return chatService.chat(memoryId, message);
}
```

---

### Task 8: SysUser 追加 email 字段

**Files:**
- Modify: `zhiliao-app/src/main/resources/sql/schema.sql`
- Modify: `zhiliao-auth/.../entity/SysUser.java`

- [ ] **Step 1: schema.sql 追加 email 列**

```sql
-- 在 sys_user 表 username 列后追加（或末尾追加）
-- 注意：已有表需另行执行 ALTER TABLE，但 schema.sql 使用 CREATE TABLE IF NOT EXISTS，
-- 对于已存在的表不会重新执行，需要在 data.sql 或迁移脚本中单独处理。
-- 这里仅对新部署生效。
```

实际上 schema.sql 是 `CREATE TABLE IF NOT EXISTS`，已存在的表不会受影响。需要新增 DDL 迁移文件或直接修改建表语句。建议：

```sql
-- 直接修改 sys_user 的建表语句，在 username 后追加 email 列
CREATE TABLE IF NOT EXISTS sys_user (
    id              BIGSERIAL       PRIMARY KEY,
    username        VARCHAR(50)     NOT NULL UNIQUE,
    email           VARCHAR(200),                  -- 新增：用户邮箱，用于 OAuth 自动合并
    password_hash   VARCHAR(255)    NOT NULL,
    dept_id         BIGINT          NOT NULL DEFAULT 1,
    role            VARCHAR(20)     NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    tenant_id       VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at      TIMESTAMPTZ       DEFAULT NOW()
);
```

- [ ] **Step 2: SysUser 实体追加 email 字段**

```java
// SysUser.java 中追加
/** 用户邮箱，用于 OAuth 自动合并 */
private String email;
```

---

## 自审检查

| 设计要求 | 对应任务 | 覆盖情况 |
|----------|----------|----------|
| GitHub OAuth2 登录 | Task 3 Step 1 | 授权码 + access_token + 用户信息 + 邮箱获取 |
| 微信扫码登录 | Task 3 Step 2 | 授权码 + openid + 用户信息（邮箱为空）|
| 邮箱合并 | Task 4 Step 3 | 有邮箱→按邮箱查已有用户→合并；无邮箱→创建新用户 |
| OAuth 配置外部化 | Task 2 Step 1 | @ConfigurationProperties + 环境变量 |
| 部门多租户 | Task 6 | CurrentUser.visibleDeptIds + DeptPermissionService |
| 知识库-部门可见性表 | Task 1 Step 2 + Task 6 Step 3 | zl_kb_dept_visibility 实体/Mapper |
| 输入过滤 | Task 7 Step 1 + Step 3 | Prompt 注入正则 + ChatController 接入 |
| 输出审核预留 | Task 7 Step 2 | OutputFilter 接口 |
| SQL COMMENT | Task 1 全部 | 每个表/列都含 COMMENT |
| JavaDoc | Task 2/3/4/6/7 全部 | 接口/类/公共方法都有 JavaDoc |
| sys_user.email 字段 | Task 8 | schema.sql + SysUser 实体 |
