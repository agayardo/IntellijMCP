# Requirements Document

## Introduction

This feature adds a file-based command protocol to the DevelopmentMcp IntelliJ plugin. The goal is to expose IntelliJ IDE capabilities as MCP tools.

The MCP stdio transport requires the server to be a child process, which is impractical for a running IDE. To work around this, we use a file-based bridge: a lightweight stdio MCP server (built separately) translates between stdio JSON-RPC and this file protocol, writing `<UUID>.request.json` files into `~/.intellij-dev-plugin/` and reading back `<UUID>.response.json` files. The IDE plugin watches for these request files, executes the corresponding tool, and writes the response.

Because the bridge should be as thin as possible, the request and response payloads use MCP types directly (`CallToolRequest` params, `CallToolResult`), serialized with `io.modelcontextprotocol.sdk:mcp:1.1.0`. The plugin also publishes a `schema.json` describing available tools in MCP `ListToolsResult` format, so the bridge can serve `tools/list` requests without any transformation.

The initial implementation supports a single "hello world" tool as a skeleton for future expansion.

## Glossary

- **Plugin**: The DevelopmentMcp IntelliJ IDEA plugin
- **MCP_SDK**: The `io.modelcontextprotocol.sdk:mcp:1.1.0` library providing MCP protocol types and JSON schemas
- **Command_Directory**: The directory `~/.intellij-dev-plugin/` where request and response files are exchanged
- **Request_File**: A JSON file matching the pattern `<UUID>.request.json` placed in the Command_Directory by an external process
- **Response_File**: A JSON file matching the pattern `<UUID>.response.json` written by the Plugin into the Command_Directory
- **Schema_File**: The file `~/.intellij-dev-plugin/schema.json` that advertises available actions using the MCP tool listing format
- **Tool**: An MCP tool — a named operation the Plugin can execute, corresponding to an MCP `Tool` definition with `name`, `description`, and `inputSchema`
- **File_Watcher**: The Plugin component that monitors the Command_Directory for new Request_Files
- **Action_Registry**: The Plugin component that maintains the set of available Tools and their metadata
- **Request_Processor**: The Plugin component that reads a Request_File, dispatches to the appropriate Tool handler, and writes the Response_File

## Requirements

### Requirement 1: Command Directory Initialization

**User Story:** As a plugin developer, I want the plugin to ensure the command directory exists on startup, so that external processes have a reliable location to place request files.

#### Acceptance Criteria

1. WHEN the Plugin starts, THE Plugin SHALL create the Command_Directory if the Command_Directory does not already exist
2. WHEN the Plugin starts and the Command_Directory already exists, THE Plugin SHALL reuse the existing Command_Directory without modifying its contents
3. IF the Plugin cannot create the Command_Directory, THEN THE Plugin SHALL log an error message containing the path that failed

### Requirement 2: Schema File Publication

**User Story:** As an external tool developer, I want the plugin to publish a machine-readable schema of available tools, so that I can discover what commands the plugin supports and how to call them.

#### Acceptance Criteria

