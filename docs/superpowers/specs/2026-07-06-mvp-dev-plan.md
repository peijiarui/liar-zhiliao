# 知了知了 MVP 开发计划

> 版本：v2.0 | 日期：2026-07-07 | 作者：Pei
> 基于：企业级 RAG 知识库架构设计文档 (v1.0)

## 1. 整体策略

### 1.1 核心原则

- **接口 + 条件装配**：每个模块定义接口，MVP 提供简单实现，未来通过 `@ConditionalOnProperty` 或 `@Profile` 切换为生产实现，调用方不改代码
- **字段先行**：数据库表字段一次设计到位（含多租户 tenant_id），MVP 阶段赋默认值，不做逻辑过滤
- **不做冗余预留**：只通过抽象接口和字段预留扩展点，不做"未来可能用到的实现代码"

### 1.2 技术栈

| 技术 | 用途 | 版本 |
|------|------|------|
| Spring Boot | 应用框架 | 3.5.16 |
| Java | 开发语言 | 17 |
| LangChain4j | AI 编排框架 | 1.17.0-beta27 |
| MyBatis-Plus | 持久层框架 | 3.5.9 |
| PostgreSQL 16 | 业务数据库 | - |
| Redis Stack | 会话记忆缓存 | - |
| MinIO | 文档对象存储 | - |
| RabbitMQ | 异步任务队列 | 3.13 |
| Milvus | 向量数据库 | - |
| Apache Tika | 文档解析 | 3.1.0 |
| JJWT | JWT 令牌 | 0.12.6 |

### 1.3 MVP 范围

| 阶段 | 模块 | 状态 |
|------|------|------|
| Phase 1 | 基础设施（Docker Compose + 数据库 DDL） | 完成 |
| Phase 2 | 文档摄入 + 向量化（Tika 解析 + Embedding + 双写 PG/Milvus） | 完成 |
| Phase 3 | 检索层（Milvus 查询 + EmbeddingQueryRouter + ContentRetriever 注入 ChatService） | 完成 |
| Phase 4 | 对话增强（Redis 记忆 + 引用溯源 + 置信度拒答 + JWT 鉴权） | 完成 |

### 1.4 延后范围

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
| PostgreSQL 16 | 业务数据主库 | 启用 | 文档/用户/zlChunk 等 |
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
| `sys_user` | id, username, password_hash, dept_id, role, created_at | 写入测试用户 |
| `sys_department` | id, name, parent_id | 写入默认部门 |
| `zl_knowledge_base` | id, name, description, tenant_id, created_at | 使用 |
| `zl_document` | id, kb_id, file_name, file_type, status, minio_key, file_size, md5, chunk_count, created_at | 使用 |
| `zl_chunk` | id, doc_id, content, embedding_id, metadata, created_at | 使用（PG + Milvus 双写）|
| `zl_conversation` | id, memory_id, user_id, title, message_count, created_at | 使用 |
| `zl_audit_log` | id, user_id, action, target_type, target_id, detail, created_at | 写入 |

- 所有表带 `tenant_id` / `dept_id` 列，MVP 阶段填入 `"default"` / `1`
- DDL 使用 SQL 文件管理（`schema.sql` + `data.sql`）

### 2.3 PG + Milvus 双写设计

**为什么双写？**

| 角色 | 用途 | 说明 |
|------|------|------|
| **PG chunks 表** | 数据主库、管理操作、恢复兜底 | 权威来源，未来管理后台读此表展示 zlChunk 列表 |
| **Milvus** | 向量检索引擎 | 纯搜索用途，数据可视为 PG 的快照副本 |

**数据映射关系：**

```
PG zl_chunk 表：   id | doc_id | content | metadata | embedding_id | created_at
                      ↑            ↑          ↑           ↓
Milvus：              doc_id   content   metadata   vector_id
```

- `chunks.embedding_id` → 关联 Milvus 中该 zlChunk 对应的向量 ID
- 检索时：只查 Milvus，拿到 content + doc_id + metadata，不查 PG
- 管理时：查 PG chunks 表，展示/搜索 zlChunk 列表
- 恢复时：PG 数据完整，可批量重新写入 Milvus

**写入流程：**

```
Tika 解析 → 分割 chunks → Embedding → 写入 Milvus（返回 vector_id）
                                          → 写入 PG chunks（含 vector_id）
```

两条写入在同一个事务性操作中完成（非强事务，以 PG 为准，Milvus 写入失败时文档标记 FAILED 可重试）。

