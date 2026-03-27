package ca.artemgm.developmentmcp.protocol

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageSuiteListener
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.LineCoverage
import com.intellij.rt.coverage.data.ProjectData
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
import org.jetbrains.plugins.gradle.util.GradleConstants
import org.jetbrains.plugins.gradle.util.GradleUtil
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RunTestTool internal constructor(
    private val configCreator: (ConfigParams) -> String,
    private val executionLauncher: (String, String) -> ExecutionResult,
    private val moduleResolver: (String) -> Module
) {

    constructor(project: Project) : this(
        configCreator = { params ->
            val gradleData = GradleUtil.findGradleModuleData(params.module)
            if (gradleData != null) createGradleConfig(project, params, gradleData)
            else createJUnitConfig(project, params)
        },
        executionLauncher = { configName, packageName -> launchWithCoverage(project, configName, packageName) },
        moduleResolver = { target -> resolveModule(project, target) }
    )

    @ToolDefinition(name = TOOL_NAME, description = "Runs JUnit tests in the IDE")
    fun handle(
        @Param(description = "Test scope: package, class, or method") scope: String,
        @Param(description = "Target: package name, class FQN, or class#method") target: String
    ): CallToolResult {
        val testScope = SCOPE_BY_NAME[scope]
            ?: return errorResult("Invalid scope '$scope'. Must be one of: ${SCOPE_BY_NAME.keys.joinToString()}")

        if (target.isEmpty()) return errorResult("Target must not be empty")

        if (testScope == TestScope.METHOD && '#' !in target)
            return errorResult("Method scope requires target format 'com.example.MyTest#myMethod'")

        return try {
            val classOrPackageName = if (testScope == TestScope.METHOD) target.substringBefore('#') else target
            val module = moduleResolver(classOrPackageName)
            val configName = configCreator(ConfigParams(testScope, target, module))
            val packageName = resolvePackageName(testScope, target)
            val result = executionLauncher(configName, packageName)
            val response = if (result.coverage != null)
                "${result.testOutput}\n\n${formatCoverage(result.coverage)}"
            else
                result.testOutput
            CallToolResult.builder()
                .addContent(TextContent(response))
                .build()
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Unknown error")
        } catch (e: Exception) {
            errorResult("Failed to launch configuration: ${e.message}")
        }
    }

    fun registration() = ReflectiveToolAdapter(this, ::handle).toRegistration()

    data class ConfigParams(
        val scope: TestScope,
        val target: String,
        val module: Module
    )

    enum class TestScope {
        PACKAGE, CLASS, METHOD
    }
}

data class ExecutionResult(
    val testOutput: String,
    val coverage: PackageCoverage?
)

data class PackageCoverage(
    val packageName: String,
    val totalLines: Int,
    val coveredLines: Int,
    val totalBranches: Int,
    val coveredBranches: Int,
    val classCoverages: List<ClassCoverage>
)

data class ClassCoverage(
    val className: String,
    val totalLines: Int,
    val coveredLines: Int,
    val totalBranches: Int,
    val coveredBranches: Int
)

internal fun extractTestResults(console: Any?, configName: String, exitCode: Int, processOutput: String): String {
    if (console == null) return fallbackMessage(configName, exitCode, processOutput)
    val root = tryGetTestRoot(console) ?: return fallbackMessage(configName, exitCode, processOutput)
    return formatTestResults(configName, root, exitCode)
}

private fun resolvePackageName(scope: RunTestTool.TestScope, target: String): String = when (scope) {
    RunTestTool.TestScope.PACKAGE -> target
    RunTestTool.TestScope.CLASS -> target.substringBeforeLast('.')
    RunTestTool.TestScope.METHOD -> target.substringBefore('#').substringBeforeLast('.')
}

private fun formatCoverage(coverage: PackageCoverage): String {
    val lineRate = if (coverage.totalLines > 0) coverage.coveredLines.toDouble() / coverage.totalLines else 0.0
    val branchRate = if (coverage.totalBranches > 0) coverage.coveredBranches.toDouble() / coverage.totalBranches else 0.0

    val sb = StringBuilder()
    sb.appendLine("Coverage for package '${coverage.packageName}':")
    sb.appendLine("  Lines:    ${coverage.coveredLines}/${coverage.totalLines} (${"%.1f".format(lineRate * 100)}%)")
    sb.appendLine("  Branches: ${coverage.coveredBranches}/${coverage.totalBranches} (${"%.1f".format(branchRate * 100)}%)")

    if (coverage.classCoverages.isNotEmpty()) {
        sb.appendLine("  Per class:")
        for (cls in coverage.classCoverages) {
            val clsLineRate = if (cls.totalLines > 0) cls.coveredLines.toDouble() / cls.totalLines else 0.0
            sb.appendLine("    ${cls.className}: ${cls.coveredLines}/${cls.totalLines} lines (${"%.1f".format(clsLineRate * 100)}%)")
        }
    }

    return sb.toString().trimEnd()
}

