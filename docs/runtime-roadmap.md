# Runtime Roadmap

## Current State

| Area | Current implementation | Missing for a usable runtime |
| --- | --- | --- |
| Tools | In-process `ToolRegistry`, local `time_now`, durable custom debug tools, management API, durable Workspace Profiles with active switching, sandboxed workspace list/read/write adapters, per-profile allowlisted `workspace_exec`, unified approval and allowlist enforcement | Richer permission policies and controlled reload |
| MCP | Java SDK client with stdio/SSE/Streamable HTTP, durable profiles, encrypted credentials, startup reconnect with backoff, initialize/list-tools/call-tool, resource/prompt inspection, durable resource subscriptions and update capture, lifecycle API, Server-level approval policy, persistent health telemetry and ToolRegistry synchronization | Richer transport diagnostics |
| Skills | Filesystem `skills/<name>/SKILL.md`, front matter with version, resource indexing, durable enable/disable state and system-prompt injection, safe package install/update/remove API | Remote package registry and signature verification |
| Models | Persisted profiles with active selection, built-in and plugin-registered Provider routing, encrypted API keys, proxy/request parameters, capability flags, context budgets, Provider extension JSON, input/output Token pricing, connectivity tests, policy-driven fallback routing, durable health counters and per-model usage/cost accounting | Exact tokenizer implementations for individual providers |
| Agent modes | Persisted Agent Profiles with chat, planning and execution modes; profile-specific model, prompt and turn budget; active runs can delegate through a virtual tool with approval propagation; adaptive planning exposes streaming planning/reasoning events and preserves step retry budgets | Richer delegation policies |
| Sub-agents | Persisted sub-agent profiles and continuable sessions with independent model/mode/prompt, tool and Skill allowlists, priority, cost weight, capability tags and concurrency limits; plan steps and active Agents can dispatch enabled execution workers; HTTP API can start, inspect, stream and cancel workers; persisted async Sub-agent Runs are reattached after restart without persisting request API Keys; adaptive candidate scoring uses model price/health/load and exposes diagnostics | Learned routing and cross-process distributed quotas |
| Plans | Persisted Plan/PlanStep state, approval, generated/manual dependencies, dependency-aware asynchronous execution, bounded parallelism, retries, cancellation API, durable parent-child run IDs and restart recovery that requeues interrupted steps | Richer dependency policies |
| Context | Conversation IDs, persisted messages including assistant tool calls and tool results, conversation listing/rename/delete/search/fork/replay, configurable message/token budgets, newest-first truncation, rolling model-generated summaries, Provider tokenizer hooks, dynamic ContextProvider registration, priority/budget collection, failure isolation, and context inspection/compaction APIs | Exact provider tokenizer implementations |
| Memory | MariaDB/in-memory explicit memories, namespace isolation, keyword retrieval, conversation-context injection and opt-in model-based extraction with validation/deduplication | User/workspace namespaces, forgetting/update rules and semantic retrieval |
| Security | API key can be supplied for debugging | Authentication, tool approval, secret references, execution sandbox and audit trail |
| Operations | Health endpoint, durable Run IDs, parent-child run tree and persisted audit events | Structured logs, metrics, timeout and failure replay |

## Delivery Order

1. **Persistence foundation**: MariaDB, migrations, conversation/message repository, and run IDs.
2. **Context service**: build the model input from persisted messages with a token/turn budget and compaction hook.
3. **MCP manager**: synchronize remote tools into the same `ToolRegistry` boundary and expose connect/refresh/disconnect lifecycle controls.
4. **Tool management**: expose registry metadata and enable/disable state; add approval policies before destructive tools.
5. **Memory service**: start with explicit durable memories and MariaDB full-text retrieval; add embeddings only when keyword retrieval is insufficient.
6. **Agent orchestration**: persist Agent Profiles and run modes first, then add Plan/PlanStep state, approval and execution APIs.

Active Agents can now call `delegate_to_subagent` when enabled execution profiles exist. The call is recorded as a normal tool event and creates a child Run when Run persistence is enabled; the parent and child depth budgets are enforced. If a child tool requires approval, the parent delegation pauses and approval resumes the child before continuing the parent. The Sub-agent API can also start an independent execution worker or a continuable session, stream persisted and live events, and cancel its in-process model call. Persisted async Sub-agent Runs and interrupted Plan steps are reattached after an application restart; request API Keys are intentionally excluded from recovery payloads.

## Database Development

The local MariaDB service is intentionally independent from application startup. Its data directory is mounted at:

```text
../venus/mariadb/dsh-java/data
```

Start it with:

```bash
docker compose up -d mariadb
docker compose ps
```

The development schema is initialized from `docker/mariadb/init/001_schema.sql` on the first empty data directory. Do not put model keys or other credentials in the SQL file.

The MCP management boundary is available at `GET/POST/PATCH/DELETE /api/v1/mcp/servers`, plus `POST /api/v1/mcp/servers/{id}/connect`, `/refresh`, and `/disconnect`. Connected tools are removed from the registry when a server disconnects or is deleted.
