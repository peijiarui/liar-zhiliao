# 可观测性与运维 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为知了知了系统添加两级缓存、限流熔断、Prometheus/Grafana 监控和管理后台 REST API，提升系统稳定性和可观测性

**Architecture:** 缓存通过 Spring @Cacheable + Redis 实现；限流熔断通过 Resilience4j 注解声明式配置；监控通过 Micrometer + Actuator 暴露指标，Prometheus 拉取，Grafana 展示；管理后台通过 zhiliao-admin 模块提供 REST API，AdminFilter 做权限校验。

**Tech Stack:** Spring Boot 3.5, Resilience4j 2.x, Micrometer, Prometheus, Grafana, MyBatis-Plus 3.5.9, PostgreSQL 16, Redis

---

## 文件全景

```
修改的文件:
  zhiliao-app/pom.xml                                     (+ resilience4j, micrometer-prometheus)
  zhiliao-app/src/main/resources/application.yaml         (+ management 端点, resilience4j 配置)
  docker/local-dev.yml                                    (+ prometheus, grafana 服务)
  zhiliao-chat/.../controller/ChatController.java         (+ @RateLimiter + fallback)
  zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java (+ 嵌入缓存查询)

新建的文件:
  zhiliao-app/.../config/CacheConfig.java                 (RedisCacheManager + @EnableCaching)
  zhiliao-retrieval/.../service/RetrievalCacheService.java (@Cacheable 热点问答/Embedding 缓存)
  zhiliao-retrieval/.../aspect/RetrievalMetricsAspect.java (Micrometer Timer/Counter 自定义指标)
  docker/prometheus/prometheus.yml                        (Prometheus 抓取配置)
  docker/prometheus/alerts.yml                            (告警规则)
  zhiliao-auth/.../filter/AdminFilter.java                (@Order(2) 管理员权限校验)
  zhiliao-admin/.../controller/KnowledgeBaseController.java (知识库 CRUD)
  zhiliao-admin/.../controller/AdminDocumentController.java (文档管理列表)
  zhiliao-admin/.../controller/AdminUserController.java    (用户管理)
  zhiliao-admin/.../controller/AuditLogController.java     (审计日志)
  zhiliao-admin/.../controller/DashboardController.java    (数据看板)
  zhiliao-admin/.../service/DashboardService.java          (统计查询)
  zhiliao-admin/.../entity/ZlKnowledgeBase.java           (MyBatis-Plus 实体，含 JavaDoc)
  zhiliao-admin/.../mapper/ZlKnowledgeBaseMapper.java     (MyBatis-Plus Mapper)
  zhiliao-admin/.../vo/DashboardVO.java                   (看板响应 VO)
```

---

### Task 1: 依赖添加

**Files:**
- Modify: `zhiliao-app/pom.xml`

- [ ] **Step 1: 追加 resilience4j 和 micrometer 依赖**

```xml
<!-- Resilience4j 限流熔断 -->
<dependency>
    <groupId>io.github.resilience4j</groupId>
    <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>

<!-- Micrometer Prometheus 注册表 -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>

<!-- Spring Boot Actuator（通常已有，确认存在） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>

<!-- Spring AOP（RetrievalMetricsAspect 使用，通常已有） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

---

### Task 2: 两级缓存

**Files:**
- Create: `zhiliao-app/.../config/CacheConfig.java`
- Create: `zhiliao-retrieval/.../service/RetrievalCacheService.java`
- Modify: `zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java`

- [ ] **Step 1: 创建 CacheConfig**

```java
/**
 * 缓存配置。
 * 启用 Spring Cache 抽象，基于 Redis 实现。
 * 各缓存区 TTL 在此统一管理。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        return RedisCacheManager.builder(factory)
            .withCacheConfiguration("qa_cache",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofHours(1))
                    .disableCachingNullValues())
            .withCacheConfiguration("embedding_cache",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofHours(24))
                    .disableCachingNullValues())
            .withCacheConfiguration("perm_cache",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofMinutes(5)))
            .build();
    }
}
```

- [ ] **Step 2: 创建 RetrievalCacheService**

```java
/**
 * 检索缓存服务。
 * 对热点问答和 Embedding 向量做 Redis 缓存，
 * 减少重复 LLM 调用和 Embedding 计算。
 *
 * 缓存键统一使用 MD5 摘要，避免中文字符问题。
 */
@Service
public class RetrievalCacheService {

    /**
     * 从缓存获取热点问答回答。
     * key = MD5(问题全文)
     */
    @Cacheable(value = "qa_cache", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes())")
    public String getCachedAnswer(String question, String memoryId) {
        return null;  // 缓存未命中返回 null
    }

    /**
     * 清除指定问题的缓存（文档更新后调用）。
     */
    @CacheEvict(value = "qa_cache", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes())")
    public void evictAnswer(String question) {}

