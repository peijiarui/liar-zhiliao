# 知了知了 MVP 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现企业级 RAG 知识库 MVP，覆盖文档上传 → Tika 解析 → 分割 → Embedding → 双写 PG + Milvus → 向量检索 → LLM 对话的核心链路

**Architecture:** Spring Boot 单体应用 + Maven 多模块。Phase 2 完成全部写入链路（ingestion 编排 + retrieval Embedding/向量写入），Phase 3 做 Milvus 查询 + 注入 ChatService，Phase 4 做 JWT 登录和对话增强。

**Tech Stack:** Spring Boot 3.5, LangChain4j 1.17, Apache Tika, RabbitMQ, Milvus, PostgreSQL, Redis, MinIO, DeepSeek API, JJWT

---

## 文件全景

```
Phase 1 - 基础设施:
  docker/local-dev.yml                                          (新建)
  zhiliao-app/src/main/resources/sql/schema.sql                 (新建)
  zhiliao-app/src/main/resources/sql/data.sql                   (新建)
  zhiliao-app/src/main/resources/application.yaml               (修改)
  zhiliao-ingestion/pom.xml                                     (修改)
  zhiliao-retrieval/pom.xml                                     (修改)
  zhiliao-auth/pom.xml                                          (修改)
  zhiliao-common/pom.xml                                        (修改)
  zhiliao-app/pom.xml                                           (修改)

Phase 2 - 文档摄入 + 向量化:
  zhiliao-ingestion/src/main/java/.../config/IngestionConfig.java         (新建)
  zhiliao-ingestion/src/main/java/.../controller/DocumentController.java  (新建)
  zhiliao-ingestion/src/main/java/.../service/DocumentProcessor.java      (新建)
  zhiliao-ingestion/src/main/java/.../service/DocumentParser.java         (新建)
  zhiliao-ingestion/src/main/java/.../service/DocumentSplitter.java       (新建)
  zhiliao-ingestion/src/main/java/.../service/impl/TikaDocumentParser.java          (新建)
  zhiliao-ingestion/src/main/java/.../service/impl/RecursiveDocumentSplitterWrapper.java (新建)
  zhiliao-ingestion/src/main/java/.../service/async/DocumentConsumer.java (新建)
  zhiliao-ingestion/src/main/java/.../service/async/AsyncDocumentProcessor.java (新建)
  zhiliao-ingestion/src/main/java/.../repository/DocumentRepository.java  (新建)
  zhiliao-ingestion/src/main/java/.../entity/DocumentRecord.java          (新建)
  zhiliao-retrieval/src/main/java/.../service/EmbeddingService.java       (新建)
  zhiliao-retrieval/src/main/java/.../service/VectorStore.java            (新建)
  zhiliao-retrieval/src/main/java/.../service/impl/DeepSeekEmbeddingService.java (新建)
  zhiliao-retrieval/src/main/java/.../service/impl/MilvusVectorStore.java (新建)
  zhiliao-retrieval/src/main/java/.../repository/ChunkRepository.java     (新建)
  zhiliao-retrieval/src/main/java/.../entity/ChunkRecord.java             (新建)

Phase 3 - 检索查询:
  zhiliao-retrieval/src/main/java/.../config/RetrievalConfig.java         (重写)
  zhiliao-retrieval/src/main/java/.../service/impl/MilvusRetriever.java   (新建)

Phase 4 - 对话增强 + JWT:
  zhiliao-chat/src/main/resources/system-prompt.md                        (修改)
  zhiliao-common/src/main/java/.../dto/CurrentUser.java                   (新建)
  zhiliao-common/src/main/java/.../utils/UserContextHolder.java           (新建)
  zhiliao-common/src/main/java/.../utils/JwtUtil.java                     (新建)
  zhiliao-auth/src/main/java/.../config/WebConfig.java                    (新建)
  zhiliao-auth/src/main/java/.../filter/JwtFilter.java                    (新建)
  zhiliao-auth/src/main/java/.../controller/AuthController.java           (新建)
  zhiliao-auth/src/main/java/.../service/UserService.java                 (新建)
  zhiliao-auth/src/main/java/.../entity/User.java                         (新建)
```

---

### Task 1: Docker Compose + 数据库 DDL

**Files:**
- Create: `docker/local-dev.yml`
- Create: `zhiliao-app/src/main/resources/sql/schema.sql`
- Create: `zhiliao-app/src/main/resources/sql/data.sql`
- Modify: `zhiliao-app/pom.xml`
- Modify: `zhiliao-app/src/main/resources/application.yaml`

- [ ] **Step 1: 创建 Docker Compose 配置文件**

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

  redis:
    image: redis/redis-stack:latest
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

- [ ] **Step 2: 创建数据库 DDL**

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

- [ ] **Step 3: 创建种子数据**

`zhiliao-app/src/main/resources/sql/data.sql`:

```sql
INSERT INTO departments (id, name) VALUES (1, '默认部门'), (2, '技术部'), (3, '产品部'), (4, '运营部');

INSERT INTO users (id, username, password_hash, dept_id, role) VALUES
    (1, 'admin',  '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 2, 'ADMIN'),
    (2, 'zhangsan', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 3, 'USER'),
    (3, 'lisi', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy', 4, 'USER');
-- 所有用户密码均为 123456，BCrypt hash 需在 JwtUtil 实现后重新生成并替换

INSERT INTO knowledge_bases (id, name, description) VALUES
    (1, '公司知识库', '默认知识库');
```

- [ ] **Step 4: 在 application.yaml 中添加 PostgreSQL 和 RabbitMQ 配置**

`zhiliao-app/src/main/resources/application.yaml` 修改后：

```yaml
server:
  port: 8080

spring:
  application:
    name: liar-zhiliao
  datasource:
    url: jdbc:postgresql://localhost:5432/zhiliao
    username: zhiliao
    password: zhiliao123
    driver-class-name: org.postgresql.Driver
    sql:
      init:
        mode: always
        schema-locations: classpath:sql/schema.sql
        data-locations: classpath:sql/data.sql
        continue-on-error: true
  data:
    redis:
      host: localhost
      port: 6379
      password: Pjr@18556720503
  rabbitmq:
    host: localhost
    port: 5672
    username: zhiliao
    password: zhiliao123
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 100MB

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

zhiliao:
  jwt:
    secret: zhiliao-jwt-secret-key-change-in-production-2026
    expiration: 86400000
  minio:
    endpoint: http://localhost:9000
    access-key: zhiliao
    secret-key: zhiliao123
    bucket: zhiliao-docs
  milvus:
    host: localhost
    port: 19530
    collection: zhiliao_chunks
    dimension: 1024

logging:
  level:
    dev.langchain4j: debug
```

- [ ] **Step 5: 在 zhiliao-app/pom.xml 添加 PostgreSQL JDBC 和 RabbitMQ 依赖**

```xml
<!-- 在 zhiliao-app/pom.xml <dependencies> 中添加 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 6: 配置 MinIO Client Bean**

在 `zhiliao-ingestion` 模块中创建配置类读取 `zhiliao.minio.*` 属性并创建 `MinioClient` Bean。

`zhiliao-ingestion/src/main/java/.../config/MinioConfig.java`:

```java
package org.liar.zhiliao.ingestion.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @ConfigurationProperties(prefix = "zhiliao.minio")
    public record MinioProperties(
            String endpoint,
            String accessKey,
            String secretKey,
            String bucket
    ) {}
}
```

---

### Task 2: zhiliao-ingestion 模块依赖 + 实体类

**Files:**
- Modify: `zhiliao-ingestion/pom.xml`
- Create: `zhiliao-ingestion/src/main/java/.../entity/DocumentRecord.java`

- [ ] **Step 1: 更新 zhiliao-ingestion/pom.xml**

```xml
<dependencies>
    <dependency>
        <groupId>org.liar.ai</groupId>
        <artifactId>zhiliao-common</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <!-- Apache Tika -->
    <dependency>
        <groupId>org.apache.tika</groupId>
        <artifactId>tika-core</artifactId>
        <version>3.1.0</version>
    </dependency>
    <dependency>
        <groupId>org.apache.tika</groupId>
        <artifactId>tika-parsers-standard-package</artifactId>
        <version>3.1.0</version>
    </dependency>
    <!-- MinIO -->
    <dependency>
        <groupId>io.minio</groupId>
        <artifactId>minio</artifactId>
        <version>8.5.17</version>
    </dependency>
    <!-- LangChain4j (for DocumentSplitter) -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-core</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
</dependencies>
```

- [ ] **Step 2: 创建 DocumentRecord 实体**

`zhiliao-ingestion/src/main/java/.../entity/DocumentRecord.java`:

```java
package org.liar.zhiliao.ingestion.entity;

import java.time.LocalDateTime;