private fun launchWithCoverage(project: Project, configName: String, packageName: String): ExecutionResult {
    val runManager = RunManager.getInstance(project)
    val settings = runManager.findConfigurationByName(configName)
        ?: throw IllegalStateException("Configuration '$configName' not found after creation")

    val coverageExecutor = com.intellij.execution.ExecutorRegistry.getInstance()
        .getExecutorById(CoverageExecutor.EXECUTOR_ID)
        ?: throw IllegalStateException("Coverage executor not found. Is com.intellij.coverage plugin enabled?")

    val runConfig = settings.configuration as? RunConfigurationBase<*>
        ?: throw IllegalStateException("Configuration is not a RunConfigurationBase")
    CoverageEnabledConfiguration.getOrCreate(runConfig)

    val testResultFuture = CompletableFuture<String>()
    val coverageFuture = CompletableFuture<PackageCoverage?>()

    val coverageDisposable = Disposer.newDisposable("coverage-listener-$configName")
    Disposer.register(project as com.intellij.openapi.Disposable, coverageDisposable)

    CoverageDataManager.getInstance(project).addSuiteListener(object : CoverageSuiteListener {
        override fun coverageDataCalculated(suitesBundle: CoverageSuitesBundle) {
            try {
                val projectData = suitesBundle.getCoverageData()
                if (projectData == null) {
                    coverageFuture.complete(null)
                    return
                }
                coverageFuture.complete(extractPackageCoverage(projectData, packageName))
            } catch (e: Exception) {
                coverageFuture.complete(null)
            } finally {
                Disposer.dispose(coverageDisposable)
            }
        }
    }, coverageDisposable)

    val connection = project.messageBus.connect()
    val processOutput = StringBuilder()

    connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
        override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            if (env.runProfile.name != configName) return

            val capturedConsole = env.contentToReuse?.executionConsole

            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    processOutput.append(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    try {
                        val console = capturedConsole ?: findConsole(project, configName)
                        val summary = extractTestResults(console, configName, event.exitCode, processOutput.toString())
                        testResultFuture.complete(summary)
                    } catch (e: Exception) {
                        testResultFuture.complete(fallbackMessage(configName, event.exitCode, processOutput.toString()))
                    } finally {
                        connection.disconnect()
                    }
                }
            })
        }
    })

    DumbService.getInstance(project).smartInvokeLater {
        try {
            ProgramRunnerUtil.executeConfiguration(settings, coverageExecutor)
        } catch (e: Exception) {
            connection.disconnect()
            testResultFuture.completeExceptionally(e)
            coverageFuture.complete(null)
        }
    }

    val testOutput = try {
        testResultFuture.get(TEST_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    } catch (e: Exception) {
        disposeSafely(coverageDisposable)
        throw e
    }

    val coverage = try {
        coverageFuture.get(COVERAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (_: Exception) {
        disposeSafely(coverageDisposable)
        null
    }

    return ExecutionResult(testOutput, coverage)
}

private fun extractPackageCoverage(projectData: ProjectData, packageName: String): PackageCoverage {
    val packagePrefix = "$packageName."
    val matchingClasses = projectData.classes.filter { (className, _) ->
        (className.startsWith(packagePrefix) || className == packageName) && !isTestClass(className, packagePrefix)
    }

    val classCoverages = matchingClasses.map { (className, classData) ->
        extractClassCoverage(className, classData)
    }

    return PackageCoverage(
        packageName = packageName,
        totalLines = classCoverages.sumOf { it.totalLines },
        coveredLines = classCoverages.sumOf { it.coveredLines },
        totalBranches = classCoverages.sumOf { it.totalBranches },
        coveredBranches = classCoverages.sumOf { it.coveredBranches },
        classCoverages = classCoverages
    )
}

private fun extractClassCoverage(className: String, classData: ClassData): ClassCoverage {
    val lines = classData.lines ?: return ClassCoverage(className, 0, 0, 0, 0)
    var totalLines = 0
    var coveredLines = 0
    var totalBranches = 0
    var coveredBranches = 0

    for (line in lines) {
        val ld = line as? LineData ?: continue
        totalLines++
        if (ld.status.toInt() != LineCoverage.NONE.toInt()) coveredLines++

        for (j in 0 until ld.jumpsCount()) {
            val jump = ld.getJumpData(j) ?: continue
            totalBranches += 2
            if (jump.trueHits > 0) coveredBranches++
            if (jump.falseHits > 0) coveredBranches++
        }
        for (s in 0 until ld.switchesCount()) {
            val sw = ld.getSwitchData(s) ?: continue
            totalBranches += sw.hits.size + 1
            for (h in sw.hits) { if (h > 0) coveredBranches++ }
            if (sw.defaultHits > 0) coveredBranches++
        }
    }

    return ClassCoverage(className, totalLines, coveredLines, totalBranches, coveredBranches)
}

// RunContentManager is the only reliable way to get the execution console for Gradle runs.
// env.contentToReuse (used in the JUnit path) is null for Gradle because there's no previous
// run content to reuse — Gradle creates a fresh descriptor each time.
private fun findConsole(project: Project, configName: String): Any? =
    com.intellij.execution.ui.RunContentManager.getInstance(project)
        .allDescriptors
        .firstOrNull { it.displayName == configName }
        ?.executionConsole

@Suppress("UNCHECKED_CAST")
private fun tryGetTestRoot(console: Any): AbstractTestProxy? = try {
    val target = tryUnwrapConsole(console) ?: console
    val resultsViewer = target.javaClass.getMethod("getResultsViewer").invoke(target)
    resultsViewer.javaClass.getMethod("getRoot").invoke(resultsViewer) as? AbstractTestProxy
} catch (_: Exception) {
    null
}

// Gradle wraps the real test console (SMTRunnerConsoleView) inside an anonymous
// ExternalSystemRunnableState inner class. That wrapper doesn't expose getResultsViewer() directly,
// so we call getConsoleView() to unwrap it first. Without this, tryGetTestRoot returns null and we
// fall back to the raw process output with no structured test results.
// Discovered empirically — there's no integration test for this, only manual MCP invocation.
private fun tryUnwrapConsole(console: Any): Any? = try {
    console.javaClass.getMethod("getConsoleView").invoke(console)
} catch (_: Exception) {
    null
}

private fun formatTestResults(configName: String, root: AbstractTestProxy, exitCode: Int): String {
    val allTests = root.allTests
    val passed = allTests.count { it.isPassed }
    val failed = allTests.count { it.isDefect }
    val ignored = allTests.count { !it.isPassed && !it.isDefect }

    val sb = StringBuilder()
    sb.appendLine("Test run '$configName' completed (exit code $exitCode)")
    sb.appendLine("Total: ${allTests.size}, Passed: $passed, Failed: $failed, Ignored: $ignored")

    val failures = allTests.filter { it.isDefect }
    if (failures.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine("Failures:")
        for (test in failures) {
            sb.appendLine("  - ${test.name}")
            val errorMessage = test.errorMessage
            if (errorMessage != null) sb.appendLine("    $errorMessage")
            val stacktrace = test.stacktrace
            if (stacktrace != null) {
                val relevantFrames = extractRelevantFrames(stacktrace)
                if (relevantFrames.isNotEmpty()) {
                    for (frame in relevantFrames) sb.appendLine("    $frame")
                }
            }
        }
    }

    return sb.toString().trimEnd()
}

private fun fallbackMessage(configName: String, exitCode: Int, processOutput: String): String {
    val sb = StringBuilder("Configuration '$configName' finished with exit code $exitCode")
    val trimmed = processOutput.trim()
    if (trimmed.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine()
        sb.append(trimmed)
    }
    return sb.toString()
}

private fun resolveModule(project: Project, target: String): Module {
    return com.intellij.openapi.application.ReadAction.compute<Module, Exception> {
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)

        val psiElement = facade.findClass(target, scope)
            ?: facade.findPackage(target)
            ?: throw IllegalArgumentException("Could not find '$target' in any module")

        ModuleUtilCore.findModuleForPsiElement(psiElement)
            ?: throw IllegalArgumentException("Found '$target' but could not determine its module")
    }
}

private fun createJUnitConfig(project: Project, params: RunTestTool.ConfigParams): String {
    val runManager = RunManager.getInstance(project)
    val junitType = com.intellij.execution.junit.JUnitConfigurationType.getInstance()
    val factory = junitType.configurationFactories[0]
    val configName = "RunTest-${params.target.substringAfterLast('.')}"
    val settings = runManager.createConfiguration(configName, factory)
    val config = settings.configuration as com.intellij.execution.junit.JUnitConfiguration

    when (params.scope) {
        RunTestTool.TestScope.PACKAGE -> {
            config.persistentData.TEST_OBJECT = com.intellij.execution.junit.JUnitConfiguration.TEST_PACKAGE
            config.persistentData.PACKAGE_NAME = params.target
        }
        RunTestTool.TestScope.CLASS -> {
            config.persistentData.TEST_OBJECT = com.intellij.execution.junit.JUnitConfiguration.TEST_CLASS
            config.persistentData.MAIN_CLASS_NAME = params.target
        }
        RunTestTool.TestScope.METHOD -> {
            val (className, methodName) = params.target.split("#", limit = 2)
            config.persistentData.TEST_OBJECT = com.intellij.execution.junit.JUnitConfiguration.TEST_METHOD
            config.persistentData.MAIN_CLASS_NAME = className
            config.persistentData.METHOD_NAME = methodName
        }
    }

    config.setModule(params.module)
    settings.isTemporary = true
    runManager.addConfiguration(settings)
    return configName
}

private fun errorResult(message: String): CallToolResult = CallToolResult.builder()
    .addContent(TextContent(message))
    .isError(true)
    .build()

private fun disposeSafely(disposable: com.intellij.openapi.Disposable) {
    try { Disposer.dispose(disposable) } catch (_: Exception) { }
}

private fun createGradleConfig(
    project: Project,
    params: RunTestTool.ConfigParams,
    gradleModuleData: DataNode<ModuleData>
): String {
    val runManager = RunManager.getInstance(project)
    val gradleType = GradleExternalTaskConfigurationType.getInstance()
    val factory = gradleType.factory
    val configName = "RunTest-${params.target.substringAfterLast('.')}"
    val settings = runManager.createConfiguration(configName, factory)
    val config = settings.configuration as GradleRunConfiguration

    val taskSettings = config.settings
    taskSettings.externalSystemIdString = GradleConstants.SYSTEM_ID.id
    taskSettings.externalProjectPath = gradleModuleData.data.linkedExternalProjectPath
    taskSettings.taskNames = listOf(gradleTestTaskName(gradleModuleData.data.id))
    taskSettings.scriptParameters = buildGradleTestFilter(params.scope, params.target)

    settings.isTemporary = true
    runManager.addConfiguration(settings)
    return configName
}

private const val TOOL_NAME = "run_test"
private const val TEST_TIMEOUT_MINUTES = 10L
private const val COVERAGE_TIMEOUT_SECONDS = 60L
private const val GRADLE_TEST_TASK = "test"

// Builds a fully qualified Gradle task path like ":protocol-shared:test".
// The leading colon is required — without it, Gradle runs the task across ALL subprojects,
// causing "No tests found" failures in subprojects that don't contain the target class.
// Subproject ids start with ":" (e.g., ":protocol-shared"), so appending ":test" works directly.
// The root project id is the project name (e.g., "DevelopmentMcp"), so we fall back to ":test".
internal fun gradleTestTaskName(gradleProjectId: String) =
    if (gradleProjectId.startsWith(":")) "$gradleProjectId:$GRADLE_TEST_TASK"
    else ":$GRADLE_TEST_TASK"

private val SCOPE_BY_NAME = RunTestTool.TestScope.entries.associateBy { it.name.lowercase() }

private fun buildGradleTestFilter(scope: RunTestTool.TestScope, target: String) = when (scope) {
    RunTestTool.TestScope.PACKAGE -> "--tests \"$target.*\""
    RunTestTool.TestScope.CLASS -> "--tests \"$target\""
    RunTestTool.TestScope.METHOD -> {
        val (className, methodName) = target.split("#", limit = 2)
        "--tests \"$className.${methodName.substringBefore('(')}\""
    }
}

private fun isTestClass(fqClassName: String, packagePrefix: String): Boolean {
    val topLevelSimpleName = fqClassName.removePrefix(packagePrefix).substringBefore('$')
    return TEST_CLASS_SUFFIXES.any { topLevelSimpleName.endsWith(it) }
}

private val TEST_CLASS_SUFFIXES = listOf("Test", "Tests", "TestCase", "IT")

private fun extractRelevantFrames(stacktrace: String): List<String> {
    val lines = stacktrace.lines()
    val causeLine = lines.firstOrNull { it.trimStart().startsWith("Caused by:") }
    val frames = lines.filter { line ->
        val trimmed = line.trimStart()
        trimmed.startsWith("at ") && FRAMEWORK_PACKAGE_PREFIXES.none { prefix -> trimmed.startsWith("at $prefix") }
    }
    val result = mutableListOf<String>()
    if (causeLine != null) result.add(causeLine.trim())
    result.addAll(frames.take(MAX_STACKTRACE_FRAMES))
    return result
}

private const val MAX_STACKTRACE_FRAMES = 5

private val FRAMEWORK_PACKAGE_PREFIXES = listOf(
    "org.junit.",
    "junit.",
    "java.lang.reflect.",
    "sun.reflect.",
    "jdk.internal.",
    "com.intellij.",
    "org.gradle.",
    "worker.org.gradle."
)
