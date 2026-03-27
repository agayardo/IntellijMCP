# Implementation Plan: Run Test Tool

## Overview

Add a `run_test` MCP tool to the IntelliJ plugin that launches JUnit tests by scope (package, class, method). Implementation follows the established `HelloWorldTool` pattern with constructor-injected dependencies for testability. Requires new bundled plugin dependencies on `com.intellij.java` and `JUnit`.

## Tasks

- [x] 1. Add plugin dependencies for Java and JUnit APIs
  - [x] 1.1 Add bundled plugin dependencies in `build.gradle.kts`
    - Add `bundledPlugin("com.intellij.java")` and `bundledPlugin("JUnit")` inside the `intellijPlatform` block
    - _Requirements: 5.1, 5.2_

  - [x] 1.2 Add `<depends>` entries in `plugin.xml`
    - Add `<depends>com.intellij.modules.java</depends>`, `<depends>com.intellij.java</depends>`, and `<depends>JUnit</depends>` after the existing `<depends>com.intellij.modules.platform</depends>`
    - _Requirements: 5.3, 5.4, 5.5_

- [x] 2. Implement RunTestTool with validation and configuration logic
  - [x] 2.1 Create `TestScope` enum and `ConfigParams` data class in `RunTestTool.kt`
    - `TestScope` enum with `PACKAGE`, `CLASS`, `METHOD` entries
    - `ConfigParams` data class with `scope: TestScope`, `target: String`, `module: Module`
    - Place in `src/main/kotlin/com/amazon/rasp/developmentmcp/protocol/RunTestTool.kt`
    - _Requirements: 3.1, 3.2, 3.3_

  - [x] 2.2 Create `RunTestTool` class with internal constructor and parameter validation
    - Internal constructor accepting `configCreator`, `executionLauncher`, `moduleResolver` lambdas
    - No-arg production constructor wiring real IntelliJ APIs (`RunManager`, `ExecutionManager`, `ModuleManager`)
    - `handle()` method annotated with `@ToolDefinition(name = "run_test", ...)` taking `scope`, `target`, `moduleName` params
    - Validate scope is one of `package`, `class`, `method`; target is non-empty; method scope target contains `#`
    - On validation success: resolve module, create config via `configCreator`, launch via `executionLauncher`, return success with config name
    - On any failure: return error `CallToolResult` with descriptive message
    - `registration()` method delegating to `ReflectiveToolAdapter`
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.2, 4.3, 6.1, 6.2, 6.3_

  - [x] 2.3 Implement default `createJUnitConfig`, `launchConfig`, and `resolveModule` functions
    - `createJUnitConfig`: creates temporary `JUnitConfiguration` via `RunManager` with correct `TEST_OBJECT`, target fields, and module
    - `launchConfig`: launches via `DefaultRunExecutor` and `ProgramRunnerUtil`
    - `resolveModule`: resolves module by name via `ModuleManager`, or returns first module if name is null
    - These are private/top-level functions used by the production constructor
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1_

- [x] 3. Checkpoint
  - Ensure the project compiles with the new dependencies and RunTestTool class. Ask the user if questions arise.

- [x] 4. Register RunTestTool in CommandProtocolService
  - [x] 4.1 Register `RunTestTool` in `CommandProtocolService.initialize()`
    - Add `actionRegistry.register(RunTestTool(project).registration())` after the existing `HelloWorldTool` registration
    - _Requirements: 1.1_

- [x] 5. Write unit tests for RunTestTool
  - [x] 5.1 Write registration schema tests in `RunTestToolTest.kt`
    - `registration name is run_test` — verifies `registration().name`
    - `scope is required in schema` — scope in required array, type string
    - `target is required in schema` — target in required array, type string
    - `moduleName is optional in schema` — present in properties but NOT in required array
    - Use `parseSchema` and `schemaProperty` from `TestHelpers.kt`
    - _Requirements: 1.3, 1.4, 1.5, 1.6_

  - [x] 5.2 Write parameter validation tests in `RunTestToolTest.kt`
    - `unrecognized scope produces error listing valid values` — **Property 1** — _Validates: Requirements 2.1_
    - `empty target produces error` — _Validates: Requirements 2.3_
    - `method scope without hash separator produces error` — **Property 2** — _Validates: Requirements 2.2_

  - [x] 5.3 Write configuration and execution tests in `RunTestToolTest.kt`
    - `package scope passes PACKAGE scope to config creator` — **Property 3** — _Validates: Requirements 3.1_
    - `class scope passes CLASS scope to config creator` — **Property 3** — _Validates: Requirements 3.2_
    - `method scope passes METHOD scope and full target to config creator` — **Property 3** — _Validates: Requirements 3.3_
    - `unknown module name produces error listing available modules` — **Property 4** — _Validates: Requirements 3.6_
    - `successful invocation includes configuration name in result` — **Property 5** — _Validates: Requirements 4.1, 4.2_
    - `execution launcher failure produces error with reason` — **Property 6** — _Validates: Requirements 4.3_
    - `injected dependencies are used instead of real APIs` — _Validates: Requirements 6.3_

- [x] 6. Final checkpoint
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- The design uses Kotlin throughout, matching the existing codebase
- All tests use JUnit 5 + AssertJ only, following project testing guidelines
- Tests use the `internal` constructor with lambda test doubles — no IntelliJ platform required
- Existing `TestHelpers.kt` utilities (`parseSchema`, `schemaProperty`) are reused for schema assertions
