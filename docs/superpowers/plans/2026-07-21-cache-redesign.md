# 缓存重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将缓存从「LLM 完整答案 MD5 匹配」重构为「检索结果 + 查询改写的规范化 Key 缓存」，解决假性空对话、改写不缓存、文档不淘汰、权限不隔离四个问题。

**架构:** 移除 `qa_answer` 缓存区，新增 `query_rewrite` 缓存区，`retrieval_result` 改为 `{规范化查询}:{部门ID}` 复合 key。所有改动在 `KnowledgeRetrievalTool` 内部封闭，对外无感知。

**Tech Stack:** Spring Cache (Caffeine + Redis), SpEL, Spring Events

## 全局约束

- 所有代码中关键逻辑必须有注释
- 超长原始问题（>200 字符）normalize 后仍过长时，在 `buildCacheKey()` 中使用 `MD5(normalized)` 压缩 key
- normalize() 后结果为空字符串时，退化为 `MD5(原始问题)` 作为缓存 key
- 遵循现有命名风格：`RetrievalCacheService` 使用 `@Cacheable`/`@CachePut` 注解方式

---

### Task 1: CacheConfig — 移除 qa_answer，新增 query_rewrite

**Files:**
- Modify: `zhiliao-app/src/main/java/org/liar/zhiliao/config/CacheConfig.java:43-51`

**Interfaces:**
- Consumes: 无
- Produces: 新增 `query_rewrite` 缓存区，被 Task 3 消费

- [ ] **Step 1: 修改 CacheConfig.java**

将 CaffeineCacheManager 的缓存名从 `"qa_answer", "retrieval_result"` 改为 `"query_rewrite", "retrieval_result"`，同时将 maxSize 提升至 2000（兼容两个缓存区）。

```java
// line 43 — CaffeineCacheManager
CaffeineCacheManager manager = new CaffeineCacheManager("query_rewrite", "retrieval_result");
manager.setCaffeine(Caffeine.newBuilder()
        // 缓存上限2000条（rewrite + retrieval 共享），防止内存泄漏
        .maximumSize(2000)
        // 缓存10min
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .recordStats());
```

移除 `qa_answer` 的 Redis 配置块（`CacheConfig.java` 中 `withCacheConfiguration("qa_answer", ...)` 整个块，约 8 行），新增 `query_rewrite` 配置块：

```java
// 在 redisCacheManager() 方法中，替换原有的 qa_answer 配置
// 改写结果缓存1小时
.withCacheConfiguration("query_rewrite",
        RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(stringKey)
                .serializeValuesWith(jsonValue)
                .entryTtl(Duration.ofHours(1))
                .prefixCacheNameWith("cache:rewrite:")
                .disableCachingNullValues())
```

> 注意：`retrieval_result` 的 Redis 配置保留不动，仅需将 Redis key prefix 改为 `cache:retrieval:`（当前已是此值，确认即可）。

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl zhiliao-app -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add zhiliao-app/src/main/java/org/liar/zhiliao/config/CacheConfig.java
git commit -m "refactor(cache): 移除 qa_answer，新增 query_rewrite 缓存区
```
---

### Task 2: DocumentUpdateEvent — 定义事件并发布

**Files:**
- Create: `zhiliao-common/src/main/java/org/liar/zhiliao/common/event/DocumentUpdateEvent.java`
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessor.java`

**Interfaces:**
- Consumes: 无
- Produces: `DocumentUpdateEvent` 事件，被 Task 5 消费

- [ ] **Step 1: 创建 DocumentUpdateEvent 记录类**

```java
package org.liar.zhiliao.common.event;

import java.util.Set;

/**
 * 文档更新事件，知识库文档处理完成或更新时发布。
 * 监听器消费此事件后执行缓存全量淘汰等副作用操作。
 */
public record DocumentUpdateEvent(Set<Long> docIds) {
}
```

- [ ] **Step 2: 在 DocumentConsumerProcessor 中注入 ApplicationEventPublisher 并发布事件**

在 `DocumentConsumerProcessor.java` 中新增字段：

```java
import org.springframework.context.ApplicationEventPublisher;

// 在类字段中新增
private final ApplicationEventPublisher eventPublisher;
```

在 `process()` 方法末尾，document 状态更新为 COMPLETED 之后，发布事件：

