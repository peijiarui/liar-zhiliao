# 知了知了 MVP 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现企业级 RAG 知识库 MVP，覆盖文档上传 → Tika 解析 → 分割 → Embedding → 双写 PG + Milvus → 向量检索 → LLM 对话的核心链路

**Architecture:** Spring Boot 单体应用 + Maven 多模块。持久层统一使用 MyBatis-Plus，向量检索通过 LangChain4j EmbeddingStoreContentRetriever 集成。

**Tech Stack:** Spring Boot 3.5, LangChain4j 1.17, MyBatis-Plus 3.5.9, Apache Tika, RabbitMQ, Milvus, PostgreSQL 16, Redis Stack, MinIO, 通义千问 text-embedding-v4, DeepSeek Chat, JJWT

---

## 文件全景

```
Phase 1 - 基础设施:
  docker/local-dev.yml                                          (新建)
  zhiliao-app/src/main/resources/sql/schema.sql                 (新建)
  zhiliao-app/src/main/resources/sql/data.sql                   (新建)
  zhiliao-app/src/main/resources/application.yaml               (修改)
  zhiliao-app/pom.xml                                           (修改)
  zhiliao-ingestion/pom.xml                                     (已存在，无需修改)
  zhiliao-retrieval/pom.xml                                     (已存在，无需修改)
  zhiliao-auth/pom.xml                                          (已存在，无需修改)
  zhiliao-common/pom.xml                                        (已存在，无需修改)

Phase 2 - 文档摄入 + 向量化:
  zhiliao-ingestion 模块（MyBatis-Plus 实体 + Mapper + Service + Controller + Consumer）
  实体层:
    zhiliao-ingestion/.../entity/ZlDocument.java                (已存在)
    zhiliao-ingestion/.../entity/ZlChunk.java                   (已存在)
    zhiliao-ingestion/.../enums/DocumentStatusEnum.java         (已存在)
  持久层:
    zhiliao-ingestion/.../mapper/ZlDocumentMapper.java          (已存在)
    zhiliao-ingestion/.../mapper/ZlChunkMapper.java             (已存在)
    zhiliao-ingestion/.../config/MyBatisPlusConfig.java         (已存在)
    zhiliao-ingestion/.../config/JsonbTypeHandler.java          (已存在)
  服务层:
    zhiliao-ingestion/.../service/DocumentService.java          (已存在)
    zhiliao-ingestion/.../service/impl/DocumentServiceImpl.java (已存在)
    zhiliao-ingestion/.../service/DocumentParser.java           (已存在)
    zhiliao-ingestion/.../service/impl/TikaDocumentParserImpl.java (已存在)
    zhiliao-ingestion/.../service/RecursiveDocumentSplitter.java (已存在)
    zhiliao-ingestion/.../service/impl/RecursiveDocumentSplitterImpl.java (已存在)
  配置层:
    zhiliao-ingestion/.../config/MinIOConfig.java               (已存在)
    zhiliao-ingestion/.../config/RabbitMQConfig.java            (已存在)
    zhiliao-ingestion/.../config/SpringBeanConfig.java          (已存在)
  消息层:
    zhiliao-ingestion/.../model/DocumentMessage.java            (已存在)
  Controller + Consumer:
    zhiliao-ingestion/.../controller/DocumentController.java    (已存在)
    zhiliao-ingestion/.../consumer/DocumentConsumer.java         (已存在)
    zhiliao-ingestion/.../consumer/DocumentConsumerProcessor.java (已存在，处理逻辑已补全)

Phase 3 - 检索查询:
  zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java       (新建，Tool-based 检索)
  (替代旧方案：删除了 EmbeddingService, VectorStore, RetrieverService, MilvusConfig 等)

Phase 4 - 对话增强 + JWT:
  zhiliao-chat/src/main/resources/system-prompt.md              (使用工具调用指令)
  zhiliao-chat/.../repository/CustomChatMemoryStore.java        (已存在，使用 Redis)
  zhiliao-chat/.../config/ChatMemoryConfig.java                 (已存在)
  zhiliao-chat/.../service/ChatService.java                     (已存在，注入 tools)
  zhiliao-chat/.../controller/ChatController.java               (已存在)
  zhiliao-common/.../model/CurrentUser.java                     (已存在)
  zhiliao-common/.../utils/UserContextHolder.java               (已存在)
  zhiliao-common/.../utils/JwtUtil.java                         (已存在)
  zhiliao-app/.../config/CorsConfig.java                        (新建，跨域支持)
  zhiliao-auth/.../config/SecurityConfig.java                   (已存在)
  zhiliao-auth/.../filter/JwtFilter.java                        (已存在)
  zhiliao-auth/.../controller/AuthController.java               (已存在)
  zhiliao-auth/.../service/UserService.java                     (已存在)
  zhiliao-auth/.../service/impl/UserServiceImpl.java            (已存在)
  zhiliao-auth/.../entity/SysUser.java                          (已存在)
  zhiliao-auth/.../mapper/SysUserMapper.java                    (已存在)
```