### 2.4 输出物

- `docker/local-dev.yml`（Milvus 使用 standalone 模式：独立 etcd + MinIO，非嵌入式）
- `zhiliao-app/src/main/resources/sql/schema.sql`（DDL）
- `zhiliao-app/src/main/resources/sql/data.sql`（测试种子数据）

## 3. Phase 2：文档摄入 + 向量化

**此阶段覆盖全部写入链路：Tika 解析 → 分割 → Embedding → 双写 Milvus + PG。**

### 3.1 架构

```
POST /api/documents/upload
  → DocumentController
  → DocumentService.upload()
    → 计算 MD5 → 保存原始文件到 MinIO（minio_key = docs/{kb_id}/{uuid}/{filename}）
    → DocumentMapper.insert() → documents 表写入记录（status = UPLOADED）
    → 投递 RabbitMQ（message = DocumentMessage）
    → 返回 DocumentRespVO（含 documentId）

RabbitMQ Consumer（异步）
  → DocumentConsumer 接收消息
  → DocumentConsumerProcessor.process()
    → 从 MinIO 下载文件
    → TikaDocumentParserImpl.parse() → 提取文本
    → RecursiveDocumentSplitterImpl.split() → List<TextSegment>
    → EmbeddingModel（通过 LangChain4j 调用通义千问 text-embedding-v4）→ 每个 zlChunk 向量化
    → 写入 Milvus（通过 LangChain4j EmbeddingStore）
    → ChunkMapper.insert() → 写入 PG chunks 表（含 doc_id, content, embedding_id, metadata）
    → DocumentMapper.updateById() → documents 表 status → COMPLETED / FAILED
```

### 3.2 模块文件结构

#### zhiliao-ingestion（完整结构）

```
zhiliao-ingestion/
├── config/
│   ├── IngestionConfig.java           # 启动时从 classpath:docs/ 加载文档，初始化 EmbeddingStore
│   ├── JsonbTypeHandler.java          # MyBatis TypeHandler for PostgreSQL JSONB
│   ├── MinIOConfig.java               # MinIO 客户端配置（endpoint/access-key/bucket）
│   ├── MyBatisPlusConfig.java         # MetaObjectHandler 自动填充 createdAt
│   └── RabbitMQConfig.java            # Exchange/Queue/Binding 声明
├── consumer/
│   ├── DocumentConsumer.java          # @RabbitListener 监听文档处理队列
│   └── DocumentConsumerProcessor.java # 编排解析→分割→Embedding→双写完整流程
├── controller/
│   └── DocumentController.java        # POST /api/documents/upload, GET /api/documents/{id}
├── entity/
│   ├── ZlDocument.java                # MyBatis-Plus @TableName("zl_document") 实体
│   └── ZlChunk.java                   # MyBatis-Plus @TableName("zl_chunk") 实体
├── enums/
│   └── DocumentStatusEnum.java        # UPLOADED / PROCESSING / COMPLETED / FAILED
├── mapper/
│   ├── ZlDocumentMapper.java          # extends BaseMapper<ZlDocument>
│   └── ZlChunkMapper.java             # extends BaseMapper<ZlChunk>
├── model/
│   └── DocumentMessage.java           # RabbitMQ 消息 POJO
├── service/
│   ├── DocumentParser.java            # 接口：parse(InputStream, fileName) → String
│   ├── DocumentService.java            # 接口：upload(MultipartFile, kbId) → Document
│   ├── DocumentSplitter.java          # 接口：split(text, documentId) → List<TextSegment>
│   └── impl/
│       ├── DocumentServiceImpl.java         # Upload 实现：MinIO 入 + DB 写 + MQ 投递
│       ├── RecursiveDocumentSplitterImpl.java  # LangChain4j DocumentSplitters.recursive 封装
│       └── TikaDocumentParserImpl.java      # Apache Tika 解析实现
└── vo/response/
    └── DocumentRespVO.java            # 响应 VO（id, fileName, status, fileSize, chunkCount, createdAt）
```

> **MyBatis-Plus 说明**：所有数据库操作通过 Mapper 接口完成（如 `DocumentMapper.insert()`、`documentMapper.selectById()`），不再使用 JdbcTemplate。JSONB 列通过自定义 `JsonbTypeHandler` 处理，`createdAt` 字段通过 `MetaObjectHandler` 自动填充。

#### zhiliao-retrieval（写入相关）

