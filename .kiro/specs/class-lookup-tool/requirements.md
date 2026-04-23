# Requirements Document

## Introduction

This feature adds a new MCP tool (`lookup_class`) to the IntelliJ plugin that allows callers to look up Java/Kotlin classes by name pattern and retrieve their public interface. The tool accepts partial class names, fully qualified names, or wildcard patterns (using `*`), searches the entire project classpath (sources and libraries/JARs), and returns the fully qualified name(s) of matching classes along with their public methods, fields, implemented interfaces, and superclass.

## Glossary

- **Class_Lookup_Tool**: The MCP tool exposed by the plugin that accepts a class name pattern and returns matching classes with their public interface information.
- **Class_Name_Pattern**: A string provided by the caller that identifies one or more classes. It may be a fully qualified name (e.g., `java.util.List`), a simple/partial name (e.g., `ArrayList`), or a wildcard pattern using `*` (e.g., `Abstract*List`).
- **Public_Interface**: The set of publicly visible members of a class, including public methods (with full signatures), public fields, implemented interfaces, and the superclass.
- **Classpath_Scope**: The full search scope encompassing project source files and all library/JAR dependencies, corresponding to `GlobalSearchScope.allScope(project)`.
- **Handler**: A class following the plugin's tool-handler pattern — annotated with `@ToolDefinition`, using constructor injection, and producing a `ToolRegistration` via `ReflectiveToolAdapter`.
- **Project_Resolver**: The existing component that resolves which IntelliJ project and module to use for a given request.

## Requirements

### Requirement 1: Class Name Pattern Search

**User Story:** As an MCP client, I want to search for classes by name pattern, so that I can discover classes without knowing their exact fully qualified name.

#### Acceptance Criteria

1. WHEN a fully qualified class name is provided as the Class_Name_Pattern, THE Class_Lookup_Tool SHALL search the Classpath_Scope and return the matching class if it exists.
2. WHEN a simple (unqualified) class name is provided as the Class_Name_Pattern, THE Class_Lookup_Tool SHALL search the Classpath_Scope for all classes whose simple name matches exactly and return all matches.
3. WHEN a Class_Name_Pattern containing one or more `*` wildcard characters is provided, THE Class_Lookup_Tool SHALL treat each `*` as matching zero or more characters and return all classes in the Classpath_Scope whose fully qualified name matches the resulting pattern.
4. WHEN the Class_Name_Pattern matches more than one class, THE Class_Lookup_Tool SHALL return information for each matching class.

### Requirement 2: Public Interface Extraction

**User Story:** As an MCP client, I want to see the public interface of a matched class, so that I can understand its API without reading the full source code.

#### Acceptance Criteria

1. FOR ALL matched classes, THE Class_Lookup_Tool SHALL return the fully qualified class name.
2. FOR ALL matched classes, THE Class_Lookup_Tool SHALL return the list of public methods, each including the method name, parameter types, and return type.
3. FOR ALL matched classes, THE Class_Lookup_Tool SHALL return the list of public fields, each including the field name and type.
4. FOR ALL matched classes, THE Class_Lookup_Tool SHALL return the list of directly implemented interfaces as fully qualified names.
5. FOR ALL matched classes, THE Class_Lookup_Tool SHALL return the superclass as a fully qualified name, or indicate that no explicit superclass exists.

### Requirement 3: Result Limiting

**User Story:** As an MCP client, I want results to be bounded, so that a broad wildcard pattern does not produce an overwhelmingly large response.

#### Acceptance Criteria

1. WHEN the Class_Name_Pattern matches more classes than a configured maximum limit, THE Class_Lookup_Tool SHALL return results for only the first classes up to the limit and include a message indicating the total number of matches and that results were truncated.
2. THE Class_Lookup_Tool SHALL use a default maximum limit of 20 matched classes.

### Requirement 4: Tool Registration and Schema

**User Story:** As a plugin maintainer, I want the new tool to follow the existing handler pattern, so that it integrates consistently with the plugin architecture.

#### Acceptance Criteria

1. THE Class_Lookup_Tool SHALL be implemented as a Handler class with a `@ToolDefinition`-annotated method and `@Param`-annotated parameters.
2. THE Class_Lookup_Tool SHALL produce a `ToolRegistration` via `ReflectiveToolAdapter`, consistent with the existing `RunTestHandler` pattern.
3. THE Class_Lookup_Tool SHALL be registered in `CommandProtocolService.initialize()` alongside the existing `run_test` tool.
4. THE Class_Lookup_Tool SHALL accept a required `className` parameter of type `String` representing the Class_Name_Pattern.
5. THE Class_Lookup_Tool SHALL accept an optional `moduleName` parameter of type `String` for project/module disambiguation via the Project_Resolver.

### Requirement 5: Error Handling

**User Story:** As an MCP client, I want clear error messages when a lookup fails, so that I can correct my request.

#### Acceptance Criteria

1. WHEN the Class_Name_Pattern is empty or blank, THE Class_Lookup_Tool SHALL return an error result with a descriptive message.
2. WHEN no classes match the provided Class_Name_Pattern, THE Class_Lookup_Tool SHALL return an error result stating that no matching classes were found and echoing the pattern that was searched.
3. IF the Project_Resolver fails to resolve a project or module, THEN THE Class_Lookup_Tool SHALL return the error message from the Project_Resolver.
4. IF an unexpected exception occurs during class lookup, THEN THE Class_Lookup_Tool SHALL return an error result containing the exception summary.

### Requirement 6: Classpath Scope Search

**User Story:** As an MCP client, I want the tool to search across all available classes including library JARs, so that I can look up classes from dependencies.

#### Acceptance Criteria

1. THE Class_Lookup_Tool SHALL search using `GlobalSearchScope.allScope(project)` to include both project sources and library/JAR dependencies.
2. THE Class_Lookup_Tool SHALL use IntelliJ's PSI index APIs (`JavaPsiFacade`, `ReadAction`, `DumbService`) to perform the search.
3. WHILE the IntelliJ index is in dumb mode (not yet fully built), THE Class_Lookup_Tool SHALL wait for smart mode before executing the search.

### Requirement 7: Constructor Injection for Testability

**User Story:** As a plugin developer, I want the handler to support constructor injection of its dependencies, so that I can test it with fakes and stubs without requiring a running IntelliJ instance.

#### Acceptance Criteria

1. THE Class_Lookup_Tool Handler SHALL provide a primary constructor that accepts functional dependencies (context resolver, class lookup logic) for production use.
2. THE Class_Lookup_Tool Handler SHALL provide an internal constructor that accepts the same functional dependencies as explicit parameters, enabling test doubles to be injected.