---

### Task 1: Docker Compose + 数据库 DDL

**Files:**
- Create: `docker/local-dev.yml`
- Create: `zhiliao-app/src/main/resources/sql/schema.sql`
- Create: `zhiliao-app/src/main/resources/sql/data.sql`
- Modify: `zhiliao-app/src/main/resources/application.yaml`

- [x] **Step 1: 创建 Docker Compose 配置文件**

`docker/local-dev.yml`（完整内容见实际文件，以下为结构概要）：

```yaml
# 网络：zhiliao-net（bridge）
# Volumes：postgres-data, redis-data, minio-data, rabbitmq-data, etcd-data, milvus-data, milvus-minio-data

services:
  postgres:      # postgres:16-alpine, 5432:5432
  redis:         # redis/redis-stack:latest, 6379:6379, 8001:8001 (RedisInsight)
  minio:         # minio/minio:latest, 9000:9000 (S3 API), 9001:9001 (Console)
  minio-init:    # minio/mc:latest, 启动时自动创建 zhiliao-docs bucket
  rabbitmq:      # rabbitmq:4-management-alpine, 5672:5672, 15672:15672

  # Milvus 独立模式（三容器协作，非嵌入式）：
  etcd:          # quay.io/coreos/etcd:v3.5.18, 元数据存储
  milvus-minio:  # minio/minio:latest, 向量数据存储
  milvus:        # milvusdb/milvus:latest, 19530:19530, 使用外部 etcd + MinIO

  # elasticsearch 注释掉，未来启用
```

- [x] **Step 2: 创建数据库 DDL**

`zhiliao-app/src/main/resources/sql/schema.sql`（完整内容见实际文件，以下为表结构概要）：

```sql
-- 核心表（全部使用 IF NOT EXISTS 支持幂等执行）：
-- sys_department:    id, name, parent_id, tenant_id, created_at
-- sys_user:          id, username, password_hash, dept_id, role(CHECK), tenant_id, created_at
-- zl_knowledge_base: id, name, description, dept_id, tenant_id, created_at
-- zl_document:       id, kb_id, file_name, file_type, status(CHECK), minio_key, file_size, md5, chunk_count, dept_id, tenant_id, created_at
-- zl_chunk:          id, doc_id, content, embedding_id, metadata(JSONB), dept_id, tenant_id, created_at
-- zl_conversation:   id, memory_id, user_id, title, message_count, dept_id, tenant_id, created_at
-- zl_audit_log:      id, user_id, action, target_type, target_id, detail(JSONB), dept_id, tenant_id, created_at

-- 索引：zl_document(kb_id, status, tenant+dept), zl_chunk(doc_id, tenant+dept),
--       zl_conversation(memory_id, user_id, tenant+dept), sys_user(tenant+dept),
--       sys_department(parent_id, tenant_id), kb(tenant+dept), audit_log(tenant+user)
```

- [x] **Step 3: 创建种子数据**

`zhiliao-app/src/main/resources/sql/data.sql`:

```sql
-- 部门（使用 ON CONFLICT DO NOTHING 幂等插入）
INSERT INTO sys_department (name, parent_id, tenant_id)
VALUES ('技术部', NULL, 'default'), ('产品部', NULL, 'default'), ('运营部', NULL, 'default')
ON CONFLICT (tenant_id, name) DO NOTHING;

-- 用户（dept_id 通过子查询解析）
-- admin123 → BCrypt hash, 123456 → BCrypt hash
INSERT INTO sys_user (username, password_hash, dept_id, role, tenant_id)
VALUES ('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        (SELECT id FROM sys_department WHERE name = '技术部'), 'ADMIN', 'default'),
       ('zhangsan', '$2a$10$.J9Dk5kBT0UQxYPfqYq3s.DGQPnwY3Y5GqYI71G2QrMnKN9JGKQTa',
        (SELECT id FROM sys_department WHERE name = '产品部'), 'USER', 'default'),
       ('lisi', '$2a$10$.J9Dk5kBT0UQxYPfqYq3s.DGQPnwY3Y5GqYI71G2QrMnKN9JGKQTa',
        (SELECT id FROM sys_department WHERE name = '运营部'), 'USER', 'default')
ON CONFLICT (username) DO NOTHING;
```

