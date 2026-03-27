package ca.artemgm.developmentmcp.bridge

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class BridgeInstallerTest {

    private val tempDir = File("build/private/tmp/BridgeInstallerTest").apply { deleteRecursively(); mkdirs() }
    private val installDir = tempDir.toPath()
    private val pluginVersion = "abc123"

    @Test
    fun `missing version file triggers installation`() {
        installDir.resolve("bin").toFile().mkdirs()
        installDir.resolve("bin/stdio-mcp-server").toFile().writeText("old-binary")

        val result = installer().ensureBridge()

        assertThat(result).isTrue()
        assertThat(installDir.resolve("bin/stdio-mcp-server")).exists()
    }

    @Test
    fun `missing binary triggers installation`() {
        Files.writeString(installDir.resolve(BridgeInstaller.VERSION_FILE), pluginVersion)

        val result = installer().ensureBridge()

        assertThat(result).isTrue()
        assertThat(installDir.resolve("bin/stdio-mcp-server")).exists()
    }

    @Test
    fun `outdated version marker triggers reinstallation`() {
        Files.writeString(installDir.resolve(BridgeInstaller.VERSION_FILE), "old-version")
        populateBinAndLib()

        val result = installer().ensureBridge()

        assertThat(result).isTrue()
    }

    @Test
    fun `up-to-date bridge with matching marker and existing binary skips installation`() {
        Files.writeString(installDir.resolve(BridgeInstaller.VERSION_FILE), pluginVersion)
        populateBinAndLib()

        val result = installer().ensureBridge()

        assertThat(result).isFalse()
    }

    @Test
    fun `null resource loader skips installation gracefully`() {
        val installer = BridgeInstaller(installDir, pluginVersion) { null }

        val result = installer.ensureBridge()

        assertThat(result).isFalse()
    }

    @Test
    fun `executable permission set on binary after extraction`() {
        if (System.getProperty("os.name").lowercase().contains("win")) return

        val result = installer().ensureBridge()

        assertThat(result).isTrue()
        assertThat(installDir.resolve("bin/stdio-mcp-server").toFile().canExecute()).isTrue()
    }

    @Test
    fun `stale temp directory cleaned up before extraction`() {
        val staleDir = installDir.resolve(".bridge-install-tmp").toFile()
        staleDir.mkdirs()
        File(staleDir, "leftover.txt").writeText("stale")

        val result = installer().ensureBridge()

        assertThat(result).isTrue()
        assertThat(installDir.resolve(".bridge-install-tmp/leftover.txt")).doesNotExist()
        assertThat(installDir.resolve("bin/stdio-mcp-server")).exists()
    }

    @Test
    fun `io failure during extraction leaves existing bin and lib intact`() {
        populateBinAndLib()
        val originalBinContent = installDir.resolve("bin/stdio-mcp-server").toFile().readText()
        val originalLibContent = installDir.resolve("lib/some.jar").toFile().readText()

        val installer = BridgeInstaller(installDir, pluginVersion) { failingInputStream() }

        val result = installer.ensureBridge()

        assertThat(result).isFalse()
        assertThat(installDir.resolve("bin/stdio-mcp-server").toFile().readText()).isEqualTo(originalBinContent)
        assertThat(installDir.resolve("lib/some.jar").toFile().readText()).isEqualTo(originalLibContent)
        assertThat(installDir.resolve(".bridge-install-tmp")).doesNotExist()
    }

    @Test
    fun `version marker contains the expected version string after successful installation`() {
        installer().ensureBridge()

        assertThat(Files.readString(installDir.resolve(BridgeInstaller.VERSION_FILE)).trim())
            .isEqualTo(pluginVersion)
    }

    private fun installer() = BridgeInstaller(installDir, pluginVersion) { syntheticBridgeZip() }

    private fun populateBinAndLib() {
        installDir.resolve("bin").toFile().mkdirs()
        installDir.resolve("bin/stdio-mcp-server").toFile().writeText("existing-binary")
        installDir.resolve("lib").toFile().mkdirs()
        installDir.resolve("lib/some.jar").toFile().writeText("existing-jar")
    }

    private fun failingInputStream(): InputStream {
        val validBytes = ByteArray(10) { 0 }
        return object : InputStream() {
            private var pos = 0
            override fun read(): Int {
                if (pos < validBytes.size) return validBytes[pos++].toInt() and 0xFF
                throw IOException("simulated I/O failure")
            }
        }
    }
}
