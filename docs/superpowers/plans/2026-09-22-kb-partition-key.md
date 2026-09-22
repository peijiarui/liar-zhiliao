# kbId 作为 Milvus 独立字段并设为 PartitionKey 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Milvus 中 kbId 落为顶层 `Int64` 分区键字段（不再塞进 metadata JSON），检索按 kbId 做分区裁剪，替换掉无法表达该 schema 的 `langchain4j-milvus-spring-boot-starter`。

**Architecture:** 新增 `zhiliao-vector` 模块，用 `milvus-sdk-java` 直接管理 collection（5 字段 + `kb_id` 分区键 + HNSW/COSINE + `partitionkey.isolation`），对外暴露窄接口 `KbAwareEmbeddingStore`（继承 `EmbeddingStore<TextSegment>`，增加带 `kbId` 的写入与带 `kbIds` 的检索）；ingestion 写入路径与 retrieval 检索路径改注入该接口；启动期校验 schema，不符则拒绝启动（不自动 drop）。

**Tech Stack:** Java 17、Spring Boot 3.5.16、多模块 Maven、`milvus-sdk-java` 2.5.9、`langchain4j-core` 1.17.0、Milvus standalone、PostgreSQL 16、JUnit 5 + Mockito。

**Spec:** `docs/superpowers/specs/2026-09-22-kb-partition-key-design.md`（实现前必读）

## Global Constraints

- 后端根目录 `/Users/liar/Java/project/liar-zhiliao`。
- Maven 测试默认被跳过（根 pom `<skipTests>true</skipTests>`），运行测试必须加 `-DskipTests=false`。
- 模块依赖方向：`zhiliao-vector` 只依赖 `langchain4j-core` + `milvus-sdk-java` + spring-boot-starter（不得依赖 common/ingestion/retrieval/chat）；`zhiliao-ingestion`、`zhiliao-retrieval` 依赖 `zhiliao-vector`。
- 父 POM 坐标：`org.liar.ai:liar-zhiliao:0.0.1-SNAPSHOT`（各模块 `<parent>` 用它）。
- **git 约束（项目 CLAUDE.md）**：不主动执行 git 命令。每个任务末尾展示建议的 commit 命令，由用户自行执行。
- Milvus collection 名 `zhiliao_chunks`（`zhiliao.milvus.collection-name`），gRPC 19530。
- Milvus 字段名固定为 `id` / `text` / `metadata` / `vector` / `kb_id`；分区键字段名 `kb_id`（`MilvusSchema.KB_ID_FIELD`）。
  > 注：Task 4 的修复轮已把五个字段名常量与响应校验合并到包私有类 `MilvusSchema` 与 `MilvusResponses`；Task 2/3 的代码片段中出现的 `MilvusMappers.KB_ID_FIELD` 是合并前的历史写法，实际代码以 `MilvusSchema.KB_ID_FIELD` 为准（`MilvusMappers.KB_ID_FIELD` 已删除）。
- `metadata` JSON 只保留 `chunkId`、`parentId`（`RrfReranker` 依赖这两个键），**不含 kbId**。
- 分区数 `num-partitions` 默认 64；向量索引 HNSW + COSINE；consistency `EVENTUALLY`。
- 检索分数必须复刻 langchain4j 语义：`RelevanceScore.fromCosineSimilarity(cos) = (cos + 1) / 2`，再按 `request.minScore()` 过滤（`minScore=0.7` ⇔ `cos ≥ 0.4`）。
- 不得自动 drop collection；schema 不符一律抛异常并在消息中给出 drop 指引。
- 异常处理：业务异常抛 `org.liar.zhiliao.common.exception.BusinessException`；本模块内部配置/校验失败抛 `IllegalStateException`。

## File Structure

| 文件 | 职责 |
|------|------|
| `pom.xml`（根） | 注册 `zhiliao-vector` 模块；管理 `milvus-sdk-java`、`langchain4j-core` 版本；移除 starter 条目 |
| `zhiliao-vector/pom.xml` | 新模块依赖 |
| `zhiliao-vector/.../MilvusProperties.java` | `zhiliao.milvus.*` 配置绑定 |
| `zhiliao-vector/.../KbAwareEmbeddingStore.java` | 对外窄接口（写入带 kbId / 检索带 kbIds） |
| `zhiliao-vector/.../MilvusMappers.java` | 纯函数：expr 构造、metadata 解析、结果映射（可单测） |
| `zhiliao-vector/.../MilvusCollectionManager.java` | 建表/建索引/load + schema 校验 |
| `zhiliao-vector/.../MilvusKbEmbeddingStore.java` | `EmbeddingStore<TextSegment>` 实现（insert/search/delete） |
| `zhiliao-vector/.../MilvusStoreConfig.java` | 装配 `MilvusServiceClient` 与 store bean，触发 ensure |
| `zhiliao-ingestion/pom.xml`、`zhiliao-retrieval/pom.xml` | 移除 starter，改依赖 `zhiliao-vector` |
| `zhiliao-ingestion/.../DocumentConsumerProcessor.java` | 写入路径：metadata 去 kbId，kbId 作为独立参数 |
| `zhiliao-retrieval/.../KnowledgeRetrievalTool.java` | 检索路径：不再构造 Filter，改传 kbIds |
| `zhiliao-app/src/main/resources/application.yaml` | milvus 配置迁至 `zhiliao.milvus` |
| `docs/ops/rebuild-vectors.md` | 存量迁移步骤（新增"停应用"、建表归属变更） |
| `docker/local-dev.yml` | Milvus 镜像 tag 固定 |

---

### Task 1: `zhiliao-vector` 模块骨架

**Files:**
- Modify: `pom.xml:19-27`（modules）、`pom.xml:119-123`（dependencyManagement，仅新增，不删 starter）
- Create: `zhiliao-vector/pom.xml`
- Create: `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusProperties.java`
- Create: `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/KbAwareEmbeddingStore.java`

**Interfaces:**
- Consumes: 无
- Produces: 模块 `org.liar.ai:zhiliao-vector`；`MilvusProperties`（getter：`getHost/getPort/getCollectionName/getUsername/getPassword/getDimension/getNumPartitions`）；接口 `KbAwareEmbeddingStore`（`addAll(List<Embedding>, List<TextSegment>, long)`、`search(EmbeddingSearchRequest, List<Long>)`）。Task 2-7 全部依赖本任务产出。

- [ ] **Step 1: 根 pom 注册模块**

`pom.xml` 的 `<modules>`（L19-27）改为（在 `zhiliao-common` 之后插入 `zhiliao-vector`）：

```xml
    <modules>
        <module>zhiliao-common</module>
        <module>zhiliao-vector</module>
        <module>zhiliao-ingestion</module>
        <module>zhiliao-retrieval</module>
        <module>zhiliao-chat</module>
        <module>zhiliao-auth</module>
        <module>zhiliao-admin</module>
        <module>zhiliao-app</module>
    </modules>
```

- [ ] **Step 2: 根 pom 声明新依赖版本**

在 `pom.xml` 的 `<dependencyManagement><dependencies>` 中，紧随 `langchain4j`（L113-117）之后追加：

```xml
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j-core</artifactId>
                <version>1.17.0</version>
            </dependency>

            <!-- Milvus 官方 SDK：自研 zhiliao-vector 直接使用，不再经 langchain4j-milvus -->
            <dependency>
                <groupId>io.milvus</groupId>
                <artifactId>milvus-sdk-java</artifactId>
                <version>2.5.9</version>
            </dependency>
```

注意：本步**不动** `langchain4j-milvus-spring-boot-starter` 条目（L119-123），它由 Task 5 统一切换。

- [ ] **Step 3: 新建模块 pom**

创建 `zhiliao-vector/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.liar.ai</groupId>
        <artifactId>liar-zhiliao</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>zhiliao-vector</artifactId>
    <name>zhiliao-vector</name>
    <description>Milvus 向量存储：kb_id 分区键 schema、写入与按知识库分区裁剪的检索</description>

    <dependencies>

        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-core</artifactId>
        </dependency>

        <dependency>
            <groupId>io.milvus</groupId>
            <artifactId>milvus-sdk-java</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

    </dependencies>

</project>
```

（gson 由 `milvus-sdk-java` 以 compile scope 传递，无需显式声明。）

- [ ] **Step 4: 配置属性类**

创建 `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusProperties.java`：

```java
package org.liar.zhiliao.vector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 连接与建表配置，绑定 zhiliao.milvus.*。
 */
@Data
@ConfigurationProperties(prefix = "zhiliao.milvus")
public class MilvusProperties {

    private String host = "localhost";
    private int port = 19530;
    private String collectionName = "zhiliao_chunks";
    private String username;
    private String password;
    /** 向量维度；为 null 时取 EmbeddingModel.dimension() */
    private Integer dimension;
    /** 分区数：仅在存在分区键时可设，服务端默认 64，上限 4096 */
    private int numPartitions = 64;
}
```

