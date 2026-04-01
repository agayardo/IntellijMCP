package ca.artemgm.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class FileProtocolClient internal constructor(
    private val directory: Path,
    private val consumptionTimeout: Duration
) {

    constructor(directory: Path) : this(directory, REQUEST_CONSUMPTION_TIMEOUT)

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
        val now = Instant.now()
        val deadline = now.plus(timeout)
        waitForRequestConsumption(id, minOf(deadline, now.plus(consumptionTimeout)))
        val target = responsePath(directory, id)
        waitForFile(directory, deadline, "Timed out waiting for response to request ${id.value}") {
            it == target.fileName.toString()
        }
        try {
            return mapper.readValue(Files.readString(target), CallToolResult::class.java)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to parse response for request ${id.value}", e)
        } finally {
            Files.deleteIfExists(target)
        }
    }

    private fun waitForRequestConsumption(id: RequestId, deadline: Instant) {
        val requestFile = requestPath(directory, id)
        if (!Files.exists(requestFile)) return
        waitForFileDeletion(requestFile, deadline)
        if (Files.exists(requestFile)) {
            Files.deleteIfExists(requestFile)
            throw RequestNotConsumedException(id)
        }
    }
}

class RequestNotConsumedException(id: RequestId) : RuntimeException(
    "Request ${id.value} was not consumed by the server within $REQUEST_CONSUMPTION_TIMEOUT_MINUTES minutes. " +
        "Is the IDE plugin running and connected?"
)

private const val REQUEST_CONSUMPTION_TIMEOUT_MINUTES = 5L
private val REQUEST_CONSUMPTION_TIMEOUT = Duration.ofMinutes(REQUEST_CONSUMPTION_TIMEOUT_MINUTES)
