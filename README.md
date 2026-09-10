# dsh-java

Java 版 DeepSeek Harness 的最小运行时内核。

当前版本是 Java 17 + Gradle + Spring Boot 的正式工程骨架，先实现可复用的运行时基础语义：

- 插件生命周期与 `ServiceLoader` 发现入口
- 服务注册、事件总线、可回收副作用
- JSONL 会话事件存储
- 本地工具与模型适配接口
- Java 17、Gradle、Spring Boot 4
- Spring Boot 宿主 API：`GET /api/v1/health`
- 最小 Agent Loop：`POST /api/v1/chat`

项目结构说明见 [`docs/architecture.md`](docs/architecture.md)。

## 快速运行

```bash
./gradlew test
./gradlew bootRun
```

启动后访问 `http://localhost:8080/api/v1/health`。如果 8080 已被其他服务占用，可以运行 `./gradlew bootRun --args='--server.port=18080'`。

启动 Vue 调试前端：

```bash
cd frontend
npm install
VITE_API_TARGET=http://localhost:8080 npm run dev
```

打开 `http://localhost:5173`。前端开发服务器会把 `/api` 请求代理到 Spring Boot。

配置 DeepSeek API Key 后运行智能体：

```bash
export DEEPSEEK_API_KEY=your-api-key
# 如果本机通过代理访问外网：
export DEEPSEEK_PROXY_HOST=127.0.0.1
export DEEPSEEK_PROXY_PORT=7897
./gradlew bootRun
```

调用：

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"现在几点？"}'
```

当前内置了 `time.now` 工具。模型客户端是 OpenAI-compatible 的 DeepSeek Chat Completions 适配器，MCP 工具适配将在 `ToolRegistry` 边界上接入。

如果 IDEA 没有自动识别 JDK，项目 SDK 和 Gradle JVM 选择 Java 17 或更高版本。

## 设计边界

`DshRuntime` 是插件宿主，插件通过 `PluginContext` 注册服务、监听事件和登记清理动作。`AgentLoop` 编排模型与工具调用，Spring Boot 只负责启动宿主和暴露 API，不进入 Agent 核心。

后续实现顺序：

1. 接入 MCP Java SDK，提供 `McpToolProvider`。
2. 实现流式模型输出和会话事件持久化。
3. 增加审批策略、工作区文件工具和 shell 工具。
4. 增加插件 JAR 版本、依赖排序和受控 reload。

## License

MIT