- [x] **Step 4: 更新 application.yaml**

`zhiliao-app/src/main/resources/application.yaml`:

```yaml
server:
  port: 8080

spring:
  autoconfigure:
    exclude: dev.langchain4j.spring.LangChain4jAutoConfig
  application:
    name: liar-zhiliao
  data:
    redis:
      host: ${RESOURCE_SERVER_HOST}
      port: 6379
      password: ${RESOURCE_SERVER_PASSWORD}
  datasource:
    url: jdbc:postgresql://${RESOURCE_SERVER_HOST}:5432/zhiliao?useUnicode=true&characterEncoding=utf8
    username: peijiarui
    password: ${RESOURCE_SERVER_PASSWORD}
    driver-class-name: org.postgresql.Driver
  rabbitmq:
    host: ${RESOURCE_SERVER_HOST}
    port: 5672
    username: peijiarui
    password: ${RESOURCE_SERVER_PASSWORD}
    virtual-host: /
    listener:
      simple:
        concurrency: 1
        max-concurrency: 3
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 100MB

# langchain4j 配置
langchain4j:
  open-ai:
    chat-model:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model-name: deepseek-v4-flash
      log-requests: true
    streaming-chat-model:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model-name: deepseek-v4-flash
      log-requests: true
    embedding-model:
      base-url: https://llm-kyz5903txm3fyz8f.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
      api-key: ${QWEN_API_KEY}
      model-name: text-embedding-v4
      max-segments-per-batch: 10
      log-requests: true
  milvus:
    host: ${RESOURCE_SERVER_HOST}
    port: 19530
    collection-name: zhiliao_chunks
    username: peijiarui
    password: ${RESOURCE_SERVER_PASSWORD}

# MyBatis-Plus 配置
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
    map-underscore-to-camel-case: true

logging:
  level:
    dev.langchain4j: debug
    org.liar.zhiliao.retrieval.service: debug

# zhiliao custom config
zhiliao:
  minio:
    endpoint: http://${RESOURCE_SERVER_HOST}:9000
    access-key: peijiarui
    secret-key: ${RESOURCE_SERVER_PASSWORD}
    bucket: zhiliao-docs
  rabbitmq:
    queue: zhiliao.document.process
```

---

### Task 2: zhiliao-ingestion MyBatis-Plus 实体 + Mapper（已存在，无需新建）

以下文件已存在，确认内容完整：

- `zhiliao-ingestion/.../entity/ZlDocument.java` — `@TableName("zl_document")`, `@TableId(type = IdType.AUTO)`, 含 kbId/fileName/fileType/status/minioKey/fileSize/md5/chunkCount/tenantId/createdAt
- `zhiliao-ingestion/.../entity/ZlChunk.java` — `@TableName("zl_chunk")`, 含 docId/content/embeddingId/metadata(JSONB via JsonbTypeHandler)/tenantId/createdAt
- `zhiliao-ingestion/.../enums/DocumentStatusEnum.java` — UPLOADED/PROCESSING/COMPLETED/FAILED
- `zhiliao-ingestion/.../mapper/ZlDocumentMapper.java` — `extends BaseMapper<ZlDocument>`
- `zhiliao-ingestion/.../mapper/ZlChunkMapper.java` — `extends BaseMapper<ZlChunk>`
- `zhiliao-ingestion/.../config/MyBatisPlusConfig.java` — `MetaObjectHandler` 自动填充 `createdAt`
- `zhiliao-ingestion/.../config/JsonbTypeHandler.java` — PostgreSQL JSONB TypeHandler

---

### Task 3: MinIO 集成 + DocumentService

**Files:**
- Verify: `zhiliao-ingestion/.../config/MinIOConfig.java`
- Verify: `zhiliao-ingestion/.../service/DocumentService.java`
- Verify: `zhiliao-ingestion/.../service/impl/DocumentServiceImpl.java`

