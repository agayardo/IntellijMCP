package ca.artemgm.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class FileProtocolClient(private val directory: Path) {

    fun sendRequest(request: CallToolRequest): RequestId {
        val id = RequestId.generate()
        val envelope = mapOf(
            "method" to "tools/call",
            "params" to request
        )
        writeAtomically(requestPath(directory, id), mapper.writeValueAsString(envelope))
        return id
    }

    fun receiveResponse(id: RequestId, timeout: Duration): CallToolResult {
        val target = responsePath(directory, id)
        val deadline = Instant.now().plus(timeout)
        waitForFile(directory, deadline) { it == target.fileName.toString() }
        try {
            return mapper.readValue(Files.readString(target), CallToolResult::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse response for request ${id.value}", e)
        } finally {
            Files.deleteIfExists(target)
        }
    }
}
