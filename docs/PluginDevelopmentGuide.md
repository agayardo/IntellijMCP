# Building an IntelliJ plugin for programmatic test coverage collection

**The fastest path to running tests with coverage programmatically inside IntelliJ IDEA** — without any user interaction — requires stitching together five distinct API surfaces: the Gradle build toolchain, the RunManager execution API, the coverage executor subsystem, the coverage data model, and the VFS/threading infrastructure for automation. This guide covers all five, with complete working code.

The plugin architecture follows a clean pipeline: a `postStartupActivity` registers a `BulkFileListener` that watches for a trigger file in the project root. When detected, a project service reads the trigger parameters, creates a `JUnitConfiguration` via `RunManager`, launches it through `ExecutionEnvironmentBuilder` with the `CoverageExecutor`, listens for process termination via `ExecutionListener`, then reads `ProjectData` from the `CoverageSuitesBundle` and writes JSON results to disk. Every step has threading constraints that must be respected.

---

## 1. Plugin project setup with the IntelliJ Platform Gradle Plugin

The **IntelliJ Platform Gradle Plugin 2.x** (`org.jetbrains.intellij.platform`) is the actively maintained build tool. The legacy 1.x plugin (`org.jetbrains.intellij`) reached its final release at **1.17.4** and is no longer developed. Use 2.x for all new work.

### `settings.gradle.kts`

```kotlin
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("org.jetbrains.intellij.platform.settings") version "2.13.1"
}

rootProject.name = "auto-test-coverage-plugin"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        intellijPlatform {
            defaultRepositories()
        }
    }
}
```

When you declare the plugin version in `settings.gradle.kts`, **omit the version** in `build.gradle.kts` to avoid conflicts.

### `build.gradle.kts`

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform")  // version from settings.gradle.kts
}

group = "com.example"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    intellijPlatform {
        // Target IDE — for 2024.3.x:
        intellijIdeaCommunity("2024.3.6")
        // For 2025.3+ (unified distribution): use intellijIdea("2025.3.3") instead

        // Bundled plugin dependencies — these are REQUIRED
        bundledPlugin("com.intellij.java")       // Java PSI, run configs
        bundledPlugin("JUnit")                    // JUnitConfiguration, JUnitConfigurationType
        bundledPlugin("com.intellij.coverage")    // CoverageExecutor, CoverageDataManager

        // Test framework for plugin tests
        testFramework(TestFrameworkType.Platform)
    }

    // Workaround: opentest4j needed for test framework (IJPL-157292)
    testImplementation("org.opentest4j:opentest4j:1.3.0")
    testRuntimeOnly("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"  // 2024.3
        }
    }
    // Disable if your plugin has no Settings UI
    buildSearchableOptions = false
}
```

### `gradle.properties`

```properties
kotlin.stdlib.default.dependency = false
org.gradle.configuration-cache = true
```

### `plugin.xml`

Place at `src/main/resources/META-INF/plugin.xml`:

```xml
<idea-plugin>
    <id>com.example.auto-test-coverage</id>
    <name>Auto Test Coverage Runner</name>
    <vendor>Example Corp</vendor>

    <description><![CDATA[
    Programmatically runs tests with coverage and collects results.
    ]]></description>

    <!-- Required dependencies -->
    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.java</depends>
    <depends>com.intellij.java</depends>
    <depends>JUnit</depends>
    <depends>com.intellij.coverage</depends>

    <extensions defaultExtensionNs="com.intellij">
        <!-- Registers file watcher on project open -->
        <postStartupActivity
            implementation="com.example.coverage.AutoTestStartupActivity"/>
    </extensions>

    <!-- Execution listener for process termination -->
    <applicationListeners>
        <listener class="com.example.coverage.TestExecutionListener"
                  topic="com.intellij.execution.ExecutionListener"/>
    </applicationListeners>
</idea-plugin>
```

**Dependency ID clarifications:** `com.intellij.modules.java` is a module declaration that restricts your plugin to Java-capable IDEs. `com.intellij.java` is the actual Java plugin providing PSI and run configuration APIs. You need both. `JUnit` (literal string, not `com.intellij.junit`) is the bundled JUnit runner plugin. `com.intellij.coverage` is the bundled coverage plugin providing `CoverageExecutor` and `CoverageDataManager`.

### Project structure

```
auto-test-coverage-plugin/
├── src/main/
│   ├── kotlin/com/example/coverage/
│   │   ├── AutoTestStartupActivity.kt
│   │   ├── AutoTestOrchestrator.kt       // project service: orchestration
│   │   ├── TestConfigurationFactory.kt   // creates JUnit run configs
│   │   ├── CoverageExecutionRunner.kt    // runs configs with coverage
│   │   ├── CoverageResultCollector.kt    // reads coverage data
│   │   └── TestExecutionListener.kt      // detects process termination
│   └── resources/META-INF/
│       └── plugin.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 2. Creating JUnit run configurations programmatically

