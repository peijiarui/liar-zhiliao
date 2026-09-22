# 知识库关联、文档管理与检索权限修复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 文档管理收编为纯管理员功能（上传关联知识库、物理删除），并修复 Milvus 稠密检索不按部门/知识库过滤的越权访问漏洞。

**Architecture:** 文档接口全部迁入 `/api/admin/*`（复用现有 `AdminFilter` 管理员校验）；删除走 Milvus → PG 事务 → MinIO 的向前编排；检索权限通过会话 ID（`@MemoryId`）解析用户身份，构造 Milvus `IsIn(kbId)` metadata Filter（与 PG BM25 部门过滤同一可见性语义）；入库 metadata 补写 `kbId`，存量数据一次性重建。

**Tech Stack:** Spring Boot 3.x 多模块 Maven（MyBatis-Plus、JdbcTemplate）、LangChain4j 1.17.0-beta27（Milvus EmbeddingStore + @AiService 工具）、PostgreSQL 16、Milvus standalone、MinIO、RabbitMQ、Vue 3 + Naive UI。

**Spec:** `docs/superpowers/specs/2026-09-22-kb-permission-design.md`（实现前必读）

## Global Constraints

- 后端根目录 `/Users/liar/Java/project/liar-zhiliao`，前端项目 `/Users/liar/Java/project/ui/liar-zhiliao-ui`（注意：不是 `liar-zhiliao-ui` 在 Java/project 下）。
- Maven 测试默认被 surefire 跳过（根 pom `<skipTests>true</skipTests>`），运行测试必须加 `-DskipTests=false`。
- 模块依赖方向（不可违背）：`zhiliao-retrieval` 仅依赖 `zhiliao-common`（不能注入 auth/chat 的 bean，跨表查询用 JdbcTemplate）；`zhiliao-admin` 依赖 common+auth+ingestion+chat。
- `langchain4j.version=1.17.0-beta27`（根 pom properties L31），新增 langchain4j 依赖走根 pom dependencyManagement。
- **git 约束（项目 CLAUDE.md）**：不得主动执行 git 命令。每个任务末尾的 commit 步骤：展示建议的 commit 命令给用户，由用户自行提交或明确授权后执行。
- Milvus collection `zhiliao_chunks`（`application.yaml`），gRPC 端口 19530，9091 默认未映射。
- Access Token TTL 15min 等 auth 约束本次不改动；`AdminFilter`（@Order(2)）已拦截 `/api/admin/*` 校验 `role=ADMIN`，迁移接口无需重复校验。
- Milvus metadata 的 `kbId` 以**字符串**写入并匹配（`String.valueOf(kbId)`），避免类型不一致导致 filter 失效。
- 异常处理：业务异常抛 `org.liar.zhiliao.common.exception.BusinessException(status, message)`（如 404、400）。

---

### Task 1: zhiliao-ingestion 测试依赖 + 上传 kbId 校验

**Files:**
- Modify: `zhiliao-ingestion/pom.xml`
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImpl.java`
- Test: `zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImplTest.java`（新建，含目录）

**Interfaces:**
- Consumes: 现有 `DocumentService.upload(MultipartFile, Long)`、`ZlDocumentMapper`、`MinioClient`、`MinIOConfig.getBucket()`、`RabbitTemplate`
- Produces: `upload()` 对 `kbId == null` 抛 `BusinessException(400, "kbId 不能为空")`；kbId 不存在于 `zl_knowledge_base` 时抛 `BusinessException(400, "知识库不存在: " + kbId)`。后续 Task 4 的 controller 依赖此行为。

- [ ] **Step 1: 添加测试依赖**

在 `zhiliao-ingestion/pom.xml` 的 `<dependencies>` 末尾追加（版本由 Spring Boot parent 管理，无需写版本号）：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 写失败测试**

创建 `zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImplTest.java`：

```java
package org.liar.zhiliao.ingestion.service.impl;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.common.entity.ZlKbDeptVisibility;
import org.liar.zhiliao.common.exception.BusinessException;
import org.liar.zhiliao.common.mapper.ZlKbDeptVisibilityMapper;
import org.liar.zhiliao.ingestion.config.MinIOConfig;
import org.liar.zhiliao.ingestion.config.RabbitMQConfig;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.mapper.ZlChunkMapper;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.liar.zhiliao.ingestion.model.DocumentMessage;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionTemplate;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock MinioClient minioClient;
    @Mock MinIOConfig minIOConfig;
    @Mock ZlDocumentMapper documentMapper;
    @Mock ZlKbDeptVisibilityMapper visibilityMapper;
    @Mock RabbitTemplate rabbitTemplate;
    @Mock ZlChunkMapper chunkMapper;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock TransactionTemplate transactionTemplate;

    DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(minioClient, minIOConfig, documentMapper,
                visibilityMapper, rabbitTemplate, chunkMapper, jdbcTemplate, transactionTemplate);
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes());
    }

    @Test
    void uploadShouldRejectNullKbId() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upload(file(), null));
        assertEquals(400, ex.getStatus());
        verifyNoInteractions(minioClient, rabbitTemplate);
    }

    @Test
    void uploadShouldRejectUnknownKbId() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(9L))).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upload(file(), 9L));

        assertEquals(400, ex.getStatus());
        assertTrue(ex.getMessage().contains("知识库不存在"));
        verifyNoInteractions(minioClient, rabbitTemplate);
    }

    @Test
    void uploadShouldSaveToMinioInsertDocAndSendMq() throws Exception {
        when(minIOConfig.getBucket()).thenReturn("zhiliao");
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(1L);
        when(documentMapper.insert(any(ZlDocument.class))).thenAnswer(i -> {
            ZlDocument d = i.getArgument(0);
            d.setId(100L);
            return 1;
        });
        when(visibilityMapper.selectOne(any())).thenReturn(null);
        // 上传时无登录上下文（UserContextHolder 为空），deptId 兜底 1L
        doAnswer(inv -> {
            PutObjectArgs args = inv.getArgument(0);
            assertNotNull(args);
            return null;
        }).when(minioClient).putObject(any(PutObjectArgs.class));

        ZlDocument doc = service.upload(file(), 1L);

        assertEquals(100L, doc.getId());
        assertEquals(1L, doc.getKbId());
        assertEquals("UPLOADED", doc.getStatus());
        verify(minioClient).putObject(any(PutObjectArgs.class));
        verify(visibilityMapper).insert(any(ZlKbDeptVisibility.class));
        ArgumentCaptor<DocumentMessage> msg = ArgumentCaptor.forClass(DocumentMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY), msg.capture());
        assertEquals(100L, msg.getValue().getDocumentId());
    }
}
```

- [ ] **Step 3: 运行测试确认编译失败（构造器不存在）**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -am -DskipTests=false -Dtest=DocumentServiceImplTest`
Expected: 编译 FAIL —— `DocumentServiceImpl` 构造器参数不匹配（当前只有 5 个依赖）。

- [ ] **Step 4: 实现 kbId 校验**

