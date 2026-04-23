package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import java.lang.reflect.Proxy

internal val ECHO_SCHEMA = """{"type":"object","properties":{"msg":{"type":"string"}}}"""

internal class ProcessorFixture {
    val registry = ActionRegistry().apply { register(toolRegistration("echo", inputSchema = ECHO_SCHEMA)) }
    val processor = RequestProcessor(registry)
}

@Suppress("UNCHECKED_CAST")
internal fun parseSchema(registration: ToolRegistration) =
    McpJsonDefaults.getMapper().readValue(registration.inputSchema, Map::class.java) as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun schemaProperty(schema: Map<String, Any?>, name: String) =
    ((schema["properties"] as Map<String, Any?>)[name] as Map<String, Any?>)

private val IRRELEVANT_RESULT = CallToolResult.builder()
    .addContent(TextContent("irrelevant"))
    .build()

internal fun toolRegistration(
    name: String,
    description: String = "desc",
    inputSchema: String = "{}"
) = ToolRegistration(name, description, inputSchema) { IRRELEVANT_RESULT }

internal fun textOf(result: CallToolResult) =
    (result.content.first() as TextContent).text

internal fun stubModule(name: String = "stub-module"): Module = Proxy.newProxyInstance(
    Module::class.java.classLoader,
    arrayOf(Module::class.java)
) { _, method, _ ->
    if (method.name == "getName") name else null
} as Module

internal fun stubProject(name: String = "StubProject"): Project = Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java)
) { proxy, method, args ->
    when (method.name) {
        "getName" -> name
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.get(0)
        "toString" -> "StubProject($name)"
        else -> null
    }
} as Project

internal fun stubTool(module: Module) = RunTestTool(
    configCreator = { "stub" },
    executionLauncher = { _, _ -> ExecutionResult("ok", false, null) },
    filePathResolver = { null },
    classesInFile = { emptySet() },
    sourceReader = { null },
    module = module,
)

@Suppress("UNCHECKED_CAST")
internal fun requiredParams(schema: Map<String, Any?>) = schema["required"] as List<String>

internal fun assertOptionalStringParam(schema: Map<String, Any?>, name: String) {
    assertThat(schemaProperty(schema, name)["type"]).isEqualTo("string")
    assertThat(requiredParams(schema)).doesNotContain(name)
}