package ca.artemgm.mcpserver

import ca.artemgm.protocol.FileProtocolClient
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import java.time.Duration

internal class FileBridge(
    private val sender: FileProtocolClient,
    private val responseTimeout: Duration = RESPONSE_TIMEOUT
) {

    fun call(toolName: String, arguments: Map<String, Any?>): CallToolResult {
        val id = sender.sendRequest(CallToolRequest(toolName, arguments))
        return sender.receiveResponse(id, responseTimeout)
    }
}

private val RESPONSE_TIMEOUT = Duration.ofSeconds(120)
