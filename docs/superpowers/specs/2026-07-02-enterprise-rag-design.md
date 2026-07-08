# 企业级 RAG 知识库架构设计文档

> 版本：v1.0 | 日期：2026-07-02 | 作者：Pei

## 1. 项目背景与目标

### 1.1 场景定义

- **场景类型：** 企业内部知识库（A 类）
- **企业规模：** 3000 人
- **用户群体：** 内部员工，按部门划分
- **文档来源：**
  - 公司内网 Wiki 平台（Confluence / 自建 Wiki），格式包括 docx、ppt、pdf、xlsx、md
  - 服务器文件（NAS / 文件服务器，SMB / NFS / SFTP）
  - 后续平台上传（管理后台手动上传）
- **认证方式：** 对接公司 OA 系统 SSO（OAuth2 / OIDC）
- **多租户：** 按部门多租户，支持跨部门共享知识库

### 1.2 核心目标

构建一个基于 Spring Boot + LangChain4j 的企业级 RAG 知识库平台，实现多源文档的自动摄入、智能检索、带引用的答案生成，支撑 3000 名员工的日常知识查询需求。

---

## 2. 总体架构策略

### 2.1 架构演进路线

| 阶段 | 策略 | 说明 |
|------|------|------|
| **当前（MVP）** | 模块化单体 | Spring Boot 单应用，Maven 多模块拆分，架构预留微服务扩展点 |
| **未来演进** | 按需拆分微服务 | 检索服务独立（计算密集型）→ 文档处理独立（IO 密集型）→ 对话服务独立（高并发） |

### 2.2 选择模块化单体而非微服务的理由

- 3000 人内部系统，峰值并发约 50-150 QPS，单体完全可承载
- 微服务增加运维复杂度、调试难度、事务一致性成本
- 团队初期规模有限，微服务收益（独立部署/独立扩缩容）暂时用不上
- Maven 多模块在代码层面实现隔离，未来拆分成本低

### 2.3 RAG 策略选择：高级 RAG（方案 B）

| 方案 | 准确率 | 复杂度 | 适用场景 |
|------|--------|--------|----------|
| 基础 RAG | ~60-70% | 低 | 原型验证 |
| **高级 RAG（推荐）** | **~85-95%** | **中** | **企业知识库** |
| Agentic RAG | ~90-95% | 高 | 多步推理场景 |

**检索流程：**

```
用户提问 → 查询改写（LLM 扩展同义词/分解）→ 多路召回 → 粗排（混合检索）
→ Rerank 精排 → Top-K 上下文构建 → LLM 生成 → 引用溯源 → 答案 + 来源文档
```

---

## 3. 系统分层架构

### 3.1 六层架构

```
┌─────────────────────────────────────────────────────┐
│ 接入层：Web UI (React) / REST API / SSE 流式 / WebSocket │
├─────────────────────────────────────────────────────┤
│ 网关与安全层：Spring Security + OAuth2 SSO / RBAC /      │
│              多租户隔离 / Rate Limiter / 审计日志        │
├─────────────────────────────────────────────────────┤
│ 业务服务层：                                              │
│  文档管理服务 / 检索服务 / 对话服务 /                        │
│  知识库管理 / 用户与租户 / 运营监控                         │
├─────────────────────────────────────────────────────┤
│ AI 编排层 (LangChain4j)：                                │
│  @AiService / EmbeddingStore / ContentRetriever /       │
│  ChatMemory / @Tool                                     │
├─────────────────────────────────────────────────────┤
│ 数据存储层：                                              │
│  Milvus / Elasticsearch / PostgreSQL / Redis /          │
│  MinIO / RabbitMQ                                       │
└─────────────────────────────────────────────────────┘
```

### 3.2 Maven 模块拆分

```
liar-zhiliao/
├── zhiliao-common      # 公共模型、工具类、常量
├── zhiliao-ingestion   # 文档摄入（解析/分割/向量化）
├── zhiliao-retrieval   # 检索服务（混合检索/Rerank）
├── zhiliao-chat        # 对话服务（LLM 编排/流式输出）
├── zhiliao-auth        # 认证授权（SSO/RBAC/多租户）
├── zhiliao-admin       # 管理后台（知识库/用户/监控）
└── zhiliao-app         # 启动入口，组装所有模块
```

---

## 4. 技术栈总览

