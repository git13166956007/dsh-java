# Architecture

## Boundaries

```text
bootstrap -> config -> core
                         ├── plugin
                         ├── event
                         ├── service
                         ├── agent
                         ├── tool
                         └── session

web -> core
```

`core` owns the runtime lifecycle. `plugin` exposes the extension contract. `agent` and `tool` are protocol boundaries for the model loop and MCP adapters. `web` is only the Spring Boot transport layer and must not contain runtime behavior.

## Runtime Composition

`DshRuntime` owns a root `Scope`. Every plugin receives a child Scope; services, event subscriptions, and cleanup effects are owned by that Scope and released together during uninstall or shutdown. The built-in services are installed through `RuntimeServicePlugin`, so `ToolRegistry`, `ModelRegistry`, `ContextManager`, `ConversationStore`, and `AgentLoop` can be replaced without changing the host API.

`AgentLoop` creates one child Scope per execution. It resolves runtime services from that Scope with constructor-injected dependencies as a compatibility fallback. `RuntimeProfile` is immutable and `ProfilePatch` is a sparse overlay for model selection, system instructions, tool/Skill capabilities, and permissions.

## Events And Sessions

`EventBus` has a typed `EventKey<T>` contract. Handlers receive `(event, next)` and may continue the waterfall with a rewritten payload, return an accepted result, or reject the event. Runtime and Agent lifecycle events use this API; the legacy string broadcast API remains only for compatibility.

Conversation state is represented by append-only `SessionEvent` records. `ConversationStore` keeps the existing read/write API while exposing `eventLog()` for replay and subscriptions. MariaDB writes message materialization and the corresponding SessionEvent in the same JDBC transaction, and projections rebuild chat messages from the event stream.

## Package naming

The root package follows the repository owner using reverse-domain notation:

```text
io.github.git13166956007.dsh
```

The username used for Git commits is unrelated to Java package naming.
