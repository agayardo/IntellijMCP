package ca.artemgm.developmentmcp.protocol

import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
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
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.module.Module
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RunTestTool internal constructor(
    private val configCreator: (ConfigParams) -> String,
    private val executionLauncher: (String, Set<String>) -> ExecutionResult,
    private val filePathResolver: (String) -> String?,
    private val classesInFile: (String) -> Set<String>,
    private val sourceReader: (String) -> List<String>?,
    private val module: Module
) {

    constructor(project: Project, module: Module) : this(
        configCreator = { params ->
            val gradleData = GradleUtil.findGradleModuleData(params.module)
            if (gradleData != null) createGradleConfig(project, params, gradleData)
            else createJUnitConfig(project, params)
        },
        executionLauncher = { configName, packageNames ->
            launchWithCoverage(project, configName, packageNames)
        },
        filePathResolver = { className -> resolveFilePath(project, className) },
        classesInFile = { filePath -> resolveClassesInFile(project, filePath) },
        sourceReader = { filePath -> readSourceLines(project, filePath) },
        module = module
    )

    fun handle(
        scope: TestScope,
        targets: List<String>,
        coverageFor: List<String>? = null
    ): CallToolResult {
        return try {
            val configName = configCreator(ConfigParams(scope, targets, module))
            val packageNames = targets.map { resolvePackageName(scope, it) }.toSet()
            val result = executionLauncher(configName, packageNames)
            val response = buildString {
                append(result.testOutput)
                if (result.coverage != null) {
                    val fileCoverages = groupByFile(result.coverage.classCoverages, filePathResolver)
                    if (coverageFor != null) {
                        val loadedClassNames = result.coverage.classCoverages.map { it.className }.toSet()
                        val corrected = correctForUnloadedClasses(
                            fileCoverages, coverageFor, loadedClassNames, classesInFile, sourceReader
                        )
                        append("\n\n")
                        append(formatCoverage(result.coverage, corrected))
                        val uncovered = formatUncoveredLines(corrected, coverageFor, sourceReader)
                        if (uncovered.isNotEmpty()) {
                            append("\n\n")
                            append(uncovered)
                        }
                    } else {
                        append("\n\n")
                        append(formatCoverage(result.coverage, fileCoverages))
                    }
                }
            }
            CallToolResult.builder()
                .addContent(TextContent(response))
                .isError(result.failed)
                .build()
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "Unknown error")
        } catch (e: Exception) {
            errorResult("Failed to launch configuration: ${e.exceptionSummary()}")
        }
    }

    data class ConfigParams(
        val scope: TestScope,
        val targets: List<String>,
        val module: Module
    )

    enum class TestScope {
        PACKAGE, CLASS, METHOD
    }
}

data class ExecutionResult(
    val testOutput: String,
    val failed: Boolean,
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
    val coveredBranches: Int,
    val coveredLineNumbers: List<Int> = emptyList(),
    val uncoveredLineNumbers: List<Int> = emptyList()
)

data class FileCoverage(
    val filePath: String,
    val totalLines: Int,
    val coveredLines: Int,
    val totalBranches: Int,
    val coveredBranches: Int,
    val coveredLineNumbers: List<Int>,
    val uncoveredLineNumbers: List<Int>
)

internal data class TestRunSummary(val output: String, val failed: Boolean)

internal fun extractTestResults(console: Any?, configName: String, exitCode: Int, processOutput: String): TestRunSummary {
    if (console == null) return TestRunSummary(fallbackMessage(configName, exitCode, processOutput), exitCode != 0)
    val root = tryGetTestRoot(console)
        ?: return TestRunSummary(fallbackMessage(configName, exitCode, processOutput), exitCode != 0)
    return formatTestResults(configName, root, exitCode)
}

private fun resolvePackageName(scope: RunTestTool.TestScope, target: String): String = when (scope) {
    RunTestTool.TestScope.PACKAGE -> target
    RunTestTool.TestScope.CLASS -> target.substringBeforeLast('.')
    RunTestTool.TestScope.METHOD -> target.substringBefore('#').substringBeforeLast('.')
}