| 层次 | 技术                                       | 版本 | 说明 |
|------|------------------------------------------|------|------|
| **主体框架** | Spring Boot + WebFlux                    | 3.5.x | 流式响应、非阻塞 IO |
| **AI 编排** | LangChain4j                              | 1.17+ | @AiService / Tool / RAG 模块 |
| **LLM** | DeepSeek V4（主）/ 通义千问（备）                  | — | 多模型可切换，降级策略 |
| **Embedding** | BGE-M3（本地部署，推荐）/ Qwen Embedding API（MVP） | — | 1024 维，中英双语，稠密+稀疏 |
| **向量数据库** | Milvus Standalone                        | 2.4+ | HNSW 索引，支持混合检索 |
| **搜索引擎** | Elasticsearch                            | 8.x | BM25 关键词检索 + 全文搜索 |
| **业务数据库** | PostgreSQL                               | 16 | 文档/用户/权限/审计，pgvector 备选 |
| **会话记忆** | Redis Stack                              | 7.x | 低延迟，TTL 淘汰，JSON 存储 |
| **缓存** | Caffeine (L1) + Redis (L2)               | — | 两级缓存，节省 LLM 调用 |
| **文档存储** | MinIO                                    | latest | S3 兼容，自建对象存储 |
| **消息队列** | RabbitMQ                                 | 3.13+ | 文档异步处理、事件驱动 |
| **文档解析** | Apache Tika + PDFBox + Tesseract         | — | 1000+ 格式 + OCR，全部开源 |
| **认证** | Spring Security + OAuth2                 | 6.x | 对接 OA 系统 SSO |
| **限流熔断** | Resilience4j                             | 2.x | Rate Limiter + Circuit Breaker |
| **监控** | Prometheus + Grafana + Actuator          | — | 指标采集 + 可视化 + 告警 |

---

## 5. 存储层详细设计

### 5.1 Redis Stack — 会话记忆存储

**选型理由：**

- ChatMemory 每次对话频繁读写，延迟要求 < 1ms
- Redis RDB + AOF 双重持久化保证数据不丢
- 记忆窗口天然是 LRU 淘汰模式，Redis TTL 完美匹配
- Redis Stack 内置 JSON 存储，ChatMessage 序列化零成本

**为什么不用 MySQL？**

- 每次对话写表，磁盘 IO 高
- ChatMemory 是短期上下文窗口（20 条消息），不是持久业务数据
- 高频 UPDATE 导致行锁竞争

**LangChain4j 集成（极简改动）：**

### 5.2 Milvus Standalone — 向量存储

**选型理由（对比 Redis Stack 向量功能）：**

| 对比维度 | Redis Stack 向量 | Milvus Standalone |
|----------|-----------------|-------------------|
| 索引算法 | FLAT / HNSW | HNSW / IVF_FLAT / IVF_PQ / SCANN / DiskANN 等 11 种 |
| 向量规模 | < 100 万（受内存限制）| 10 亿+（内存+磁盘混合）|
| 混合检索 | 支持但有限 | 原生混合检索（稠密+稀疏+标量过滤）|
| 多租户分区 | 手动管理 key 前缀 | 原生 Partition Key |
| 内存成本 | 极高（全内存）| 低（热数据内存，冷数据磁盘）|

**配置要点：**

| 参数 | 值 | 说明 |
|------|-----|------|
| 部署模式 | Standalone | 单机足够 3000 人场景 |
| Embedding 维度 | 1024 | 取决于 BGE-M3 |
| 索引类型 | HNSW | 生产环境推荐 |
| 相似度度量 | Cosine Similarity | 语义相似度标准选择 |
| 分区策略 | Partition Key = knowledgeBaseId | 多租户隔离 |

**为什么不用 pgvector？**

pgvector 适合 < 100 万向量且不想引入额外组件的场景。企业级知识库文档可达百万级 Chunk，pgvector 的 IVF 索引在大规模下性能显著低于 Milvus。建议：pgvector 用于开发环境，生产用 Milvus。

### 5.3 Elasticsearch — 关键词检索引擎

与 Milvus 形成混合检索。BM25 算法对专业术语、编号、日期等精确匹配远优于向量检索。同时提供高亮片段和全文搜索能力。

### 5.4 PostgreSQL — 业务数据主库

**为什么选 PostgreSQL 而非 MySQL？**

| 特性 | PostgreSQL 16 | MySQL 8.0 |
|------|--------------|-----------|
| JSON 支持 | JSONB（二进制，支持索引）| JSON（文本存储）|
| 全文搜索 | 内置 tsvector + zhparser 中文分词 | ngram，中文效果差 |
| 向量扩展 | pgvector 原生支持 | 无 |
| 并发模型 | MVCC 读写互不阻塞 | MVCC + Undo Log |
| 许可协议 | 完全开源 | Oracle 所有，合规风险 |

