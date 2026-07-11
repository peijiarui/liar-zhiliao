# 可观测性与运维设计文档

> 版本：v1.0 | 日期：2026-07-11 | 作者：Pei
> 基于：企业级 RAG 知识库架构设计文档 (v1.0)

## 1. 概述

本文档覆盖"企业级 RAG 知识库"第三阶段**可观测性与运维**的设计方案。当前 MVP 无缓存、无限流、无监控、无管理后台。阶段三新增两级缓存、限流熔断、Prometheus/Grafana 监控、管理后台 REST API 四项能力。

## 2. 两级缓存

### 2.1 架构

```
请求 → Caffeine (L1, JVM 内存, <0.01ms)
         → 未命中 → Redis (L2, 分布式, ~1ms)
                      → 未命中 → 实际调用 LLM/DB (1000ms+)
```

初始实现先做 Redis L2（复用现有 Redis），L1 Caffeine 后续叠加。

### 2.2 缓存场景

| 场景 | TTL | Key 设计 | 预期命中率 | 说明 |
|------|-----|----------|-----------|------|
| 热点问答 | 1h | `cache:qa:{md5(问题全文)}` | L2: ~60% | 相同问题不重复调用 LLM |
| Embedding 向量 | 24h | `cache:embed:{md5(文本)}` | ~80% | 相同文本不重复向量化 |
| 用户权限 | 5min | `cache:perm:{userId}` | ~95% | 减少 DB 查询 |

### 2.3 实现方式

通过 Spring Cache 抽象 + Redis，声明式缓存：

```java
/**
 * 检索缓存服务。
 * 通过 @Cacheable 注解声明缓存，基于 Redis 实现。
 * TTL 在 CacheConfig 中统一配置。
 */
@Service
public class RetrievalCacheService {

    /**
     * 获取缓存的热点问答答案。
     * key = md5(问题)，value = 回答文本，TTL = 1h
     */
    @Cacheable(value = "qa_cache", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes())")
    public String getCachedAnswer(String question, String memoryId) {
        return null;  // 缓存未命中返回 null，由调用方执行实际 LLM 调用
    }

    @CacheEvict(value = "qa_cache", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#question.getBytes())")
    public void evictAnswer(String question) {}

    /**
     * 获取缓存的 Embedding 向量。
     * key = md5(文本)，value = 向量 JSON 字符串
     */
    @Cacheable(value = "embedding_cache", key = "T(org.springframework.util.DigestUtils).md5DigestAsHex(#text.getBytes())")
    public String getCachedEmbedding(String text) {
        return null;
    }
}
```

### 2.4 缓存配置

```java
/**
 * 缓存配置。
 * Redis 缓存管理器，各缓存区的 TTL 和容量在此统一管理。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        return RedisCacheManager.builder(factory)
            .withCacheConfiguration("qa_cache",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofHours(1)))
            .withCacheConfiguration("embedding_cache",
                RedisCacheConfiguration.defaultCacheConfig()
                    .entryTtl(Duration.ofHours(24)))
            .build();
    }
}
```

### 2.5 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `CacheConfig.java` | zhiliao-app | 新建，RedisCacheManager + @EnableCaching |
| `RetrievalCacheService.java` | zhiliao-retrieval | 新建，@Cacheable 封装 |
| `KnowledgeRetrievalTool.java` | zhiliao-retrieval | 修改，嵌入缓存调用 |

## 3. 限流熔断

### 3.1 限流场景

| 场景 | 限制 | 超限行为 |
|------|------|----------|
| 单用户对话 | 30 次/分钟 | HTTP 429 + "请求过于频繁，请稍后重试" |
| LLM API | 跟随 API 配额 | 熔断降级为"AI 服务暂时不可用" |

### 3.2 技术选型：Resilience4j

Spring Boot 3.5 原生兼容，通过 `@RateLimiter` 和 `@CircuitBreaker` 注解声明式配置。

**限流器**：按用户维度（从 UserContextHolder 取用户 ID 作为限流 key）