    /**
     * 从缓存获取 Embedding 向量。
     * value = 向量 JSON 字符串，由调用方解析。
     */
    @Cacheable(value = "embedding_cache", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#text.getBytes())")
    public String getCachedEmbedding(String text) {
        return null;
    }
}
```

- [ ] **Step 3: KnowledgeRetrievalTool 接入缓存**

```java
// 在 KnowledgeRetrievalTool 中注入 RetrievalCacheService

private final RetrievalCacheService cacheService;

public String retrieveKnowledge(@P("查询内容") String query) {
    // 先查缓存（相同问题直接返回缓存答案）
    String cached = cacheService.getCachedAnswer(query, "");
    if (cached != null) {
        log.debug("Cache hit for query: {}", query);
        return cached;
    }

    // 缓存未命中，执行正常检索流程...
}
```

> **注意**：KnowledgeRetrievalTool 不持有 memoryId，缓存仅对知识检索结果生效。完整对话缓存涉及 ChatService 的流式输出，当前版本不做流式缓存。

---

### Task 3: 限流熔断

**Files:**
- Modify: `zhiliao-app/src/main/resources/application.yaml`
- Modify: `zhiliao-chat/.../controller/ChatController.java`

- [ ] **Step 1: application.yaml 追加限流熔断配置**

```yaml
# resilience4j 配置
resilience4j:
  circuitbreaker:
    configs:
      default:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 30s
        permitted-number-of-calls-in-half-open-state: 3
        record-exceptions:
          - java.io.IOException
          - org.springframework.web.client.HttpServerErrorException
    instances:
      llmApi:
        base-config: default
  ratelimiter:
    instances:
      chatRateLimiter:
        limit-for-period: 30
        limit-refresh-period: 1m
```

- [ ] **Step 2: ChatController 追加 @RateLimiter**

```java
// ChatController
@GetMapping("/chat")
@RateLimiter(name = "chatRateLimiter", fallbackMethod = "chatFallback")
public Flux<String> chat(@RequestParam String memoryId, @RequestParam String message) {
    // ... 现有逻辑不变
}

/**
 * 限流降级方法。
 * 当用户超过 30 次/分钟限制时，返回友好提示而非 429 错误页。
 */
public Flux<String> chatFallback(String memoryId, String message, Throwable t) {
    log.warn("Rate limit exceeded for memoryId: {}", memoryId);
    return Flux.just("系统提示：请求过于频繁，请稍后重试");
}
```

---

### Task 4: 监控指标 + Docker Compose

**Files:**
- Create: `zhiliao-retrieval/.../aspect/RetrievalMetricsAspect.java`
- Modify: `zhiliao-app/src/main/resources/application.yaml`
- Modify: `docker/local-dev.yml`
- Create: `docker/prometheus/prometheus.yml`
- Create: `docker/prometheus/alerts.yml`

- [ ] **Step 1: 创建 RetrievalMetricsAspect**

```java
/**
 * 检索性能指标采集。
 * 通过 Micrometer Timer 和 Counter 记录检索各环节的延迟和调用量。
 * 指标前缀 "retrieval."，可在 Grafana 中统一展示。
 */
@Aspect
@Component
public class RetrievalMetricsAspect {

    private final Timer denseSearchTimer;
    private final Timer sparseSearchTimer;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public RetrievalMetricsAspect(MeterRegistry registry) {
        this.denseSearchTimer = Timer.builder("retrieval.dense.duration")
            .description("Milvus dense search latency")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        this.sparseSearchTimer = Timer.builder("retrieval.sparse.duration")
            .description("PG BM25 search latency")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
        this.cacheHitCounter = Counter.builder("retrieval.cache.hit")
            .description("Cache hit count")
            .register(registry);
        this.cacheMissCounter = Counter.builder("retrieval.cache.miss")
            .description("Cache miss count")
            .register(registry);
    }

    @Around("execution(* org.liar.zhiliao.retrieval.tools.KnowledgeRetrievalTool.retrieveKnowledge(..))")
    public Object measureDenseSearch(ProceedingJoinPoint pjp) throws Throwable {
        return denseSearchTimer.recordCallable(pjp::proceed);
    }

    /**
     * 缓存命中时调用。
     */
    public void recordCacheHit() {
        cacheHitCounter.increment();
    }

