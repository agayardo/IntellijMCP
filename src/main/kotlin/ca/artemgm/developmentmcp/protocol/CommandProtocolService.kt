package ca.artemgm.developmentmcp.protocol

import ca.artemgm.protocol.FileProtocolServer
import ca.artemgm.protocol.PROTOCOL_DIR
import ca.artemgm.protocol.ProtocolLogger
import ca.artemgm.protocol.mcpLog
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class CommandProtocolService : Disposable {

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        // ServiceLoader.load() uses the thread context classloader, which in IntelliJ
        // is the system classloader — not the plugin classloader that has the MCP JARs.
        Thread.currentThread().contextClassLoader = javaClass.classLoader

        Files.createDirectories(PROTOCOL_DIR)

        mcpLog = ProtocolLogger.forPlugin(PROTOCOL_DIR.resolve("logs"))
        mcpLog.info("plugin initializing")

        actionRegistry.register(runTestRegistration(ProjectResolver()))

        Files.writeString(PROTOCOL_DIR.resolve("schema.json"), buildSchemaJson(actionRegistry))
        mcpLog.info("wrote schema.json with ${actionRegistry.allTools().size} tools")

        val loop = RequestLoop(FileProtocolServer(PROTOCOL_DIR), RequestProcessor(actionRegistry))
        requestThread = Thread(loop).apply {
            isDaemon = true
            name = "request-loop"
            start()
        }
        mcpLog.info("plugin ready")
    }

    override fun dispose() {
        requestThread?.interrupt()
    }

    companion object {
        fun getInstance(): CommandProtocolService = service()
    }

    private val initialized = AtomicBoolean(false)
    private val actionRegistry = ActionRegistry()
    private var requestThread: Thread? = null
}

// internal rather than private — CommandProtocolService.initialize() requires an IntelliJ Project,
// making it impractical to test schema generation through the service's public API.
internal fun buildSchemaJson(registry: ActionRegistry): String {
    val mcpTools = registry.allTools().map { registration ->
        McpSchema.Tool.builder()
            .name(registration.name)
            .description(registration.description)
            .inputSchema(mapper, registration.inputSchema)
            .build()
    }
    return mapper.writeValueAsString(McpSchema.ListToolsResult(mcpTools, null))
}

private val mapper = McpJsonDefaults.getMapper()
