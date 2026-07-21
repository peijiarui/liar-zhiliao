# Phase 3 企业级可观测性与运维 实施计划

> **日期：** 2026-07-20 | **版本：** v1.0
> **基于：** `2026-07-11-observability-admin-design.md` (v1.1)
> **技术变更：** Resilience4j → **Sentinel**（限流熔断）；Spring Security OAuth2 Client 迁移**暂缓，预留改造口子**

---

## 执行顺序

**默认顺序按编号依次执行**，每完成一个模块验证无回归再进入下一个。

---

## 全局约束

1. 所有新建表、字段必须带 `COMMENT`
2. 新建 Java 类必须带中文 JavaDoc
3. 前端项目路径：`../ui/liar-zhiliao-ui`
4. 后端 API 路径规范：`/api/admin/*`（管理后台）、`/api/*`（业务接口）
5. 管理后台接口需 `role = 'ADMIN'` 鉴权

---

## Task 1: Schema 变更（RBAC 预留表 + COMMENT）

**Files:**
- Modify: `zhiliao-app/src/main/resources/sql/schema.sql`

**变更内容：**

1. **为所有已有表补充 COMMENT**（如果尚未完全覆盖）
2. **新增 RBAC 预留表**（3 张表，不启用，仅为后续改造预留结构）

```sql
-- =============================================================================
-- RBAC 权限模型（预留，Phase 3 不启用）
-- 当前管理后台使用 sys_user.role 字段做简单角色校验，
-- 后续迁移到 RBAC 时启用以下三张表。
-- =============================================================================

-- 角色表
CREATE TABLE IF NOT EXISTS sys_role (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(50)     NOT NULL UNIQUE,
    description VARCHAR(200),
    created_at  TIMESTAMPTZ     DEFAULT NOW()
);
COMMENT ON TABLE  sys_role IS '角色表（预留 RBAC）';
COMMENT ON COLUMN sys_role.name IS '角色标识：ADMIN / USER / DEPT_ADMIN';
COMMENT ON COLUMN sys_role.description IS '角色说明';

-- 权限表
CREATE TABLE IF NOT EXISTS sys_permission (
    id          BIGSERIAL       PRIMARY KEY,
    code        VARCHAR(100)    NOT NULL UNIQUE,
    name        VARCHAR(100)    NOT NULL,
    created_at  TIMESTAMPTZ     DEFAULT NOW()
);
COMMENT ON TABLE  sys_permission IS '权限表（预留 RBAC）';
COMMENT ON COLUMN sys_permission.code IS '权限编码：kb:create, kb:delete, user:manage, admin:access';
COMMENT ON COLUMN sys_permission.name IS '权限名称';

-- 角色-权限关联表
CREATE TABLE IF NOT EXISTS sys_role_permission (
    id              BIGSERIAL   PRIMARY KEY,
    role_id         BIGINT      NOT NULL REFERENCES sys_role(id),
    permission_id   BIGINT      NOT NULL REFERENCES sys_permission(id),
    UNIQUE (role_id, permission_id)
);
COMMENT ON TABLE  sys_role_permission IS '角色权限关联表（预留 RBAC）';
COMMENT ON COLUMN sys_role_permission.role_id IS '关联 sys_role.id';
COMMENT ON COLUMN sys_role_permission.permission_id IS '关联 sys_permission.id';
```

---

## Task 2: Sentinel 限流熔断

### 2.1 Docker Compose — 新增 Sentinel Dashboard

**Files:**
- Modify: `docker/local-dev.yml`

在 Grafana 服务之后追加：

```yaml
sentinel-dashboard:
  image: bladex/sentinel-dashboard:latest
  container_name: zhiliao-sentinel
  networks:
    - zhiliao-net
  ports:
    - "8858:8858"
  environment:
    JAVA_OPTS: "-Dserver.port=8858"
  deploy:
    resources:
      limits:
        cpus: '0.5'
        memory: 512M
```

### 2.2 Maven 依赖 — 新增 Sentinel + AOP

**Files:**
- Modify: `zhiliao-app/pom.xml`

追加：

```xml
<!-- Sentinel 限流熔断 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
    <version>2025.0.0.0</version>
</dependency>
<!-- Sentinel AOP 注解支持 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

### 2.3 application.yaml — Sentinel 配置

**Files:**
- Modify: `zhiliao-app/src/main/resources/application.yaml`

追加：

```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: ${SENTINEL_DASHBOARD_HOST:localhost}:8858
      eager: true
