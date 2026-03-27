package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

internal class ProcessorFixture {
    val registry = ActionRegistry().apply { register(HelloWorldTool {}.registration()) }
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