public record DocumentRecord(
        Long id,
        Long kbId,
        String fileName,
        String fileType,
        String status,
        String minioKey,
        Long fileSize,
        String md5,
        Integer chunkCount,
        String errorMsg,
        String tenantId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
```

---

### Task 3: DocumentRepository + DocumentParser + DocumentSplitter 接口和实现

**Files:**
- Create: `zhiliao-ingestion/src/main/java/.../repository/DocumentRepository.java`
- Create: `zhiliao-ingestion/src/main/java/.../service/DocumentParser.java`
- Create: `zhiliao-ingestion/src/main/java/.../service/impl/TikaDocumentParser.java`
- Create: `zhiliao-ingestion/src/main/java/.../service/DocumentSplitter.java`
- Create: `zhiliao-ingestion/src/main/java/.../service/impl/RecursiveDocumentSplitterWrapper.java`

- [ ] **Step 1: 创建 DocumentRepository**

`zhiliao-ingestion/src/main/java/.../repository/DocumentRepository.java`:

```java
package org.liar.zhiliao.ingestion.mapper;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.ingestion.entity.DocumentRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DocumentRepository {

    private final JdbcTemplate jdbc;

    public long insert(DocumentRecord doc) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO documents (kb_id, file_name, file_type, status, minio_key, file_size, md5, tenant_id) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, doc.kbId());
            ps.setString(2, doc.fileName());
            ps.setString(3, doc.fileType());
            ps.setString(4, doc.status());
            ps.setString(5, doc.minioKey());
            ps.setLong(6, doc.fileSize());
            ps.setString(7, doc.md5());
            ps.setString(8, doc.tenantId() != null ? doc.tenantId() : "default");
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void updateStatus(long id, String status, String errorMsg, Integer chunkCount) {
        jdbc.update("UPDATE documents SET status=?, error_msg=?, chunk_count=COALESCE(?, chunk_count), updated_at=? WHERE id=?",
                status, errorMsg, chunkCount, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    public Optional<DocumentRecord> findById(long id) {
        var list = jdbc.query(
                "SELECT id, kb_id, file_name, file_type, status, minio_key, file_size, md5, chunk_count, error_msg, tenant_id, created_at, updated_at FROM documents WHERE id=?",
                (rs, row) -> new DocumentRecord(
                        rs.getLong("id"), rs.getLong("kb_id"),
                        rs.getString("file_name"), rs.getString("file_type"),
                        rs.getString("status"), rs.getString("minio_key"),
                        rs.getLong("file_size"), rs.getString("md5"),
                        rs.getInt("chunk_count"), rs.getString("error_msg"),
                        rs.getString("tenant_id"),
                        rs.getTimestamp("created_at").toLocalDateTime(),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
```

- [ ] **Step 2: 创建 DocumentParser 接口**

`zhiliao-ingestion/src/main/java/.../service/DocumentParser.java`:

```java
package org.liar.zhiliao.ingestion.service;

import java.io.InputStream;

public interface DocumentParser {
    /** 解析文档，返回提取的纯文本。解析失败返回 null。 */
    String parse(String fileName, InputStream inputStream);
}
```

- [ ] **Step 3: 创建 TikaDocumentParser 实现**

`zhiliao-ingestion/src/main/java/.../service/impl/TikaDocumentParser.java`:

```java
package org.liar.zhiliao.ingestion.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.liar.zhiliao.ingestion.service.DocumentParser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Service
public class TikaDocumentParser implements DocumentParser {

    private final Tika tika = new Tika();

    @Override
    public String parse(String fileName, InputStream inputStream) {
        try {
            String text = tika.parseToString(inputStream);
            if (text == null || text.trim().length() < 50) {
                log.warn("Tika 提取文本过少，文件可能为扫描件: {} (length={})", fileName, text == null ? 0 : text.length());
            }
            return text;
        } catch (TikaException | IOException e) {
            log.error("Tika 解析失败: {}", fileName, e);
            return null;
        }
    }
}
```

- [ ] **Step 4: 创建 DocumentSplitter 接口**

`zhiliao-ingestion/src/main/java/.../service/DocumentSplitter.java`:

```java
package org.liar.zhiliao.ingestion.service;

import java.util.List;

public interface DocumentSplitter {
    List<String> split(String text, String fileName);
}
```

- [ ] **Step 5: 创建 RecursiveDocumentSplitterWrapper 实现**

`zhiliao-ingestion/src/main/java/.../service/impl/RecursiveDocumentSplitterWrapper.java`:

```java
package org.liar.zhiliao.ingestion.service.impl;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.liar.zhiliao.ingestion.service.DocumentSplitter;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecursiveDocumentSplitterWrapper implements DocumentSplitter {

    @Override
    public List<String> split(String text, String fileName) {
        // 简单递归分割：按段落 → 按句子，最大 512 token ≈ 约 2000 字符
        return splitText(text, 2000, 200);
    }

    private List<String> splitText(String text, int maxChars, int overlap) {
        // 先按双换行分段
        String[] paragraphs = text.split("\\n\\s*\\n");
        java.util.ArrayList<String> chunks = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;

            if (current.length() + trimmed.length() > maxChars && current.length() > 0) {
                chunks.add(current.toString().trim());
                // overlap: 保留最后 overlap 字符
                current = new StringBuilder(
                        current.length() > overlap ? current.substring(current.length() - overlap) : "");
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(trimmed);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }
}
```

---

### Task 4: EmbeddingService + VectorStore + DeepSeek 实现

**Files:**
- Create: `zhiliao-retrieval/src/main/java/.../service/EmbeddingService.java`
- Create: `zhiliao-retrieval/src/main/java/.../service/impl/DeepSeekEmbeddingService.java`
- Create: `zhiliao-retrieval/src/main/java/.../service/VectorStore.java`
- Create: `zhiliao-retrieval/src/main/java/.../service/impl/MilvusVectorStore.java`
- Create: `zhiliao-retrieval/src/main/java/.../entity/ChunkRecord.java`
- Create: `zhiliao-retrieval/src/main/java/.../repository/ChunkRepository.java`

- [ ] **Step 1: 创建 EmbeddingService 接口**

`zhiliao-retrieval/src/main/java/.../service/EmbeddingService.java`:

```java
package org.liar.zhiliao.retrieval.service;

import java.util.List;

public interface EmbeddingService {
    float[] embed(String text);
    List<float[]> embedBatch(List<String> texts);
    int dimension();
}
```

- [ ] **Step 2: 创建 DeepSeekEmbeddingService**

通过 LangChain4j 的 EmbeddingModel（已在 application.yaml 中配置为通义千问 text-embedding-v4）来实现。这里直接封装已有的 `EmbeddingModel` Bean。

`zhiliao-retrieval/src/main/java/.../service/impl/DeepSeekEmbeddingService.java`:

```java
package org.liar.zhiliao.retrieval.service.impl;

import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.retrieval.service.EmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(name = "zhiliao.embedding.provider", havingValue = "deepseek", matchIfMissing = true)
@RequiredArgsConstructor
public class DeepSeekEmbeddingService implements EmbeddingService {

    private final EmbeddingModel embeddingModel;

    @Override
    public float[] embed(String text) {
        var response = embeddingModel.embed(text);
        return response.content().vector();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        var response = embeddingModel.embedAll(texts);
        return response.stream()
                .map(r -> r.content().vector())
                .toList();
    }

    @Override
    public int dimension() {
        return embeddingModel.dimension();
    }
}
```

- [ ] **Step 3: 创建 VectorStore 接口**

`zhiliao-retrieval/src/main/java/.../service/VectorStore.java`:

```java
package org.liar.zhiliao.retrieval.service;

import java.util.List;

public interface VectorStore {
    /** 写入向量 + 文本 + 元数据，返回每个 chunk 的向量 ID */
    List<String> store(List<String> texts, List<float[]> vectors, List<String> metadataJsons);
}
```

- [ ] **Step 4: 创建 MilvusVectorStore**

`zhiliao-retrieval/src/main/java/.../service/impl/MilvusVectorStore.java`:

```java
package org.liar.zhiliao.retrieval.service.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.retrieval.service.VectorStore;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusVectorStore implements VectorStore {

    private final MilvusClientV2 milvusClient;
    private final String collectionName;

    @Override
    public List<String> store(List<String> texts, List<float[]> vectors, List<String> metadataJsons) {
        if (texts.isEmpty()) return List.of();

        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            JsonObject row = new JsonObject();
            row.add("vector", floatArrayToJsonArray(vectors.get(i)));
            row.addProperty("text", texts.get(i));
            row.addProperty("metadata", metadataJsons.get(i));
            rows.add(row);
        }

        var req = InsertReq.builder()
                .collectionName(collectionName)
                .data(rows)
                .build();
        var resp = milvusClient.insert(req);
        List<Long> ids = (List<Long>) resp.getInsertIds();

        log.info("Milvus 写入 {} 条向量, ids: {}", ids.size(), ids);
        return ids.stream().map(String::valueOf).toList();
    }

    private static com.google.gson.JsonArray floatArrayToJsonArray(float[] arr) {
        var jsonArr = new com.google.gson.JsonArray();
        for (float v : arr) jsonArr.add(v);
        return jsonArr;
    }
}
```

- [ ] **Step 5: 创建 Milvus Client 配置**

`zhiliao-retrieval/src/main/java/.../config/MilvusConfig.java`:

```java
package org.liar.zhiliao.retrieval.config;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
public class MilvusConfig {

    @Bean
    public MilvusClientV2 milvusClient(MilvusProperties props) {
        var connectConfig = ConnectConfig.builder()
                .host(props.getHost())
                .port(props.getPort())
                .build();
        var client = new MilvusClientV2(connectConfig);
        initCollection(client, props);
        return client;
    }

    private void initCollection(MilvusClientV2 client, MilvusProperties props) {
        var hasReq = HasCollectionReq.builder().collectionName(props.getCollection()).build();
        if (client.hasCollection(hasReq)) {
            log.info("Milvus collection 已存在: {}", props.getCollection());
            return;
        }

        var fieldId = CreateCollectionReq.Field.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(true).build();
        var fieldVector = CreateCollectionReq.Field.builder()
                .name("vector").dataType(DataType.FloatVector).dimension(props.getDimension()).build();
        var fieldText = CreateCollectionReq.Field.builder()
                .name("text").dataType(DataType.VarChar).maxLength(65535).build();
        var fieldMetadata = CreateCollectionReq.Field.builder()
                .name("metadata").dataType(DataType.VarChar).maxLength(65535).build();

        var schema = CreateCollectionReq.CollectionSchema.builder()
                .build();

        var indexParam = IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        var createReq = CreateCollectionReq.builder()
                .collectionName(props.getCollection())
                .collectionSchema(schema)
                .indexParams(List.of(indexParam))
                .build();
        // 使用简化的创建方式
        client.createCollection(createReq);
        log.info("Milvus collection 创建成功: {} (dim={})", props.getCollection(), props.getDimension());
    }

    @Setter
    @Getter
    @ConfigurationProperties(prefix = "zhiliao.milvus")
    public static class MilvusProperties {
        private String host = "localhost";
        private int port = 19530;
        private String collection = "zhiliao_chunks";
        private int dimension = 1024;
    }
}
```

- [ ] **Step 6: 创建 ChunkRecord 和 ChunkRepository**

`zhiliao-retrieval/src/main/java/.../entity/ChunkRecord.java`:

```java
package org.liar.zhiliao.retrieval.entity;

import java.time.LocalDateTime;

public record ChunkRecord(
        Long id,
        Long docId,
        String content,
        String embeddingId,
        String metadata,
        String tenantId,
        LocalDateTime createdAt
) {}
```

`zhiliao-retrieval/src/main/java/.../repository/ChunkRepository.java`:

```java
package org.liar.zhiliao.retrieval.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChunkRepository {

    private final JdbcTemplate jdbc;

    public void batchInsert(List<Object[]> rows) {
        jdbc.batchUpdate(
                "INSERT INTO chunks (doc_id, content, embedding_id, metadata, tenant_id) VALUES (?,?,?,?::jsonb,?)",
                rows);
    }

    public void deleteByDocId(long docId) {
        jdbc.update("DELETE FROM chunks WHERE doc_id=?", docId);
    }
}
```

---

### Task 5: RabbitMQ 异步消费者 + DocumentProcessor

**Files:**
- Create: `zhiliao-ingestion/src/main/java/.../service/DocumentProcessor.java`
- Create: `zhiliao-ingestion/src/main/java/.../service/async/DocumentConsumer.java`
- Create: `zhiliao-ingestion/src/main/java/.../service/async/AsyncDocumentProcessor.java`

- [ ] **Step 1: 创建 DocumentProcessor 接口**

`zhiliao-ingestion/src/main/java/.../service/DocumentProcessor.java`:

```java
package org.liar.zhiliao.ingestion.service;

public interface DocumentProcessor {
    void process(Long documentId);
}
```

- [ ] **Step 2: 创建 AsyncDocumentProcessor**

`zhiliao-ingestion/src/main/java/.../service/async/AsyncDocumentProcessor.java`:

```java
package org.liar.zhiliao.ingestion.service.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.entity.DocumentRecord;
import org.liar.zhiliao.ingestion.mapper.DocumentRepository;
import org.liar.zhiliao.ingestion.service.DocumentParser;
import org.liar.zhiliao.ingestion.service.DocumentProcessor;
import org.liar.zhiliao.ingestion.service.DocumentSplitter;
import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.liar.zhiliao.retrieval.service.EmbeddingService;
import org.liar.zhiliao.retrieval.service.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncDocumentProcessor implements DocumentProcessor {

    private final DocumentRepository documentRepository;
    private final DocumentParser documentParser;
    private final DocumentSplitter documentSplitter;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final ChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;
    private final io.minio.MinioClient minioClient;
    @org.springframework.beans.factory.annotation.Value("${zhiliao.minio.bucket}")
    private String minioBucket;

    @Override
    public void process(Long documentId) {
        DocumentRecord doc = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("文档不存在: " + documentId));

        try {
            // 1. 从 MinIO 读取文件
            String text;
            var getArgs = io.minio.GetObjectArgs.builder()
                    .bucket(minioBucket)
                    .object(doc.minioKey())
                    .build();
            try (java.io.InputStream minioInput = minioClient.getObject(getArgs)) {
                // 2. Tika 解析
                text = documentParser.parse(doc.fileName(), minioInput);
            }
            if (text == null) {
                documentRepository.updateStatus(documentId, "FAILED", "解析失败: 文本为空", null);
                return;
            }

            // 3. 分割
            List<String> chunks = documentSplitter.split(text, doc.fileName());
            if (chunks.isEmpty()) {
                documentRepository.updateStatus(documentId, "FAILED", "分割结果为空", null);
                return;
            }

            // 4. Embedding
            List<float[]> vectors = embeddingService.embedBatch(chunks);

            // 5. 写入 Milvus
            List<String> metadataJsons = chunks.stream()
                    .map(c -> {
                        try {
                            return objectMapper.writeValueAsString(
                                    java.util.Map.of("docId", documentId, "fileName", doc.fileName()));
                        } catch (Exception e) {
                            return "{}";
                        }
                    })
                    .toList();
            List<String> vectorIds = vectorStore.store(chunks, vectors, metadataJsons);

            // 6. 写入 PG chunks
            List<Object[]> pgRows = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new Object[]{
                            documentId, chunks.get(i), vectorIds.get(i),
                            metadataJsons.get(i), "default"
                    })
                    .toList();
            chunkRepository.batchInsert(pgRows);

            // 7. 更新状态
            documentRepository.updateStatus(documentId, "COMPLETED", null, chunks.size());
            log.info("文档处理完成: id={}, chunks={}", documentId, chunks.size());

        } catch (Exception e) {
            log.error("文档处理失败: id={}", documentId, e);
            documentRepository.updateStatus(documentId, "FAILED", e.getMessage(), null);
        }
    }
}
```

- [ ] **Step 3: 创建 DocumentConsumer（RabbitMQ 监听）**

`zhiliao-ingestion/src/main/java/.../service/async/DocumentConsumer.java`:

```java
package org.liar.zhiliao.ingestion.service.async;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.service.DocumentProcessor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentConsumer {

    private final DocumentProcessor documentProcessor;

    @RabbitListener(queues = "${zhiliao.rabbitmq.queue:zhiliao.document.process}")
    public void handleMessage(Map<String, Object> message) {
        Long documentId = Long.valueOf(message.get("documentId").toString());
        log.info("收到文档处理消息: documentId={}", documentId);
        documentProcessor.process(documentId);
    }
}
```

- [ ] **Step 4: 创建 RabbitMQ 配置**

`zhiliao-ingestion/src/main/java/.../config/RabbitConfig.java`:

```java
package org.liar.zhiliao.ingestion.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "zhiliao.direct";
    public static final String QUEUE = "zhiliao.document.process";
    public static final String ROUTING_KEY = "document.process";

    @Bean
    public DirectExchange exchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Queue queue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding binding(DirectExchange exchange, Queue queue) {
        return BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);
    }
}
```

---

### Task 6: DocumentController（上传 + 状态查询）

**Files:**
- Create: `zhiliao-ingestion/src/main/java/.../controller/DocumentController.java`
- Modify: `zhiliao-ingestion/src/main/java/.../config/IngestionConfig.java`

- [ ] **Step 1: 创建 DocumentController**

`zhiliao-ingestion/src/main/java/.../controller/DocumentController.java`:

```java
package org.liar.zhiliao.ingestion.controller;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.ingestion.entity.DocumentRecord;
import org.liar.zhiliao.ingestion.mapper.DocumentRepository;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

import static org.liar.zhiliao.ingestion.config.RabbitConfig.EXCHANGE;
import static org.liar.zhiliao.ingestion.config.RabbitConfig.ROUTING_KEY;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentRepository documentRepository;
    private final RabbitTemplate rabbitTemplate;

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "kbId", defaultValue = "1") Long kbId) {

        String originalName = file.getOriginalFilename();
        String ext = originalName != null && originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf(".") + 1).toLowerCase()
                : "unknown";

        // 保存到 MinIO（TODO: 实际 MinIO 写入，目前直接跳过）
        String minioKey = "docs/" + kbId + "/" + UUID.randomUUID() + "/" + originalName;

        DocumentRecord doc = new DocumentRecord(
                null, kbId, originalName, ext, "UPLOADED",
                minioKey, file.getSize(), null, null, null, "default", null, null);
        long docId = documentRepository.insert(doc);

        // 更新状态为 PROCESSING
        documentRepository.updateStatus(docId, "PROCESSING", null, null);

        // 投递 RabbitMQ
        rabbitTemplate.convertAndSend(EXCHANGE, ROUTING_KEY,
                Map.of("documentId", docId, "minioKey", minioKey, "fileName", originalName));

        return ResponseEntity.ok(Map.of(
                "documentId", docId,
                "status", "PROCESSING",
                "message", "文档已加入处理队列"
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getDocument(@PathVariable Long id) {
        return documentRepository.findById(id)
                .map(doc -> ResponseEntity.ok(Map.of(
                        "id", doc.id(),
                        "fileName", doc.fileName(),
                        "status", doc.status(),
                        "chunkCount", doc.chunkCount() != null ? doc.chunkCount() : 0,
                        "errorMsg", doc.errorMsg(),
                        "createdAt", doc.createdAt()
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
```

---

### Task 7: 更新 zhiliao-retrieval pom.xml 依赖

**Files:**
- Modify: `zhiliao-retrieval/pom.xml`

- [ ] **Step 1: 更新 pom.xml 添加 Milvus JDBC 和 Spring AMQP 依赖

`zhiliao-retrieval/pom.xml`:

```xml
<dependencies>
    <dependency>
        <groupId>org.liar.ai</groupId>
        <artifactId>zhiliao-common</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-easy-rag</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-milvus</artifactId>
    </dependency>
    <!-- Milvus SDK -->
    <dependency>
        <groupId>io.milvus</groupId>
        <artifactId>milvus-sdk-java</artifactId>
        <version>2.5.5</version>
    </dependency>
    <!-- Spring JDBC -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <!-- Jackson -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
</dependencies>
```

---

### Task 8: MilvusRetriever + RetrievalConfig 查询升级

**Files:**
- Create: `zhiliao-retrieval/src/main/java/.../service/impl/MilvusRetriever.java`
- Rewrite: `zhiliao-retrieval/src/main/java/.../config/RetrievalConfig.java`

- [ ] **Step 1: 创建 MilvusRetriever**

`zhiliao-retrieval/src/main/java/.../service/impl/MilvusRetriever.java`:

```java
package org.liar.zhiliao.retrieval.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.retrieval.service.EmbeddingService;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusRetriever implements ContentRetriever {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final String collectionName;

    @Override
    public List<Content> retrieve(Query query) {
        // 1. Embedding 用户问题
        float[] vector = embeddingService.embed(query.text());

        // 2. 搜索 Milvus
        var searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .data(List.of(new FloatVec(vector)))
                .topK(5)
                .outputFields(List.of("text", "metadata"))
                .build();
        SearchResp resp = milvusClient.search(searchReq);

        // 3. 解析结果
        List<Content> contents = resp.getSearchResults().get(0).stream()
                .map(r -> {
                    String text = (String) r.getEntity().get("text");
                    return Content.from(TextSegment.from(text));
                })
                .toList();

        log.info("Milvus 检索结果: query={}, hits={}", query.text(), contents.size());
        return contents;
    }
}
```

- [ ] **Step 2: 重写 RetrievalConfig**

`zhiliao-retrieval/src/main/java/.../config/RetrievalConfig.java`:

```java
package org.liar.zhiliao.retrieval.config;

import io.milvus.v2.client.MilvusClientV2;
import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.retrieval.service.impl.MilvusRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RetrievalConfig {

    private final MilvusRetriever milvusRetriever;

    @Bean
    public dev.langchain4j.rag.content.retriever.ContentRetriever contentRetriever() {
        return milvusRetriever;
    }
}
```

---

### Task 9: 更新 zhiliao-retrieval 的 MilvusRetriever Bean 注入

需要在 `MilvusRetriever` 中注入 `collectionName`，通过构造函数注入配置属性。

- [ ] **Step 1: 修改 MilvusRetriever 构造函数**

在 `MilvusRetriever` 类中添加 `@Value` 注入 collection 名称。或者直接在 `RetrievalConfig` 中手动构造。

简化方案：将 `MilvusRetriever` 改为在 `RetrievalConfig` 中通过 `@Bean` 方法创建：

`zhiliao-retrieval/src/main/java/.../config/RetrievalConfig.java`（更新版）：

```java
@Configuration
@RequiredArgsConstructor
public class RetrievalConfig {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final MilvusConfig.MilvusProperties milvusProperties;

    @Bean
    public ContentRetriever contentRetriever() {
        return new MilvusRetriever(milvusClient, embeddingService, milvusProperties.getCollection());
    }
}
```

相应地，`MilvusRetriever` 去掉 `@Service` 注解，改用 `@RequiredArgsConstructor`：

```java
@Slf4j
@RequiredArgsConstructor
public class MilvusRetriever implements ContentRetriever {
    // ... 不变
}
```

---

### Task 10: System Prompt 更新（引用溯源 + 置信度拒答）

**Files:**
- Modify: `zhiliao-chat/src/main/resources/system-prompt.md`

- [ ] **Step 1: 更新 System Prompt**

`zhiliao-chat/src/main/resources/system-prompt.md`:

```markdown
你是一个企业知识库助手，名叫知了知了，负责答疑。

## 回答要求
1. 仅根据提供的文档内容回答，不要编造信息
2. 每段回答末尾标注来源文档标题，格式：[来源：xxx.pdf]
3. 如果没有检索到相关文档内容，明确说明"文档库中未找到相关信息"
4. 禁止编造文档中不存在的内容
5. 回答简洁准确，使用中文
```

- [ ] **Step 2: 确认 Redis ChatMemoryStore 已就绪**

`CustomChatMemoryStore.java` 已经使用 Redis 存储对话记忆，无需修改。验证：
- 通过 `StringRedisTemplate` 读写 Redis ✓
- TTL 1 天 ✓
- `updateMessages` 序列化/反序列化 ✓

---

### Task 11: JWT 工具类 + UserContextHolder

**Files:**
- Modify: `zhiliao-common/pom.xml`
- Create: `zhiliao-common/src/main/java/.../dto/CurrentUser.java`
- Create: `zhiliao-common/src/main/java/.../utils/UserContextHolder.java`
- Create: `zhiliao-common/src/main/java/.../utils/JwtUtil.java`

- [ ] **Step 1: 在 zhiliao-common/pom.xml 添加 JJWT 依赖**

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: 创建 CurrentUser record**

`zhiliao-common/src/main/java/.../dto/CurrentUser.java`:

```java
package org.liar.zhiliao.common.dto;

public record CurrentUser(
        Long id,
        String username,
        Long deptId,
        String role
) {}
```

- [ ] **Step 3: 创建 UserContextHolder**

`zhiliao-common/src/main/java/.../utils/UserContextHolder.java`:

```java
package org.liar.zhiliao.common.utils;

import org.liar.zhiliao.common.dto.CurrentUser;

public class UserContextHolder {

    private static final ThreadLocal<CurrentUser> CONTEXT = new ThreadLocal<>();

    public static void set(CurrentUser user) {
        CONTEXT.set(user);
    }

    public static CurrentUser get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
```

- [ ] **Step 4: 创建 JwtUtil**

`zhiliao-common/src/main/java/.../utils/JwtUtil.java`:

```java
package org.liar.zhiliao.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.liar.zhiliao.common.dto.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey secretKey;
    private final long expiration;

    public JwtUtil(
            @Value("${zhiliao.jwt.secret}") String secret,
            @Value("${zhiliao.jwt.expiration}") long expiration) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
    }

    public String generateToken(CurrentUser user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("deptId", user.deptId())
                .claim("role", user.role())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiration))
                .signWith(secretKey)
                .compact();
    }

    public CurrentUser parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new CurrentUser(
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("deptId", Long.class),
                claims.get("role", String.class)
        );
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
```

---

### Task 12: JwtFilter + AuthController + UserService

**Files:**
- Modify: `zhiliao-auth/pom.xml`
- Create: `zhiliao-auth/src/main/java/.../filter/JwtFilter.java`
- Create: `zhiliao-auth/src/main/java/.../config/WebConfig.java`
- Create: `zhiliao-auth/src/main/java/.../service/UserService.java`
- Create: `zhiliao-auth/src/main/java/.../controller/AuthController.java`
- Create: `zhiliao-auth/src/main/java/.../entity/User.java`

- [ ] **Step 1: 更新 zhiliao-auth/pom.xml**

```xml
<dependencies>
    <dependency>
        <groupId>org.liar.ai</groupId>
        <artifactId>zhiliao-common</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
</dependencies>
```

- [ ] **Step 2: 创建 User 实体**

`zhiliao-auth/src/main/java/.../entity/User.java`:

```java
package org.liar.zhiliao.auth.entity;

public record User(
        Long id,
        String username,
        String passwordHash,
        Long deptId,
        String role
) {}
```

- [ ] **Step 3: 创建 UserService**

`zhiliao-auth/src/main/java/.../service/UserService.java`:

```java
package org.liar.zhiliao.auth.service;

import lombok.RequiredArgsConstructor;
import org.liar.zhiliao.auth.entity.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final JdbcTemplate jdbc;

    public Optional<User> findByUsername(String username) {
        var list = jdbc.query(
                "SELECT id, username, password_hash, dept_id, role FROM users WHERE username=?",
                (rs, row) -> new User(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getLong("dept_id"),
                        rs.getString("role")),
                username);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
```

- [ ] **Step 4: 创建 AuthController**

`zhiliao-auth/src/main/java/.../controller/AuthController.java`:

```java
package org.liar.zhiliao.auth.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.auth.entity.User;
import org.liar.zhiliao.auth.service.UserService;
import org.liar.zhiliao.common.dto.CurrentUser;
import org.liar.zhiliao.common.utils.JwtUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        User user = userService.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.passwordHash())) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }

        CurrentUser currentUser = new CurrentUser(user.id(), user.username(), user.deptId(), user.role());
        String token = jwtUtil.generateToken(currentUser);

        log.info("===== 登录成功 =====\n用户: {}\nToken: {}", username, token);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "user", currentUser
        ));
    }
}
```

- [ ] **Step 5: 创建 JwtFilter**

`zhiliao-auth/src/main/java/.../filter/JwtFilter.java`:

```java
package org.liar.zhiliao.auth.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.utils.JwtUtil;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class JwtFilter implements Filter {

    private final JwtUtil jwtUtil;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        String path = req.getRequestURI();

        // 登录接口跳过
        if (path.equals("/api/auth/login")) {
            chain.doFilter(request, response);
            return;
        }

        // 从 Header 取 token
        String auth = req.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7);
            try {
                var user = jwtUtil.parseToken(token);
                UserContextHolder.set(user);
            } catch (Exception e) {
                log.warn("JWT 解析失败: {}", e.getMessage());
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }
}
```

- [ ] **Step 6: 通过 @SpringBootApplication 扫描 auth 模块**

确保 `zhiliao-auth` 被 `zhiliao-app` 的组件扫描覆盖。检查 `LiarZhiliaApplication.java` 入口：

```java
@SpringBootApplication(scanBasePackages = "org.liar.zhiliao")
public class LiarZhiliaApplication {
    public static void main(String[] args) {
        SpringApplication.run(LiarZhiliaApplication.class, args);
    }
}
```

需要添加 BCrypt 依赖。在 `zhiliao-auth/pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-crypto</artifactId>
</dependency>
```

---

### Task 13: 更新种子数据正确 BCrypt Hash

- [ ] **Step 1: 生成正确的 BCrypt Hash**

由于 `data.sql` 中的 BCrypt hash 是示例值，需要在项目启动时或在测试中生成正确的 hash，或使用在线工具生成密码 `123456` 的 BCrypt hash。

更新 `data.sql` 中的 `password_hash` 为正确的 BCrypt hash（`123456` 的 hash）：

```sql
-- 密码 123456 的 BCrypt hash（使用 BCryptPasswordEncoder 生成）
-- 实际值需运行以下代码生成：
-- System.out.println(new BCryptPasswordEncoder().encode("123456"));
```

---

### Task 14: 端到端验证

- [ ] **Step 1: 启动基础设施**

```bash
docker compose -f docker/local-dev.yml up -d
```

验证各服务健康状态。

- [ ] **Step 2: 启动应用**

```bash
DEEPSEEK_API_KEY=your_key QWEN_API_KEY=your_key mvn spring-boot:run -pl zhiliao-app
```

- [ ] **Step 3: 登录获取 Token**

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

- [ ] **Step 4: 上传文档**

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@test.pdf" \
  -F "kbId=1"
```

- [ ] **Step 5: 查询文档状态**

```bash
curl http://localhost:8080/api/documents/1 \
  -H "Authorization: Bearer <token>"
```

- [ ] **Step 6: 对话测试**

```bash
curl "http://localhost:8080/chat/chat?memoryId=test1&message=文档里说了什么" \
  -H "Authorization: Bearer <token>"
```

期待：流式返回答案，带来源标注 [来源：xxx.pdf]。
