# 检索质量提升 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 MVP 检索管线基础上，引入查询改写、双路混合检索（稠密+稀疏）、RRF 融合排序和父子文档分割，显著提升检索召回率和答案完整性

**Architecture:** KnowledgeRetrievalTool 内部从单路 Milvus 检索重构为：查询改写 → 双路检索（Milvus稠密 + PG BM25稀疏）→ RRF 融合 → 父子文档替换（Child检索→Parent上下文）→ LLM 回答。SparseSearcher 和 Reranker 接口预留，未来可切换为 ES / BGE-Reranker。

**Tech Stack:** Spring Boot 3.5.16, LangChain4j 1.17, PostgreSQL 16 (zhparser 中文分词 + tsvector GIN 索引), MyBatis-Plus 3.5.9, JdbcTemplate

---

## 文件全景

```
修改的文件:
  zhiliao-retrieval/pom.xml                                    (+ spring-boot-starter-jdbc)
  zhiliao-app/src/main/resources/sql/schema.sql               (+ zhparser扩展, GIN索引, parent_id, chunk_type)
  zhiliao-ingestion/.../entity/ZlChunk.java                   (+ parentId, chunkType)
  zhiliao-ingestion/.../service/RecursiveDocumentSplitter.java (返回类型改为 ParentChildSplitResult)
  zhiliao-ingestion/.../service/impl/RecursiveDocumentSplitterImpl.java (实现父子分割逻辑)
  zhiliao-ingestion/.../consumer/DocumentConsumerProcessor.java (双阶段写入: parent→PG, child→PG+Milvus)
  zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java     (查询改写+双路检索+RRF+父子替换)

新建的文件:
  zhiliao-retrieval/.../service/SparseSearcher.java           (稀疏检索接口)
  zhiliao-retrieval/.../service/PgBm25Searcher.java           (PG tsvector 实现)
  zhiliao-retrieval/.../service/Reranker.java                 (精排接口)
  zhiliao-retrieval/.../service/RrfReranker.java              (RRF 融合实现)
  zhiliao-retrieval/.../repository/ChunkRepository.java       (JdbcTemplate 查询 chunk)
  zhiliao-retrieval/.../config/RetrievalConfig.java           (条件装配 Bean)
  zhiliao-ingestion/.../model/ParentChildSplitResult.java     (分割结果 POJO)
```

不修改的文件：ChatController、ChatService、system-prompt.md、Docker Compose、前端

---

### Task 1: 数据库 DDL 变更

**Files:**
- Modify: `zhiliao-app/src/main/resources/sql/schema.sql`

- [ ] **Step 1: 添加 zhparser 扩展和全文搜索索引**

在 `schema.sql` 的 Extensions 区块追加：

```sql
-- zhparser 中文分词插件（全文搜索用）
CREATE EXTENSION IF NOT EXISTS zhparser;
CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser);
```

在 `zl_chunk` 表的索引区块追加：

```sql
-- BM25 全文搜索索引
CREATE INDEX IF NOT EXISTS idx_zl_chunk_content_fts ON zl_chunk
  USING GIN (to_tsvector('zh', content));
```

- [ ] **Step 2: zl_chunk 表追加父子文档字段**

在 `zl_chunk` 表的 `metadata` 列后追加：

```sql
parent_id   BIGINT,           -- 逻辑关联 parent chunk，无外键约束
chunk_type  VARCHAR(10) NOT NULL DEFAULT 'child' CHECK (chunk_type IN ('parent', 'child')),
```

完整列顺序：
```sql
CREATE TABLE IF NOT EXISTS zl_chunk (
    id           BIGSERIAL       PRIMARY KEY,
    doc_id       BIGINT          NOT NULL,
    content      TEXT            NOT NULL,
    embedding_id VARCHAR(100),
    metadata     JSONB,
    parent_id    BIGINT,                           -- 新增
    chunk_type   VARCHAR(10) NOT NULL DEFAULT 'child',  -- 新增
    dept_id      BIGINT          NOT NULL DEFAULT 1,
    tenant_id    VARCHAR(50)     NOT NULL DEFAULT 'default',
    created_at   TIMESTAMPTZ       DEFAULT NOW()
);
```

