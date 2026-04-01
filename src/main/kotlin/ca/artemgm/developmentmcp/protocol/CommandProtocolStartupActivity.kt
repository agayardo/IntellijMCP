package ca.artemgm.developmentmcp.protocol

import ca.artemgm.developmentmcp.bridge.BridgeInstaller
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CommandProtocolStartupActivity internal constructor(
    private val bridgeInstaller: BridgeInstaller,
    private val serviceInitializer: () -> Unit
) : ProjectActivity {

    constructor() : this(
        bridgeInstaller = BridgeInstaller(),
        serviceInitializer = { CommandProtocolService.getInstance().initialize() }
    )

    // ProjectActivity requires a suspend function — this is one of the rare cases where it's mandated by the IntelliJ API.
    override suspend fun execute(project: Project) {
        Thread {
            bridgeInstaller.ensureBridge()
        }.apply {
            isDaemon = true
            name = "bridge-installer"
            start()
        }
        serviceInitializer()
    }
}
