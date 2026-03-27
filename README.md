# IntelliJ Dev MCP

An IntelliJ IDEA plugin that exposes MCP (Model Context Protocol) tools, letting AI assistants run JUnit tests with coverage and look up class APIs directly inside the IDE.

## How it works

The plugin runs inside IntelliJ and communicates with MCP clients through a stdio bridge process. The bridge (`stdio-mcp-server`) translates standard MCP stdio transport into a file-based protocol that the plugin watches from within the IDE. This means the AI assistant talks MCP over stdio to the bridge, and the bridge relays requests to the running IntelliJ instance.

```
AI Assistant  ──stdio──>  stdio-mcp-server  ──files──>  IntelliJ Plugin
```

## Available tools

### `run_test`

Runs JUnit tests inside the IDE at package, class, or method scope. Returns structured pass/fail results with line and branch coverage. Supports both Gradle and native JUnit run configurations. Module is auto-detected when not specified.

### `lookup_class`

Searches for Java/Kotlin classes by name — fully qualified, simple name, or wildcard patterns like `*Handler` or `com.example.*Service`. Returns the public interface: methods with full signatures, fields, implemented interfaces, superclass, and the source file path or JAR location.

Searches across all open IntelliJ projects simultaneously. When the same class appears in every project (e.g. a JDK class), results are merged seamlessly. When results differ across projects, each gets its own labeled section.

## Requirements

- IntelliJ IDEA 2025.2+ (build 252.25557 or later)
- Java 21+
- The following bundled plugins must be enabled in IntelliJ: Java, JUnit, Coverage

## Installation

```bash
curl -fsSL https://raw.githubusercontent.com/agayardo/IntellijMCP/main/install.sh | bash
```

This downloads the latest release and installs the IntelliJ plugin. Restart IntelliJ after running it — the stdio bridge is installed automatically on first startup.

## Configuration

### MCP client configuration

Point your MCP client at the stdio bridge binary. The exact config depends on your client.

#### Kiro / VS Code (`mcp.json`)

```json
{
  "mcpServers": {
    "intellij-dev-mcp": {
      "command": "bash",
      "args": ["-c", "~/.intellij-dev-mcp/bin/stdio-mcp-server"]
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
      "command": "/Users/you/.intellij-dev-mcp/bin/stdio-mcp-server"
    }
  }
}
```

### Verifying the connection

Once IntelliJ is running with a project open and your MCP client is configured, the plugin starts automatically. Ask your agent to try running a test or looking up a class to confirm everything is wired up.

## Building from source

Requires Java 21+ and a working Gradle installation (or use the included wrapper).

```bash
./gradlew buildPlugin
```

The plugin zip will be at `build/distributions/`. The stdio bridge is bundled inside the plugin and installed automatically on first IDE startup.
