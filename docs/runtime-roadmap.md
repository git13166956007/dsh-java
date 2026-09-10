# Runtime Roadmap

## Current State

| Area | Current implementation | Missing for a usable runtime |
| --- | --- | --- |
| Tools | In-process `ToolRegistry`, local `time_now`, runtime custom debug tools, management API | Durable catalog, permissions, reload, approval policy and real executable adapters |
| MCP | Java SDK client with stdio/SSE/Streamable HTTP, initialize/list-tools/call-tool, lifecycle API and ToolRegistry synchronization | Durable profiles, credentials, approval policy, reconnect backoff and resource/prompt management |
| Skills | Filesystem `skills/<name>/SKILL.md`, front matter, enable/disable and system-prompt injection | Durable enablement state, package/version management and resource indexing |
| Models | Model profiles, active model selection, OpenAI-compatible routing, MariaDB persistence and redacted API responses | Encrypted secret storage, provider-specific adapters, health checks, model capabilities and routing policies |
| Agent modes | Persisted Agent Profiles with chat, planning and execution modes; profile-specific model, prompt and turn budget | Adaptive delegation |
| Sub-agents | Persisted sub-agent profiles with independent model/mode/prompt, tool and Skill allowlists; plan steps can dispatch them; adaptive planner selects workers | Parent-child run tree, parallel scheduling, budgets and adaptive creation |
| Plans | Persisted Plan/PlanStep state, approval, dependency-aware asynchronous execution, bounded parallelism, retries and cancellation API | Streaming plan events, durable run IDs and richer dependency policies |
| Context | Per-request `List<ChatMessage>` | Conversation IDs, persisted messages, token budget, truncation, summarization, system/context providers |
| Memory | MariaDB/in-memory explicit memories, namespace isolation, keyword retrieval and conversation-context injection | Automatic extraction, user/workspace namespaces, forgetting/update rules and semantic retrieval |
| Security | API key can be supplied for debugging | Authentication, tool approval, secret references, execution sandbox and audit trail |
| Operations | Health endpoint, durable Run IDs, parent-child run tree and persisted audit events | Structured logs, metrics, timeout and failure replay |

## Delivery Order

1. **Persistence foundation**: MariaDB, migrations, conversation/message repository, and run IDs.
2. **Context service**: build the model input from persisted messages with a token/turn budget and compaction hook.
3. **MCP manager**: synchronize remote tools into the same `ToolRegistry` boundary and expose connect/refresh/disconnect lifecycle controls.
4. **Tool management**: expose registry metadata and enable/disable state; add approval policies before destructive tools.
5. **Memory service**: start with explicit durable memories and MariaDB full-text retrieval; add embeddings only when keyword retrieval is insufficient.
6. **Agent orchestration**: persist Agent Profiles and run modes first, then add Plan/PlanStep state, approval and execution APIs.

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
