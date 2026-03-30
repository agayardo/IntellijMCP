package ca.artemgm.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FileProtocolTest {

    private val tempDir = File("build/private/tmp/FileProtocolTest").apply { deleteRecursively(); mkdirs() }
    private val directory = tempDir.toPath()
    private val sender = FileProtocolClient(directory)
    private val receiver = FileProtocolServer(directory)

    @Test
    fun `request tool name and arguments survive the sender-receiver round trip`() {
        val id = sender.sendRequest(CallToolRequest("my_tool", mapOf("key" to "value", "nested" to mapOf("a" to 1))))

        val received = receiver.receiveRequest()
        receiver.sendResponse(received.id, buildResponse("result-payload-xyz", isError = true))

        val result = sender.receiveResponse(id, Duration.ofSeconds(10))

        assertThat(received.toolRequest.name()).isEqualTo("my_tool")
        assertThat(received.toolRequest.arguments())
            .containsEntry("key", "value")
            .containsEntry("nested", mapOf("a" to 1))
        assertThat(result.isError()).isTrue()
        assertThat((result.content().first() as TextContent).text()).isEqualTo("result-payload-xyz")
    }

    @Test
    fun `no request, response, or tmp files remain after a full round trip`() {
        val id = sender.sendRequest(CallToolRequest("any_tool", emptyMap()))
        val received = receiver.receiveRequest()
        receiver.sendResponse(received.id, buildResponse("done"))
        sender.receiveResponse(id, Duration.ofSeconds(10))

        assertThat(Files.list(directory).use { it.toList() }).isEmpty()
    }

    @Nested
    inner class StaleRequestExpiration {

        private val now = Instant.parse("2025-01-01T00:10:00Z")
        private val fixedClock = Clock.fixed(now, ZoneOffset.UTC)
        private val receiverWithFixedClock = FileProtocolServer(directory, fixedClock)

        @Test
        fun `stale request is deleted and skipped in favor of a fresh one`() {
            createRequestFile("stale_tool", now.minus(Duration.ofMinutes(6)))

            val received = receiveAfterSending(CallToolRequest("fresh_tool", emptyMap()))

            assertThat(received.toolRequest.name()).isEqualTo("fresh_tool")
            assertThat(requestFileCount()).isZero()
        }

        @Test
        fun `request just under the staleness threshold is processed normally`() {
            createRequestFile("recent_tool", now.minus(Duration.ofMinutes(4).plusSeconds(59)), mapOf("x" to "y"))

            val received = receiverWithFixedClock.receiveRequest()

            assertThat(received.toolRequest.name()).isEqualTo("recent_tool")
            assertThat(received.toolRequest.arguments()).containsEntry("x", "y")
        }

        @Test
        fun `request exactly at the staleness threshold is discarded`() {
            createRequestFile("threshold_tool", now.minus(Duration.ofMinutes(5)))

            val received = receiveAfterSending(CallToolRequest("fresh_tool", emptyMap()))

            assertThat(received.toolRequest.name()).isEqualTo("fresh_tool")
        }

        private fun receiveAfterSending(freshRequest: CallToolRequest): ReceivedRequest {
            val latch = CountDownLatch(1)
            val result = AtomicReference<ReceivedRequest>()
            val thread = Thread {
                result.set(receiverWithFixedClock.receiveRequest())
                latch.countDown()
            }
            thread.start()
            sender.sendRequest(freshRequest)
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue()
            return result.get()
        }
    }

    @Nested
    inner class RequestConsumptionTimeout {

        private val senderWithZeroTimeout = FileProtocolClient(directory, Duration.ZERO)

        @Test
        fun `unconsumed request throws RequestNotConsumedException`() {
            val id = senderWithZeroTimeout.sendRequest(CallToolRequest("ignored_tool", emptyMap()))

            assertThatThrownBy { senderWithZeroTimeout.receiveResponse(id, Duration.ofSeconds(10)) }
                .isInstanceOf(RequestNotConsumedException::class.java)
                .hasMessageContaining(id.value)
        }

        @Test
        fun `unconsumed request file is cleaned up on timeout`() {
            val id = senderWithZeroTimeout.sendRequest(CallToolRequest("ignored_tool", emptyMap()))

            runCatching { senderWithZeroTimeout.receiveResponse(id, Duration.ofSeconds(10)) }

            assertThat(Files.exists(requestPath(directory, id))).isFalse()
        }
    }

    @Nested
    inner class EarlyRequestFileDeletion {

        @Test
        fun `request file is deleted before receiveRequest returns`() {
            sender.sendRequest(CallToolRequest("any_tool", emptyMap()))

            val received = receiver.receiveRequest()

            assertThat(requestFileCount()).isZero()
            assertThat(received.toolRequest.name()).isEqualTo("any_tool")
        }
    }

    private fun buildResponse(text: String, isError: Boolean = false) =
        CallToolResult.builder()
            .content(listOf(TextContent(text)))
            .isError(isError)
            .build()

    private fun createRequestFile(toolName: String, lastModified: Instant, arguments: Map<String, Any> = emptyMap()) =
        requestPath(directory, RequestId.generate()).also { path ->
            val envelope = mapOf("method" to "tools/call", "params" to CallToolRequest(toolName, arguments))
            writeAtomically(path, mapper.writeValueAsString(envelope))
            Files.setLastModifiedTime(path, FileTime.from(lastModified))
        }

    private fun requestFileCount() =
        Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(REQUEST_SUFFIX) }.count()
        }
}
