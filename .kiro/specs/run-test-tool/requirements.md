# Requirements Document

## Introduction

This feature adds a `run_test` MCP tool to the IntelliJ plugin that allows callers to programmatically run JUnit tests inside the IDE. The tool accepts a test scope (package, class, or method) and launches the corresponding JUnit run configuration via IntelliJ's execution infrastructure. It follows the existing tool pattern established by `HelloWorldTool` — a Kotlin class with `@ToolDefinition`/`@Param` annotations, a `registration()` method, and registration in `CommandProtocolService.initialize()`. Because the plugin currently depends only on `com.intellij.modules.platform`, new dependencies on `com.intellij.java` and `JUnit` bundled plugins are required.

## Glossary

- **Run_Test_Tool**: The MCP tool class that accepts test scope parameters and launches a JUnit run configuration inside IntelliJ.
- **Test_Scope**: The granularity of the test run — one of `package`, `class`, or `method`.
- **Target**: A string identifying what to run — a fully qualified package name, a fully qualified class name, or a fully qualified class name combined with a method name.
- **Module_Name**: The name of the IntelliJ project module whose classpath is used for test execution.
- **Run_Manager**: The IntelliJ `RunManager` API used to create and manage run configurations.
- **JUnit_Configuration**: An IntelliJ `JUnitConfiguration` object representing a JUnit run configuration with a specific test scope and target.
- **Execution_Manager**: The IntelliJ `ExecutionManager` API used to launch run configurations.
- **Command_Protocol_Service**: The project-level service that initializes and registers all MCP tools.
- **Action_Registry**: The registry that holds all tool registrations and dispatches tool calls.

## Requirements

### Requirement 1: Tool Registration

**User Story:** As an MCP client, I want the `run_test` tool to appear in the tool schema, so that I can discover and invoke it.

#### Acceptance Criteria

1. WHEN Command_Protocol_Service initializes, THE Command_Protocol_Service SHALL register Run_Test_Tool in the Action_Registry.
2. THE Run_Test_Tool SHALL expose a `registration()` method that returns a ToolRegistration built via `ReflectiveToolAdapter`.
3. THE Run_Test_Tool registration SHALL declare the tool name as `run_test`.
4. THE Run_Test_Tool registration input schema SHALL declare a required `scope` parameter of type string.
5. THE Run_Test_Tool registration input schema SHALL declare a required `target` parameter of type string.
6. THE Run_Test_Tool registration input schema SHALL declare an optional `moduleName` parameter of type string.

### Requirement 2: Parameter Validation

**User Story:** As an MCP client, I want clear error messages when I provide invalid parameters, so that I can correct my request.

#### Acceptance Criteria

1. WHEN the `scope` parameter value is not one of `package`, `class`, or `method`, THEN THE Run_Test_Tool SHALL return an error result describing the valid scope values.
2. WHEN the `scope` is `method` and the `target` does not contain a `#` separator between the class name and method name, THEN THE Run_Test_Tool SHALL return an error result describing the expected format `com.example.MyTest#myMethod`.
3. WHEN the `target` parameter is an empty string, THEN THE Run_Test_Tool SHALL return an error result indicating that the target is required.

### Requirement 3: JUnit Configuration Creation

**User Story:** As an MCP client, I want the tool to create the correct JUnit run configuration for my test scope, so that the right tests are executed.

#### Acceptance Criteria

1. WHEN `scope` is `package`, THE Run_Test_Tool SHALL create a JUnit_Configuration with `TEST_OBJECT` set to `TEST_PACKAGE` and `PACKAGE_NAME` set to the `target` value.
2. WHEN `scope` is `class`, THE Run_Test_Tool SHALL create a JUnit_Configuration with `TEST_OBJECT` set to `TEST_CLASS` and `MAIN_CLASS_NAME` set to the `target` value.
3. WHEN `scope` is `method`, THE Run_Test_Tool SHALL create a JUnit_Configuration with `TEST_OBJECT` set to `TEST_METHOD`, `MAIN_CLASS_NAME` set to the class portion of `target` (before `#`), and `METHOD_NAME` set to the method portion of `target` (after `#`).
4. THE Run_Test_Tool SHALL create the JUnit_Configuration as a temporary run configuration via Run_Manager.
5. WHEN `moduleName` is provided, THE Run_Test_Tool SHALL set the JUnit_Configuration module to the module matching `moduleName`.
6. IF the provided `moduleName` does not match any module in the project, THEN THE Run_Test_Tool SHALL return an error result listing the available module names.

### Requirement 4: Test Execution

**User Story:** As an MCP client, I want the tool to launch the test run inside IntelliJ, so that the tests execute in the IDE environment.

#### Acceptance Criteria

1. WHEN a valid JUnit_Configuration has been created, THE Run_Test_Tool SHALL launch the configuration using Execution_Manager on the EDT.
2. THE Run_Test_Tool SHALL return a success result containing the run configuration name that was launched.
3. IF Execution_Manager fails to launch the configuration, THEN THE Run_Test_Tool SHALL return an error result containing the failure reason.

### Requirement 5: Plugin Dependency Declaration

**User Story:** As a plugin developer, I want the build and plugin descriptor to declare the required JUnit and Java dependencies, so that the JUnit run configuration APIs are available at compile time and runtime.

#### Acceptance Criteria

1. THE build configuration SHALL declare `com.intellij.java` as a bundled plugin dependency.
2. THE build configuration SHALL declare `JUnit` as a bundled plugin dependency.
3. THE plugin descriptor (plugin.xml) SHALL declare `<depends>com.intellij.modules.java</depends>` to restrict the plugin to Java-capable IDEs.
4. THE plugin descriptor (plugin.xml) SHALL declare `<depends>com.intellij.java</depends>` for access to Java PSI and run configuration APIs.
5. THE plugin descriptor (plugin.xml) SHALL declare `<depends>JUnit</depends>` for access to JUnitConfiguration and JUnitConfigurationType.

### Requirement 6: Testability via Constructor Injection

**User Story:** As a plugin developer, I want the Run_Test_Tool to be unit-testable without a running IntelliJ instance, so that I can verify parameter validation and configuration logic in isolation.

#### Acceptance Criteria

1. THE Run_Test_Tool SHALL accept an internal constructor that takes functional dependencies (configuration creator, execution launcher) as parameters, following the HelloWorldTool pattern.
2. THE Run_Test_Tool SHALL provide a no-arg constructor that wires in the real IntelliJ API implementations for production use.
3. WHEN unit tests supply test-double functions via the internal constructor, THE Run_Test_Tool handler SHALL use those functions instead of real IntelliJ APIs.
