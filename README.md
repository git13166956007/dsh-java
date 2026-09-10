# dsh-java

Java 版 DeepSeek Harness 的最小运行时内核。

当前版本先实现可复用的基础语义：

- 插件生命周期与 `ServiceLoader` 发现入口
- 服务注册、事件总线、可回收副作用
- JSONL 会话事件存储
- 本地工具与模型适配接口
- Java 8 可直接编译运行，无第三方依赖

## 快速运行

```bash
mkdir -p out/classes
find out/classes -type f -delete
javac -encoding UTF-8 -d out/classes $(find src/main/java src/test/java -name '*.java')
java -cp out/classes io.github.git13166956007.dsh.DshSelfTest
```

## 设计边界

`DshRuntime` 是插件宿主，插件通过 `PluginContext` 注册服务、监听事件和登记清理动作。`ToolProvider` 和 `ChatModel` 是后续接入 MCP 与 OpenAI-compatible API 的边界，不在第一版引入未经验证的网络依赖。

后续实现顺序：

1. 接入官方 MCP Java SDK，提供 `McpToolProvider`。
2. 实现流式 `ChatModel` 和 Agent Loop。
3. 增加审批策略、工作区文件工具和 shell 工具。
4. 增加插件 JAR 版本、依赖排序和受控 reload。

## License

MIT
