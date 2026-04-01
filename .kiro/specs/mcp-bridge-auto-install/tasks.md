# Implementation Plan: MCP Bridge Auto-Install

## Overview

Bundle the `stdio-mcp-server` distribution zip inside the IntelliJ plugin and automatically extract it into `~/.intellij-dev-mcp/` at IDE startup. Implementation proceeds bottom-up: build system changes first, then the `BridgeInstaller` class, then wiring into the startup activity. Each step is testable in isolation before integration.

## Tasks

- [x] 1. Configure build system to bundle bridge distribution and version hash
  - [x] 1.1 Add `processResources` task dependency on `:stdio-mcp-server:distZip` to copy the bridge zip into plugin resources
    - In root `build.gradle.kts`, configure `processResources` to copy the output of `:stdio-mcp-server:distZip` into `bridge/stdio-mcp-server.zip`
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 1.2 Add `generateBridgeVersion` task to compute SHA-256 of the bridge zip and write `bridge-version.txt`
    - Register a task that reads the `distZip` output, computes its SHA-256 hash, and writes it to `build/generated/bridge-version/bridge-version.txt`
    - Add the generated directory as a resource source directory so it ends up on the classpath
    - _Requirements: 3.1, 4.5_

- [x] 2. Implement `BridgeInstaller` class
  - [x] 2.1 Create `BridgeInstaller` with `internal` constructor, public no-arg constructor, and `ensureBridge()` method
    - Create `src/main/kotlin/ca/artemgm/developmentmcp/bridge/BridgeInstaller.kt`
    - `internal` constructor takes `installDir: Path`, `pluginVersion: String`, `resourceLoader: (String) -> InputStream?`
    - Public no-arg constructor uses `PROTOCOL_DIR`, reads `bridge-version.txt` resource, and uses classloader resource loading
    - `ensureBridge()` returns `Boolean`: `true` if installation was performed, `false` if skipped
    - Implement version check: read `bridge.version` from install dir, compare to `pluginVersion`
    - If bridge is current and `bin/stdio-mcp-server` exists, log debug message and return `false`
    - If missing or outdated: load `bridge/stdio-mcp-server.zip` from `resourceLoader`, extract to `.bridge-install-tmp/` temp dir, swap `bin/` and `lib/` into install dir, write `bridge.version`, set executable permission on Unix, return `true`
    - Clean up stale `.bridge-install-tmp/` before extraction begins
    - If resource is null, log warning and return `false`
    - Catch all exceptions during extraction: log error, clean up temp dir, return `false`, leave existing install intact
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 5.1, 5.2, 5.3, 5.4, 6.1, 7.1, 7.2_

  - [x] 2.2 Write unit tests for `BridgeInstaller`
    - Create `src/test/kotlin/ca/artemgm/developmentmcp/bridge/BridgeInstallerTest.kt`
    - Use JUnit 5 + AssertJ. No other test frameworks. Use `internal` constructor to inject test seams.
    - Temp dir: `File("build/private/tmp/BridgeInstallerTest").apply { deleteRecursively(); mkdirs() }` — no `@TempDir`
    - Test names describe the assertion as a business requirement (no "should/returns/handles/correct")
    - Test through the public API (`ensureBridge()`). Do not expose or test internals directly.
    - Required test cases:
      - Missing version file triggers installation
      - Missing binary triggers installation
      - Outdated version marker triggers reinstallation
      - Up-to-date bridge with matching marker and existing binary skips installation
      - Null resource loader (bundled zip not found) skips installation gracefully
      - Executable permission set on `bin/stdio-mcp-server` after extraction (non-Windows)
      - Stale `.bridge-install-tmp/` directory cleaned up before extraction
      - I/O failure during extraction leaves existing `bin/` and `lib/` intact
      - Version marker contains the expected version string after successful installation
    - Extract helper functions for recurring setup (e.g., creating synthetic bridge zips, populating install dirs). Each helper does one thing.
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2, 4.4, 4.5, 5.1, 5.2, 5.3, 6.1, 7.1_

- [x] 3. Checkpoint - Verify BridgeInstaller in isolation
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Wire `BridgeInstaller` into startup activity
  - [x] 4.1 Modify `CommandProtocolStartupActivity` to launch `BridgeInstaller.ensureBridge()` on a daemon thread
    - Add a daemon `Thread` named `bridge-installer` that calls `BridgeInstaller().ensureBridge()`
    - Keep existing `CommandProtocolService.getInstance().initialize()` call unchanged
    - _Requirements: 6.2, 6.3, 5.4_

  - [x] 4.2 Write unit tests for `CommandProtocolStartupActivity` bridge installer integration
    - Verify the startup activity launches bridge installation without blocking `CommandProtocolService.initialize()`
    - Use JUnit 5 + AssertJ. Test through the public API.
    - _Requirements: 6.2, 6.3_

- [x] 5. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Production code changes always have required unit tests — no exceptions per project conventions
- Each task references specific requirements for traceability
- The `BridgeInstaller` uses an `internal` constructor for test seams per project conventions
- All tests use JUnit 5 + AssertJ only — no other test or mocking frameworks
- Correctness properties from the design are validated through example-based unit tests with well-chosen inputs and edge cases
- Temp directories follow project convention: `File("build/private/tmp/<TestClassName>").apply { deleteRecursively(); mkdirs() }` — no `@TempDir`
- Test names describe the assertion as a business requirement — no "should/returns/handles/correct"
- No coroutines — daemon threads only, consistent with existing `RequestLoop` pattern
