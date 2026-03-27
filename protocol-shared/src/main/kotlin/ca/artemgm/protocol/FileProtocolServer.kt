package ca.artemgm.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Clock
import java.time.Duration

class FileProtocolServer internal constructor(
    private val directory: Path,
    private val clock: Clock
) {

    constructor(directory: Path) : this(directory, Clock.systemUTC())

    fun receiveRequest(): ReceivedRequest {
        while (true) {
            val requestFile = waitForFile(directory, timeoutMessage = "Timed out waiting for request") {
                isRequestFile(it)
            }
            if (isStale(requestFile)) {
                mcpLog.info("deleted stale request ${requestFile.fileName}")
                Files.deleteIfExists(requestFile)
                continue
            }
            val id = RequestId(requestFile.fileName.toString().removeSuffix(REQUEST_SUFFIX))
            val content = try {
                Files.readString(requestFile)
            } catch (e: Exception) {
                throw RequestParseException(id, e)
            } finally {
                Files.deleteIfExists(requestFile)
            }
            try {
                @Suppress("UNCHECKED_CAST")
                val envelope = mapper.readValue(content, Map::class.java) as Map<String, Any?>
                val params = envelope["params"]
                    ?: throw IllegalArgumentException("Request JSON missing 'params' field")
                val request = mapper.convertValue(params, io.modelcontextprotocol.spec.McpSchema.CallToolRequest::class.java)
                mcpLog.info("[${id.value}] received tool=${request.name()}")
                return ReceivedRequest(id, request)
            } catch (e: Exception) {
                throw RequestParseException(id, e)
            }
        }
    }

    fun sendResponse(id: RequestId, result: CallToolResult) {
        writeAtomically(responsePath(directory, id), mapper.writeValueAsString(result))
    }

    private fun isStale(file: Path): Boolean = try {
        val fileAge = Duration.between(Files.getLastModifiedTime(file).toInstant(), clock.instant())
        fileAge >= STALE_THRESHOLD
    } catch (_: NoSuchFileException) {
        true
    }
}

class RequestParseException(val requestId: RequestId, cause: Exception) :
    RuntimeException("Failed to parse request ${requestId.value}", cause)

private val STALE_THRESHOLD = Duration.ofMinutes(5)