The `RunManager` API is the entry point for all run configuration manipulation. You obtain the JUnit-specific `ConfigurationType` and `ConfigurationFactory`, create a `RunnerAndConfigurationSettings` wrapper, then configure the inner `JUnitConfiguration` and its `Data` object.

### Core API surface

`JUnitConfiguration` extends `ModuleBasedConfiguration` and exposes a **`persistentData`** property returning `JUnitConfiguration.Data` — a mutable object with public fields serialized via `DefaultJDOMExternalizer`. The `Data.TEST_OBJECT` field controls what scope of tests to run:

| Constant | Value | What it runs |
|----------|-------|-------------|
| `TEST_PACKAGE` | `"package"` | All tests in a Java package |
| `TEST_CLASS` | `"class"` | All tests in a single class |
| `TEST_METHOD` | `"method"` | A specific test method |
| `TEST_PATTERN` | `"pattern"` | Tests matching a class/method pattern |
| `TEST_DIRECTORY` | `"directory"` | All tests under a directory |
| `TEST_CATEGORY` | `"category"` | Tests annotated with a JUnit category |
| `TEST_TAGS` | `"tags"` | Tests matching JUnit 5 tags |

### `TestConfigurationFactory.kt`

```kotlin
package com.example.coverage

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.testframework.TestSearchScope
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project

object TestConfigurationFactory {

    /**
     * Creates a JUnit run configuration for a specific test class.
     * Returns the RunnerAndConfigurationSettings ready for execution.
     */
    fun createClassConfig(
        project: Project,
        module: Module,
        fqClassName: String,
        configName: String = "AutoTest-${fqClassName.substringAfterLast('.')}"
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val junitType = JUnitConfigurationType.getInstance()
        val factory = junitType.configurationFactories[0]

        val settings = runManager.createConfiguration(configName, factory)
        val config = settings.configuration as JUnitConfiguration

        config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
        config.persistentData.MAIN_CLASS_NAME = fqClassName
        config.setModule(module)
        config.setSearchScope(TestSearchScope.MODULE_WITH_DEPENDENCIES)

        // Persist in the IDE's run configuration list
        settings.isTemporary = true
        runManager.addConfiguration(settings)
        return settings
    }

    /**
     * Creates a JUnit run configuration for a specific test method.
     */
    fun createMethodConfig(
        project: Project,
        module: Module,
        fqClassName: String,
        methodName: String,
        configName: String = "AutoTest-${fqClassName.substringAfterLast('.')}.$methodName"
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val junitType = JUnitConfigurationType.getInstance()
        val factory = junitType.configurationFactories[0]

        val settings = runManager.createConfiguration(configName, factory)
        val config = settings.configuration as JUnitConfiguration

        config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
        config.persistentData.MAIN_CLASS_NAME = fqClassName
        config.persistentData.METHOD_NAME = methodName
        config.setModule(module)

        settings.isTemporary = true
        runManager.addConfiguration(settings)
        return settings
    }

    /**
     * Creates a JUnit run configuration for an entire package.
     */
    fun createPackageConfig(
        project: Project,
        module: Module,
        packageName: String,
        configName: String = "AutoTest-pkg-$packageName"
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val junitType = JUnitConfigurationType.getInstance()
        val factory = junitType.configurationFactories[0]

        val settings = runManager.createConfiguration(configName, factory)
        val config = settings.configuration as JUnitConfiguration

        config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_PACKAGE
        config.persistentData.PACKAGE_NAME = packageName
        config.setModule(module)
        config.setSearchScope(TestSearchScope.WHOLE_PROJECT)

        settings.isTemporary = true
        runManager.addConfiguration(settings)
        return settings
    }

    /**
     * Creates a JUnit run configuration using class/method patterns.
     * Pattern format: "com.example.MyTest,testMethod" per entry.
     */
    fun createPatternConfig(
        project: Project,
        module: Module,
        patterns: Set<String>,
        configName: String = "AutoTest-pattern"
    ): RunnerAndConfigurationSettings {
        val runManager = RunManager.getInstance(project)
        val junitType = JUnitConfigurationType.getInstance()
        val factory = junitType.configurationFactories[0]

        val settings = runManager.createConfiguration(configName, factory)
        val config = settings.configuration as JUnitConfiguration

        config.persistentData.TEST_OBJECT = JUnitConfiguration.TEST_PATTERN
        config.persistentData.setPatterns(LinkedHashSet(patterns))
        config.setModule(module)

        settings.isTemporary = true
        runManager.addConfiguration(settings)
        return settings
    }
}
```

**Key detail**: `JUnitConfigurationType.getInstance()` is the canonical way to obtain the type. The alternative `ConfigurationTypeUtil.findConfigurationType(JUnitConfigurationType::class.java)` also works. The factory at index `[0]` is the default JUnit factory — there is only one.

If you have `PsiClass` or `PsiMethod` references (e.g., from a PSI search), `JUnitConfiguration` offers convenience methods: `beClassConfiguration(psiClass)` and `beMethodConfiguration(location)` that internally populate all `Data` fields.

---