**熔断器**：保护 LLM/Embedding API 调用，避免连续失败浪费资源

### 3.3 配置

```yaml
zhiliao:
  rate-limit:
    chat-per-user: 30        # 单用户每分钟最多 30 次对话
    llm-api-per-second: 10   # LLM API 调用限速
```

```yaml
resilience4j:
  circuitbreaker:
    instances:
      llmApi:
        sliding-window-size: 10       # 10 次调用为一个滑动窗口
        failure-rate-threshold: 50    # 50% 失败率开启熔断
        wait-duration-in-open-state: 30s  # 30s 后尝试半开
        permitted-number-of-calls-in-half-open-state: 3
  ratelimiter:
    instances:
      chatRateLimiter:
        limit-for-period: 30
        limit-refresh-period: 1m
```

### 3.4 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `pom.xml` | zhiliao-app | 追加 resilience4j-spring-boot3-starter |
| `application.yaml` | zhiliao-app | 追加限流熔断配置 |
| `ChatController.java` | zhiliao-chat | 修改，追加 @RateLimiter + fallback 方法 |

## 4. 监控告警

### 4.1 指标体系

| 类别 | 指标 | 来源 |
|------|------|------|
| LLM 调用 | 次数、延迟 P50/P95/P99、Token 消耗 | Micrometer @Timed |
| 检索性能 | 召回率、检索引擎延迟分布 | 自定义 Timer |
| 缓存 | 命中率、穿透次数 | CacheMeterBinder |
| 应用 | QPS、HTTP 错误率 | Actuator 自动暴露 |
| JVM | 堆内存、GC 频率与耗时 | Actuator 自动暴露 |
| 基础设施 | Redis/Milvus/PG 连接池状态 | HealthIndicator |

### 4.2 采集与展示

```
应用 → Actuator (/actuator/prometheus) → Prometheus (拉取) → Grafana (看板)
                                              → 告警规则 → 钉钉/邮件
```

### 4.3 自定义检索指标

```java
/**
 * 检索性能指标采集。
 * 通过 Micrometer Timer/Counter 记录检索各环节延迟和调用量。
 */
@Aspect
@Component
public class RetrievalMetricsAspect {

    private final Timer denseSearchTimer;    // Milvus 检索耗时
    private final Timer sparseSearchTimer;   // PG BM25 检索耗时
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    public RetrievalMetricsAspect(MeterRegistry registry) {
        this.denseSearchTimer = Timer.builder("retrieval.dense.duration")
            .description("Milvus dense search latency")
            .register(registry);
        this.sparseSearchTimer = Timer.builder("retrieval.sparse.duration")
            .description("PG BM25 search latency")
            .register(registry);
        this.cacheHitCounter = Counter.builder("retrieval.cache.hit")
            .register(registry);
        this.cacheMissCounter = Counter.builder("retrieval.cache.miss")
            .register(registry);
    }
}
```

### 4.4 Docker Compose 新增

```yaml
prometheus:
  image: prom/prometheus:latest
  ports: ["9090:9090"]
  volumes:
    - ../prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
  networks: [zhiliao-net]

grafana:
  image: grafana/grafana:latest
  ports: ["3000:3000"]
  depends_on: [prometheus]
  networks: [zhiliao-net]
```

Prometheus 配置文件：

```yaml
# prometheus/prometheus.yml
scrape_configs:
  - job_name: 'zhiliao-app'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```

### 4.5 告警规则（预留）

```yaml
# prometheus/alerts.yml
groups:
  - name: zhiliao-alerts
    rules:
      - alert: HighErrorRate
        expr: rate(http_requests_seconds_count{status=~"5.."}[5m]) > 0.05
        for: 2m
        labels: { severity: warning }
      - alert: SlowLLMResponse
        expr: llm_api_duration_seconds_p99 > 5
        for: 1m
        labels: { severity: warning }
```

