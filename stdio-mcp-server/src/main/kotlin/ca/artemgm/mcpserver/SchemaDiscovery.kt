package ca.artemgm.mcpserver

import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult
import io.modelcontextprotocol.spec.McpSchema.Tool
import mu.KotlinLogging
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

internal object SchemaDiscovery {

    fun loadTools(commandDir: Path): List<Tool> {
        val schemaPath = commandDir.resolve("schema.json")
        val json = readSchemaFile(schemaPath)
        return deserializeTools(json, schemaPath)
    }
}

private val log = KotlinLogging.logger {}

private fun readSchemaFile(schemaPath: Path): String =
    try {
        Files.readString(schemaPath)
    } catch (e: NoSuchFileException) {
        log.error { "Schema file not found: $schemaPath" }
        throw IllegalStateException("Schema file not found: $schemaPath", e)
    }

private fun deserializeTools(json: String, schemaPath: Path): List<Tool> =
    try {
        McpJsonDefaults.getMapper().readValue(json, ListToolsResult::class.java).tools()
    } catch (e: Exception) {
        log.error { "Failed to parse schema file $schemaPath: ${e.message}" }
        throw IllegalStateException("Failed to parse schema file $schemaPath", e)
    }
