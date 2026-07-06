# 知了知了 MVP 开发计划

> 版本：v1.0 | 日期：2026-07-06 | 作者：Pei
> 基于：企业级 RAG 知识库架构设计文档 (v1.0)

## 1. 整体策略

### 1.1 核心原则

- **接口 + 条件装配**：每个模块定义接口，MVP 提供简单实现，未来通过 `@ConditionalOnProperty` 或 `@Profile` 切换为生产实现，调用方不改代码
- **字段先行**：数据库表字段一次设计到位（含多租户 tenant_id），MVP 阶段赋默认值，不做逻辑过滤
- **不做冗余预留**：只通过抽象接口和字段预留扩展点，不做"未来可能用到的实现代码"

### 1.2 MVP 范围

| 阶段 | 模块 | 状态 |
|------|------|------|
| Phase 1 | 基础设施（Docker Compose + 数据库 DDL） | 新建 |
| Phase 2 | 文档摄入 + 向量化（Tika 解析 + Embedding + 双写 PG/Milvus） | 从占位符实现 |
| Phase 3 | 检索层（Milvus 查询 + ContentRetriever 注入 ChatService） | 从 InMemory 升级 |
| Phase 4 | 对话增强（Redis 记忆 + 引用溯源 + 置信度拒答） | 在现有雏形上增强 |

### 1.3 延后范围

| 阶段 | 内容 | 延后原因 |
|------|------|----------|
| Phase 5 | SSO/RBAC/多租户逻辑 | MVP 无用户概念，tenant_id 字段预留 |
| Phase 6 | 管理后台 REST API | MVP 通过 curl/ApiFox 直接管理 |
| Phase 7 | Prometheus/Grafana/缓存 | MVP 验证核心流程后再加 |

## 2. Phase 1：基础设施（预计 1 周）

### 2.1 Docker Compose

`docker/local-dev.yml`，包含以下服务：

| 服务 | 用途 | MVP 状态 | 说明 |
|------|------|----------|------|
| PostgreSQL 16 | 业务数据主库 | 启用 | 文档/用户/chunk 等 |
| Redis Stack | 会话记忆 | 启用 | 替代本地文件存储 |
| MinIO | 文档对象存储 | 启用 | S3 兼容 API |
| RabbitMQ | 异步任务队列 | 启用 | 文档异步处理 |
| Milvus Standalone | 向量数据库 | 启用 | 替代 InMemoryEmbeddingStore |
| Elasticsearch | 关键词检索 | 注释掉 | 未来混合检索时启用 |

所有 Compose 配置写在一个文件里，Milvus/ES 等暂不启用的服务注释掉，未来取消注释即可。

### 2.2 数据库 DDL

核心表（全部建表，字段一次到位）：

| 表 | 关键字段 | MVP 使用情况 |
|----|----------|-------------|
| `users` | id, username, password_hash, dept_id, role, created_at | 写入测试用户 |
| `departments` | id, name, parent_id | 写入默认部门 |
| `knowledge_bases` | id, name, description, tenant_id, created_at | 使用 |
| `documents` | id, kb_id, file_name, file_type, status, minio_key, file_size, md5, chunk_count, created_at | 使用 |
| `chunks` | id, doc_id, content, embedding_id, metadata, created_at | 使用（PG + Milvus 双写）|
| `conversations` | id, memory_id, user_id, title, message_count, created_at | 使用 |
| `audit_logs` | id, user_id, action, target_type, target_id, detail, created_at | 写入 |

- 所有表带 `tenant_id` / `dept_id` 列，MVP 阶段填入 `"default"` / `1`
- 使用 Flyway 或手动 SQL 管理 DDL 版本

### 2.3 PG + Milvus 双写设计

**为什么双写？**

| 角色 | 用途 | 说明 |
|------|------|------|
| **PG chunks 表** | 数据主库、管理操作、恢复兜底 | 权威来源，未来管理后台读此表展示 chunk 列表 |
| **Milvus** | 向量检索引擎 | 纯搜索用途，数据可视为 PG 的快照副本 |