修改 `DocumentServiceImpl.java`：
1. import 增加：`org.liar.zhiliao.common.exception.BusinessException`、`org.liar.zhiliao.ingestion.mapper.ZlChunkMapper`、`org.liar.zhiliao.ingestion.entity.ZlChunk`、`org.liar.zhiliao.common.event.DocumentUpdateEvent`、`org.springframework.context.ApplicationEventPublisher`、`org.springframework.jdbc.core.JdbcTemplate`、`org.springframework.transaction.TransactionTemplate`、`io.minio.RemoveObjectArgs`、`com.baomidou.mybatisplus.core.toolkit.Wrappers`、`org.liar.zhiliao.ingestion.enums.DocumentStatusEnum`、`java.util.Objects`、`java.util.Set`。
2. 字段列表（`@RequiredArgsConstructor` 自动生成构造器，与测试中 `new DocumentServiceImpl(...)` 顺序一致）改为：

```java
    private final MinioClient minioClient;
    private final MinIOConfig minIOConfig;
    private final ZlDocumentMapper documentMapper;
    private final ZlKbDeptVisibilityMapper visibilityMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ZlChunkMapper chunkMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
```

3. `upload()` 方法开头（计算 MD5 之前）插入校验：

```java
        if (kbId == null) {
            throw new BusinessException(400, "kbId 不能为空");
        }
        Long kbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zl_knowledge_base WHERE id = ?", Long.class, kbId);
        if (kbCount == null || kbCount == 0) {
            throw new BusinessException(400, "知识库不存在: " + kbId);
        }
```

注意：本任务**不改动** `upload()` 其余逻辑（Task 2 才加 delete）。为使测试通过编译，`delete`/`reprocess` 方法在本任务先不加（接口在 Task 2/3 扩展）。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -DskipTests=false -Dtest=DocumentServiceImplTest`
Expected: PASS（3 个测试）。

- [ ] **Step 6: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-ingestion/pom.xml zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImpl.java zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImplTest.java
git commit -m "feat(ingestion): 上传文档校验 kbId 必填与存在性"
```

---

### Task 2: DocumentService 物理删除编排

**Files:**
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/DocumentService.java`
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImpl.java`
- Test: `zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImplTest.java`（追加测试）

**Interfaces:**
- Consumes: Task 1 的构造器依赖（含 `ZlChunkMapper`、`TransactionTemplate`）；`EmbeddingStore<TextSegment>`（Milvus，`removeAll(Collection<String> ids)`）；`DocumentUpdateEvent(Set<Long> docIds)`；`ZlChunk.getEmbeddingId()/getDocId()/getChunkType()`。
- Produces: `DocumentService.delete(Long id)` —— 文档不存在抛 `BusinessException(404, "文档不存在: " + id)`；成功删除 Milvus 向量、PG chunk/document 行、MinIO 对象，并发布 `DocumentUpdateEvent`。Task 4 controller 依赖。

- [ ] **Step 1: 接口与失败测试**

`DocumentService.java` 增加（`listDocuments` 本任务**保留**，Task 4 再删，避免编译断裂）：

```java
    void delete(Long id);
```

测试追加到 `DocumentServiceImplTest.java`：

```java
    @Test
    void deleteShouldThrow404WhenDocumentMissing() {
        when(documentMapper.selectById(404L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.delete(404L));

        assertEquals(404, ex.getStatus());
        verifyNoInteractions(chunkMapper, transactionTemplate);
    }

    @Test
    void deleteShouldAbortWhenMilvusRemoveFails() {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(1L).minioKey("docs/1/x/a.txt").kbId(1L).build();
        when(documentMapper.selectById(1L)).thenReturn(doc);
        org.liar.zhiliao.ingestion.entity.ZlChunk child =
                org.liar.zhiliao.ingestion.entity.ZlChunk.builder()
                        .id(11L).docId(1L).chunkType("child").embeddingId("v-11").build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(child));
        doThrow(new RuntimeException("milvus down"))
                .when(milvusEmbeddingStore).removeAll(anyCollection());

        assertThrows(RuntimeException.class, () -> service.delete(1L));

        verify(documentMapper, never()).deleteById(anyLong());
        verify(chunkMapper, never()).delete(any());
    }

    @Test
    void deleteShouldRemoveVectorsPgMinioAndPublishEvent() throws Exception {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(1L).minioKey("docs/1/x/a.txt").kbId(1L).build();
        when(documentMapper.selectById(1L)).thenReturn(doc);
        org.liar.zhiliao.ingestion.entity.ZlChunk child =
                org.liar.zhiliao.ingestion.entity.ZlChunk.builder()
                        .id(11L).docId(1L).chunkType("child").embeddingId("v-11").build();
        org.liar.zhiliao.ingestion.entity.ZlChunk parent =
                org.liar.zhiliao.ingestion.entity.ZlChunk.builder()
                        .id(10L).docId(1L).chunkType("parent").embeddingId(null).build();
        when(chunkMapper.selectList(any())).thenReturn(List.of(child, parent));
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        service.delete(1L);

        verify(milvusEmbeddingStore).removeAll(eq(List.of("v-11")));
        verify(chunkMapper).delete(any());
        verify(documentMapper).deleteById(1L);
        verify(minioClient).removeObject(any(io.minio.RemoveObjectArgs.class));
        ArgumentCaptor<org.liar.zhiliao.common.event.DocumentUpdateEvent> ev =
                ArgumentCaptor.forClass(org.liar.zhiliao.common.event.DocumentUpdateEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertTrue(ev.getValue().docIds().contains(1L));
    }

    @Test
    void deleteShouldNotFailWhenMinioDeleteThrows() {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(2L).minioKey("docs/1/x/b.txt").kbId(1L).build();
        when(documentMapper.selectById(2L)).thenReturn(doc);
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(transactionTemplate).executeWithoutResult(any());
        org.mockito.Mockito.doThrow(new RuntimeException("minio down"))
                .when(minioClient).removeObject(any(io.minio.RemoveObjectArgs.class));

        assertDoesNotThrow(() -> service.delete(2L));

        verify(documentMapper).deleteById(2L);
        verify(eventPublisher).publishEvent(any(org.liar.zhiliao.common.event.DocumentUpdateEvent.class));
    }
```

测试类需追加字段与 import：字段 `@Mock dev.langchain4j.store.embedding.EmbeddingStore<dev.langchain4j.data.segment.TextSegment> milvusEmbeddingStore;`、`@Mock org.springframework.context.ApplicationEventPublisher eventPublisher;`，import `java.util.List`。`setUp()` 中构造器更新为：

```java
        service = new DocumentServiceImpl(minioClient, minIOConfig, documentMapper,
                visibilityMapper, rabbitTemplate, chunkMapper, jdbcTemplate, transactionTemplate,
                milvusEmbeddingStore, eventPublisher);
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -DskipTests=false -Dtest=DocumentServiceImplTest`
Expected: 编译 FAIL（`delete` 未定义）。

- [ ] **Step 3: 实现删除编排**

`DocumentServiceImpl`：

1. 新增字段（加在 `transactionTemplate` 之后，构造器参数顺序同步更新）：

```java
    private final EmbeddingStore<TextSegment> milvusEmbeddingStore;
    private final ApplicationEventPublisher eventPublisher;
```

import 增加 `dev.langchain4j.data.segment.TextSegment`、`dev.langchain4j.store.embedding.EmbeddingStore`、`org.springframework.amqp...`（已有）。

