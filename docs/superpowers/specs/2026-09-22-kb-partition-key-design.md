# 设计：kbId 作为 Milvus 独立字段并设为 PartitionKey

- 日期：2026-09-22
- 状态：已获用户批准（分节确认），待实现
- 范围：根 `pom.xml`、新增 `zhiliao-vector`、`zhiliao-ingestion`、`zhiliao-retrieval`、`zhiliao-app`（`application.yaml`）、`docker/local-dev.yml`、`docs/ops/`
- 取代关系：本设计**取代** `docs/superpowers/specs/2026-09-22-kb-permission-design.md` 第 6 节中"方案 A：Milvus metadata 补写 kbId + `metadataKey("kbId").isIn(...)` 过滤"的入库与过滤部分。该 spec 其余章节（接口收编 `/api/admin/*`、物理删除编排、前端页面整合、会话身份解析）全部保持有效。
- 同时取代 `docs/superpowers/plans/2026-09-22-kb-permission-fix.md` 中已按方案 A 编写的 **Task 5（入库 metadata 补写 kbId）** 与 **Task 8（`@MemoryId` + `IsIn(kbId)` 过滤）**；该计划其余任务不受影响。这两项在本设计的实现计划中会被重新表述。

## 1. 背景与动机

现状：kbId 以**字符串**写入 Milvus 的 `metadata` JSON（`DocumentConsumerProcessor.java:125`），检索侧用 `metadataKey("kbId").isIn(...)` 构造 filter（`KnowledgeRetrievalTool.java:71-73`）。

用户目标（已确认）：**性能与多租户隔离**——把 kbId 落为 Milvus 顶层 `Int64` 字段并设为 PartitionKey，使检索按 kbId 做分区裁剪，减少扫描面；接受为此把索引从 FLAT 换成 HNSW 并 drop 重建 collection。

## 2. 已确认的决策

| # | 决策 | 依据 |
|---|------|------|
| 1 | 目标为性能与隔离，接受 HNSW + drop 重建 | 用户明确选择 |
| 2 | 可见 kbId 恰好 1 个时构造 `kb_id == x`（命中 isolation），多个时构造 `kb_id in [...]` | isolation 仅对单值生效；IN 仍按其文档收窄分区 |
| 3 | `num_partitions = 64` | 预期 kbId 规模 ≤ 64 |
| 4 | 自研 store 放在**新增模块 `zhiliao-vector`**，彻底移除 `langchain4j-milvus-spring-boot-starter` | 用户确认；schema 所有权收归自己 |
| 5 | `metadata` JSON **保留**（只放 `chunkId`、`parentId`），仅 `kbId` 移出 | `RrfReranker.java:32-39` 从 metadata 读 `chunkId`/`parentId` |

## 3. 为什么必须自研（源码事实）

`langchain4j-milvus:1.17.0-beta27` 无法表达"额外标量字段 + PartitionKey"：

| 事实 | 位置 |
|------|------|
| 建表写死 4 个字段：`id`(VarChar,36,PK) / `text`(VarChar,65535) / `metadata`(JSON) / `vector`(FloatVector) | `CollectionOperationsExecutor.java:41-73` |
| 写入只写这 4 个字段 | `MilvusEmbeddingStore.java:291-306` |
| 过滤表达式只能映射为 `metadata["key"]` | `MilvusMetadataFilterMapper.java:132-134`、`CollectionRequestBuilder.java:74-76` |
| 整个 jar 无任何 partition key 相关代码 | `langchain4j-milvus-1.17.0-beta27-sources.jar` 全部 8 个类 |

因此"kbId 独立字段"必须自研；由于裁剪要求过滤表达式直接引用顶层字段，**读写两条路径都要绕过该 store**，所以整体替换而非并存。

补充事实：`langchain4j-core-1.17.0-beta27` 在本地仓库只有 `.lastUpdated` 失败标记，实际解析到 **1.17.0 稳定版**，接口与语义以 1.17.0 为准。