## 3. Running configurations with the coverage executor

The gap between "run this test" and "run this test **with coverage**" comes down to three things: selecting the `CoverageExecutor` instead of `DefaultRunExecutor`, ensuring `CoverageEnabledConfiguration` is initialized for the run config, and optionally choosing between IntelliJ's built-in runner and JaCoCo.

### How the coverage agent gets attached

When you execute with the `CoverageExecutor` (ID: `"Coverage"`), IntelliJ's extension mechanism activates **`CoverageJavaRunConfigurationExtension.updateJavaParameters()`**. This method intercepts the JVM launch, determines the correct coverage agent JAR (either IntelliJ's `intellij-coverage-agent.jar` or JaCoCo's agent), and appends a `-javaagent:/path/to/agent.jar=<args>` flag to the JVM parameters. The args file specifies the output path (`.ic` or `.exec`), include/exclude patterns, and whether to track branches. You do not need to configure any of this manually — **calling `CoverageEnabledConfiguration.getOrCreate()` on your run config is the critical step** that makes the extension recognize the config as coverage-eligible.

### `CoverageExecutionRunner.kt`

```kotlin
package com.example.coverage

import com.intellij.coverage.CoverageExecutor
import com.intellij.coverage.CoverageRunner
import com.intellij.coverage.DefaultJavaCoverageRunner
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ExecutionManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

object CoverageExecutionRunner {

    private val LOG = Logger.getInstance(CoverageExecutionRunner::class.java)

    /**
     * Runs a RunnerAndConfigurationSettings with the Coverage executor.
     * MUST be called on the EDT.
     *
     * @param useJaCoCo If true, uses JaCoCo runner (.exec files).
     *                  If false, uses IntelliJ's built-in runner (.ic files).
     */
    fun runWithCoverage(
        project: Project,
        settings: RunnerAndConfigurationSettings,
        useJaCoCo: Boolean = false
    ) {
        // 1. Get the Coverage executor
        val coverageExecutor = ExecutorRegistry.getInstance()
            .getExecutorById(CoverageExecutor.EXECUTOR_ID)
            ?: throw IllegalStateException(
                "Coverage executor not found. Is com.intellij.coverage plugin enabled?"
            )

        // 2. CRITICAL: Initialize CoverageEnabledConfiguration for this run config.
        //    Without this call, the CoverageJavaRunConfigurationExtension won't
        //    attach the -javaagent, and you'll get 0% coverage.
        val runConfig = settings.configuration as? RunConfigurationBase<*>
            ?: throw IllegalStateException("Configuration is not a RunConfigurationBase")
        val covConfig = CoverageEnabledConfiguration.getOrCreate(runConfig)

        // 3. Select coverage runner
        if (useJaCoCo) {
            try {
                val jacocoRunner = CoverageRunner.getInstance(
                    Class.forName("com.intellij.coverage.JaCoCoCoverageRunner")
                        as Class<out CoverageRunner>
                )
                covConfig.coverageRunner = jacocoRunner
            } catch (e: ClassNotFoundException) {
                LOG.warn("JaCoCo runner not found, falling back to IntelliJ runner", e)
            }
        } else {
            val ideaRunner = CoverageRunner.getInstance(DefaultJavaCoverageRunner::class.java)
            if (ideaRunner != null) {
                covConfig.coverageRunner = ideaRunner
            }
        }

        // 4. Build execution environment and launch
        val builder = ExecutionEnvironmentBuilder.createOrNull(coverageExecutor, settings)
        if (builder == null) {
            LOG.error("No ProgramRunner can handle Coverage + ${settings.type.displayName}")
            return
        }

        val environment = builder
            .contentToReuse(null)
            .dataContext(null)
            .activeTarget()
            .build()

        ExecutionManager.getInstance(project).restartRunProfile(environment)
        LOG.info("Launched '${settings.name}' with coverage (runner: " +
                "${if (useJaCoCo) "JaCoCo" else "IntelliJ"})")
    }
}
```

### Simpler one-liner alternative

If you don't need to control the coverage runner, this single call handles everything:

```kotlin
ProgramRunnerUtil.executeConfiguration(settings, coverageExecutor)
```

This internally calls `ExecutionEnvironmentBuilder.create()`, resolves the `ProgramRunner`, builds the environment, and executes. It does **not** call `CoverageEnabledConfiguration.getOrCreate()` for you — you must still do that yourself before calling this method.

### IntelliJ runner vs. JaCoCo runner

