# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 构建与测试命令

```bash
# 构建全部模块（默认跳过测试）
mvn clean package

# 构建指定模块
mvn clean package -pl zhiliao-chat -am

# 运行全部测试
mvn test -DskipTests=false

# 运行单个测试类
mvn test -Dtest=LiarZhiliaApplicationTests -DskipTests=false

# 启动应用（入口在 zhiliao-app 模块）
mvn spring-boot:run -pl zhiliao-app

# 查看依赖树
mvn dependency:tree
```

> **注意：** `pom.xml` 中 `maven-surefire-plugin` 默认配置了 `<skipTests>true</skipTests>`，运行测试时务必加上 `-DskipTests=false`。

## 环境变量

| 变量 | 必填 | 说明 |
|----------|----------|-------------|
| `DEEPSEEK_API_KEY` | 是 | DeepSeek API 密钥 |

## 项目结构（多模块 Maven 项目）

项目已从单模块重构为 7 个 Maven 模块，根 pom 为 `pom.xml`：

| 模块 | 职责 | 状态 |
|------|------|------|
| `zhiliao-common` | 共享常量（`AiModelConstants`）、工具类（`LocalFileStore`） | 活跃 |
| `zhiliao-ingestion` | 数据接入（文档上传、解析、分块） | 占位 |
| `zhiliao-retrieval` | 向量检索（`InMemoryEmbeddingStore` + `ContentRetriever`） | 活跃 |
| `zhiliao-chat` | AI 对话编排（LangChain4j `@AiService`、流式输出、Redis 记忆持久化） | 活跃 |
| `zhiliao-auth` | 认证与授权 | 占位 |
| `zhiliao-admin` | 管理后台 | 占位 |
| `zhiliao-app` | Spring Boot 启动入口，组装所有模块 | 活跃 |

**关键包路径：** `org.liar.zhiliao.*`

## 核心架构

### 请求流程

```
客户端 → GET /chat/chat?memoryId=X&message=Y
         → ChatController (WebFlux, produces text/html)
           → ChatService (LangChain4j @AiService, 流式)
             → contentRetriever (RAG 检索)
             → OpenAiStreamingChatModel (OpenAI 兼容接口调用 DeepSeek)
               → DeepSeek API
           ← Flux<String> SSE 流式响应
         ← 客户端
```

### LangChain4j @AiService

核心模式：在 **接口**（`ChatService.java:14`）上使用 `@AiService` 注解，LangChain4j 在运行时生成实现。

使用 **`EXPLICIT`** 显式装配模式，通过名称引用 Spring Bean：

- `openAiChatModel` / `openAiStreamingChatModel` — 由 `langchain4j-open-ai-spring-boot-starter` 根据 `application.yaml` 自动装配
- `chatMemory` / `chatMemoryProvider` — 在 `ChatMemoryConfig.java` 中定义，使用 `MessageWindowChatMemory`（20 条消息窗口）
- `contentRetriever` — 在 `RetrievalConfig.java` 中定义，连接 RAG 管道
- `CustomChatMemoryStore` — 基于 Redis（`StringRedisTemplate`）的记忆持久化，1 天 TTL

### RAG 检索

`RetrievalConfig.java` 在启动时从 classpath:`docs/` 加载文档，构建 `InMemoryEmbeddingStore`，并通过 `EmbeddingStoreContentRetriever`（minScore=0.5, maxResults=3）提供检索能力，已注入 `ChatService`。

### 配置架构

**`application.yaml`**（位于 `zhiliao-app`）使用 `langchain4j.open-ai.*` 命名空间，通过 OpenAI 兼容协议调用 DeepSeek API。切换供应商只需修改 `base-url`、`api-key` 和 `model-name`。

**`AiModelConstants.java`**（位于 `zhiliao-common`）定义了 DeepSeek 和智谱的 base URL 与模型名称常量。

## 关键文件

| 文件 | 模块 | 职责 |
|------|------|------|
| `ChatService.java` | zhiliao-chat | `@AiService` 接口，接收 `memoryId` + 用户消息，返回 `Flux<String>` |
| `ChatController.java` | zhiliao-chat | REST 控制器，`GET /chat/chat` |
| `ChatMemoryConfig.java` | zhiliao-chat | 定义 `ChatMemory`、`ChatMemoryProvider` + `CustomChatMemoryStore` |
| `CustomChatMemoryStore.java` | zhiliao-chat | Redis 实现的 `ChatMemoryStore`（1 天 TTL） |
| `RetrievalConfig.java` | zhiliao-retrieval | 初始化 `InMemoryEmbeddingStore` + `ContentRetriever` |
| `AiModelConstants.java` | zhiliao-common | DeepSeek/智谱的 base URL 和模型名称常量 |
| `LocalFileStore.java` | zhiliao-common | 本地文件存储工具（`data/memory/` 目录） |
| `application.yaml` | zhiliao-app | 应用配置（DeepSeek、Redis、日志等） |
| `system-prompt.md` | zhiliao-chat | 系统提示词，定义 AI 角色 |
| `LiarZhiliaApplication.java` | zhiliao-app | Spring Boot 启动入口 |

## 添加工具调用

1. 在 `ChatService` 接口中添加带 `@Tool` 注解的方法
2. 在独立的 Service 类中实现工具逻辑
3. 工具类需注册为 Spring Bean，LangChain4j 会自动发现

## 约束

1. 不要主动执行 `git` 命令，需要用户自行执行