## 4. Milvus 约束（设计依据）

| 约束 | 内容 |
|------|------|
| 字段类型 | 分区键只能 `INT64` 或 `VARCHAR`；一个集合只能有一个分区键；**主键不能作分区键** |
| 分区管理 | 定义分区键后分区自动创建，不能再手工建/删分区，不能与手工分区共存 |
| `num_partitions` | 仅在存在分区键时可设，必须 > 0，服务端默认 64，上限 4096 |
| 裁剪条件 | 搜索表达式含分区键（`kb_id == x` 或 `kb_id in [...]`）时收窄到对应分区；无过滤则扫全部分区 |
| 索引 | 分区搜索由 **HNSW** 支持 |
| 隔离 | `partitionkey.isolation=true` 仅对 HNSW 生效，且仅对**单值**分区键过滤产生"每分区独立索引"的加速 |
| SDK 支持 | `FieldType.Builder.withPartitionKey(boolean)`、`CreateCollectionParam.Builder.withPartitionsNum(int)`、`withProperty(k,v)`、`IndexType.HNSW(5)` 均存在于 `milvus-sdk-java:2.5.9` |

## 5. 模块与依赖

### 新增模块 `zhiliao-vector`

```
zhiliao-vector/
├── pom.xml
└── src/main/java/org/liar/zhiliao/vector/
    ├── KbAwareEmbeddingStore.java      # 窄接口
    ├── MilvusKbEmbeddingStore.java     # 唯一实现，持有 MilvusServiceClient
    ├── MilvusProperties.java           # @ConfigurationProperties(prefix="zhiliao.milvus")
    └── MilvusStoreConfig.java          # @Configuration：装配 bean + 启动期 schema 校验
```

包根 `org.liar.zhiliao.vector` 位于启动类 `org.liar.zhiliao.LiarZhiliaoApplication` 的组件扫描范围内，无需额外注册。

### 接口边界

```java
public interface KbAwareEmbeddingStore extends EmbeddingStore<TextSegment> {

    /** 写入单个文档的切片：kbId 落为顶层分区键字段，不进入 metadata JSON */
    List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments, long kbId);

    /** kbIds == null 表示不过滤（admin）；size==1 → kb_id == x；size>1 → kb_id in [...] */
    EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request, List<Long> kbIds);
}
```

- `zhiliao-ingestion`：`DocumentConsumerProcessor` 注入 `KbAwareEmbeddingStore`（需要带 kbId 的写入方法）；`DocumentServiceImpl` 注入基接口 `EmbeddingStore<TextSegment>`（只用 `removeAll(ids)`，不必依赖 kbId API）
- `zhiliao-retrieval`：`KnowledgeRetrievalTool` 注入 `KbAwareEmbeddingStore`
- 注入点的 bean 类型是同一个 `MilvusKbEmbeddingStore`，基接口注入照常可解析

`EmbeddingStore`（1.17.0）要求实现 5 个抽象方法，本实现对每个入口的处理：

| 方法 | 处理 |
|------|------|
| `search(EmbeddingSearchRequest)` | **断言 `request.filter() == null`（非空则抛）**，然后委托 `search(request, null)`。绝不静默忽略 filter——否则调用方以为过滤了、实际扫了全部分区，是越权风险 |
| `addAll(List<Embedding>)` | 抛 `UnsupportedOperationException("kbId required: use addAll(embeddings, segments, kbId)")` |
| `add(Embedding)` / `add(String, Embedding)` / `add(Embedding, TextSegment)` | 同上抛异常（这三个入口都无法携带 kbId，会让分区键缺值） |
| `addAll(List<String> ids, List<Embedding>, List<TextSegment>)`（接口 default，原抛 `UnsupportedFeatureException`） | 覆盖为同样的 `UnsupportedOperationException` 并给出明确指引。接口 default 的 `addAll(embeddings, segments)` 会先生成 id 再落到这里，因此也被一并拦下 |
| `removeAll(Collection<String> ids)`（接口 default 原抛 `UnsupportedFeatureException`） | 覆盖：expr `id in [...]`（现删除路径依赖它） |
| `removeAll(Filter)` / `removeAll()` | 不覆盖，保持接口 default 抛异常——过滤语义已改为 kbId 参数，不再支持 metadata Filter |