2. 实现：

```java
    @Override
    public void delete(Long id) {
        ZlDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在: " + id);
        }

        // 1. 收集 child chunk 的向量 ID（最难恢复的数据先删，失败则中止）
        List<ZlChunk> children = chunkMapper.selectList(Wrappers.<ZlChunk>lambdaQuery()
                .eq(ZlChunk::getDocId, id)
                .eq(ZlChunk::getChunkType, "child"));
        List<String> embeddingIds = children.stream()
                .map(ZlChunk::getEmbeddingId)
                .filter(Objects::nonNull)
                .toList();
        if (!embeddingIds.isEmpty()) {
            milvusEmbeddingStore.removeAll(embeddingIds);
        }

        // 2. PG 事务删除（chunk + document）
        transactionTemplate.executeWithoutResult(tx -> {
            chunkMapper.delete(Wrappers.<ZlChunk>lambdaQuery().eq(ZlChunk::getDocId, id));
            documentMapper.deleteById(id);
        });

        // 3. MinIO 对象尽力而为删除（残留孤儿对象无害，不阻断）
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minIOConfig.getBucket())
                    .object(doc.getMinioKey())
                    .build());
        } catch (Exception e) {
            log.warn("MinIO object delete ignored: key={}, err={}", doc.getMinioKey(), e.getMessage());
        }

        // 4. 发布文档更新事件 → 全量淘汰检索缓存
        eventPublisher.publishEvent(new DocumentUpdateEvent(Set.of(id)));
        log.info("Document {} deleted physically", id);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -DskipTests=false -Dtest=DocumentServiceImplTest`
Expected: PASS（7 个测试）。

- [ ] **Step 5: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/DocumentService.java zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImpl.java zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImplTest.java
git commit -m "feat(ingestion): 文档物理删除编排（Milvus 向量 + PG 事务 + MinIO）"
```

---

### Task 3: DocumentService.reprocess 补发 MQ

现状 `AdminDocumentController.reprocess` 只重置状态不发 MQ，消费由 RabbitMQ 驱动导致 reprocess 实际无效；且 Task 9 的存量重建依赖 reprocess 触发重新处理。

**Files:**
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/DocumentService.java`
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImpl.java`
- Test: `zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImplTest.java`（追加）

**Interfaces:**
- Consumes: `DocumentMessage.builder().documentId/minioKey/fileName`、`RabbitMQConfig.EXCHANGE/ROUTING_KEY`、`DocumentStatusEnum.UPLOADED`
- Produces: `DocumentService.reprocess(Long id)` —— 文档不存在静默返回（保持现 controller 行为）；存在则置状态 `UPLOADED` 并发 MQ。Task 4 controller 依赖。

- [ ] **Step 1: 接口与失败测试**

`DocumentService.java` 增加：

```java
    void reprocess(Long id);
```

测试追加：

```java
    @Test
    void reprocessShouldResetStatusAndSendMq() {
        org.liar.zhiliao.ingestion.entity.ZlDocument doc =
                org.liar.zhiliao.ingestion.entity.ZlDocument.builder()
                        .id(1L).minioKey("docs/1/x/a.txt").fileName("a.txt").build();
        when(documentMapper.selectById(1L)).thenReturn(doc);

        service.reprocess(1L);

        assertEquals("UPLOADED", doc.getStatus());
        verify(documentMapper).updateById(doc);
        ArgumentCaptor<DocumentMessage> msg = ArgumentCaptor.forClass(DocumentMessage.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY), msg.capture());
        assertEquals("docs/1/x/a.txt", msg.getValue().getMinioKey());
    }

    @Test
    void reprocessShouldIgnoreMissingDocument() {
        when(documentMapper.selectById(99L)).thenReturn(null);
        assertDoesNotThrow(() -> service.reprocess(99L));
        verifyNoInteractions(rabbitTemplate);
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -DskipTests=false -Dtest=DocumentServiceImplTest`
Expected: 编译 FAIL（`reprocess` 未定义）。

- [ ] **Step 3: 实现**

`DocumentServiceImpl` 追加：

```java
    @Override
    public void reprocess(Long id) {
        ZlDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            return;
        }
        doc.setStatus(DocumentStatusEnum.UPLOADED.getStatus());
        documentMapper.updateById(doc);
        DocumentMessage message = DocumentMessage.builder()
                .documentId(doc.getId())
                .minioKey(doc.getMinioKey())
                .fileName(doc.getFileName())
                .build();
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, message);
        log.info("Document {} queued for reprocess", id);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -DskipTests=false -Dtest=DocumentServiceImplTest`
Expected: PASS（9 个测试）。

- [ ] **Step 5: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/DocumentService.java zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImpl.java zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImplTest.java
git commit -m "fix(ingestion): reprocess 重置状态并重新投递 MQ"
```

---

### Task 4: AdminDocumentController 收编 + 删除 DocumentController

**Files:**
- Modify: `zhiliao-admin/src/main/java/org/liar/zhiliao/admin/controller/AdminDocumentController.java`
- Delete: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/controller/DocumentController.java`
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/DocumentService.java`（移除 `listDocuments`）
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/service/impl/DocumentServiceImpl.java`（移除 `listDocuments` 实现）

**Interfaces:**
- Consumes: `DocumentService.upload/getDocument/delete/reprocess`（Task 1-3）；`DocumentResponse.of(ZlDocument)`（`zhiliao-ingestion/.../vo/response/DocumentResponse.java`，已存在）。
- Produces: 管理员文档 REST 接口：`POST /api/admin/documents/upload`（`file` + `kbId`）、`GET /api/admin/documents/{id}`、`DELETE /api/admin/documents/{id}`、`POST /api/admin/documents/{id}/reprocess`；`GET /api/admin/documents`（分页，不变）。前端 Task 10 依赖这些 URL。

- [ ] **Step 1: 扩展 AdminDocumentController**

替换 `AdminDocumentController.java` 全文为：

```java
package org.liar.zhiliao.admin.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.ingestion.entity.ZlDocument;
import org.liar.zhiliao.ingestion.mapper.ZlDocumentMapper;
import org.liar.zhiliao.ingestion.service.DocumentService;
import org.liar.zhiliao.ingestion.vo.response.DocumentResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档管理接口（仅管理员，由 AdminFilter 校验角色）。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/admin/documents")
public class AdminDocumentController {

    private final ZlDocumentMapper documentMapper;
    private final DocumentService documentService;

