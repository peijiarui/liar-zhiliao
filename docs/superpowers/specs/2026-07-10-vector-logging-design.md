# RAG 检索关键节点日志设计

## 目标

在 `/chat` 接口执行过程中，在向量模型调用、向量数据库查询及结果返回三个关键节点打印日志，便于调试和监控。

## 改动文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `zhiliao-retrieval/.../router/EmbeddingQueryRouter.java` | 修改 | `route()` 中调用 `embed()` 前加向量调用日志 |
| `zhiliao-retrieval/.../retriever/CustomContentRetriever.java` | **新建** | 替代 `EmbeddingStoreContentRetriever`，各节点输出日志 |
| `zhiliao-retrieval/.../config/RetrievalConfig.java` | 修改 | 注入配置，用 `CustomContentRetriever` 替换，传给 Router |

## 日志节点

### 节点 1：EmbeddingQueryRouter.route() — 路由阶段的向量调用

在 `route()` 调用 `embeddingModel.embed(text)` 之前插入：

```
========开始调用向量模型========
base-url : https://llm-kyz5903txm3fyz8f.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
model-name : text-embedding-v4
text : <用户查询文本>
```

`base-url` 和 `model-name` 通过 `@Value("${langchain4j.open-ai.embedding-model.base-url}")` 和 `@Value("${langchain4j.open-ai.embedding-model.model-name}")` 注入。

### 节点 2-3：CustomContentRetriever.retrieve() — 检索阶段

新类接受 `EmbeddingModel` + `EmbeddingStore<TextSegment>` + 配置，手动完成检索并记录日志：

```
========开始调用向量模型========
base-url : <同上>
model-name : <同上>
text : <用户查询文本>
========开始查询向量数据库========
========向量数据库查询结果========
<embeddingId1> ---> 0.6
<embeddingId2> ---> 0.5
```

### 配置注入

`RetrievalConfig` 增加 `@Value` 注入：

```java
@Value("${langchain4j.open-ai.embedding-model.base-url}")
private String embeddingBaseUrl;

@Value("${langchain4j.open-ai.embedding-model.model-name}")
private String embeddingModelName;
```

将以上参数注入 `EmbeddingQueryRouter` 和 `CustomContentRetriever`。

## 日志级别

使用 `log.info`，可在 `application.yaml` 中通过 `logging.level.org.liar.zhiliao.retrieval` 控制。
