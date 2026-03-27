package ca.artemgm.mcpserver

import ca.artemgm.protocol.FileProtocolClient
import ca.artemgm.protocol.FileProtocolServer
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FileBridgeTest {

    private val tempDir = File("build/private/tmp/FileBridgeTest").apply { deleteRecursively(); mkdirs() }
    private val commandDir = tempDir.toPath().resolve(".intellij-dev-mcp").apply { Files.createDirectories(this) }
    private val receiver = FileProtocolServer(commandDir)
    private val bridge = FileBridge(FileProtocolClient(commandDir), responseTimeout = Duration.ofSeconds(10))
    private var serverThread: Thread? = null

    @AfterEach
    fun cleanup() {
        serverThread?.interrupt()
        serverThread?.join(5000)
    }

    @Test
    fun `response content matches what the server wrote`() {
        startServer { _, _ -> successResult("specific-payload-42") }

        val result = bridge.call("echo_tool", mapOf("key" to "value"))

        assertThat((result.content().first() as TextContent).text()).isEqualTo("specific-payload-42")
        assertThat(result.isError()).isFalse()
    }

    @Test
    fun `no tmp files remain after call completes`() {
        startServer { _, _ -> successResult("done") }

        bridge.call("cleanup_tool", emptyMap())

        assertThat(filesWithSuffix(".tmp")).isEmpty()
    }

    @Test
    fun `response file absent after successful call`() {
        startServer { _, _ -> successResult("transient") }

        bridge.call("transient_tool", emptyMap())

        assertThat(filesWithSuffix(".response.json")).isEmpty()
    }

    @Test
    fun `unconsumed request fails with actionable error`() {
        val noServerBridge = FileBridge(FileProtocolClient(commandDir), responseTimeout = Duration.ofMillis(200))

        assertThatThrownBy { noServerBridge.call("ghost_tool", emptyMap()) }
            .hasMessageContaining("not consumed")
    }

    private fun startServer(handler: (String, Map<String, Any?>) -> CallToolResult) {
        val ready = CountDownLatch(1)
        serverThread = Thread {
            ready.countDown()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val request = receiver.receiveRequest()
                    val result = handler(request.toolRequest.name(), request.toolRequest.arguments() ?: emptyMap())
                    receiver.sendResponse(request.id, result)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }.apply { isDaemon = true; start() }
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
    }

    private fun successResult(text: String) =
        CallToolResult.builder()
            .content(listOf(TextContent(text)))
            .isError(false)
            .build()

    private fun filesWithSuffix(suffix: String) = Files.list(commandDir).use { stream ->
        stream.filter { it.fileName.toString().endsWith(suffix) }.toList()
    }
}