```java
// 在 doc.setStatus(DocumentStatusEnum.COMPLETED.getStatus()) 之后
// doc.setChunkCount(...) 之后，log.info 之前

// 发布文档更新事件，触发检索缓存全量淘汰
eventPublisher.publishEvent(new DocumentUpdateEvent(Set.of(documentId)));
```

注意：事件仅在状态为 COMPLETED 时发布，FAILED 不发布。

- [ ] **Step 3: 编译验证**

```bash
mvn compile -pl zhiliao-ingestion -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add zhiliao-common/src/main/java/org/liar/zhiliao/common/event/DocumentUpdateEvent.java
git add zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessor.java
git commit -m "feat(cache): 定义 DocumentUpdateEvent 并在文档处理完成后发布
```
---

### Task 3: RetrievalCacheService — 重写缓存服务

**Files:**
- Modify: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/service/RetrievalCacheService.java`（全量重写）

**Interfaces:**
- Consumes: `query_rewrite` 缓存区（Task 1）
- Produces: `getRewrite()`, `putRewrite()`, `evictAllRewrite()`, `getCachedRetrieval()`, `putRetrieval()`, `evictAllRetrieval()` — 被 Task 4 消费

- [ ] **Step 1: 重写 RetrievalCacheService.java**

完整新文件内容：

```java
package org.liar.zhiliao.retrieval.service;

import org.liar.zhiliao.retrieval.records.RankedChunk;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 两级缓存服务。
 *
 * <p>两个缓存区，均走 L1 Caffeine → L2 Redis：</p>
 * <ul>
 *   <li><b>query_rewrite</b>（cache:rewrite:*）：{规范化查询} → LLM 改写后的查询文本，TTL 1h</li>
 *   <li><b>retrieval_result</b>（cache:retrieval:*）：{规范化查询}:{部门ID} → RankedChunk 列表，TTL 24h</li>
 * </ul>
 *
 * <p>注意：retrieval_result 的 key 中拼接了 deptSuffix 用于权限隔离，
 * 同一规范化查询在不同部门维度下缓存不同结果。</p>
 */
@Service
public class RetrievalCacheService {

    // ==================== query_rewrite 缓存 ====================

    /**
     * 获取缓存的查询改写结果。
     *
     * @param normalizedQuery normalize() 后的规范化查询
     * @return 改写后的查询文本，未命中返回 null
     */
    @Cacheable(value = "query_rewrite")
    public String getRewrite(String normalizedQuery) {
        return null;
    }

    /**
     * 写入查询改写缓存。
     *
     * @param normalizedQuery normalize() 后的规范化查询
     * @param rewriteResult   LLM 改写后的查询文本
     * @return 写入的改写结果
     */
    @CachePut(value = "query_rewrite")
    public String putRewrite(String normalizedQuery, String rewriteResult) {
        return rewriteResult;
    }

    /**
     * 全量淘汰 query_rewrite 缓存区。
     * 文档更新时由 DocumentUpdateEventListener 调用。
     */
    @CacheEvict(value = "query_rewrite", allEntries = true)
    public void evictAllRewrite() {
    }

    // ==================== retrieval_result 缓存 ====================

    /**
     * 获取缓存的检索结果。
     * key 由 canonicalQuery 和 deptSuffix 复合构成，天然实现部门间缓存隔离。
     *
     * @param canonicalQuery 用于缓存 key 的规范查询（通常是 normalize 后的值）
     * @param deptSuffix     部门 ID 后缀，格式 "deptId1_deptId2"，由调用方计算
     * @return 缓存的 RankedChunk 列表，未命中返回 null
     */
    @Cacheable(value = "retrieval_result", key = "#canonicalQuery + ':' + #deptSuffix")
    public List<RankedChunk> getCachedRetrieval(String canonicalQuery, String deptSuffix) {
        return null;
    }

    /**
     * 写入检索结果缓存。
     *
     * @param canonicalQuery 用于缓存 key 的规范查询
     * @param deptSuffix     部门 ID 后缀
     * @param rankedChunks   检索结果列表
     * @return 写入的结果列表
     */
    @CachePut(value = "retrieval_result", key = "#canonicalQuery + ':' + #deptSuffix")
    public List<RankedChunk> putRetrieval(String canonicalQuery, String deptSuffix, List<RankedChunk> rankedChunks) {
        return rankedChunks;
    }