**数据映射关系：**

```
PG chunks 表：    id | doc_id | content | metadata | embedding_id | created_at
                      ↑            ↑          ↑           ↓
Milvus：              doc_id   content   metadata   vector_id
```

- `chunks.embedding_id` → 关联 Milvus 中该 chunk 对应的向量 ID
- 检索时：只查 Milvus，拿到 content + doc_id + metadata，不查 PG
- 管理时：查 PG chunks 表，展示/搜索 chunk 列表
- 恢复时：PG 数据完整，可批量重新写入 Milvus

**写入流程：**

```
Tika 解析 → 分割 chunks → Embedding → 写入 Milvus（返回 vector_id）
                                          → 写入 PG chunks（含 vector_id）
```

两条写入在同一个事务性操作中完成（非强事务，以 PG 为准，Milvus 写入失败时文档标记 FAILED 可重试）。

### 2.4 输出物

- `docker/local-dev.yml`
- `zhiliao-app/src/main/resources/sql/schema.sql`（DDL）
- `zhiliao-app/src/main/resources/sql/data.sql`（测试种子数据）

## 3. Phase 2：文档摄入 + 向量化（预计 4 周）

**此阶段覆盖全部写入链路：Tika 解析 → 分割 → Embedding → 双写 Milvus + PG。**

### 3.1 架构

```
POST /api/documents/upload
  → 接收 MultipartFile
  → 保存原始文件到 MinIO（minio_key = docs/{kb_id}/{uuid}/{filename}）
  → documents 表写入记录（status = PROCESSING）
  → 投递 RabbitMQ（message = { documentId, minio_key, fileName }）
  → 返回 { documentId, status: PROCESSING }

RabbitMQ Consumer（异步）
  → Apache Tika 解析文档 → 提取文本
  → 递归字符分割器（RecursiveDocumentSplitter）→ chunks
  → DeepSeek Embedding API → 每个 chunk 向量化
  → 写入 Milvus（Collection = zhiliao_chunks, 返回 vector_id）
  → 写入 PG chunks 表（含 doc_id, content, metadata, embedding_id=vector_id）
  → documents 表 status → COMPLETED / FAILED
```

### 3.2 文件结构（zhiliao-ingestion + zhiliao-retrieval 写入部分）

Phase 2 涉及两个模块：ingestion（编排层）和 retrieval（写入层）。

```
zhiliao-ingestion/
├── config/
│   └── IngestionConfig.java        # Bean 装配
├── service/
│   ├── DocumentProcessor.java      # 接口：process(documentId)
│   ├── DocumentParser.java         # 接口：parse(file) → String
│   ├── DocumentSplitter.java       # 接口：split(text) → List<Chunk>
│   └── async/
│       ├── DocumentConsumer.java   # RabbitMQ 监听
│       └── AsyncDocumentProcessor.java  # 实现 DocumentProcessor（编排完整流程）
├── controller/
│   └── DocumentController.java     # POST /api/documents/upload, GET /api/documents/{id}
└── repository/
    └── DocumentRepository.java     # PostgreSQL documents 表操作

zhiliao-retrieval/（写入相关）
├── service/
│   ├── EmbeddingService.java       # 接口：embed(text) → float[]
│   ├── VectorStore.java            # 接口：store(chunks, vectors) → List<VectorId>
│   └── impl/
│       ├── DeepSeekEmbeddingService.java  # API 调用实现
│       └── MilvusVectorStore.java  # Milvus 写入实现
└── repository/
    └── ChunkRepository.java        # PostgreSQL chunks 表操作（写入）
```

### 3.3 预留扩展点

