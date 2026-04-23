package ca.artemgm.developmentmcp.protocol

data class ClassLookupResult(
    val classes: List<ClassInfo>,
    val totalMatches: Int,
    val truncated: Boolean
)

data class ClassInfo(
    val fqn: String,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>,
    val interfaces: List<String>,
    val superclass: String?
)

data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<ParameterInfo>
)

data class ParameterInfo(
    val name: String,
    val type: String
)

data class FieldInfo(
    val name: String,
    val type: String
)

internal const val DEFAULT_MAX_RESULTS = 20

internal fun formatClassLookupResult(result: ClassLookupResult) = buildString {
    appendLine(formatHeader(result))
    result.classes.forEachIndexed { index, classInfo ->
        if (index > 0) appendLine()
        appendLine(formatClassBlock(classInfo))
    }
}

private fun formatHeader(result: ClassLookupResult): String {
    val count = result.classes.size
    return if (result.truncated)
        "Found $count of ${result.totalMatches} matching classes (results truncated):"
    else
        "Found $count matching classes:"
}

private fun formatClassBlock(info: ClassInfo) = buildString {
    appendLine("=== ${info.fqn} ===")
    appendLine("Superclass: ${info.superclass ?: "(none)"}")
    appendLine("Interfaces: ${formatInterfaces(info.interfaces)}")
    appendLine()
    appendLine("Methods:")
    if (info.methods.isEmpty()) appendLine("  (none)")
    else info.methods.forEach { appendLine("  ${formatMethod(it)}") }
    appendLine()
    appendLine("Fields:")
    if (info.fields.isEmpty()) appendLine("  (none)")
    else info.fields.forEach { appendLine("  ${formatField(it)}") }
}

private fun formatInterfaces(interfaces: List<String>) =
    if (interfaces.isEmpty()) "(none)" else interfaces.joinToString(", ")

private fun formatMethod(method: MethodInfo): String {
    val params = method.parameters.joinToString(", ") { "${it.type} ${it.name}" }
    return "${method.returnType} ${method.name}($params)"
}

private fun formatField(field: FieldInfo) = "${field.type} ${field.name}"