- [ ] **Step 5: 窄接口**

创建 `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/KbAwareEmbeddingStore.java`：

```java
package org.liar.zhiliao.vector;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

/**
 * 带知识库语义的向量存储。
 * <p>kbId 是顶层 Int64 分区键字段，不进入 metadata JSON。</p>
 */
public interface KbAwareEmbeddingStore extends EmbeddingStore<TextSegment> {

    /**
     * 写入单个文档的切片（一个文档只有一个 kbId）。
     *
     * @param kbId 知识库 ID，落为 kb_id 分区键字段
     * @return 与 embeddings 一一对应的向量 ID
     */
    List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, long kbId);

    /**
     * 按 kbId 范围检索。
     *
     * @param kbIds null 表示不过滤（admin，扫全部分区）；单值 → {@code kb_id == x}；多值 → {@code kb_id in [...]}
     */
    EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request, List<Long> kbIds);
}
```

- [ ] **Step 6: 构建验证**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -pl zhiliao-vector -am`
Expected: BUILD SUCCESS

- [ ] **Step 7: 展示 commit 命令（用户执行）**

```bash
git add pom.xml zhiliao-vector/pom.xml zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusProperties.java zhiliao-vector/src/main/java/org/liar/zhiliao/vector/KbAwareEmbeddingStore.java
git commit -m "feat(vector): 新增 zhiliao-vector 模块骨架与 KbAwareEmbeddingStore 接口"
```

---

### Task 2: `MilvusMappers` 纯函数（expr 构造 / metadata 解析 / 结果映射）

**Files:**
- Create: `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusMappers.java`
- Test: `zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusMappersTest.java`

**Interfaces:**
- Consumes: Task 1 的模块骨架
- Produces:
  - 常量 `MilvusMappers.KB_ID_FIELD = "kb_id"`
  - `record MilvusMappers.RawHit(String id, double rawScore, String text, Map<String, Object> metadata)`
  - `static String buildKbExpr(List<Long> kbIds)`
  - `static Map<String, Object> parseMetadata(JsonObject json)`
  - `static JsonObject toJson(Metadata metadata)`
  - `static List<EmbeddingMatch<TextSegment>> toMatches(List<RawHit> hits, double minScore)`
  Task 3/4 依赖以上签名。

- [ ] **Step 1: 写失败测试**

创建 `zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusMappersTest.java`：

```java
package org.liar.zhiliao.vector;

