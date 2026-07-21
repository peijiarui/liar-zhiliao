# 缓存方案重构：检索缓存 + 查询改写缓存 + 规范化 Key

## 背景

当前缓存使用 `MD5(原始问题)` 作为 key，缓存 LLM 的完整答案（`qa_answer`）。存在五个核心问题：

1. **假性空对话** — 缓存命中直接返回答案，不走 LLM → chatMemory 不记录该轮对话，破坏多轮体验
2. **改写绕过缓存** — `tryCache()` 限制「仅未改写查询」才读写缓存
3. **改写不缓存** — `rewriteQuery()` 每次调用 LLM，同一问题重复改写
4. **文档更新不淘汰** — 仅 TTL 过期淘汰，知识库更新后用户拿到过时答案
5. **数据权限不隔离** — 不同部门用户检索同一问题得到不同结果，缓存混用有越权风险

## 方案概览

**核心思路：不再缓存 LLM 完整答案，改为缓存查询改写结果和检索结果。LLM 始终被调用，chatMemory 正常写入。**

```
原始问题 → normalize() → 规范化查询
  → 查 rewrite 缓存（命中直接获取改写结果）
  → 查 retrieval 缓存（key 混入部门 ID 做权限隔离）
  → 完整管线（兜底）
```

## 数据流

```
retrieveKnowledge("我要查询请假流程")
  │
  ├─ normalize("我要查询请假流程") → "请假流程"
  │
  ├─ 查 rewrite 缓存: key="请假流程"
  │  ├─ 命中 → rewrite = "请假流程"（实际上就是原值，无需 LLM 改写）
  │  └─ 未命中 → 需要判断:
  │     ├─ normalize 后 != query → 调 LLM 改写 → 写入 rewrite 缓存
  │     └─ normalize 后 == query → 改写无意义，直接用原值，不调 LLM
  │
  ├─ 查 retrieval 缓存: key="请假流程:1_2_3"
  │  ├─ 命中 → 返回 chunks → LLM 组装答案
  │  └─ 未命中 → 完整检索管线 → 写入 retrieval 缓存
  │
  └─ LLM 流式回答 + chatMemory 正常写入
```

## Key 设计

| 缓存区 | Key 格式 | 说明 |
|--------|----------|------|
| `query_rewrite` | `cache:rewrite:{normalized_query}` | normalize 后的字符串，可读性强 |
| `retrieval_result` | `cache:retrieval:{normalized_query}:{dept_1_dept_2}` | 末尾拼接可见部门 ID（排序后下划线连接） |

**注意：** 对于超长原始问题（>200 字符），normalize 后的字符串仍可能很长。此时可用 MD5(normalized_query) 代替，但当前场景下 normalize 产物通常为短文本（<50 字符），直接用原文即可。如需切换，在 `buildCacheKey()` 实现处添加 MD5 压缩逻辑。

## normalize() 过滤规则

去除常见语气词和无效字符，将原始问题映射到规范化形态：

| 类别 | 规则 | 示例 |
|------|------|------|
| 语气前缀 | `^(请问\|我想问\|我要\|我想\|我来\|我查一下\|帮我\|能不能\|可以\|麻烦)` | 请问请假流程 → 请假流程 |
| 语气后缀 | `(是什么\|怎么做\|如何操作\|怎么弄\|怎么办\|有哪些\|在哪\|怎么走\|呢\|吗\|啊\|呀\|嘛\|哦)$` | 请假流程是什么 → 请假流程 |
| 无效字符 | `[\pP\pZ\s]` 全半角标点、空格、换行 | 请假、流程。 → 请假流程 |
| 重复词 | `(.+?)\1+` → `$1` | 请假请假流程 → 请假流程 |

```java
String normalize(String raw) {
    String s = raw.trim();
    s = s.replaceAll("^(请问|我想问|我要|我想|我来|我查一下|帮我|能不能|可以|麻烦)", "");
    s = s.replaceAll("(是什么|怎么做|如何操作|怎么弄|怎么办|有哪些|在哪|怎么走|呢|吗|啊|呀|嘛|哦)$", "");
    s = s.replaceAll("[\\pP\\pZ\\s]", "");
    s = s.replaceAll("(.+?)\\1+", "$1");
    return s;
}
```

**注意：** 此规则覆盖大部分常见场景。少数场景中 normalize 后结果为空（如用户只说「请问」），此时保留原始问题作为 key，以 `MD5(raw)` 兜底。

## 缓存区配置

### query_rewrite

| 层级 | 类型 | 容量 | TTL |
|------|------|------|-----|
| L1 | Caffeine | 2000 条 | 10 min |
| L2 | Redis | 不限 | 1 h |