```
zhiliao-retrieval/
└── config/
    └── RetrievalConfig.java           # 配置 ContentRetriever（minScore=0.5, maxResults=5）
```

检索模块采用极简设计，通过 `langchain4j-milvus-spring-boot-starter` 自动装配 `EmbeddingStore<TextSegment>` 和 `EmbeddingModel` Bean。这些 Bean 在 `IngestionConfig` 和 `RetrievalConfig` 中共享使用，写入流程在 ingestion 的 `DocumentConsumerProcessor` 中编排。

### 3.3 服务层协作关系

```
DocumentController
  → DocumentService (接口/impl)
    → ZlDocumentMapper (MyBatis-Plus)
    → MinioClient (MinIO)
    → RabbitTemplate (消息投递)

DocumentConsumer (RabbitMQ 监听)
  → DocumentConsumerProcessor
    → MinioClient → 下载文件
    → DocumentParser → Tika 解析
    → DocumentSplitter → 递归分割
    → EmbeddingModel (LangChain4j) → 向量化
    → EmbeddingStore (LangChain4j/Milvus) → 向量写入
    → ZlChunkMapper (MyBatis-Plus) → PG 写入
    → ZlDocumentMapper (MyBatis-Plus) → 状态更新
```

### 3.4 预留扩展点

```java
// DocumentParser 接口
// MVP：TikaDocumentParserImpl（Apache Tika 通用解析）
// 未来：OcrDocumentParser（Tesseract OCR，解析图片/扫描件）
//       PdfBoxDocumentParser（复杂 PDF 降级处理）
// 通过 DocumentParser 链（Composite/Chain of Responsibility）组合

// DocumentSplitter 接口
// MVP：RecursiveDocumentSplitterImpl（LangChain4j DocumentSplitters.recursive，max 500/overlap 100）
// 未来：SemanticDocumentSplitter（基于 Embedding 相似度断点）
//       ParentChildDocumentSplitter（Parent 2048t + Child 512t 父子文档模式）
```

### 3.5 状态机

```
UPLOADED → PROCESSING → COMPLETED
                         → FAILED（含 error_message）
```

### 3.6 RabbitMQ 配置

| 配置项 | 值 |
|--------|-----|
| Exchange | `zhiliao.direct` |
| Queue | `zhiliao.document.process` |
| Routing Key | `document.process` |
| Consumer 并发 | 1-3（根据文档大小动态调整）|

## 4. Phase 3：检索层 — 查询

**此阶段只做 Milvus 查询 + 注入 ChatService，写入已在 Phase 2 完成。**

### 4.1 架构

```
用户问题 → EmbeddingModel (LangChain4j) → 向量
  → EmbeddingQueryRouter
    → 计算与闲聊/知识质心的余弦相似度
    → 闲聊（sim > 0.78 + 偏向闲聊）→ EmptyContentRetriever（跳过 RAG）
    → 知识类 → EmbeddingStoreContentRetriever（Milvus, HNSW, COSINE, Top-5, minScore=0.5）
  → 注入 ChatService（@AiService retrievalAugmentor = "retrievalAugmentor"）
```

具体配置在 `RetrievalConfig.java`：

```java
// EmbeddingQueryRouter：基于质心相似度的意图路由
//   从 sys_routing_seed 表加载种子（降级到内置默认值）
//   计算 chat/knowledge 两类质心 embedding
//   运行时判断用户意图，路由到不同检索器

// EmbeddingStoreContentRetriever（知识检索）：
//   minScore=0.5: 低于此分数的结果不返回
//   maxResults=5: 最多返回 5 个相关片段
//   EmbeddingModel 和 EmbeddingStore 由 langchain4j-milvus-spring-boot-starter 自动装配

// EmptyContentRetriever（闲聊）：
//   直接返回空结果，跳过 RAG 检索
```

### 4.2 模块文件结构

```
zhiliao-retrieval/
├── config/
│   └── RetrievalConfig.java           # contentRetriever + emptyContentRetriever + EmbeddingQueryRouter + retrievalAugmentor Bean
├── entity/
│   └── SysRoutingSeed.java            # 路由种子实体（MyBatis-Plus）
├── mapper/
│   └── SysRoutingSeedMapper.java       # BaseMapper<SysRoutingSeed>
├── retriever/
│   └── EmptyContentRetriever.java     # 空检索器（闲聊时跳过 RAG）
└── router/
    └── EmbeddingQueryRouter.java      # QueryRouter 实现，基于质心相似度路由
```