```java
// DocumentParser 接口
// MVP：TikaDocumentParser（Apache Tika 通用解析）
// 未来：OcrDocumentParser（Tesseract OCR，解析图片/扫描件）
//       PdfBoxDocumentParser（复杂 PDF 降级处理）
// 通过 DocumentParser 链（Composite/Chain of Responsibility）组合

// DocumentSplitter 接口
// MVP：RecursiveDocumentSplitter（LangChain4j 内置，按段落→句子递归切分）
// 未来：SemanticDocumentSplitter（基于 Embedding 相似度断点）
//       ParentChildDocumentSplitter（Parent 2048t + Child 512t 父子文档模式）
```

### 3.4 状态机

```
UPLOADED → PROCESSING → COMPLETED
                         → FAILED（含 error_message）
```

### 3.5 RabbitMQ 配置

| 配置项 | 值 |
|--------|-----|
| Exchange | `zhiliao.direct` |
| Queue | `zhiliao.document.process` |
| Routing Key | `document.process` |
| Consumer 并发 | 1-3（根据文档大小动态调整）|

### 3.6 长期演进

未来替换 `AsyncDocumentProcessor` 为增强版时，只需加 `@ConditionalOnProperty(name = "zhiliao.ingestion.async", havingValue = "true")`，不改 Controller 层代码。

## 4. Phase 3：检索层 — 查询（预计 1-2 周）

**此阶段只做 Milvus 查询 + 注入 ChatService，写入已在 Phase 2 完成。**

### 4.1 架构

```
用户问题 → EmbeddingService.embed(question) → 向量
  → Milvus 搜索（Collection = zhiliao_chunks, HNSW, Cosine, Top-5）
  → 返回 List<Chunk>（含 documentId + 原文 + 元数据）
  → ContentRetriever 包装（兼容 LangChain4j 接口）
  → 注入 ChatService
```

### 4.2 文件结构（zhiliao-retrieval 查询相关）

```
zhiliao-retrieval/（查询相关）
├── config/
│   └── RetrievalConfig.java        # 现有，升级为 Milvus ContentRetriever
├── service/
│   └── impl/
│       └── MilvusRetriever.java    # Milvus 向量检索实现
```

### 4.3 预留扩展点

```java
// RetrieverService 接口
// MVP：MilvusRetriever（纯向量检索，Cosine 相似度，Top-5）
// 未来：HybridRetriever（Milvus 稠密 + ES 稀疏 BM25 + RRF 融合）
//       RerankedRetriever（BGE-Reranker 精排，Top-30 → Top-5）
// 通过装饰器模式组合：
//   new RerankedRetriever(new HybridRetriever(milvusRetriever, esRetriever))

// EmbeddingService 接口
// MVP：DeepSeekEmbeddingService（调用 API，零部署）
// 未来：LocalEmbeddingService（本地 BGE-M3，数据不出内网）
// 切换：application.yaml 中改配置
```

### 4.4 Milvus 配置

| 参数 | MVP 值 | 说明 |
|------|--------|------|
| Collection 名称 | `zhiliao_chunks` | |
| 向量维度 | 1024 | DeepSeek Embedding 输出维度 |
| 索引类型 | HNSW | 适合 10 万级向量 |
| 相似度度量 | COSINE | |
| 分区 | 不分区 | 未来按 knowledgeBaseId 分区 |

### 4.5 长期演进

引入 ES 或 Reranker 只需加新实现类，RetrieverService 接口不变，上层 ChatService 不改代码。

## 5. Phase 4：对话增强（预计 1-2 周）

### 5.1 改动点

| 改动 | 说明 | 代码量 |
|------|------|--------|
| Redis ChatMemoryStore | 替换 CustomChatMemoryStore（本地文件 → Redis） | ~20 行 |
| 引用溯源 | System Prompt 注入"标注来源文档"指令 | ~5 行 |
| 置信度拒答 | ContentRetriever minScore 阈值判断 | ~10 行 |

### 5.2 引用溯源实现

System Prompt（`system-prompt.md`）增加指令：