Redis key 示例：`cache:rewrite:请假流程`

### retrieval_result

改为 key 拼接部门 ID：

| 层级 | 类型 | 容量 | TTL |
|------|------|------|-----|
| L1 | Caffeine | 1000 条 | 10 min |
| L2 | Redis | 不限 | 24 h |

Redis key 示例：`cache:retrieval:请假流程:1_2_3`

## 缓存写入与读取

### query_rewrite

```java
// RetrievalCacheService.java

@Cacheable(value = "query_rewrite", key = "#normalizedQuery")
public String getRewrite(String normalizedQuery) {
    return null; // 未命中时返回 null，触发 LLM 改写
}

@CachePut(value = "query_rewrite", key = "#normalizedQuery")
public String putRewrite(String normalizedQuery, String rewriteResult) {
    return rewriteResult;
}
```

### retrieval_result

部门 ID 拼接在 key 中，与 `KnowledgeRetrievalTool` 中的 `buildCacheKey()` 配合：

```java
// RetrievalCacheService.java

private String buildCacheKey(String canonicalQuery) {
    // 当前用户不可见时使用兜底部门 1
    CurrentUser user = UserContextHolder.get();
    List<Long> deptIds = user != null ? user.visibleDeptIds() : List.of(1L);
    deptIds.sort(Long::compareTo);
    String deptSuffix = deptIds.stream().map(String::valueOf).collect(Collectors.joining("_"));
    return canonicalQuery + ":" + deptSuffix;
}

@Cacheable(value = "retrieval_result", key = "#canonicalQuery + ':' + #deptSuffix")
public List<RankedChunk> getCachedRetrieval(String canonicalQuery) {
    return null;
}

@CachePut(value = "retrieval_result", key = "#canonicalQuery + ':' + #deptSuffix")
public List<RankedChunk> putRetrieval(String canonicalQuery, String deptSuffix, List<RankedChunk> chunks) {
    return chunks;
}
```

## 文档更新淘汰

知识库文档更新（chunk save/delete）时，发布事件，监听器全量清空两个缓存区：

```java
// 事件
public record DocumentUpdateEvent(Set<Long> docIds) {}

// 监听器
@EventListener
public void onDocumentUpdate(DocumentUpdateEvent event) {
    retrievalCacheService.evictAllRetrieval(); // 清空 retrieval 缓存
    retrievalCacheService.evictAllRewrite();   // 清空 rewrite 缓存
}
```

**合理性：** 知识库文档更新频率低（每日 1-3 次人工审核后批量更新），全量淘汰可接受。淘汰后首次查询走完整管线。

## 移除 qa_answer

**`qa_answer` 缓存区整体移除，`ChatController` 中的 L1 查/写逻辑清理。**

理由：
- 缓存 LLM 完整答案导致 chatMemory 不记录该轮对话，严重破坏多轮体验
- 缓存 chunks 给 LLM 组装同样省掉了大部分延迟，且不影响多轮
- 简化代码：少维护一个缓存区的心智负担

## 缓存命中率预估

| 场景 | 命中率 | 延迟节省 |
|------|--------|----------|
| 改写缓存命中 | 高频问题 ~40% | 省 1 次 LLM 改写调用 (~1-2s) |
| 检索缓存命中 | 高频问题 ~30% | 省 embedding+稠密+稀疏+rerank (~500ms) |
| 两者均命中 | 高频问题 ~25% | ~1.5-2.5s |

## 涉及改动文件

| 文件 | 模块 | 操作 | 说明 |
|------|------|------|------|
| `CacheConfig.java` | zhiliao-app | 修改 | 移除 `qa_answer` 配置，新增 `query_rewrite` 缓存区 |
| `RetrievalCacheService.java` | zhiliao-retrieval | 重写 | 新增 rewrite 缓存方法；移除 qa_answer 相关 |
| `KnowledgeRetrievalTool.java` | zhiliao-retrieval | 修改 | 新增 normalize + 缓存读写逻辑；放开缓存限制 |
| `DocumentUpdateEventListener.java` | zhiliao-retrieval | **新建** | 文档更新时全量淘汰缓存 |
| `RetrievalConfig.java` | zhiliao-retrieval | 修改 | 发布 DocumentUpdateEvent |
| `ChatController.java` | zhiliao-chat | 修改 | 移除 qa_answer 的 L1 查/写逻辑 |
| `TwoTierCache.java` | zhiliao-app | 不变 | 两级缓存实现无需改动 |
| `TwoTierCacheManager.java` | zhiliao-app | 不变 | 无需改动 |
