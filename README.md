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

模型配置也使用同一个持久化开关。启用后，模型 Profile 会保存到 `dsh_model_profile`，应用重启后仍然可用；列表接口只返回 `apiKeyConfigured`，不会回显 API Key：

```bash
export DSH_PERSISTENCE_ENABLED=true
```

模型管理接口为 `GET/POST/PATCH/DELETE /api/v1/models`，以及 `POST /api/v1/models/{id}/activate`。Profile 支持 `provider`、`baseUrl`、`model`、`proxyHost`、`proxyPort`、`enabled` 和 `active`。聊天请求可以传 `modelId` 选择模型；不传时使用当前 active 模型。页面中的 DEBUG API KEY 仍然只对当前请求生效，并优先于 Profile 中保存的 Key。

Agent Profile 管理接口为 `GET/POST/PATCH/DELETE /api/v1/agents`，以及 `POST /api/v1/agents/{id}/activate`。Profile 支持 `mode`（`chat`、`planning`、`execution`）、`modelId`、`systemPrompt`、`maxTurns`、`enabled` 和 `active`。聊天请求可以传 `agentId` 和 `mode`；不传时使用当前 active Agent Profile。Planning 模式不会向模型暴露工具，并要求输出结构化的执行计划；Execution 模式允许使用已启用工具。

Sub-agent Profile 管理接口为 `GET/POST/PATCH/DELETE /api/v1/sub-agents`。每个子智能体可以独立配置 `mode`、`modelId`、`systemPrompt`、`maxTurns`、工具白名单 `allowedToolNames`、Skill 白名单 `skillIds` 和 `enabled`。白名单会在模型请求和实际工具执行两处生效。计划步骤可以填写 `subAgentId`，执行时由对应子智能体完成。

计划接口为 `GET /api/v1/plans`、`GET /api/v1/plans/{id}`、`POST /api/v1/plans`、`POST /api/v1/plans/{id}/approve`、`POST /api/v1/plans/{id}/execute` 和 `POST /api/v1/plans/{id}/cancel`。Plan 创建时提交有序步骤、`dependsOn` 步骤编号和 `maxConcurrency`；需要人工确认的计划先处于 `draft`，审批后进入 `approved`，执行过程中会持久化每个步骤的 `pending/running/completed/failed/cancelled` 状态，并支持单步骤重试和满足依赖后的并行执行。

自适应计划接口为 `POST /api/v1/plans/adaptive`。它会调用 Planning Agent 生成严格 JSON 步骤，服务端限制最大步骤数、校验结构，并根据子智能体的名称、说明、工具和 Skill 能力做确定性匹配；匹配不到时回退到父 Agent。生成结果直接进入同一套审批和执行状态机。

同一个 `conversationId` 会复用最近的历史消息；不传时服务会创建新的会话 ID。工具管理接口为 `GET /api/v1/tools` 和 `PATCH /api/v1/tools/{name}`，请求体示例为 `{"enabled":false}`。

前端右上角的 `Tools` 可以添加调试工具。自定义工具当前是运行时内存工具：填写名称、描述、JSON Schema 和固定返回值后，模型即可调用；应用重启后需要重新添加，真正的业务执行工具通过插件或 MCP 接入。

MCP Server 管理已经接入官方 Java SDK `0.17.0`，支持 `stdio`、`sse` 和 `streamable_http`。HTTP endpoint 可以填写完整 URL，包含 path 和 query 参数，例如高德 MCP 使用 `https://mcp.amap.com/mcp?key=YOUR_KEY`，transport 选择 `streamable_http`。配置后通过 `POST /api/v1/mcp/servers/{id}/connect` 建立会话，工具会自动同步进统一的 ToolRegistry；`refresh`、`disconnect` 分别用于刷新工具和释放连接。MCP 工具会以 `mcp_<server-id>_<tool-name>` 暴露，名称只使用模型兼容的字母、数字、下划线和连字符，避免不同 Server 同名冲突。

Skills 使用文件系统目录，默认扫描项目根目录 `skills/`，也可以通过 `DSH_SKILLS_DIR` 指定目录。每个 Skill 的入口文件是 `skills/<name>/SKILL.md`，支持简单 front matter：

```markdown
---
name: Writing Style
description: Keep answers concise and structured.
enabled: true
---

具体的 Agent 指令写在这里。启用后会追加到每次 Agent run 的 system prompt。
```

Skills 管理接口为 `GET /api/v1/skills`、`PATCH /api/v1/skills/{id}` 和 `POST /api/v1/skills/refresh`，前端的 `MCP`、`Skills` 入口也可以直接管理它们。

调试前端可以在页面输入框临时填写 API Key。它只随当前请求提交，不保存到浏览器、本地配置或 Git；未填写时使用 `DEEPSEEK_API_KEY` 环境变量。

当前默认使用 `deepseek-v4-flash`，内置了 `time_now` 工具。模型客户端是 OpenAI-compatible 的 DeepSeek Chat Completions 适配器，MCP 工具和本地工具都从同一个 `ToolRegistry` 边界进入 Agent Loop。

详细能力缺口和实施顺序见 [`docs/runtime-roadmap.md`](docs/runtime-roadmap.md)。

如果 IDEA 没有自动识别 JDK，项目 SDK 和 Gradle JVM 选择 Java 17 或更高版本。

## 设计边界

`DshRuntime` 是插件宿主，插件通过 `PluginContext` 注册服务、监听事件和登记清理动作。`AgentLoop` 编排模型与工具调用，Spring Boot 只负责启动宿主和暴露 API，不进入 Agent 核心。

后续实现顺序：

1. 增加工具审批策略、工作区文件工具和 shell 工具。
2. 为 MCP 配置和 Skills 增加持久化、凭据引用及重连策略。
3. 增加插件 JAR 版本、依赖排序和受控 reload。

## License

MIT
