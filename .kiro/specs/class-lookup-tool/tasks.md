# Implementation Plan: Class Lookup Tool

## Overview

Add a new `lookup_class` MCP tool to the IntelliJ plugin following the existing handler/tool split pattern established by `RunTestHandler`/`RunTestTool`. The implementation proceeds bottom-up: data models first, then the tool (PSI lookup + formatting), then the handler (validation + error handling), then registration and wiring.

## Tasks

- [x] 1. Create data models and formatting logic
  - [x] 1.1 Create `ClassLookupResult` and related data classes
    - Create file `src/main/kotlin/ca/artemgm/developmentmcp/protocol/ClassLookupResult.kt`
    - Define `ClassLookupResult(classes: List<ClassInfo>, totalMatches: Int, truncated: Boolean)`
    - Define `ClassInfo(fqn: String, methods: List<MethodInfo>, fields: List<FieldInfo>, interfaces: List<String>, superclass: String?)`
    - Define `MethodInfo(name: String, returnType: String, parameters: List<ParameterInfo>)`
    - Define `ParameterInfo(name: String, type: String)`
    - Define `FieldInfo(name: String, type: String)`
    - Define `DEFAULT_MAX_RESULTS = 20`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.2_

  - [x] 1.2 Implement `formatClassInfo` formatting function
    - Add a top-level function `formatClassLookupResult(result: ClassLookupResult): String` in the same file or a dedicated formatting file
    - Format each class block with FQN header, superclass, interfaces, methods with full signatures, and fields
    - Include truncation message when `result.truncated` is true, showing `totalMatches`
    - Match the output format specified in the design document
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1_

  - [x] 1.3 Write unit tests for formatting logic
    - Create `FormatClassLookupResultTest.kt` in the test directory
    - Test: output includes FQN, methods with signatures, fields with types, interfaces, and superclass for a fully populated ClassInfo
    - Test: absent superclass shows no-superclass indicator
    - Test: empty methods and fields sections render cleanly
    - Test: truncation message included when `truncated=true` with total match count
    - Test: no truncation message when `truncated=false`
    - Use JUnit 5 and AssertJ, follow project test naming conventions
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 3.1_

- [x] 2. Implement `ClassLookupTool` with PSI lookup logic
  - [x] 2.1 Create `ClassLookupTool` class with functional dependency injection
    - Create file `src/main/kotlin/ca/artemgm/developmentmcp/protocol/ClassLookupTool.kt`
    - Implement internal constructor accepting lambdas: `findClassesByFqn`, `findClassesByShortName`, `getAllClassNames`, `waitForSmartMode`
    - Implement production constructor accepting `Project` that wires `JavaPsiFacade`, `PsiShortNamesCache`, `DumbService`, and `ReadAction`
    - _Requirements: 6.1, 6.2, 6.3, 7.1, 7.2_

  - [x] 2.2 Implement pattern detection and lookup dispatch
    - Implement `lookup(pattern: String, maxResults: Int): ClassLookupResult`
    - Detect pattern type: wildcard (contains `*`), FQN (contains `.`), or simple name (neither)
    - FQN strategy: call `findClassesByFqn(pattern)`
    - Simple name strategy: call `findClassesByShortName(pattern)`
    - Wildcard strategy: call `getAllClassNames()`, expand each to classes via `findClassesByShortName`, filter FQNs against glob pattern
    - Call `waitForSmartMode()` before any PSI access
    - Cap results at `maxResults`, set `truncated` flag and `totalMatches` accordingly
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 3.1, 3.2, 6.3_

  - [x] 2.3 Implement PsiClass-to-ClassInfo extraction
    - Write a function to extract public methods, public fields, interfaces, and superclass from a `PsiClass`
    - Wrap PSI reads in `ReadAction.compute`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 2.4 Write unit tests for `ClassLookupTool`
    - Create `ClassLookupToolTest.kt` in the test directory
    - Test: FQN pattern dispatches to findClassesByFqn (e.g., `"java.util.List"`)
    - Test: simple name dispatches to findClassesByShortName (e.g., `"ArrayList"`)
    - Test: wildcard pattern filters FQNs by glob (e.g., `"*List"` matches `java.util.ArrayList` but not `java.util.Map`)
    - Test: wildcard with dot filters correctly (e.g., `"java.util.*Map"` matches `java.util.HashMap` but not `java.lang.Map`)
    - Test: results capped at maxResults with truncated flag and totalMatches set
    - Test: no truncation when matches are under the limit
    - Test: waitForSmartMode called before PSI access
    - Inject fake lambdas via the internal constructor, use JUnit 5 and AssertJ
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 3.1, 3.2, 6.3_

