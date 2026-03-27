package ca.artemgm.mcpserver

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult
import io.modelcontextprotocol.spec.McpSchema.Tool
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class SchemaDiscoveryTest {

    private val tempDir = File("build/private/tmp/SchemaDiscoveryTest").apply { deleteRecursively(); mkdirs() }
    private val mapper = McpJsonDefaults.getMapper()

    @Test
    fun `tool list from a single-tool schema — name, description, and inputSchema match`() {
        val inputSchema = """{"type":"object","properties":{"name":{"type":"string","description":"Who to greet"}},"required":["name"]}"""
        val tool = Tool.builder()
            .name("hello_world")
            .description("Returns a greeting message")
            .inputSchema(mapper, inputSchema)
            .build()
        writeSchemaFile(ListToolsResult(listOf(tool), null))

        val tools = SchemaDiscovery.loadTools(tempDir.toPath())

        assertThat(tools).hasSize(1)
        assertThat(tools.first().name()).isEqualTo("hello_world")
        assertThat(tools.first().description()).isEqualTo("Returns a greeting message")
        assertThat(tools.first().inputSchema()).isEqualTo(tool.inputSchema())
    }

    @Test
    fun `missing schema file — IllegalStateException with file path`() {
        val emptyDir = File(tempDir, "empty").apply { mkdirs() }

        assertThatThrownBy { SchemaDiscovery.loadTools(emptyDir.toPath()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("schema.json")
    }

    @Test
    fun `malformed JSON in schema file — IllegalStateException`() {
        Files.writeString(tempDir.toPath().resolve("schema.json"), "not valid json {{{")

        assertThatThrownBy { SchemaDiscovery.loadTools(tempDir.toPath()) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    private fun writeSchemaFile(result: ListToolsResult) {
        Files.writeString(tempDir.toPath().resolve("schema.json"), mapper.writeValueAsString(result))
    }
}
