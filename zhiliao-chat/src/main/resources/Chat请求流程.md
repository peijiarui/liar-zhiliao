# Chat 请求流程

## 入口

```
GET /chat/chat?memoryId=X&message=Y
  → ChatController.chat()                        # ChatController.java:22
    → ChatService.chat(memoryId, message)        # @AiService 接口，LangChain4j 运行时生成
      → Flux<String> SSE 流式响应 (text/html)
```

`ChatService` 绑定的组件：

| 引用 | Bean 名称 | 类型 | 作用 |
|------|-----------|------|------|
| `streamingChatModel` | `openAiStreamingChatModel` | `OpenAiStreamingChatModel` | 流式调用 DeepSeek |
| `chatModel` (tools 内) | `openAiChatModel` | `OpenAiChatModel` | 非流式调用 DeepSeek（查询改写） |
| `chatMemoryProvider` | `chatMemoryProvider` | `ChatMemoryProvider` | 按 memoryId 管理会话 |
| `chatMemory` | `chatMemory` | `ChatMemory` | 会话记忆对象 |
| `tools` | `knowledgeRetrievalTool` | `KnowledgeRetrievalTool` | 知识检索工具 |
| `@SystemMessage` | `system-prompt.md` | — | AI 角色设定 |

---

## 架构总览

```mermaid
graph TB
    Client[客户端]

    subgraph Controller [zhiliao-chat]
        CC[ChatController]
    end

    subgraph Service [zhiliao-chat]
        CS[ChatService<br/>@AiService]
        SPM[system-prompt.md]
    end

    subgraph Memory [记忆层]
        CM[ChatMemoryProvider]
        MWC[MessageWindowChatMemory<br/>maxMessages=20]
        CMS[CustomChatMemoryStore]
        REDIS[(Redis<br/>1天 TTL)]
    end

    subgraph Tool [知识检索]
        KRT[KnowledgeRetrievalTool]
        EM[text-embedding-v4]
        MILVUS[(Milvus)]
        PG[(PostgreSQL<br/>zl_chunk)]
    end

    subgraph LLM [模型层]
        DS_STREAM[DeepSeek 流式]
        DS_SYNC[DeepSeek 非流式]
    end

    Client -->|GET /chat/chat| CC
    CC --> CS

    CS -->|system prompt| SPM
    CS -->|读写会话| CM
    CM --> MWC
    MWC --> CMS
    CMS --> REDIS

    CS ==自由对话==> DS_STREAM
    CS ==知识问答==> KRT

    KRT -->|查询改写| DS_SYNC
    KRT -->|稠密检索| EM --> MILVUS
    KRT -->|稀疏检索| PG
```

---

## 路径 A：自由对话（不涉及知识库）

**触发条件**：问候、感谢、闲聊、自我介绍等非知识类问题。

```mermaid
sequenceDiagram
    actor C as 客户端
    participant CS as ChatService
    participant LLM as DeepSeek(流式)
    participant R as Redis

    C->>CS: GET /chat/chat?memoryId=X&message=Y
    CS->>R: GET memoryId → 历史消息
    R-->>CS: JSON 消息列表
    CS->>LLM: System: system-prompt.md<br/>User: Y
    Note over LLM: 判断无需检索 → 直接回答
    LLM-->>CS: 流式返回回答
    CS-->>C: Flux<String> SSE
    CS->>R: SET memoryId ← 更新后的消息(1天 TTL)
```

---

## 路径 B：知识问答（涉及知识库）

**触发条件**：询问公司制度、政策、流程、产品信息等企业内部知识。

```mermaid
sequenceDiagram
    actor C as 客户端
    participant CS as ChatService<br/>(LangChain4j)
    participant KRT as KnowledgeRetrievalTool
    participant LLM_S as DeepSeek(流式)
    participant LLM_N as DeepSeek(非流式)
    participant EMB as text-embedding-v4
    participant MIL as Milvus
    participant PG as PostgreSQL
    participant R as Redis

    C->>CS: GET /chat/chat?memoryId=X&message=Y
    CS->>R: ① GET memoryId → 历史消息
    R-->>CS: JSON 消息列表

    CS->>LLM_S: ② 首次调用 (system+history+user+tools)
    Note over LLM_S: 判断需要知识 → 返回 tool_call
    LLM_S-->>CS: tool_call: retrieveKnowledge(query)

    CS->>KRT: ③ 执行检索工具
    KRT->>LLM_N: ④ 查询改写：优化为多个子查询
    LLM_N-->>KRT: 改写后的查询列表

    loop 每个子查询
        KRT->>EMB: ⑤ 文本向量化
        EMB-->>KRT: embedding vector
        KRT->>MIL: ⑥ 稠密检索 (topK=10, minScore=0.5)
        MIL-->>KRT: EmbeddingMatch[]
        KRT->>PG: ⑦ BM25 全文检索 (topK=10)
        PG-->>KRT: SparseSearchResult[]
    end

    Note over KRT: ⑧ RRF 融合 + 排序取 topK=10

    loop topK 结果
        opt 有 parentId
            KRT->>PG: ⑨ 查 parent 完整内容
            PG-->>KRT: parent content
        end
    end

    KRT-->>CS: ⑩ 返回上下文字符串

    CS->>LLM_S: ⑪ 二次调用 (原消息 + tool result)
    Note over LLM_S: 结合 context 生成回答
    LLM_S-->>CS: 流式返回最终回答
    CS-->>C: ⑫ Flux<String> SSE
    CS->>R: ⑬ SET memoryId ← 更新后的消息(1天 TTL)
```

