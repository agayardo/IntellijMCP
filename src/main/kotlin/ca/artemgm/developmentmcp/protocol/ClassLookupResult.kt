package ca.artemgm.developmentmcp.protocol

data class ClassLookupResult(
    val classes: List<ClassInfo>,
    val totalMatches: Int,
    val truncated: Boolean,
    val moduleName: String? = null
)

data class ClassInfo(
    val fqn: String,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>,
    val interfaces: List<String>,
    val superclass: String?,
    val sourceFile: String? = null
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

internal fun formatClassLookupResult(results: List<ClassLookupResult>) = buildString {
    val nonEmpty = results.filter { it.classes.isNotEmpty() }
    if (allResultsFromSameClasses(nonEmpty)) {
        val merged = mergeResults(nonEmpty)
        appendLine(formatHeader(merged))
        merged.classes.forEachIndexed { index, classInfo ->
            if (index > 0) appendLine()
            appendLine(formatClassBlock(classInfo))
        }
    } else {
        for (result in nonEmpty) {
            if (isNotEmpty()) appendLine()
            appendLine(formatHeader(result))
            result.classes.forEachIndexed { index, classInfo ->
                if (index > 0) appendLine()
                appendLine(formatClassBlock(classInfo))
            }
        }
    }
}

private fun allResultsFromSameClasses(results: List<ClassLookupResult>): Boolean {
    if (results.size <= 1) return true
    val fqns = results.first().classes.map { it.fqn }.toSet()
    return results.all { it.classes.map { c -> c.fqn }.toSet() == fqns }
}

private fun mergeResults(results: List<ClassLookupResult>): ClassLookupResult {
    if (results.size == 1) return results.single().copy(moduleName = null)
    return ClassLookupResult(
        results.first().classes,
        totalMatches = results.maxOf { it.totalMatches },
        truncated = results.any { it.truncated },
        moduleName = null
    )
}

private fun formatHeader(result: ClassLookupResult): String {
    val count = result.classes.size
    val moduleLabel = result.moduleName?.let { " in module '$it'" } ?: ""
    return if (result.truncated)
        "Found $count of ${result.totalMatches} matching classes$moduleLabel (results truncated):"
    else
        "Found $count matching classes$moduleLabel:"
}

private fun formatClassBlock(info: ClassInfo) = buildString {
    appendLine("=== ${info.fqn} ===")
    if (info.sourceFile != null) appendLine("Source: ${info.sourceFile}")
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