> 检索模块采用 LangChain4j 内置机制：`EmbeddingStoreContentRetriever` 封装了向量检索逻辑，直接注入 ChatService。无需自定义 EmbeddingService、VectorStore 或 Retriever 接口。

### 4.3 Milvus 配置

| 参数 | MVP 值 | 说明 |
|------|--------|------|
| Collection 名称 | `zhiliao_chunks` | 由 `langchain4j.milvus.collection-name` 配置 |
| 向量维度 | 1024 | 通义千问 text-embedding-v4 输出维度 |
| 索引类型 | HNSW | 适合 10 万级向量 |
| 相似度度量 | COSINE | |
| 分区 | 不分区 | 未来按 knowledgeBaseId 分区 |

### 4.4 预留扩展点

```java
// QueryRouter 扩展（通过 RetrievalConfig 切换）
// MVP：EmbeddingQueryRouter（基于质心相似度的意图路由）
//       知识类 → EmbeddingStoreContentRetriever（纯向量检索，Cosine 相似度，Top-5，minScore=0.5）
//       闲聊类 → EmptyContentRetriever（跳过 RAG）
// 未来：HybridRetriever（Milvus 稠密 + ES 稀疏 BM25 + RRF 融合）
//       RerankedRetriever（BGE-Reranker 精排，Top-30 → Top-5）
// 通过装饰器模式组合：
//   new RerankedRetriever(new HybridRetriever(milvusRetriever, esRetriever))
```

## 5. Phase 4：对话增强

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

### 5.4 Chat 模块结构

```
zhiliao-chat/
├── config/
│   └── ChatMemoryConfig.java         # MessageWindowChatMemory（20条窗口）
│                                      # ChatMemoryProvider（Redis 持久化）
├── controller/
│   └── ChatController.java           # GET /chat?memoryId=X&message=Y → Flux<String>
├── repository/
│   └── CustomChatMemoryStore.java    # @Repository, StringRedisTemplate 实现
│                                      # getMessages/updateMessages/deleteMessages
│                                      # TTL = 1 天
└── service/
    └── ChatService.java              # @AiService 接口
                                      # @SystemMessage(fromResource = "system-prompt.md")
                                      # Flux<String> chat(@MemoryId, @UserMessage)
                                      # retrievalAugmentor = "retrievalAugmentor"
```

## 6. 轻量 JWT 方案

### 6.1 设计思路

不用 Spring Security Web 模块，仅引入 `jjwt` 库和 `spring-security-crypto`（仅用于 BCrypt）。通过自定义 `Filter` 实现 JWT 解析和用户上下文注入。

### 6.2 涉及的类

| 类 | 位置 | 职责 |
|----|------|------|
| `CurrentUser` (record) | zhiliao-common (`model` 包) | `{ Long id, String username, Long deptId }` |
| `UserContextHolder` | zhiliao-common | ThreadLocal，`set/get/clear` 当前用户 |
| `JwtUtil` | zhiliao-common | JJWT 签发/解析/验证（HMAC-SHA256），含默认 secret/expiration |
| `JwtFilter` | zhiliao-auth | `Filter`，从 `Authorization: Bearer <token>` 解析用户 |
| `SecurityConfig` | zhiliao-auth | `PasswordEncoder` Bean（BCryptPasswordEncoder）|
| `AuthController` | zhiliao-auth | `POST /api/auth/login` |
| `UserService` | zhiliao-auth | 接口 + `UserServiceImpl`（MyBatis-Plus SysUserMapper） |
| `SysUserMapper` | zhiliao-auth | MyBatis-Plus `BaseMapper<SysUser>` |
| `SysUser` | zhiliao-auth | MyBatis-Plus 实体 |
| `DataInitializer` | zhiliao-auth | `CommandLineRunner`，启动时校验/更新 BCrypt 密码 hash |

### 6.3 Auth 模块结构

```
zhiliao-auth/
├── config/
│   ├── SecurityConfig.java           # @Bean PasswordEncoder (BCrypt)
│   └── DataInitializer.java          # CommandLineRunner，启动时校验/更新 BCrypt hash
├── controller/
│   └── AuthController.java           # POST /api/auth/login → { token }
├── entity/
│   └── SysUser.java                  # MyBatis-Plus 实体（@TableName "sys_user"）
├── filter/
│   └── JwtFilter.java                # Filter, @Order(1), 解析 Bearer Token
├── mapper/
│   └── SysUserMapper.java            # extends BaseMapper<SysUser>
├── service/
│   ├── UserService.java              # 接口：authenticate(username, password)
│   └── impl/
│       └── UserServiceImpl.java      # SysUserMapper.selectOne → BCrypt 校验
```

