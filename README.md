# IntelliJ Dev MCP

An IntelliJ IDEA plugin that exposes MCP (Model Context Protocol) tools, letting AI assistants run JUnit tests with coverage directly inside the IDE.

## How it works

The plugin runs inside IntelliJ and communicates with MCP clients through a stdio bridge process. The bridge (`stdio-mcp-server`) translates standard MCP stdio transport into a file-based protocol that the plugin watches from within the IDE. This means the AI assistant talks MCP over stdio to the bridge, and the bridge relays requests to the running IntelliJ instance.

```
AI Assistant  ──stdio──>  stdio-mcp-server  ──files──>  IntelliJ Plugin
```

## Available tools

| Tool | Description |
|------|-------------|
| `run_test` | Runs JUnit tests at package, class, or method scope. Returns structured test results with line and branch coverage. Supports both Gradle and native JUnit run configurations. |

## Requirements

- IntelliJ IDEA 2025.2+ (build 252.25557 or later)
- Java 21+
- The following bundled plugins must be enabled in IntelliJ: Java, JUnit, Coverage

## Installation

```bash
curl -fsSL https://raw.githubusercontent.com/agayardo/IntellijMCP/main/install.sh | bash
```

This downloads the latest release, installs the IntelliJ plugin, and sets up the stdio bridge at `~/.intellij-dev-mcp/bridge/`. Restart IntelliJ after running it.

## Configuration

### MCP client configuration

Point your MCP client at the stdio bridge binary. The exact config depends on your client.

#### Kiro / VS Code (`mcp.json`)

```json
{
  "mcpServers": {
    "intellij-dev-mcp": {
      "command": "~/.intellij-dev-mcp/bridge/bin/stdio-mcp-server",
      "args": []
    }
  }
}
```

#### Claude Desktop (`claude_desktop_config.json`)

Claude Desktop requires an absolute path (no `~`):

```json
{
  "mcpServers": {
    "intellij-dev-mcp": {
      "command": "/Users/you/.intellij-dev-mcp/bridge/bin/stdio-mcp-server"
    }
  }
}
```

### Verifying the connection

Once IntelliJ is running with a project open and your MCP client is configured:

1. The plugin starts automatically when IntelliJ opens a project
2. Call `run_test` with a test class to run tests with coverage

### `run_test` parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `scope` | yes | One of: `package`, `class`, `method` |
| `target` | yes | Package name, fully qualified class name, or `class#method` |
| `moduleName` | no | IntelliJ module name. Auto-detected if omitted. |

Examples:

```
scope: "class",   target: "com.example.MyServiceTest"
scope: "method",  target: "com.example.MyServiceTest#testCreate"
scope: "package", target: "com.example.service"
```

## Building from source

Requires Java 21+ and a working Gradle installation (or use the included wrapper).

```bash
./gradlew buildPlugin :stdio-mcp-server:installDist
```

The plugin zip will be at `build/distributions/` and the bridge at `stdio-mcp-server/build/install/stdio-mcp-server/`.