---

### Task 2: ZlChunk 实体扩展 + 分割接口重构

**Files:**
- Modify: `zhiliao-ingestion/.../entity/ZlChunk.java`
- Modify: `zhiliao-ingestion/.../service/RecursiveDocumentSplitter.java`
- Create: `zhiliao-ingestion/.../model/ParentChildSplitResult.java`

- [ ] **Step 1: ZlChunk 添加 parentId 和 chunkType**

```java
// 在 ZlChunk.java 追加字段
private Long parentId;

@Builder.Default
private String chunkType = "child";  // "parent" | "child"
```

注意保留现有的 `@Builder` 注解，`@Builder.Default` 确保 chunkType 默认值为 "child"。

- [ ] **Step 2: 创建分割结果 POJO**

```java
// 新建文件 ParentChildSplitResult.java
package org.liar.zhiliao.ingestion.model;

import dev.langchain4j.data.segment.TextSegment;
import java.util.List;

/**
 * 父子文档分割结果。
 * parentSegments — 2048 token parent chunks（写 PG）
 * childSegments  — 512 token child chunks（写 PG + Milvus）
 * childParentMapping — child 在 List 中的 index → parent 在 List 中的 index
 */
public record ParentChildSplitResult(
    List<TextSegment> parentSegments,
    List<TextSegment> childSegments,
    int[] childParentMapping   // childParentMapping[childIndex] = parentIndex
) {}
```

- [ ] **Step 3: 修改 RecursiveDocumentSplitter 接口**

```java
// 修改 RecursiveDocumentSplitter.java
// 返回类型从 List<TextSegment> 改为 ParentChildSplitResult

public interface RecursiveDocumentSplitter {
    ParentChildSplitResult split(String text, String documentId);
}
```

---

### Task 3: 父子文档分割实现

**Files:**
- Modify: `zhiliao-ingestion/.../service/impl/RecursiveDocumentSplitterImpl.java`

- [ ] **Step 1: 实现父子分割逻辑**

```java
// RecursiveDocumentSplitterImpl.java
// 使用 LangChain4j 的 DocumentSplitters 两次分割：
// 1. 先分割为 parent chunks (2048 token)
// 2. 每个 parent 再分割为 child chunks (512 token)

@Service
@RequiredArgsConstructor
public class RecursiveDocumentSplitterImpl implements RecursiveDocumentSplitter {

    private final DocumentSplitter parentSplitter;   // 2048 token（在 SpringBeanConfig 定义）
    private final DocumentSplitter childSplitter;     // 512 token（在 SpringBeanConfig 定义）

    @Override
    public ParentChildSplitResult split(String text, String documentId) {
        Document doc = Document.from(text, Metadata.from("documentId", documentId));

        // Step 1: 分割为 parent chunks
        List<TextSegment> parents = parentSplitter.split(doc);

        List<TextSegment> allChildren = new ArrayList<>();
        int[] mapping = new int[parents.size()];  // 先占位，后续动态用 List
        List<Integer> mappingList = new ArrayList<>();

        // Step 2: 每个 parent 再分割为 children
        for (int pIdx = 0; pIdx < parents.size(); pIdx++) {
            Document parentDoc = Document.from(parents.get(pIdx).text());
            List<TextSegment> children = childSplitter.split(parentDoc);
            for (TextSegment child : children) {
                allChildren.add(child);
                mappingList.add(pIdx);  // 记录这个 child 属于哪个 parent
            }
        }

        int[] mappingArray = mappingList.stream().mapToInt(Integer::intValue).toArray();
        return new ParentChildSplitResult(parents, allChildren, mappingArray);
    }
}
```

- [ ] **Step 2: 更新 SpringBeanConfig 定义两个分割器**

