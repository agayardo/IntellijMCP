package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HelloWorldToolTest {

    private val tool = HelloWorldTool {}
    private val registration = tool.registration()

    @Test
    fun `greeting includes the provided name`() {
        assertThat(tool.handle("Alice")).isEqualTo("Hello, Alice!")
    }

    @Test
    fun `greeting defaults to World when name is null`() {
        assertThat(tool.handle(null)).isEqualTo("Hello, World!")
    }

    @Test
    fun `registration name is hello_world`() {
        assertThat(registration.name).isEqualTo("hello_world")
    }

    @Test
    fun `registration inputSchema describes the name parameter`() {
        val schema = parseSchema(registration)

        assertThat(schemaProperty(schema, "name")["type"]).isEqualTo("string")
        assertThat(schemaProperty(schema, "name")["description"]).isEqualTo("Name of the person to greet")
    }
}
