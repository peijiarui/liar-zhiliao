# 知了知了 - 企业级 AI 知识库

<div align="center">
  <p><em>基于 LangChain4j + Spring Boot 的企业级智能问答系统</em></p>
</div>

## 项目简介

**知了知了** 是一款基于 **LangChain4j** 框架构建的企业级 AI 知识库问答系统。项目采用 **Spring Boot 3** 作为基础框架，支持多种主流大语言模型，提供流式对话、会话记忆管理等核心能力，可作为企业内部智能问答、知识检索的基础设施。

> **核心定位**：为企业提供开箱即用的 AI 问答能力，支持多模型切换、对话上下文管理，便于二次扩展和集成。

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.5.16 | 基础框架 |
| LangChain4j | 1.17.0 | AI 编排框架 |
| Spring WebFlux | - | 流式响应支持 |
| Maven | - | 构建管理 |
| Lombok | - | 代码简化 |

## 支持的模型

| 厂商 | 模型 | API 类型 |
|------|------|----------|
| DeepSeek | deepseek-v4-flash | OpenAI 兼容 |
| DeepSeek | deepseek-v4-pro | OpenAI 兼容 |
| 智谱 AI | GLM-5.1 | OpenAI 兼容 |
| 智谱 AI | GLM-5.2 | OpenAI 兼容 |
| Anthropic | claude-sonnet-4.6 | Anthropic API |

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.6+
- 对应 AI 模型的 API Key

### 配置

1. 配置环境变量：

```bash
export DEEPSEEK_API_KEY=your_api_key_here
```

2. 修改 `application.yaml`（可选）：

```yaml
langchain4j:
  open-ai:
    chat-model:
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
      model-name: deepseek-v4-flash
```

### 启动

```bash
# 构建项目
mvn clean package -DskipTests

# 启动服务
mvn spring-boot:run
```

服务启动后默认监听 `http://localhost:8080`。

### Docker 部署（可选）

```bash
# 构建镜像
docker build -t liar-zhiliao .

# 运行容器
docker run -p 8080:8080 -e DEEPSEEK_API_KEY=your_key liar-zhiliao
```

## API 文档

### 对话接口

```http
GET /chat/chat?memoryId={sessionId}&message={content}
```

流式返回对话内容，支持 Server-Sent Events (SSE) 格式。

**参数说明：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| memoryId | String | 是 | 会话 ID，用于维持多轮对话上下文 |
| message | String | 是 | 用户输入的对话内容 |

**示例：**

```bash
curl -N "http://localhost:8080/chat/chat?memoryId=user001&message=你好"
```

## 项目结构

```
liar-zhiliao/
├── src/main/java/org/liar/ai/liarziliao/
│   ├── LiarZhiliaApplication.java      # 应用入口
│   ├── config/
│   │   └── BeansConfig.java         # 会话记忆配置
│   ├── controller/
│   │   └── ChatController.java      # 对话 API 控制器
│   ├── service/
│   │   └── ChatService.java         # AI 服务接口定义
│   ├── repository/
│   │   └── CustomChatMemoryStore.java # 自定义记忆持久化
│   └── enums/
│       └── AiModelEnum.java         # AI 模型枚举
├── src/main/resources/
│   ├── application.yaml             # 应用配置
│   └── system-prompt.md             # 系统提示词
└── pom.xml                          # Maven 依赖管理
```

## 核心功能

### 1. 流式对话

基于 Spring WebFlux + LangChain4j Reactor 集成，支持流式 SSE 响应，实现打字机效果输出。

### 2. 会话记忆管理

- 内置 `MessageWindowChatMemory`，默认保留最近 20 轮对话
- 支持 `ChatMemoryStore` 扩展，可对接 Redis、MySQL 等实现持久化
- 通过 `memoryId` 区分不同会话

### 3. 多模型支持

通过 `AiModelEnum` 枚举管理多个厂商模型，预留了扩展接口，方便接入更多模型。

### 4. 自定义系统提示词

系统提示词通过外部资源文件 `system-prompt.md` 管理，无需修改代码即可调整 AI 角色和行为。

## 开发指南

### 自定义记忆存储

实现 `ChatMemoryStore` 接口，将其注入 Spring 容器：

```java
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {
    // 实现 getMessages、updateMessages、deleteMessages 方法
}
```

### 修改系统提示词

编辑 `src/main/resources/system-prompt.md` 文件即可。

## 环境要求

- **Java**: 17+
- **Maven**: 3.6+
- **内存**: 最小 256MB，推荐 512MB+
- **网络**: 需要访问对应 AI 模型的 API 端点

## 路线图

- [x] 多轮对话记忆
- [x] 流式 SSE 响应
- [x] 多模型支持
- [ ] 知识库文档上传与管理
- [ ] RAG 向量检索集成
- [ ] 对话历史持久化（Redis/MySQL）
- [ ] Web 管理后台
- [ ] 权限与用户管理
- [ ] API 调用统计与监控
- [ ] 多轮对话上下文压缩

## 贡献指南

欢迎提交 Issue 和 Pull Request 参与项目贡献。

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交改动 (`git commit -m 'feat: add amazing feature'`)
4. 推送分支 (`git push origin feature/amazing-feature`)
5. 提交 Pull Request

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

---

<div align="center">
  <sub>Built with ❤️ using LangChain4j & Spring Boot</sub>
</div>