    @GetMapping
    public IPage<ZlDocument> page(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) Long kbId,
                                  @RequestParam(required = false) String status) {
        var wrapper = new LambdaQueryWrapper<ZlDocument>();
        if (kbId != null) {
            wrapper.eq(ZlDocument::getKbId, kbId);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(ZlDocument::getStatus, status);
        }
        wrapper.orderByDesc(ZlDocument::getCreatedAt);
        return documentMapper.selectPage(new Page<>(page, size), wrapper);
    }

    @PostMapping("/upload")
    public DocumentResponse upload(@RequestParam("file") MultipartFile file,
                                   @RequestParam("kbId") Long kbId) {
        ZlDocument doc = documentService.upload(file, kbId);
        log.info("Document uploaded: id={}, fileName={}, status={}",
                doc.getId(), doc.getFileName(), doc.getStatus());
        return DocumentResponse.of(doc);
    }

    @GetMapping("/{id}")
    public DocumentResponse detail(@PathVariable Long id) {
        return DocumentResponse.of(documentService.getDocument(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        documentService.delete(id);
    }

    @PostMapping("/{id}/reprocess")
    public void reprocess(@PathVariable Long id) {
        documentService.reprocess(id);
    }
}
```

- [ ] **Step 2: 删除旧用户端 Controller 与 listDocuments**

1. 删除文件 `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/controller/DocumentController.java`。
2. 从 `DocumentService.java` 移除 `List<ZlDocument> listDocuments(Long kbId, Integer page, Integer pageSize);` 及 `import java.util.List;`（若无其他引用）。
3. 从 `DocumentServiceImpl.java` 移除 `listDocuments` 方法及 `Page`、`LambdaQueryWrapper` 中不再使用的 import（保留仍使用的）。

- [ ] **Step 3: 全模块编译验证**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -pl zhiliao-admin -am`
Expected: BUILD SUCCESS（admin 依赖 ingestion，一并提供验证）。

- [ ] **Step 4: 展示 commit 命令（用户执行）**

```bash
git add -A zhiliao-admin/src/main/java/org/liar/zhiliao/admin/controller/AdminDocumentController.java zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/
git commit -m "refactor(documents): 文档接口收编 /api/admin/*，移除普通用户文档接口"
```

---

### Task 5: 入库 metadata 补写 kbId

**Files:**
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessor.java:112-121`

**Interfaces:**
- Consumes: `ZlDocument.getKbId()`（`zl_document.kb_id`，NOT NULL）
- Produces: Milvus metadata 键 `kbId`（字符串值），Task 8 的 `IsIn("kbId", ...)` 过滤依赖它。新上传/重新处理的文档即带此字段。

- [ ] **Step 1: 修改 child segment 的 metadata 构造**

将 `DocumentConsumerProcessor.process()` 中第 7 步的 metadata 构造（原 L115-119）：

```java
                TextSegment segWithMeta = TextSegment.from(
                        childSegments.get(i).text(),
                        Metadata.from("chunkId", childEntity.getId().toString())
                                .put("parentId", childEntity.getParentId() != null
                                        ? childEntity.getParentId().toString() : ""));
```

改为（增加 `kbId`，字符串形式）：

```java
                TextSegment segWithMeta = TextSegment.from(
                        childSegments.get(i).text(),
                        Metadata.from("chunkId", childEntity.getId().toString())
                                .put("parentId", childEntity.getParentId() != null
                                        ? childEntity.getParentId().toString() : "")
                                .put("kbId", String.valueOf(doc.getKbId())));
```

- [ ] **Step 2: 编译验证**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -pl zhiliao-ingestion -am`
Expected: BUILD SUCCESS。

- [ ] **Step 3: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessor.java
git commit -m "feat(ingestion): Milvus 向量 metadata 补写 kbId"
```

---

### Task 6: 检索侧依赖 + 会话身份与可见 KB 查询

`zhiliao-retrieval` 只依赖 `zhiliao-common`，不能注入 auth/chat 的 bean；沿用 `ChunkRepository` 现有 JdbcTemplate 直查模式（`zl_conversation`、`sys_user`、`zl_kb_dept_visibility` 同库）。

**Files:**
- Modify: `pom.xml`（根，dependencyManagement）
- Modify: `zhiliao-retrieval/pom.xml`
- Create: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/records/RetrievalPrincipal.java`
- Modify: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/repository/ChunkRepository.java`
- Test: `zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/repository/ChunkRepositoryTest.java`（新建）

**Interfaces:**
- Consumes: `JdbcTemplate`（已注入）；表 `zl_conversation(memory_id, user_id)`、`sys_user(id, role, dept_id)`、`zl_kb_dept_visibility(kb_id, dept_id)`。
- Produces:
  - `record RetrievalPrincipal(Long userId, String role, Long deptId)`，方法 `boolean isAdmin()`（`"ADMIN".equals(role)`）。
  - `ChunkRepository.findPrincipalByMemoryId(String memoryId)` —— 会话或用户不存在返回 `null`。
  - `ChunkRepository.findVisibleKbIds(Long deptId)` —— 返回该部门可见的 kbId 列表（可能为空）。
  Task 8 依赖以上签名。

- [ ] **Step 1: 添加依赖**

根 `pom.xml` 的 `<dependencyManagement><dependencies>` 中（其他 langchain4j 条目旁）追加：

```xml
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j</artifactId>
                <version>${langchain4j.version}</version>
            </dependency>
```

`zhiliao-retrieval/pom.xml` 的 `<dependencies>` 追加（`@MemoryId` 注解在主 jar，core 中没有）：

```xml
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 写失败测试**

创建 `zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/repository/ChunkRepositoryTest.java`：

```java
package org.liar.zhiliao.retrieval.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.retrieval.records.RetrievalPrincipal;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChunkRepositoryTest {

    @Mock JdbcTemplate jdbcTemplate;
    ChunkRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ChunkRepository(jdbcTemplate);
    }

    @Test
    void findPrincipalShouldReturnPrincipalWhenSessionExists() {
        when(jdbcTemplate.query(anyString(), any(DataClassRowMapper.class), eq("conv-1")))
                .thenReturn(List.of(new RetrievalPrincipal(1L, "ADMIN", 1L)));

        RetrievalPrincipal principal = repository.findPrincipalByMemoryId("conv-1");

        assertNotNull(principal);
        assertTrue(principal.isAdmin());
        assertEquals(1L, principal.deptId());
    }

    @Test
    void findPrincipalShouldReturnNullWhenSessionMissing() {
        when(jdbcTemplate.query(anyString(), any(DataClassRowMapper.class), eq("conv-x")))
                .thenReturn(List.of());

        assertNull(repository.findPrincipalByMemoryId("conv-x"));
    }

    @Test
    void findVisibleKbIdsShouldReturnKbIds() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(2L)))
                .thenReturn(List.of(1L, 3L));

        assertEquals(List.of(1L, 3L), repository.findVisibleKbIds(2L));
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -am -DskipTests=false -Dtest=ChunkRepositoryTest`
Expected: 编译 FAIL（`RetrievalPrincipal`、方法不存在）。

- [ ] **Step 4: 实现**

创建 `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/records/RetrievalPrincipal.java`：

```java
package org.liar.zhiliao.retrieval.records;

/**
 * 检索主体：从会话 memoryId 解析出的用户身份。
 * role 取自 sys_user 实时值，避免会话创建后角色变更导致权限漂移。
 */
public record RetrievalPrincipal(Long userId, String role, Long deptId) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
```

`ChunkRepository.java` 追加（import `org.liar.zhiliao.retrieval.records.RetrievalPrincipal`）：

```java
    /** 按 memoryId 解析会话归属用户（join sys_user 取实时 role/deptId）；会话或用户不存在返回 null */
    public RetrievalPrincipal findPrincipalByMemoryId(String memoryId) {
        String sql = """
            SELECT u.id AS userId, u.role AS role, u.dept_id AS deptId
            FROM zl_conversation c
            JOIN sys_user u ON u.id = c.user_id
            WHERE c.memory_id = ?
            LIMIT 1
            """;
        List<RetrievalPrincipal> rows = jdbcTemplate.query(
                sql, new DataClassRowMapper<>(RetrievalPrincipal.class), memoryId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 部门可见的知识库 ID 集合（来自 zl_kb_dept_visibility） */
    public List<Long> findVisibleKbIds(Long deptId) {
        String sql = "SELECT kb_id FROM zl_kb_dept_visibility WHERE dept_id = ?";
        return jdbcTemplate.queryForList(sql, Long.class, deptId);
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -DskipTests=false -Dtest=ChunkRepositoryTest`
Expected: PASS（3 个测试）。

- [ ] **Step 6: 展示 commit 命令（用户执行）**

```bash
git add pom.xml zhiliao-retrieval/pom.xml zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/records/RetrievalPrincipal.java zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/repository/ChunkRepository.java zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/repository/ChunkRepositoryTest.java
git commit -m "feat(retrieval): 会话身份解析与部门可见知识库查询"
```

---

### Task 7: 稀疏检索支持 admin 全量语义

**Files:**
- Modify: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/service/impl/PgBm25Searcher.java`
- Test: `zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/service/impl/PgBm25SearcherTest.java`（新建）

**Interfaces:**
- Consumes: `ChunkRepository.searchBm25(queryText, topK)`（无过滤版，**已存在** L19-31）、`searchBm25WithDeptFilter`
- Produces: `SparseSearcher.search(query, topK, visibleDeptIds)` 语义扩展：`visibleDeptIds == null` → 不做部门过滤（admin 全量）；空列表 → 返回空（无可见部门）；非空 → 部门过滤。Task 8 传 `null` 或 `List.of(deptId)`。

- [ ] **Step 1: 写失败测试**

创建 `zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/service/impl/PgBm25SearcherTest.java`：

```java
package org.liar.zhiliao.retrieval.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PgBm25SearcherTest {

    @Mock ChunkRepository chunkRepository;
    PgBm25Searcher searcher;

    @BeforeEach
    void setUp() {
        searcher = new PgBm25Searcher(chunkRepository);
    }

    @Test
    void nullDeptIdsMeansUnfilteredSearch() {
        searcher.search("q", 10, null);
        verify(chunkRepository).searchBm25("q", 10);
        verify(chunkRepository, never()).searchBm25WithDeptFilter(anyString(), anyInt(), anyList());
    }

    @Test
    void emptyDeptIdsMeansEmptyResult() {
        var result = searcher.search("q", 10, List.of());
        assert result.isEmpty();
        verifyNoInteractions(chunkRepository);
    }

    @Test
    void nonEmptyDeptIdsMeansFilteredSearch() {
        searcher.search("q", 10, List.of(2L));
        verify(chunkRepository).searchBm25WithDeptFilter("q", 10, List.of(2L));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -DskipTests=false -Dtest=PgBm25SearcherTest`
Expected: FAIL —— `nullDeptIdsMeansUnfilteredSearch`（当前 null 走 `List.of()` 短路，`searchBm25` 从未被调用）。

- [ ] **Step 3: 实现**

`PgBm25Searcher.search` 改为：

```java
    @Override
    public List<SparseSearchResult> search(String query, int topK, List<Long> visibleDeptIds) {
        if (visibleDeptIds == null) {
            // admin：不做部门过滤
            return chunkRepository.searchBm25(query, topK);
        }
        if (visibleDeptIds.isEmpty()) {
            return List.of();
        }
        return chunkRepository.searchBm25WithDeptFilter(query, topK, visibleDeptIds);
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -DskipTests=false -Dtest=PgBm25SearcherTest`
Expected: PASS（3 个测试）。

- [ ] **Step 5: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/service/impl/PgBm25Searcher.java zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/service/impl/PgBm25SearcherTest.java
git commit -m "feat(retrieval): 稀疏检索支持 admin 无部门过滤语义"
```

---

### Task 8: KnowledgeRetrievalTool 权限过滤（@MemoryId + IsIn(kbId)）

**Files:**
- Modify: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalTool.java`
- Test: `zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalToolTest.java`（新建）

**Interfaces:**
- Consumes: Task 6 `findPrincipalByMemoryId` / `findVisibleKbIds` / `RetrievalPrincipal.isAdmin()`；Task 7 `SparseSearcher.search(query, topK, null)` 全量语义；`RetrievalCacheService.getCachedRetrieval(canonicalKey, deptSuffix)` / `putRetrieval` / `getRewrite` / `putRewrite`；`RetrievalMetrics`；`Reranker.rerank(query, allDense, allSparse, 10)`。
- Produces: 工具方法签名 `String retrieveKnowledge(@MemoryId String memoryId, @P("查询内容") String query)` —— LangChain4j 在工具调用时自动注入会话 ID（`ChatService.chat` 已有 `@MemoryId String memoryId` 参数）。这是被 `@AiService(tools={"knowledgeRetrievalTool"})` 反射发现的 public 方法，签名变更对调用方透明。

- [ ] **Step 1: 写失败测试**

创建 `zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalToolTest.java`：

```java
package org.liar.zhiliao.retrieval.tools;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.liar.zhiliao.retrieval.records.RetrievalPrincipal;
import org.liar.zhiliao.retrieval.records.RankedChunk;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;
import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.liar.zhiliao.retrieval.service.Reranker;
import org.liar.zhiliao.retrieval.service.RetrievalCacheService;
import org.liar.zhiliao.retrieval.service.RetrievalMetrics;
import org.liar.zhiliao.retrieval.service.SparseSearcher;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrievalToolTest {

    @Mock EmbeddingModel embeddingModel;
    @Mock EmbeddingStore<TextSegment> milvusEmbeddingStore;
    @Mock SparseSearcher sparseSearcher;
    @Mock Reranker reranker;
    @Mock ChunkRepository chunkRepository;
    @Mock ChatModel chatModel;
    @Mock RetrievalCacheService retrievalCacheService;
    @Mock RetrievalMetrics retrievalMetrics;

    KnowledgeRetrievalTool tool;

    @BeforeEach
    void setUp() {
        tool = new KnowledgeRetrievalTool(embeddingModel, milvusEmbeddingStore, sparseSearcher,
                reranker, chunkRepository, chatModel, retrievalCacheService, retrievalMetrics);
    }

    private void stubHappyPath() {
        when(retrievalCacheService.getRewrite(anyString())).thenReturn("请假流程\n年假天数");
        when(embeddingModel.embed(anyString()))
                .thenReturn(Embedding.from(new float[]{0.1f, 0.2f}));
        when(milvusEmbeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
        when(sparseSearcher.search(anyString(), anyInt(), any()))
                .thenReturn(List.<SparseSearchResult>of());
        when(reranker.rerank(anyString(), anyList(), anyList(), anyInt()))
                .thenReturn(List.of());
    }

    @Test
    void shouldDenyRetrievalWhenSessionMissing() {
        when(chunkRepository.findPrincipalByMemoryId("conv-x")).thenReturn(null);

        String result = tool.retrieveKnowledge("conv-x", "请假流程");

        assertEquals("", result);
        verifyNoInteractions(embeddingModel, milvusEmbeddingStore, sparseSearcher);
    }

    @Test
    void shouldDenyRetrievalWhenNoVisibleKb() {
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of());

        String result = tool.retrieveKnowledge("conv-1", "请假流程");

        assertEquals("", result);
        verifyNoInteractions(embeddingModel, milvusEmbeddingStore, sparseSearcher);
    }

    @Test
    void userRetrievalShouldFilterByVisibleKbIds() {
        stubHappyPath();
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of(1L, 3L));

        tool.retrieveKnowledge("conv-1", "请假流程");

        ArgumentCaptor<EmbeddingSearchRequest> req =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(milvusEmbeddingStore, atLeastOnce()).search(req.capture());
        Filter filter = req.getValue().filter();
        assertNotNull(filter);
        assertInstanceOf(IsIn.class, filter);
        // kbId metadata 为字符串形式；comparisonValues() 返回 Collection<?>，用 containsAll 断言避免实现类不匹配
        Collection<?> values = ((IsIn) filter).comparisonValues();
        assertEquals(2, values.size());
        assertTrue(values.containsAll(List.of("1", "3")));
        // 稀疏路按部门过滤
        verify(sparseSearcher, atLeastOnce()).search(anyString(), anyInt(), eq(List.of(2L)));
        // 缓存后缀为部门 ID
        verify(retrievalCacheService, atLeastOnce())
                .getCachedRetrieval(anyString(), eq("2"));
    }

    @Test
    void adminRetrievalShouldSkipFiltering() {
        stubHappyPath();
        when(chunkRepository.findPrincipalByMemoryId("conv-a"))
                .thenReturn(new RetrievalPrincipal(1L, "ADMIN", 1L));

        tool.retrieveKnowledge("conv-a", "请假流程");

        ArgumentCaptor<EmbeddingSearchRequest> req =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(milvusEmbeddingStore, atLeastOnce()).search(req.capture());
        assertNull(req.getValue().filter());
        verify(sparseSearcher, atLeastOnce()).search(anyString(), anyInt(), isNull());
        verify(retrievalCacheService, atLeastOnce())
                .getCachedRetrieval(anyString(), eq("all"));
    }

    @Test
    void cachedRetrievalShouldBypassSearch() {
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of(1L));
        when(retrievalCacheService.getCachedRetrieval(anyString(), eq("2")))
                .thenReturn(List.of(new RankedChunk(11L, 22L, "内容", 0.9f)));

        String context = tool.retrieveKnowledge("conv-1", "请假流程");

        assertEquals("内容", context);
        verifyNoInteractions(embeddingModel, milvusEmbeddingStore, sparseSearcher);
    }
}
```

注意：`RankedChunk` 为 record，其字段以实际定义为准（`ChunkRepository`/`RrfReranker` 现有用法 `chunk.parentId()`、`chunk.content()`）。若构造器参数不匹配，按实际 record 定义调整本测试中的 `new RankedChunk(...)`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -DskipTests=false -Dtest=KnowledgeRetrievalToolTest`
Expected: 编译 FAIL —— `retrieveKnowledge(String, String)` 签名不存在、构造器少依赖、import `MemoryId` 失败。

- [ ] **Step 3: 实现权限过滤**

修改 `KnowledgeRetrievalTool.java`：

1. import 调整：
   - 新增：`dev.langchain4j.service.MemoryId`、`dev.langchain4j.store.embedding.filter.Filter`、`org.liar.zhiliao.retrieval.records.RetrievalPrincipal`、`static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey`
   - 删除：`org.liar.zhiliao.common.model.CurrentUser`、`org.liar.zhiliao.common.utils.UserContextHolder`
2. 方法签名（L52）改为：

```java
    public String retrieveKnowledge(@MemoryId String memoryId, @P("查询内容") String query) {
```

3. 方法体开头（`normalize` 之前）插入身份解析与权限上下文：

```java
        // Step -1: 会话身份解析（替代 ThreadLocal，流式工具线程不可靠）
        RetrievalPrincipal principal = chunkRepository.findPrincipalByMemoryId(memoryId);
        if (principal == null) {
            log.warn("No session principal for memoryId={}, deny retrieval", memoryId);
            return "";
        }
        boolean admin = principal.isAdmin();
        List<Long> visibleKbIds = admin
                ? List.of()
                : chunkRepository.findVisibleKbIds(principal.deptId());
        if (!admin && visibleKbIds.isEmpty()) {
            log.info("User {} has no visible knowledge bases, return empty", principal.userId());
            return "";
        }
        // Milvus metadata 的 kbId 为字符串形式
        Filter kbFilter = admin
                ? null
                : metadataKey("kbId").in(visibleKbIds.stream().map(String::valueOf).toList());
```

4. Step 1 的部门后缀（原 `String deptSuffix = extractDeptSuffix();`）改为：

```java
        String deptSuffix = admin ? "all" : String.valueOf(principal.deptId());
```

5. Step 4a 稠密检索请求（原 L115-119）改为：

```java
            EmbeddingSearchRequest.Builder requestBuilder = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .minScore(0.7);
            if (kbFilter != null) {
                requestBuilder.filter(kbFilter);
            }
            EmbeddingSearchRequest request = requestBuilder.build();
```

6. Step 4b 稀疏检索（原 L131-134）改为：

```java
            // admin 传 null 表示不过滤；普通用户按自身部门过滤
            List<Long> visibleDeptIds = admin ? null : List.of(principal.deptId());
```

（`sparseSearcher.search(subQuery, 10, visibleDeptIds)` 调用本身不变。）

7. **删除** `extractDeptSuffix()` 私有方法（L166-180）——不再有调用方。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -DskipTests=false -Dtest=KnowledgeRetrievalToolTest`
Expected: PASS（5 个测试）。

- [ ] **Step 5: 全模块编译（确认 chat 模块未破坏）**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -pl zhiliao-chat -am`
Expected: BUILD SUCCESS —— `ChatService.chat(@MemoryId String memoryId, ...)` 调用工具时 LangChain4j 自动注入 memoryId，接口无需改动。

- [ ] **Step 6: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalTool.java zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalToolTest.java
git commit -m "fix(retrieval): 向量检索按会话身份过滤可见知识库，修复越权访问"
```

---

### Task 9: 存量数据重建操作文档

**Files:**
- Create: `docs/ops/rebuild-vectors.md`

**Interfaces:**
- Consumes: Task 3 的 `POST /api/admin/documents/{id}/reprocess`（发 MQ）；Task 5 的 metadata kbId。
- Produces: 一次性迁移操作步骤（执行时机由用户决定，见 Task 11 验证清单）。

- [ ] **Step 1: 编写操作文档**

创建 `docs/ops/rebuild-vectors.md`：

```markdown
# 向量数据一次性重建（kbId metadata 补写后）

> 背景：2026-09 起入库向量 metadata 携带 kbId，检索按可见知识库过滤。
> 旧向量无 kbId，必须重建，否则：普通用户检索不到旧文档；admin 检索会命中已删除文档的孤儿向量。
> 前置：部署包含本次改动的新版本（metadata 已带 kbId、reprocess 已发 MQ）。

## 步骤

1. 清空 PG 切片表（父+子切片全部失效，需重新切分）：
   ```bash
   docker exec -it zhiliao-postgres psql -U <user> -d zhiliao -c "TRUNCATE zl_chunk;"
   ```

2. drop Milvus collection（旧向量全部失效）。临时开放 HTTP 端口：
   ```bash
   # 编辑 docker/local-dev.yml，取消 zhiliao-milvus 9091 端口映射注释，然后：
   docker compose -f docker/local-dev.yml up -d zhiliao-milvus
   curl -X POST http://localhost:9091/v2/vectordb/collections/drop \
        -H 'Content-Type: application/json' \
        -d '{"collectionName": "zhiliao_chunks"}'
   # 恢复 9091 注释后再次 up -d（可选）
   ```
   collection 由 langchain4j starter 在应用下次写入时自动重建。

3. 全量重新处理（重新解析、切分、embedding，metadata 带 kbId）：
   ```bash
   # 取 access token（管理员登录）
   TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"loginName":"admin","password":"<密码>"}' | jq -r '.data.accessToken')
   # 逐个触发 reprocess
   docker exec -it zhiliao-postgres psql -U <user> -d zhiliao -tAc \
        "SELECT id FROM zl_document WHERE status = 'COMPLETED';" | while read id; do
     curl -s -X POST "http://localhost:8080/api/admin/documents/$id/reprocess" \
          -H "Authorization: Bearer $TOKEN"
   done
   ```

4. 观察处理完成：
   ```bash
   docker exec -it zhiliao-postgres psql -U <user> -d zhiliao -c \
        "SELECT status, COUNT(*) FROM zl_document GROUP BY status;"
   # 全部 COMPLETED 即完成
   ```

## 注意

- 重建窗口内（步骤 2-4 之间）知识检索结果不全或为空，属预期，建议低峰执行。
- 若文档状态为 FAILED/UPLOADED 且 MinIO 对象缺失，reprocess 会再次失败——人工核对该文档是否可删。
```

（`zhiliao-postgres` 容器名与登录 URL 以 `docker/local-dev.yml`、`application.yaml` 实际值为准，执行时核对。）

- [ ] **Step 2: 展示 commit 命令（用户执行）**

```bash
git add docs/ops/rebuild-vectors.md
git commit -m "docs(ops): 向量数据一次性重建操作步骤"
```

---

### Task 10: 前端 — API 封装、路由菜单、DocumentList 重构

**Files:**
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/api/admin.js`
- Delete: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/api/document.js`
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/router/index.js`（删除 `/documents` 路由，L34-39）
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/App.vue`（menuOptions 删除"文档"项）
- Delete: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/views/Documents.vue`
- Modify: `/Users/liar/Java/project/ui/liar-zhiliao-ui/src/views/admin/DocumentList.vue`（重构）

**Interfaces:**
- Consumes: Task 4 后端接口；`getKnowledgeBases`（admin.js 已有，返回 IPage：`res.data.records`）。
- Produces: 单一文档管理页 `/admin/documents`（仅管理员菜单可见），支持上传（必选知识库）、详情、删除、重新处理、状态筛选。

- [ ] **Step 1: admin.js 增加文档接口封装**

在 `src/api/admin.js` 的 `reprocessDocument` 之后追加：

```js
export function uploadDocument(file, kbId) {
  const form = new FormData()
  form.append('file', file)
  form.append('kbId', String(kbId))
  return request.post('/api/admin/documents/upload', form)
}

export function getDocument(id) {
  return request.get(`/api/admin/documents/${id}`)
}

export function deleteDocument(id) {
  return request.delete(`/api/admin/documents/${id}`)
}
```

- [ ] **Step 2: 删除 document.js 与 Documents.vue，清理路由与菜单**

1. 删除文件 `src/api/document.js`、`src/views/Documents.vue`。
2. `src/router/index.js` 删除整个 `/documents` 路由对象（`path: '/documents', name: 'Documents', ...`）。
3. `src/App.vue` `menuOptions` 中 items 数组删除这一行：

```js
    { label: '文档', key: 'Documents', icon: () => h(NIcon, null, { default: () => h(DocIcon) }) }
```

（注意 `DocIcon` 仍被"文档管理"项使用，import 保留。）

4. 全局搜索确认无残留引用：`grep -rn "document'" src/ && grep -rn "Documents" src/`（应只剩 `AdminDocuments`）。

- [ ] **Step 3: 重构 DocumentList.vue**

替换 `src/views/admin/DocumentList.vue` 全文为：

```vue
<template>
  <n-space vertical :size="16" style="padding: 24px">
    <n-h2>文档管理</n-h2>

    <n-space align="center">
      <n-select
        v-model:value="filterStatus"
        :options="statusOptions"
        placeholder="状态筛选"
        clearable
        style="width: 160px"
        @update:value="load()"
      />
      <n-button type="primary" @click="showUpload = !showUpload">
        {{ showUpload ? '关闭上传' : '上传文档' }}
      </n-button>
    </n-space>

    <n-space v-if="showUpload" align="center">
      <n-select
        v-model:value="uploadKbId"
        :options="kbOptions"
        placeholder="选择知识库（必选）"
        style="width: 240px"
      />
      <n-upload
        :default-upload="false"
        :multiple="false"
        accept=".pdf,.txt,.doc,.docx,.md"
        :show-upload-list="false"
        @change="handleUpload"
      >
        <n-button :disabled="!uploadKbId">选择文件上传</n-button>
      </n-upload>
    </n-space>

    <n-data-table :columns="columns" :data="list" :pagination="pagination" :loading="loading" />

    <n-drawer v-model:show="showDetail" :width="420" placement="right">
      <n-drawer-content title="文档详情" closable>
        <n-descriptions v-if="detailDoc" :column="1" bordered size="small">
          <n-descriptions-item label="ID">{{ detailDoc.id }}</n-descriptions-item>
          <n-descriptions-item label="文件名">{{ detailDoc.fileName }}</n-descriptions-item>
          <n-descriptions-item label="知识库">{{ detailDoc.kbId }}</n-descriptions-item>
          <n-descriptions-item label="文件类型">{{ detailDoc.fileType || '-' }}</n-descriptions-item>
          <n-descriptions-item label="大小">{{ formatSize(detailDoc.fileSize) }}</n-descriptions-item>
          <n-descriptions-item label="状态">{{ statusLabel(detailDoc.status) }}</n-descriptions-item>
          <n-descriptions-item label="切片数">{{ detailDoc.chunkCount ?? '-' }}</n-descriptions-item>
          <n-descriptions-item label="上传时间">{{ detailDoc.createdAt || '-' }}</n-descriptions-item>
        </n-descriptions>
      </n-drawer-content>
    </n-drawer>
  </n-space>
</template>

<script setup>
import { ref, h, onMounted } from 'vue'
import { NButton, NTag, NPopconfirm, useMessage } from 'naive-ui'
import {
  getDocuments, getDocument, uploadDocument, deleteDocument,
  reprocessDocument, getKnowledgeBases
} from '../../api/admin'

const message = useMessage()
const list = ref([])
const loading = ref(false)
const filterStatus = ref(null)
const showUpload = ref(false)
const uploadKbId = ref(null)
const kbOptions = ref([])

const showDetail = ref(false)
const detailDoc = ref(null)

const statusOptions = [
  { label: '已上传', value: 'UPLOADED' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '已完成', value: 'COMPLETED' },
  { label: '失败', value: 'FAILED' }
]
const pagination = { page: 1, pageSize: 20, onChange: load, onUpdatePageSize: load }

const statusColors = {
  UPLOADED: 'default', PROCESSING: 'info', COMPLETED: 'success', FAILED: 'error'
}

const columns = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '文件名', key: 'fileName', ellipsis: { tooltip: true } },
  { title: '知识库', key: 'kbId', width: 80 },
  {
    title: '状态', key: 'status', width: 100,
    render(row) {
      return h(NTag, { type: statusColors[row.status] || 'default', size: 'small' }, { default: () => statusLabel(row.status) })
    }
  },
  { title: '切片数', key: 'chunkCount', width: 80, render: row => row.chunkCount ?? '-' },
  { title: '上传时间', key: 'createdAt', width: 180, render: row => row.createdAt || '-' },
  {
    title: '操作', key: 'actions', width: 260,
    render(row) {
      return h('n-space', { size: 'small', wrap: false }, {
        default: () => [
          h(NButton, { size: 'small', onClick: () => openDetail(row) }, { default: () => '详情' }),
          h(NPopconfirm, { onPositiveClick: () => doDelete(row.id) }, {
            default: () => '删除将同时清除切片、向量与文件，且不可恢复，确定？',
            trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' })
          }),
          h(NPopconfirm, { onPositiveClick: () => doReprocess(row.id) }, {
            default: () => '重新处理此文档？',
            trigger: () => h(NButton, { size: 'small' }, { default: () => '重新处理' })
          })
        ]
      })
    }
  }
]

async function load(page = 1) {
  loading.value = true
  try {
    const params = {}
    if (filterStatus.value) params.status = filterStatus.value
    const res = await getDocuments(page, pagination.pageSize, params)
    list.value = res.data.records
    pagination.page = res.data.current
    pagination.pageCount = res.data.pages
    pagination.itemCount = res.data.total
  } finally {
    loading.value = false
  }
}

async function loadKnowledgeBases() {
  try {
    const res = await getKnowledgeBases(1, 100)
    kbOptions.value = (res.data.records || []).map(kb => ({ label: kb.name, value: kb.id }))
  } catch (err) {
    message.error('加载知识库列表失败')
  }
}

async function handleUpload({ file }) {
  if (!uploadKbId.value) {
    message.warning('请先选择知识库')
    return
  }
  if (!file.file) return
  try {
    await uploadDocument(file.file, uploadKbId.value)
    message.success('上传成功，已加入处理队列')
    showUpload.value = false
    uploadKbId.value = null
    await load()
  } catch (err) {
    message.error('上传失败: ' + (err.response?.data?.message || err.message))
  }
}

async function openDetail(row) {
  try {
    const res = await getDocument(row.id)
    detailDoc.value = res.data
    showDetail.value = true
  } catch (err) {
    message.error('获取文档详情失败')
  }
}

async function doDelete(id) {
  try {
    await deleteDocument(id)
    message.success('删除成功')
    await load(pagination.page)
  } catch (err) {
    message.error('删除失败: ' + (err.response?.data?.message || err.message))
  }
}

async function doReprocess(id) {
  try {
    await reprocessDocument(id)
    message.success('已重新提交处理')
    await load(pagination.page)
  } catch (err) {
    message.error('重新处理失败: ' + (err.response?.data?.message || err.message))
  }
}

function formatSize(bytes) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

function statusLabel(s) {
  const map = { UPLOADED: '已上传', PROCESSING: '处理中', COMPLETED: '已完成', FAILED: '失败' }
  return map[s] || s
}

onMounted(() => {
  load()
  loadKnowledgeBases()
})
</script>
```

注意：`h('n-space', ...)` 在 render 函数中需字符串标签 `'n-space'`（Naive UI 全局注册时可用）或改用 import 的 `NSpace` 组件引用 —— 实现时优先 `import { NSpace } from 'naive-ui'` 并写 `h(NSpace, ...)`，避免全局注册依赖。

- [ ] **Step 4: 构建验证**

Run: `cd /Users/liar/Java/project/ui/liar-zhiliao-ui && npm run build`
Expected: 构建成功，无 `document.js`/`Documents.vue` 的引用报错。

- [ ] **Step 5: 展示 commit 命令（用户执行，在前端仓库）**

```bash
cd /Users/liar/Java/project/ui/liar-zhiliao-ui
git add -A src/
git commit -m "refactor(documents): 合并文档页面为管理员单一页面，支持知识库关联与删除"
```

---

### Task 11: 全量构建与手工验证

**Files:** 无新增（验证任务）

**Interfaces:**
- Consumes: Task 1-10 全部产出；本地 docker 环境（`docker/local-dev.yml`）、`DEEPSEEK_API_KEY`。
- Produces: 验证结论（设计第 8 节手工验证项的核对结果）。

- [ ] **Step 1: 后端全量构建 + 全部单测**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -DskipTests=false`
Expected: BUILD SUCCESS，Task 1/2/3/6/7/8 的测试全部 PASS。

- [ ] **Step 2: 通知用户执行存量重建（Task 9 文档）**

提示用户：部署新版后按 `docs/ops/rebuild-vectors.md` 执行一次性重建（TRUNCATE zl_chunk → drop collection → 全量 reprocess）。

- [ ] **Step 3: 手工验证清单（用户环境，执行者陪同核对）**

1. 管理员登录：菜单无"文档"（普通用户项）只有"文档管理"；页面上传文档时必须先选知识库（不选则按钮禁用/提示）。
2. 上传后 `zl_document.kb_id` 为所选知识库（psql 核对），Milvus metadata 含 kbId（attu 或检索验证）。
3. 管理员对话提问命中该文档内容（RAG 生效）。
4. 用另一部门普通用户提问同一问题，回答不引用该文档（权限过滤生效）——前提两部门可见 KB 不同。
5. 删除文档：确认弹窗 → 列表消失；psql 核对 `zl_chunk` 无该 doc_id 行；检索不再命中该文档内容。
6. 普通用户直接 `curl http://localhost:8080/api/admin/documents` → 403（AdminFilter）。
7. 普通用户菜单无任何文档入口。

- [ ] **Step 4: 展示收尾 commit 命令（用户执行，如有微调）**

```bash
git status   # 确认无遗漏文件
```

---

## Self-Review 记录

- **Spec 覆盖**：设计第 3 节（接口收编/页面整合）→ Task 4/10；第 4 节（上传关联 KB）→ Task 1/10；第 5 节（物理删除）→ Task 2；第 6 节（权限过滤）→ Task 5/6/7/8；第 7 节（数据重建）→ Task 9/11；第 8 节（测试）→ 各任务测试 + Task 11。无遗漏。
- **占位符扫描**：无 TBD/TODO；`RankedChunk` 构造参数已在 Task 8 中注明按实际 record 定义调整。
- **类型一致性**：`RetrievalPrincipal(Long userId, String role, Long deptId)` 在 Task 6 定义、Task 8 使用一致；`delete(Long)`/`reprocess(Long)` 接口与 controller 调用一致；`searchBm25(queryText, topK)` 与现有 ChunkRepository L19 签名一致。
