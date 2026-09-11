# Kernel Audit

## Verified In This Change

- Run event writes return whether the event was newly inserted. Idempotent retries do not notify SSE subscribers a second time.
- Run lifecycle state changes and lifecycle events remain one atomic store operation in MariaDB.
- MCP sensitive endpoint query parameters are removed from the public endpoint, encrypted at rest, restored after Registry recreation, and appended only while connecting.
- MariaDB schema initialization and the checked-in bootstrap SQL contain the Run event key and MCP query-secret columns.
- In-memory, MariaDB integration, full unit tests, formatting checks, and secret scanning pass.

## Remaining Kernel Boundaries

### P0: SaaS isolation

There is no authenticated principal or `TenantContext` yet. Conversation, Run, Memory, MCP, Tool, Model, Agent Profile, Plan, and Workspace records are not tenant-owned. This must be designed before exposing the API to multiple organizations.

### P1: Live Runtime replacement

`AgentLoop` resolves services from the active `Scope`, but `DshController`, `McpClientManager`, `PlanExecutor`, and other Spring-managed long-lived components still hold constructor references. A runtime service-reference bridge or controlled component restart is required before replacing those services during live traffic.

### P1: Distributed execution

Run CAS prevents state overwrites, but there is no owner, lease, heartbeat, timeout takeover, or cross-instance execution lock. Restart recovery is safe for the current single-process execution model, not a multi-node scheduler.

### P2: Event and session migration

Session events and projections are durable, but the legacy message materialization table and compatibility APIs remain. Fork, summary, and search should eventually use pure event replay with projection rebuild checkpoints.

### P2: Network and secret operations

MCP endpoint syntax and private-address checks are present, but DNS rebinding protection, credential rotation/KMS integration, and structured security audit events remain.