```
回答要求：
1. 仅根据提供的文档内容回答
2. 每段回答末尾标注来源文档标题，格式：[来源：xxx.pdf]
3. 如果文档内容不足以回答问题，明确说明"文档库中未找到相关信息"
4. 禁止编造文档中不存在的内容
```

### 5.3 置信度拒答实现

```java
// 在 RetrievalConfig 中配置 ContentRetriever
EmbeddingStoreContentRetriever.builder()
    .embeddingStore(embeddingStore)
    .embeddingModel(embeddingModel)
    .minScore(0.5)        // 低于此分数拒绝回答
    .maxResults(5)
    .build();

// 当检索结果全部低于 minScore 时，ChatService 返回：
// "文档库中未找到与您问题相关的信息，请尝试换一种问法。"
```

## 6. 轻量 JWT 方案

### 6.1 设计思路

不用 Spring Security，仅引入 `jjwt` 库（一个依赖），通过自定义 Filter 实现 JWT 解析和用户上下文注入。

### 6.2 涉及的类

| 类 | 位置 | 职责 |
|----|------|------|
| `CurrentUser` (record) | zhiliao-common | `{ Long id, String username, Long deptId }` |
| `UserContextHolder` | zhiliao-common | ThreadLocal，`set/get/clear` 当前用户 |
| `JwtUtil` | zhiliao-common | JJWT 签发/解析/验证（HMAC-SHA256）|
| `JwtFilter` | zhiliao-auth | `OncePerRequestFilter`，从 `Authorization: Bearer <token>` 解析用户 |
| `AuthController` | zhiliao-auth | `POST /api/auth/login`（校验 → 签发 → 打印 → 返回 token）|
| `UserService` | zhiliao-auth | 查询 users 表校验密码（BCrypt）|

### 6.3 登录流程

```
POST /api/auth/login
Body: { "username": "admin", "password": "admin123" }

服务端：
  1. UserService 查 users 表，BCrypt 校验密码
  2. JwtUtil 签发 token（payload: userId, username, deptId, expireAt）
  3. 控制台打印：===== 登录成功 =====\nToken: eyJhbGci...
  4. 返回 { "token": "eyJhbGci..." }

后续请求：
  Header: Authorization: Bearer eyJhbGci...
  → JwtFilter 解析 → UserContextHolder.set(user)
  → Controller 中 UserContextHolder.get() 获取用户
```

### 6.4 预留扩展点

```java
// JwtFilter 是 Filter 链的最后一环
// 未来接入 SSO 时，在 JwtFilter 之前加 OAuth2Filter：
//   OAuth2Filter（SSO 回调 → 获取用户信息 → 生成自有 JWT）
//     → JwtFilter（和现在一样，解析 JWT → 注入上下文）
// UserService 接口：
//   MVP：DbUserService（查 users 表）
//   未来：SsoUserService（SSO 回调时自动创建/同步用户）
```

### 6.5 测试用户（种子数据）

| 用户名 | 密码 | 部门 |
|--------|------|------|
| admin | admin123 | 技术部 |
| zhangsan | 123456 | 产品部 |
| lisi | 123456 | 运营部 |

## 7. 模块间依赖关系

```
zhiliao-common（CurrentUser, UserContextHolder, JwtUtil）
    ├── zhiliao-ingestion（依赖 common + RabbitMQ + MinIO + PostgreSQL）
    ├── zhiliao-retrieval（依赖 common + Milvus + PostgreSQL）
    │       └── zhiliao-chat（依赖 retrieval + common + Redis）
    └── zhiliao-auth（依赖 common + PostgreSQL）
            └── 注入 JwtFilter 到 WebConfig
```

`zhiliao-app` 作为启动入口，引入所有模块的依赖。

## 8. 开发顺序与里程碑

### 8.1 Phase 1：基础设施（第 1 周）

**里程碑：** `docker compose up` 一键启动全部依赖，PostgreSQL 表建好，测试数据写入

