package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent

internal fun errorResult(message: String): CallToolResult = CallToolResult.builder()
    .addContent(TextContent(message))
    .isError(true)
    .build()

internal fun Exception.exceptionSummary(): String {
    val name = this::class.simpleName ?: this::class.java.name
    val msg = message
    val causeSummary = cause?.let { c ->
        val causeName = c::class.simpleName ?: c::class.java.name
        " caused by $causeName: ${c.message}"
    } ?: ""
    return if (msg != null) "$name: $msg$causeSummary" else "$name (no message)$causeSummary"
}