    /**
     * 全量淘汰 retrieval_result 缓存区。
     * 文档更新时由 DocumentUpdateEventListener 调用。
     */
    @CacheEvict(value = "retrieval_result", allEntries = true)
    public void evictAllRetrieval() {
    }
}
```

关键设计说明：
- `getCachedRetrieval` 和 `putRetrieval` 的 key 使用 SpEL 表达式 `#canonicalQuery + ':' + #deptSuffix` 拼接为复合 key
- deptSuffix 由调用方（KnowledgeRetrievalTool）计算，服务层不感知用户上下文（符合单一职责）
- 对于超长 canonicalQuery（>200字符的场景），应在调用方 `buildCacheKey()` 中预处理为 MD5，此处不做额外处理

- [ ] **Step 2: 清理旧方法引用**

确认旧版本的 `getCachedAnswer(String)`、`putAnswer(String, String)`、`evictAnswer(String)` 被移除后，没有其他文件引用它们：

```bash
# 确认 qa_answer 相关方法不再被引用
grep -r "getCachedAnswer\|putAnswer\|evictAnswer" zhiliao-*/src --include="*.java" || echo "No references found"
```

Expected: "No references found"

- [ ] **Step 3: 编译验证**

```bash
mvn compile -pl zhiliao-retrieval -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/service/RetrievalCacheService.java
git commit -m "refactor(cache): 重写 RetrievalCacheService，移除 qa_answer 新增 rewrite 缓存
```
---

### Task 4: KnowledgeRetrievalTool — 集成新缓存流程

