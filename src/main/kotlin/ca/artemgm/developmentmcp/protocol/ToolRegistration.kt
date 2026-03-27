package ca.artemgm.developmentmcp.protocol

import io.modelcontextprotocol.spec.McpSchema.CallToolResult

class ToolRegistration(
    val name: String,
    val description: String,
    val inputSchema: String,
    val handler: (Map<String, Any?>) -> CallToolResult
)
