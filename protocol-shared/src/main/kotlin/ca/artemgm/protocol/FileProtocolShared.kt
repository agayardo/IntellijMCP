package ca.artemgm.protocol

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@JvmInline
value class RequestId(val value: String) {
    companion object {
        fun generate() = RequestId(UUID.randomUUID().toString())
    }
}

data class ReceivedRequest(val id: RequestId, val toolRequest: CallToolRequest)

val PROTOCOL_DIR: Path get() = Path.of(System.getProperty("user.home"), ".intellij-dev-plugin")

internal fun findExisting(dir: Path, matches: (String) -> Boolean): Path? =
    Files.list(dir).use { stream ->
        stream.filter { matches(it.fileName.toString()) }.findFirst().orElse(null)
    }

internal fun waitForFile(directory: Path, deadline: Instant = Instant.MAX, matches: (String) -> Boolean): Path {
    findExisting(directory, matches)?.let { return it }

    directory.fileSystem.newWatchService().use { ws ->
        directory.register(ws, ENTRY_CREATE)

        findExisting(directory, matches)?.let { return it }

        while (Instant.now().isBefore(deadline)) {
            val key = if (deadline == Instant.MAX) {
                ws.take()
            } else {
                val remaining = Duration.between(Instant.now(), deadline)
                if (remaining.isNegative) break
                ws.poll(remaining.toMillis(), TimeUnit.MILLISECONDS) ?: break
            }
            try {
                for (event in key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        findExisting(directory, matches)?.let { return it }
                        continue
                    }
                    val filename = (event.context() as? Path)?.fileName?.toString() ?: continue
                    if (matches(filename)) return directory.resolve(filename)
                }
            } finally {
                key.reset()
            }
        }
    }
    throw IllegalStateException("Timed out waiting for file")
}

internal fun writeAtomically(target: Path, content: String) {
    val tmpFile = target.resolveSibling("${target.fileName}$TMP_SUFFIX")
    Files.writeString(tmpFile, content)
    Files.move(tmpFile, target, StandardCopyOption.ATOMIC_MOVE)
}

internal fun requestPath(directory: Path, id: RequestId) = directory.resolve("${id.value}$REQUEST_SUFFIX")

internal fun responsePath(directory: Path, id: RequestId) = directory.resolve("${id.value}$RESPONSE_SUFFIX")

internal fun isRequestFile(filename: String) =
    filename.endsWith(REQUEST_SUFFIX) && filename.length > REQUEST_SUFFIX.length

internal const val REQUEST_SUFFIX = ".request.json"
internal const val RESPONSE_SUFFIX = ".response.json"

internal val mapper = McpJsonDefaults.getMapper()

private const val TMP_SUFFIX = ".tmp"
