# Implementation Plan: Stdio MCP Server

## Overview

Build a standalone CLI MCP server as a separate Gradle module (`stdio-mcp-server`) that bridges stdio JSON-RPC to the existing file-based command protocol. Implementation follows red-green TDD — every production code change starts with a failing test.

## Tasks

- [x] 1. Set up Gradle module and project structure
  - [x] 1.1 Create `stdio-mcp-server/build.gradle.kts` with `org.jetbrains.kotlin.jvm` + `application` plugins, MCP SDK dependency, test dependencies (JUnit 5, AssertJ), JVM 21 target, and `mainClass` set to `ca.artemgm.mcpserver.MainKt`
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  - [x] 1.2 Update `settings.gradle.kts` to include the new `stdio-mcp-server` module
    - _Requirements: 1.1_
  - [x] 1.3 Create `stdio-mcp-server/src/main/kotlin/com/amazon/rasp/mcpserver/ProtocolConstants.kt` with protocol constants at file scope: `REQUEST_SUFFIX`, `RESPONSE_SUFFIX`, `TMP_SUFFIX`, `SCHEMA_FILENAME`, `METHOD_TOOLS_CALL`, `RESPONSE_TIMEOUT: Duration`, `SERVER_NAME`, `SERVER_VERSION`
    - _Requirements: 6.1, 6.2, 6.3, 6.7, 8.2_
  - [x] 1.4 Create `stdio-mcp-server/src/main/resources/simplelogger.properties` configuring SLF4J Simple to write to `System.err`
    - _Requirements: 3.3_

- [x] 2. Checkpoint — Verify module compiles
  - Ensure the new module compiles. Ask the user if questions arise.

- [x] 3. Implement SchemaDiscovery (TDD)
  - [x] 3.1 Red-green TDD: `SchemaDiscovery.loadTools()` — tool list from a schema with one tool matches name, description, and inputSchema; missing file exits with code 1; malformed JSON exits with code 1
    - Write each failing test, then write the minimal `SchemaDiscovery` production code to make it pass
    - Create `SchemaDiscovery` object in `stdio-mcp-server/src/main/kotlin/com/amazon/rasp/mcpserver/SchemaDiscovery.kt`
    - `loadTools(commandDir: Path)` reads `schema.json`, deserializes as `ListToolsResult` via `McpJsonDefaults.getMapper()`, returns `tools()`
    - On missing file or invalid JSON: log error to stderr, call `exitProcess(1)`
    - Temp dirs via `File("build/private/tmp/SchemaDiscoveryTest").apply { deleteRecursively(); mkdirs() }`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 5.1, 5.2_
  - [ ]* 3.2 Schema round-trip with multiple tools — write a schema with 3 tools (varying inputSchemas: empty properties, required params, nested objects), load via `SchemaDiscovery.loadTools()`, assert each tool's name, description, and inputSchema match
    - **Validates: Requirements 2.1, 2.2, 5.1, 5.2**

- [x] 4. Implement FileBridge — request writing (TDD)
  - [x] 4.1 Red-green TDD: `FileBridge.call()` request file writing — request file content matches envelope format with tool name and arguments; filename matches UUID regex; no `.tmp` file remains after call completes
    - Write each failing test, then write the minimal `FileBridge` production code to make it pass
    - Create `FileBridge` class in `stdio-mcp-server/src/main/kotlin/com/amazon/rasp/mcpserver/FileBridge.kt`
    - Constructor takes `commandDir: Path`
    - `call(toolName: String, arguments: Map<String, Any?>): CallToolResult` — generates UUID, builds request envelope, writes atomically via tmp+rename, waits for response via `WatchService.poll(timeout)`, reads and deletes response file, throws on timeout
    - In tests, use a `CountDownLatch` and a background thread to write response files — no `Thread.sleep`
    - Separate business logic from low-level I/O: extract private functions for envelope building, atomic writing, response waiting, and response reading — `call()` should read like pseudocode
    - Only "why" comments — no "what" comments. If a step needs explaining, extract a function with that name
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 7.1, 7.2, 8.1, 8.2_
  - [ ]* 4.2 Request envelope round-trip — serialize a request with nested arguments (strings, numbers, booleans, nested maps), deserialize using the plugin's `parseCallToolRequest` approach, assert tool name and arguments match the originals
    - **Validates: Requirements 6.2, 8.1, 8.3**
  - [ ]* 4.3 Two consecutive calls produce distinct UUID filenames — invoke `FileBridge.call()` twice with simulated fast responses, assert the two request filenames differ and both match UUID regex
    - **Validates: Requirements 6.1, 8.2**

- [x] 5. FileBridge — response waiting and edge cases (TDD)
  - [x] 5.1 Red-green TDD: response waiting edge cases — response written via `CountDownLatch`-synchronized background thread is read back with matching content; response file absent after successful call; timeout throws when no response appears (use a very short injected `Duration`, assert exception within a generous latch timeout)
    - Never use `Thread.sleep` — use `CountDownLatch` with generous timeouts (10s) for synchronization, rely on timeouts only for failure
    - _Requirements: 6.4, 6.5, 6.6, 6.7, 7.1, 7.2_
  - [ ]* 5.2 Error response round-trip — write a `CallToolResult` with `isError=true` as a response file, assert `FileBridge.call()` returns a result with `isError=true` and matching error message
    - **Validates: Requirements 6.4, 6.5**
  - [ ]* 5.3 Response file absent after successful call — call `FileBridge.call()` with a simulated response, assert the response file no longer exists in the command directory
    - **Validates: Requirements 6.6**
  - [ ]* 5.4 Three concurrent calls each receive only their own response — launch 3 concurrent `FileBridge.call()` invocations via `ExecutorService`, write each response file keyed by UUID, assert each call's result matches its own response content
    - **Validates: Requirements 9.1, 9.2**

- [x] 6. Checkpoint — Verify SchemaDiscovery and FileBridge tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement Main entry point (TDD)
  - [x] 7.1 Red-green TDD: server name constant is `"intellij-dev-mcp"` and version is `"1.0.0"`, then implement `Main.kt`
    - Create `Main.kt` in `stdio-mcp-server/src/main/kotlin/com/amazon/rasp/mcpserver/Main.kt`
    - `main()` resolves command directory, calls `SchemaDiscovery.loadTools()`, creates `FileBridge`, builds `McpSyncServer` via `McpServer.sync(transportProvider)` with `StdioServerTransportProvider`, registers `tools/list` and `tools/call` handlers, adds shutdown hook, calls `server.connect()`
    - `main()` should read like pseudocode — each line states what happens in domain language, with all mechanical details (transport setup, server building) extracted behind intent-revealing names
    - _Requirements: 3.1, 3.2, 3.4, 4.1, 4.2, 4.3, 5.1, 5.2, 6.1, 10.1_

- [x] 8. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- All tests use `File("build/private/tmp/<TestClassName>").apply { deleteRecursively(); mkdirs() }` — no `@TempDir`, never the real `~/.intellij-dev-plugin/`
- JUnit5 + AssertJ only — no other test frameworks
- Never use `Thread.sleep` in tests — use `CountDownLatch` with generous timeouts for synchronization
