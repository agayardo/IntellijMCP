package ca.artemgm.developmentmcp.protocol

import ca.artemgm.protocol.FileProtocolClient
import ca.artemgm.protocol.FileProtocolServer
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
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
    private val commandDir = tempDir.toPath().resolve(".intellij-dev-mcp")
    private lateinit var sender: FileProtocolClient
    private lateinit var serverThread: Thread

    @BeforeEach
    fun setup() {
        Files.createDirectories(commandDir)
        sender = FileProtocolClient(commandDir)
        val registry = ActionRegistry().apply { register(echoRegistration()) }
        val loop = RequestLoop(FileProtocolServer(commandDir), RequestProcessor(registry))
        serverThread = Thread(loop).apply { isDaemon = true; name = "test-server"; start() }
    }

    @AfterEach
    fun cleanup() {
        serverThread.interrupt()
        serverThread.join(5000)
    }

    @Test
    fun `echo tool returns provided message`() {
        val id = sender.sendRequest(CallToolRequest("echo", mapOf("msg" to "ping")))
        val result = sender.receiveResponse(id, TIMEOUT)
        assertThat((result.content().first() as TextContent).text()).isEqualTo("ping")
    }

    @Test
    fun `echo tool with empty arguments returns fallback`() {
        val id = sender.sendRequest(CallToolRequest("echo", emptyMap()))
        val result = sender.receiveResponse(id, TIMEOUT)
        assertThat((result.content().first() as TextContent).text()).isEqualTo("(empty)")
    }

    @Test
    fun `no tmp files remain after response write`() {
        val id = sender.sendRequest(CallToolRequest("echo", emptyMap()))
        sender.receiveResponse(id, TIMEOUT)

        val tmpFiles = Files.list(commandDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".tmp") }.toList()
        }
        assertThat(tmpFiles).isEmpty()
    }
}

private fun echoRegistration() = ToolRegistration(
    name = "echo",
    description = "Echoes the msg argument",
    inputSchema = ECHO_SCHEMA
) { args ->
    val msg = args["msg"] as? String ?: "(empty)"
    CallToolResult.builder().addContent(TextContent(msg)).build()
}

private val TIMEOUT = Duration.ofSeconds(10)
