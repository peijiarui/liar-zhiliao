# LocalFileStore 设计文档

## 概述

基于本地文件的持久化工具类 `LocalFileStore`，用于替代数据库作为 `ChatMemoryStore` 的消息持久化方案。提供基于 TTL 的自动过期能力，实现"记忆一天有效期"的业务需求。

## 存储路径

- **根目录**: `./data/memory/`（相对于进程工作目录，非 classpath 资源）
- **文件命名**: `{memoryId}.json`
  - `memoryId` 需做文件名安全过滤（替换路径分隔符、`.`、`..` 等特殊字符）
  - Unicode 字符保持原样，仅转义文件系统敏感字符

## 文件格式

单个 JSON 文件，序列化 `List<ChatMessage>`：

```json
[
  {
    "@class": "dev.langchain4j.data.message.UserMessage",
    "text": "你好",
    ...
  },
  {
    "@class": "dev.langchain4j.data.message.AiMessage",
    "text": "你好，有什么可以帮你的？",
    ...
  }
]
```

使用 Jackson 的 `activateDefaultTyping`（`As.PROPERTY`）在 JSON 中保留 `@class` 多态类型信息，确保反序列化时能正确还原 `ChatMessage` 的各种子类。

## 过期策略

**惰性检查**，无定时任务：

- `read()` 时检查文件的最后修改时间，如果超过 24 小时则删除并返回空
- `save()` / `delete()` 内部先触发 `read()` 类似检查（可选，避免写入已过期文件）
- 清理时机由业务调用方（`ChatMemoryStore`）驱动

判断逻辑：

```java
boolean isExpired(Path file) {
    long lastModified = Files.getLastModifiedTime(file).toMillis();
    return System.currentTimeMillis() - lastModified > 24 * 60 * 60 * 1000;
}
```

## 接口定义

```java
@Component
@AllArgsConstructor
public class LocalFileStore {

    // 默认 24 小时 TTL
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String FILE_EXT = ".json";
    private static final String MEMORY_DIR = "./data/memory/";

    private final ObjectMapper objectMapper;
    private final Path storageDir;
    private final Duration ttl;

    // ====== 公开方法 ======

    // 存储数据（覆写文件）
    <T> void save(String id, T data);

    // 读取数据（含过期检查，过期返回 empty）
    <T> Optional<T> read(String id, Class<T> type);

    // 读取数据（泛型类型，如 List<ChatMessage>）
    <T> Optional<T> read(String id, TypeReference<T> typeRef);

    // 删除指定文件
    void delete(String id);

    // ====== 内部方法 ======

    private Path filePath(String id);     // 生成路径 + 文件名安全处理
    private boolean isExpired(Path file);  // 过期检查
    private void ensureDir();             // 确保目录存在
}
```

## 行为说明

| 方法 | 文件存在 & 未过期 | 文件存在 & 已过期 | 文件不存在 |
|------|-------------------|-------------------|-----------|
| `save()` | 覆写 | 覆写（相当于新建） | 创建 |
| `read()` | 返回数据 | 删除文件，返回 `empty` | 返回 `empty` |
| `delete()` | 删除 | 删除 | 无操作 |

## 异常处理

- 文件 I/O 异常包装为运行时异常，不抛出受检异常
- 目录创建失败抛出 `RuntimeException`
- 序列化/反序列化失败抛出 `RuntimeException`

## 测试要点

- 正常读写 JSON 文件
- 过期文件 read 时自动删除
- 文件名安全处理（含特殊字符的 memoryId）
- 目录自动创建
- 并发读写同一文件
- 空列表的序列化/反序列化