### 6.4 登录流程

```
POST /api/auth/login
Body: { "username": "admin", "password": "123456" }

服务端：
  1. UserService.authenticate()
     → UserMapper.selectOne(username)
     → PasswordEncoder.matches(password, user.passwordHash)
     → 校验失败抛 IllegalArgumentException
  2. JwtUtil.generateToken(currentUser)
  3. 控制台打印：===== 登录成功 =====\nToken: eyJhbGci...
  4. 返回 { "token": "eyJhbGci..." }

后续请求：
  Header: Authorization: Bearer eyJhbGci...
  → JwtFilter 解析 → UserContextHolder.set(user)
  → Controller 中 UserContextHolder.get() 获取用户
```

### 6.5 预留扩展点

```java
// JwtFilter 是 Filter 链的最后一环
// 未来接入 SSO 时，在 JwtFilter 之前加 OAuth2Filter：
//   OAuth2Filter（SSO 回调 → 获取用户信息 → 生成自有 JWT）
//     → JwtFilter（和现在一样，解析 JWT → 注入上下文）
// UserService 接口：
//   MVP：UserServiceImpl（MyBatis-Plus 查 users 表）
//   未来：SsoUserService（SSO 回调时自动创建/同步用户）
```

### 6.6 测试用户（种子数据）

| 用户名 | 密码 | 部门 |
|--------|------|------|
| admin | admin123 | 技术部 |
| zhangsan | 123456 | 产品部 |
| lisi | 123456 | 运营部 |

## 7. 模块间依赖关系

```
zhiliao-common（CurrentUser, UserContextHolder, JwtUtil, AiModelConstants, LocalFileStore）
    ├── zhiliao-ingestion（依赖 common + retrieval + MyBatis-Plus + RabbitMQ + MinIO + Tika）
    ├── zhiliao-retrieval（依赖 common + langchain4j-milvus-spring-boot-starter）
    │       └── zhiliao-chat（依赖 retrieval + common + langchain4j-open-ai + Redis）
    │               注入 retrievalAugmentor（含 EmbeddingQueryRouter + ContentRetriever）
    └── zhiliao-auth（依赖 common + MyBatis-Plus + spring-security-crypto）
            └── JwtFilter 被 @Component 自动注册

zhiliao-app（启动入口，引入所有模块）
```

### 模块 pom 依赖要点

| 模块 | 关键依赖 |
|------|----------|
| `zhiliao-common` | `spring-boot-starter`, JJWT (0.12.6), Lombok, commons-codec |
| `zhiliao-ingestion` | common, retrieval, mybatis-plus, PostgreSQL, RabbitMQ, Tika, MinIO, langchain4j-easy-rag |
| `zhiliao-retrieval` | common, langchain4j-easy-rag, langchain4j-milvus-spring-boot-starter |
| `zhiliao-chat` | common, retrieval, langchain4j-open-ai-spring-boot-starter, langchain4j-spring-boot-starter, langchain4j-reactor, spring-boot-starter-webflux, spring-boot-starter-data-redis |
| `zhiliao-auth` | common, mybatis-plus, spring-boot-starter-web, spring-security-crypto |
| `zhiliao-app` | 所有模块 + spring-boot-starter-web + spring-boot-starter-test |

## 8. 开发顺序与里程碑

### 8.1 Phase 1：基础设施（第 1 周）

**里程碑：** `docker compose up` 一键启动全部依赖，PostgreSQL 表建好，测试数据写入

| 序号 | 任务 | 产出 |
|------|------|------|
| 1.1 | 编写 Docker Compose 配置文件 | `docker/local-dev.yml`（Milvus standard mode: etcd + minio + milvus）|
| 1.2 | 编写数据库 DDL + 种子数据 | `schema.sql`（sys_/zl_ 前缀表名）+ `data.sql` |
| 1.3 | 在 zhiliao-app 中配置多数据源连接 | `application.yaml` 更新 |
| 1.4 | 验证各服务可用性 | 启动脚本 + 健康检查 |

### 8.2 Phase 2：文档摄入 + 向量化（第 2-5 周）

