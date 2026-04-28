package ca.artemgm.developmentmcp.protocol

import ca.artemgm.developmentmcp.protocol.RunTestTool.TestScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RunTestToolTest {

    private var capturedParams: RunTestTool.ConfigParams? = null
    private var launcherConfigName: String? = null
    private var launcherPackageNames: Set<String>? = null
    private var callOrder = mutableListOf<String>()

    private val stubModule = stubModule()
    private val tool = RunTestTool(
        configCreator = { params -> callOrder += "configCreator"; capturedParams = params; "RunTest-stub" },
        executionLauncher = { name, pkgs ->
            callOrder += "executionLauncher"
            launcherConfigName = name
            launcherPackageNames = pkgs
            ExecutionResult("Total: 1, Passed: 1, Failed: 0", false, null, "")
        },
        filePathResolver = { null },
        classesInFile = { emptySet() },
        sourceReader = { null },
        module = stubModule,
        waitForSmartMode = { callOrder += "waitForSmartMode" },
    )

    private fun stubTool(
        executionLauncher: (String, Set<String>) -> ExecutionResult = { _, _ ->
            ExecutionResult("Total: 1, Passed: 1, Failed: 0", false, null, "")
        },
        filePathResolver: (String) -> String? = { null },
        classesInFile: (String) -> Set<String> = { emptySet() },
        sourceReader: (String) -> List<String>? = { null }
    ) = RunTestTool(
        configCreator = { "stub" },
        executionLauncher = executionLauncher,
        filePathResolver = filePathResolver,
        classesInFile = classesInFile,
        sourceReader = sourceReader,
        module = stubModule,
        waitForSmartMode = {},
    )

    private fun RunTestTool.runSilently(
        scope: RunTestTool.TestScope,
        targets: List<String>,
        coverageFor: List<String>? = null
    ) = handle(scope, targets, coverageFor, outputLines = 0, outputFilter = Regex(".*"))

    @Nested
    inner class ConfigurationAndExecution {

        @Test
        fun `package scope passes PACKAGE scope to config creator`() {
            tool.runSilently(TestScope.PACKAGE, listOf("com.example.tests"))

            assertThat(capturedParams!!.scope).isEqualTo(TestScope.PACKAGE)
            assertThat(capturedParams!!.targets).containsExactly("com.example.tests")
        }

        @Test
        fun `class scope passes CLASS scope to config creator`() {
            tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(capturedParams!!.scope).isEqualTo(TestScope.CLASS)
            assertThat(capturedParams!!.targets).containsExactly("com.example.MyTest")
        }

        @Test
        fun `method scope passes METHOD scope and full target to config creator`() {
            tool.runSilently(TestScope.METHOD, listOf("com.example.MyTest#testFoo"))

            assertThat(capturedParams!!.scope).isEqualTo(TestScope.METHOD)
            assertThat(capturedParams!!.targets).containsExactly("com.example.MyTest#testFoo")
        }

        @Test
        fun `multiple targets are passed through to config creator`() {
            tool.runSilently(TestScope.CLASS, listOf("com.example.FooTest", "com.example.BarTest"))

            assertThat(capturedParams!!.targets).containsExactly("com.example.FooTest", "com.example.BarTest")
        }

        @Test
        fun `successful invocation includes test results in response`() {
            val result = tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(textOf(result)).contains("Total: 1, Passed: 1, Failed: 0")
            assertThat(result.isError).isFalse()
        }

        @Test
        fun `execution launcher failure produces error with reason`() {
            val tool = stubTool(
                executionLauncher = { _, _ -> throw RuntimeException("connection refused") }
            )

            val result = tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(textOf(result)).contains("connection refused")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `injected dependencies are used instead of real APIs`() {
            tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(capturedParams).isNotNull
            assertThat(launcherConfigName).isEqualTo("RunTest-stub")
        }
    }

    @Nested
    inner class FailureFlag {

        @Test
        fun `failed execution result sets isError on tool response`() {
            val tool = stubTool(
                executionLauncher = { _, _ -> ExecutionResult("Total: 1, Passed: 0, Failed: 1", true, null, "") }
            )

            val result = tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(result.isError).isTrue()
        }

        @Test
        fun `successful execution result does not set isError`() {
            val result = tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(result.isError).isFalse()
        }
    }

    @Nested
    inner class SmartModeOrdering {

        @Test
        fun `waitForSmartMode called before config creation and execution`() {
            tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(callOrder.first()).isEqualTo("waitForSmartMode")
        }
    }

    @Nested
    inner class Coverage {

        @Test
        fun `response includes coverage when execution returns coverage data`() {
            val coverage = PackageCoverage(
                packageName = "com.example",
                totalLines = 100,
                coveredLines = 75,
                totalBranches = 20,
                coveredBranches = 15,
                classCoverages = listOf(
                    ClassCoverage("com.example.Foo", 60, 50, 10, 8,
                        coveredLineNumbers = (1..50).toList(),
                        uncoveredLineNumbers = (51..60).toList()),
                    ClassCoverage("com.example.Bar", 40, 25, 10, 7,
                        coveredLineNumbers = (1..25).toList(),
                        uncoveredLineNumbers = (26..40).toList())
                )
            )
            val tool = stubTool(
                executionLauncher = { _, _ -> ExecutionResult("Total: 1, Passed: 1, Failed: 0", false, coverage, "") },
                filePathResolver = { className ->
                    when (className) {
                        "com.example.Foo" -> "src/main/kotlin/com/example/Foo.kt"
                        "com.example.Bar" -> "src/main/kotlin/com/example/Bar.kt"
                        else -> null
                    }
                }
            )

            val result = tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            val text = textOf(result)
            assertThat(text).contains("Coverage for package 'com.example':")
            assertThat(text).contains("Lines:    75/100 (75.0%)")
            assertThat(text).contains("Branches: 15/20 (75.0%)")
            assertThat(text).contains("Per file:")
            assertThat(text).contains("src/main/kotlin/com/example/Foo.kt: 50/60 lines (83.3%)")
            assertThat(text).contains("src/main/kotlin/com/example/Bar.kt: 25/40 lines (62.5%)")
        }

        @Test
        fun `response omits coverage section when execution returns null coverage`() {
            val result = tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest"))

            val text = textOf(result)
            assertThat(text).doesNotContain("Coverage")
            assertThat(text).contains("Total: 1, Passed: 1, Failed: 0")
        }

        @Test
        fun `class scope derives package from class FQN for coverage`() {
            tool.runSilently(TestScope.CLASS, listOf("com.example.service.MyTest"))

            assertThat(launcherPackageNames).containsExactly("com.example.service")
        }

        @Test
        fun `method scope derives package from class FQN before hash`() {
            tool.runSilently(TestScope.METHOD, listOf("com.example.service.MyTest#testFoo"))

            assertThat(launcherPackageNames).containsExactly("com.example.service")
        }

        @Test
        fun `package scope passes package name directly for coverage`() {
            tool.runSilently(TestScope.PACKAGE, listOf("com.example.tests"))

            assertThat(launcherPackageNames).containsExactly("com.example.tests")
        }

        @Test
        fun `multiple targets from different packages produce merged package set`() {
            tool.runSilently(TestScope.CLASS, listOf("com.example.a.FooTest", "com.example.b.BarTest"))

            assertThat(launcherPackageNames).containsExactlyInAnyOrder("com.example.a", "com.example.b")
        }

        @Test
        fun `multiple targets from same package deduplicate package names`() {
            tool.runSilently(TestScope.CLASS, listOf("com.example.FooTest", "com.example.BarTest"))

            assertThat(launcherPackageNames).containsExactly("com.example")
        }

        @Test
        fun `zero lines and branches produce 0 percent coverage`() {
            val coverage = PackageCoverage("com.example", 0, 0, 0, 0, emptyList())
            val text = runWithCoverage(coverage)

            assertThat(text).contains("Lines:    0/0 (0.0%)")
            assertThat(text).contains("Branches: 0/0 (0.0%)")
        }

        @Test
        fun `empty class list omits per-file section`() {
            val coverage = PackageCoverage("com.example", 10, 5, 4, 2, emptyList())
            val text = runWithCoverage(coverage)

            assertThat(text).doesNotContain("Per file:")
        }

        private fun runWithCoverage(coverage: PackageCoverage): String {
            val tool = stubTool(
                executionLauncher = { _, _ -> ExecutionResult("ok", false, coverage, "") }
            )
            return textOf(tool.runSilently(TestScope.CLASS, listOf("com.example.MyTest")))
        }
    }

    @Nested
    inner class UncoveredLines {

        private val fooAndFooKt: (String) -> Set<String> = { setOf("com.example.Foo", "com.example.FooKt") }

        private fun toolReturning(
            coverage: PackageCoverage,
            source: List<String>?,
            classesInFile: (String) -> Set<String>
        ) = stubTool(
            executionLauncher = { _, _ -> ExecutionResult("ok", false, coverage, "") },
            filePathResolver = { "src/main/kotlin/com/example/Foo.kt" },
            classesInFile = classesInFile,
            sourceReader = { source }
        )

        private fun runWithCoverageFor(
            coverage: PackageCoverage,
            source: List<String>?,
            classesInFile: (String) -> Set<String>
        ): String {
            val tool = toolReturning(coverage, source, classesInFile)
            return textOf(tool.runSilently(TestScope.CLASS, listOf("com.example.FooTest"), listOf("**/Foo.kt")))
        }

        @Test
        fun `coverageFor includes uncovered lines section in response`() {
            val coverage = PackageCoverage(
                "com.example", 5, 3, 0, 0,
                listOf(ClassCoverage("com.example.Foo", 5, 3, 0, 0, uncoveredLineNumbers = listOf(4, 5)))
            )
            val source = listOf("package com.example", "", "class Foo {", "    fun a() {}", "    fun b() {}", "}")
            val tool = toolReturning(coverage, source, { emptySet() })

            val text = textOf(tool.runSilently(TestScope.CLASS, listOf("com.example.FooTest"), listOf("**/Foo.kt")))

            assertThat(text).contains("Uncovered lines in src/main/kotlin/com/example/Foo.kt:")
            assertThat(text).contains("4:     fun a() {}")
            assertThat(text).contains("5:     fun b() {}")
        }

        @Test
        fun `null coverageFor omits uncovered lines section`() {
            val coverage = PackageCoverage(
                "com.example", 5, 3, 0, 0,
                listOf(ClassCoverage("com.example.Foo", 5, 3, 0, 0,
                    coveredLineNumbers = listOf(1, 2, 3), uncoveredLineNumbers = listOf(4)))
            )
            val tool = toolReturning(coverage, listOf("a", "b", "c", "d"), { emptySet() })

            val text = textOf(tool.runSilently(TestScope.CLASS, listOf("com.example.FooTest")))

            assertThat(text).doesNotContain("Uncovered lines")
        }

        @Test
        fun `unloaded class adds heuristic uncovered lines while preserving engine data`() {
            val coverage = PackageCoverage(
                "com.example", 3, 2, 0, 0,
                listOf(ClassCoverage("com.example.Foo", 3, 2, 0, 0,
                    coveredLineNumbers = listOf(4, 5), uncoveredLineNumbers = listOf(6)))
            )
            val source = listOf(
                "package com.example",   // 1 - not code
                "",                      // 2 - not code
                "class Foo {",           // 3 - code, not in engine → heuristic
                "    fun a() = 1",       // 4 - engine covered
                "    fun b() = 2",       // 5 - engine covered
                "    fun c() = 3",       // 6 - engine uncovered
                "}",                     // 7 - not code
                "fun topLevel() = 42"    // 8 - code, not in engine → heuristic
            )

            val text = runWithCoverageFor(coverage, source, fooAndFooKt)

            assertThat(text).contains("Foo.kt: 2/5 lines")
            assertThat(text).contains("Uncovered lines in src/main/kotlin/com/example/Foo.kt:")
            assertThat(text).contains("3: class Foo {")
            assertThat(text).contains("6:     fun c() = 3")
            assertThat(text).contains("8: fun topLevel() = 42")
        }

        @Test
        fun `all classes loaded uses exact engine numbers without heuristic`() {
            val coverage = PackageCoverage(
                "com.example", 6, 4, 0, 0,
                listOf(
                    ClassCoverage("com.example.Foo", 3, 2, 0, 0,
                        coveredLineNumbers = listOf(4, 5), uncoveredLineNumbers = listOf(6)),
                    ClassCoverage("com.example.FooKt", 3, 2, 0, 0,
                        coveredLineNumbers = listOf(8, 9), uncoveredLineNumbers = listOf(10))
                )
            )

            val text = runWithCoverageFor(coverage, null, fooAndFooKt)

            assertThat(text).contains("Foo.kt: 4/6 lines")
        }
    }

    @Nested
    inner class TestOutput {

        private val matchAll = Regex(".*")

        private fun responseWithConsoleOutput(
            consoleOutput: String,
            outputLines: Int = RunTestTool.DEFAULT_OUTPUT_LINES,
            outputFilter: Regex = RunTestTool.DEFAULT_OUTPUT_FILTER
        ): String {
            val tool = stubTool(
                executionLauncher = { _, _ -> ExecutionResult("ok", false, null, consoleOutput) }
            )
            return textOf(tool.handle(TestScope.CLASS, listOf("com.example.MyTest"), outputLines = outputLines, outputFilter = outputFilter))
        }

        @Test
        fun `response includes last N matching lines of test output`() {
            val text = responseWithConsoleOutput(
                consoleOutput = (1..10).joinToString("\n") { "line $it" },
                outputLines = 3,
                outputFilter = matchAll
            )

            assertThat(text).contains("line 8\nline 9\nline 10")
        }

        @Test
        fun `outputLines zero suppresses test output`() {
            val text = responseWithConsoleOutput("some output", outputLines = 0, outputFilter = matchAll)

            assertThat(text).doesNotContain("Test output")
            assertThat(text).doesNotContain("some output")
        }

        @Test
        fun `empty console output omits section even with positive outputLines`() {
            val text = responseWithConsoleOutput("", outputLines = 100, outputFilter = matchAll)

            assertThat(text).doesNotContain("Test output")
        }

        @Test
        fun `default filter excludes lines not matching TEST DEBUG`() {
            val text = responseWithConsoleOutput("noise\nTEST DEBUG: x=1\nmore noise")

            assertThat(text).contains("TEST DEBUG: x=1")
            assertThat(text).doesNotContain("noise")
        }

        @Test
        fun `default filter produces no output section when nothing matches`() {
            assertThat(responseWithConsoleOutput("no match here")).doesNotContain("Test output")
        }

        @Test
        fun `custom filter selects only matching lines`() {
            val text = responseWithConsoleOutput(
                "##teamcity[stuff]\nSTDERR: hello\n##teamcity[more]",
                outputFilter = Regex("STDERR")
            )

            assertThat(text).contains("STDERR: hello")
            assertThat(text).doesNotContain("teamcity")
        }
    }
}

class GradleTestTaskNameTest {

    @Test
    fun `root project produces colon-scoped task`() {
        assertThat(gradleTestTaskName("DevelopmentMcp")).isEqualTo(":test")
    }

    @Test
    fun `subproject produces fully qualified task path`() {
        assertThat(gradleTestTaskName(":protocol-shared")).isEqualTo(":protocol-shared:test")
    }

    @Test
    fun `nested subproject produces fully qualified task path`() {
        assertThat(gradleTestTaskName(":parent:child")).isEqualTo(":parent:child:test")
    }
}