```

### 2.4 ChatController 限流 + 熔断降级

**Files:**
- Modify: `zhiliao-chat/.../controller/ChatController.java`

**改动：**

```java
@SentinelResource(
    value = "chat",
    fallback = "chatFallback",        // 熔断降级
    blockHandler = "chatBlockHandler" // 限流降级
)
public Flux<String> chat(@RequestParam String memoryId, @RequestParam String message) { ... }

/** 限流降级：用户 5次/分钟 超限 */
public Flux<String> chatBlockHandler(String memoryId, String message, BlockException e) {
    return Flux.just("请求过于频繁，每分钟限 " + RATE_LIMIT_PER_USER + " 次，请稍后重试");
}

/** 熔断降级：LLM API 异常 → 缓存兜底 → 文档片段 */
public Flux<String> chatFallback(String memoryId, String message, Throwable t) {
    // Step 1: 查热点缓存
    String cached = retrievalCacheService.getCachedAnswer(message);
    if (cached != null) return Flux.just(cached);
    // Step 2: 返回文档片段（仅检索，不走 LLM）
    String docs = knowledgeRetrievalTool.retrieveKnowledge(message);
    if (!docs.isEmpty()) return Flux.just("AI 服务暂时繁忙，以下是从知识库找到的相关内容：\n\n" + docs);
    return Flux.just("AI 服务暂时不可用，请稍后再试");
}
```

### 2.5 用户维度限流 — 动态限流规则

**Files:**
- Create: `zhiliao-chat/.../config/SentinelConfig.java`

```java
/**
 * Sentinel 配置。
 * 初始化限流规则 + 熔断规则。
 * 启动后仍可在 Sentinel Dashboard 动态调整。
 */
@Configuration
public class SentinelConfig {

    @PostConstruct
    public void init() {
        // 用户维度限流: 5次/分钟
        List<AuthorityRule> authorityRules = new ArrayList<>();
        // 使用调用方来源（userId）做限流 key
        AuthorityRule rule = new AuthorityRule();
        rule.setResource("chat");
        rule.setLimitApp("");        // 从 UserContextHolder 获取 userId
        rule.setStrategy(RuleConstant.AUTHORITY_BLACK);
        AuthorityRuleManager.loadRules(authorityRules);
    }
}
```

**注意：** 用户维度限流（按 userId 5次/分钟）在 Sentinel 中通过 `AuthorityRule` + 热点参数限流实现，或者通过自定义 `RequestOriginParser` 从请求提取 userId。

---

## Task 3: 两级缓存

### 3.1 CacheConfig — Redis 缓存管理器

**Files:**
- Create: `zhiliao-app/.../config/CacheConfig.java`

```java
/**
 * 缓存配置。
 * Redis 缓存管理器，各缓存区 TTL 和容量统一管理。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        return RedisCacheManager.builder(factory)
            .withCacheConfiguration("qa_answer",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofHours(1))
                    .prefixInCacheNameWith("cache:qa:"))
            .withCacheConfiguration("retrieval_result",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofHours(24))
                    .prefixInCacheNameWith("cache:retrieval:"))
            .build();
    }
}
```

### 3.2 RetrievalCacheService — 缓存服务

**Files:**
- Create: `zhiliao-retrieval/.../service/RetrievalCacheService.java`

```java
/**
 * 检索缓存服务。
 * Level 1: 精确问答缓存（MD5 query → 完整答案），TTL 1h
 * Level 2: 检索结果缓存（MD5 query → Top chunks），TTL 24h
 */
@Service
public class RetrievalCacheService {

    private final CacheManager cacheManager;

    // ===== Level 1: 热点问答缓存 =====

    @Cacheable(value = "qa_answer", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes(StandardCharsets.UTF_8))")
    public String getCachedAnswer(String question) { return null; }

    @CacheEvict(value = "qa_answer", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes(StandardCharsets.UTF_8))")
    public void evictAnswer(String question) {}

    // ===== Level 2: 检索结果缓存 =====

    @Cacheable(value = "retrieval_result", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes(StandardCharsets.UTF_8))")
    public List<RankedChunk> getCachedRetrieval(String question) { return null; }

