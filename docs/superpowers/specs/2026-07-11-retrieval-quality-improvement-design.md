# 检索质量提升设计文档

> 版本：v1.0 | 日期：2026-07-11 | 作者：Pei
> 基于：企业级 RAG 知识库架构设计文档 (v1.0)

## 1. 概述

本文档是"企业级 RAG 知识库"第一阶段**检索质量提升**的设计方案。在现有 MVP 检索管线基础上，引入查询改写、双路混合检索（稠密+稀疏）、RRF 融合排序和父子文档分割四项改进，显著提升知识库问答的召回率和答案完整性。

## 2. 当前管线

```
用户问题 → LLM 判断意图 → KnowledgeRetrievalTool
  → Embedding → Milvus 检索 → Top-5 → LLM 回答
```

问题：单路稠密检索对关键词精确匹配（编号、型号、日期等）效果差；chunk 过小导致 LLM 上下文不完整。

## 3. 目标管线

```
用户问题
  → 查询改写（LLM 扩展/优化查询，支持多维度子查询）
    → 稠密检索：Embedding → Milvus（语义匹配）
    → 稀疏检索：PG tsvector BM25（关键词精确匹配）
  → RRF 融合 → Top-10 → 父子文档替换（Child → Parent）
  → LLM 生成回答（附来源文档）
```

## 4. 各模块设计

### 4.1 查询改写

在检索前用 LLM 将用户问题改写为更适合检索的关键词形式，并支持拆分为多个子查询以提升召回率。

**位置**：`KnowledgeRetrievalTool.retrieveKnowledge()` 入口处

**实现思路**：

```java
// 调用 LLM 改写查询（复用 openAiChatModel，非流式）
String rewrittenQuery = openAiChatModel.generate("""
    你是一个查询优化助手。将用户的问题改写成更适合检索的关键词形式。
    如果问题包含多个方面，用换行分隔多个查询。
    不要添加与问题无关的关键词。
    
    用户问题：{query}
    改写后：
    """);
// 按换行拆分为多个子查询
List<String> subQueries = List.of(rewrittenQuery.split("\\n"));
```

**要点**：
- 复用现有的 `openAiChatModel` Bean（非流式），不引入新依赖
- 短 prompt 控制 token 消耗
- 改写结果按 `\n` 拆分为多个子查询，每个子查询独立走双路检索
- **后续升级**：可替换为专用查询改写模型或同义词扩展

### 4.2 PG 全文搜索（BM25 稀疏检索）

在 `zl_chunk.content` 字段上建立 GIN 倒排索引，利用 PostgreSQL 内置的 `tsvector` + `ts_rank` 实现 BM25 关键词检索，与 Milvus 形成混合检索。

**数据库变更**：

```sql
-- 安装中文分词插件
CREATE EXTENSION IF NOT EXISTS zhparser;

-- 创建中文分词配置
CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser);

-- GIN 倒排索引
CREATE INDEX idx_chunk_content_fts ON zl_chunk
  USING GIN (to_tsvector('zh', content));
```

**接口抽象**（为第三阶段升级为 ES 预留）：

```java
/**
 * 稀疏检索接口。
 * MVP：PgBm25Searcher — PG tsvector 实现，零额外依赖
 * Phase 3：EsBm25Searcher — Elasticsearch BM25 实现，部署 ES 后切换
 */
public interface SparseSearcher {
    List<SparseSearchResult> search(String query, int topK);
}

public record SparseSearchResult(Long chunkId, String content, Double score) {}
```

**配置切换**（通过 `@ConditionalOnProperty` 控制实现）：

```java
@Bean
@ConditionalOnProperty(value = "zhiliao.retrieval.sparse", havingValue = "pg", matchIfMissing = true)
public SparseSearcher pgSearcher() { return new PgBm25Searcher(); }

@Bean
@ConditionalOnProperty(value = "zhiliao.retrieval.sparse", havingValue = "es")
public SparseSearcher esSearcher() { return new EsBm25Searcher(); }
```

**性能评估**：10 万级 chunk，GIN 索引下单次全文搜索延迟 < 20ms。

### 4.3 RRF 融合

双路检索结果通过 RRF（Reciprocal Rank Fusion）算法合并排序，替代简单的分数加权（因不同检索系统的分数尺度不可比）。

**位置**：`KnowledgeRetrievalTool` 中新增 `RrfFusion` 组件

**核心逻辑**：

```java
// RRF 公式：score(d) = sum(1/(k + rank_i(d)))，k=60
// 所有子查询的所有检索结果统一参与排名计算
List<ChunkResult> top10 = fuse(
    milvusResults,  // Milvus 稠密结果
    bm25Results     // PG BM25 稀疏结果
);
```

