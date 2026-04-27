package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache

class ClassLookupTool internal constructor(
    private val findClassesByFqn: (String) -> List<ClassInfo>,
    private val findClassesByShortName: (String) -> List<ClassInfo>,
    private val getAllClassNames: () -> Array<String>,
    private val waitForSmartMode: () -> Unit
) {

    constructor(project: Project) : this(
        findClassesByFqn = { fqn ->
            ReadAction.compute<List<ClassInfo>, Exception> {
                JavaPsiFacade.getInstance(project)
                    .findClasses(fqn, GlobalSearchScope.allScope(project))
                    .map(::extractClassInfo)
            }
        },
        findClassesByShortName = { name ->
            ReadAction.compute<List<ClassInfo>, Exception> {
                PsiShortNamesCache.getInstance(project)
                    .getClassesByName(name, GlobalSearchScope.allScope(project))
                    .map(::extractClassInfo)
            }
        },
        getAllClassNames = {
            ReadAction.compute<Array<String>, Exception> {
                PsiShortNamesCache.getInstance(project).allClassNames
            }
        },
        waitForSmartMode = { DumbService.getInstance(project).waitForSmartMode() }
    )

    fun lookup(pattern: String): ClassLookupResult {
        waitForSmartMode()

        val allMatches = when {
            '*' in pattern -> lookupByWildcard(pattern.replace('$', '.'))
            '.' in pattern -> findClassesByFqn(pattern.replace('$', '.'))
            '$' in pattern -> findClassesByShortName(pattern.substringAfterLast('$'))
            else -> findClassesByShortName(pattern)
        }

        val truncated = allMatches.size > DEFAULT_MAX_RESULTS
        val classes = if (truncated) allMatches.take(DEFAULT_MAX_RESULTS) else allMatches
        return ClassLookupResult(classes, totalMatches = allMatches.size, truncated)
    }

    private fun lookupByWildcard(pattern: String): List<ClassInfo> {
        val fqnRegex = globToRegex(pattern)
        val shortNameRegex = globToRegex(pattern.substringAfterLast('.'))
        return getAllClassNames()
            .distinct()
            .filter { shortNameRegex.matches(it) }
            .flatMap { findClassesByShortName(it) }
            .filter { fqnRegex.matches(it.fqn) }
            .distinctBy { it.fqn }
    }
}

private fun extractClassInfo(psiClass: PsiClass) = ClassInfo(
    fqn = psiClass.qualifiedName ?: psiClass.name ?: "<anonymous>",
    methods = psiClass.methods
        .filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
        .map { method ->
            MethodInfo(
                name = method.name,
                returnType = method.returnType?.presentableText ?: "void",
                parameters = method.parameterList.parameters.map { param ->
                    ParameterInfo(name = param.name ?: "arg", type = param.type.presentableText)
                }
            )
        },
    fields = psiClass.fields
        .filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
        .map { FieldInfo(name = it.name, type = it.type.presentableText) },
    interfaces = psiClass.interfaces.mapNotNull { it.qualifiedName },
    superclass = psiClass.superClass
        ?.qualifiedName
        ?.takeUnless { it == "java.lang.Object" },
    sourceFile = resolveSourceFile(psiClass)
)

private fun resolveSourceFile(psiClass: PsiClass): String? {
    val virtualFile = psiClass.containingFile?.virtualFile ?: return null
    val path = virtualFile.path
    // jar:// URLs look like "/path/to/lib.jar!/com/example/Foo.class"
    val jarSeparator = path.indexOf("!/")
    if (jarSeparator >= 0) return path.substring(0, jarSeparator)
    val project = psiClass.project
    val basePath = project.basePath ?: return path
    return path.removePrefix("$basePath/")
}

private fun globToRegex(pattern: String): Regex {
    val escaped = buildString {
        append('^')
        for (ch in pattern) {
            when (ch) {
                '*' -> append(".*")
                '.' -> append("\\.")
                else -> append(Regex.escape(ch.toString()))
            }
        }
        append('$')
    }
    return Regex(escaped)
}
