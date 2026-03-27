package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RequestProcessorTest {

    private val fixture = ProcessorFixture()

    @Test
    fun `registered tool returns its response`() {
        val result = process("echo", mapOf("msg" to "ping"))

        assertThat(textOf(result)).isEqualTo("irrelevant")
        assertThat(result.isError()).isFalse()
    }

    @Test
    fun `unregistered tool name produces unknown tool error`() {
        val result = process("nonexistent_tool", emptyMap())

        assertThat(result.isError()).isTrue()
        assertThat(textOf(result)).contains("Unknown tool: nonexistent_tool")
    }

    @Test
    fun `handler exception produces tool execution error`() {
        register("failing_tool") { throw RuntimeException("something broke") }

        val result = process("failing_tool", emptyMap())

        assertThat(result.isError()).isTrue()
        assertThat(textOf(result)).contains("Tool execution failed")
        assertThat(textOf(result)).contains("RuntimeException")
        assertThat(textOf(result)).contains("something broke")
    }

    @Test
    fun `dispatch invokes only the matching handler`() {
        val invoked = mutableListOf<String>()
        listOf("tool_alpha", "tool_beta", "tool_gamma").forEach { name ->
            register(name) { invoked.add(name); "from-$name" }
        }

        process("tool_beta", mapOf("key" to "val"))

        assertThat(invoked).containsExactly("tool_beta")
    }

    private fun register(name: String, handler: () -> String) {
        fixture.registry.register(textToolRegistration(name, handler))
    }

    private fun process(toolName: String, arguments: Map<String, Any?>) =
        fixture.processor.process(
            CallToolRequest.builder().name(toolName).arguments(arguments).build()
        )
}

private fun textToolRegistration(name: String, handler: () -> String) =
    ToolRegistration(name, "desc", "{}") {
        CallToolResult.builder()
            .addContent(TextContent(handler()))
            .build()
    }
