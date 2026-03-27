package ca.artemgm.protocol

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

// Set once at startup by Main.kt (bridge) or CommandProtocolService (plugin).
// Defaults to no-op so code works without initialization and tests don't need setup
// unless they want to capture log output.
var mcpLog: Logger = Logger.getLogger("mcp.noop").apply {
    level = Level.OFF
    useParentHandlers = false
}

object ProtocolLogger {

    // Single writer (one IntelliJ instance), so rotating files are safe
    fun forPlugin(logsDir: Path): Logger {
        Files.createDirectories(logsDir)
        val pattern = logsDir.resolve("plugin.log").toString()
        val handler = FileHandler(pattern, PLUGIN_MAX_BYTES, PLUGIN_FILE_COUNT, true)
        handler.formatter = SingleLineFormatter()
        return createLogger("mcp.plugin", handler)
    }

    fun forBridge(logsDir: Path): Logger = forBridge(logsDir, ProcessHandle.current().pid())

    // Each MCP client spawns its own bridge process, so PID in the filename avoids
    // cross-process write conflicts that JUL's FileHandler can't handle safely.
    internal fun forBridge(logsDir: Path, pid: Long): Logger {
        Files.createDirectories(logsDir)
        cleanStaleBridgeLogs(logsDir)
        val file = logsDir.resolve("bridge-$pid.log")
        // limit=0 disables rotation — stale cleanup on startup replaces it
        val handler = FileHandler(file.toString(), /* limit = */ 0, /* count = */ 1, /* append = */ true)
        handler.formatter = SingleLineFormatter()
        return createLogger("mcp.bridge.$pid", handler)
    }
}

internal fun cleanStaleBridgeLogs(logsDir: Path) = cleanStaleBridgeLogs(logsDir, Instant.now())

internal fun cleanStaleBridgeLogs(logsDir: Path, now: Instant) {
    if (!Files.isDirectory(logsDir)) return
    Files.list(logsDir).use { stream ->
        stream.filter { BRIDGE_LOG_PATTERN.matches(it.fileName.toString()) }
            .filter { Duration.between(Files.getLastModifiedTime(it).toInstant(), now) > STALE_LOG_THRESHOLD }
            .forEach { Files.deleteIfExists(it) }
    }
}

internal class SingleLineFormatter : Formatter() {
    override fun format(record: LogRecord): String {
        val timestamp = Instant.ofEpochMilli(record.millis)
        val thrown = record.thrown?.let { " ${it::class.simpleName}: ${it.message}" } ?: ""
        return "$timestamp ${record.level} ${record.message}$thrown\n"
    }
}

private fun createLogger(name: String, handler: FileHandler) = Logger.getLogger(name).apply {
    addHandler(handler)
    level = Level.ALL
    useParentHandlers = false
}

private val BRIDGE_LOG_PATTERN = Regex("bridge-\\d+\\.log")
private val STALE_LOG_THRESHOLD = Duration.ofHours(24)
private const val PLUGIN_MAX_BYTES = 10 * 1024 * 1024
private const val PLUGIN_FILE_COUNT = 3