| 序号 | 任务 | 产出 |
|------|------|------|
| 1.1 | 编写 Docker Compose 配置文件 | `docker/local-dev.yml` |
| 1.2 | 编写数据库 DDL + 种子数据 | `schema.sql` + `data.sql` |
| 1.3 | 在 zhiliao-app 中配置多数据源连接 | `application.yaml` 更新 |
| 1.4 | 验证各服务可用性 | 启动脚本 + 健康检查 |

### 8.2 Phase 2：文档摄入 + 向量化（第 2-5 周）

**里程碑：** 上传文档 → Tika 解析 → 分割 → Embedding → 双写 PG + Milvus

| 序号 | 任务 | 产出 |
|------|------|------|
| 2.1 | zhiliao-ingestion 模块初始化 + MinIO 集成 | 文件结构 + MinIOConfig |
| 2.2 | Tika 文档解析（DocumentParser + TikaDocumentParser）| PDF/Word/MD 文本提取 |
| 2.3 | 文档分割（RecursiveDocumentSplitter）| Chunk 生成 |
| 2.4 | RabbitMQ 配置 + 异步消费者 | 消息队列 + Consumer |
| 2.5 | DeepSeek Embedding Service | Embedding API 调用 |
| 2.6 | Milvus 集成（Collection 创建 + 写入）| MilvusConfig + 向量写入 |
| 2.7 | PG chunks 表写入 + DocumentRepository | 双写完成 |
| 2.8 | DocumentController + 端到端联调 | 完整 Pipeline |

### 8.3 Phase 3：检索层 — 查询（第 5-6 周）

**里程碑：** 提问 → 向量检索 → 返回相关文档片段（与 Phase 2 末周并行）

| 序号 | 任务 | 产出 |
|------|------|------|
| 3.1 | MilvusRetriever（查询接口）| 向量检索能力 |
| 3.2 | 升级 RetrievalConfig（InMemory → Milvus）| ContentRetriever 注入 ChatService |

### 8.4 Phase 4：对话增强 + JWT（第 6-8 周）

**里程碑：** 完整 RAG 对话流程跑通（上传 → 检索 → 回答）

| 序号 | 任务 | 产出 |
|------|------|------|
| 4.1 | Redis ChatMemoryStore | 替换本地文件存储 |
| 4.2 | 引用溯源（System Prompt 更新）| 答案带来源标注 |
| 4.3 | 置信度拒答（minScore 配置）| 低置信度拒绝回答 |
| 4.4 | JWT 工具类 + UserContextHolder | zhiliao-common |
| 4.5 | JwtFilter + AuthController | 登录 + 鉴权 |
| 4.6 | 端到端联调 | 上传文档 → 搜索 → 对话 |

## 9. 关键决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| RAG 策略 | 基础 RAG（纯向量检索，不查询改写，不 Rerank） | MVP 快速验证，精度后续迭代 |
| Embedding 方式 | DeepSeek Embedding API | 已有 API Key，零部署成本 |
| 文档处理 | RabbitMQ 异步 | 用户明确要求 |
| 用户认证 | 自定义 JWT Filter，不用 Spring Security | 轻量、可控，未来 SSO 可叠加 |
| 数据库 | 一次建全部表 | tenant_id 字段预留，未来不改表结构 |
| 鉴权 JWT | HMAC-SHA256 对称签名 | JJWT 库仅一个依赖，无需 RSA 密钥对管理 |
| 密码存储 | BCrypt | 业界标准，Spring 自带 BCryptPasswordEncoder |

## 10. 风险与应对

| 风险 | 概率 | 影响 | 应对 |
|------|------|------|------|
| Tika 解析复杂 PDF 乱码 | 高 | 中 | 降级尝试 PDFBox，再降级返回"解析失败" |
| Milvus 部署问题 | 中 | 高 | 备选方案：用 pgvector 替代（PostgreSQL 已有）|
| DeepSeek API 限流 | 低 | 中 | 重试机制 + 降级提示 |
| 文档处理 Pipeline Bug | 中 | 高 | 完善的错误处理和状态记录 |
