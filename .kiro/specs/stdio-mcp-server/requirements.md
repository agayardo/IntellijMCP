# Requirements Document

## Introduction

This feature adds a standalone CLI application that acts as an MCP stdio transport bridge between MCP clients (such as Claude Desktop or VS Code) and the DevelopmentMcp IntelliJ plugin. The CLI tool implements the MCP protocol over stdin/stdout (JSON-RPC) and translates tool invocations into the file-based command protocol already implemented by the plugin.

On startup, the CLI reads `~/.intellij-dev-plugin/schema.json` to discover available tools. It responds to `tools/list` requests by returning the tools from the schema. For `tools/call` requests, it writes a `<UUID>.request.json` file to the command directory, waits for the corresponding `<UUID>.response.json` file to appear, reads the response, and returns it to the MCP client via stdout.

The CLI is a separate Gradle module with its own `main` function. It depends only on the MCP SDK and standard Kotlin/JVM libraries — it has no dependency on IntelliJ Platform APIs. It reuses the same MCP SDK types (`CallToolRequest`, `CallToolResult`, `ListToolsResult`) and the same file naming conventions as the plugin side, ensuring wire-format compatibility.

## Glossary

- **Stdio_Server**: The standalone CLI application that implements the MCP stdio transport protocol
- **MCP_Client**: An external application (e.g., Claude Desktop, VS Code) that connects to the Stdio_Server via stdin/stdout using the MCP JSON-RPC protocol
- **Command_Directory**: The directory `~/.intellij-dev-plugin/` where request and response files are exchanged with the IntelliJ plugin
- **Schema_File**: The file `~/.intellij-dev-plugin/schema.json` containing available tools in MCP `ListToolsResult` format, written by the IntelliJ plugin
- **Request_File**: A JSON file matching the pattern `<UUID>.request.json` placed in the Command_Directory by the Stdio_Server
- **Response_File**: A JSON file matching the pattern `<UUID>.response.json` written by the IntelliJ plugin into the Command_Directory
- **MCP_SDK**: The `io.modelcontextprotocol.sdk:mcp:1.1.0` library providing MCP protocol types, JSON-RPC transport, and Jackson-based serialization
- **JSON_RPC**: The JSON-RPC 2.0 message framing used by the MCP protocol over stdio
- **Tool_Descriptor**: An MCP `Tool` object containing `name`, `description`, and `inputSchema` fields, as read from the Schema_File

## Requirements

### Requirement 1: Gradle Module Structure

**User Story:** As a developer, I want the stdio MCP server to be a separate Gradle module, so that it can be built and distributed independently of the IntelliJ plugin without pulling in IntelliJ Platform dependencies.

#### Acceptance Criteria

1. THE Stdio_Server SHALL be a separate Gradle module within the existing project, with its own `build.gradle.kts`
2. THE Stdio_Server module SHALL depend on `io.modelcontextprotocol.sdk:mcp:1.1.0`, Kotlin standard library, and `kotlin-logging-jvm`, and SHALL NOT depend on any IntelliJ Platform APIs
3. THE Stdio_Server module SHALL produce a runnable artifact (fat JAR or application distribution) with a `main` entry point
4. THE Stdio_Server module SHALL target JVM 21 and Kotlin 2.1.20, matching the existing project configuration

### Requirement 2: Schema Discovery

**User Story:** As an MCP client, I want the server to know what tools are available, so that it can respond to tool listing requests accurately.

#### Acceptance Criteria

1. WHEN the Stdio_Server starts, THE Stdio_Server SHALL read the Schema_File from the Command_Directory
2. WHEN the Schema_File exists and contains valid JSON, THE Stdio_Server SHALL parse the Schema_File as an MCP `ListToolsResult` using the MCP_SDK
3. IF the Schema_File does not exist, THEN THE Stdio_Server SHALL log an error message containing the expected file path and exit with a non-zero exit code
4. IF the Schema_File contains invalid JSON, THEN THE Stdio_Server SHALL log an error message describing the parse failure and exit with a non-zero exit code

### Requirement 3: MCP Stdio Transport

**User Story:** As an MCP client, I want to communicate with the server over stdin/stdout using the MCP protocol, so that I can use standard MCP client tooling to interact with IntelliJ tools.

#### Acceptance Criteria

1. THE Stdio_Server SHALL read JSON-RPC messages from stdin and write JSON-RPC responses to stdout, following the MCP stdio transport specification
2. THE Stdio_Server SHALL use the MCP_SDK `McpTransport` stdio implementation for JSON-RPC message framing and parsing
3. THE Stdio_Server SHALL write all diagnostic and log output to stderr, keeping stdout reserved for MCP JSON-RPC messages only
4. WHEN the stdin stream is closed (EOF), THE Stdio_Server SHALL shut down gracefully, cleaning up any pending operations