**MinIOConfig** 已存在，提供 `MinioClient` Bean 和 bucket/endpoint/access-key 配置。

**DocumentService** 接口：

```java
public interface DocumentService {
    Document upload(MultipartFile file, Long kbId);
    Document getDocument(Long id);
}
```

**DocumentServiceImpl** 已实现：
1. 计算文件 MD5
2. 生成 MinIO key: `docs/{kbId}/{uuid}/{filename}`
3. 上传文件到 MinIO
4. `documentMapper.insert(doc)` → 状态 UPLOADED
5. 投递 `DocumentMessage` 到 RabbitMQ
6. 返回 Document 实体

---

### Task 4: Tika 解析 + 文档分割

**Files:**
- Verify: `zhiliao-ingestion/.../service/DocumentParser.java`
- Verify: `zhiliao-ingestion/.../service/impl/TikaDocumentParserImpl.java`
- Verify: `zhiliao-ingestion/.../service/DocumentSplitter.java`
- Verify: `zhiliao-ingestion/.../service/impl/RecursiveDocumentSplitterImpl.java`

**DocumentParser 接口：**

```java
public interface DocumentParser {
    String parse(InputStream inputStream, String fileName) throws Exception;
}
```

**TikaDocumentParserImpl：** 使用 Apache Tika 解析文档，提取纯文本。解析失败抛异常。

**DocumentSplitter 接口：**

```java
public interface DocumentSplitter {
    List<TextSegment> split(String text, String documentId);
}
```

**RecursiveDocumentSplitterImpl：** 使用 LangChain4j `DocumentSplitters.recursive(500, 100)`，按段落→句子递归切分，最大 500 token，重叠 100 token。

---

### Task 5: RabbitMQ 配置 + 异步消费者 + DocumentConsumerProcessor（补全处理逻辑）

**Files:**
- Verify: `zhiliao-ingestion/.../config/RabbitMQConfig.java`
- Verify: `zhiliao-ingestion/.../consumer/DocumentConsumer.java`
- **Modify:** `zhiliao-ingestion/.../consumer/DocumentConsumerProcessor.java` (补全 TODO 逻辑)

- [x] **Step 1: 确认 RabbitMQConfig**

已存在声明 Exchange(`zhiliao.direct`)、Queue(`zhiliao.document.process`)、Binding。

- [x] **Step 2: 确认 DocumentConsumer**

已存在 `@RabbitListener` 监听，接收 `DocumentMessage` 并调用 `DocumentConsumerProcessor.process()`。

- [x] **Step 3: 补全 DocumentConsumerProcessor**

目前 `process()` 方法体为 TODO 注释状态。需补全完整流程：

```java
@Service
@AllArgsConstructor
public class DocumentConsumerProcessor {

    private final MinioClient minioClient;
    private final MinIOConfig minIOConfig;
    private final DocumentParser documentParser;
    private final DocumentSplitter documentSplitter;
    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    // EmbeddingModel 和 EmbeddingStore 由 langchain4j-milvus-spring-boot-starter 自动装配
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public void process(DocumentMessage message) throws Exception {
        Long documentId = message.getDocumentId();
        Document doc = documentMapper.selectById(documentId);

        // 1. Update status to PROCESSING
        doc.setStatus(DocumentStatusEnum.PROCESSING.getStatus());
        documentMapper.updateById(doc);

        try {
            // 2. Download from MinIO
            var args = GetObjectArgs.builder()
                    .bucket(minIOConfig.getBucket())
                    .object(message.getMinioKey())
                    .build();
            var object = minioClient.getObject(args);

            // 3. Parse with Tika
            String text = documentParser.parse(object, message.getFileName());

            // 4. Split into chunks
            var segments = documentSplitter.split(text, documentId.toString());

            // 5. Embed all chunks via LangChain4j EmbeddingModel
            List<Embedding> embeddings = embeddingModel.embedAll(
                    segments.stream().map(TextSegment::text).toList()
            ).content();

            // 6. Store vectors in Milvus via LangChain4j EmbeddingStore
            List<String> vectorIds = embeddingStore.addAll(embeddings, segments);

            // 7. Save chunks to PG via MyBatis-Plus
            for (int i = 0; i < segments.size(); i++) {
                Chunk zlChunk = Chunk.builder()
                        .docId(documentId)
                        .content(segments.get(i).text())
                        .embeddingId(vectorIds.get(i))
                        .metadata("{\"index\": " + i + ", \"fileName\": \"" + message.getFileName() + "\"}")
                        .build();
                chunkMapper.insert(zlChunk);
            }

            // 8. Update document status to COMPLETED
            doc.setStatus(DocumentStatusEnum.COMPLETED.getStatus());
            doc.setChunkCount(segments.size());
            documentMapper.updateById(doc);

            log.info("Document {} processed successfully: {} chunks", documentId, segments.size());
        } catch (Exception e) {
            log.error("Error processing document {}: {}", documentId, e.getMessage(), e);
            doc.setStatus(DocumentStatusEnum.FAILED.getStatus());
            documentMapper.updateById(doc);
            throw e;
        }
    }
}
```

