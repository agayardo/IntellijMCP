package ca.artemgm.mcpserver

import ca.artemgm.protocol.FileProtocolClient
import ca.artemgm.protocol.FileProtocolServer
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FileBridgeTest {

    private val tempDir = File("build/private/tmp/FileBridgeTest").apply { deleteRecursively(); mkdirs() }
    private val commandDir = tempDir.toPath().resolve(".intellij-dev-plugin")
    private lateinit var bridge: FileBridge
    private lateinit var receiver: FileProtocolServer
    private var serverThread: Thread? = null

    @BeforeEach
    fun setup() {
        Files.createDirectories(commandDir)
        receiver = FileProtocolServer(commandDir)
        bridge = FileBridge(FileProtocolClient(commandDir), responseTimeout = Duration.ofSeconds(10))
    }

    @AfterEach
    fun cleanup() {
        serverThread?.interrupt()
        serverThread?.join(5000)
    }

    @Test
    fun `response content matches what the server wrote`() {
        val capturedToolName = AtomicReference<String>()
        val capturedArguments = AtomicReference<Map<String, Any?>>()
        startServer { name, args ->
            capturedToolName.set(name)
            capturedArguments.set(args)
            successResult("specific-payload-42")
        }

        val result = bridge.call("echo_tool", mapOf("key" to "value"))

        assertThat((result.content().first() as TextContent).text()).isEqualTo("specific-payload-42")
        assertThat(result.isError()).isFalse()
        assertThat(capturedToolName.get()).isEqualTo("echo_tool")
        @Suppress("UNCHECKED_CAST")
        assertThat(capturedArguments.get()).containsEntry("key", "value")
    }

    @Test
    fun `no tmp files remain after call completes`() {
        startServer { _, _ -> successResult("done") }

        bridge.call("cleanup_tool", emptyMap())

        val tmpFiles = Files.list(commandDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun `response file absent after successful call`() {
        startServer { _, _ -> successResult("transient") }

        bridge.call("transient_tool", emptyMap())

        val responseFiles = Files.list(commandDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".response.json") }.toList()
        }
        assertThat(responseFiles).isEmpty()
    }

    @Test
    fun `timeout throws when no response appears`() {
        val shortTimeoutBridge = FileBridge(FileProtocolClient(commandDir), responseTimeout = Duration.ofMillis(100))

        assertThatThrownBy { shortTimeoutBridge.call("ghost_tool", emptyMap()) }
            .isInstanceOf(IllegalStateException::class.java)
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
}