import com.google.gson.JsonObject;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MilvusMappersTest {

    // ---- buildKbExpr ----

    @Test
    void buildKbExprReturnsNullWhenKbIdsIsNull() {
        assertNull(MilvusMappers.buildKbExpr(null));
    }

    @Test
    void buildKbExprUsesEqualityForSingleKbId() {
        assertEquals("kb_id == 1", MilvusMappers.buildKbExpr(List.of(1L)));
    }

    @Test
    void buildKbExprUsesInForMultipleKbIds() {
        assertEquals("kb_id in [1,3]", MilvusMappers.buildKbExpr(List.of(1L, 3L)));
    }

    @Test
    void buildKbExprRejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> MilvusMappers.buildKbExpr(List.of()));
    }

    // ---- parseMetadata / toJson ----

    @Test
    void parseMetadataKeepsStringValuesAndHandlesNull() {
        JsonObject json = new JsonObject();
        json.addProperty("chunkId", "11");
        json.addProperty("parentId", "10");

        assertEquals(Map.of("chunkId", "11", "parentId", "10"), MilvusMappers.parseMetadata(json));
        assertEquals(Map.of(), MilvusMappers.parseMetadata(null));
    }

    @Test
    void toJsonRoundTripsMetadata() {
        JsonObject json = MilvusMappers.toJson(
                Metadata.from("chunkId", "11").put("parentId", "10"));

        assertEquals("11", json.get("chunkId").getAsString());
        assertEquals("10", json.get("parentId").getAsString());
    }

    // ---- toMatches ----

    @Test
    void toMatchesConvertsCosineToRelevanceScoreAtBoundary() {
        // cos = 0.4 -> (0.4 + 1) / 2 = 0.7，恰好等于阈值，必须保留
        List<EmbeddingMatch<TextSegment>> matches = MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.4, "文本", Map.of())), 0.7);

        assertEquals(1, matches.size());
        assertEquals(0.7, matches.get(0).score(), 1e-9);
        assertEquals("v1", matches.get(0).embeddingId());
        assertNull(matches.get(0).embedding());
        assertEquals("文本", matches.get(0).embedded().text());
    }

    @Test
    void toMatchesDropsHitsBelowMinScore() {
        // cos = 0.39 -> 0.695 < 0.7
        assertTrue(MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.39, "文本", Map.of())), 0.7).isEmpty());
    }

    @Test
    void toMatchesKeepsChunkAndParentIdWithoutKbId() {
        List<EmbeddingMatch<TextSegment>> matches = MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.9, "文本",
                        Map.of("chunkId", "11", "parentId", "10"))), 0.7);

        Metadata metadata = matches.get(0).embedded().metadata();
        assertEquals("11", metadata.getString("chunkId"));
        assertEquals("10", metadata.getString("parentId"));
        assertNull(metadata.getString("kbId"));
    }

    @Test
    void toMatchesReturnsNullSegmentForBlankText() {
        List<EmbeddingMatch<TextSegment>> matches = MilvusMappers.toMatches(
                List.of(new MilvusMappers.RawHit("v1", 0.9, "   ", Map.of())), 0.7);

        assertNull(matches.get(0).embedded());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-vector -DskipTests=false -Dtest=MilvusMappersTest`
Expected: 编译 FAIL —— `MilvusMappers` 不存在

- [ ] **Step 3: 实现**

创建 `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusMappers.java`：

```java
package org.liar.zhiliao.vector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.RelevanceScore;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE;

/**
 * Milvus 表达式构造与检索结果映射。纯函数，便于单测。
 * 结果映射语义逐字对齐 langchain4j-milvus 的 Mapper，避免替换后检索行为漂移。
 */
final class MilvusMappers {

    /** 分区键字段名 */
    static final String KB_ID_FIELD = "kb_id";

    private static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(LONG_OR_DOUBLE).create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private MilvusMappers() {}

    /** 一条原始检索命中，尚未换算分数 */
    record RawHit(String id, double rawScore, String text, Map<String, Object> metadata) {}

    /**
     * @param kbIds null 表示不过滤
     * @return null 表示无 expr；否则 {@code kb_id == x} 或 {@code kb_id in [x,y]}
     * @throws IllegalArgumentException kbIds 为空列表（调用方必须提前短路）
     */
    static String buildKbExpr(List<Long> kbIds) {
        if (kbIds == null) {
            return null;
        }
        if (kbIds.isEmpty()) {
            throw new IllegalArgumentException("kbIds must not be empty; caller must short-circuit");
        }
        if (kbIds.size() == 1) {
            return KB_ID_FIELD + " == " + kbIds.get(0);
        }
        return KB_ID_FIELD + " in ["
                + kbIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    static Map<String, Object> parseMetadata(JsonObject json) {
        return json == null ? Map.of() : GSON.fromJson(json, MAP_TYPE);
    }

    static JsonObject toJson(Metadata metadata) {
        return GSON.toJsonTree(metadata.toMap()).getAsJsonObject();
    }

    /**
     * 换算 relevance score 并按 minScore 过滤。
     * COSINE 度量下 Milvus 返回余弦相似度，langchain4j 统一按 (cos + 1) / 2 映射到 [0,1]。
     */
    static List<EmbeddingMatch<TextSegment>> toMatches(List<RawHit> hits, double minScore) {
        return hits.stream()
                .map(hit -> new EmbeddingMatch<>(
                        RelevanceScore.fromCosineSimilarity(hit.rawScore()),
                        hit.id(),
                        null,
                        toTextSegment(hit)))
                .filter(match -> match.score() >= minScore)
                .toList();
    }

    private static TextSegment toTextSegment(RawHit hit) {
        if (hit.text() == null || hit.text().isBlank()) {
            return null;
        }
        return TextSegment.from(hit.text(), Metadata.from(hit.metadata()));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-vector -DskipTests=false -Dtest=MilvusMappersTest`
Expected: PASS（10 个测试）

- [ ] **Step 5: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusMappers.java zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusMappersTest.java
git commit -m "feat(vector): Milvus expr 构造与检索结果映射（对齐 langchain4j 分数语义）"
```

---

### Task 3: `MilvusCollectionManager` 建表与 schema 校验

**Files:**
- Create: `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusCollectionManager.java`
- Test: `zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusCollectionManagerTest.java`

**Interfaces:**
- Consumes: `MilvusMappers.KB_ID_FIELD`（Task 2）
- Produces: `static void MilvusCollectionManager.validateSchema(List<FieldType> fields, int expectedDimension, String collectionName)`；包可见构造 `MilvusCollectionManager(MilvusServiceClient, String, int, int)` 与 `void ensure()`。Task 4 的 `MilvusStoreConfig` 依赖。

- [ ] **Step 1: 写失败测试**

创建 `zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusCollectionManagerTest.java`：

```java
package org.liar.zhiliao.vector;

import io.milvus.grpc.DataType;
import io.milvus.param.collection.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MilvusCollectionManagerTest {

    private static final int DIM = 1024;
    private static final String COLLECTION = "zhiliao_chunks";

    private static FieldType kbField(boolean partitionKey) {
        return FieldType.newBuilder()
                .withName("kb_id")
                .withDataType(DataType.Int64)
                .withPartitionKey(partitionKey)
                .build();
    }

    private static FieldType vectorField(int dimension) {
        return FieldType.newBuilder()
                .withName("vector")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();
    }

    @Test
    void validateSchemaAcceptsExpectedSchema() {
        assertDoesNotThrow(() -> MilvusCollectionManager.validateSchema(
                List.of(kbField(true), vectorField(DIM)), DIM, COLLECTION));
    }

    @Test
    void validateSchemaRejectsMissingKbIdWithDropHint() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MilvusCollectionManager.validateSchema(
                        List.of(vectorField(DIM)), DIM, COLLECTION));

        assertTrue(ex.getMessage().contains("drop"));
        assertTrue(ex.getMessage().contains(COLLECTION));
    }

    @Test
    void validateSchemaRejectsKbIdWithoutPartitionKey() {
        assertThrows(IllegalStateException.class,
                () -> MilvusCollectionManager.validateSchema(
                        List.of(kbField(false), vectorField(DIM)), DIM, COLLECTION));
    }

    @Test
    void validateSchemaRejectsDimensionMismatch() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MilvusCollectionManager.validateSchema(
                        List.of(kbField(true), vectorField(768)), DIM, COLLECTION));

        assertTrue(ex.getMessage().contains("768"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-vector -DskipTests=false -Dtest=MilvusCollectionManagerTest`
Expected: 编译 FAIL —— `MilvusCollectionManager` 不存在

- [ ] **Step 3: 实现**

创建 `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusCollectionManager.java`：

```java
package org.liar.zhiliao.vector;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.DescribeIndexResponse;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import io.milvus.response.DescCollResponseWrapper;
import io.milvus.response.DescIndexResponseWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 负责 zhiliao_chunks 的建表、建索引、load 与启动期 schema 校验。
 * 绝不自动 drop：schema 不符一律抛异常交由运维按 docs/ops/rebuild-vectors.md 处理。
 */
@Slf4j
class MilvusCollectionManager {

    static final String ID_FIELD = "id";
    static final String TEXT_FIELD = "text";
    static final String METADATA_FIELD = "metadata";
    static final String VECTOR_FIELD = "vector";

    private static final int ID_MAX_LENGTH = 36;
    private static final int TEXT_MAX_LENGTH = 65535;
    private static final String ISOLATION_PROPERTY = "partitionkey.isolation";
    private static final String HNSW_EXTRA_PARAM = "{\"M\":16,\"efConstruction\":200}";

    private final MilvusServiceClient client;
    private final String collectionName;
    private final int dimension;
    private final int numPartitions;

    MilvusCollectionManager(MilvusServiceClient client, String collectionName, int dimension, int numPartitions) {
        this.client = client;
        this.collectionName = collectionName;
        this.dimension = dimension;
        this.numPartitions = numPartitions;
    }

    void ensure() {
        if (hasCollection()) {
            validateExisting();
        } else {
            createCollection();
            createVectorIndex();
            log.info("Milvus collection {} created: dimension={}, numPartitions={}, vectorIndex=HNSW/COSINE, partitionKey={}",
                    collectionName, dimension, numPartitions, MilvusMappers.KB_ID_FIELD);
        }
        check(client.loadCollection(LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName).build()), "loadCollection");
    }

    /**
     * 校验既有 collection：必须有 kb_id 分区键字段，且向量维度与配置一致。
     */
    static void validateSchema(List<FieldType> fields, int expectedDimension, String collectionName) {
        FieldType kbField = fields.stream()
                .filter(field -> MilvusMappers.KB_ID_FIELD.equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        mismatch("missing field '" + MilvusMappers.KB_ID_FIELD + "'")));

        if (!kbField.isPartitionKey()) {
            throw new IllegalStateException(
                    mismatch("field '" + MilvusMappers.KB_ID_FIELD + "' is not a partition key"));
        }

        FieldType vectorField = fields.stream()
                .filter(field -> VECTOR_FIELD.equals(field.getName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        mismatch("missing field '" + VECTOR_FIELD + "'")));

        if (vectorField.getDimension() != expectedDimension) {
            throw new IllegalStateException(mismatch(
                    "vector dimension is " + vectorField.getDimension() + " but configured " + expectedDimension));
        }
    }

    private static String mismatch(String detail) {
        return "Milvus collection schema mismatch: " + detail
                + ". 请先 drop collection 后重启（见 docs/ops/rebuild-vectors.md）";
    }

    private boolean hasCollection() {
        R<Boolean> response = client.hasCollection(HasCollectionParam.newBuilder()
                .withCollectionName(collectionName).build());
        check(response, "hasCollection");
        return Boolean.TRUE.equals(response.getData());
    }

    private void createCollection() {
        CreateCollectionParam request = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withSchema(CollectionSchemaParam.newBuilder()
                        .addFieldType(FieldType.newBuilder()
                                .withName(ID_FIELD).withDataType(DataType.VarChar)
                                .withMaxLength(ID_MAX_LENGTH)
                                .withPrimaryKey(true).withAutoID(false).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(TEXT_FIELD).withDataType(DataType.VarChar)
                                .withMaxLength(TEXT_MAX_LENGTH).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(METADATA_FIELD).withDataType(DataType.JSON).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(VECTOR_FIELD).withDataType(DataType.FloatVector)
                                .withDimension(dimension).build())
                        .addFieldType(FieldType.newBuilder()
                                .withName(MilvusMappers.KB_ID_FIELD).withDataType(DataType.Int64)
                                .withPartitionKey(true).build())
                        .build())
                .withPartitionsNum(numPartitions)
                .withProperty(ISOLATION_PROPERTY, "true")
                .build();
        check(client.createCollection(request), "createCollection");
    }

    private void createVectorIndex() {
        check(client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName(VECTOR_FIELD)
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam(HNSW_EXTRA_PARAM)
                .build()), "createIndex");
    }

    private void validateExisting() {
        R<DescribeCollectionResponse> response = client.describeCollection(DescribeCollectionParam.newBuilder()
                .withCollectionName(collectionName).build());
        check(response, "describeCollection");
        validateSchema(new DescCollResponseWrapper(response.getData()).getFields(), dimension, collectionName);

        if (isVectorIndexMissing()) {
            log.warn("Milvus collection {} has no index on {}; creating", collectionName, VECTOR_FIELD);
            createVectorIndex();
        }
    }

    private boolean isVectorIndexMissing() {
        R<DescribeIndexResponse> response = client.describeIndex(
                DescribeIndexParam.newBuilder().withCollectionName(collectionName).build());
        if (response == null || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            return true;
        }
        return new DescIndexResponseWrapper(response.getData())
                .getIndexDescByFieldName(VECTOR_FIELD) == null;
    }

    private static void check(R<?> response, String operation) {
        if (response == null) {
            throw new IllegalStateException("Milvus " + operation + " failed: null response");
        }
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException(
                    "Milvus " + operation + " failed: status=" + response.getStatus(), response.getException());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-vector -DskipTests=false -Dtest=MilvusCollectionManagerTest`
Expected: PASS（4 个测试）

- [ ] **Step 5: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusCollectionManager.java zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusCollectionManagerTest.java
git commit -m "feat(vector): Milvus collection 建表/索引与启动期 schema 校验"
```

---

### Task 4: `MilvusKbEmbeddingStore` 实现与 `MilvusStoreConfig` 装配

**Files:**
- Create: `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusKbEmbeddingStore.java`
- Create: `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusStoreConfig.java`
- Test: `zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusKbEmbeddingStoreTest.java`

**Interfaces:**
- Consumes: Task 1 的 `KbAwareEmbeddingStore` / `MilvusProperties`；Task 2 的 `MilvusMappers`；Task 3 的 `MilvusCollectionManager`
- Produces: `MilvusKbEmbeddingStore(MilvusServiceClient, String collectionName)`；包可见 `SearchParam buildSearchParam(EmbeddingSearchRequest, List<Long>)`；Spring bean `KbAwareEmbeddingStore milvusKbEmbeddingStore`（Task 6/7 注入）

- [ ] **Step 1: 写失败测试**

创建 `zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusKbEmbeddingStoreTest.java`：

```java
package org.liar.zhiliao.vector;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilvusKbEmbeddingStoreTest {

    @Mock MilvusServiceClient client;

    MilvusKbEmbeddingStore store;

    @BeforeEach
    void setUp() {
        store = new MilvusKbEmbeddingStore(client, "zhiliao_chunks");
    }

    private static EmbeddingSearchRequest request() {
        return EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f, 0.2f}))
                .maxResults(10)
                .minScore(0.7)
                .build();
    }

    // ---- expr 构造 ----

    @Test
    void searchWithoutKbFilterHasNoExpr() {
        assertEquals("", store.buildSearchParam(request(), null).getExpr());
    }

    @Test
    void searchWithSingleKbIdUsesEquality() {
        assertEquals("kb_id == 1", store.buildSearchParam(request(), List.of(1L)).getExpr());
    }

    @Test
    void searchWithMultipleKbIdsUsesIn() {
        assertEquals("kb_id in [1,3]", store.buildSearchParam(request(), List.of(1L, 3L)).getExpr());
    }

    @Test
    void buildSearchParamUsesExpectedTopKVectorFieldAndOutFields() {
        SearchParam param = store.buildSearchParam(request(), null);

        assertEquals(10, param.getTopK());
        assertEquals("vector", param.getVectorFieldName());
        assertEquals(List.of("id", "text", "metadata"), param.getOutFields());
    }

    // ---- 短路与防误用 ----

    @Test
    void searchWithEmptyKbIdsReturnsEmptyWithoutTouchingMilvus() {
        EmbeddingSearchResult<TextSegment> result = store.search(request(), List.of());

        assertTrue(result.matches().isEmpty());
        verifyNoInteractions(client);
    }

    @Test
    void searchWithMetadataFilterIsRejected() {
        EmbeddingSearchRequest withFilter = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0.1f}))
                .maxResults(10)
                .minScore(0.7)
                .filter(metadataKey("kbId").isEqualTo("1"))
                .build();

        assertThrows(UnsupportedOperationException.class, () -> store.search(withFilter));
        verifyNoInteractions(client);
    }

    @Test
    void everyWriteEntryWithoutKbIdIsRejected() {
        Embedding embedding = Embedding.from(new float[]{0.1f});
        TextSegment segment = TextSegment.from("文本");

        assertThrows(UnsupportedOperationException.class, () -> store.add(embedding));
        assertThrows(UnsupportedOperationException.class, () -> store.add("v1", embedding));
        assertThrows(UnsupportedOperationException.class, () -> store.add(embedding, segment));
        assertThrows(UnsupportedOperationException.class, () -> store.addAll(List.of(embedding)));
        assertThrows(UnsupportedOperationException.class,
                () -> store.addAll(List.of("v1"), List.of(embedding), List.of(segment)));
        verifyNoInteractions(client);
    }

    // ---- 写入 ----

    @Test
    void addAllWritesKbIdAsSeparateFieldAndMetadataWithoutKbId() {
        when(client.insert(any(InsertParam.class))).thenReturn(R.success());

        List<String> ids = store.addAll(
                List.of(Embedding.from(new float[]{0.1f})),
                List.of(TextSegment.from("文本", Metadata.from("chunkId", "11").put("parentId", "10"))),
                7L);

        assertEquals(1, ids.size());
        ArgumentCaptor<InsertParam> captor = ArgumentCaptor.forClass(InsertParam.class);
        verify(client).insert(captor.capture());

        Map<String, List<?>> fields = captor.getValue().getFields().stream()
                .collect(Collectors.toMap(InsertParam.Field::getName, InsertParam.Field::getValues));

        assertEquals(List.of(7L), fields.get("kb_id"));
        assertEquals(List.of("文本"), fields.get("text"));
        assertEquals(ids, fields.get("id"));

        String metadataJson = String.valueOf(((List<?>) fields.get("metadata")).get(0));
        assertTrue(metadataJson.contains("chunkId"));
        assertFalse(metadataJson.contains("kbId"));
    }

    // ---- 删除 ----

    @Test
    void removeAllBuildsIdInExpression() {
        when(client.delete(any(DeleteParam.class))).thenReturn(R.success());

        store.removeAll(List.of("v1", "v2"));

        ArgumentCaptor<DeleteParam> captor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(client).delete(captor.capture());
        assertEquals("id in [\"v1\",\"v2\"]", captor.getValue().getExpr());
    }

    @Test
    void removeAllWithEmptyIdsTouchesNothing() {
        store.removeAll(List.of());

        verifyNoInteractions(client);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-vector -DskipTests=false -Dtest=MilvusKbEmbeddingStoreTest`
Expected: 编译 FAIL —— `MilvusKbEmbeddingStore` 不存在

- [ ] **Step 3: 实现 store**

创建 `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusKbEmbeddingStore.java`：

```java
package org.liar.zhiliao.vector;

import com.google.gson.JsonObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储实现：kb_id 为顶层 Int64 分区键字段，检索按 kbId 收窄分区。
 * 取代 langchain4j-milvus 的 MilvusEmbeddingStore（其 schema 固定 4 字段、不支持分区键）。
 */
public class MilvusKbEmbeddingStore implements KbAwareEmbeddingStore {

    private static final String ID_FIELD = "id";
    private static final String TEXT_FIELD = "text";
    private static final String METADATA_FIELD = "metadata";
    private static final String VECTOR_FIELD = "vector";

    static final String KB_ID_REQUIRED_MESSAGE =
            "kbId required: use addAll(embeddings, segments, kbId)";

    private final MilvusServiceClient client;
    private final String collectionName;

    public MilvusKbEmbeddingStore(MilvusServiceClient client, String collectionName) {
        this.client = client;
        this.collectionName = collectionName;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, long kbId) {
        if (embeddings == null || embeddings.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(embeddings.size());
        for (int i = 0; i < embeddings.size(); i++) {
            ids.add(UUID.randomUUID().toString());
        }

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field(ID_FIELD, ids),
                new InsertParam.Field(TEXT_FIELD, texts(segments, embeddings.size())),
                new InsertParam.Field(METADATA_FIELD, metadataJsons(segments, embeddings.size())),
                new InsertParam.Field(VECTOR_FIELD,
                        embeddings.stream().map(Embedding::vectorAsList).toList()),
                new InsertParam.Field(MilvusMappers.KB_ID_FIELD,
                        Collections.nCopies(embeddings.size(), kbId)));

        check(client.insert(InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withFields(fields)
                .build()), "insert");

        return ids;
    }

    @Override
    public void removeAll(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String expr = ID_FIELD + " in [" + ids.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]";

        check(client.delete(DeleteParam.newBuilder()
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build()), "delete");
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        if (request.filter() != null) {
            throw new UnsupportedOperationException(
                    "metadata Filter is not supported by this store: use search(request, kbIds)");
        }
        return search(request, null);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request, List<Long> kbIds) {
        if (kbIds != null && kbIds.isEmpty()) {
            return new EmbeddingSearchResult<>(List.of());
        }

        R<SearchResults> response = client.search(buildSearchParam(request, kbIds));
        check(response, "search");

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        return new EmbeddingSearchResult<>(
                MilvusMappers.toMatches(toRawHits(wrapper), request.minScore()));
    }

    /** 包可见以便单测直接断言 expr 与 outFields，无需真连 Milvus。 */
    SearchParam buildSearchParam(EmbeddingSearchRequest request, List<Long> kbIds) {
        SearchParam.Builder builder = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withFloatVectors(List.of(request.queryEmbedding().vectorAsList()))
                .withVectorFieldName(VECTOR_FIELD)
                .withTopK(request.maxResults())
                .withMetricType(MetricType.COSINE)
                .withConsistencyLevel(ConsistencyLevelEnum.EVENTUALLY)
                .withOutFields(List.of(ID_FIELD, TEXT_FIELD, METADATA_FIELD));

        String expr = MilvusMappers.buildKbExpr(kbIds);
        if (expr != null) {
            builder.withExpr(expr);
        }
        return builder.build();
    }

    private static List<MilvusMappers.RawHit> toRawHits(SearchResultsWrapper wrapper) {
        // RowRecord 实际类型是 QueryResultsWrapper.RowRecord，用 var 避免额外导入
        // 用带参重载 getRowRecords(0)：无参版本在 milvus-sdk-java 2.5.9 标记 @Deprecated（其实现即委托 0）
        var rows = wrapper.getRowRecords(0);
        if (rows.isEmpty()) {
            return List.of();
        }

        var idScores = wrapper.getIDScore(0);
        List<MilvusMappers.RawHit> hits = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object textField = rows.get(i).get(TEXT_FIELD);
            Object metadataField = rows.get(i).get(METADATA_FIELD);
            Map<String, Object> metadata = metadataField instanceof JsonObject json
                    ? MilvusMappers.parseMetadata(json)
                    : Map.of();
            hits.add(new MilvusMappers.RawHit(
                    idScores.get(i).getStrID(),
                    idScores.get(i).getScore(),
                    textField == null ? null : textField.toString(),
                    metadata));
        }
        return hits;
    }

    private static List<String> texts(List<TextSegment> segments, int size) {
        if (segments == null || segments.isEmpty()) {
            return Collections.nCopies(size, "");
        }
        return segments.stream().map(TextSegment::text).toList();
    }

    private static List<JsonObject> metadataJsons(List<TextSegment> segments, int size) {
        if (segments == null || segments.isEmpty()) {
            return Collections.nCopies(size, new JsonObject());
        }
        return segments.stream().map(segment -> MilvusMappers.toJson(segment.metadata())).toList();
    }

    // ---- 无法携带 kbId 的写入入口一律拒绝，避免分区键缺值 ----

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        throw new UnsupportedOperationException(KB_ID_REQUIRED_MESSAGE);
    }

    private static void check(R<?> response, String operation) {
        if (response == null) {
            throw new IllegalStateException("Milvus " + operation + " failed: null response");
        }
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new IllegalStateException(
                    "Milvus " + operation + " failed: status=" + response.getStatus(),
                    response.getException());
        }
    }
}
```

- [ ] **Step 4: 运行 store 测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-vector -DskipTests=false -Dtest=MilvusKbEmbeddingStoreTest`
Expected: PASS（10 个测试）