    /**
     * 缓存未命中时调用。
     */
    public void recordCacheMiss() {
        cacheMissCounter.increment();
    }
}
```

- [ ] **Step 2: application.yaml 追加 Actuator 配置**

```yaml
# management 端点配置
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics,env
  metrics:
    tags:
      application: zhiliao
    export:
      prometheus:
        enabled: true
  endpoint:
    health:
      show-details: always
    prometheus:
      enabled: true
```

- [ ] **Step 3: docker/local-dev.yml 追加 Prometheus + Grafana**

```yaml
# 在 docker/local-dev.yml 中追加
prometheus:
  image: prom/prometheus:latest
  container_name: zhiliao-prometheus
  ports:
    - "9090:9090"
  volumes:
    - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
    - prometheus-data:/prometheus
  networks:
    - zhiliao-net

grafana:
  image: grafana/grafana:latest
  container_name: zhiliao-grafana
  ports:
    - "3000:3000"
  depends_on:
    - prometheus
  networks:
    - zhiliao-net

# 在 volumes 中追加
volumes:
  prometheus-data:
```

- [ ] **Step 4: 创建 Prometheus 配置文件**

```yaml
# docker/prometheus/prometheus.yml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'zhiliao-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

```yaml
# docker/prometheus/alerts.yml
groups:
  - name: zhiliao-alerts
    rules:
      - alert: HighErrorRate
        expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "HTTP 5xx 错误率超过 5%"

      - alert: SlowLLMResponse
        expr: retrieval_dense_duration_seconds_p99 > 5
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "LLM 检索 P99 延迟超过 5 秒"
```

---

### Task 5: AdminFilter + 管理后台基础

**Files:**
- Create: `zhiliao-auth/.../filter/AdminFilter.java`
- Create: `zhiliao-admin/.../entity/ZlKnowledgeBase.java`
- Create: `zhiliao-admin/.../mapper/ZlKnowledgeBaseMapper.java`
- Create: `zhiliao-admin/.../vo/DashboardVO.java`

- [ ] **Step 1: 创建 AdminFilter**

```java
/**
 * 管理员权限过滤器。
 * 在所有 /api/admin/* 请求上校验当前用户角色。
 *
 * @Order(2) 在 JwtFilter(@Order(1)) 之后执行，
 * 此时 UserContextHolder 已设置当前用户。
 */
@Slf4j
@Component
@Order(2)
public class AdminFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        // 非管理员路径直接放行
        if (!path.startsWith("/api/admin/")) {
            chain.doFilter(request, response);
            return;
        }

        // 校验管理员权限
        CurrentUser user = UserContextHolder.get();
        if (user == null || !"ADMIN".equals(user.role())) {
            log.warn("非管理员用户尝试访问管理接口: path={}, user={}", path, user);
            ((HttpServletResponse) response).setStatus(403);
            response.getWriter().write("{\"error\":\"需要管理员权限\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 2: 创建 ZlKnowledgeBase 实体（含 JavaDoc）和 Mapper**

```java
/**
 * 知识库实体。
 * 知识库是文档的逻辑容器，一个知识库包含多个文档。
 * 部门级可见性通过 zl_kb_dept_visibility 表控制。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@TableName("zl_knowledge_base")
public class ZlKnowledgeBase {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 所属部门 ID */
    private Long deptId;

    @Builder.Default
    private String tenantId = "default";

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}

/**
 * 知识库 Mapper。
 */
@Mapper
public interface ZlKnowledgeBaseMapper extends BaseMapper<ZlKnowledgeBase> {
}
```

- [ ] **Step 3: 创建 DashboardVO**

```java
/**
 * 数据看板响应 VO。
 */
public record DashboardVO(
    long totalDocuments,
    long totalChunks,
    long totalUsers,
    long totalConversations,
    long todayConversations,
    double avgResponseTime,
    double cacheHitRate
) {}
```

---

### Task 6: 管理后台控制器

**Files:**
- Create: `zhiliao-admin/.../controller/KnowledgeBaseController.java`
- Create: `zhiliao-admin/.../controller/AdminDocumentController.java`
- Create: `zhiliao-admin/.../controller/AdminUserController.java`
- Create: `zhiliao-admin/.../controller/AuditLogController.java`
- Create: `zhiliao-admin/.../controller/DashboardController.java`
- Create: `zhiliao-admin/.../service/DashboardService.java`

- [ ] **Step 1: 知识库 CRUD 控制器**

```java
/**
 * 知识库管理控制器。
 * 管理员创建、编辑、删除和列出知识库。
 * 所有接口需要 ADMIN 角色（由 AdminFilter 校验）。
 */