    @CacheEvict(value = "retrieval_result", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes(StandardCharsets.UTF_8))")
    public void evictRetrieval(String question) {}
}
```

### 3.3 缓存写入 — 在 ChatService 调用后异步写入

**Files:**
- Modify: `zhiliao-chat/.../service/ChatService.java`（接口不变，内部调用 RetrievalCacheService）
- Modify: `zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java`（接入 Level 2 缓存）

### 3.4 application.yaml — Redis maxmemory

**Files:**
- Modify: `zhiliao-app/src/main/resources/application.yaml`

```yaml
spring:
  data:
    redis:
      # ... 已有配置不变，追加：
      timeout: 3000ms
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 4
```

同时在 Redis 容器中设置 maxmemory（在 `local-dev.yml` 的 redis `REDIS_ARGS` 追加 `--maxmemory 512mb --maxmemory-policy allkeys-lru`）。

---

## Task 4: 自定义检索指标

### 4.1 RetrievalMetricsAspect — 指标切面

**Files:**
- Create: `zhiliao-retrieval/.../aspect/RetrievalMetricsAspect.java`

8 个 Micrometer 指标：

| 指标 | 类型 | 埋点位置 |
|------|------|---------|
| `rag.dense_search.duration` | Timer | KnowledgeRetrievalTool Milvus 检索 |
| `rag.sparse_search.duration` | Timer | KnowledgeRetrievalTool BM25 检索 |
| `rag.rewrite.duration` | Timer | KnowledgeRetrievalTool 查询改写 |
| `rag.rrf.result_count` | Gauge | RrfReranker 返回结果数 |
| `rag.empty_result.total` | Counter | KnowledgeRetrievalTool 空结果 |
| `rag.llm.first_token_latency` | Timer | ChatService 流式第一个 token 耗时 |
| `rag.llm.token_usage` | Counter | LLM token 消耗量（从 API 响应取） |
| `rag.cache.hit_rate` | Gauge | RetrievalCacheService 命中率 |

### 4.2 KnowledgeRetrievalTool — 埋点

**Files:**
- Modify: `zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java`

在各检索阶段注入 Micrometer `Timer.Sample`：

```java
Timer.Sample denseSample = Timer.start(meterRegistry);
// ... Milvus 检索 ...
denseSample.stop(denseSearchTimer);

Timer.Sample sparseSample = Timer.start(meterRegistry);
// ... BM25 检索 ...
sparseSample.stop(sparseSearchTimer);
```

空结果时 `emptyResultCounter.increment()`。

### 4.3 ChatService — 首个 Token 耗时

**Files:**
- Modify: `zhiliao-chat/.../service/ChatService.java`（如果存在实现类）

记录 `rag.llm.first_token_latency` — 从用户请求到流式第一个 token 返回的耗时。

### 4.4 Grafana 告警（只展示不推送）

告警规则已存在于 `docker/prometheus/alerts.yml`，不做钉钉/邮件推送绑定，只在 Grafana Alert UI 中可查看告警状态。

---

## Task 5: 管理后台 — 后端 API

### 5.1 AdminFilter — 管理员权限校验

**Files:**
- Create: `zhiliao-auth/.../filter/AdminFilter.java`

```java
/**
 * 管理员权限过滤器。
 * 拦截 /api/admin/* 路径，校验当前用户 role 是否为 ADMIN。
 * 后续迁移到 RBAC 时只需修改内部实现逻辑，接口不变。
 */
