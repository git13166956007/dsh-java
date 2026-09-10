# Runtime Roadmap

## Current State

| Area | Current implementation | Missing for a usable runtime |
| --- | --- | --- |
| Tools | In-process `ToolRegistry`, local `time_now` tool | Tool metadata, enable/disable, permissions, reload, management API |
| MCP | No MCP client yet | Server profiles, stdio/HTTP transports, initialize/list-tools lifecycle, reconnect and timeout policy |
| Context | Per-request `List<ChatMessage>` | Conversation IDs, persisted messages, token budget, truncation, summarization, system/context providers |
| Memory | JSONL session store exists but is not in the Agent Loop | Explicit memory extraction, durable storage, retrieval policy, user/workspace namespaces, forgetting/update rules |
| Security | API key can be supplied for debugging | Authentication, tool approval, secret references, execution sandbox and audit trail |
| Operations | Health endpoint and trace events | Run IDs, structured logs, metrics, cancellation, timeout and failure replay |

## Delivery Order

1. **Persistence foundation**: MariaDB, migrations, conversation/message repository, and run IDs.
2. **Context service**: build the model input from persisted messages with a token/turn budget and compaction hook.
3. **MCP manager**: add one transport first, synchronize remote tools into the same `ToolRegistry` boundary, then add lifecycle controls.
4. **Tool management**: expose registry metadata and enable/disable state; add approval policies before destructive tools.
5. **Memory service**: start with explicit durable memories and MariaDB full-text retrieval; add embeddings only when keyword retrieval is insufficient.

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
