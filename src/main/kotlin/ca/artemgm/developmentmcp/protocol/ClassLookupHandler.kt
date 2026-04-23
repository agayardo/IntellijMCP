package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.project.Project
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

class ClassLookupHandler internal constructor(
    private val contextResolver: (String?) -> List<ResolvedProject>,
    private val classLookup: (Project, String) -> ClassLookupResult
) {

    constructor(projectResolver: ProjectResolver) : this(
        contextResolver = { moduleName -> projectResolver.resolveForLookup(moduleName) },
        classLookup = { project, pattern -> ClassLookupTool(project).lookup(pattern) }
    )

    @ToolDefinition(
        name = "lookup_class",
        description = "Looks up Java/Kotlin classes by name pattern and returns their public interface (methods, fields, interfaces, superclass)"
    )
    fun handle(
        @Param(description = "Class name pattern: fully qualified name, simple name, or wildcard pattern using *")
        className: String,
        @Param(description = "IntelliJ module name (optional)")
        moduleName: String?
    ): CallToolResult {
        if (className.isBlank()) return errorResult("className must not be blank")

        val resolvedProjects = try {
            contextResolver(moduleName)
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Unknown error")
        }

        val results = resolvedProjects.map { resolved ->
            try {
                classLookup(resolved.project, className).copy(moduleName = resolved.project.name)
            } catch (e: Exception) {
                return errorResult("Tool execution failed: ${e.exceptionSummary()}")
            }
        }

        val totalClasses = results.sumOf { it.classes.size }
        if (totalClasses == 0) return errorResult("No classes found matching '$className'")

        return CallToolResult.builder()
            .addContent(TextContent(formatClassLookupResult(results)))
            .build()
    }

    fun registration() = ReflectiveToolAdapter(this, ::handle).toRegistration()
}

internal fun classLookupRegistration(projectResolver: ProjectResolver) =
    ClassLookupHandler(projectResolver).registration()