- [ ] **Step 5: 实现 Spring 装配**

创建 `zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusStoreConfig.java`：

```java
package org.liar.zhiliao.vector;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

/**
 * 装配 Milvus 客户端与向量存储。启动期由 MilvusCollectionManager 建表或校验 schema。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(MilvusProperties.class)
public class MilvusStoreConfig {

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient(MilvusProperties properties) {
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withAuthorization(
                        properties.getUsername() == null ? "" : properties.getUsername(),
                        properties.getPassword() == null ? "" : properties.getPassword())
                .build());
    }

    @Bean
    public KbAwareEmbeddingStore milvusKbEmbeddingStore(MilvusServiceClient client,
                                                       MilvusProperties properties,
                                                       @Nullable EmbeddingModel embeddingModel) {
        Integer dimension = properties.getDimension() != null
                ? properties.getDimension()
                : (embeddingModel == null ? null : embeddingModel.dimension());
        if (dimension == null) {
            throw new IllegalStateException("Milvus vector dimension unknown: "
                    + "set zhiliao.milvus.dimension or provide an EmbeddingModel");
        }

        new MilvusCollectionManager(
                client, properties.getCollectionName(), dimension, properties.getNumPartitions()).ensure();

        log.info("Milvus store ready: collection={}, dimension={}, numPartitions={}",
                properties.getCollectionName(), dimension, properties.getNumPartitions());
        return new MilvusKbEmbeddingStore(client, properties.getCollectionName());
    }
}
```

