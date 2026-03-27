package ca.artemgm.developmentmcp.protocol

import ca.artemgm.developmentmcp.bridge.BridgeInstaller
import ca.artemgm.developmentmcp.bridge.syntheticBridgeZip
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CommandProtocolStartupActivityTest {

    private val tempDir = File("build/private/tmp/CommandProtocolStartupActivityTest").apply { deleteRecursively(); mkdirs() }
    private val project = mock<com.intellij.openapi.project.Project>()

    @Test
    fun `bridge installation runs on a separate daemon thread named bridge-installer`() {
        val threadCapture = CountDownLatch(1)
        var capturedThread: Thread? = null

        val installer = BridgeInstaller(tempDir.toPath(), "test-version") {
            capturedThread = Thread.currentThread()
            threadCapture.countDown()
            syntheticBridgeZip()
        }

        val activity = CommandProtocolStartupActivity(
            bridgeInstaller = installer,
            serviceInitializer = {}
        )

        runBlocking { activity.execute(project) }

        assertThat(threadCapture.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(capturedThread!!.name).isEqualTo("bridge-installer")
        assertThat(capturedThread!!.isDaemon).isTrue()
    }

    @Test
    fun `service starts before bridge installation finishes`() {
        val bridgeStarted = CountDownLatch(1)
        val bridgeRelease = CountDownLatch(1)
        val serviceInitialized = CountDownLatch(1)

        val installer = BridgeInstaller(tempDir.toPath(), "test-version") {
            bridgeStarted.countDown()
            bridgeRelease.await(10, TimeUnit.SECONDS)
            syntheticBridgeZip()
        }

        val activity = CommandProtocolStartupActivity(
            bridgeInstaller = installer,
            serviceInitializer = { serviceInitialized.countDown() }
        )

        runBlocking { activity.execute(project) }

        assertThat(bridgeStarted.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(serviceInitialized.await(10, TimeUnit.SECONDS)).isTrue()
        bridgeRelease.countDown()
    }
}
