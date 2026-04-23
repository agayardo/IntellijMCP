package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommandProtocolServiceTest {

    private val mapper = McpJsonDefaults.getMapper()

    @Test
    fun `schema JSON contains all registered tools with name, description, and inputSchema`() {
        val registry = ActionRegistry()
        registry.register(toolRegistration("tool_a", description = "Description A", inputSchema = """{"type":"object"}"""))
        registry.register(toolRegistration("tool_b", description = "Description B", inputSchema = """{"type":"object"}"""))

        val tools = deserializeSchemaJson(buildSchemaJson(registry))

        assertThat(tools.map { it.name() }).containsExactlyInAnyOrder("tool_a", "tool_b")
        tools.forEach { tool ->
            assertThat(tool.description()).isNotBlank()
            assertThat(tool.inputSchema()).isNotNull
        }
    }

    @Test
    fun `schema JSON contains both run_test and lookup_class tools`() {
        val registry = ActionRegistry()
        registry.register(toolRegistration("run_test"))
        registry.register(toolRegistration("lookup_class"))

        val tools = deserializeSchemaJson(buildSchemaJson(registry))

        assertThat(tools.map { it.name() }).containsExactlyInAnyOrder("run_test", "lookup_class")
    }

    @Test
    fun `CallToolResult survives serialization round-trip`() {
        val original = CallToolResult.builder()
            .content(listOf(TextContent("hello output")))
            .isError(true)
            .build()

        val restored = roundTrip(original, CallToolResult::class.java)

        assertThat((restored.content().first() as TextContent).text()).isEqualTo("hello output")
        assertThat(restored.isError()).isTrue()
    }

    @Test
    fun `ListToolsResult survives serialization round-trip`() {
        val original = ListToolsResult(listOf(buildTool("round_trip_tool", "A tool for testing")), null)

        val restored = roundTrip(original, ListToolsResult::class.java)

        assertThat(restored.tools()).hasSize(1)
        assertThat(restored.tools().first().name()).isEqualTo("round_trip_tool")
        assertThat(restored.tools().first().description()).isEqualTo("A tool for testing")
    }

    private fun <T> roundTrip(original: T, clazz: Class<T>) =
        mapper.readValue(mapper.writeValueAsString(original), clazz)

    private fun deserializeSchemaJson(json: String) =
        mapper.readValue(json, ListToolsResult::class.java).tools()

    private fun buildTool(name: String, description: String) =
        Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(mapper, """{"type":"object"}""")
            .build()
}