private fun formatCoverage(coverage: PackageCoverage, fileCoverages: List<FileCoverage>): String {
    val lineRate = if (coverage.totalLines > 0) coverage.coveredLines.toDouble() / coverage.totalLines else 0.0
    val branchRate = if (coverage.totalBranches > 0) coverage.coveredBranches.toDouble() / coverage.totalBranches else 0.0

    val sb = StringBuilder()
    sb.appendLine("Coverage for package '${coverage.packageName}':")
    sb.appendLine("  Lines:    ${coverage.coveredLines}/${coverage.totalLines} (${"%.1f".format(lineRate * 100)}%)")
    sb.appendLine("  Branches: ${coverage.coveredBranches}/${coverage.totalBranches} (${"%.1f".format(branchRate * 100)}%)")

    if (fileCoverages.isNotEmpty()) {
        sb.appendLine("  Per file:")
        for (file in fileCoverages) {
            val fileLineRate = if (file.totalLines > 0) file.coveredLines.toDouble() / file.totalLines else 0.0
            sb.appendLine("    ${file.filePath}: ${file.coveredLines}/${file.totalLines} lines (${"%.1f".format(fileLineRate * 100)}%)")
        }
    }

    return sb.toString().trimEnd()
}

private fun launchWithCoverage(project: Project, configName: String, packageNames: Set<String>): ExecutionResult {
    val runManager = RunManager.getInstance(project)
    val settings = runManager.findConfigurationByName(configName)
        ?: throw IllegalStateException("Configuration '$configName' not found after creation")

    val coverageExecutor = com.intellij.execution.ExecutorRegistry.getInstance()
        .getExecutorById(CoverageExecutor.EXECUTOR_ID)
        ?: throw IllegalStateException("Coverage executor not found. Is com.intellij.coverage plugin enabled?")

    val runConfig = settings.configuration as? RunConfigurationBase<*>
        ?: throw IllegalStateException("Configuration is not a RunConfigurationBase")
    CoverageEnabledConfiguration.getOrCreate(runConfig)

    val testResultFuture = CompletableFuture<TestRunSummary>()
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
                coverageFuture.complete(extractPackageCoverage(projectData, packageNames))
            } catch (e: Exception) {
                coverageFuture.complete(null)
            } finally {
                Disposer.dispose(coverageDisposable)
            }
        }
    }, coverageDisposable)

    val buildErrors = BuildErrorCollector()
    val buildDisposable = Disposer.newDisposable("build-listener-$configName")
    Disposer.register(project as com.intellij.openapi.Disposable, buildDisposable)
    project.getService(BuildViewManager::class.java).addListener(buildErrors, buildDisposable)

    val connection = project.messageBus.connect()
    val processOutput = StringBuilder()

    connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
        override fun processNotStarted(executorId: String, env: ExecutionEnvironment, cause: Throwable?) {
            if (env.runProfile.name != configName) return
            try {
                val message = buildNotStartedMessage(configName, cause, buildErrors.errors())
                testResultFuture.complete(TestRunSummary(message, true))
                coverageFuture.complete(null)
            } finally {
                connection.disconnect()
                disposeSafely(buildDisposable)
                disposeSafely(coverageDisposable)
            }
        }

        override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            if (env.runProfile.name != configName) return
            disposeSafely(buildDisposable)

            val capturedConsole = env.contentToReuse?.executionConsole

            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    processOutput.append(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    try {
                        val console = capturedConsole ?: findConsole(project, configName)
                        testResultFuture.complete(extractTestResults(console, configName, event.exitCode, processOutput.toString()))
                    } catch (e: Exception) {
                        val fallback = fallbackMessage(configName, event.exitCode, processOutput.toString())
                        testResultFuture.complete(TestRunSummary(fallback, event.exitCode != 0))
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
            disposeSafely(buildDisposable)
            testResultFuture.completeExceptionally(e)
            coverageFuture.complete(null)
        }
    }

    val testSummary = try {
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

    return ExecutionResult(testSummary.output, testSummary.failed, coverage)
}