### 依赖变更

| 位置 | 变更 |
|------|------|
| 根 `pom.xml` | `<modules>` 增加 `zhiliao-vector`；移除 `langchain4j-milvus-spring-boot-starter` 的 dependencyManagement 条目；新增 `milvus-sdk-java:2.5.9` 与 `langchain4j-core:1.17.0`（后者当前只有 `langchain4j` 主 jar 被管理）；**`io.grpc` 1.75.0 三个 CVE 固定项保留**（`milvus-sdk-java` 同样依赖 grpc） |
| `zhiliao-ingestion/pom.xml`、`zhiliao-retrieval/pom.xml` | 移除 starter，改依赖 `zhiliao-vector` |
| `zhiliao-app/src/main/resources/application.yaml` | milvus 配置段由 `langchain4j.milvus.*` 迁至 `zhiliao.milvus.*`，键名不变 |

`MilvusProperties` 键（`zhiliao.milvus.*`）：`host`、`port`、`collection-name`、`username`、`password`、`dimension`（可选，缺省取 `EmbeddingModel.dimension()`）、`num-partitions`（默认 64）。

starter 移除后不再存在自动装配的 `MilvusEmbeddingStore` bean，因此无需 `spring.autoconfigure.exclude`。

## 6. Collection schema 与启动期 ensure

`zhiliao_chunks` 字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VarChar(36) | 主键，`autoID=false`，UUID 由 store 生成 |
| `text` | VarChar(65535) | 切片文本 |
| `metadata` | JSON | 仅 `chunkId`、`parentId` |
| `vector` | FloatVector(dim) | dim 取 `EmbeddingModel.dimension()`，可被配置覆盖 |
| `kb_id` | Int64 | `isPartitionKey=true`，非空 |

建表参数：`withPartitionsNum(zhiliao.milvus.num-partitions，默认 64)` + `withProperty("partitionkey.isolation","true")`；向量索引 **HNSW / COSINE**，extra `{"M":16,"efConstruction":200}`；consistency `EVENTUALLY`（维持现状）。

启动期 ensure（`MilvusStoreConfig`）：

1. collection 不存在 → 建表 → 建 HNSW 索引 → `loadCollection`
2. collection 已存在 → `describeCollection` 校验：字段含 `kb_id` 且 `isPartitionKey=true`、vector 维度与配置一致。**任一项不符则抛出启动异常**，消息中包含集合名与"请先 `drop collection` 后重启（见 `docs/ops/rebuild-vectors.md`）"
3. 向量索引缺失属于**可非破坏性修复**的差异，不计入上述失败条件：缺失时自动 `createIndex` 并记 `warn` 日志。理由：索引不是数据布局不兼容，为了补索引而要求 drop collection 会造成不必要的数据丢失
4. **绝不自动 drop**：静默丢弃存量向量不可接受

## 7. 写入路径

`DocumentConsumerProcessor.process()` 第 7 步（现 `DocumentConsumerProcessor.java:117-135`）：

- 构造 `TextSegment` 的 metadata 时**删除** `.put("kbId", String.valueOf(doc.getKbId()))`，只保留 `chunkId`、`parentId`
- `milvusEmbeddingStore.addAll(embeddings, childSegmentsWithMeta)` 改为 `kbAwareEmbeddingStore.addAll(embeddings, childSegmentsWithMeta, doc.getKbId())`
- 返回值 `vectorIds` 回写 `zl_chunk.embedding_id` 的逻辑不变

单文档单 kbId，故 kbId 以标量参数传入。

## 8. 检索路径

`KnowledgeRetrievalTool.retrieveKnowledge()`：

