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

模型管理接口为 `GET/POST/PATCH/DELETE /api/v1/models`，以及 `POST /api/v1/models/{id}/activate`、`POST /api/v1/models/{id}/test` 和 `GET /api/v1/models/{id}/health`。Profile 持久化 `provider`、`baseUrl`、`model`、`proxyHost`、`proxyPort`、`enabled`、`active`、工具调用/流式/Vision 能力、`contextWindow`、`temperature`、`topP`、`maxTokens`、频率惩罚、存在惩罚、请求超时和 Provider 扩展参数 `requestOptionsJson`。模型路由会持久化成功次数、失败次数、最近延迟、最近成功时间和最近错误。扩展参数必须是 JSON 对象，不能覆盖 `model`、`messages`、`stream`、`tools` 或 `tool_choice`；可用于配置 `reasoning_effort`、`response_format`、`stop` 等 Provider-specific 字段。聊天请求可以传 `modelId` 选择模型；不传时使用当前 active 模型。页面中的 DEBUG API KEY 仍然只对当前请求生效，并优先于 Profile 中保存的 Key。

启用 MariaDB 时建议同时设置 `DSH_SECRET_KEY`。模型 API Key 会使用 AES-GCM 封装后保存，接口仍只返回 `apiKeyConfigured`；历史明文记录可以兼容读取，设置主密钥后更新一次模型即可转为加密存储。主密钥不会写入配置文件或 Git。

Agent Profile 管理接口为 `GET/POST/PATCH/DELETE /api/v1/agents`，以及 `POST /api/v1/agents/{id}/activate`。Profile 支持 `mode`（`chat`、`planning`、`execution`）、`modelId`、`systemPrompt`、`maxTurns`、`enabled` 和 `active`。聊天请求可以传 `agentId` 和 `mode`；不传时使用当前 active Agent Profile。Planning 模式不会向模型暴露工具，并要求输出结构化的执行计划；Execution 模式允许使用已启用工具。

Sub-agent Profile 管理接口为 `GET/POST/PATCH/DELETE /api/v1/sub-agents`。每个子智能体可以独立配置 `mode`、`modelId`、`systemPrompt`、`maxTurns`、工具白名单 `allowedToolNames`、Skill 白名单 `skillIds` 和 `enabled`。白名单会在模型请求和实际工具执行两处生效。计划步骤可以填写 `subAgentId`，执行时由对应子智能体完成。

当存在启用的 `execution` 子智能体时，主 Agent 会自动获得 `delegate_to_subagent` 虚拟工具。模型可以提交 `profileId` 和独立 `task`，子任务会复用当前 API Key 和模型路由，结果作为工具消息回传；启用 Run 持久化时会记录父子运行树，并同时校验父、子智能体的最大深度。子智能体内部触发工具审批时，审批会代理回父委派运行，批准父运行即可继续子任务并回到主 Agent。

计划接口为 `GET /api/v1/plans`、`GET /api/v1/plans/{id}`、`POST /api/v1/plans`、`POST /api/v1/plans/{id}/approve`、`POST /api/v1/plans/{id}/execute` 和 `POST /api/v1/plans/{id}/cancel`。Plan 创建时提交有序步骤、`dependsOn` 步骤编号和 `maxConcurrency`；需要人工确认的计划先处于 `draft`，审批后进入 `approved`，执行过程中会持久化每个步骤的 `pending/running/completed/failed/cancelled` 状态，并支持单步骤重试和满足依赖后的并行执行。

自适应计划接口为 `POST /api/v1/plans/adaptive`。它会调用 Planning Agent 生成严格 JSON 步骤，服务端限制最大步骤数、校验结构，并根据子智能体的名称、说明、工具和 Skill 能力做确定性匹配；匹配不到时回退到父 Agent。生成结果直接进入同一套审批和执行状态机。

自适应计划可以传 `allowDynamicSubAgents: true`。当已有 Worker 都无法匹配时，Planning Agent 可为步骤返回 Worker 描述，服务端会过滤不存在的工具/Skill，创建并持久化一个 `execution` Sub-agent Profile，再将步骤绑定到它。默认关闭，前端 Adaptive Planner 中可显式开启。

记忆接口为 `GET /api/v1/memories`、`GET /api/v1/memories/search`、`POST /api/v1/memories` 和 `DELETE /api/v1/memories/{id}`。记忆按 `namespace + subjectKey` 隔离，当前聊天会以 `conversation + conversationId` 自动检索相关记忆并注入系统上下文；前端 Memory Tab 可以显式添加和删除记忆。

运行追踪接口为 `GET /api/v1/runs`、`GET /api/v1/runs/{id}`、`GET /api/v1/runs/{id}/events` 和 `GET /api/v1/runs/{id}/tree`。聊天响应和流式 `done` 事件会返回 `runId`；计划执行会创建 `plan -> plan_step -> agent/sub_agent` 的父子运行树，可按 `planId` 查询。每个运行会记录模型响应、工具调用、工具结果、重试和终态，启用 MariaDB 时会持久化到 `dsh_run` 与 `dsh_run_event`。

同一个 `conversationId` 会复用最近的历史消息；不传时服务会创建新的会话 ID。工具管理接口为 `GET /api/v1/tools` 和 `PATCH /api/v1/tools/{name}`，请求体示例为 `{"enabled":false}`。

自定义调试工具在启用 MariaDB 持久化时会保存名称、描述、JSON Schema、固定返回值和启用状态，重启后自动恢复；内置工具和已连接 MCP 工具仍由运行时负责注册。MCP Server 配置同样会保存，启用的 Server 会在应用启动后异步尝试恢复连接，失败不会阻塞应用启动；仍可显式调用 `POST /api/v1/mcp/servers/{id}/connect` 重试。

上下文窗口同时受 `DSH_MAX_HISTORY_MESSAGES` 和 `DSH_MAX_CONTEXT_TOKENS` 限制，按最新消息优先裁剪；`GET /api/v1/conversations/{id}/context` 可以查看当前消息数、估算 token 数和是否发生裁剪。token 数是运行时估算值，不依赖特定模型 tokenizer。

前端右上角的 `Tools` 可以添加调试工具。启用 MariaDB 持久化后，自定义工具的名称、描述、JSON Schema、固定返回值、启用状态和审批策略会保存并在重启后恢复；真正的业务执行工具通过插件或 MCP 接入。

MCP Server 管理已经接入官方 Java SDK `0.17.0`，支持 `stdio`、`sse` 和 `streamable_http`。HTTP endpoint 填写服务地址，敏感参数使用 Credential Reference 或加密 Header/Environment 保存，不要把 Key 放进 URL。配置后通过 `POST /api/v1/mcp/servers/{id}/connect` 建立会话，工具会自动同步进统一的 ToolRegistry；`refresh`、`disconnect` 分别用于刷新工具和释放连接。MCP 还提供资源列表/读取和 Prompt 列表/加载接口，前端 MCP 面板可直接调试。MCP 工具会以 `mcp_<server-id>_<tool-name>` 暴露，名称只使用模型兼容的字母、数字、下划线和连字符，避免不同 Server 同名冲突。

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

启用 MariaDB 持久化后，Skill 的启用/禁用状态会保存到 `dsh_skill_state`；Skill 正文和附属资源仍从 `DSH_SKILLS_DIR` 读取，应用重启后不会自动执行远程或本地 Skill，只恢复配置状态。

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