- [ ] **Step 6: 构建验证**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -pl zhiliao-vector -am -DskipTests=false`
Expected: BUILD SUCCESS，`MilvusMappersTest` / `MilvusCollectionManagerTest` / `MilvusKbEmbeddingStoreTest` 全部 PASS

- [ ] **Step 7: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusKbEmbeddingStore.java zhiliao-vector/src/main/java/org/liar/zhiliao/vector/MilvusStoreConfig.java zhiliao-vector/src/test/java/org/liar/zhiliao/vector/MilvusKbEmbeddingStoreTest.java
git commit -m "feat(vector): 自研 MilvusEmbeddingStore（kb_id 分区键）与 Spring 装配"
```

---

### Task 5: 依赖收编（移除 starter、模块改依赖、配置迁移）

**Files:**
- Modify: `pom.xml:119-123`（删除 starter 的 dependencyManagement 条目）
- Modify: `zhiliao-ingestion/pom.xml:32-36`
- Modify: `zhiliao-retrieval/pom.xml:35-39`
- Modify: `zhiliao-app/src/main/resources/application.yaml:67-73` 与 `:100-101`

**Interfaces:**
- Consumes: Task 4 的 `KbAwareEmbeddingStore` bean
- Produces: 全工程不再引用 `langchain4j-milvus-spring-boot-starter`；配置前缀 `zhiliao.milvus`。Task 6/7 的编译依赖此任务完成。

- [ ] **Step 1: 根 pom 依赖管理调整（删 starter 条目、加 zhiliao-vector 条目）**

在 `pom.xml` 的 `<dependencyManagement>` 中**删除** `langchain4j-milvus-spring-boot-starter` 条目（内容如下，按内容查找，Task 1 已使行号偏移）：

```xml
            <dependency>
                <groupId>dev.langchain4j</groupId>
                <artifactId>langchain4j-milvus-spring-boot-starter</artifactId>
                <version>${langchain4j.version}</version>
            </dependency>
```

**同时新增** `zhiliao-vector` 的管理条目，与既有内部模块条目（`zhiliao-common`、`zhiliao-retrieval` 等，均为 `${project.version}`）并列：

```xml
            <dependency>
                <groupId>org.liar.ai</groupId>
                <artifactId>zhiliao-vector</artifactId>
                <version>${project.version}</version>
            </dependency>
```

保留 `io.grpc` 1.75.0 的三个 CVE 固定项（`milvus-sdk-java` 同样依赖 grpc，仍需覆盖）。

- [ ] **Step 2: ingestion 模块改依赖**

`zhiliao-ingestion/pom.xml` 中把：

```xml
        <!-- milvus 向量数据库客户端 -->
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-milvus-spring-boot-starter</artifactId>
        </dependency>
```

替换为：

```xml
        <!-- milvus 向量存储（自研，kb_id 分区键）；版本由根 pom dependencyManagement 管理 -->
        <dependency>
            <groupId>org.liar.ai</groupId>
            <artifactId>zhiliao-vector</artifactId>
        </dependency>
```

- [ ] **Step 3: retrieval 模块改依赖**

`zhiliao-retrieval/pom.xml` 中把：

```xml
        <!-- milvus 向量数据库相关依赖 -->
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-milvus-spring-boot-starter</artifactId>
        </dependency>
```

替换为：

```xml
        <!-- milvus 向量存储（自研，kb_id 分区键）；版本由根 pom dependencyManagement 管理 -->
        <dependency>
            <groupId>org.liar.ai</groupId>
            <artifactId>zhiliao-vector</artifactId>
        </dependency>

        <!-- 直接使用 TextSegment / EmbeddingStore / ToolMemoryId 等核心类型 -->
        <dependency>
            <groupId>dev.langchain4j</groupId>
            <artifactId>langchain4j-core</artifactId>
        </dependency>
```

（`@ToolMemoryId` 在 `langchain4j-core` 中，不需要 `langchain4j` 主 jar。）

- [ ] **Step 4: 配置迁移到 zhiliao.milvus**

`application.yaml` 删除 `langchain4j:` 下的 milvus 段（L67-73）：

```yaml
  milvus:
    host: ${RESOURCE_SERVER_HOST}
    port: 19530
    collection-name: zhiliao_chunks
    username: peijiarui
    password: ${RESOURCE_SERVER_PASSWORD}
#    dimension: 1024
```

保留紧随其后的 `#  community:` 注释块不动。然后在 `zhiliao:` 段（L94-107）的 `rabbitmq:` 之后、`auth:` 之前插入：

```yaml
  milvus:
    host: ${RESOURCE_SERVER_HOST}
    port: 19530
    collection-name: zhiliao_chunks
    username: peijiarui
    password: ${RESOURCE_SERVER_PASSWORD}
    num-partitions: 64
    # dimension: 1024   # 缺省取 EmbeddingModel.dimension()
```