**核心原因：pgvector。** 文档元数据和 Chunk 本身存在 PG，pgvector 可在同一表上做向量检索，开发环境甚至不需要单独部署 Milvus。

**核心表设计：**

- `zl_user` — 用户信息（关联 SSO）
- `zl_department` — 部门组织架构
- `zl_knowledge_base` — 知识库元数据
- `zl_document` — 文档信息（含 MinIO objectKey）
- `zl_chunk` — 文档切片（含向量 ID 映射）
- `zl_conversation` — 对话记录
- `zl_audit_log` — 审计日志

所有表带 `tenant_id` / `dept_id` 列实现多租户隔离。

### 5.5 MinIO — 原始文档对象存储

S3 兼容 API。用户上传的原始文档（PDF/Word/图片）先存 MinIO 再异步处理。保留原始文件供溯源和重新处理。

**使用流程：**

```
上传文档 → MinIOClient.putObject() → 返回 objectKey
→ 异步解析/向量化 → objectKey 存入 PG documents 表
→ 用户点击"查看原文" → getPresignedObjectUrl() → 返回临时下载链接
```

**Docker 启动：**

```bash
docker run -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=admin -e MINIO_ROOT_PASSWORD=admin123 \
  minio/minio server /data --console-address ":9001"
```

### 5.6 RabbitMQ — 异步任务队列

**三大用途：**

1. **文档异步处理** — 大文件上传后立即返回"处理中"，Worker 异步消费
2. **批量导入** — Wiki 同步时逐条投递队列，多 Worker 并行处理
3. **事件通知** — 索引完成/知识库更新等事件通知下游

**为什么 RabbitMQ 而不是 RocketMQ？**

- Spring AMQP 原生集成，配置量极低
- 3000 人内部知识库，日均文档不过百篇，RabbitMQ 吞吐完全够
- 运维简单，Docker 一键部署
- 如果公司已在用 RocketMQ，优先复用现有基础设施

---

## 6. 文档摄入层设计

### 6.1 多源接入架构

```
Wiki 平台 ──→ API 拉取 / Webhook ──┐
服务器文件 ──→ 目录监控 / 定时扫描 ──┼──→ 统一 ETL 管道
平台上传 ──→ REST API / Web UI ────┘
```

### 6.2 ETL 处理管道

```
多源接入 → 格式检测 → Apache Tika 解析 → 文档分割 → 元数据提取
→ 图片 OCR（Tesseract）→ Embedding 向量化 → 写入 Milvus + ES + MinIO
```

### 6.3 文档解析技术栈（全部开源，Apache 2.0 License）

| 技术 | 用途 | 覆盖场景 |
|------|------|----------|
| **Apache Tika** | 通用文档解析引擎 | 1000+ 格式统一入口 |
| **PDFBox** | PDF 深度处理 | 复杂排版降级处理 |
| **Tesseract** | OCR 光学识别 | 扫描件/图片文字提取 |

**策略模式设计：**

```java
public String parseDocument(DocumentFile doc) {
    // 1. Tika 通用解析（覆盖 80% 场景）
    String text = tika.parseToString(doc.getStream());

    // 2. 降级：文本过少说明是扫描件，走 OCR
    if (text == null || text.trim().length() < 50) {
        if (doc.getType() == FileType.PDF) {
            text = parseScannedPdfWithOCR(doc.getStream());
        }
    }
    return text;
}
```

### 6.4 文档分割策略

| 策略 | 原理 | 适用场景 |
|------|------|----------|
| 递归字符分割 | 按段落→句子→字符递归切分 | 大多数文档 |
| 语义分割 | 基于 Embedding 相似度断点 | 长文档/论文 |
| **父子文档（推荐）** | Child (512t) 检索，Parent (2048t) 上下文 | 兼顾精度与完整性 |

### 6.5 增量同步机制

- 文件指纹：MD5 哈希追踪文档变更
- 映射关系：doc_id → chunk_id 列表，更新时先删旧再写新
- 定时对账：全量对账任务兜底，确保最终一致
- Wiki 增量：Webhook 触发实时同步

---

## 7. 检索层设计（核心难点）

### 7.1 混合检索流程

```
用户问题
  → 查询改写（LLM 扩展同义词、分解复杂问题）
    → 稠密检索：Milvus 向量相似度（语义理解）
    → 稀疏检索：ES BM25 关键词匹配（精确匹配）
    → 知识图谱（NG 阶段）：实体关系多跳推理
  → RRF（倒数排名融合）合并为 Top-30
    → BGE-Reranker 精排取 Top-5
      → 构建上下文 → LLM 生成
```

### 7.2 关键技术点

