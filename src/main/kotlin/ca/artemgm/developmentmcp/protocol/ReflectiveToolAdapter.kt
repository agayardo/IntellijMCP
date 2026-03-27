package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaType

class ReflectiveToolAdapter(private val instance: Any, private val function: KFunction<*>) {

    fun toRegistration(): ToolRegistration {
        val tool = function.annotations.filterIsInstance<ToolDefinition>().firstOrNull()
            ?: throw IllegalArgumentException("Function ${function.name} is not annotated with @ToolDefinition")

        val paramDescriptionByName = function.valueParameters
            .mapNotNull { param ->
                val name = param.name ?: return@mapNotNull null
                param.annotations.filterIsInstance<Param>().firstOrNull()
                    ?.let { name to it.description }
            }
            .toMap()

        function.javaMethod?.isAccessible = true

        return ToolRegistration(
            name = tool.name,
            description = tool.description,
            inputSchema = generateInputSchema(paramDescriptionByName),
            handler = ::invoke
        )
    }

    private fun invoke(arguments: Map<String, Any?>): CallToolResult {
        val args = function.valueParameters.map { param ->
            coerce(arguments[param.name], param)
        }.toTypedArray()

        val isBound = function.parameters.none { it.kind == KParameter.Kind.INSTANCE }
        val result = if (isBound) function.call(*args) else function.call(instance, *args)
        if (result is CallToolResult) return result
        return CallToolResult.builder()
            .addContent(TextContent(result.toString()))
            .build()
    }

    // Kotlin reflection preserves parameter names without -parameters compiler flag,
    // unlike Java reflection which needs it.
    private fun generateInputSchema(paramDescriptionByName: Map<String, String>): String {
        val propertyByName = linkedMapOf<String, Any>()
        val requiredNames = mutableListOf<String>()

        for (param in function.valueParameters) {
            val name = param.name ?: continue
            val description = paramDescriptionByName[name] ?: name

            propertyByName[name] = if (isList(param)) {
                mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to description)
            } else {
                val javaType = param.type.javaType as? Class<*> ?: String::class.java
                mapOf("type" to jsonType(javaType), "description" to description)
            }
            if (!param.type.isMarkedNullable) requiredNames.add(name)
        }

        val schema = linkedMapOf<String, Any>("type" to "object", "properties" to propertyByName)
        if (requiredNames.isNotEmpty()) schema["required"] = requiredNames

        return mapper.writeValueAsString(schema)
    }
}

private val mapper = McpJsonDefaults.getMapper()

private fun jsonType(clazz: Class<*>) = when (clazz) {
    String::class.java, java.lang.String::class.java -> "string"
    Int::class.java, java.lang.Integer::class.java -> "integer"
    Long::class.java, java.lang.Long::class.java -> "integer"
    Double::class.java, java.lang.Double::class.java, Float::class.java, java.lang.Float::class.java -> "number"
    Boolean::class.java, java.lang.Boolean::class.java -> "boolean"
    else -> "string"
}

private fun isList(param: KParameter) =
    (param.type.javaType as? Class<*>)?.let { List::class.java.isAssignableFrom(it) } == true
        || param.type.classifier == List::class

@Suppress("UNCHECKED_CAST")
private fun coerce(value: Any?, param: KParameter): Any? {
    if (value == null) {
        if (!param.type.isMarkedNullable) throw IllegalArgumentException("Missing required parameter: ${param.name}")
        return null
    }
    if (isList(param)) return when (value) {
        is List<*> -> (value as List<Any?>).map { it?.toString() ?: "" }
        else -> listOf(value.toString())
    }
    return when (param.type.javaType) {
        String::class.java, java.lang.String::class.java -> value.toString()
        Int::class.java, java.lang.Integer::class.java -> (value as Number).toInt()
        Long::class.java, java.lang.Long::class.java -> (value as Number).toLong()
        Double::class.java, java.lang.Double::class.java -> (value as Number).toDouble()
        Float::class.java, java.lang.Float::class.java -> (value as Number).toFloat()
        Boolean::class.java, java.lang.Boolean::class.java -> value as Boolean
        else -> value
    }
}