- [ ] **Step 5: 全量构建验证**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -DskipTests=false`
Expected: BUILD SUCCESS，全部模块测试 PASS

- [ ] **Step 6: 确认无残留引用**

Run: `cd /Users/liar/Java/project/liar-zhiliao && grep -rn "langchain4j-milvus" --include=pom.xml --include=*.yaml .`
Expected: 无输出（`docs/` 下的历史文档不计入，因命令已限定 pom.xml 与 yaml）

- [ ] **Step 7: 展示 commit 命令（用户执行）**

```bash
git add pom.xml zhiliao-ingestion/pom.xml zhiliao-retrieval/pom.xml zhiliao-app/src/main/resources/application.yaml
git commit -m "refactor(vector): 移除 langchain4j-milvus starter，改依赖 zhiliao-vector，配置迁至 zhiliao.milvus"
```

---

### Task 6: `DocumentConsumerProcessor` 写入路径改造

**Files:**
- Modify: `zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessor.java`（import、字段、第 7 步 L117-130）
- Test: `zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessorTest.java`

**Interfaces:**
- Consumes: `KbAwareEmbeddingStore.addAll(List<Embedding>, List<TextSegment>, long)`（Task 1/4）
- Produces: 写入 Milvus 的 metadata 只含 `chunkId`/`parentId`；`kb_id` 作为独立字段写入。无其他模块依赖本任务。

- [ ] **Step 1: 改造测试（先失败）**

`DocumentConsumerProcessorTest.java` 修改：

1. import 增加 `dev.langchain4j.data.document.Metadata`，删除 `dev.langchain4j.store.embedding.EmbeddingStore`，增加 `org.liar.zhiliao.vector.KbAwareEmbeddingStore`
2. 字段声明（L51）改为：

```java
    @Mock KbAwareEmbeddingStore milvusEmbeddingStore;
```

3. `stubHappyPath()` 中的打桩（L82）改为：

```java
        when(milvusEmbeddingStore.addAll(anyList(), anyList(), anyLong())).thenReturn(List.of("v-new"));
```

4. 两个既有测试中的 `verify(milvusEmbeddingStore).addAll(anyList(), anyList());` 改为：

```java
        verify(milvusEmbeddingStore).addAll(anyList(), anyList(), eq(1L));
