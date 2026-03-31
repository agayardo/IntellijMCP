package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolResult

class RunTestHandler internal constructor(
    private val contextResolver: (String, String?) -> ResolvedContext,
    private val toolFactory: (ResolvedContext) -> RunTestTool
) {

    constructor(projectResolver: ProjectResolver) : this(
        contextResolver = projectResolver::resolve,
        toolFactory = { ctx -> RunTestTool(ctx.project, ctx.module) }
    )

    @ToolDefinition(name = TOOL_NAME, description = "Runs JUnit tests in the IDE and reports test coverage")
    fun handle(
        @Param(description = "Test scope: package, class, or method") scope: String,
        @Param(description = "Targets: list of package names, class FQNs, or class#method strings") targets: List<String>,
        @Param(description = "IntelliJ module name (optional)") moduleName: String?,
        @Param(description = "Glob patterns for source file paths (relative to project root, e.g. **/MyFile.kt) to report uncovered line numbers for (optional)") coverageFor: List<String>? = null
    ): CallToolResult {
        if (targets.isEmpty()) return errorResult("Targets list must not be empty")

        var testScope: RunTestTool.TestScope? = null
        for (target in targets) {
            val (parsedScope, error) = validateTestParams(scope, target)
            if (error != null) return error
            if (testScope == null) testScope = parsedScope
        }
        testScope!!

        if (testScope == RunTestTool.TestScope.METHOD && targets.size > 1) {
            return errorResult("Method scope supports only a single target")
        }

        val resolveTarget = resolveTarget(testScope, targets.first())

        val ctx = try {
            contextResolver(resolveTarget, moduleName)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Unknown error")
        }

        return try {
            toolFactory(ctx).handle(testScope, targets, coverageFor)
        } catch (e: Exception) {
            errorResult("Tool execution failed: ${e.exceptionSummary()}")
        }
    }

    fun registration() = ReflectiveToolAdapter(this, ::handle).toRegistration()
}

internal fun runTestRegistration(projectResolver: ProjectResolver) =
    RunTestHandler(projectResolver).registration()

private fun resolveTarget(scope: RunTestTool.TestScope, target: String) =
    if (scope == RunTestTool.TestScope.METHOD) target.substringBefore('#') else target

private const val TOOL_NAME = "run_test"