**后续升级路径**（文档中说明）：

| 阶段 | 方案 | 条件 |
|------|------|------|
| 当前 | RRF 融合（默认） | 零依赖 |
| Phase 3+ | BGE-Reranker 模型本地部署 | 有 GPU/CPU 资源 |
| Phase 3+ | Cohere Rerank API 等付费服务 | 有预算 |

**接口预留**：

```java
public interface Reranker {
    List<ChunkResult> rerank(String query, List<ChunkResult> candidates, int topK);
}

@Component
@ConditionalOnMissingBean(Reranker.class)
public class RrfReranker implements Reranker {
    // RRF 公式实现
}
```

### 4.4 父子文档分割

解决当前 chunk 大小（500 token）检索精度和上下文完整性的矛盾：Child 用于检索，Parent 作为 LLM 上下文。

**数据库变更**：

```sql
-- zl_chunk 表新增字段（无外键约束，纯逻辑关联）
ALTER TABLE zl_chunk ADD COLUMN parent_id BIGINT;
ALTER TABLE zl_chunk ADD COLUMN chunk_type VARCHAR(10) NOT NULL DEFAULT 'child';
```

**写入流程**（修改 `DocumentConsumerProcessor`）：

```
Tika 解析后的长文本
  ↓
分割为 Parent chunks（2048 token，overlap 200）
  ↓ 写入 PG（chunk_type='parent', parent_id=null）
每个 Parent 再细分为 Child chunks（512 token，overlap 50）
  ↓ 写入 PG（chunk_type='child', parent_id=关联的 Parent id）
只对 Child 做 Embedding → 写入 Milvus
```

**检索流程**：

```
双路检索 → RRF 融合 → 匹配到 Child chunks
  ↓ 通过 parent_id 查出对应的 Parent content
  ↓ 将 Parent content（2048 token 完整上下文）输入给 LLM
```

**接口抽象**：

```java
public interface DocumentSplitter {
    SplitResult split(String text, String documentId);
}

public record SplitResult(
    List<Chunk> parentChunks,  // 写 PG（含完整 content）
    List<Chunk> childChunks    // 写 PG + 写 Milvus（含 embedding_id）
) {}
```

## 5. 涉及的文件与改动汇总

| 文件 | 模块 | 改动类型 | 说明 |
|------|------|----------|------|
| `KnowledgeRetrievalTool.java` | zhiliao-retrieval | 修改 | 增加查询改写 + 双路检索 + RRF 融合 |
| `SparseSearcher.java` | zhiliao-retrieval | 新建 | 稀疏检索接口 |
| `PgBm25Searcher.java` | zhiliao-retrieval | 新建 | PG tsvector BM25 实现 |
| `Reranker.java` | zhiliao-retrieval | 新建 | 精排接口 |
| `RrfReranker.java` | zhiliao-retrieval | 新建 | RRF 融合默认实现 |
| `RetrievalConfig.java` | zhiliao-retrieval | 新增 | 条件装配稀疏检索和精排 Bean |
| `DocumentSplitter.java` | zhiliao-ingestion | 修改 | 返回 SplitResult（含父子文档） |
| `RecursiveDocumentSplitterImpl.java` | zhiliao-ingestion | 修改 | 实现父子分割逻辑 |
| `DocumentConsumerProcessor.java` | zhiliao-ingestion | 修改 | 写入时支持两阶段写 PG + Milvus |
| `ZlChunk.java` | zhiliao-ingestion | 修改 | 新增 parentId、chunkType 字段 |
| `schema.sql` | zhiliao-app | 修改 | 新增全文搜索索引和 DDL |
| `application.yaml` | zhiliao-app | 可选 | 新增 `zhiliao.retrieval.sparse` 配置 |

## 6. 不影响已有功能

- ChatController、ChatService、system-prompt.md **不修改**
- JWT 鉴权 **不修改**
- Docker Compose **不修改**（不引入新服务）
- 前端测试客户端 **不修改**

## 7. 后续升级路径（本文档的扩展点）

| 改进方向 | 当前方案 | 升级方案 | 触发条件 |
|----------|----------|----------|----------|
| 稀疏检索 | PG tsvector BM25 | Elasticsearch BM25 | 检索精度或性能不够时 |
| 精排 | RRF 融合 | BGE-Reranker 本地部署 / Cohere Rerank API | 需要更高精度时 |
| 查询改写 | LLM 改写 | 专用改写模型 / 同义词扩展 | 需要降低 LLM 调用成本时 |
| 分割策略 | 父子文档递归分割 | 语义分割 / Agentic 分割 | 需要更智能的分断时 |
| Embedding | 通义千问 API | BGE-M3 本地部署 | 数据安全和成本优化 |