**Files:**
- Modify: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalTool.java`

**Interfaces:**
- Consumes: `RetrievalCacheService.getRewrite()`, `.putRewrite()`, `.getCachedRetrieval()`, `.putRetrieval()`
- Produces: 向外提供完整的 `retrieveKnowledge()` 方法（接口不变，调用方无感知）

- [ ] **Step 1: 添加 normalize() 方法**

在 `KnowledgeRetrievalTool` 类中添加规范化方法：

```java
// normalize 规则说明：
// 1. 去除常见语气前缀：请问、我想问、我要、我想等
// 2. 去除常见语气后缀：是什么、怎么做、呢、吗等
// 3. 去除全半角标点符号和空白
// 4. 连续重复词去重：请假请假流程 → 请假流程
// 注意：若清洗后为空字符串，调用方应回退使用 MD5(原始查询) 作为兜底 key
static String normalize(String raw) {
    if (raw == null || raw.isBlank()) return "";
    String s = raw.trim();
    s = s.replaceAll("^(请问|我想问|我要|我想|我来|我查一下|帮我|能不能|可以|麻烦)", "");
    s = s.replaceAll("(是什么|怎么做|如何操作|怎么弄|怎么办|有哪些|在哪|怎么走|呢|吗|啊|呀|嘛|哦)$", "");
    s = s.replaceAll("[\\pP\\pZ\\s]", "");
    s = s.replaceAll("(.+?)\\1+", "$1");
    return s;
}
```

- [ ] **Step 2: 添加 buildCacheKey() 工具方法**

```java
// 构建检索缓存 key：规范化查询 + 部门可见性后缀
// 部门后缀用于权限隔离，不同部门的同一规范化查询缓存不同结果
// 注意：对于超长规范化查询（>200字符），使用 MD5 压缩 key 长度
private String buildCacheKey(String canonicalQuery) {
    CurrentUser user = UserContextHolder.get();
    List<Long> deptIds = user != null ? user.visibleDeptIds() : List.of(1L);
    deptIds.sort(Long::compareTo);
    String deptSuffix = deptIds.stream().map(String::valueOf).collect(Collectors.joining("_"));

    // 规范化查询通常为短文本（<50字符），直接用原文做 key 便于排查；
    // 若超长则压缩为 MD5 避免 Redis key 过长
    String queryPart = canonicalQuery.length() > 200
            ? org.springframework.util.DigestUtils.md5DigestAsHex(canonicalQuery.getBytes(StandardCharsets.UTF_8))
            : canonicalQuery;

    return queryPart + ":" + deptSuffix;
}
```

需要新增 imports：
```java
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
```

- [ ] **Step 3: 重写 retrieveKnowledge() 核心流程**

将原 `retrieveKnowledge()` 中的缓存逻辑替换为新流程：

```java
@Tool("检索企业知识库：查找公司制度、政策、流程、产品信息等企业内部知识。仅当用户明确询问企业内部知识时调用，日常闲聊无需调用")
public String retrieveKnowledge(@P("查询内容") String query) {
    // Step 0: 查询规范化
    String normalized = normalize(query);

    // 规范化后为空的极端情况，回退使用 MD5 兜底
    String canonicalKey = normalized.isEmpty()
            ? DigestUtils.md5DigestAsHex(query.getBytes(StandardCharsets.UTF_8))
            : normalized;

    // Step 1: 尝试从 rewrite 缓存获取改写结果
    // 命中：直接用改写结果检索；未命中且 normalize != query → LLM 改写 → 写缓存
    List<String> subQueries;
    String rewritten = retrievalCacheService.getRewrite(canonicalKey);
    if (rewritten != null) {
        subQueries = parseRewriteResult(rewritten);
        log.debug("Rewrite cache hit for '{}': {}", canonicalKey, rewritten);
    } else if (!normalized.equals(query)) {
        // normalize 后有变化，说明需要 LLM 改写
        subQueries = rewriteQuery(query);
        String joined = String.join("\n", subQueries);
        retrievalCacheService.putRewrite(canonicalKey, joined);
    } else {
        // normalize 后无变化（如 "请假流程" 已是规范形态），直接用原值
        subQueries = List.of(normalized);
    }

    // Step 2: 尝试从 retrieval 缓存获取检索结果
    String deptSuffix = buildDeptSuffix();
    List<RankedChunk> ranked = retrievalCacheService.getCachedRetrieval(canonicalKey, deptSuffix);
    if (ranked != null) {
        log.debug("Retrieval cache hit for key: {}:{}", canonicalKey, deptSuffix);
        retrievalMetrics.recordCacheHitRate(1.0);
        return ranked.isEmpty() ? "" : buildContextFromRanked(ranked);
    }

    // Step 3～6: 完整检索管线（原有逻辑保持不变）
    // ...
    // 在得到 ranked 结果后写入缓存
    putCache(canonicalKey, deptSuffix, ranked);

    return buildContextFromRanked(ranked);
}
```

需要提取 `buildDeptSuffix()` 方法和 `parseRewriteResult()` 方法：

```java
private String buildDeptSuffix() {
    CurrentUser user = UserContextHolder.get();
    List<Long> deptIds = user != null ? user.visibleDeptIds() : List.of(1L);
    deptIds.sort(Long::compareTo);
    return deptIds.stream().map(String::valueOf).collect(Collectors.joining("_"));
}