| 技术点 | 实现方式 |
|--------|----------|
| 查询改写 | LLM 分解复杂问题 + 扩展同义词 |
| 稠密检索 | Milvus HNSW 索引 + Cosine 相似度 |
| 稀疏检索 | ES BM25 + 高亮片段 |
| 结果融合 | RRF（Reciprocal Rank Fusion）|
| 精排 | BGE-Reranker-v2-m3（本地部署）|

---

## 8. Embedding 模型选型

### 8.1 推荐：BGE-M3 本地部署

**为什么本地部署而不是调用第三方 API？**

- 数据不出内网（企业文档安全）
- 零调用成本
- 延迟 < 50ms（vs API 的 50-500ms）
- BGE-M3 特点：中英双语、1024 维、同时输出稠密+稀疏向量（天然适合混合检索）

### 8.2 第三方 API 备选

| 模型 | 维度 | 特点 |
|------|------|------|
| DeepSeek Embedding | 1024 | 性价比高，已有 API Key |
| 通义千问 text-embedding-v3 | 1024/768 | 中文效果最好 |
| OpenAI text-embedding-3-large | 3072 | 通用能力最强 |
| Cohere Embed v3 | 1024 | 多语言，企业 SLA |

**建议：** MVP 阶段先用 DeepSeek Embedding API 快速跑通，生产环境迁移到 BGE-M3 本地部署。LangChain4j 切换只需改 Bean 配置。

---

## 9. 会话记忆设计

### 9.1 原理

- LangChain4j ChatMemory 维护对话上下文（20 条消息窗口）
- 每个 `memoryId` 对应一个独立会话
- ChatMemoryProvider 根据 memoryId 创建/获取对应 ChatMemory

### 9.2 存储方案

| 方案 | 延迟 | 持久化 | 适用场景 |
|------|------|--------|----------|
| 本地内存（当前）| < 0.01ms | 无 | 开发测试 |
| 本地文件（当前 CustomChatMemoryStore）| ~1ms | 有 | 单机 MVP |
| **Redis Stack（推荐）** | **< 1ms** | **RDB+AOF** | **生产环境** |
| MySQL | ~5ms | 强 | 不推荐（过度设计）|

---

## 10. 多级缓存设计

### 10.1 架构

```
请求 → Caffeine (L1, JVM 内存, 0.01ms)
         → 未命中 → Redis (L2, 分布式, 1ms)
                      → 未命中 → 实际调用 LLM/DB (1000ms+)
```

### 10.2 缓存场景

| 场景 | TTL | 容量 | 预期命中率 |
|------|-----|------|-----------|
| 热点问题答案 | 1h | 10000 | L1: 30%, L2: 60% |
| Embedding 向量 | 24h | 100000 | 80%+（相同文本不重复计算）|
| 用户权限信息 | 5min | 3000 | 95%+ |

### 10.3 实现方式

Spring Cache + Caffeine + Redis，通过 `@Cacheable` 注解声明式缓存，Micrometer 暴露命中率指标。

---

## 11. 安全与多租户设计

### 11.1 认证流程

```
用户 → OA 登录页 → 回调 /login/oauth2/code/oa
→ Spring Security OAuth2 Client 获取 Token
→ 查询用户信息 + 部门归属 → 生成 JWT → 后续请求携带 JWT
```

### 11.2 多租户数据隔离

| 存储 | 隔离方式 |
|------|----------|
| PostgreSQL | 所有表带 tenant_id / dept_id，应用层过滤 |
| Milvus | Partition Key = knowledgeBaseId |
| Elasticsearch | 基于 index 别名路由 |
| Redis | key 前缀含 deptId |

### 11.3 检索时注入租户上下文

```java
// 从 JWT 提取当前用户可访问的知识库列表
// 注入 ThreadLocal 租户上下文
// 所有 Milvus/ES 查询自动拼装 tenant_id 过滤条件
```

### 11.4 防幻觉与安全策略

- System Prompt 约束："只根据提供的文档回答，不知道就说不知道"
- 答案置信度评分：检索相关性低于阈值时拒绝回答
- 输入过滤：敏感词检测 + Prompt 注入防护
- 内容安全审核：答案二次校验

---

## 12. 监控与可观测性

### 12.1 监控指标

| 类别 | 指标 | 采集方式 |
|------|------|----------|
| LLM 调用 | 次数、延迟 P50/P95/P99、成功率、Token 消耗 | Micrometer @Timed |
| 检索性能 | 召回率、延迟分布、Top-K 命中率 | 自定义 Counter + Timer |
| 应用 | QPS、响应时间、错误率 | Actuator 自动暴露 |
| JVM | 堆内存、GC 频率、线程数 | Actuator 自动暴露 |
| 缓存 | 命中率、穿透次数 | CacheMeterBinder |
| 基础设施 | Redis/Milvus/ES 健康状态 | HealthIndicator |
| 文档处理 | 队列积压、处理成功率、处理耗时 | RabbitMQ Metrics |

