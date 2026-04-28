package ca.artemgm.developmentmcp.protocol

import ca.artemgm.protocol.mcpLog
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

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
        @Param(description = "Glob patterns for source file paths (relative to project root, e.g. **/MyFile.kt) to report uncovered line numbers for (optional)") coverageFor: List<String>? = null,
        @Param(description = "Number of trailing test output lines (stdout+stderr interleaved) to include in the response (optional, default 100, 0 to suppress)") outputLines: Int? = null,
        @Param(description = "Regex to filter test output lines — only lines containing a match are included (optional, default 'TEST DEBUG:')") outputFilter: String? = null
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

        val result = try {
            val outputFilterRegex = try {
                if (outputFilter != null) Regex(outputFilter) else RunTestTool.DEFAULT_OUTPUT_FILTER
            } catch (e: Exception) {
                return errorResult("Invalid outputFilter regex: ${e.message}")
            }
            toolFactory(ctx).handle(
                testScope, targets, coverageFor,
                outputLines = outputLines ?: RunTestTool.DEFAULT_OUTPUT_LINES,
                outputFilter = outputFilterRegex
            )
        } catch (e: Exception) {
            errorResult("Tool execution failed: ${e.exceptionSummary()}")
        }

        logTestSummary(result, scope, targets)
        return result
    }

    fun registration() = ReflectiveToolAdapter(this, ::handle).toRegistration()
}

internal fun runTestRegistration(projectResolver: ProjectResolver) =
    RunTestHandler(projectResolver).registration()

// Avoids logging full response bodies which can be large.
internal fun extractLogSummary(text: String): String {
    val parts = mutableListOf<String>()
    TEST_COUNTS_PATTERN.find(text)?.let { parts.add(it.value) }
    COVERAGE_LINE_PATTERN.find(text)?.let { parts.add("coverage=${it.groupValues[1]}") }
    return parts.joinToString(" ")
}

private fun logTestSummary(result: CallToolResult, scope: String, targets: List<String>) {
    val text = (result.content()?.firstOrNull() as? TextContent)?.text() ?: return
    val summary = extractLogSummary(text)
    mcpLog.info("run_test scope=$scope targets=${targets.joinToString(",")} isError=${result.isError()} $summary")
}

private fun resolveTarget(scope: RunTestTool.TestScope, target: String) =
    if (scope == RunTestTool.TestScope.METHOD) target.substringBefore('#') else target

private const val TOOL_NAME = "run_test"
private val TEST_COUNTS_PATTERN = Regex("Total: \\d+, Passed: \\d+, Failed: \\d+, Ignored: \\d+")
private val COVERAGE_LINE_PATTERN = Regex("Lines:\\s+\\d+/\\d+\\s+\\(([^)]+)\\)")
