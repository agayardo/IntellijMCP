package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager

class ProjectResolver internal constructor(
    private val openProjects: () -> Array<Project>,
    private val findModuleByName: (Project, String) -> Module?,
    private val resolveTargetInProject: (Project, String) -> TargetMatch,
    private val listModuleNames: (Project) -> List<String>,
    private val refreshAndWait: () -> Unit = {}
) {

    constructor() : this(
        openProjects = { ProjectManager.getInstance().openProjects },
        findModuleByName = { project, name -> ModuleManager.getInstance(project).findModuleByName(name) },
        resolveTargetInProject = ::resolveViaSourceRoots,
        listModuleNames = { project -> ModuleManager.getInstance(project).modules.map { it.name } },
        refreshAndWait = ::refreshVfsAndWaitForSmartMode
    )

    fun resolve(target: String, moduleName: String?): ResolvedContext {
        val projects = openProjects()
        if (moduleName != null) return resolveByModuleName(projects, moduleName)
        return resolveByTarget(projects, target.substringBefore('$'))
    }

    private fun resolveByModuleName(projects: Array<Project>, moduleName: String): ResolvedContext {
        val (project, module) = findProjectAndModule(projects, moduleName)
        return ResolvedContext(project, module)
    }

    private fun resolveByTarget(projects: Array<Project>, target: String): ResolvedContext {
        val result = attemptResolve(projects, target)
        if (result != null) return result

        refreshAndWait()

        return attemptResolve(projects, target)
            ?: throw IllegalArgumentException("Could not find '$target' in any open project")
    }

    private fun attemptResolve(projects: Array<Project>, target: String): ResolvedContext? {
        val matches = mutableListOf<ResolvedContext>()
        var foundButUnresolvable = false

        for (project in projects) {
            when (val result = resolveTargetInProject(project, target)) {
                is TargetMatch.Resolved -> matches.add(result.context)
                is TargetMatch.FoundButModuleUnknown -> foundButUnresolvable = true
                is TargetMatch.NotFound -> {}
            }
        }

        if (matches.isEmpty()) {
            if (foundButUnresolvable) throw IllegalArgumentException(
                "Found '$target' but could not determine its module. " +
                    "Specify moduleName to disambiguate. Available modules: ${allModuleNames(projects)}"
            )
            return null
        }
        if (matches.size > 1) throw IllegalArgumentException(
            "'$target' found in multiple projects. Specify moduleName to disambiguate. " +
                "Available modules: ${allModuleNames(projects)}"
        )
        return matches.single()
    }

    fun resolveForLookup(moduleName: String?): List<ResolvedProject> {
        val projects = openProjects()
        if (moduleName != null) return listOf(ResolvedProject(findProjectAndModule(projects, moduleName).first))
        require(projects.isNotEmpty()) { "No open projects" }
        return projects.map { ResolvedProject(it) }
    }

    private fun findProjectAndModule(projects: Array<Project>, moduleName: String): Pair<Project, Module> {
        for (project in projects) {
            val module = findModuleByName(project, moduleName)
            if (module != null) return project to module
        }
        throw IllegalArgumentException(
            "Module '$moduleName' not found. Available modules: ${allModuleNames(projects)}"
        )
    }

    private fun allModuleNames(projects: Array<Project>) = projects
        .flatMap { listModuleNames(it) }
        .sorted()
        .joinToString()
}

data class ResolvedProject(val project: Project)

data class ResolvedContext(val project: Project, val module: Module)

sealed interface TargetMatch {
    data class Resolved(val context: ResolvedContext) : TargetMatch
    data object FoundButModuleUnknown : TargetMatch
    data object NotFound : TargetMatch
}

private fun resolveViaSourceRoots(project: Project, target: String): TargetMatch {
    val asClass = resolveClass(project, target)
    if (asClass != null) return TargetMatch.Resolved(asClass)

    val asPackage = resolvePackage(project, target)
    if (asPackage != null) return TargetMatch.Resolved(asPackage)

    return TargetMatch.NotFound
}

private fun resolveClass(project: Project, classFqn: String): ResolvedContext? {
    val packagePath = classFqn.substringBeforeLast('.').replace('.', '/')
    val simpleName = classFqn.substringAfterLast('.')

    return findInSourceRoots(project, packagePath) { packageDir ->
        packageDir.children.any { child ->
            !child.isDirectory
                && SOURCE_EXTENSIONS.any { child.name.endsWith(it) }
                && try { containsClassOrObject(String(child.contentsToByteArray(), child.charset), simpleName) }
                  catch (_: Exception) { false }
        }
    }
}

private fun resolvePackage(project: Project, packageFqn: String): ResolvedContext? {
    val packagePath = packageFqn.replace('.', '/')
    return findInSourceRoots(project, packagePath) { dir ->
        dir.children.any { SOURCE_EXTENSIONS.any { ext -> it.name.endsWith(ext) } }
    }
}

private fun findInSourceRoots(
    project: Project,
    relativePath: String,
    predicate: (com.intellij.openapi.vfs.VirtualFile) -> Boolean
): ResolvedContext? {
    for (module in ModuleManager.getInstance(project).modules) {
        for (sourceRoot in ModuleRootManager.getInstance(module).sourceRoots) {
            val dir = sourceRoot.findFileByRelativePath(relativePath) ?: continue
            if (dir.isDirectory && predicate(dir)) return ResolvedContext(project, module)
        }
    }
    return null
}

private fun containsClassOrObject(content: String, name: String): Boolean {
    val pattern = "(?:class|object|interface|enum)\\s+$name\\b"
    return Regex(pattern).containsMatchIn(content)
}

private val SOURCE_EXTENSIONS = listOf(".kt", ".java")

internal fun refreshVfsAndWaitForSmartMode() {
    com.intellij.openapi.vfs.VirtualFileManager.getInstance().refreshWithoutFileWatcher(false)
    for (project in ProjectManager.getInstance().openProjects) {
        com.intellij.openapi.project.DumbService.getInstance(project).waitForSmartMode()
    }
}