```

5. 追加新测试：

```java
    @Test
    void processShouldPassKbIdSeparatelyAndKeepItOutOfMetadata() throws Exception {
        stubHappyPath();
        when(chunkMapper.selectList(any())).thenReturn(List.of());

        processor.process(message());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segments = ArgumentCaptor.forClass(List.class);
        verify(milvusEmbeddingStore).addAll(anyList(), segments.capture(), eq(1L));

        Metadata metadata = segments.getValue().get(0).metadata();
        // setUp 里 AtomicLong 从 100 起：parent 得到 101，child 得到 102
        assertEquals("102", metadata.getString("chunkId"));
        assertEquals("101", metadata.getString("parentId"));
        assertNull(metadata.getString("kbId"));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DocumentConsumerProcessorTest`
Expected: **运行期失败（不是编译失败）** —— `EmbeddingStore` 自带两参 `addAll` default 方法，处理器调用的旧签名仍可编译。失败点可能是：处理器仍把 kbId 写进 metadata 导致新增断言不成立、新 `verify(... addAll(..., eq(1L)))` 无匹配调用、或 Mockito 对未打桩的两参重载返回空值引发后续 NPE/越界。**本步只要求确认失败发生在运行期**，具体失败点以实际输出为准。
（注：`-am` 会让上游模块因「No tests matching pattern」报错，故必须带 `-Dsurefire.failIfNoSpecifiedTests=false`。）

- [ ] **Step 3: 改造处理器**

`DocumentConsumerProcessor.java`：

1. import 调整：删除 `dev.langchain4j.store.embedding.EmbeddingStore`，增加 `org.liar.zhiliao.vector.KbAwareEmbeddingStore`
2. 字段（L44）改为：

```java
    private final KbAwareEmbeddingStore milvusEmbeddingStore;
```

3. 第 7 步（L117-130）替换为：

```java
            // 7. 只对 child 做 Embedding 并写入 Milvus（kb_id 作为独立分区键字段）
            List<TextSegment> childSegmentsWithMeta = new ArrayList<>();
            for (int i = 0; i < childSegments.size(); i++) {
                ZlChunk childEntity = childEntities.get(i);
                TextSegment segWithMeta = TextSegment.from(
                        childSegments.get(i).text(),
                        Metadata.from("chunkId", childEntity.getId().toString())
                                .put("parentId", childEntity.getParentId() != null
                                        ? childEntity.getParentId().toString() : ""));
                childSegmentsWithMeta.add(segWithMeta);
            }

            List<Embedding> embeddings = embeddingModel.embedAll(childSegmentsWithMeta).content();
            List<String> vectorIds = milvusEmbeddingStore.addAll(
                    embeddings, childSegmentsWithMeta, doc.getKbId());
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-ingestion -DskipTests=false -Dtest=DocumentConsumerProcessorTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 模块编译验证**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -pl zhiliao-ingestion -am`
Expected: BUILD SUCCESS（`DocumentServiceImpl` 仍注入基接口 `EmbeddingStore<TextSegment>`，无需改动）

- [ ] **Step 6: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-ingestion/src/main/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessor.java zhiliao-ingestion/src/test/java/org/liar/zhiliao/ingestion/consumer/DocumentConsumerProcessorTest.java
git commit -m "feat(ingestion): kbId 作为独立字段写入 Milvus，移出 metadata"
```

---

### Task 7: `KnowledgeRetrievalTool` 检索过滤改造

**Files:**
- Modify: `zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalTool.java`（import、字段 L46、L70-73、L137-148）
- Test: `zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalToolTest.java`

**Interfaces:**
- Consumes: `KbAwareEmbeddingStore.search(EmbeddingSearchRequest, List<Long>)`（Task 1/4）
- Produces: 稠密检索按 kbIds 传参（admin 传 null）。`ChunkRepository.findPrincipalByMemoryId` / `findVisibleKbIds` 的既有语义不变。

- [ ] **Step 1: 改造测试（先失败）**

`KnowledgeRetrievalToolTest.java` 修改：

1. import：删除 `dev.langchain4j.store.embedding.filter.Filter`、`dev.langchain4j.store.embedding.filter.comparison.IsIn`、`java.util.Collection`；删除 `dev.langchain4j.store.embedding.EmbeddingStore`，增加 `org.liar.zhiliao.vector.KbAwareEmbeddingStore`
2. 字段（L41）改为：

```java
    @Mock KbAwareEmbeddingStore milvusEmbeddingStore;
```

3. `stubHappyPath()` 中的打桩（L67-68）改为：

```java
        when(milvusEmbeddingStore.search(any(EmbeddingSearchRequest.class), any()))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));
```

4. `userRetrievalShouldFilterByVisibleKbIds` 的断言段（L106-115）替换为：

```java
        ArgumentCaptor<EmbeddingSearchRequest> req =
                ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(milvusEmbeddingStore, atLeastOnce()).search(req.capture(), eq(List.of(1L, 3L)));
        // 过滤改由 kbIds 参数承载，请求本身不再挂 metadata Filter
        assertNull(req.getValue().filter());
```

5. `adminRetrievalShouldSkipFiltering` 的断言段（L131-134）替换为：

```java
        verify(milvusEmbeddingStore, atLeastOnce())
                .search(any(EmbeddingSearchRequest.class), isNull());
```

6. 追加单值场景测试：

```java
    @Test
    void singleVisibleKbPassesSingleElementList() {
        stubHappyPath();
        when(chunkRepository.findPrincipalByMemoryId("conv-1"))
                .thenReturn(new RetrievalPrincipal(1L, "USER", 2L));
        when(chunkRepository.findVisibleKbIds(2L)).thenReturn(List.of(7L));

        tool.retrieveKnowledge("conv-1", "请假流程");

        verify(milvusEmbeddingStore, atLeastOnce())
                .search(any(EmbeddingSearchRequest.class), eq(List.of(7L)));
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -am -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest=KnowledgeRetrievalToolTest`
Expected: **运行期失败（不是编译失败）** —— mock 类型已换为 `KbAwareEmbeddingStore`，但工具仍调用单参 `search(request)`：该重载在测试里未打桩，Mockito 返回空值可能先引发 NPE，或新增 `verify(...).search(..., eq(List.of(1L, 3L)))` 无匹配调用。**本步只要求确认失败发生在运行期**，具体失败点以实际输出为准。
（注：`-am` 会让上游模块因「No tests matching pattern」报错，故必须带 `-Dsurefire.failIfNoSpecifiedTests=false`。）

- [ ] **Step 3: 改造工具类**

`KnowledgeRetrievalTool.java`：

1. import 调整：
   - 删除 `dev.langchain4j.store.embedding.filter.Filter`、`static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey`
   - 增加 `org.liar.zhiliao.vector.KbAwareEmbeddingStore`
2. 字段（L46）改为：

```java
    private final KbAwareEmbeddingStore milvusEmbeddingStore;
```

3. 把 L70-73 的 Filter 构造替换为：

```java
        // kb_id 是顶层分区键字段：null 表示不过滤（admin），单值走 ==，多值走 in [...]
        List<Long> kbIds = admin ? null : visibleKbIds;
```

4. 稠密检索段（L137-148）替换为：

```java
            log.debug("======== 调用向量模型获取向量 ========");
            Embedding queryEmbedding = embeddingModel.embed(subQuery).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .minScore(0.7)
                    .build();
            log.debug("======== 稠密检索：调用向量数据库进行相似度匹配 ========");
            Timer.Sample denseSample = retrievalMetrics.startTimer();   // 稠密检索耗时统计埋点
            try {
                EmbeddingSearchResult<TextSegment> result = milvusEmbeddingStore.search(request, kbIds);
                allDenseResults.addAll(result.matches());
                log.debug("======== 稠密检索：结果数量：{} ========", result.matches().size());
            } finally {
                denseSample.stop(retrievalMetrics.getDenseSearch());    // 埋点结束，必须调用stop
            }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-retrieval -DskipTests=false -Dtest=KnowledgeRetrievalToolTest`
Expected: PASS（6 个测试）

- [ ] **Step 5: chat 模块编译验证**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -pl zhiliao-chat -am`
Expected: BUILD SUCCESS

- [ ] **Step 6: 展示 commit 命令（用户执行）**

```bash
git add zhiliao-retrieval/src/main/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalTool.java zhiliao-retrieval/src/test/java/org/liar/zhiliao/retrieval/tools/KnowledgeRetrievalToolTest.java
git commit -m "feat(retrieval): 稠密检索按 kbId 分区裁剪，移除 metadata Filter"
```

---

### Task 8: 全量构建与全测试

**Files:** 无新增（验证任务）

**Interfaces:**
- Consumes: Task 1-7 全部产出
- Produces: 构建与测试结论

- [ ] **Step 0: 环境前提（重要，先读）**

本地 Milvus 中 `zhiliao_chunks` 仍是**旧的 4 字段 collection**（迁移尚未执行）。启动期 schema 校验会按设计拒绝启动，
因此**不加覆盖直接跑全量构建时 `LiarZhiliaoApplicationTests.contextLoads` 必然失败**——这是预期行为，不是代码缺陷。

本任务不改动本地数据（破坏性的 drop 由用户在低峰自行执行，见 Task 9 文档），改用**临时 collection 名**做验收：
用一个不存在的 collection 名覆盖 `zhiliao.milvus.collection-name`，让应用全新建表，从而验证代码与接线完全正确。

- [ ] **Step 1: 全量构建 + 全部单测（临时 collection 名覆盖）**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn clean package -DskipTests=false -Dzhiliao.milvus.collection-name=zhiliao_verify_t8`
Expected: BUILD SUCCESS；全部测试 PASS（含 `LiarZhiliaoApplicationTests.contextLoads`），
关键测试类：`MilvusMappersTest`、`MilvusCollectionManagerTest`、`MilvusKbEmbeddingStoreTest`、`DocumentConsumerProcessorTest`、`KnowledgeRetrievalToolTest`、`DocumentServiceImplTest`、`ChunkRepositoryTest`、`PgBm25SearcherTest`

（系统属性优先级高于 `application.yaml`，因此该覆盖生效；应用会在临时 collection 上自动建表，
并在日志打出 `Milvus collection zhiliao_verify_t8 created` 与 `Milvus store ready`。）

- [ ] **Step 2: 对照运行（记录不加覆盖时的预期失败）**

Run: `cd /Users/liar/Java/project/liar-zhiliao && mvn test -pl zhiliao-app -am -DskipTests=false -Dtest=LiarZhiliaoApplicationTests -Dsurefire.failIfNoSpecifiedTests=false`
（**必须带 `-am`**：不带时 `-pl` 会解析 `~/.m2` 中的陈旧快照，得到 `ClassNotFoundException: JwtUtil` 等无关错误，真正想看的 schema 校验信息反而看不到。）
Expected: FAIL，且失败信息含 `Milvus collection schema mismatch`（旧 collection 缺 `kb_id` 分区键）。
把这一段输出抄进报告——它是 fail-fast 生效的证据，也是 Task 9 文档存在的原因。

- [ ] **Step 3: 清理临时 collection**

Run（Milvus v2.6 的 REST 与 gRPC 同端口；若开了鉴权则需带 `Authorization: Bearer <user>:<password>`）:
```bash
curl -s -X POST http://localhost:19530/v2/vectordb/collections/drop \
     -H 'Content-Type: application/json' \
     -d '{"collectionName": "zhiliao_verify_t8"}'
```
Expected: 返回成功；若 REST 不可用或认证失败，改用 attu（本地已在运行）在 Collections 中删除 `zhiliao_verify_t8`。
两种方式都失败时**不要阻塞**——在报告中记下该临时 collection 名，由 Task 10 一并清理。

- [ ] **Step 4: 展示收尾 commit 命令（用户执行，如有微调）**

```bash
git status   # 本任务不应产生代码改动
```

---

### Task 9: 改写存量迁移文档 `docs/ops/rebuild-vectors.md`

**Files:**
- Modify: `docs/ops/rebuild-vectors.md`

**Interfaces:**
- Consumes: Task 3 的启动期 schema 校验（失败即拒绝启动）；Task 5 的 `zhiliao.milvus.*` 配置；既有 `DocumentService.reprocess`（发 MQ）
- Produces: 可执行的迁移步骤，供 Task 10 手工执行

- [ ] **Step 1: 替换文档全文**

`docs/ops/rebuild-vectors.md` 全文替换为：

```markdown
# 向量数据一次性重建（kb_id 分区键 schema 变更后）

> 背景：2026-09 起 Milvus 中 kbId 由 metadata JSON 迁到顶层 Int64 分区键字段 `kb_id`，
> collection 为 5 字段（id/text/metadata/vector/kb_id）+ HNSW 索引 + 64 分区。
> 旧 collection 的 4 字段 schema 不兼容，必须 drop 后重建。
> 前置：部署包含本次改动的新版本（zhiliao-vector 模块，自研 store 取代 langchain4j-milvus starter）。

## 本机环境（2026-09-22 实测）

容器均为手工 `docker run` 启动，**没有使用**仓库的 `docker/local-dev.yml`（该文件中的 `zhiliao-*` 命名与实际不符）：

| 组件 | 容器名 | 镜像 | 端口 |
|------|--------|------|------|
| Milvus | `milvus-standalone` | `milvusdb/milvus:v2.6.18` | 19530（gRPC + REST 同端口） |
| PostgreSQL | `postgres16-zhparser` | `abcfy2/zhparser:16` | 5432，库 `zhiliao`，用户 `peijiarui` |
| Redis | `redis-stack` | `redis/redis-stack:7.2.0-v20` | 6379 |
| RabbitMQ | `rabbitmq` | `rabbitmq:3.13-management` | 5672 |
| MinIO | `minio` | `minio/minio:RELEASE.2025-09-07T16-13-09Z` | 9000 |
| attu（Milvus 控制台） | `attu` | `zilliz/attu:v3.0.0-beta.6` | 见 `docker port attu` |

**Milvus v2.6.18 ≥ 2.5.4**，`partitionkey.isolation` 可用，无需升级镜像。

## 顺序要求

新版**启动期会校验 schema，不符即拒绝启动**（不自动 drop）。因此 drop 必须发生在启动之前。
未执行 drop 时启动会报 `Milvus collection schema mismatch: missing field 'kb_id'`。

## 步骤

0. 停旧版应用（避免重建窗口内继续写入旧 schema 数据）。

1. drop Milvus collection `zhiliao_chunks`（旧向量全部失效）。两种方式任选：
   - **attu**（推荐，无需处理鉴权）：`docker port attu` 查端口后打开，选中 `zhiliao_chunks` → Drop Collection。
   - **REST**：
     ```bash
     # 若 Milvus 未开鉴权则省略 -H；开启则用 Bearer <username>:<password>
     curl -s -X POST http://localhost:19530/v2/vectordb/collections/drop \
          -H 'Content-Type: application/json' \
          -H 'Authorization: Bearer peijiarui:<密码>' \
          -d '{"collectionName": "zhiliao_chunks"}'
     ```

2. 清空 PG 切片表（父+子切片全部失效，需重新切分）：
   ```bash
   docker exec -it postgres16-zhparser psql -U peijiarui -d zhiliao -c "TRUNCATE zl_chunk;"
   ```

3. 保留 `zl_document` 行与 MinIO 对象不变。

4. 部署新版并启动。collection 由 `zhiliao-vector` 在**启动期**自动创建
   （64 分区 + HNSW/COSINE + `partitionkey.isolation=true`）。
   应用日志应出现 `Milvus collection zhiliao_chunks created` 与 `Milvus store ready`。

5. 全量重新处理（重新解析、切分、embedding，`kb_id` 作为独立字段写入）：
   ```bash
   # 取 access token（管理员登录）
   TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"loginName":"admin","password":"<密码>"}' | jq -r '.data.accessToken')
   # 逐个触发 reprocess
   docker exec -it postgres16-zhparser psql -U peijiarui -d zhiliao -tAc \
        "SELECT id FROM zl_document WHERE status = 'COMPLETED';" | while read id; do
     curl -s -X POST "http://localhost:8080/api/admin/documents/$id/reprocess" \
          -H "Authorization: Bearer $TOKEN"
   done
   ```
   注意：reprocess 通过 RabbitMQ 异步处理，需要应用、RabbitMQ、MinIO 均在运行。

6. 观察处理完成：
   ```bash
   docker exec -it postgres16-zhparser psql -U peijiarui -d zhiliao -c \
        "SELECT status, COUNT(*) FROM zl_document GROUP BY status;"
   # 全部 COMPLETED 即完成
   ```

7. 抽查 `kb_id` 已落为独立字段（管理员登录后向知识库提问，能命中新写入的文档）。

## 注意

- 重建窗口内（步骤 1-5 之间）知识检索结果不全或为空，属预期，建议低峰执行。
- 若文档状态为 FAILED/UPLOADED 且 MinIO 对象缺失，reprocess 会再次失败——人工核对该文档是否可删。
- 若启动日志报 `Milvus collection schema mismatch`，说明 drop 未生效或连到了错误的 Milvus 实例。
- 仓库 `docker/local-dev.yml` 与本机实际容器不一致，**不要**按该文件操作。
```