### 检索流水线内部流程

```mermaid
flowchart TD
    A[用户原始问题] --> B[查询改写<br/>DeepSeek 非流式]
    B --> C{是否成功?}
    C -->|是| D[子查询列表<br/>q1, q2, ...]
    C -->|否| E[回退: 使用原始查询]

    D --> F
    E --> F

    F[遍历每个子查询] --> G[稠密检索]
    F --> H[稀疏检索]

    G --> G1[embedding 向量化<br/>text-embedding-v4]
    G1 --> G2[Milvus 相似度搜索<br/>topK=10, minScore=0.5]

    H --> H1[PG tsvector BM25<br/>WHERE chunk_type='child']
    H1 --> H2[ORDER BY ts_rank<br/>LIMIT 10]

    G2 --> I[RRF 融合排序<br/>K=60, topK=10]
    H2 --> I

    I --> J{有 parentId?}
    J -->|是| K[查 parent 完整内容<br/>SELECT content FROM zl_chunk]
    J -->|否| L[直接使用 child content]

    K --> M[合并为上下文字符串]
    L --> M
```

---

## 外部资源调用统计

以一次典型知识问答为例：查询改写产生 **2 个子查询**，topK=10 中有 **5 个 chunk 带 parentId**。

```mermaid
flowchart LR
    subgraph Legend[一次知识问答的总调用次数]
        L1["🥇 DeepSeek(流式) : 2次"]
        L2["🥇 DeepSeek(非流式) : 1次"]
        L3["🥇 向量模型 : 2次"]
        L4["🥇 Milvus : 2次"]
        L5["🥇 PostgreSQL : 7次"]
        L6["🥇 Redis : 2次"]
    end
```

| 资源 | Bean | 调用次数 | 时机与目的 |
|------|------|---------|-----------|
| **DeepSeek 流式** | `openAiStreamingChatModel` | **2 次** | ① 首次：发消息，LLM 返回 tool_call<br>② 二次：发消息 + tool 返回的 context，LLM 生成最终回答 |
| **DeepSeek 非流式** | `openAiChatModel` | **1 次** | `rewriteQuery()` 内改写用户问题为多个检索关键词 |
| **向量模型** | `embeddingModel` | **2 次** | 每个子查询 1 次：文本 → 向量，供 Milvus 检索 |
| **Milvus** | `milvusEmbeddingStore` | **2 次** | 每个子查询 1 次：向量相似度搜索 |
| **PostgreSQL** | `ChunkRepository` | **7 次** | ① BM25 搜索(每个子查询 1 次) : 2 次<br>② 父文档替换(每带 parentId 的 chunk 1 次) : 5 次 |
| **Redis** | `CustomChatMemoryStore` | **2 次** | ① GET：读历史消息<br>② SET：写更新后的消息(1天 TTL) |

> **自由对话对比**：仅 1 次 DeepSeek 流式调用 + 2 次 Redis 调用，其余均为 0。

---

## 关键设计要点

1. **工具驱动的 RAG**：知识检索通过 `@Tool` 注入，LLM 自主判断是否调用，非强制检索
2. **混合检索 (Hybrid Search)**：稠密（语义向量）+ 稀疏（关键词 BM25）双路互补，RRF 无参数融合（K=60）
3. **查询改写**：复杂问题 → 多个子查询，提高召回率；失败时回退原始查询
4. **父子文档替换**：命中 child chunk → 替换为 parent 完整内容，保证上下文完整性
5. **记忆持久化**：Redis 1 天 TTL，20 条消息滑动窗口
6. **双模型 bean**：流式 `OpenAiStreamingChatModel` 用于对话，非流式 `OpenAiChatModel` 用于查询改写，指向同一 DeepSeek API
