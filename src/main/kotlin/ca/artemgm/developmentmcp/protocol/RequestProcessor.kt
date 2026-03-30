package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult

class RequestProcessor(private val actionRegistry: ActionRegistry) {

    fun process(toolRequest: CallToolRequest): CallToolResult {
        val registration = actionRegistry.lookup(toolRequest.name())
            ?: return errorResult("Unknown tool: ${toolRequest.name()}")

        return try {
            registration.handler(toolRequest.arguments() ?: emptyMap())
        } catch (e: Exception) {
            errorResult("Tool execution failed: ${e.exceptionSummary()}")
        }
    }
}
