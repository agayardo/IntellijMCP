# Implementation Plan: File-Based Command Protocol

## Overview

Implement a file-based bridge for MCP tool calls in the DevelopmentMcp IntelliJ plugin. The plugin watches `~/.intellij-dev-plugin/` for JSON request files, dispatches to registered tool handlers, and writes JSON response files. All payloads use MCP SDK types directly. Implementation is in Kotlin targeting JVM 21 with IntelliJ Platform Gradle Plugin 2.x.

## Tasks

- [x] 1. Add dependencies and configure test framework
  - [x] 1.1 Add MCP SDK dependency and Kotest test dependencies to `build.gradle.kts`
    - Add `implementation("io.modelcontextprotocol.sdk:mcp:1.1.0")` to dependencies
    - Add `testImplementation("io.kotest:kotest-runner-junit5:5.9.1")`
    - Add `testImplementation("io.kotest:kotest-property:5.9.1")`
    - Add `testImplementation("io.kotest:kotest-assertions-core:5.9.1")`
    - Add `useJUnitPlatform()` to the test task configuration
    - _Requirements: Design § Dependency Addition, Design § Test Dependencies_

- [x] 2. Implement ActionRegistry and HelloWorldTool
  - [x] 2.1 Create `ToolRegistration` data class and `ActionRegistry` class
    - Create `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/ToolRegistration.kt`
    - Create `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/ActionRegistry.kt`
    - `ToolRegistration` holds `name`, `description`, `inputSchema` (JSON string), and `handler: (Map<String, Any?>) -> CallToolResult`
    - `ActionRegistry` uses `ConcurrentHashMap<String, ToolRegistration>`
    - `register()` returns `false` and logs warning if name already registered
    - `lookup()` returns `null` if no tool matches
    - `allTools()` returns a snapshot list
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 2.2 Write property test: ActionRegistry register-then-lookup round-trip
    - **Property 10: Action registry register-then-lookup round-trip**
    - **Validates: Requirements 7.1, 7.3**
    - Create `src/test/kotlin/com/amazon/rasp/developmentmcp/protocol/ActionRegistryPropertyTest.kt`
    - Generate random ToolRegistrations with unique names, register, lookup by name, verify match
    - Lookup unregistered names returns null
    - Minimum 100 iterations

  - [x] 2.3 Write property test: ActionRegistry rejects duplicate tool names
    - **Property 11: Action registry rejects duplicate tool names**
    - **Validates: Requirements 7.2**
    - Register a tool, attempt re-registration with same name, verify `register()` returns `false` and original unchanged

  - [x] 2.4 Create `HelloWorldTool` object
    - Create `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/HelloWorldTool.kt`
    - Tool name: `hello_world`, description: `"Returns a greeting message"`
    - `handle(arguments)`: returns greeting with provided `name`, or default `"Hello, World!"` if absent
    - `registration()`: builds `ToolRegistration` with JSON Schema for optional `name` parameter
    - Returns `CallToolResult` with text content using MCP SDK types
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [x] 2.5 Write property test: HelloWorld tool greeting contains the provided name
    - **Property 12: HelloWorld tool greeting contains the provided name**
    - **Validates: Requirements 6.2**
    - Generate random non-null name strings, invoke `hello_world`, verify output text contains the name

  - [x] 2.6 Write unit test: HelloWorld default greeting and schema
    - Test that invoking with no `name` argument returns a default greeting containing "World"
    - Test that `registration()` produces a `ToolRegistration` with correct name, description, and inputSchema
    - _Requirements: 6.3, 6.4_

- [x] 3. Implement RequestProcessor
  - [x] 3.1 Create `RequestProcessor` class
    - Create `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/RequestProcessor.kt`
    - Constructor takes `ActionRegistry` and `commandDir: Path`
    - `process(requestFile: Path)`: reads file, deserializes as MCP `CallToolRequest`, extracts `params.name` and `params.arguments`
    - Looks up tool in `ActionRegistry`, invokes handler
    - Writes `CallToolResult` to `<UUID>.response.json` atomically (write to `.tmp`, then `Files.move` with `ATOMIC_MOVE`)
    - Deletes request file after processing (success or failure)
    - Error handling: invalid JSON → error response, unknown tool → error response, handler exception → error response with exception message
    - Uses MCP SDK Jackson `ObjectMapper` for all JSON serialization/deserialization
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 3.2 Write property test: Valid request parsing extracts correct tool name and arguments
    - **Property 5: Valid request parsing extracts correct tool name and arguments**
    - **Validates: Requirements 4.1, 5.1**
    - Create `src/test/kotlin/com/amazon/rasp/developmentmcp/protocol/RequestProcessorPropertyTest.kt`
    - Generate random tool names and argument maps, build valid CallToolRequest JSON, parse, verify extracted values match

  - [x] 3.3 Write property test: Request dispatch invokes the correct handler
    - **Property 6: Request dispatch invokes the correct handler**
    - **Validates: Requirements 4.2**
    - Register random tools with distinct mock handlers, send requests, verify correct handler called with correct args

  - [x] 3.4 Write property test: Successful tool execution produces correctly-formed response with matching UUID
    - **Property 7: Successful tool execution produces a correctly-formed response with matching UUID**
    - **Validates: Requirements 4.3, 5.2**
    - Generate random tool outputs, process request, verify response UUID matches and CallToolResult is well-formed with `isError=false`

  - [x] 3.5 Write property test: Error conditions produce error responses
    - **Property 8: Error conditions produce error responses**
    - **Validates: Requirements 4.4, 4.5, 4.6, 5.3**
    - Generate invalid JSON strings, unknown tool names, and exception-throwing handlers, verify error responses have `isError=true` and descriptive messages

  - [x] 3.6 Write property test: Request file is deleted after processing
    - **Property 9: Request file is deleted after processing**
    - **Validates: Requirements 4.7**
    - Process random requests (success and failure), verify request file no longer exists

