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
    zhiliao-ingestion/.../entity/Document.java                  (已存在)
    zhiliao-ingestion/.../entity/Chunk.java                     (已存在)
    zhiliao-ingestion/.../enums/DocumentStatusEnum.java         (已存在)
  持久层:
    zhiliao-ingestion/.../mapper/DocumentMapper.java            (已存在)
    zhiliao-ingestion/.../mapper/ChunkMapper.java               (已存在)
    zhiliao-ingestion/.../config/MyBatisPlusConfig.java         (已存在)
    zhiliao-ingestion/.../config/JsonbTypeHandler.java          (已存在)
  服务层:
    zhiliao-ingestion/.../service/DocumentService.java          (已存在)
    zhiliao-ingestion/.../service/impl/DocumentServiceImpl.java (已存在)
    zhiliao-ingestion/.../service/DocumentParser.java           (已存在)
    zhiliao-ingestion/.../service/impl/TikaDocumentParserImpl.java (已存在)
    zhiliao-ingestion/.../service/DocumentSplitter.java         (已存在)
    zhiliao-ingestion/.../service/impl/RecursiveDocumentSplitterImpl.java (已存在)
  配置层:
    zhiliao-ingestion/.../config/MinIOConfig.java               (已存在)
    zhiliao-ingestion/.../config/RabbitMQConfig.java            (已存在)
    zhiliao-ingestion/.../config/IngestionConfig.java           (已存在)
  消息层:
    zhiliao-ingestion/.../model/DocumentMessage.java            (已存在)
  Controller + Consumer:
    zhiliao-ingestion/.../controller/DocumentController.java    (已存在)
    zhiliao-ingestion/.../consumer/DocumentConsumer.java         (已存在)
    zhiliao-ingestion/.../consumer/DocumentConsumerProcessor.java (已存在，需补全处理逻辑)

Phase 3 - 检索查询:
  zhiliao-retrieval/.../config/RetrievalConfig.java             (已存在)
  (删除已注释的旧接口和实现: EmbeddingService, VectorStore, RetrieverService 等)

Phase 4 - 对话增强 + JWT:
  zhiliao-chat/src/main/resources/system-prompt.md              (修改)
  zhiliao-chat/.../repository/CustomChatMemoryStore.java        (已存在，使用 Redis)
  zhiliao-chat/.../config/ChatMemoryConfig.java                 (已存在)
  zhiliao-chat/.../service/ChatService.java                     (已存在)
  zhiliao-chat/.../controller/ChatController.java               (已存在)
  zhiliao-common/.../model/CurrentUser.java                     (已存在)
  zhiliao-common/.../utils/UserContextHolder.java               (已存在)
  zhiliao-common/.../utils/JwtUtil.java                         (已存在)
  zhiliao-auth/.../config/SecurityConfig.java                   (已存在)
  zhiliao-auth/.../filter/JwtFilter.java                        (已存在)
  zhiliao-auth/.../controller/AuthController.java               (已存在)
  zhiliao-auth/.../service/UserService.java                     (已存在)
  zhiliao-auth/.../service/impl/UserServiceImpl.java            (已存在)
  zhiliao-auth/.../entity/User.java                             (已存在)
  zhiliao-auth/.../mapper/UserMapper.java                       (已存在)
```

---

### Task 1: Docker Compose + 数据库 DDL

**Files:**
- Create: `docker/local-dev.yml`
- Create: `zhiliao-app/src/main/resources/sql/schema.sql`
- Create: `zhiliao-app/src/main/resources/sql/data.sql`
- Modify: `zhiliao-app/src/main/resources/application.yaml`

- [x] **Step 1: 创建 Docker Compose 配置文件**

`docker/local-dev.yml`:

```yaml
version: "3.8"
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: zhiliao
      POSTGRES_USER: zhiliao
      POSTGRES_PASSWORD: zhiliao123
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U zhiliao"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis-stack:
    image: redis/redis-stack-server:latest
    ports:
      - "6379:6379"
      - "8001:8001"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5

  minio:
    image: minio/minio:latest
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: zhiliao
      MINIO_ROOT_PASSWORD: zhiliao123
    command: server /data --console-address ":9001"
    volumes:
      - miniodata:/data
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/minio/health/live"]
      interval: 5s
      timeout: 5s
      retries: 5

  rabbitmq:
    image: rabbitmq:3.13-management
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: zhiliao
      RABBITMQ_DEFAULT_PASS: zhiliao123
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5

  milvus:
    image: milvusdb/milvus:latest
    ports:
      - "19530:19530"
      - "9091:9091"
    environment:
      ETCD_USE_EMBED: "true"
      COMMON_STORAGE_TYPE: local
    volumes:
      - milvusdata:/var/lib/milvus