- 删除 `Filter kbFilter` 的构造（现 `L70-73`）与 `metadataKey` 静态导入；保留 `principal` / `admin` / `visibleKbIds` 的解析逻辑（现 `L56-69`）不变
- `EmbeddingSearchRequest` 仍为 `maxResults=10`、`minScore=0.7`，但**不再挂 filter**
- 稠密检索改为 `kbAwareEmbeddingStore.search(request, admin ? null : visibleKbIds)`（现 `L137-148`）

store 内部 expr 规则（字段名 `kb_id`）：

| `kbIds` | expr |
|---------|------|
| `null` | 无 expr（扫全部分区，admin 语义） |
| size == 1 | `kb_id == 1` |
| size > 1 | `kb_id in [1,3]` |
| size == 0 | 正常路径不会到达（上游 `KnowledgeRetrievalTool` 已提前 `return ""`，现 `L66-69`）；store 仍显式返回空结果作为防御 |

`SearchParam`：`withOutFields(id, text, metadata)`、`withTopK(request.maxResults())`、`withMetricType(COSINE)`、`withConsistencyLevel(EVENTUALLY)`、`withVectorFieldName(vector)`。

结果映射**逐字复刻** `langchain4j` `Mapper` 语义（`Mapper.java:86-96` 映射、`Mapper.java:101-107` score 换算），否则检索行为漂移：

```java
double score = RelevanceScore.fromCosineSimilarity(rawCosineScore); // (cos + 1) / 2
EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(score, rowId, null, textSegment);
matches = matches.stream().filter(m -> m.score() >= request.minScore()).toList();
```

- 因 `minScore` 比较的是转换后的 relevance score，`minScore=0.7` 实际等价于 `cosine ≥ 0.4`
- `embedding` 传 `null`（不额外回查向量），与现状 `retrieveEmbeddingsOnSearch=false` 一致
- `metadata` JSON 反序列化回 `Metadata`；`text` 为空白时 `TextSegment` 为 `null`（同 `Mapper.java:109-123`）

## 9. 删除与重处理

`DocumentServiceImpl.delete` 与 `DocumentConsumerProcessor.cleanupExistingChunks` 继续使用 `removeAll(Collection<String> ids)`，expr 为 `id in [...]`，**不追加 `kb_id` 子句**：

- 追加布尔项会使删除变成"复杂布尔表达式删除"，`langchain4j` 文档标注其需要 `BOUNDED` 一致性，而当前为 `EVENTUALLY`
- 保持与现状一致的删除语义，降低风险；效率优化留给后续按需评估

## 10. 存量迁移

因启动期做 schema 校验并失败即拒绝启动，**drop 必须先于新版启动**：

```
1. 停旧版应用
2. drop Milvus collection `zhiliao_chunks`（9091 默认未映射，需临时开放，沿用现有 ops 文档做法）
3. TRUNCATE zl_chunk（父子切片全部失效，需重新切分）
4. 保留 zl_document 行与 MinIO 对象不变
5. 部署新版并启动（自动建表：64 分区 + HNSW + isolation；启动期校验通过）
6. 对全部文档触发 reprocess（复用已实现的 DocumentService.reprocess 发 MQ，重新解析/切分/embedding）
7. 校验：zl_document 全部 COMPLETED；抽查 Milvus 中 kb_id 字段有值
```

`docs/ops/rebuild-vectors.md` 需按上述流程改写：

- 补上"停应用"作为第 0 步（原文没有，而新版本启动即做 schema 校验，顺序错了会启动失败）
- 原文第 25 行"collection 由 langchain4j starter 在应用下次写入时自动重建"已失效——starter 被移除，建表改由 `zhiliao-vector` 在**启动期**完成
- 其余（临时开放 9091、`curl ... /collections/drop`、compose 服务名 `milvus`、TRUNCATE 与 `<user>` 取值提示、reprocess 循环）可沿用

## 11. 风险与实施期验证清单

