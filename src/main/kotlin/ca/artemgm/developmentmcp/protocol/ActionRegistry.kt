package ca.artemgm.developmentmcp.protocol

import java.util.concurrent.ConcurrentHashMap

class ActionRegistry {

    fun register(tool: ToolRegistration): Boolean {
        val previous = registrationByName.putIfAbsent(tool.name, tool)
        return previous == null
    }

    fun lookup(toolName: String): ToolRegistration? = registrationByName[toolName]

    fun allTools(): List<ToolRegistration> = registrationByName.values.toList()

    private val registrationByName = ConcurrentHashMap<String, ToolRegistration>()
}