```java
// SpringBeanConfig.java
// 原有一个 recursiveDocumentSplitter (500/100)，现在需要两个：
// parentSplitter  → 2048/200
// childSplitter   → 512/50

@Bean
public DocumentSplitter parentSplitter() {
    return DocumentSplitters.recursive(2048, 200);
}

@Bean
public DocumentSplitter childSplitter() {
    return DocumentSplitters.recursive(512, 50);
}
```

> **注意**：删除旧的 `recursiveDocumentSplitter` Bean（maxSize 500 的），DocumentConsumerProcessor 需要改成注入 `parentSplitter` → `childSplitter` 的组合，而非直接注入 RecursiveDocumentSplitter（接口仍为 RecursiveDocumentSplitter，Impl 自动装配 Bean）。

---

### Task 4: DocumentConsumerProcessor 写入适配

**Files:**
- Modify: `zhiliao-ingestion/.../consumer/DocumentConsumerProcessor.java`

- [ ] **Step 1: 改写 process 方法为双阶段写入**

```java
// DocumentConsumerProcessor.java 核心变动

public void process(DocumentMessage message) {
    // ... 1-3步不变：查文档、下载、Tika解析 ...

    // 4. 父子文档分割（返回类型变了）
    ParentChildSplitResult splitResult = recursiveDocumentSplitter.split(text, documentId.toString());

    // 5. 先写所有 parent 到 PG，获取自增 ID
    List<Long> parentIds = new ArrayList<>();
    for (TextSegment parentSeg : splitResult.parentSegments()) {
        ZlChunk parentChunk = ZlChunk.builder()
                .docId(documentId)
                .content(parentSeg.text())
                .chunkType("parent")
                .metadata("{\"fileName\": \"" + message.getFileName() + "\"}")
                .build();
        chunkMapper.insert(parentChunk);  // insert 后 parentChunk.id 被自动回填
        parentIds.add(parentChunk.getId());
    }

    // 6. 再写所有 child 到 PG（含 parent_id），同时收集需要 Embedding 的内容
    List<ZlChunk> childEntities = new ArrayList<>();
    List<TextSegment> childSegments = new ArrayList<>();
    int[] mapping = splitResult.childParentMapping();

    for (int i = 0; i < splitResult.childSegments().size(); i++) {
        TextSegment childSeg = splitResult.childSegments().get(i);
        ZlChunk childChunk = ZlChunk.builder()
                .docId(documentId)
                .content(childSeg.text())
                .parentId(parentIds.get(mapping[i]))  // 关联 parent
                .chunkType("child")
                .metadata("{\"index\": " + i + ", \"fileName\": \"" + message.getFileName() + "\"}")
                .build();
        chunkMapper.insert(childChunk);  // 先写入 PG 获取 child ID
        childEntities.add(childChunk);
        childSegments.add(childSeg);
    }

    // 7. 只对 child 做 Embedding 并写入 Milvus
    // 重要：TextSegment 必须携带 chunkId 和 parentId metadata，
    //       供检索时 RRF 融合识别 chunk 和后续父替换使用
    List<TextSegment> childSegmentsWithMeta = new ArrayList<>();
    for (int i = 0; i < childSegments.size(); i++) {
        ZlChunk childEntity = childEntities.get(i);
        TextSegment segWithMeta = TextSegment.from(
                childSegments.get(i).text(),
                Metadata.from("chunkId", childEntity.getId().toString())
                        .add("parentId", childEntity.getParentId() != null
                                ? childEntity.getParentId().toString() : ""));
        childSegmentsWithMeta.add(segWithMeta);
    }

    List<Embedding> embeddings = embeddingModel.embedAll(childSegmentsWithMeta).content();
    List<String> vectorIds = milvusEmbeddingStore.addAll(embeddings, childSegmentsWithMeta);

    for (int i = 0; i < childEntities.size(); i++) {
        childEntities.get(i).setEmbeddingId(vectorIds.get(i));
        chunkMapper.updateById(childEntities.get(i));
    }

    // 8. 更新 document 状态（chunkCount = parent数，而非child数，parent才是上下文单元）
    doc.setStatus(DocumentStatusEnum.COMPLETED.getStatus());
    doc.setChunkCount(splitResult.parentSegments().size());
    documentMapper.updateById(doc);
}
```

