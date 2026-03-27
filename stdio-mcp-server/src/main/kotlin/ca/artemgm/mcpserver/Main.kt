package ca.artemgm.mcpserver

import ca.artemgm.protocol.FileProtocolClient
import ca.artemgm.protocol.PROTOCOL_DIR
import ca.artemgm.protocol.ProtocolLogger
import ca.artemgm.protocol.mcpLog
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import java.util.concurrent.CountDownLatch

fun main() {
    mcpLog = ProtocolLogger.forBridge(PROTOCOL_DIR.resolve("logs"))
    mcpLog.info("bridge starting")
    val tools = SchemaDiscovery.loadTools(PROTOCOL_DIR)
    mcpLog.info("loaded ${tools.size} tools from schema")
    val fileBridge = FileBridge(FileProtocolClient(PROTOCOL_DIR))
    val server = buildServer(tools, fileBridge)
    mcpLog.info("bridge ready")
    blockUntilShutdown(server)
}

private fun buildServer(tools: List<McpSchema.Tool>, fileBridge: FileBridge): McpSyncServer {
    val transportProvider = StdioServerTransportProvider(McpJsonDefaults.getMapper())
    val builder = McpServer.sync(transportProvider)
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(ServerCapabilities.builder().tools(false).build())

    tools.forEach { tool ->
        builder.toolCall(tool) { _, request ->
            fileBridge.call(request.name(), request.arguments() ?: emptyMap())
        }
    }

    return builder.build()
}

private fun blockUntilShutdown(server: McpSyncServer) {
    val shutdownLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            mcpLog.info("bridge shutting down")
            server.close()
        } finally {
            shutdownLatch.countDown()
        }
    })
    shutdownLatch.await()
}

private const val SERVER_NAME = "intellij-dev-mcp"
private const val SERVER_VERSION = "1.0.0"