### 4.6 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `pom.xml` | zhiliao-app | 追加 micrometer-registry-prometheus |
| `application.yaml` | zhiliao-app | 追加 management 配置 |
| `RetrievalMetricsAspect.java` | zhiliao-retrieval | 新建，自定义指标 |
| `local-dev.yml` | docker | 追加 prometheus + grafana |
| `prometheus/prometheus.yml` | docker | 新建，抓取配置 |
| `prometheus/alerts.yml` | docker | 新建，告警规则 |

## 5. 管理后台 REST API

### 5.1 功能范围

| 模块 | 接口 | 说明 |
|------|------|------|
| 知识库管理 | `CRUD /api/admin/knowledge-bases` | 创建/编辑/删除/分页列表 |
| 文档管理 | `GET /api/admin/documents` | 文档列表、状态筛选、重新处理 |
| 用户管理 | `GET /api/admin/users` | 用户列表、角色变更 |
| 审计日志 | `GET /api/admin/audit-logs` | 操作日志分页查询 |
| 数据看板 | `GET /api/admin/dashboard` | 统计数据概览 |

### 5.2 权限控制

所有 `/api/admin/*` 接口要求 `role = 'ADMIN'`，通过独立的 `AdminFilter` 校验：

```java
/**
 * 管理员权限过滤器。
 * @Order(2) 在 JwtFilter(@Order(1)) 之后执行，
 * 确保 UserContextHolder 已设置当前用户。
 */
@Component
@Order(2)
public class AdminFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        if (req.getRequestURI().startsWith("/api/admin/")) {
            CurrentUser user = UserContextHolder.get();
            if (user == null || !"ADMIN".equals(user.role())) {
                ((HttpServletResponse) response).setStatus(403);
                response.getWriter().write("需要管理员权限");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
```

### 5.3 数据看板接口

```java
@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    @GetMapping
    public DashboardVO getDashboard() {
        // 统计文档数、chunk 数、用户数、对话数
        // 今日对话数、平均响应时间、缓存命中率
        return dashboardService.getStats();
    }
}

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

### 5.4 涉及文件

| 文件 | 模块 | 操作 |
|------|------|------|
| `KnowledgeBaseController.java` | zhiliao-admin | 新建，知识库 CRUD |
| `AdminDocumentController.java` | zhiliao-admin | 新建，文档管理 |
| `AdminUserController.java` | zhiliao-admin | 新建，用户管理 |
| `AuditLogController.java` | zhiliao-admin | 新建，审计日志 |
| `DashboardController.java` | zhiliao-admin | 新建，数据看板 |
| `AdminFilter.java` | zhiliao-auth | 新建，管理员权限校验 |
| `ZlKnowledgeBase.java` | zhiliao-admin | 新建，MyBatis-Plus 实体 |
| `ZlKnowledgeBaseMapper.java` | zhiliao-admin | 新建 |
| `ZkKnowledgeBaseService.java` | zhiliao-admin | 新建，知识库 CRUD |
| `DashboardService.java` | zhiliao-admin | 新建，统计数据查询 |
| `SchemaVO.java` 等 VO 类 | zhiliao-admin | 若干新建 |
| `audit_log` 使用已有 mapper | zhiliao-auth | 复用 SysUserMapper、ZlDocumentMapper 等 |

## 6. 数据流转示意

```
用户请求 → @RateLimiter(限流) → ChatController → InputFilter
  → RetrievalCacheService(@Cacheable)
    → ChatService → KnowledgeRetrievalTool
      → RetrievalMetricsAspect(计时)
      → 双路检索 → RRF → 父替换
      → @CircuitBreaker(熔断) → LLM 回答
```

管理员操作：
```
管理员请求 → JwtFilter → AdminFilter(role检查)
  → zhiliao-admin Controller → MyBatis-Plus CRUD
```

## 7. 不影响已有功能

- 文档摄入管线（ingestion）不修改
- 检索管线（retrieval）仅追加缓存和指标，不改动现有逻辑
- 用户认证（auth）仅追加 AdminFilter，不改动现有 JwtFilter 和 AuthController
- 前端测试客户端不修改