- [x] 3. Checkpoint
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement `ClassLookupHandler` with validation and error handling
  - [x] 4.1 Create `ClassLookupHandler` class
    - Create file `src/main/kotlin/ca/artemgm/developmentmcp/protocol/ClassLookupHandler.kt`
    - Implement internal constructor accepting `contextResolver: (String?) -> ResolvedProject` and `classLookup: (Project, String, Int) -> ClassLookupResult`
    - Implement production constructor accepting `ProjectResolver`
    - Annotate `handle` method with `@ToolDefinition(name = "lookup_class", ...)` and parameters with `@Param`
    - Implement `registration()` via `ReflectiveToolAdapter`
    - _Requirements: 4.1, 4.2, 4.4, 4.5, 7.1, 7.2_

  - [x] 4.2 Implement input validation and error handling in `handle`
    - Reject blank/empty `className` with `errorResult("className must not be blank")`
    - Catch `IllegalArgumentException` from context resolver, return `errorResult(e.message)`
    - Catch unexpected exceptions during lookup, return `errorResult("Tool execution failed: ${e.exceptionSummary()}")`
    - Return `errorResult("No classes found matching '<pattern>'")` when lookup returns zero matches
    - Format and return successful results via `formatClassLookupResult`
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x] 4.3 Add `resolveForLookup` to `ProjectResolver`
    - Add a method or standalone function that resolves project from optional `moduleName`
    - When `moduleName` is null, return the first open project
    - When `moduleName` is provided, find the module across open projects (reuse existing logic)
    - Define `ResolvedProject(val project: Project)` data class
    - _Requirements: 4.5_

  - [x] 4.4 Write unit tests for `ClassLookupHandler`
    - Create `ClassLookupHandlerTest.kt` in the test directory
    - Test: registration name is `lookup_class`, `className` is required, `moduleName` is optional in schema
    - Test: blank className produces error
    - Test: whitespace-only className produces error
    - Test: no-match error includes the searched pattern
    - Test: context resolution failure surfaces error message
    - Test: unexpected lookup exception surfaces error summary
    - Test: successful lookup returns formatted text
    - Test: moduleName forwarded to context resolver
    - Test: null moduleName forwarded when omitted
    - Follow the `RunTestHandlerTest` pattern using injected fakes, JUnit 5, and AssertJ
    - _Requirements: 4.1, 4.2, 4.4, 4.5, 5.1, 5.2, 5.3, 5.4_

- [x] 5. Register tool and wire into CommandProtocolService
  - [x] 5.1 Add `classLookupRegistration` function and register in `CommandProtocolService`
    - Add `classLookupRegistration(projectResolver: ProjectResolver)` function in `ClassLookupHandler.kt`
    - Add `actionRegistry.register(classLookupRegistration(ProjectResolver()))` in `CommandProtocolService.initialize()`
    - _Requirements: 4.3_

  - [x] 5.2 Write integration test for schema registration
    - Add a test to `CommandProtocolServiceTest.kt`
    - Verify `buildSchemaJson` output contains both `run_test` and `lookup_class` tools
    - _Requirements: 4.3_

- [x] 6. Final checkpoint
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- All tests use JUnit 5, AssertJ, and Kotlin — no property-based testing
- Tests use internal constructors with injected fakes (no IntelliJ runtime needed)
- Test names describe the business behavior being verified, not implementation details