- [ ] **Step 2: 展示 commit 命令（用户执行）**

```bash
git add docs/ops/rebuild-vectors.md
git commit -m "docs(ops): 重写向量重建步骤（kb_id 分区键 schema，含停应用前置）"
```

---

### Task 10: 记录实测版本与实施期验证清单

**Files:** 无新增（验证任务；本机容器由手工 `docker run` 启动，仓库 `docker/local-dev.yml` 未被使用，故**不改**任何文件）

**Interfaces:**
- Consumes: Task 1-9 全部产出；本机 docker 环境（`milvus-standalone` / `postgres16-zhparser` / `attu` 等）
- Produces: 7 项验证结论（写入 spec 第 11 节对应项，供用户复核）

- [ ] **Step 1: 记录实测 Milvus 版本（验证项 1）**

Run: `docker inspect milvus-standalone --format '{{.Config.Image}}'`
Expected: `milvusdb/milvus:v2.6.18`（2026-09-22 实测）。**v2.6.18 ≥ 2.5.4，`partitionkey.isolation` 可用，验证项 1 通过，无需升级。**
把该结论记入 spec 第 11 节第 1 行。

- [ ] **Step 1b: 清理遗留临时 collection**

Task 5/Task 8 的验收各自创建过临时 collection（`zhiliao_t5_probe` / `zhiliao_verify_t8`）。
用 attu 或 REST 检查并删除它们（drop 一个我们自己刚建的空 collection，无数据损失）：
```bash
curl -s -X POST http://localhost:19530/v2/vectordb/collections/list \
     -H 'Content-Type: application/json' -d '{}'
```
Expected: 列表中不残留 `zhiliao_t5_probe` / `zhiliao_verify_t8`；若残留则逐个 drop。

- [ ] **Step 2: 验证项 2 —— isolation + 多值 IN**

用管理员账号登录后向两个以上可见知识库提问（普通用户可见多库场景），触发 `kb_id in [...]` 路径。
Expected: 检索正常返回，应用日志无 Milvus 报错。
若报错：在 `MilvusCollectionManager.createCollection()` 去掉 `.withProperty(ISOLATION_PROPERTY, "true")`，
drop collection 重启，退化为普通分区裁剪，并在 spec 第 11 节记录该降级。

- [ ] **Step 3: 验证项 3 —— 分区键字段与索引**

用 attu 打开 `zhiliao_chunks` 的 schema 视图（或）：
```bash
curl -s -X POST http://localhost:19530/v2/vectordb/collections/describe \
     -H 'Content-Type: application/json' -d '{"collectionName":"zhiliao_chunks"}'
```
Expected: 字段含 `kb_id`（Int64，`isPartitionKey: true`）、`vector` 维度 1024、`numPartitions: 64`；
若索引列表中无 `kb_id` 且过滤报错，在 `MilvusCollectionManager` 中补显式 `createIndex(kb_id)` 后重跑 Task 8。

- [ ] **Step 4: 验证项 4 —— 删除表达式**

在文档管理页删除一个文档。
Expected: 列表消失，`zl_chunk` 无该 `doc_id` 行，检索不再命中。
若 Milvus 报一致性相关错误：把 `MilvusKbEmbeddingStore.buildSearchParam` 中的
`withConsistencyLevel(EVENTUALLY)` 提升为 `ConsistencyLevelEnum.BOUNDED`，重跑 Task 8 测试。

- [ ] **Step 5: 验证项 5 —— score 语义与召回**

迁移前对同一批问题记录旧 collection（FLAT）的回答；迁移后同批问题重跑。
Expected: `minScore=0.7`（即 cosine ≥ 0.4）下命中内容基本一致；若召回明显下降，
调整 `MilvusMappers.toMatches` 的换算或提高 HNSW `ef`（`HNSW_EXTRA_PARAM`）。

- [ ] **Step 6: 验证项 6/7 —— admin 全分区检索与延迟**

用管理员账号提问（`kbIds=null`，无 expr，扫全部 64 分区）。
Expected: 正常返回结果；对比迁移前 admin 提问的响应时间，记录劣化幅度。
若劣化明显：登记为后续优化项（spec 第 13 节"不在本次范围"已排除 admin 单独 collection）。

- [ ] **Step 7: 本任务无代码改动**

`git status` 应显示无本任务引入的改动（本机容器不是由仓库 compose 管理的）。
若前面步骤为修复问题而改了代码，则按对应文件的常规 commit 流程处理。

---

## Self-Review 记录

**Spec 覆盖**

| Spec 章节 | 对应 Task |
|-----------|-----------|
| 第 5 节 模块与依赖（新模块、接口边界、pom 变更、配置迁移） | Task 1、Task 5 |
| 第 6 节 Collection schema 与启动期 ensure | Task 3、Task 4 Step 5 |
| 第 7 节 写入路径 | Task 6 |
| 第 8 节 检索路径（expr 规则、score 换算、outFields） | Task 2、Task 4、Task 7 |
| 第 9 节 删除与重处理 | Task 4（`removeAll(ids)`）、Task 6/7 保持不动 |
| 第 10 节 存量迁移 | Task 9 |
| 第 11 节 风险与验证清单 | Task 10 |
| 第 12 节 测试 | 各 Task 的测试步骤 + Task 8 |
| 接口边界中"基接口各入口处理"表格 | Task 4 Step 1 的 `everyWriteEntryWithoutKbIdIsRejected` 与 `searchWithMetadataFilterIsRejected` |

无遗漏。

**占位符扫描**：无 TBD/TODO；所有代码步骤均为完整可编译代码；所有测试步骤均给出断言内容。

**类型一致性核对**

- `KbAwareEmbeddingStore`：`addAll(List<Embedding>, List<TextSegment>, long)`、`search(EmbeddingSearchRequest, List<Long>)` —— Task 1 定义，Task 4 实现，Task 6/7 调用，签名一致。
- `MilvusMappers.KB_ID_FIELD`、`RawHit`、`buildKbExpr`、`parseMetadata`、`toJson`、`toMatches` —— Task 2 定义，Task 3/4 使用，未出现别名。
- `MilvusCollectionManager`：构造 `(MilvusServiceClient, String, int, int)`、`ensure()`、`validateSchema(List<FieldType>, int, String)` —— Task 3 定义，Task 4 Step 5 调用一致。
- 字段名常量：`id` / `text` / `metadata` / `vector` 在 `MilvusCollectionManager` 与 `MilvusKbEmbeddingStore` 各定义一份私有常量（模块内两处，值相同且都在 Task 3/4 固定），测试断言用字面量 `"vector"`、`"id"`、`"text"`、`"metadata"`，与实现一致。
- `DocumentConsumerProcessor` 注入类型由 `EmbeddingStore<TextSegment>` 改为 `KbAwareEmbeddingStore`；`DocumentServiceImpl` **保持** `EmbeddingStore<TextSegment>`（Task 6 Step 5 专门验证其仍可编译）。

**已知风险提示（非计划缺陷）**：Task 10 的 Step 3-5 存在"验证不通过需改回设计"的可能，spec 第 11 节已登记对应降级方案，执行时按该节处理。
