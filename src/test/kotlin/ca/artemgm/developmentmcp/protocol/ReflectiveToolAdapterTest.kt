package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReflectiveToolAdapterTest {

    @Test
    fun `generated schema includes all parameters with JSON types`() {
        val schema = parseSchema(ReflectiveToolAdapter(MultiParamTool, MultiParamTool::execute).toRegistration())

        assertThat(schemaProperty(schema, "label")["type"]).isEqualTo("string")
        assertThat(schemaProperty(schema, "count")["type"]).isEqualTo("integer")
        assertThat(schemaProperty(schema, "enabled")["type"]).isEqualTo("boolean")
    }

    @Test
    fun `non-nullable parameters appear in the required array`() {
        val schema = parseSchema(ReflectiveToolAdapter(MultiParamTool, MultiParamTool::execute).toRegistration())

        assertThat(schema["required"] as List<*>).containsExactly("label", "count", "enabled")
    }

    @Test
    fun `nullable parameters are omitted from the required array`() {
        val schema = parseSchema(ReflectiveToolAdapter(NullableParamTool, NullableParamTool::greet).toRegistration())

        assertThat(schemaProperty(schema, "name")["type"]).isEqualTo("string")
        assertThat(schema).doesNotContainKey("required")
    }

    @Test
    fun `handler invokes the function and wraps result in CallToolResult`() {
        val registration = ReflectiveToolAdapter(NullableParamTool, NullableParamTool::greet).toRegistration()
        val result = registration.handler(mapOf("name" to "Bob"))

        assertThat((result.content.first() as TextContent).text).isEqualTo("Hi, Bob!")
    }

    @Test
    fun `handler coerces numeric arguments to the declared parameter type`() {
        val registration = ReflectiveToolAdapter(MultiParamTool, MultiParamTool::execute).toRegistration()
        val result = registration.handler(mapOf("label" to "x", "count" to 42, "enabled" to true))

        assertThat((result.content.first() as TextContent).text).isEqualTo("x:42:true")
    }

    @Test
    fun `missing required parameter throws IllegalArgumentException`() {
        val registration = ReflectiveToolAdapter(MultiParamTool, MultiParamTool::execute).toRegistration()

        assertThatThrownBy { registration.handler(emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Missing required parameter")
    }

    @Test
    fun `absent nullable parameter is passed as null`() {
        val registration = ReflectiveToolAdapter(NullableParamTool, NullableParamTool::greet).toRegistration()
        val result = registration.handler(emptyMap())

        assertThat((result.content.first() as TextContent).text).isEqualTo("Hi, stranger!")
    }

    @Test
    fun `tool name and description match the ToolDefinition annotation values`() {
        val registration = ReflectiveToolAdapter(AnnotatedTool, AnnotatedTool::greet).toRegistration()

        assertThat(registration.name).isEqualTo("annotated_greet")
        assertThat(registration.description).isEqualTo("Greets someone by name")
    }

    @Test
    fun `input schema contains the Param annotation description`() {
        val schema = parseSchema(ReflectiveToolAdapter(AnnotatedTool, AnnotatedTool::greet).toRegistration())

        assertThat(schemaProperty(schema, "name")["description"]).isEqualTo("The name to greet")
    }

    @Test
    fun `unannotated function is rejected with IllegalArgumentException`() {
        assertThatThrownBy {
            ReflectiveToolAdapter(UnannotatedTool, UnannotatedTool::greet).toRegistration()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("@ToolDefinition")
    }

    @Test
    fun `schema falls back to parameter name when Param annotation is absent`() {
        val schema = parseSchema(ReflectiveToolAdapter(NoParamDescTool, NoParamDescTool::echo).toRegistration())

        assertThat(schemaProperty(schema, "message")["description"]).isEqualTo("message")
    }

    @Test
    fun `list parameter generates array schema with string items`() {
        val schema = parseSchema(ReflectiveToolAdapter(ListParamTool, ListParamTool::process).toRegistration())

        assertThat(schemaProperty(schema, "items")["type"]).isEqualTo("array")
        @Suppress("UNCHECKED_CAST")
        val items = schemaProperty(schema, "items")["items"] as Map<String, Any?>
        assertThat(items["type"]).isEqualTo("string")
    }

    @Test
    fun `handler coerces list argument elements to strings`() {
        val registration = ReflectiveToolAdapter(ListParamTool, ListParamTool::process).toRegistration()
        val result = registration.handler(mapOf("items" to listOf("a", "b", "c")))

        assertThat((result.content.first() as TextContent).text).isEqualTo("a,b,c")
    }
}

internal object MultiParamTool {
    @ToolDefinition(name = "multi_param", description = "Multi-param tool")
    fun execute(label: String, count: Int, enabled: Boolean) = "$label:$count:$enabled"
}

internal object NullableParamTool {
    @ToolDefinition(name = "nullable_param", description = "Nullable param tool")
    fun greet(name: String?) = "Hi, ${name ?: "stranger"}!"
}

internal object UnannotatedTool {
    fun greet(name: String?) = "Hi, ${name ?: "stranger"}!"
}

internal object AnnotatedTool {
    @ToolDefinition(name = "annotated_greet", description = "Greets someone by name")
    fun greet(@Param(description = "The name to greet") name: String?) = "Hi, ${name ?: "stranger"}!"
}

internal object NoParamDescTool {
    @ToolDefinition(name = "annotated_echo", description = "Echoes a message")
    fun echo(message: String) = message
}

internal object ListParamTool {
    @ToolDefinition(name = "list_param", description = "Processes a list")
    fun process(items: List<String>) = items.joinToString(",")
}
