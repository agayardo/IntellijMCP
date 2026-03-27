package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages

class HelloWorldTool internal constructor(private val showDialog: (String) -> Unit) {

    constructor() : this({ message ->
        ApplicationManager.getApplication().invokeAndWait {
            Messages.showInfoMessage(message, "Hello")
        }
    })

    @ToolDefinition(name = "hello_world", description = "Returns a greeting message")
    fun handle(@Param(description = "Name of the person to greet") name: String?): String {
        val greeting = greeting(name)
        showDialog(greeting)
        return greeting
    }

    fun registration() = ReflectiveToolAdapter(this, ::handle).toRegistration()

    private fun greeting(name: String?) = "Hello, ${name ?: "World"}!"
}