---

### Task 6: DocumentController（上传 + 状态查询）

**Files:**
- Verify: `zhiliao-ingestion/.../controller/DocumentController.java`
- Verify: `zhiliao-ingestion/.../model/DocumentMessage.java`
- Verify: `zhiliao-ingestion/.../vo/response/DocumentRespVO.java`

**DocumentController** 已实现：

- `POST /api/documents/upload` — 接收 MultipartFile → 调用 DocumentService → 返回 DocumentRespVO
- `GET /api/documents/{id}` — 查询文档状态 → 返回 DocumentRespVO

**DocumentMessage** 已实现（RabbitMQ 消息体：documentId, minioKey, fileName）

**DocumentRespVO** 已实现（响应体：id, fileName, fileType, status, fileSize, chunkCount, createdAt）

---

### Task 7: zhiliao-retrieval 实现 KnowledgeRetrievalTool

**Files:**
- **Delete (旧接口，已不在代码库):** `zhiliao-retrieval/.../service/EmbeddingService.java`
- **Delete (旧接口，已不在代码库):** `zhiliao-retrieval/.../service/VectorStore.java`
- **Delete (旧接口，已不在代码库):** `zhiliao-retrieval/.../service/RetrieverService.java`
- **Create:** `zhiliao-retrieval/.../tools/KnowledgeRetrievalTool.java`

- [x] **Step 1: 确认唯一的活跃类是 KnowledgeRetrievalTool**

MVP 采用 Tool-based 检索方案。`KnowledgeRetrievalTool` 是一个 `@Component`，通过 `@Tool` 注解暴露给 ChatService，由 LLM 自主决定是否调用。

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalTool {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> milvusEmbeddingStore;

    @Tool("检索企业知识库：查找公司制度、政策、流程、产品信息等企业内部知识。仅当用户明确询问企业内部知识时调用，日常闲聊无需调用")
    public String retrieveKnowledge(@P("查询内容") String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = milvusEmbeddingStore.search(request);
        // 无匹配返回空字符串，LLM 据此告知用户
        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        if (matches.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) sb.append("\n---\n");
            sb.append(matches.get(i).embedded().text());
        }
        return sb.toString();
    }
}
```

- [x] **Step 2: ChatService 注入 KnowledgeRetrievalTool**

`ChatService.java` 通过 `tools = {"knowledgeRetrievalTool"}` 注册工具，LangChain4j 自动发现并注入。

- [x] **Step 3: System Prompt 增加工具调用指令**

`system-prompt.md` 指导 LLM 何时调用检索工具，替代传统的 EmbeddingQueryRouter 意图路由。

---

### Task 8: zhiliao-chat 模块确认

**Files:**
- Verify: `zhiliao-chat/.../config/ChatMemoryConfig.java`
- Verify: `zhiliao-chat/.../controller/ChatController.java`
- Verify: `zhiliao-chat/.../repository/CustomChatMemoryStore.java`
- Verify: `zhiliao-chat/.../service/ChatService.java`
- Verify: `zhiliao-chat/src/main/resources/system-prompt.md`

**ChatMemoryConfig** — `MessageWindowChatMemory`（20条窗口），`ChatMemoryProvider` 使用 `CustomChatMemoryStore`（Redis，TTL 1天）

**ChatController** — `GET /chat?memoryId=X&message=Y`，`produces = "text/html;charset=utf-8"`，返回 `Flux<String>`

**CustomChatMemoryStore** — 通过 `StringRedisTemplate` 读写，`ChatMessageSerializer` 序列化/反序列化

**ChatService** — `@AiService` 接口，显式装配模式，注入 `knowledgeRetrievalTool`：
```java
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemory = "chatMemory",
        chatMemoryProvider = "chatMemoryProvider",
        tools = {"knowledgeRetrievalTool"})
