package ca.artemgm.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger

class ProtocolLoggerTest {

    private val tempDir = File("build/private/tmp/ProtocolLoggerTest").apply { deleteRecursively(); mkdirs() }
    private val logsDir = tempDir.toPath().resolve("logs")
    private val loggersToClose = mutableListOf<Logger>()

    @AfterEach
    fun cleanup() {
        loggersToClose.forEach { logger -> logger.handlers.forEach { it.close() } }
    }

    @Nested
    inner class BridgeLogger {

        @Test
        fun `log file named with PID`() {
            val logger = createBridgeLogger(pid = 42)

            logger.info("test message")

            assertThat(logsDir.resolve("bridge-42.log")).exists()
        }

        @Test
        fun `log entry contains timestamp, level, and message`() {
            val logger = createBridgeLogger(pid = 1)

            logger.info("hello world")
            flushHandlers(logger)

            val content = Files.readString(logsDir.resolve("bridge-1.log"))
            assertThat(content).contains("INFO").contains("hello world")
            // Timestamp is ISO instant format
            assertThat(content).containsPattern("\\d{4}-\\d{2}-\\d{2}T")
        }

        @Test
        fun `stale bridge logs older than 24 hours are deleted`() {
            Files.createDirectories(logsDir)
            val staleFile = createBridgeLogFile("bridge-999.log", hoursAgo = 25)
            val freshFile = createBridgeLogFile("bridge-888.log", hoursAgo = 23)

            createBridgeLogger(pid = 1)

            assertThat(staleFile).doesNotExist()
            assertThat(freshFile).exists()
        }

        @Test
        fun `non-bridge log files are not deleted during cleanup`() {
            Files.createDirectories(logsDir)
            val pluginLog = logsDir.resolve("plugin.log")
            Files.writeString(pluginLog, "keep me")
            Files.setLastModifiedTime(pluginLog, FileTime.from(Instant.now().minus(Duration.ofHours(48))))

            createBridgeLogger(pid = 1)

            assertThat(pluginLog).exists()
        }

        private fun createBridgeLogger(pid: Long): Logger {
            val logger = ProtocolLogger.forBridge(logsDir, pid)
            loggersToClose.add(logger)
            return logger
        }
    }

    @Nested
    inner class PluginLogger {

        @Test
        fun `log file created in logs directory`() {
            val logger = createPluginLogger()

            logger.info("plugin message")
            flushHandlers(logger)

            assertThat(pluginLogContent()).contains("plugin message")
        }

        @Test
        fun `log entry contains timestamp, level, and message`() {
            val logger = createPluginLogger()

            logger.info("plugin event")
            flushHandlers(logger)

            val content = pluginLogContent()
            assertThat(content).contains("INFO").contains("plugin event")
        }

        // JUL's rotating FileHandler appends .0, .1, etc. to the pattern
        private fun pluginLogContent() = Files.list(logsDir).use { stream ->
            stream.filter { it.fileName.toString().startsWith("plugin.log") }
                .map { Files.readString(it) }
                .reduce { a, b -> a + b }
                .orElse("")
        }

        private fun createPluginLogger(): Logger {
            val logger = ProtocolLogger.forPlugin(logsDir)
            loggersToClose.add(logger)
            return logger
        }
    }

    @Nested
    inner class SingleLineFormatterTest {

        @Test
        fun `exception class name and message appended to log line`() {
            val formatter = SingleLineFormatter()
            val record = java.util.logging.LogRecord(Level.SEVERE, "something broke").apply {
                thrown = IllegalStateException("bad state")
            }

            val formatted = formatter.format(record)

            assertThat(formatted).contains("SEVERE")
                .contains("something broke")
                .contains("IllegalStateException: bad state")
            assertThat(formatted.lines().filter { it.isNotEmpty() }).hasSize(1)
        }
    }

    private fun createBridgeLogFile(name: String, hoursAgo: Long): File {
        val file = logsDir.resolve(name)
        Files.writeString(file, "old log data")
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofHours(hoursAgo))))
        return file.toFile()
    }

    private fun flushHandlers(logger: Logger) = logger.handlers.forEach { it.flush() }
}
