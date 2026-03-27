package ca.artemgm.developmentmcp.protocol

import ca.artemgm.protocol.mcpLog
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult

class RequestProcessor(private val actionRegistry: ActionRegistry) {

    fun process(toolRequest: CallToolRequest): CallToolResult {
        val name = toolRequest.name()
        val registration = actionRegistry.lookup(name)
            ?: run {
                mcpLog.warning("unknown tool: $name")
                return errorResult("Unknown tool: $name")
            }

        return try {
            registration.handler(toolRequest.arguments() ?: emptyMap())
        } catch (e: Exception) {
            mcpLog.severe("tool=$name execution failed: ${e.message}")
            errorResult("Tool execution failed: ${e.exceptionSummary()}")
        }
    }
}