---

### Task 5: 依赖添加 + SparseSearcher 接口 + PgBm25Searcher 实现

**Files:**
- Modify: `zhiliao-retrieval/pom.xml`
- Create: `zhiliao-retrieval/.../service/SparseSearcher.java`
- Create: `zhiliao-retrieval/.../service/PgBm25Searcher.java`
- Create: `zhiliao-retrieval/.../repository/ChunkRepository.java`

- [ ] **Step 1: 添加依赖到 zhiliao-retrieval/pom.xml**

```xml
<!-- JdbcTemplate 用于 PG BM25 查询 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<!-- PostgreSQL 驱动 -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: 创建 ChunkRepository（JdbcTemplate 读写 zl_chunk）**

```java
// ChunkRepository.java
package org.liar.zhiliao.retrieval.repository;

@Repository
@RequiredArgsConstructor
public class ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    /** BM25 全文搜索，按 ts_rank 降序取 topK */
    public List<SparseSearchResult> searchBm25(String queryText, int topK) {
        String sql = """
            SELECT id, content, ts_rank(to_tsvector('zh', content), plainto_tsquery('zh', ?)) AS score
            FROM zl_chunk
            WHERE chunk_type = 'child'
              AND to_tsvector('zh', content) @@ plainto_tsquery('zh', ?)
            ORDER BY score DESC
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, 
            new BeanPropertyRowMapper<>(SparseSearchResult.class),
            queryText, queryText, topK);
    }

    /** 根据 ID 查询 chunk 内容（用于 parent 内容替换） */
    public String findContentById(Long id) {
        String sql = "SELECT content FROM zl_chunk WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, String.class, id);
    }
}
```

- [ ] **Step 3: 创建 SparseSearchResult 和 SparseSearcher 接口**

```java
// SparseSearchResult.java（与 ChunkRepository 同级 package，或放在 model 包）
package org.liar.zhiliao.retrieval.service;

public record SparseSearchResult(Long id, String content, Double score) {}

// SparseSearcher.java
package org.liar.zhiliao.retrieval.service;

/**
 * 稀疏检索接口。
 * 当前：PgBm25Searcher — PG tsvector BM25，零额外依赖
 * 未来：EsBm25Searcher — Elasticsearch BM25，部署 ES 后切换
 */
public interface SparseSearcher {
    List<SparseSearchResult> search(String query, int topK);
}
```

- [ ] **Step 4: 创建 PgBm25Searcher 实现**

```java
// PgBm25Searcher.java
package org.liar.zhiliao.retrieval.service;

@Component
public class PgBm25Searcher implements SparseSearcher {

    private final ChunkRepository chunkRepository;

    @Override
    public List<SparseSearchResult> search(String query, int topK) {
        return chunkRepository.searchBm25(query, topK);
    }
}
```

---

### Task 6: Reranker 接口 + RrfReranker 实现

**Files:**
- Create: `zhiliao-retrieval/.../service/Reranker.java`
- Create: `zhiliao-retrieval/.../service/RrfReranker.java`

- [ ] **Step 1: 创建 Reranker 接口**

```java
// Reranker.java
package org.liar.zhiliao.retrieval.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import java.util.List;

/**
 * 精排接口。
 * 当前：RrfReranker — RRF 融合，零依赖
 * 未来：BgeReranker — BGE-Reranker 本地模型 / Cohere Rerank API
 */
public interface Reranker {

    /**
     * @param query        原始用户问题
     * @param denseResults  Milvus 稠密检索结果
     * @param sparseResults PG BM25 稀疏检索结果
     * @param topK         返回 Top-K 个结果
     * @return 融合排序后的结果列表（含 chunkId、content、parentId、score）
     */
    List<RankedChunk> rerank(
        String query,
        List<EmbeddingMatch<TextSegment>> denseResults,
        List<SparseSearchResult> sparseResults,
        int topK
    );
}

// RankedChunk.java（同一 package）
public record RankedChunk(
    Long chunkId,
    String content,
    Long parentId,
    double rrfScore
) {}
```

- [ ] **Step 2: 实现 RrfReranker**

```java
// RrfReranker.java
package org.liar.zhiliao.retrieval.service;

@Slf4j
@Component
@ConditionalOnMissingBean(Reranker.class)
public class RrfReranker implements Reranker {

    private static final int K = 60;  // RRF 常数

    @Override
    public List<RankedChunk> rerank(
            String query,
            List<EmbeddingMatch<TextSegment>> denseResults,
            List<SparseSearchResult> sparseResults,
            int topK) {

        // 收集所有候选的 RRF 得分
        // key=chunkId, value={score, content, parentId}
        Map<Long, RrfEntry> scoreMap = new HashMap<>();

        // 处理稠密结果：从 TextSegment.metadata 取 chunkId
        for (int rank = 0; rank < denseResults.size(); rank++) {
            EmbeddingMatch<TextSegment> match = denseResults.get(rank);
            Metadata meta = match.embedded().metadata();
            Long chunkId = Long.parseLong(meta.get("chunkId"));
            String parentIdStr = meta.get("parentId");

            double score = 1.0 / (K + rank);
            RrfEntry entry = scoreMap.getOrDefault(chunkId,
                    new RrfEntry(0, match.embedded().text(),
                            parentIdStr.isEmpty() ? null : Long.parseLong(parentIdStr)));
            entry.score += score;
            scoreMap.put(chunkId, entry);
        }

        // 处理稀疏结果：SparseSearchResult 本身带 id
        for (int rank = 0; rank < sparseResults.size(); rank++) {
            SparseSearchResult result = sparseResults.get(rank);
            double score = 1.0 / (K + rank);
            RrfEntry entry = scoreMap.getOrDefault(result.id(),
                    new RrfEntry(0, result.content(), null));
            entry.score += score;
            scoreMap.put(result.id(), entry);
        }

        // 按 RRF 分数降序取 topK
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, RrfEntry>comparingByValue(
                        Comparator.comparingDouble(e -> e.score)).reversed())
                .limit(topK)
                .map(e -> new RankedChunk(e.getKey(), e.getValue().content,
                        e.getValue().parentId, e.getValue().score))
                .toList();
    }

    /** 内部辅助类 */
    @Data
    @AllArgsConstructor
    private static class RrfEntry {
        double score;
        String content;
        Long parentId;
    }
}
```

**关键细节**：Milvus 写入时需要在 `TextSegment.metadata` 中携带 `chunkId`（PG 主键）和 `parentId`，这样在检索时才能通过 `match.embedded().metadata()` 获取到 chunkId 和 parentId。这意味着在 Task 4 中写入 Milvus 的 `TextSegment` 需要附加这两项 metadata。

---

### Task 7: KnowledgeRetrievalTool 全面重构

**Files:**
- Modify: `zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java`

- [ ] **Step 1: 注入新依赖 + 实现查询改写**

```java
// KnowledgeRetrievalTool.java
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalTool {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> milvusEmbeddingStore;
    private final SparseSearcher sparseSearcher;         // 新增
    private final Reranker reranker;                     // 新增
    private final ChunkRepository chunkRepository;       // 新增
    private final ChatLanguageModel chatModel;           // 新增：查询改写用

    @Tool("检索企业知识库：查找公司制度、政策、流程、产品信息等企业内部知识。仅当用户明确询问企业内部知识时调用，日常闲聊无需调用")
    public String retrieveKnowledge(@P("查询内容") String query) {
        // Step 1: 查询改写
        List<String> subQueries = rewriteQuery(query);
        if (subQueries.isEmpty()) {
            subQueries = List.of(query);  // 改写失败时回退到原查询
        }

        // Step 2: 对每个子查询执行双路检索
        List<EmbeddingMatch<TextSegment>> allDenseResults = new ArrayList<>();
        List<SparseSearchResult> allSparseResults = new ArrayList<>();

        for (String subQuery : subQueries) {
            // 2a: Milvus 稠密检索
            Embedding queryEmbedding = embeddingModel.embed(subQuery).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .minScore(0.5)
                    .build();
            allDenseResults.addAll(milvusEmbeddingStore.search(request).matches());

            // 2b: PG BM25 稀疏检索
            allSparseResults.addAll(sparseSearcher.search(subQuery, 10));
        }

        // Step 3: RRF 融合排序
        List<RankedChunk> ranked = reranker.rerank(query, allDenseResults, allSparseResults, 10);

        // Step 4: 父子文档替换（child → parent content）
        StringBuilder context = new StringBuilder();
        for (RankedChunk chunk : ranked) {
            String content;
            if (chunk.parentId() != null) {
                // 用 parent content 替代 child content
                content = chunkRepository.findContentById(chunk.parentId());
            } else {
                content = chunk.content();
            }
            if (!context.isEmpty()) {
                context.append("\n---\n");
            }
            context.append(content);
        }

        return context.toString();
    }

    /** 调用 LLM 改写查询，支持多维度子查询 */
    private List<String> rewriteQuery(String query) {
        String prompt = """
            你是一个查询优化助手。将用户的问题改写成更适合检索的关键词形式。
            如果问题包含多个方面，用换行分隔多个查询。
            不要添加与问题无关的关键词。不要回答用户的问题，只输出改写后的关键词。
            
            用户问题：%s
            改写后：
            """.formatted(query);
        
        try {
            String result = chatModel.generate(prompt);
            return Arrays.stream(result.split("\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            log.warn("Query rewriting failed, using original query: {}", e.getMessage());
            return List.of();
        }
    }
}
```

- [ ] **Step 2: 确保检索时 metadata 解析一致**

RrfReranker 从 `match.embedded().metadata().get("chunkId")` 和 `get("parentId")` 取值，类型为 String。Task 4 中写入 Milvus 时已经携带了这两项 metadata，这里不需要额外改动。

---

### Task 8: RetrievalConfig 条件装配

**Files:**
- Create: `zhiliao-retrieval/.../config/RetrievalConfig.java`

- [ ] **Step 1: 创建 RetrievalConfig**

```java
// RetrievalConfig.java
package org.liar.zhiliao.retrieval.config;

@Configuration
public class RetrievalConfig {

    /**
     * 稀疏检索实现。
     * zhiliao.retrieval.sparse=pg（默认）— PG tsvector BM25
     * zhiliao.retrieval.sparse=es         — Elasticsearch BM25（未来）
     */
    @Bean
    @ConditionalOnProperty(value = "zhiliao.retrieval.sparse", havingValue = "pg", matchIfMissing = true)
    public SparseSearcher pgBm25Searcher(ChunkRepository chunkRepository) {
        return new PgBm25Searcher(chunkRepository);
    }

    /**
     * 精排实现。
     * zhiliao.retrieval.reranker=rrf（默认）— RRF 融合
     * zhiliao.retrieval.reranker=bge         — BGE-Reranker（未来）
     */
    @Bean
    @ConditionalOnProperty(value = "zhiliao.retrieval.reranker", havingValue = "rrf", matchIfMissing = true)
    public Reranker rrfReranker() {
        return new RrfReranker();
    }
}
```

---

## 自审检查

| 设计要求 | 对应任务 | 覆盖情况 |
|----------|----------|----------|
| 查询改写 | Task 7 Step 1 | LLM 改写 + 多子查询 + 失败回退 |
| PG 全文搜索（BM25） | Task 1 + Task 5 | zhparser + GIN 索引 + PgBm25Searcher |
| RRF 融合 | Task 6 + Task 7 Step 1 | Reranker 接口 + RrfReranker 实现 |
| 父子文档分割 | Task 2 + Task 3 + Task 4 | ParentChildSplitResult + 双阶段写入 |
| 稀疏检索接口预留 | Task 5 Step 3 | SparseSearcher 接口 |
| 精排接口预留 | Task 6 Step 1 | Reranker 接口 |
| 条件装配切换 | Task 8 | @ConditionalOnProperty |
