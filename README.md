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

### 1. Download the release artifacts

Download both zip files from the [v1.0.0.0 release](https://github.com/agayardo/IntellijMCP/releases/tag/v1.0.0.0):

- **DevelopmentMcp-plugin.zip** — the IntelliJ plugin
- **stdio-mcp-server.zip** — the MCP stdio bridge

### 2. Install the IntelliJ plugin

1. Open IntelliJ IDEA
2. Go to **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. Select the downloaded `DevelopmentMcp-plugin.zip` (do not unzip it)
4. Restart IntelliJ

### 3. Set up the stdio bridge

Unzip `stdio-mcp-server.zip` to a location of your choice:

```bash
unzip stdio-mcp-server.zip -d ~/tools/intellij-dev-mcp
```

The bridge executable will be at `~/tools/intellij-dev-mcp/stdio-mcp-server/bin/stdio-mcp-server`.

Make sure it's executable:

```bash
chmod +x ~/tools/intellij-dev-mcp/stdio-mcp-server/bin/stdio-mcp-server
```

## Configuration

### MCP client configuration

Point your MCP client at the stdio bridge binary. The exact config depends on your client.

#### Kiro / VS Code (`mcp.json`)

```json
{
  "mcpServers": {
    "intellij-dev-mcp": {
      "command": "~/tools/intellij-dev-mcp/stdio-mcp-server/bin/stdio-mcp-server",
      "args": []
    }
  }
}
```

Replace the path with wherever you unzipped the bridge.

#### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "intellij-dev-mcp": {
      "command": "/Users/you/tools/intellij-dev-mcp/stdio-mcp-server/bin/stdio-mcp-server"
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
