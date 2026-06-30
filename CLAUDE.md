# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。

## 构建与测试命令

```bash
# 构建项目（默认跳过测试，见 pom.xml surefire 配置）
mvn clean package

# 运行全部测试
mvn test -DskipTests=false

# 运行单个测试类
mvn test -Dtest=LiarRagApplicationTests -DskipTests=false

# 运行单个测试方法
mvn test -Dtest=LiarRagApplicationTests#contextLoads -DskipTests=false

# 启动应用
mvn spring-boot:run -DskipTests=true

# 查看依赖树
mvn dependency:tree
```

> **注意：** `pom.xml` 中 `maven-surefire-plugin` 默认配置了 `<skipTests>true</skipTests>`，所以测试默认被跳过。运行测试时务必加上 `-DskipTests=false`。

## 环境变量

| 变量 | 必填 | 说明 |
|----------|----------|-------------|
| `DEEPSEEK_API_KEY` | 是 | DeepSeek API 密钥 |

## 架构概览

### 核心模式：LangChain4j @AiService

项目的关键架构决策是使用 LangChain4j 的 **`@AiService`** 注解在一个 **接口**（`ChatService`）上，而非类。LangChain4j 在运行时生成实现——这是标准的 LangChain4j AI 服务模式。

该服务使用 **`EXPLICIT`** 显式装配模式，所有依赖（chat model、streaming model、chat memory、memory provider）必须手动定义为 Spring Bean 并通过名称引用：

- `openAiChatModel` / `openAiStreamingChatModel` — 由 `langchain4j-open-ai-spring-boot-starter` 根据 `application.yaml` 配置自动装配
- `chatMemory` / `chatMemoryProvider` — 在 `BeansConfig.java` 中手动定义

如需为 `ChatService` 添加 AI 工具/函数调用能力，需在接口中添加带 `@Tool` 注解的方法。

### 请求流程

```
客户端 → GET /chat/chat?memoryId=X&message=Y
         → ChatController (WebFlux, produces text/html)
           → ChatService (LangChain4j @AiService, 流式)
             → OpenAiStreamingChatModel (通过 OpenAI 兼容接口调用 DeepSeek)
               → DeepSeek API
           ← Flux<String> SSE 流式响应
         ← 客户端
```

每个请求携带 `memoryId`——`ChatMemoryProvider` 根据 memoryId 创建/获取对应的 `MessageWindowChatMemory`（20 条消息窗口），从而维持多轮对话上下文。

### 配置架构

**`application.yaml`** 使用 `langchain4j.open-ai.*` 命名空间，即使目标 API 是 DeepSeek。这是因为 LangChain4j 的 OpenAI starter 使用 OpenAI 兼容协议通信，`base-url` 指向 DeepSeek 的 API 地址。切换不同供应商（如智谱）需要修改 `base-url` 和 `api-key`。

**`AiModelEnum`** 是一个**尚未接入运行时**的模型注册表。它定义了 DeepSeek、智谱和 Claude 的供应商基础 URL 和模型名称，但当前激活的模型完全由 `application.yaml` 控制。该枚举是为未来动态模型切换预留的。

### 关键文件

| 文件 | 职责 |
|------|------|
| `ChatService.java` | AI 服务接口——LangChain4j 生成实现。通过 `@SystemMessage(fromResource = "system-prompt.md")` 加载系统提示词。 |
| `ChatController.java` | REST 控制器——映射 `GET /chat/chat`，返回 `Flux<String>` 实现 SSE 流式响应。 |
| `BeansConfig.java` | 定义 `ChatMemory`（20 条消息窗口）和 `ChatMemoryProvider` Bean，提供多轮对话支持。 |
| `CustomChatMemoryStore.java` | `ChatMemoryStore` 实现存根——当前返回空列表，未做任何持久化。预留用于对接 Redis/MySQL。**尚未注入 BeansConfig 使用。** |
| `AiModelEnum.java` | 模型枚举，包含供应商基础 URL。当前仅作信息参考。 |
| `system-prompt.md` | `ChatService` 加载的系统提示词。编辑此文件可在不修改代码的情况下改变 AI 角色和行为。 |

### 流式响应

聊天使用 **Spring WebFlux**（`Flux<String>`）实现 SSE 流式响应（打字机效果）。`langchain4j-reactor` 桥接了 LangChain4j 的流式能力与 Reactor/WebFlux。Controller 需设置 `produces = "text/html;charset=utf-8"`。

### 添加新功能（工具调用/函数调用）

若需为 AI 添加工具调用/函数调用能力：

1. 在 `ChatService` 接口中添加带 `@Tool` 注解的方法
2. 在独立的 Service 类中实现工具逻辑
3. 工具类需注册为 Spring Bean，LangChain4j 会自动发现它

### 约束

1. 不要主动执行 `git` 命令，需要用户自行执行