internal fun extractPackageCoverage(projectData: ProjectData, packageNames: Set<String>): PackageCoverage {
    val matchingClasses = projectData.classes.filter { (className, _) ->
        packageNames.any { pkg -> classInPackage(className, pkg) } && !isTestClass(className)
    }

    val classCoverages = matchingClasses.map { (className, classData) ->
        extractClassCoverage(className, classData)
    }

    val label = if (packageNames.size == 1) packageNames.single() else packageNames.sorted().joinToString(", ")

    return PackageCoverage(
        packageName = label,
        totalLines = classCoverages.sumOf { it.totalLines },
        coveredLines = classCoverages.sumOf { it.coveredLines },
        totalBranches = classCoverages.sumOf { it.totalBranches },
        coveredBranches = classCoverages.sumOf { it.coveredBranches },
        classCoverages = classCoverages
    )
}

private fun extractClassCoverage(className: String, classData: ClassData): ClassCoverage {
    val lines = classData.lines ?: return ClassCoverage(className, 0, 0, 0, 0, emptyList(), emptyList())
    var totalLines = 0
    var coveredLines = 0
    var totalBranches = 0
    var coveredBranches = 0
    val coveredLineNumbers = mutableListOf<Int>()
    val uncoveredLineNumbers = mutableListOf<Int>()

    for (line in lines) {
        val ld = line as? LineData ?: continue
        totalLines++
        if (ld.status.toInt() != LineCoverage.NONE.toInt()) {
            coveredLines++
            coveredLineNumbers.add(ld.lineNumber)
        } else {
            uncoveredLineNumbers.add(ld.lineNumber)
        }

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

    return ClassCoverage(className, totalLines, coveredLines, totalBranches, coveredBranches, coveredLineNumbers, uncoveredLineNumbers)
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

private fun formatTestResults(configName: String, root: AbstractTestProxy, exitCode: Int): TestRunSummary {
    val leafTests = root.allTests.filter { it.isLeaf && it !== root }
    val passed = leafTests.count { it.isPassed }
    val failed = leafTests.count { it.isDefect }
    val ignored = leafTests.count { !it.isPassed && !it.isDefect }
    val runFailed = failed > 0 || exitCode != 0 || leafTests.isEmpty()

    val sb = StringBuilder()
    sb.appendLine("Test run '$configName' completed (exit code $exitCode)")
    sb.appendLine("Total: ${leafTests.size}, Passed: $passed, Failed: $failed, Ignored: $ignored")

    if (exitCode != 0 && failed == 0) {
        sb.appendLine()
        sb.appendLine("WARNING: non-zero exit code ($exitCode) with no test failures — the test process may have crashed or failed to start.")
    }

    if (leafTests.isEmpty() && exitCode == 0) {
        sb.appendLine()
        sb.appendLine("WARNING: no tests were found or executed.")
    }

    val failures = leafTests.filter { it.isDefect }
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

    return TestRunSummary(sb.toString().trimEnd(), runFailed)
}

internal fun formatUncoveredLines(
    fileCoverages: List<FileCoverage>,
    patterns: List<String>,
    sourceReader: (String) -> List<String>?
): String {
    val matching = fileCoverages.filter { file ->
        file.uncoveredLineNumbers.isNotEmpty() && patterns.any { matchesGlob(it, file.filePath) }
    }
    if (matching.isEmpty()) return ""

    return buildString {
        for (file in matching) {
            appendLine("Uncovered lines in ${file.filePath}:")
            val sourceLines = sourceReader(file.filePath)
            for (lineNum in file.uncoveredLineNumbers) {
                val content = sourceLines?.getOrNull(lineNum - 1)?.trimEnd()
                if (content != null) appendLine("  $lineNum: $content")
                else appendLine("  $lineNum")
            }
        }
    }.trimEnd()
}

internal fun buildNotStartedMessage(configName: String, cause: Throwable?, buildErrors: List<String>): String {
    val sb = StringBuilder("Configuration '$configName' failed to start — build or compilation error")
    if (buildErrors.isNotEmpty()) {
        sb.appendLine()
        sb.appendLine()
        sb.appendLine("Build errors:")
        for (error in buildErrors) sb.appendLine("  $error")
    } else if (cause != null) {
        sb.appendLine()
        sb.appendLine()
        sb.append(cause.message ?: cause.toString())
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

private fun createJUnitConfig(project: Project, params: RunTestTool.ConfigParams): String {
    val runManager = RunManager.getInstance(project)
    val junitType = com.intellij.execution.junit.JUnitConfigurationType.getInstance()
    val factory = junitType.configurationFactories[0]
    val configName = "RunTest-${configSuffix(params.targets)}"
    val settings = runManager.createConfiguration(configName, factory)
    val config = settings.configuration as com.intellij.execution.junit.JUnitConfiguration

    when (params.scope) {
        RunTestTool.TestScope.PACKAGE -> if (params.targets.size == 1) {
            config.persistentData.TEST_OBJECT = com.intellij.execution.junit.JUnitConfiguration.TEST_PACKAGE
            config.persistentData.PACKAGE_NAME = params.targets.single()
        } else {
            configurePattern(config, params.targets.map { "$it.*" })
        }
        RunTestTool.TestScope.CLASS -> if (params.targets.size == 1) {
            config.persistentData.TEST_OBJECT = com.intellij.execution.junit.JUnitConfiguration.TEST_CLASS
            config.persistentData.MAIN_CLASS_NAME = params.targets.single()
        } else {
            configurePattern(config, params.targets)
        }
        RunTestTool.TestScope.METHOD -> {
            val (className, methodName) = params.targets.single().split("#", limit = 2)
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

private fun configurePattern(config: com.intellij.execution.junit.JUnitConfiguration, patterns: List<String>) {
    config.persistentData.TEST_OBJECT = com.intellij.execution.junit.JUnitConfiguration.TEST_PATTERN
    config.persistentData.setPatterns(linkedSetOf(*patterns.toTypedArray()))
}

private fun disposeSafely(disposable: com.intellij.openapi.Disposable) {
    try { Disposer.dispose(disposable) } catch (_: Exception) { }
}

private class BuildErrorCollector : BuildProgressListener {
    private val messages = CopyOnWriteArrayList<String>()

    override fun onEvent(buildId: Any, event: BuildEvent) {
        if (event is MessageEvent && event.kind == MessageEvent.Kind.ERROR) {
            val location = (event as? FileMessageEvent)?.filePosition?.let { formatFilePosition(it) }
            val prefix = if (location != null) "$location: " else ""
            messages.add("$prefix${event.message}")
        }
    }

    fun errors(): List<String> = messages.toList()
}

private fun createGradleConfig(
    project: Project,
    params: RunTestTool.ConfigParams,
    gradleModuleData: DataNode<ModuleData>
): String {
    val runManager = RunManager.getInstance(project)
    val gradleType = GradleExternalTaskConfigurationType.getInstance()
    val factory = gradleType.factory
    val configName = "RunTest-${configSuffix(params.targets)}"
    val settings = runManager.createConfiguration(configName, factory)
    val config = settings.configuration as GradleRunConfiguration

    val taskSettings = config.settings
    taskSettings.externalSystemIdString = GradleConstants.SYSTEM_ID.id
    taskSettings.externalProjectPath = gradleModuleData.data.linkedExternalProjectPath
    taskSettings.taskNames = listOf(gradleTestTaskName(gradleModuleData.data.id))
    taskSettings.scriptParameters = params.targets
        .joinToString(" ") { buildGradleTestFilter(params.scope, it) }

    settings.isTemporary = true
    runManager.addConfiguration(settings)
    return configName
}

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

internal data class ValidationResult(val scope: RunTestTool.TestScope?, val error: CallToolResult?)

internal fun validateTestParams(scope: String, target: String): ValidationResult {
    val testScope = SCOPE_BY_NAME[scope]
        ?: return ValidationResult(null, errorResult("Invalid scope '$scope'. Must be one of: ${SCOPE_BY_NAME.keys.joinToString()}"))

    if (target.isEmpty()) return ValidationResult(null, errorResult("Target must not be empty"))

    if (testScope == RunTestTool.TestScope.METHOD && '#' !in target)
        return ValidationResult(null, errorResult("Method scope requires target format 'com.example.MyTest#myMethod'"))

    return ValidationResult(testScope, null)
}

private fun configSuffix(targets: List<String>) =
    if (targets.size == 1) targets.single().substringAfterLast('.')
    else "${targets.size}-targets"

private val SCOPE_BY_NAME = RunTestTool.TestScope.entries.associateBy { it.name.lowercase() }

private fun buildGradleTestFilter(scope: RunTestTool.TestScope, target: String) = when (scope) {
    RunTestTool.TestScope.PACKAGE -> "--tests \"$target.*\""
    RunTestTool.TestScope.CLASS -> "--tests \"$target\""
    RunTestTool.TestScope.METHOD -> {
        val (className, methodName) = target.split("#", limit = 2)
        "--tests \"$className.${methodName.substringBefore('(')}\""
    }
}

private fun classInPackage(fqClassName: String, packageName: String) =
    fqClassName.startsWith("$packageName.") || fqClassName == packageName

private fun isTestClass(fqClassName: String): Boolean {
    val simpleName = fqClassName.substringAfterLast('.').substringBefore('$')
    return TEST_CLASS_SUFFIXES.any { simpleName.endsWith(it) }
}

private val TEST_CLASS_SUFFIXES = listOf("Test", "Tests", "TestCase", "IT")

private fun extractRelevantFrames(stacktrace: String): List<String> {
    val lines = stacktrace.lines()
    val exceptionLine = lines.firstOrNull { it.isNotBlank() }
        ?.takeUnless { it.trimStart().startsWith("at ") || it.trimStart().startsWith("Caused by:") }
    val causeIndex = lines.indexOfFirst { it.trimStart().startsWith("Caused by:") }
    val causeLine = if (causeIndex >= 0) lines[causeIndex] else null
    val primaryLines = if (causeIndex >= 0) lines.subList(0, causeIndex) else lines
    val primaryFrames = filterFrames(primaryLines, keepFirstFrame = exceptionLine == null)

    val result = mutableListOf<String>()
    if (exceptionLine != null) result.add(exceptionLine.trim())
    result.addAll(primaryFrames.take(MAX_STACKTRACE_FRAMES))
    if (causeLine != null) {
        result.add(causeLine.trim())
        val causeLines = lines.subList(causeIndex + 1, lines.size)
        result.addAll(filterFrames(causeLines, keepFirstFrame = false).take(MAX_STACKTRACE_FRAMES))
    }
    return result
}

private fun filterFrames(lines: List<String>, keepFirstFrame: Boolean): List<String> {
    var first = true
    return lines.filter { line ->
        val trimmed = line.trimStart()
        if (!trimmed.startsWith("at ")) return@filter false
        val isFirst = first.also { first = false }
        (keepFirstFrame && isFirst) || FRAMEWORK_PACKAGE_PREFIXES.none { prefix -> trimmed.startsWith("at $prefix") }
    }.map { it.trim() }
}

private const val MAX_STACKTRACE_FRAMES = 5

// FilePosition uses 0-based lines; display as 1-based for human readability.
private fun formatFilePosition(pos: com.intellij.build.FilePosition): String? {
    val file = pos.file ?: return null
    val line = pos.startLine + 1
    return "${file.name}:$line"
}

private fun readSourceLines(project: Project, filePath: String): List<String>? =
    ReadAction.compute<List<String>?, Exception> {
        val baseDir = project.basePath ?: return@compute null
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$baseDir/$filePath") ?: return@compute null
        try {
            String(virtualFile.contentsToByteArray(), virtualFile.charset).lines()
        } catch (_: Exception) {
            null
        }
    }

private fun resolveFilePath(project: Project, className: String): String? =
    ReadAction.compute<String?, Exception> {
        val outerClassName = className.substringBefore('$')
        resolveClassFile(project, outerClassName)
            ?: outerClassName.removeSuffix("Kt").takeIf { it != outerClassName }
                ?.let { resolveClassFile(project, it) }
    }

private fun resolveClassFile(project: Project, className: String): String? {
    val psiClass = JavaPsiFacade.getInstance(project)
        .findClass(className, GlobalSearchScope.projectScope(project))
        ?: return null
    val virtualFile = psiClass.containingFile?.virtualFile ?: return null
    val basePath = project.basePath ?: return virtualFile.path
    return virtualFile.path.removePrefix("$basePath/")
}

internal fun groupByFile(
    classCoverages: List<ClassCoverage>,
    filePathResolver: (String) -> String?
): List<FileCoverage> {
    val coveragesByFile = linkedMapOf<String, MutableList<ClassCoverage>>()
    for (cls in classCoverages) {
        val filePath = filePathResolver(cls.className) ?: cls.className
        coveragesByFile.getOrPut(filePath) { mutableListOf() }.add(cls)
    }
    return coveragesByFile.map { (filePath, classes) ->
        val covered = classes.flatMap { it.coveredLineNumbers }.toSortedSet()
        val uncovered = classes.flatMap { it.uncoveredLineNumbers }.toSortedSet() - covered
        FileCoverage(
            filePath = filePath,
            totalLines = covered.size + uncovered.size,
            coveredLines = covered.size,
            totalBranches = classes.sumOf { it.totalBranches },
            coveredBranches = classes.sumOf { it.coveredBranches },
            coveredLineNumbers = covered.toList(),
            uncoveredLineNumbers = uncovered.toList()
        )
    }
}

internal fun matchesGlob(glob: String, path: String): Boolean {
    val regex = buildString {
        append("^")
        var i = 0
        while (i < glob.length) {
            when {
                glob[i] == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    if (i + 2 < glob.length && glob[i + 2] == '/') {
                        append("(.*/)?")
                        i += 3
                    } else {
                        append(".*")
                        i += 2
                    }
                }
                glob[i] == '*' -> { append("[^/]*"); i++ }
                glob[i] == '?' -> { append("[^/]"); i++ }
                glob[i] == '.' -> { append("\\."); i++ }
                else -> { append(glob[i]); i++ }
            }
        }
        append("$")
    }
    return Regex(regex).matches(path)
}

internal fun correctForUnloadedClasses(
    fileCoverages: List<FileCoverage>,
    patterns: List<String>,
    loadedClassNames: Set<String>,
    classesInFile: (String) -> Set<String>,
    sourceReader: (String) -> List<String>?
): List<FileCoverage> = fileCoverages.map { file ->
    if (!patterns.any { matchesGlob(it, file.filePath) }) return@map file
    val expectedClasses = classesInFile(file.filePath)
    // Apply heuristic only for lines not seen by the engine; preserves engine data for loaded classes
    if (expectedClasses.isEmpty() || expectedClasses.all { it in loadedClassNames }) return@map file

    val sourceLines = sourceReader(file.filePath) ?: return@map file
    val engineLineNumbers = file.coveredLineNumbers.toSet() + file.uncoveredLineNumbers.toSet()
    val heuristicUncovered = sourceLines.indices
        .map { it + 1 }
        .filter { it !in engineLineNumbers && isCodeLine(sourceLines[it - 1]) }
    val allUncovered = (file.uncoveredLineNumbers + heuristicUncovered).sorted()

    FileCoverage(
        filePath = file.filePath,
        totalLines = file.coveredLines + allUncovered.size,
        coveredLines = file.coveredLines,
        totalBranches = file.totalBranches,
        coveredBranches = file.coveredBranches,
        coveredLineNumbers = file.coveredLineNumbers,
        uncoveredLineNumbers = allUncovered
    )
}

internal fun isCodeLine(line: String): Boolean {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.startsWith("//")) return false
    if (trimmed.startsWith("/*") || trimmed.startsWith("*") || trimmed.startsWith("*/")) return false
    if (trimmed.startsWith("package ")) return false
    if (trimmed.startsWith("import ")) return false
    if (trimmed == "{" || trimmed == "}" || trimmed == ")") return false
    if (trimmed.startsWith("@")) return false
    return true
}

private fun resolveClassesInFile(project: Project, filePath: String): Set<String> =
    ReadAction.compute<Set<String>, Exception> {
        val baseDir = project.basePath ?: return@compute emptySet()
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath("$baseDir/$filePath") ?: return@compute emptySet()
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            ?: return@compute emptySet()
        val classes = mutableSetOf<String>()
        psiFile.accept(object : com.intellij.psi.PsiRecursiveElementVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (element is com.intellij.psi.PsiClass && element.qualifiedName != null) {
                    classes.add(element.qualifiedName!!)
                }
                super.visitElement(element)
            }
        })
        // Kotlin file facade class for top-level functions/properties
        if (filePath.endsWith(".kt")) {
            val packageName = (psiFile as? com.intellij.psi.PsiClassOwner)?.packageName ?: ""
            val fileName = virtualFile.nameWithoutExtension
            val facadeClass = if (packageName.isEmpty()) "${fileName}Kt" else "$packageName.${fileName}Kt"
            classes.add(facadeClass)
        }
        classes
    }

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