| # | 风险 | 验证/应对 |
|---|------|-----------|
| 1 | ~~Milvus 版本需 ≥ 2.5.4（`partitionkey.isolation` 的起点）~~ | **已解决（2026-09-22 实测）**：本机运行的是 `milvus-standalone` / `milvusdb/milvus:v2.6.18`，≥ 2.5.4，隔离特性可用。注意本机容器由手工 `docker run` 启动，与仓库 `docker/local-dev.yml`（`zhiliao-*` 命名、`latest` tag）**不对应**，故不修改该文件 |
| 2 | isolation 开启后对多值 `kb_id in [...]` 的行为（文档要求"应只含单值"方能利用隔离） | 实测；若报错则移除 isolation 属性退化为普通分区裁剪，并在本 spec 记录降级 |
| 3 | 分区键字段是否自动建索引 | `describeCollection` 确认；若无索引且过滤报错，则显式 `createIndex(kb_id)` |
| 4 | `id in [...]` 删除在分区键集合 + `EVENTUALLY` 下是否可用 | 实测；失败则将 store 的 consistency 提升为 `BOUNDED`，并评估读延迟影响 |
| 5 | FLAT → HNSW 由精确检索变为近似检索，`minScore` 命中率可能变化 | 新旧 collection 跑同一批查询对比命中率与分数，确认 `(cos+1)/2` 换算正确、召回可接受 |
| 6 | 文档措辞为搜索"应包含"分区键过滤，无过滤则扫全部分区 | 实测 admin 路径（`kbIds=null`）确实返回结果 |
| 7 | 分区后 admin 检索扫全部 64 个分区，可能比现状慢 | 接受；若延迟劣化明显，后续再评估（本期不做） |

## 12. 测试

### 单元测试（无需真连 Milvus）

- **expr 构造**：抽为包可见静态方法，直接断言字符串——`null` → 无 expr；单值 → `kb_id == 1`；多值 → `kb_id in [1,3]`
- **score 换算**：给定 raw cosine 断言 `match.score() == (cos+1)/2`；`minScore` 边界（恰好等于阈值应保留）
- **metadata 映射**：JSON → `TextSegment` 保留 `chunkId`/`parentId`，且**不含 `kbId`**；`text` 空白 → `null`
- **写入保护**：`addAll(List<Embedding>)`、`addAll(embeddings, segments)`、3 个 `add(...)` 重载一律抛 `UnsupportedOperationException`
- **filter 防误用**：`search(request)` 且 `request.filter() != null` → 抛异常，不得静默降级为全分区扫描
- **删除 expr**：`removeAll(ids)` 生成 `id in [...]`
- **schema 校验**：mock `describeCollection` 返回缺 `kb_id` / 维度不符 → 断言抛异常且消息含 drop 提示

### 改造现有测试

- `KnowledgeRetrievalToolTest`：mock 换为 `KbAwareEmbeddingStore`；admin → `search(req, null)`；普通用户 → `search(req, List.of(2L))` 及多库场景；会话不存在、无可见库 → 空结果且不触碰 store；缓存相关断言不变
- `DocumentConsumerProcessorTest`、`DocumentServiceImplTest`：适配注入类型与 `addAll(embeddings, segments, kbId)` 调用；断言传入 kbId 等于 `doc.getKbId()`、写入的 metadata 不含 kbId
- `ChunkRepositoryTest`、`PgBm25SearcherTest` 不受影响

### 手工验证

- 迁移第 7 步校验
- 跨部门用户提问同一问题，回答不引用无权限知识库内容
- 删除文档后检索不再命中，且 `zl_chunk` / Milvus / MinIO 三处数据消失

## 13. 不在本次范围

- 把 `chunkId` / `parentId` 也提升为顶层标量字段（`metadata` JSON 继续保留）
- admin 单独走一个无分区 collection
- 删除表达式追加分区键以提升删除效率
- `zl_chunk` 表结构变更（kb_id 仍通过 `zl_document` 关联）