**`DefaultJavaCoverageRunner`** (IntelliJ's built-in) produces `.ic` files. It supports per-test coverage tracking and uses IntelliJ's own instrumentation agent. This is the default and generally more reliable inside the IDE.

**`JaCoCoCoverageRunner`** produces `.exec` files in standard JaCoCo format. Better if you need to merge results with CI coverage reports. However, it can conflict with the IntelliJ Platform Gradle Plugin's bytecode instrumentation (the **0% coverage bug** — see pitfalls section below).

---

## 4. Collecting coverage data after execution completes

Coverage data becomes available asynchronously after the test process terminates. The correct listener is `CoverageSuiteListener`, registered via `CoverageDataManager.addSuiteListener()`. The **`coverageDataCalculated`** callback fires after the data file is loaded and accumulated — this is the safe point to read results.

### Listener registration and the event sequence

After you launch a test with the coverage executor, events fire in this order:

1. **Process terminates** → `CoverageDataManager.attachToProcess()` callback fires
2. **`coverageGathered(suite)`** → raw `.ic`/`.exec` file exists on disk but may not be parsed yet
3. **`beforeSuiteChosen()`** → suite about to be activated
4. **`coverageDataCalculated(bundle)`** → `ProjectData` is fully loaded — **read data here**
5. **`afterSuiteChosen()`** → UI updated with gutter marks and Coverage tool window

### `CoverageResultCollector.kt`

```kotlin
package com.example.coverage

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuiteListener
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.LineCoverage
import com.intellij.rt.coverage.data.ProjectData
import java.io.File

/**
 * Data classes for structured coverage output.
 */
data class LineCoverageInfo(
    val lineNumber: Int,
    val hits: Int,
    val status: String,          // "NONE", "PARTIAL", "FULL"
    val methodSignature: String?,
    val totalBranches: Int,
    val coveredBranches: Int
)

data class ClassCoverageInfo(
    val className: String,
    val totalLines: Int,
    val coveredLines: Int,
    val lineRate: Double,
    val totalBranches: Int,
    val coveredBranches: Int,
    val branchRate: Double,
    val lines: List<LineCoverageInfo>
)

data class CoverageReport(
    val timestamp: Long,
    val totalClasses: Int,
    val totalLines: Int,
    val coveredLines: Int,
    val lineRate: Double,
    val totalBranches: Int,
    val coveredBranches: Int,
    val branchRate: Double,
    val classes: List<ClassCoverageInfo>
)

class CoverageResultCollector(
    private val project: Project,
    private val parentDisposable: Disposable
) {
    private val LOG = Logger.getInstance(CoverageResultCollector::class.java)

    /**
     * Registers a one-shot listener that fires [onResult] when
     * coverage data is fully loaded after the next coverage run.
     */
    fun collectOnNextRun(onResult: (CoverageReport) -> Unit) {
        val manager = CoverageDataManager.getInstance(project)
        manager.addSuiteListener(object : CoverageSuiteListener {

            override fun coverageGathered(suite: com.intellij.coverage.CoverageSuite) {
                LOG.info("Coverage data file ready: ${suite.presentableName}")
            }

            override fun coverageDataCalculated(suitesBundle: CoverageSuitesBundle) {
                LOG.info("Coverage data fully loaded, extracting results...")
                val projectData = suitesBundle.getCoverageData()
                if (projectData == null) {
                    LOG.warn("CoverageSuitesBundle returned null ProjectData")
                    return
                }
                val report = extractReport(projectData)
                onResult(report)
            }
        }, parentDisposable)
    }

    /**
     * Extracts structured coverage information from ProjectData.
     * ProjectData.classes is a Map<String, ClassData>.
     * ClassData.lines is an array indexed by line number;
     * null entries are non-executable lines.
     */
    fun extractReport(projectData: ProjectData): CoverageReport {
        val classInfos = mutableListOf<ClassCoverageInfo>()

        for ((className, classData) in projectData.classes) {
            val lines = classData.lines ?: continue
            val lineInfos = mutableListOf<LineCoverageInfo>()
            var classTotalLines = 0
            var classCoveredLines = 0
            var classTotalBranches = 0
            var classCoveredBranches = 0

            for (i in lines.indices) {
                val ld = lines[i] as? LineData ?: continue
                classTotalLines++

                val covered = ld.status.toInt() != LineCoverage.NONE.toInt()
                if (covered) classCoveredLines++

                val statusStr = when (ld.status.toInt()) {
                    LineCoverage.NONE.toInt() -> "NONE"
                    LineCoverage.PARTIAL.toInt() -> "PARTIAL"
                    LineCoverage.FULL.toInt() -> "FULL"
                    else -> "UNKNOWN"
                }

                // Branch data from jumps (if/else)
                var lineTotalBranches = 0
                var lineCoveredBranches = 0
                for (j in 0 until ld.jumpsCount()) {
                    val jump = ld.getJumpData(j) ?: continue
                    lineTotalBranches += 2
                    if (jump.trueHits > 0) lineCoveredBranches++
                    if (jump.falseHits > 0) lineCoveredBranches++
                }

                // Branch data from switches
                for (s in 0 until ld.switchesCount()) {
                    val sw = ld.getSwitchData(s) ?: continue
                    lineTotalBranches += sw.hits.size + 1  // cases + default
                    for (h in sw.hits) { if (h > 0) lineCoveredBranches++ }
                    if (sw.defaultHits > 0) lineCoveredBranches++
                }

                classTotalBranches += lineTotalBranches
                classCoveredBranches += lineCoveredBranches

                lineInfos.add(LineCoverageInfo(
                    lineNumber = ld.lineNumber,
                    hits = ld.hits,
                    status = statusStr,
                    methodSignature = ld.methodSignature,
                    totalBranches = lineTotalBranches,
                    coveredBranches = lineCoveredBranches
                ))
            }

            classInfos.add(ClassCoverageInfo(
                className = className,
                totalLines = classTotalLines,
                coveredLines = classCoveredLines,
                lineRate = if (classTotalLines > 0) classCoveredLines.toDouble() / classTotalLines else 0.0,
                totalBranches = classTotalBranches,
                coveredBranches = classCoveredBranches,
                branchRate = if (classTotalBranches > 0) classCoveredBranches.toDouble() / classTotalBranches else 0.0,
                lines = lineInfos
            ))
        }

        val totalLines = classInfos.sumOf { it.totalLines }
        val coveredLines = classInfos.sumOf { it.coveredLines }
        val totalBranches = classInfos.sumOf { it.totalBranches }
        val coveredBranches = classInfos.sumOf { it.coveredBranches }

        return CoverageReport(
            timestamp = System.currentTimeMillis(),
            totalClasses = classInfos.size,
            totalLines = totalLines,
            coveredLines = coveredLines,
            lineRate = if (totalLines > 0) coveredLines.toDouble() / totalLines else 0.0,
            totalBranches = totalBranches,
            coveredBranches = coveredBranches,
            branchRate = if (totalBranches > 0) coveredBranches.toDouble() / totalBranches else 0.0,
            classes = classInfos
        )
    }
}
```

### Where coverage files live on disk

The IntelliJ runner writes `.ic` files and JaCoCo writes `.exec` files to the IDE's system cache directory:

- **macOS:** `~/Library/Caches/JetBrains/IntelliJIdea<version>/coverage/`
- **Linux:** `~/.cache/JetBrains/IntelliJIdea<version>/coverage/`
- **Windows:** `%LOCALAPPDATA%\JetBrains\IntelliJIdea<version>\coverage\`

You can also access the file path from the suite: `suite.coverageDataFileName` returns the absolute path to the data file.

### Loading external coverage files

If you have a `.ic` or `.exec` file produced externally (e.g., from a CI build), you can load it:

```kotlin
val manager = CoverageDataManager.getInstance(project)
val runner = CoverageRunner.getInstance(DefaultJavaCoverageRunner::class.java)
val suite = manager.addExternalCoverageSuite(
    file.name,
    file.lastModified(),
    runner,
    DefaultCoverageFileProvider(file.absolutePath)
)
// Activate in IDE (shows gutter marks, Coverage view):
manager.chooseSuitesBundle(CoverageSuitesBundle(suite))
```

---

## 5. Triggering the entire pipeline without user interaction

The automation layer watches for a trigger file, reads its contents, and drives the entire create-config → run-with-coverage → collect-results → write-output pipeline. Three mechanisms work together: a `postStartupActivity` to register listeners at project open, a `BulkFileListener` to detect the trigger file, and an `ExecutionListener` to detect process termination.

### Threading rules you must follow

| Operation | Required thread | API to use |
|-----------|----------------|-----------|
| Read PSI / project model | Any thread | `ReadAction.run { }` |
| Write PSI / VFS model | EDT only | `WriteAction.run { }` |
| Create/modify run configs | EDT | `invokeLater { }` |
| Start execution | EDT | `smartInvokeLater { }` |
| File I/O (java.io) | Any thread | Direct calls |
| Process termination callback | Background thread | `ProcessAdapter` |
| Read coverage results | EDT | `invokeLater { }` after `coverageDataCalculated` |

**`DumbService.getInstance(project).smartInvokeLater { }`** is the safest way to schedule work that needs both the EDT and completed indexing. Use it for anything that touches PSI or run configurations. **`ModalityState.NON_MODAL`** ensures your `invokeLater` block runs only after all modal dialogs close — use this for execution triggers.

### `AutoTestStartupActivity.kt`

```kotlin
package com.example.coverage

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Registered as <postStartupActivity> in plugin.xml.
 * Runs on a background coroutine after project opens.
 */
class AutoTestStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        AutoTestOrchestrator.getInstance(project).initialize()
    }
}
```

### `TestExecutionListener.kt`

```kotlin
package com.example.coverage

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger

/**
 * Registered as an applicationListener in plugin.xml.
 * Detects when our auto-test processes terminate.
 */
class TestExecutionListener : ExecutionListener {

    private val LOG = Logger.getInstance(TestExecutionListener::class.java)

    override fun processStarted(
        executorId: String,
        env: ExecutionEnvironment,
        handler: ProcessHandler
    ) {
        // Only intercept our own auto-test configurations
        if (!env.runProfile.name.startsWith("AutoTest-")) return

        LOG.info("Auto-test process started: ${env.runProfile.name}")
        handler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                LOG.info("Auto-test process terminated (exit=${event.exitCode}): " +
                        env.runProfile.name)
                AutoTestOrchestrator.getInstance(env.project)
                    .onTestProcessTerminated(env, event.exitCode)
            }
        })
    }
}
```

### `AutoTestOrchestrator.kt` — the complete end-to-end service

```kotlin
package com.example.coverage

import com.intellij.coverage.CoverageDataManager
import com.intellij.coverage.CoverageSuiteListener
import com.intellij.coverage.CoverageSuitesBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.execution.runners.ExecutionEnvironment
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
class AutoTestOrchestrator(private val project: Project) : Disposable {

    private val LOG = Logger.getInstance(AutoTestOrchestrator::class.java)
    private val TRIGGER_FILE = ".auto-test-trigger.json"
    private val RESULT_FILE = ".auto-test-results.json"
    private val running = AtomicBoolean(false)
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun initialize() {
        registerFileWatcher()
        registerCoverageListener()
        checkExistingTriggerFile()
        LOG.info("AutoTestOrchestrator initialized for project: ${project.name}")
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 1: Watch for trigger file
    // ─────────────────────────────────────────────────────────

    private fun registerFileWatcher() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    for (event in events) {
                        val isTrigger = when (event) {
                            is VFileCreateEvent -> event.childName == TRIGGER_FILE
                            is VFileContentChangeEvent -> event.file.name == TRIGGER_FILE
                            else -> false
                        }
                        if (isTrigger) {
                            LOG.info("Trigger file detected via VFS event")
                            handleTriggerDetected(event.path)
                        }
                    }
                }
            }
        )
    }

    /**
     * Check on startup if the trigger file already exists.
     * VFS events only fire for changes AFTER the listener is registered.
     */
    private fun checkExistingTriggerFile() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val triggerFile = File(project.basePath, TRIGGER_FILE)
            if (triggerFile.exists()) {
                LOG.info("Found existing trigger file on startup")
                handleTriggerDetected(triggerFile.absolutePath)
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 2: Parse trigger file and launch pipeline
    // ─────────────────────────────────────────────────────────

    /**
     * Trigger JSON format:
     * {
     *   "testScope": "class",          // "class", "method", "package", "pattern"
     *   "testClass": "com.example.MyTest",
     *   "testMethod": "testSomething", // optional, for method scope
     *   "package": "com.example",      // optional, for package scope
     *   "patterns": ["com.example.MyTest,testA"], // optional, for pattern scope
     *   "module": "my-module",         // optional, defaults to first module
     *   "useJaCoCo": false             // optional, defaults to IntelliJ runner
     * }
     */
    data class TriggerParams(
        val testScope: String,
        val testClass: String? = null,
        val testMethod: String? = null,
        val packageName: String? = null,
        val patterns: Set<String>? = null,
        val moduleName: String? = null,
        val useJaCoCo: Boolean = false
    )

    private fun handleTriggerDetected(triggerPath: String) {
        if (!running.compareAndSet(false, true)) {
            LOG.warn("Pipeline already running, ignoring trigger")
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val file = File(triggerPath)
                // Small delay for filesystem sync
                if (!file.exists()) Thread.sleep(200)
                if (!file.exists()) {
                    LOG.warn("Trigger file not found at: $triggerPath")
                    running.set(false)
                    return@executeOnPooledThread
                }

                val content = file.readText()
                val params = parseTrigger(content)

                // Schedule on EDT after indexing completes
                DumbService.getInstance(project).smartInvokeLater {
                    createAndRunWithCoverage(params)
                }
            } catch (e: Exception) {
                LOG.error("Failed to process trigger file", e)
                writeErrorResult(e.message ?: "Unknown error")
                running.set(false)
            }
        }
    }

    private fun parseTrigger(json: String): TriggerParams {
        val obj = JsonParser.parseString(json).asJsonObject
        return TriggerParams(
            testScope = obj.get("testScope")?.asString ?: "class",
            testClass = obj.get("testClass")?.asString,
            testMethod = obj.get("testMethod")?.asString,
            packageName = obj.get("package")?.asString,
            patterns = obj.getAsJsonArray("patterns")
                ?.map { it.asString }?.toSet(),
            moduleName = obj.get("module")?.asString,
            useJaCoCo = obj.get("useJaCoCo")?.asBoolean ?: false
        )
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 3 & 4: Create config and run with coverage
    // ─────────────────────────────────────────────────────────

    private fun createAndRunWithCoverage(params: TriggerParams) {
        // This runs on EDT (scheduled by smartInvokeLater)
        try {
            // Resolve module
            val moduleManager = ModuleManager.getInstance(project)
            val module = if (params.moduleName != null) {
                moduleManager.findModuleByName(params.moduleName)
                    ?: throw IllegalArgumentException("Module '${params.moduleName}' not found")
            } else {
                moduleManager.modules.firstOrNull()
                    ?: throw IllegalStateException("No modules found in project")
            }

            // Create the appropriate run configuration
            val settings = when (params.testScope) {
                "class" -> TestConfigurationFactory.createClassConfig(
                    project, module, params.testClass
                        ?: throw IllegalArgumentException("testClass required for class scope")
                )
                "method" -> TestConfigurationFactory.createMethodConfig(
                    project, module,
                    params.testClass
                        ?: throw IllegalArgumentException("testClass required for method scope"),
                    params.testMethod
                        ?: throw IllegalArgumentException("testMethod required for method scope")
                )
                "package" -> TestConfigurationFactory.createPackageConfig(
                    project, module, params.packageName
                        ?: throw IllegalArgumentException("package required for package scope")
                )
                "pattern" -> TestConfigurationFactory.createPatternConfig(
                    project, module, params.patterns
                        ?: throw IllegalArgumentException("patterns required for pattern scope")
                )
                else -> throw IllegalArgumentException("Unknown testScope: ${params.testScope}")
            }

            // Launch with coverage
            CoverageExecutionRunner.runWithCoverage(project, settings, params.useJaCoCo)
            LOG.info("Pipeline: configuration created and launched with coverage")

        } catch (e: Exception) {
            LOG.error("Failed to create/run test configuration", e)
            writeErrorResult(e.message ?: "Configuration creation failed")
            running.set(false)
        }
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 5: Detect execution completion
    // ─────────────────────────────────────────────────────────

    /**
     * Called by TestExecutionListener when our auto-test process terminates.
     * This fires on a process handler thread (NOT EDT).
     */
    fun onTestProcessTerminated(env: ExecutionEnvironment, exitCode: Int) {
        LOG.info("Test execution complete (exit=$exitCode), waiting for coverage data...")
        // Coverage data loads asynchronously after process termination.
        // The coverageDataCalculated listener (registered below) handles the rest.
        // Store exit code for the results.
        lastExitCode = exitCode
    }

    @Volatile
    private var lastExitCode: Int = -1

    // ─────────────────────────────────────────────────────────
    //  STEP 6: Collect coverage data
    // ─────────────────────────────────────────────────────────

    private fun registerCoverageListener() {
        val manager = CoverageDataManager.getInstance(project)
        manager.addSuiteListener(object : CoverageSuiteListener {

            override fun coverageDataCalculated(suitesBundle: CoverageSuitesBundle) {
                if (!running.get()) return  // Ignore coverage from manual runs

                LOG.info("Coverage data calculated, extracting results...")
                val projectData = suitesBundle.getCoverageData()
                if (projectData == null) {
                    LOG.warn("No ProjectData in CoverageSuitesBundle")
                    writeErrorResult("Coverage data was null")
                    running.set(false)
                    return
                }

                val collector = CoverageResultCollector(project, this@AutoTestOrchestrator)
                val report = collector.extractReport(projectData)

                // STEP 7: Write results
                writeSuccessResult(report)
                cleanup()
                running.set(false)
            }
        }, this)
    }

    // ─────────────────────────────────────────────────────────
    //  STEP 7: Write output and clean up
    // ─────────────────────────────────────────────────────────

    private fun writeSuccessResult(report: CoverageReport) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val output = mapOf(
                    "success" to true,
                    "exitCode" to lastExitCode,
                    "timestamp" to report.timestamp,
                    "summary" to mapOf(
                        "totalClasses" to report.totalClasses,
                        "totalLines" to report.totalLines,
                        "coveredLines" to report.coveredLines,
                        "lineRate" to report.lineRate,
                        "totalBranches" to report.totalBranches,
                        "coveredBranches" to report.coveredBranches,
                        "branchRate" to report.branchRate
                    ),
                    "classes" to report.classes.map { cls ->
                        mapOf(
                            "className" to cls.className,
                            "totalLines" to cls.totalLines,
                            "coveredLines" to cls.coveredLines,
                            "lineRate" to cls.lineRate,
                            "branchRate" to cls.branchRate,
                            "lines" to cls.lines.map { line ->
                                mapOf(
                                    "line" to line.lineNumber,
                                    "hits" to line.hits,
                                    "status" to line.status,
                                    "method" to line.methodSignature
                                )
                            }
                        )
                    }
                )

                val outFile = File(project.basePath, RESULT_FILE)
                outFile.writeText(gson.toJson(output))
                LOG.info("Results written to: ${outFile.absolutePath}")

                // Refresh VFS so the IDE (and external watchers) see the file
                LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(outFile.absolutePath)

            } catch (e: Exception) {
                LOG.error("Failed to write results", e)
            }
        }
    }

    private fun writeErrorResult(errorMessage: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val output = mapOf(
                    "success" to false,
                    "error" to errorMessage,
                    "timestamp" to System.currentTimeMillis()
                )
                val outFile = File(project.basePath, RESULT_FILE)
                outFile.writeText(gson.toJson(output))
                LocalFileSystem.getInstance()
                    .refreshAndFindFileByPath(outFile.absolutePath)
            } catch (e: Exception) {
                LOG.error("Failed to write error result", e)
            }
        }
    }

    private fun cleanup() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val triggerFile = File(project.basePath, TRIGGER_FILE)
                if (triggerFile.exists()) {
                    triggerFile.delete()
                    val parent = LocalFileSystem.getInstance()
                        .findFileByPath(project.basePath!!)
                    parent?.refresh(true, false)
                }
            } catch (e: Exception) {
                LOG.error("Cleanup failed", e)
            }
        }
    }

    override fun dispose() {
        // MessageBus connection auto-disposed via connect(this)
    }

    companion object {
        fun getInstance(project: Project): AutoTestOrchestrator = project.service()
    }
}
```

### How to trigger the pipeline

Drop a JSON file named `.auto-test-trigger.json` into the project root:

```json
{
  "testScope": "class",
  "testClass": "com.example.service.UserServiceTest",
  "module": "my-app.test",
  "useJaCoCo": false
}
```

The plugin detects it, runs `UserServiceTest` with the IntelliJ coverage runner, collects per-line and per-branch results, and writes `.auto-test-results.json` to the project root. The trigger file is deleted after completion.

---

## Common pitfalls and how to avoid them

### The JaCoCo 0% coverage bug with IntelliJ Platform Gradle Plugin

This affects your **plugin's own test coverage in CI**, not the coverage your plugin collects at runtime. The IntelliJ Platform sets a custom system classloader (`com.intellij.util.lang.PathClassLoader`), and the Gradle plugin performs bytecode instrumentation for nullability assertions. Both break JaCoCo's assumptions.

The fix in your plugin project's `build.gradle.kts`:

```kotlin
tasks {
    withType<Test> {
        configure<JacocoTaskExtension> {
            isIncludeNoLocationClasses = true
            excludes = listOf("jdk.internal.*")
        }
    }
    jacocoTestReport {
        // CRITICAL: use instrumented classes, not raw compiled output
        classDirectories.setFrom(instrumentCode)
    }
}
```

Alternatively, use **JetBrains Kover** (`org.jetbrains.kotlinx.kover`) which handles IntelliJ's classloader natively.

### `CoverageEnabledConfiguration.getOrCreate()` is mandatory

If you skip this call before executing with the `CoverageExecutor`, the `CoverageJavaRunConfigurationExtension` won't recognize your configuration as coverage-eligible. The `-javaagent` flag won't be appended, and you'll get **0% coverage with no errors**. This is the single most common cause of "coverage doesn't work from my plugin."

### VFS events for externally created files

`VFileCreateEvent` only fires if IntelliJ's VFS has previously scanned the parent directory (i.e., `VirtualFile.getChildren()` was called on it). For the project root, this is always true. For arbitrary directories, you may need to force a refresh: `LocalFileSystem.getInstance().refreshAndFindFileByPath(parentPath)`. Also note: **never call `VFileEvent.getFile()` on a `VFileCreateEvent`** — it's expensive and may return null. Use `event.path` or `event.childName` instead.

### EDT vs. background thread mistakes

`ExecutionManager.restartRunProfile()` and `RunManager.createConfiguration()` must be called on the EDT. `ProcessAdapter.processTerminated()` fires on a process handler thread. Reading `ProjectData` from `CoverageSuitesBundle.getCoverageData()` is safe on any thread, but the `coverageDataCalculated` listener fires during UI update processing, so keep your handler fast and offload I/O to a pooled thread.

### `ExecutionEnvironmentBuilder.createOrNull()` returning null

This means no registered `ProgramRunner` accepts the combination of your executor ID and run profile. Common causes: the coverage plugin isn't loaded (missing `<depends>com.intellij.coverage</depends>`), or the configuration type isn't a Java-based configuration that the coverage runner recognizes. Always null-check and log.

### Class data mismatch with IntelliJ's coverage runner

IntelliJ's built-in coverage agent instruments classes at load time. If your project uses build tools that also modify bytecode (annotation processors, Lombok, Kotlin compiler plugins), the `.ic` file may reference class structures that differ from source. This manifests as missing lines or incorrect hit counts. JaCoCo is generally more resilient to this because it instruments at the bytecode level independently. If you see mysterious gaps, try switching to the JaCoCo runner.

---

## Conclusion

The five pieces of this puzzle — project setup, run configuration creation, coverage execution, data collection, and headless automation — each use distinct IntelliJ Platform APIs but connect through a clean pipeline. **The two most critical details are calling `CoverageEnabledConfiguration.getOrCreate()` before execution** (without it, coverage silently fails) **and listening for `coverageDataCalculated`** (not `coverageGathered`, which fires too early). The `ProjectData` → `ClassData` → `LineData` hierarchy gives you per-line hit counts, branch coverage via `JumpData`/`SwitchData`, and method attribution — enough to produce coverage reports equivalent to what the IDE's Coverage tool window shows.

The trigger-file approach shown here is deliberately simple and robust: no network sockets, no custom protocols, just a JSON file that the VFS natively detects. For production use, consider adding a debounce mechanism (the `AtomicBoolean` guard handles concurrent triggers), timeout handling for tests that hang, and structured logging to a separate output file for observability.