- [x] 4. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement FileWatcher
  - [x] 5.1 Create `FileWatcher` class
    - Create `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/FileWatcher.kt`
    - Constructor takes `commandDir: Path`, `onRequestFile: (Path) -> Unit`, `parentDisposable: Disposable`
    - `start()`: registers `WatchService` for `ENTRY_CREATE`, scans existing `*.request.json` files, enters blocking watch loop on daemon thread
    - `stop()`: closes `WatchService` to end the loop
    - UUID regex filter: `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.request\.json$`
    - Handles `OVERFLOW` events by performing full directory scan
    - On `ClosedWatchServiceException` or `InterruptedException`, exits cleanly
    - Logs errors and continues monitoring on other exceptions
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 8.1, 8.4_

  - [x] 5.2 Write property test: File watcher ignores non-matching filenames
    - **Property 4: File watcher ignores non-matching filenames**
    - **Validates: Requirements 3.3**
    - Create `src/test/kotlin/com/amazon/rasp/developmentmcp/protocol/FileWatcherPropertyTest.kt`
    - Generate random filenames (non-UUID, wrong extension, response files, schema file), verify watcher filter rejects them all

- [x] 6. Implement CommandProtocolService and schema writing
  - [x] 6.1 Create `CommandProtocolService` class
    - Create `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/CommandProtocolService.kt`
    - `@Service(Service.Level.PROJECT)`, implements `Disposable`
    - Owns `ActionRegistry`, `FileWatcher`, `RequestProcessor`
    - `initialize()`: creates command directory, registers `hello_world` tool, writes schema, starts watcher
    - `dispose()`: stops watcher, cancels pending work
    - Command directory path: `Path.of(System.getProperty("user.home"), ".intellij-dev-plugin")`
    - Directory creation: idempotent, logs error if creation fails
    - Schema write: serializes `ListToolsResult` using MCP SDK types, logs error on failure
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.4, 2.5, 8.3, 8.4_

  - [x] 6.2 Write property test: Directory initialization is idempotent and preserves existing content
    - **Property 1: Directory initialization is idempotent and preserves existing content**
    - **Validates: Requirements 1.1, 1.2**
    - Create `src/test/kotlin/com/amazon/rasp/developmentmcp/protocol/CommandProtocolServicePropertyTest.kt`
    - Generate random directory states (non-existent, empty, containing files), run init, verify directory exists and files preserved

  - [x] 6.3 Write property test: Schema file reflects all registered tools with required fields
    - **Property 2: Schema reflects all registered tools with required fields**
    - **Validates: Requirements 2.1, 2.2, 2.3**
    - Generate random lists of ToolRegistrations, register all, write schema, verify JSON contains all tools with correct fields

  - [x] 6.4 Write property test: MCP type serialization round-trip
    - **Property 3: MCP type serialization round-trip**
    - **Validates: Requirements 2.4, 5.1, 5.5**
    - Generate random CallToolRequest/CallToolResult/ListToolsResult instances, serialize to JSON, deserialize, assert equality

- [x] 7. Create CommandProtocolStartupActivity and register in plugin.xml
  - [x] 7.1 Create `CommandProtocolStartupActivity` class
    - Create `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/CommandProtocolStartupActivity.kt`
    - Implements `ProjectActivity`
    - `execute(project)`: obtains `CommandProtocolService.getInstance(project)` and calls `initialize()`
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 7.2 Register startup activity and service in `plugin.xml`
    - Add `<postStartupActivity>` extension for `CommandProtocolStartupActivity`
    - Add `<projectService>` extension for `CommandProtocolService`
    - _Requirements: 8.3_

- [x] 8. Integration wiring and final verification
  - [x] 8.1 Write unit tests for lifecycle and threading
    - Test that after disposal, WatchService is closed and no new files are processed
    - Test that response write uses temp file + rename (atomic write)
    - Test that placing a request file triggers processing
    - _Requirements: 3.2, 3.4, 5.4, 8.3, 8.4_

  - [x] 8.2 Write unit tests for error logging
    - Simulate unwritable command directory path, verify error is logged with the path
    - Simulate unwritable schema file path, verify error is logged with path and cause
    - _Requirements: 1.3, 2.5_

- [x] 9. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate the 12 correctness properties from the design document
- All tests should use temporary directories (via `createTempDirectory()`) instead of the real `~/.intellij-dev-plugin/`
- All JSON handling uses MCP SDK's Jackson ObjectMapper for wire-format compatibility
- The implementation language is Kotlin throughout, matching the design document