public interface ChatService {
    @SystemMessage(fromResource = "system-prompt.md")
    Flux<String> chat(@MemoryId String memoryId, @UserMessage String userMessage);
}
```

- [x] **Step 1: 确认 System Prompt 包含工具调用指令**

`system-prompt.md`：
```markdown
你是一个企业知识库助手，名叫知了知了，负责答疑。

你拥有一个知识检索工具 `retrieveKnowledge`，当用户询问公司内部知识时调用它来获取相关信息。
同时你也可以进行日常闲聊。

### 规则

1. **知识问答**：当用户询问公司制度、政策、流程、产品信息等企业内部知识时，
   调用 `retrieveKnowledge` 工具进行检索，仅根据检索返回的内容回答。
   如果返回内容为空，明确说明"知识库中未找到相关信息"。禁止编造不存在的内容。
2. **自由对话**：日常闲聊、问候、感谢、自我介绍等不需要检索的内容，直接回答，
   **不要调用** `retrieveKnowledge` 工具。
```

---

### Task 9: zhiliao-auth 模块（MyBatis-Plus + JWT）

**Files:**
- Verify: `zhiliao-auth/.../config/SecurityConfig.java`
- Verify: `zhiliao-auth/.../entity/SysUser.java`
- Verify: `zhiliao-auth/.../mapper/SysUserMapper.java`
- Verify: `zhiliao-auth/.../service/UserService.java`
- Verify: `zhiliao-auth/.../service/impl/UserServiceImpl.java`
- Verify: `zhiliao-auth/.../filter/JwtFilter.java`
- Verify: `zhiliao-auth/.../controller/AuthController.java`
- Verify: `zhiliao-common/.../model/CurrentUser.java`
- Verify: `zhiliao-common/.../utils/UserContextHolder.java`
- Verify: `zhiliao-common/.../utils/JwtUtil.java`

**SecurityConfig** — `@Bean PasswordEncoder` (BCryptPasswordEncoder)

**SysUser 实体** — MyBatis-Plus 注解（`@TableName("sys_user")`, `@TableId`, `@TableField`, `@Builder`），含 id/username/passwordHash/deptId/role/tenantId/createdAt

**SysUserMapper** — `extends BaseMapper<SysUser>`

**UserService 接口 + UserServiceImpl**：
```java
public interface UserService {
    User authenticate(String username, String password);
}

// UserServiceImpl:
// UserMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username))
// PasswordEncoder.matches(password, user.getPasswordHash())
// 校验失败抛 IllegalArgumentException
```

**JwtFilter** — `Filter` 实现，`@Order(1)`，从 `Authorization: Bearer <token>` 解析 → `UserContextHolder.set(user)`

**AuthController** — `POST /api/auth/login` 返回 `{ token: "..." }`

**JwtUtil** — JJWT 签发/解析/验证，HMAC-SHA256，secret/expiration 通过 `@Value` 注入（`${zhiliao.jwt.secret}` / `${zhiliao.jwt.expiration-ms}`），均有 fallback 默认值，非必需在 application.yaml 中配置

**UserContextHolder** — ThreadLocal，set/get/clear 当前用户

**CurrentUser** — record `(Long id, String username, Long deptId)`

---

### Task 10: 端到端验证

- [x] **Step 1: 启动基础设施**

```bash
docker compose -f docker/local-dev.yml up -d
```

验证各服务健康状态。

- [x] **Step 2: 启动应用**

```bash
DEEPSEEK_API_KEY=your_key QWEN_API_KEY=your_key RESOURCE_SERVER_HOST=localhost RESOURCE_SERVER_PASSWORD=your_password mvn spring-boot:run -pl zhiliao-app
```

- [x] **Step 3: 登录获取 Token**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

- [x] **Step 4: 上传文档**

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@test.pdf" \
  -F "kbId=1"
```

- [x] **Step 5: 查询文档状态**

```bash
curl http://localhost:8080/api/documents/1 \
  -H "Authorization: Bearer <token>"
```

- [x] **Step 6: 对话测试**

```bash
curl "http://localhost:8080/chat/chat?memoryId=test1&message=文档里说了什么" \
  -H "Authorization: Bearer <token>"
```

期待：流式返回答案，带来源标注 [来源：xxx.pdf]。
