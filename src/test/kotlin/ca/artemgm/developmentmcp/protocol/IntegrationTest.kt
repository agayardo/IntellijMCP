package ca.artemgm.developmentmcp.protocol

import ca.artemgm.protocol.FileProtocolClient
import ca.artemgm.protocol.FileProtocolServer
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.Duration

class IntegrationTest {

    private val tempDir = File("build/private/tmp/IntegrationTest").apply { deleteRecursively(); mkdirs() }
    private val commandDir = tempDir.toPath().resolve(".intellij-dev-plugin")
    private lateinit var sender: FileProtocolClient
    private lateinit var serverThread: Thread

    @BeforeEach
    fun setup() {
        Files.createDirectories(commandDir)
        sender = FileProtocolClient(commandDir)
        val loop = RequestLoop(FileProtocolServer(commandDir), RequestProcessor(ActionRegistry().apply { register(HelloWorldTool {}.registration()) }))
        serverThread = Thread(loop).apply { isDaemon = true; name = "test-server"; start() }
    }

    @AfterEach
    fun cleanup() {
        serverThread.interrupt()
        serverThread.join(5000)
    }

    @Test
    fun `hello_world with no name argument greets World`() {
        val id = sender.sendRequest(CallToolRequest("hello_world", emptyMap()))
        val result = sender.receiveResponse(id, TIMEOUT)
        assertThat((result.content().first() as TextContent).text()).isEqualTo("Hello, World!")
    }

    @Test
    fun `hello world invocation writes greeting response`() {
        val id = sender.sendRequest(CallToolRequest("hello_world", mapOf("name" to "Alice")))
        val result = sender.receiveResponse(id, TIMEOUT)
        assertThat((result.content().first() as TextContent).text()).isEqualTo("Hello, Alice!")
    }

    @Test
    fun `no tmp files remain after response write`() {
        val id = sender.sendRequest(CallToolRequest("hello_world", emptyMap()))
        sender.receiveResponse(id, TIMEOUT)

        val tmpFiles = Files.list(commandDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }
}

private val TIMEOUT = Duration.ofSeconds(10)