### 12.2 技术实现

- **Actuator + Micrometer：** 暴露 `/actuator/prometheus` 端点，自动采集 JVM/HTTP/DB 指标
- **Prometheus：** 定期抓取指标，存储时序数据
- **Grafana：** 导入 Spring Boot 仪表盘模板（ID: 4701），自定义 RAG 面板
- **告警：** 错误率 > 5% / P99 > 5s / 内存 > 85% → 钉钉/邮件通知

---

## 13. 并发评估与设计

### 13.1 负载评估

| 指标 | 估算值 | 说明 |
|------|--------|------|
| 日活用户 (DAU) | 300-600 | 10-20% 员工使用 |
| 峰值 QPS | 50-150 | 工作日上午 9-11 点 |
| 单次对话延迟 | 1-5 秒 | 主要瓶颈在 LLM API |
| 文档日处理量 | 50-200 篇 | 异步处理，不限流 |

### 13.2 并发策略

- **WebFlux 全链路异步：** Controller → Service → LLM API，不阻塞线程池
- **连接池：** Milvus/ES/Redis/LLM API 全用连接池，避免频繁建连
- **多级缓存：** 减少重复 LLM 调用（缓存命中可省 70% LLM 费用）
- **限流降级：** Resilience4j 按用户/部门限流，LLM 超时降级为"请稍后重试"

### 13.3 结论

3000 人内部知识库的并发远低于 C 端产品。一个 Spring Boot 单体 + 连接池 + Redis 缓存完全够用。**真正的瓶颈在 LLM API 调用延迟和文档解析吞吐**，通过异步化解决。

---

## 14. 核心技术难点汇总

| 难点 | 问题描述 | 应对策略 |
|------|----------|----------|
| 文档解析精度 | 复杂 PDF 排版丢失、扫描件无法提取 | Tika → PDFBox → Tesseract 三级降级 |
| 文档分割策略 | 切太碎丢上下文，切太大检索不精 | 父子文档模式（512/2048 tokens）|
| 混合检索+Rerank | 单一检索方式精度不足 | 稠密+稀疏双路 → RRF 融合 → BGE Reranker 精排 |
| 引用溯源 | LLM 答案无法追溯来源 | Chunk 元数据 → System Prompt 注入引用要求 |
| Embedding 选型 | 中英文混合、延迟、成本 | BGE-M3 本地部署（生产），API（MVP）|
| 多租户隔离 | 部门间数据隔离+跨部门共享 | 所有存储层注入 tenant_id，ThreadLocal 透传 |
| LLM 幻觉 | 知识库外的问题编造答案 | Prompt 约束 + 置信度阈值 + 输入/输出过滤 |
| 知识库更新 | 文档变更后索引同步 | MD5 指纹 + doc→zlChunk 映射 + 定时对账 |
| 大文档处理 | 50MB+ PDF 阻塞 HTTP 请求 | RabbitMQ 异步 + Worker 水平扩展 |

---

## 15. 附录

### A. LangChain4j 关键模块对照

| LangChain4j 模块 | 对应功能 |
|------------------|----------|
| `@AiService` | AI 服务接口，自动生成实现 |
| `EmbeddingStore` | 向量数据库抽象（Milvus 适配）|
| `ContentRetriever` | 检索器接口（支持混合检索）|
| `ChatMemory` + `ChatMemoryStore` | 会话记忆（Redis 接入）|
| `@Tool` | 函数调用/工具调用 |
| `DocumentSplitter` | 文档分割 |
| `EmbeddingModel` | Embedding 模型抽象 |

### B. 基础设施 Docker Compose 速览

```yaml
services:
  postgres:    image: postgres:16
  redis:       image: redis/redis-stack:latest
  milvus:      image: milvusdb/milvus:latest
  elasticsearch: image: elasticsearch:8.x
  minio:       image: minio/minio:latest
  rabbitmq:    image: rabbitmq:3.13-management
  prometheus:  image: prom/prometheus:latest
  grafana:     image: grafana/grafana:latest
```

### C. 参考资源

- LangChain4j 官方文档: https://docs.langchain4j.dev
- Milvus 文档: https://milvus.io/docs
- BGE-M3 模型: https://huggingface.co/BAAI/bge-m3
- Spring AI (备选方案): https://spring.io/projects/spring-ai
