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
- 流式 Agent 调试：`POST /api/v1/chat/stream`，支持 Markdown 输出和工具追踪

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

启动本地 MariaDB：

```bash
docker compose up -d mariadb
docker compose ps
```

数据库数据挂载到项目旁的 `../venus/mariadb/dsh-java/data`，不会进入 Git。当前数据库初始化表结构用于后续会话、上下文、工具、MCP 和记忆能力接入；应用默认不强制依赖数据库启动。

配置 DeepSeek API Key 后运行智能体：

```bash
export DEEPSEEK_API_KEY=your-api-key
# 如果本机通过代理访问外网：
export DEEPSEEK_PROXY_HOST=127.0.0.1
export DEEPSEEK_PROXY_PORT=7897
./gradlew bootRun
```

项目不会把 API Key 写入配置文件。提交前的 secret scan 已启用，`.env` 文件默认被 Git 忽略。

调用：

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"现在几点？"}'
```

流式调用：

```bash
curl -N -X POST http://localhost:8080/api/v1/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"请调用 time_now 工具，然后用 Markdown 告诉我当前时间。"}'
```

启用 MariaDB 会话上下文：

```bash
export DSH_PERSISTENCE_ENABLED=true
./gradlew bootRun
```

同一个 `conversationId` 会复用最近的历史消息；不传时服务会创建新的会话 ID。工具管理接口为 `GET /api/v1/tools` 和 `PATCH /api/v1/tools/{name}`，请求体示例为 `{"enabled":false}`。

MCP Server 配置管理接口为 `GET/POST/PATCH/DELETE /api/v1/mcp/servers`。当前只保存和校验连接配置，状态会显示为 `DISCONNECTED`；真正的 MCP 连接、工具同步和重连策略随后接入。

调试前端可以在页面输入框临时填写 API Key。它只随当前请求提交，不保存到浏览器、本地配置或 Git；未填写时使用 `DEEPSEEK_API_KEY` 环境变量。

当前默认使用 `deepseek-v4-flash`，内置了 `time_now` 工具。模型客户端是 OpenAI-compatible 的 DeepSeek Chat Completions 适配器，MCP 工具适配将在 `ToolRegistry` 边界上接入。

详细能力缺口和实施顺序见 [`docs/runtime-roadmap.md`](docs/runtime-roadmap.md)。

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
