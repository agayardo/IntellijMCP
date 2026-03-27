package ca.artemgm.developmentmcp.protocol

import ca.artemgm.protocol.FileProtocolServer
import ca.artemgm.protocol.RequestParseException
import ca.artemgm.protocol.mcpLog
import java.time.Duration
import java.time.Instant
import java.util.logging.Level

class RequestLoop(
    private val receiver: FileProtocolServer,
    private val processor: RequestProcessor
) : Runnable {

    override fun run() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                val request = receiver.receiveRequest()
                val start = Instant.now()
                val result = try {
                    processor.process(request.toolRequest)
                } catch (e: Exception) {
                    mcpLog.log(Level.SEVERE, "[${request.id.value}] processing failed", e)
                    errorResult("Failed to process request: ${e.exceptionSummary()}")
                }
                val latency = Duration.between(start, Instant.now())
                mcpLog.info("[${request.id.value}] processed tool=${request.toolRequest.name()} isError=${result.isError()} latency=${latency.toMillis()}ms")
                receiver.sendResponse(request.id, result)
            } catch (e: RequestParseException) {
                mcpLog.log(Level.SEVERE, "[${e.requestId.value}] parse failed", e)
                receiver.sendResponse(e.requestId, errorResult(e.message ?: "Failed to parse request"))
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                mcpLog.log(Level.SEVERE, "unexpected error in request loop", e)
            }
        }
    }
}