@Component
@Order(2)
public class AdminFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();
        if (path.startsWith("/api/admin/")) {
            CurrentUser user = UserContextHolder.get();
            if (user == null || !"ADMIN".equals(user.role())) {
                HttpServletResponse resp = (HttpServletResponse) response;
                resp.setStatus(403);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"error\":\"forbidden\",\"message\":\"需要管理员权限\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
```

**注意：** `SessionFilter` 白名单中需追加 `/api/admin/login` 路径，管理员登录不走 token 校验。

### 5.2 知识库管理 CRUD

**Files:**
- Create: `zhiliao-admin/.../entity/ZlKnowledgeBase.java`（MyBatis-Plus 实体）
- Create: `zhiliao-admin/.../mapper/ZlKnowledgeBaseMapper.java`
- Create: `zhiliao-admin/.../service/KnowledgeBaseService.java`（接口）
- Create: `zhiliao-admin/.../service/impl/KnowledgeBaseServiceImpl.java`
- Create: `zhiliao-admin/.../controller/KnowledgeBaseController.java`

API:

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/knowledge-bases` | 分页列表 |
| POST | `/api/admin/knowledge-bases` | 创建知识库 |
| PUT | `/api/admin/knowledge-bases/{id}` | 编辑知识库 |
| DELETE | `/api/admin/knowledge-bases/{id}` | 删除知识库（软删除） |

### 5.3 文档管理

**Files:**
- Create: `zhiliao-admin/.../controller/AdminDocumentController.java`

API:

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/documents` | 文档列表（按 kb_id/status 筛选） |
| POST | `/api/admin/documents/{id}/reprocess` | 重新处理文档 |

### 5.4 用户管理

**Files:**
- Create: `zhiliao-admin/.../controller/AdminUserController.java`

API:

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/users` | 用户列表（分页） |
| PUT | `/api/admin/users/{id}/role` | 变更用户角色 |

### 5.5 审计日志

**Files:**
- Create: `zhiliao-admin/.../controller/AuditLogController.java`

API:

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/audit-logs` | 审计日志分页查询 |

### 5.6 数据看板

**Files:**
- Create: `zhiliao-admin/.../vo/DashboardVO.java`
- Create: `zhiliao-admin/.../service/DashboardService.java`
- Create: `zhiliao-admin/.../service/impl/DashboardServiceImpl.java`
- Create: `zhiliao-admin/.../controller/DashboardController.java`

```java
public record DashboardVO(
    // 使用概览
    long totalUsers,
    long totalConversations,
    long todayConversations,
    long todayActiveUsers,
    // 内容统计
    long totalDocuments,
    long totalChunks,
    long processingDocuments,
    long failedDocuments,
    long knowledgeBases,
    // 性能概览
    double avgResponseTime,
    double p95ResponseTime,
    double cacheHitRate,
    // 质量指标
    double emptyResultRate
) {}
```

API:

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/dashboard` | 数据看板 |

---

## Task 6: 管理后台 — 前端页面

### 6.1 路由与布局

**Files:**
- Create: `src/router/admin.js` — 管理后台路由 `/admin/*`
- Create: `src/layout/AdminLayout.vue` — 管理后台布局（侧边栏导航）
- Modify: `src/router/index.js` — 注册管理后台路由

### 6.2 页面组件

| 页面 | 路径 | 组件 |
|------|------|------|
| 数据看板 | `/admin/dashboard` | `AdminDashboard.vue` |
| 知识库管理 | `/admin/knowledge-bases` | `KnowledgeBaseList.vue` + `KnowledgeBaseForm.vue` |
| 文档管理 | `/admin/documents` | `DocumentList.vue` |
| 用户管理 | `/admin/users` | `UserList.vue` |
| 审计日志 | `/admin/audit-logs` | `AuditLogList.vue` |

### 6.3 API 调用封装

**Files:**
- Create: `src/api/admin.js` — Axios 封装 `/api/admin/*` 接口

### 6.4 管理后台登录

- 复用现有登录流程，管理员使用同一套登录接口
- AdminLayout 路由守卫检查 `user.role === 'ADMIN'`，非管理员跳转 403 提示页

---

## 文件变更汇总

| 模块 | 操作 | 文件 | 数量 |
|------|------|------|------|
| **zhiliao-app** | 修改 | `pom.xml`、`application.yaml` | 2 |
| | 新建 | `config/CacheConfig.java` | 1 |
| **zhiliao-chat** | 修改 | `controller/ChatController.java`、`service/ChatService.java`（如需要） | 2 |
| | 新建 | `config/SentinelConfig.java` | 1 |
| **zhiliao-retrieval** | 修改 | `tools/KnowledgeRetrievalTool.java` | 1 |
| | 新建 | `service/RetrievalCacheService.java`、`aspect/RetrievalMetricsAspect.java` | 2 |
| **zhiliao-auth** | 新建 | `filter/AdminFilter.java` | 1 |
| **zhiliao-admin** | 新建 | 实体/Mapper/Service/Controller/VO 共 15+ 文件 | ~15 |
| **docker** | 修改 | `local-dev.yml`（sentinel-dashboard） | 1 |
| **schema.sql** | 修改 | RBAC 预留表 + COMMENT | 1 |
| **前端** | 新建 | 路由/布局/组件/API 封装 共 12+ 文件 | ~12 |

**总计：约 39 个文件**（15 后端 API + 12 前端页面 + 5 配置 + 3 DB + 4 缓存/限流/指标）

---

## 不影响已有功能

- 文档摄入管线（ingestion）**不修改**
- 对话接口（ChatController/Service）**仅追加限流和缓存，不改现有逻辑**
- 认证（SessionFilter/TokenService/OAuth2）**不修改**
- 检索管线（KnowledgeRetrievalTool）**仅追加指标埋点和缓存，不改检索逻辑**
- OAuth2 手写实现**保留不动**（Spring Security 迁移暂缓）
- 前端现有页面**不修改**