1. WHEN the Plugin starts, THE Plugin SHALL write the Schema_File to the Command_Directory as a JSON object conforming to the MCP `ListToolsResult` structure (a `tools` array of MCP `Tool` objects)
2. EACH Tool entry in the Schema_File SHALL contain: `name` (string), `description` (string), and `inputSchema` (a JSON Schema object describing the tool's parameters)
3. WHEN a new Tool is registered in the Action_Registry, THE Plugin SHALL update the Schema_File to include the new Tool
4. THE Schema_File SHALL be serializable/deserializable using the MCP SDK types from `io.modelcontextprotocol.sdk:mcp:1.1.0`
5. IF the Plugin cannot write the Schema_File, THEN THE Plugin SHALL log an error message containing the file path and the cause of the failure

### Requirement 3: Request File Watching

**User Story:** As an external process, I want the plugin to detect new request files promptly, so that my commands are executed without unnecessary delay.

#### Acceptance Criteria

1. WHEN the Plugin starts, THE File_Watcher SHALL begin monitoring the Command_Directory for new files matching the pattern `<UUID>.request.json`
2. WHEN a new Request_File appears in the Command_Directory, THE File_Watcher SHALL notify the Request_Processor within 2 seconds of the file being fully written
3. THE File_Watcher SHALL ignore files that do not match the `<UUID>.request.json` naming pattern
4. WHEN the Plugin is disposed, THE File_Watcher SHALL stop monitoring the Command_Directory
5. IF the File_Watcher encounters an error while monitoring, THEN THE File_Watcher SHALL log the error and continue monitoring

### Requirement 4: Request Processing

**User Story:** As an external process, I want the plugin to read my request file, execute the tool, and write a response, so that I can get results from the IDE programmatically.

#### Acceptance Criteria

1. WHEN the Request_Processor receives a Request_File, THE Request_Processor SHALL parse the JSON content as an MCP `CallToolRequest` and extract the tool name and arguments
2. WHEN the Request_Processor parses a valid request with a known tool name, THE Request_Processor SHALL dispatch the request to the corresponding Tool handler
3. WHEN a Tool handler completes successfully, THE Request_Processor SHALL write a Response_File with the same UUID as the Request_File, containing an MCP `CallToolResult` with the tool output
4. IF the Request_File contains invalid JSON, THEN THE Request_Processor SHALL write a Response_File containing an MCP `CallToolResult` with `isError` set to `true` and a descriptive error message in `content`
5. IF the Request_File references an unknown tool name, THEN THE Request_Processor SHALL write a Response_File containing an MCP `CallToolResult` with `isError` set to `true` indicating the tool is not recognized
6. IF a Tool handler throws an exception during execution, THEN THE Request_Processor SHALL write a Response_File containing an MCP `CallToolResult` with `isError` set to `true` and the exception message in `content`
7. WHEN the Request_Processor finishes processing a Request_File, THE Request_Processor SHALL delete the Request_File from the Command_Directory

### Requirement 5: Request and Response JSON Format (MCP-aligned)

**User Story:** As an external tool developer, I want the request and response files to follow the MCP JSON-RPC schema, so that I can use standard MCP tooling and types to interact with the plugin.

#### Acceptance Criteria

1. THE Request_File SHALL contain a JSON object conforming to the MCP `CallToolRequest` structure: a `method` field set to `"tools/call"`, and a `params` object containing `name` (string, the tool name) and `arguments` (object, the tool input parameters)
2. THE Response_File for a successful tool call SHALL contain a JSON object conforming to the MCP `CallToolResult` structure: a `content` array of content objects (each with `type` and `text` fields), and `isError` set to `false` or absent
3. THE Response_File for a failed tool call SHALL contain a JSON object conforming to the MCP `CallToolResult` structure: a `content` array containing the error description, and `isError` set to `true`
4. THE Request_Processor SHALL write the Response_File atomically by writing to a temporary file first and then renaming it to the final `<UUID>.response.json` name
5. THE request and response JSON structures SHALL be serializable/deserializable using the MCP SDK types from `io.modelcontextprotocol.sdk:mcp:1.1.0`

### Requirement 6: Hello World Tool

**User Story:** As a plugin developer, I want a skeleton "hello world" tool, so that I can verify the command protocol works end-to-end and use it as a template for future tools.

#### Acceptance Criteria

1. THE Action_Registry SHALL include a tool named `hello_world`
2. WHEN the `hello_world` tool is invoked with a `name` argument, THE `hello_world` tool SHALL return an MCP `CallToolResult` containing a greeting message that includes the provided name
3. WHEN the `hello_world` tool is invoked without a `name` argument, THE `hello_world` tool SHALL return an MCP `CallToolResult` containing a default greeting message
4. THE `hello_world` tool SHALL be advertised in the Schema_File as an MCP `Tool` with its `inputSchema` describing the optional `name` parameter

### Requirement 7: Action Registry Extensibility

**User Story:** As a plugin developer, I want to easily add new tools to the plugin, so that I can extend the command protocol with additional IDE capabilities over time.

#### Acceptance Criteria

1. THE Action_Registry SHALL allow registering new Tools by providing a tool name, a description, an `inputSchema` (JSON Schema), and a handler function
2. THE Action_Registry SHALL reject registration of a Tool with a name that is already registered and log a warning
3. THE Action_Registry SHALL provide a lookup function that returns the Tool handler for a given tool name, or null if no Tool is registered with that name

### Requirement 8: Threading and Lifecycle Safety

**User Story:** As a plugin developer, I want the file watching and request processing to respect IntelliJ threading rules and plugin lifecycle, so that the plugin does not cause IDE instability.

#### Acceptance Criteria

1. THE File_Watcher SHALL perform file I/O operations on a background thread, not on the EDT (Event Dispatch Thread)
2. WHEN an Action requires access to IntelliJ read actions or the project model, THE Request_Processor SHALL execute that access within a ReadAction block
3. THE Plugin SHALL tie all background tasks and watchers to a Disposable so that disposal of the Plugin cancels all pending work
4. WHEN the Plugin is disposed, THE Plugin SHALL stop the File_Watcher and cancel any in-progress request processing