private List<String> parseRewriteResult(String rewritten) {
    return Arrays.stream(rewritten.split("\\n"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
}
```

- [ ] **Step 4: 替换缓存写入方法**

将原 tryCache/putCache 方法替换为：

```java
// 保留原 tryCache 签名但不再使用 — 新流程在核心方法中已直接处理
// 仅保留 putCache 用于 Step 3 写入
private void putCache(String canonicalKey, String deptSuffix, List<RankedChunk> ranked) {
    retrievalCacheService.putRetrieval(canonicalKey, deptSuffix, ranked);
}
```

不再需要原 tryCache 方法，可以删除。

- [ ] **Step 5: 编译验证**

```bash
mvn compile -pl zhiliao-retrieval -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalTool.java
git commit -m "feat(cache): KnowledgeRetrievalTool 集成规范化 key + 双层缓存
```
---

### Task 5: DocumentUpdateEventListener — 新建缓存淘汰监听器

**Files:**
- Create: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/event/DocumentUpdateEventListener.java`

**Interfaces:**
- Consumes: `DocumentUpdateEvent`（Task 2）、`RetrievalCacheService.evictAllRewrite()` / `evictAllRetrieval()`（Task 3）

- [ ] **Step 1: 创建监听器类**

```java
package org.liar.zhiliao.retrieval.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.event.DocumentUpdateEvent;
import org.liar.zhiliao.retrieval.service.RetrievalCacheService;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 文档更新事件监听器。
 * 知识库文档更新（新增/重新处理）时全量淘汰检索相关缓存，
 * 确保用户不会拿到过时的检索结果。
 *
 * 采用全量淘汰而非精准淘汰，因为文档更新频率低（每日1-3次），
 * 全量淘汰的代价远低于维护反向索引的复杂度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentUpdateEventListener {

    private final RetrievalCacheService retrievalCacheService;

    @EventListener
    public void onDocumentUpdate(DocumentUpdateEvent event) {
        log.info("Document update event received, evicting all caches. docIds: {}", event.docIds());
        retrievalCacheService.evictAllRewrite();
        retrievalCacheService.evictAllRetrieval();
        log.info("Cache eviction completed for document update");
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl zhiliao-retrieval -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/event/DocumentUpdateEventListener.java
git commit -m "feat(cache): 新增 DocumentUpdateEventListener 监听文档更新并全量淘汰缓存
```
---

### Task 6: ChatController — 清理 qa_answer 逻辑

**Files:**
- Modify: `zhiliao-chat/src/main/java/org/liar/zhiliao/chat/controller/ChatController.java`

**Interfaces:**
- Consumes: 不再使用 `RetrievalCacheService`
- Produces: 简化的 `chat()` 和 `fallbackToDocs()` 方法

- [ ] **Step 1: 清理 ChatController**

改动内容：
1. 移除 `RetrievalCacheService` 字段注入
2. 移除 `chat()` 方法中的 L1 缓存检查和回写逻辑
3. 简化 `fallbackToDocs()` — 不再查 L1 缓存

```java
// 移除的注入字段：
// private final RetrievalCacheService retrievalCacheService;

// chat() 方法中移除的部分（约 line 60-70）：
// L1 缓存检查：命中直接返回，跳过 LLM
// try {
//     String cached = retrievalCacheService.getCachedAnswer(finalMessage);
//     if (cached != null) { ... return Flux.just(cached); }
// } catch (Exception e) { ... }

// doOnComplete 中移除的部分（约 line 79-84）：
// // 回写 L1 热点问答缓存
// try {
//     retrievalCacheService.putAnswer(finalMessage, fullResponse.toString());
// } catch (Exception e) { ... }

// fallbackToDocs() 简化 — 不再查 L1 缓存（约 line 130-150）：
private Flux<String> fallbackToDocs(String message) {
    // 直接从知识库检索文档片段
    try {
        String docs = knowledgeRetrievalTool.retrieveKnowledge(message);
        if (docs != null && !docs.isEmpty()) {
            return Flux.just("AI 服务暂时繁忙，以下是从知识库找到的相关内容供参考：\n\n" + docs);
        }
    } catch (Exception e) {
        log.warn("Fallback retrieval failed: {}", e.getMessage());
    }
    return Flux.just("AI 服务暂时不可用，请稍后再试。");
}
```

同时移除构造器参数中的 `RetrievalCacheService`（Lombok `@RequiredArgsConstructor` 自动处理）。

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl zhiliao-chat -am -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 整体编译验证**

```bash
mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add zhiliao-chat/src/main/java/org/liar/zhiliao/chat/controller/ChatController.java
git commit -m "refactor(cache): ChatController 移除 qa_answer 缓存查/写逻辑
```
---

## 自检清单

**1. Spec 覆盖：**
- 移除 qa_answer：Task 1 + Task 6 ✓
- 新增 query_rewrite 缓存：Task 1 + Task 3 ✓
- normalize() 过滤：Task 4 ✓
- retrieval_result key + deptSuffix 权限隔离：Task 3 + Task 4 ✓
- 文档更新淘汰：Task 2 + Task 5 ✓
- 改写结果缓存：Task 3 (getRewrite/putRewrite) + Task 4 (改写流程) ✓

**2. 占位符检查：** 无 TBD/TODO，所有代码块内容完整

**3. 类型一致性：**
- `getCachedRetrieval(canonicalQuery, deptSuffix)` — Task 3 定义，Task 4 调用 ✓
- `putRetrieval(canonicalQuery, deptSuffix, chunks)` — Task 3 定义，Task 4 调用 ✓
- `getRewrite(normalizedQuery)` / `putRewrite(normalizedQuery, result)` — Task 3 定义，Task 4 调用 ✓
- `DocumentUpdateEvent(Set<Long>)` — Task 2 定义，Task 5 消费 ✓
