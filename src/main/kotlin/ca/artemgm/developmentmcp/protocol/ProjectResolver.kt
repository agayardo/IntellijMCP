package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

class ProjectResolver internal constructor(
    private val openProjects: () -> Array<Project>,
    private val findModuleByName: (Project, String) -> Module?,
    private val resolveTargetInProject: (Project, String) -> TargetMatch,
    private val listModuleNames: (Project) -> List<String>
) {

    constructor() : this(
        openProjects = { ProjectManager.getInstance().openProjects },
        findModuleByName = { project, name -> ModuleManager.getInstance(project).findModuleByName(name) },
        resolveTargetInProject = ::resolveViaPsi,
        listModuleNames = { project -> ModuleManager.getInstance(project).modules.map { it.name } }
    )

    fun resolve(target: String, moduleName: String?): ResolvedContext {
        val projects = openProjects()
        if (moduleName != null) return resolveByModuleName(projects, moduleName)
        return resolveByTarget(projects, target)
    }

    private fun resolveByModuleName(projects: Array<Project>, moduleName: String): ResolvedContext {
        for (project in projects) {
            val module = findModuleByName(project, moduleName)
            if (module != null) return ResolvedContext(project, module)
        }
        throw IllegalArgumentException(
            "Module '$moduleName' not found. Available modules: ${allModuleNames(projects)}"
        )
    }

    private fun resolveByTarget(projects: Array<Project>, target: String): ResolvedContext {
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
            throw IllegalArgumentException(
                "Could not find '$target' in any open project"
            )
        }
        if (matches.size > 1) throw IllegalArgumentException(
            "'$target' found in multiple projects. Specify moduleName to disambiguate. " +
                "Available modules: ${allModuleNames(projects)}"
        )
        return matches.single()
    }

    private fun allModuleNames(projects: Array<Project>) = projects
        .flatMap { listModuleNames(it) }
        .sorted()
        .joinToString()
}

data class ResolvedContext(val project: Project, val module: Module)

sealed interface TargetMatch {
    data class Resolved(val context: ResolvedContext) : TargetMatch
    data object FoundButModuleUnknown : TargetMatch
    data object NotFound : TargetMatch
}

private fun resolveViaPsi(project: Project, target: String): TargetMatch =
    ReadAction.compute<TargetMatch, Exception> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        val psiElement = facade.findClass(target, scope)
            ?: facade.findPackage(target)
            ?: return@compute TargetMatch.NotFound

        val module = ModuleUtilCore.findModuleForPsiElement(psiElement)
            ?: return@compute TargetMatch.FoundButModuleUnknown

        TargetMatch.Resolved(ResolvedContext(project, module))
    }
