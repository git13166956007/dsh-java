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

## Package naming

The root package follows the repository owner using reverse-domain notation:

```text
io.github.git13166956007.dsh
```

The username used for Git commits is unrelated to Java package naming.