### Requirement 4: MCP Lifecycle Handling

**User Story:** As an MCP client, I want the server to handle MCP lifecycle methods correctly, so that the connection is properly established and capabilities are exchanged.

#### Acceptance Criteria

1. WHEN the Stdio_Server receives an `initialize` request, THE Stdio_Server SHALL respond with server capabilities including `tools` support
2. WHEN the Stdio_Server receives an `initialized` notification, THE Stdio_Server SHALL acknowledge the notification and be ready to handle tool requests
3. THE Stdio_Server SHALL report its server name as `"intellij-dev-mcp"` and a protocol-compatible version in the `initialize` response

### Requirement 5: Tool Listing

**User Story:** As an MCP client, I want to list available tools, so that I can discover what operations the IntelliJ plugin supports.

#### Acceptance Criteria

1. WHEN the Stdio_Server receives a `tools/list` request, THE Stdio_Server SHALL respond with the list of Tool_Descriptors parsed from the Schema_File
2. EACH Tool_Descriptor in the response SHALL contain the `name`, `description`, and `inputSchema` fields exactly as they appear in the Schema_File

### Requirement 6: Tool Invocation via File Bridge

**User Story:** As an MCP client, I want to call tools and get results, so that I can use IntelliJ IDE capabilities programmatically.

#### Acceptance Criteria

1. WHEN the Stdio_Server receives a `tools/call` request, THE Stdio_Server SHALL generate a UUID and write a Request_File named `<UUID>.request.json` to the Command_Directory
2. THE Request_File SHALL contain a JSON object with `method` set to `"tools/call"` and `params` containing the `name` and `arguments` from the MCP client request, matching the format expected by the IntelliJ plugin
3. THE Stdio_Server SHALL write the Request_File atomically by writing to a temporary file (`<UUID>.request.json.tmp`) first and then renaming it to the final filename
4. AFTER writing the Request_File, THE Stdio_Server SHALL wait for a Response_File named `<UUID>.response.json` to appear in the Command_Directory
5. WHEN the Response_File appears, THE Stdio_Server SHALL read the Response_File, parse it as an MCP `CallToolResult`, and return the result to the MCP_Client via the JSON-RPC response
6. AFTER reading the Response_File, THE Stdio_Server SHALL delete the Response_File from the Command_Directory
7. IF the Response_File does not appear within 120 seconds, THEN THE Stdio_Server SHALL return an MCP `CallToolResult` with `isError` set to `true` and a timeout error message in `content`

### Requirement 7: Response Waiting

**User Story:** As a developer, I want the response waiting to be efficient and reliable, so that tool invocations complete promptly without excessive resource usage.

#### Acceptance Criteria

1. THE Stdio_Server SHALL wait for the Response_File to appear in the Command_Directory, using either filesystem polling or `WatchService` — the mechanism is an implementation choice left to the design phase
2. IF an I/O error occurs while waiting for the Response_File, THEN THE Stdio_Server SHALL log the error to stderr and continue waiting until the timeout is reached

### Requirement 8: Request File Format Compatibility

**User Story:** As a developer, I want the request files written by the stdio server to be identical in format to what the IntelliJ plugin expects, so that the two sides interoperate without transformation.

#### Acceptance Criteria

1. THE Request_File JSON SHALL be serialized using the MCP_SDK Jackson `ObjectMapper` (`McpJsonDefaults.getMapper()`) to ensure wire-format compatibility with the IntelliJ plugin
2. THE Request_File SHALL use the same file naming convention as the existing protocol: `<UUID>.request.json` where UUID is a standard RFC 4122 UUID
3. FOR ALL valid tool names and argument maps, serializing a request with the Stdio_Server and deserializing it with the IntelliJ plugin Request_Processor SHALL produce an equivalent `CallToolRequest` (round-trip property)

### Requirement 9: Concurrent Tool Invocations

**User Story:** As an MCP client, I want to invoke multiple tools concurrently, so that I can issue parallel requests without waiting for each one to complete sequentially.

#### Acceptance Criteria

1. THE Stdio_Server SHALL support multiple concurrent `tools/call` requests, each using a distinct UUID for its Request_File and Response_File pair
2. EACH concurrent tool invocation SHALL wait for its own Response_File independently, without interfering with other pending invocations

### Requirement 10: Graceful Shutdown

**User Story:** As a developer, I want the server to shut down cleanly when stdin closes.

#### Acceptance Criteria

1. WHEN the stdin stream is closed (EOF), THE Stdio_Server SHALL shut down the MCP server and exit