volumes:
  pgdata:
  miniodata:
  milvusdata:
```

- [x] **Step 2: 创建数据库 DDL**

`zhiliao-app/src/main/resources/sql/schema.sql`:

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE departments (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    parent_id   BIGINT REFERENCES departments(id),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(50) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    dept_id         BIGINT NOT NULL DEFAULT 1 REFERENCES departments(id),
    role            VARCHAR(20) NOT NULL DEFAULT 'USER',
    tenant_id       VARCHAR(50) NOT NULL DEFAULT 'default',
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE knowledge_bases (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    tenant_id   VARCHAR(50) NOT NULL DEFAULT 'default',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE documents (
    id          BIGSERIAL PRIMARY KEY,
    kb_id       BIGINT NOT NULL REFERENCES knowledge_bases(id),
    file_name   VARCHAR(500) NOT NULL,
    file_type   VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'UPLOADED',
    minio_key   VARCHAR(500),
    file_size   BIGINT DEFAULT 0,
    md5         VARCHAR(32),
    chunk_count INT DEFAULT 0,
    error_msg   TEXT,
    tenant_id   VARCHAR(50) NOT NULL DEFAULT 'default',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE chunks (
    id            BIGSERIAL PRIMARY KEY,
    doc_id        BIGINT NOT NULL REFERENCES documents(id),
    content       TEXT NOT NULL,
    embedding_id  VARCHAR(100),
    metadata      JSONB DEFAULT '{}',
    tenant_id     VARCHAR(50) NOT NULL DEFAULT 'default',
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE conversations (
    id            BIGSERIAL PRIMARY KEY,
    memory_id     VARCHAR(100) NOT NULL,
    user_id       BIGINT REFERENCES users(id),
    title         VARCHAR(500),
    message_count INT DEFAULT 0,
    tenant_id     VARCHAR(50) NOT NULL DEFAULT 'default',
    created_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT REFERENCES users(id),
    action      VARCHAR(100) NOT NULL,
    target_type VARCHAR(50),
    target_id   BIGINT,
    detail      JSONB DEFAULT '{}',
    tenant_id   VARCHAR(50) NOT NULL DEFAULT 'default',
    created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_status ON documents(status);
CREATE INDEX idx_documents_kb_id ON documents(kb_id);
CREATE INDEX idx_chunks_doc_id ON chunks(doc_id);
CREATE INDEX idx_conversations_memory_id ON conversations(memory_id);
```

- [x] **Step 3: 创建种子数据**

`zhiliao-app/src/main/resources/sql/data.sql`:

```sql
INSERT INTO departments (id, name) VALUES (1, '默认部门'), (2, '技术部'), (3, '产品部'), (4, '运营部');

INSERT INTO users (id, username, password_hash, dept_id, role) VALUES
    (1, 'admin',    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 2, 'ADMIN'),
    (2, 'zhangsan', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 3, 'USER'),
    (3, 'lisi',     '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 4, 'USER');
-- 密码均为 123456'，需在启动后重新生成 BCrypt hash 并替换

INSERT INTO knowledge_bases (id, name, description) VALUES
    (1, '公司知识库', '默认知识库');
```

- [x] **Step 4: 更新 application.yaml**

`zhiliao-app/src/main/resources/application.yaml`:

```yaml
server:
  port: 8080

spring:
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
      log-responses: true
    streaming-chat-model:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model-name: deepseek-v4-flash
      log-requests: true
      log-responses: true
    embedding-model:
      base-url: https://llm-kyz5903txm3fyz8f.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
      api-key: ${QWEN_API_KEY}
      model-name: text-embedding-v4
      log-requests: true
      log-responses: true
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

# zhiliao custom config
zhiliao:
  jwt:
    secret: zhiliao-jwt-secret-key-change-in-production-2026
    expiration-ms: 86400000
  retrieval:
    store-type: milvus
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

- `zhiliao-ingestion/.../entity/Document.java` — `@TableName("documents")`, `@TableId(type = IdType.AUTO)`, 含 kbId/fileName/fileType/status/minioKey/fileSize/md5/chunkCount/tenantId/createdAt
- `zhiliao-ingestion/.../entity/Chunk.java` — `@TableName("chunks")`, 含 docId/content/embeddingId/metadata(JSONB via JsonbTypeHandler)/tenantId/createdAt
- `zhiliao-ingestion/.../enums/DocumentStatusEnum.java` — UPLOADED/PROCESSING/COMPLETED/FAILED
- `zhiliao-ingestion/.../mapper/DocumentMapper.java` — `extends BaseMapper<Document>`
- `zhiliao-ingestion/.../mapper/ChunkMapper.java` — `extends BaseMapper<Chunk>`
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

### Task 7: zhiliao-retrieval 简化（删除已注释代码）

**Files:**
- **Delete (注释块):** `zhiliao-retrieval/.../service/EmbeddingService.java`
- **Delete (注释块):** `zhiliao-retrieval/.../service/VectorStore.java`
- **Delete (注释块):** `zhiliao-retrieval/.../service/RetrieverService.java`
- **Delete (注释块):** `zhiliao-retrieval/.../service/impl/DeepSeekEmbeddingService.java`
- **Delete (注释块):** `zhiliao-retrieval/.../service/impl/MilvusVectorStore.java`
- **Delete (注释块):** `zhiliao-retrieval/.../service/impl/MilvusRetriever.java`
- **Delete (注释块):** `zhiliao-retrieval/.../config/MilvusConfig.java`
- **Keep:** `zhiliao-retrieval/.../config/RetrievalConfig.java`

- [x] **Step 1: 删除已注释的旧接口和实现类**

```
zhiliao-retrieval/src/main/java/.../service/
  ├── EmbeddingService.java          ← 删除（全部注释掉）
  ├── RetrieverService.java          ← 删除（全部注释掉）
  ├── VectorStore.java               ← 删除（全部注释掉）
  └── impl/
      ├── DeepSeekEmbeddingService.java  ← 删除（全部注释掉）
      ├── MilvusRetriever.java          ← 删除（全部注释掉）
      └── MilvusVectorStore.java         ← 删除（全部注释掉）

zhiliao-retrieval/src/main/java/.../config/
  └── MilvusConfig.java              ← 删除（全部注释掉，LangChain4j 自动装配替代）
```

- [x] **Step 2: 确认唯一的活跃类—RetrievalConfig**

```java
package org.liar.zhiliao.retrieval.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@AllArgsConstructor
public class RetrievalConfig {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Bean("contentRetriever")
    public ContentRetriever milvusContentRetriever() {
        log.info("Initializing Milvus ContentRetriever (minScore=0.5, maxResults=5)");
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.5)
                .maxResults(5)
                .build();
    }
}
```

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

**ChatService** — `@AiService` 接口，显式装配模式：
```java
@AiService(wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemory = "chatMemory",
        chatMemoryProvider = "chatMemoryProvider",
        contentRetriever = "contentRetriever")
```

- [x] **Step 1: 确认 System Prompt 包含引用溯源指令**

`system-prompt.md`：
```markdown
你是一个企业知识库助手，名叫知了知了，负责答疑。

## 回答要求
1. 仅根据提供的文档内容回答，不要编造信息
2. 每段回答末尾标注来源文档标题，格式：[来源：xxx.pdf]
3. 如果没有检索到相关文档内容，明确说明"文档库中未找到相关信息"
4. 禁止编造文档中不存在的内容
5. 回答简洁准确，使用中文
```

---

### Task 9: zhiliao-auth 模块（MyBatis-Plus + JWT）

**Files:**
- Verify: `zhiliao-auth/.../config/SecurityConfig.java`
- Verify: `zhiliao-auth/.../entity/User.java`
- Verify: `zhiliao-auth/.../mapper/UserMapper.java`
- Verify: `zhiliao-auth/.../service/UserService.java`
- Verify: `zhiliao-auth/.../service/impl/UserServiceImpl.java`
- Verify: `zhiliao-auth/.../filter/JwtFilter.java`
- Verify: `zhiliao-auth/.../controller/AuthController.java`
- Verify: `zhiliao-common/.../model/CurrentUser.java`
- Verify: `zhiliao-common/.../utils/UserContextHolder.java`
- Verify: `zhiliao-common/.../utils/JwtUtil.java`

**SecurityConfig** — `@Bean PasswordEncoder` (BCryptPasswordEncoder)

**User 实体** — MyBatis-Plus 注解（`@TableId`, `@TableField`, `@Builder`），含 id/username/passwordHash/deptId/role/tenantId/createdAt

**UserMapper** — `extends BaseMapper<User>`

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

**JwtUtil** — JJWT 签发/解析/验证，HMAC-SHA256，属性 `zhiliao.jwt.secret` + `zhiliao.jwt.expiration-ms`

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
