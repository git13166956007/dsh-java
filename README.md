# dsh-java

Java 版 DeepSeek Harness 的最小运行时内核。

当前版本是 Java 17 + Gradle + Spring Boot 的正式工程骨架，先实现可复用的运行时基础语义：

- 插件生命周期与 `ServiceLoader` 发现入口
- 服务注册、事件总线、可回收副作用
- JSONL 会话事件存储
- 本地工具与模型适配接口
- Java 17、Gradle、Spring Boot 4
- Spring Boot 宿主 API：`GET /api/v1/health`

项目结构说明见 [`docs/architecture.md`](docs/architecture.md)。

## 快速运行

```bash
./gradlew test
./gradlew bootRun
```

启动后访问 `http://localhost:8080/api/v1/health`。如果 8080 已被其他服务占用，可以运行 `./gradlew bootRun --args='--server.port=18080'`。

如果 IDEA 没有自动识别 JDK，项目 SDK 和 Gradle JVM 选择 Java 17 或更高版本。

## 设计边界

`DshRuntime` 是插件宿主，插件通过 `PluginContext` 注册服务、监听事件和登记清理动作。Spring Boot 只负责启动宿主和暴露 API，不进入插件内核。`ToolProvider` 和 `ChatModel` 是后续接入 MCP 与 OpenAI-compatible API 的边界。

后续实现顺序：

1. 接入 MCP Java SDK，提供 `McpToolProvider`。
2. 实现流式 `ChatModel` 和 Agent Loop。
3. 增加审批策略、工作区文件工具和 shell 工具。
4. 增加插件 JAR 版本、依赖排序和受控 reload。

## License

MIT
