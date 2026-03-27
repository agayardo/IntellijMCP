package ca.artemgm.developmentmcp.protocol

import ca.artemgm.protocol.FileProtocolServer
import ca.artemgm.protocol.RequestParseException
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import mu.KotlinLogging

class RequestLoop(
    private val receiver: FileProtocolServer,
    private val processor: RequestProcessor
) : Runnable {

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val request = receiver.receiveRequest()
                val result = try {
                    processor.process(request.toolRequest)
                } catch (e: Exception) {
                    errorResult("Failed to process request: ${e.message}")
                }
                receiver.sendResponse(request.id, result)
            } catch (e: RequestParseException) {
                log.error(e) { "Failed to parse request" }
                receiver.sendResponse(e.requestId, errorResult(e.message ?: "Failed to parse request"))
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                log.error(e) { "Error processing request" }
            }
        }
    }
}

private fun errorResult(message: String): CallToolResult = CallToolResult.builder()
    .content(listOf(TextContent(message)))
    .isError(true)
    .build()

private val log = KotlinLogging.logger {}
