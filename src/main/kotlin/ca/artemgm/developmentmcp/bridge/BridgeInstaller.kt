package ca.artemgm.developmentmcp.bridge

import ca.artemgm.protocol.PROTOCOL_DIR
import mu.KotlinLogging
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

class BridgeInstaller internal constructor(
    private val installDir: Path,
    private val pluginVersion: String,
    private val resourceLoader: (String) -> InputStream?
) {

    constructor() : this(
        PROTOCOL_DIR,
        loadBundledVersion(),
        { BridgeInstaller::class.java.classLoader.getResourceAsStream(it) }
    )

    @Synchronized
    fun ensureBridge(): Boolean {
        val versionFile = installDir.resolve(VERSION_FILE)
        val binary = installDir.resolve(BINARY_PATH)

        if (Files.exists(versionFile) && Files.exists(binary)) {
            val installed = Files.readString(versionFile).trim()
            if (installed == pluginVersion) {
                log.debug { "Bridge is current ($pluginVersion), skipping installation" }
                return false
            }
        }

        val stream = resourceLoader(BRIDGE_RESOURCE)
        if (stream == null) {
            log.warn { "Bridge resource $BRIDGE_RESOURCE not found, skipping installation" }
            return false
        }

        val tmpDir = installDir.resolve(TMP_DIR_NAME)

        return try {
            if (Files.exists(tmpDir)) tmpDir.toFile().deleteRecursively()
            Files.createDirectories(tmpDir)

            stream.use { extractZip(it, tmpDir) }

            // Invalidate the version stamp before touching live dirs so a crash mid-swap forces reinstall on next startup.
            if (Files.exists(versionFile)) Files.delete(versionFile)

            swapDirectory("bin", tmpDir)
            swapDirectory("lib", tmpDir)

            Files.writeString(versionFile, pluginVersion)

            if (!System.getProperty("os.name").lowercase().contains("win")) {
                installDir.resolve(BINARY_PATH).toFile().setExecutable(true)
            }

            log.info { "Installed bridge version $pluginVersion" }
            true
        } catch (e: Exception) {
            log.error(e) { "Failed to install bridge" }
            if (Files.exists(tmpDir)) tmpDir.toFile().deleteRecursively()
            false
        }
    }

    companion object {
        const val VERSION_FILE = "bridge.version"
        const val BRIDGE_RESOURCE = "bridge/stdio-mcp-server.zip"
    }

    private fun extractZip(stream: InputStream, targetDir: Path) {
        val normalizedTarget = targetDir.normalize()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // The distZip wraps everything in a top-level directory we don't want in the install.
                val relativePath = entry.name.substringAfter("/", "")
                if (relativePath.isNotEmpty()) {
                    val dest = targetDir.resolve(relativePath).normalize()
                    require(dest.startsWith(normalizedTarget)) { "Zip entry escapes target dir: $relativePath" }
                    if (entry.isDirectory) {
                        Files.createDirectories(dest)
                    } else {
                        Files.createDirectories(dest.parent)
                        Files.copy(zip, dest)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun swapDirectory(name: String, tmpDir: Path) {
        val target = installDir.resolve(name)
        val source = tmpDir.resolve(name)
        if (Files.exists(target)) target.toFile().deleteRecursively()
        check(Files.exists(source)) { "Expected directory $name in extracted bridge, but it was missing" }
        Files.move(source, target)
    }
}

private const val BINARY_PATH = "bin/stdio-mcp-server"
private const val TMP_DIR_NAME = ".bridge-install-tmp"

private val log = KotlinLogging.logger { }

private fun loadBundledVersion() =
    BridgeInstaller::class.java.classLoader
        .getResourceAsStream("bridge-version.txt")
        ?.bufferedReader()?.readText()?.trim()
        ?: throw IllegalStateException("bridge-version.txt not found on classpath")