**里程碑：** 上传文档 → Tika 解析 → 分割 → Embedding → 双写 PG + Milvus

| 序号 | 任务 | 产出 |
|------|------|------|
| 2.1 | zhiliao-ingestion 模块依赖 + MyBatis-Plus 实体 | pom.xml, ZlDocument.java/ZlChunk.java/DocumentStatusEnum/Mapper |
| 2.2 | MinIO 集成（配置 + 客户端 Bean + 上传实现） | MinIOConfig.java, DocumentServiceImpl.java |
| 2.3 | Tika 文档解析 | TikaDocumentParserImpl.java |
| 2.4 | 文档分割 | RecursiveDocumentSplitterImpl.java |
| 2.5 | RabbitMQ 配置 + 异步消费者 | RabbitMQConfig.java, DocumentConsumer.java |
| 2.6 | DocumentConsumerProcessor（编排写入流程）| 解析→分割→Embedding→Milvus→PG（已完整实现）|
| 2.7 | DocumentController + 端到端联调 | 完整 Pipeline |

### 8.3 Phase 3：检索层 — 查询（第 5-6 周）

**里程碑：** 提问 → 向量检索 → 返回相关文档片段（与 Phase 2 末周并行）

| 序号 | 任务 | 产出 |
|------|------|------|
| 3.1 | 升级 RetrievalConfig（InMemory → Milvus + QueryRouter）| EmbeddingQueryRouter + ContentRetriever + EmptyContentRetriever |
| 3.2 | 路由种子表 + 实体 + Mapper | SysRoutingSeed, SysRoutingSeedMapper |
| 3.3 | 验证 retrievalAugmentor 注入 ChatService | 端到端 RAG 对话（含闲聊/知识自动路由）|

### 8.4 Phase 4：对话增强 + JWT（第 6-8 周）

**里程碑：** 完整 RAG 对话流程跑通（上传 → 检索 → 回答）

| 序号 | 任务 | 产出 |
|------|------|------|
| 4.1 | Redis ChatMemoryStore | 替换本地文件存储 |
| 4.2 | 引用溯源（System Prompt 更新）| 答案带来源标注 + 知识/闲聊双模式 |
| 4.3 | 置信度拒答（minScore 配置）| 低置信度拒绝回答 |
| 4.4 | JWT 工具类 + UserContextHolder | zhiliao-common |
| 4.5 | JwtFilter + AuthController | 登录 + 鉴权 |
| 4.6 | DataInitializer（启动时 BCrypt 校验）| CommandLineRunner 自动同步密码 |
| 4.7 | 端到端联调 | 上传文档 → 检索 → 对话 |

## 9. 关键决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 持久层框架 | MyBatis-Plus | 相比 JPA 更轻量、SQL 可控；相比 JdbcTemplate 减少样板代码 |
| RAG 策略 | 基础 RAG（纯向量检索，不查询改写，不 Rerank） | MVP 快速验证，精度后续迭代 |
| Embedding 方式 | 通义千问 text-embedding-v4 API（已配置在 application.yaml） | 与 DeepSeek 解耦，可选供应商 |
| 向量数据库 | Milvus（通过 langchain4j-milvus-spring-boot-starter 自动装配）| 标准化集成，无需手写 Milvus SDK 调用 |
| 文档处理 | RabbitMQ 异步 | 用户明确要求 |
| 用户认证 | 自定义 JWT Filter + BCrypt，不用 Spring Security Web | 轻量、可控，未来 SSO 可叠加 |
| 数据库 | 一次建全部表 | tenant_id 字段预留，未来不改表结构 |
| 鉴权 JWT | HMAC-SHA256 对称签名 | JJWT 库仅一个依赖，无需 RSA 密钥对管理 |
| 密码存储 | BCrypt（通过 spring-security-crypto 的 PasswordEncoder） | 业界标准，Spring 封装 |

## 10. 风险与应对

| 风险 | 概率 | 影响 | 应对 |
|------|------|------|------|
| Tika 解析复杂 PDF 乱码 | 高 | 中 | 降级尝试 PDFBox，再降级返回"解析失败" |
| Milvus 部署问题 | 中 | 高 | 备选方案：用 pgvector 替代（PostgreSQL 已有），切换只需改 application.yaml |
| DeepSeek API / Embedding API 限流 | 低 | 中 | 重试机制 + 降级提示 |
| 文档处理 Pipeline Bug | 中 | 高 | 完善的错误处理和状态记录 |