@RestController
@RequestMapping("/api/admin/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final ZlKnowledgeBaseMapper kbMapper;

    @GetMapping
    public List<ZlKnowledgeBase> list() {
        return kbMapper.selectList(Wrappers.emptyWrapper());
    }

    @PostMapping
    public ZlKnowledgeBase create(@RequestBody ZlKnowledgeBase kb) {
        kbMapper.insert(kb);
        return kb;
    }

    @PutMapping("/{id}")
    public ZlKnowledgeBase update(@PathVariable Long id, @RequestBody ZlKnowledgeBase kb) {
        kb.setId(id);
        kbMapper.updateById(kb);
        return kb;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        kbMapper.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 2: 文档管理控制器**

```java
/**
 * 文档管理控制器。
 * 文档列表查询、按状态筛选、触发重新处理。
 */
@RestController
@RequestMapping("/api/admin/documents")
@RequiredArgsConstructor
public class AdminDocumentController {

    private final ZlDocumentMapper documentMapper;

    @GetMapping
    public List<ZlDocument> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long kbId) {
        var wrapper = Wrappers.<ZlDocument>lambdaQuery();
        if (status != null) {
            wrapper.eq(ZlDocument::getStatus, status);
        }
        if (kbId != null) {
            wrapper.eq(ZlDocument::getKbId, kbId);
        }
        wrapper.orderByDesc(ZlDocument::getCreatedAt);
        return documentMapper.selectList(wrapper);
    }
}
```

- [ ] **Step 3: 用户管理控制器**

```java
/**
 * 用户管理控制器。
 * 用户列表查询和角色变更。
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final SysUserMapper userMapper;

    @GetMapping
    public List<SysUser> list() {
        return userMapper.selectList(Wrappers.emptyWrapper());
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<Void> updateRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        SysUser user = userMapper.selectById(id);
        if (user == null) return ResponseEntity.notFound().build();
        user.setRole(body.get("role"));
        userMapper.updateById(user);
        return ResponseEntity.ok().build();
    }
}
```

- [ ] **Step 4: 审计日志和看板控制器**

```java
/**
 * 审计日志查询控制器。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {
    // 使用 MyBatis-Plus 分页查询 zl_audit_log 表
    // 暂不实现，留空接口
}

/**
 * 数据看板控制器。
 * 提供系统运行状态的统计数据。
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public DashboardVO getDashboard() {
        return dashboardService.getStats();
    }
}
```

- [ ] **Step 5: 创建 DashboardService**

```java
/**
 * 数据看板统计服务。
 * 聚合文档数、chunk 数、用户数、对话数等统计信息。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ZlDocumentMapper documentMapper;
    private final ZlChunkMapper chunkMapper;
    private final SysUserMapper userMapper;

    public DashboardVO getStats() {
        return new DashboardVO(
            documentMapper.selectCount(Wrappers.emptyWrapper()),
            chunkMapper.selectCount(Wrappers.emptyWrapper()),
            userMapper.selectCount(Wrappers.emptyWrapper()),
            0,      // totalConversations（zl_conversation 表）
            0,      // todayConversations
            0.0,    // avgResponseTime（从 Prometheus 指标获取更准）
            0.0     // cacheHitRate
        );
    }
}
```

> **注意**：`DashboardService` 需要访问 `ZlChunkMapper`。当前 `ZlChunkMapper` 在 `zhiliao-ingestion` 模块中。如果 `zhiliao-admin` 不依赖 `zhiliao-ingestion`，需要在 admin 模块中添加对 ingestion 的依赖，或将 `ZlChunkMapper` 移到 `zhiliao-common`。建议方案：`zhiliao-admin` 依赖 `zhiliao-ingestion`。

---

## 自审检查

| 设计要求 | 对应任务 | 覆盖情况 |
|----------|----------|----------|
| Redis L2 缓存 | Task 2 | @Cacheable + RedisCacheManager |
| 热点问答缓存 | Task 2 Step 2 + Step 3 | RetrievalCacheService + KnowledgeRetrievalTool 接入 |
| Embedding 向量缓存 | Task 2 Step 2 | RetrievalCacheService.getCachedEmbedding |
| @RateLimiter 限流 | Task 3 | chatRateLimiter 30次/分钟 + fallback |
| @CircuitBreaker 熔断 | Task 3 Step 1 | llmApi 熔断器配置（依赖实际调用点加入注解） |
| Actuator + Prometheus | Task 4 Step 2 + Step 4 | endpoint 暴露 + prometheus.yml |
| 自定义检索指标 | Task 4 Step 1 | RetrievalMetricsAspect（Timer + Counter）|
| Grafana | Task 4 Step 3 | Docker Compose 新增 |
| 告警规则 | Task 4 Step 4 | 错误率 > 5% + P99 > 5s |
| AdminFilter | Task 5 Step 1 | @Order(2) 管理员权限校验 |
| 知识库 CRUD | Task 6 Step 1 | KnowledgeBaseController |
| 文档管理列表 | Task 6 Step 2 | AdminDocumentController |
| 用户管理 | Task 6 Step 3 | AdminUserController + 角色变更 |
| 审计日志 | Task 6 Step 4 | AuditLogController 预留 |
| 数据看板 | Task 6 Step 4 + Step 5 | DashboardController + DashboardService |
| SQL COMMENT | Task 5 Step 2 | ZlKnowledgeBase 实体含 JavaDoc |
