package ca.artemgm.developmentmcp.protocol

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

    @ToolDefinition(name = TOOL_NAME, description = "Runs JUnit tests in the IDE")
    fun handle(
        @Param(description = "Test scope: package, class, or method") scope: String,
        @Param(description = "Target: package name, class FQN, or class#method") target: String,
        @Param(description = "IntelliJ module name (optional)") moduleName: String?
    ): CallToolResult {
        val (testScope, error) = validateTestParams(scope, target)
        if (error != null) return error

        val resolveTarget = if (testScope == RunTestTool.TestScope.METHOD) target.substringBefore('#') else target

        val ctx = try {
            contextResolver(resolveTarget, moduleName)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Unknown error")
        }

        return toolFactory(ctx).handle(scope, target)
    }

    fun registration() = ReflectiveToolAdapter(this, ::handle).toRegistration()
}

internal fun runTestRegistration(projectResolver: ProjectResolver) =
    RunTestHandler(projectResolver).registration()

private fun errorResult(message: String) = CallToolResult.builder()
    .addContent(TextContent(message))
    .isError(true)
    .build()

private const val TOOL_NAME = "run_test"
