package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class CommandProtocolStartupActivity : ProjectActivity {
    // ProjectActivity requires a suspend function — this is one of the rare cases where it's mandated by the IntelliJ API.
    override suspend fun execute(project: Project) {
        CommandProtocolService.getInstance(project).initialize()
    }
}
