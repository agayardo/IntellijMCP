package ca.artemgm.developmentmcp.protocol

import ca.artemgm.developmentmcp.protocol.RunTestTool.TestScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RunTestToolTest {

    private var capturedParams: RunTestTool.ConfigParams? = null
    private var launcherConfigName: String? = null
    private var launcherPackageNames: Set<String>? = null

    private val stubModule = stubModule()
    private val tool = RunTestTool(
        configCreator = { params -> capturedParams = params; "RunTest-stub" },
        executionLauncher = { name, pkgs ->
            launcherConfigName = name
            launcherPackageNames = pkgs
            ExecutionResult("Total: 1, Passed: 1, Failed: 0", false, null)
        },
        sourceReader = { null },
        module = stubModule
    )

    @Nested
    inner class ConfigurationAndExecution {

        @Test
        fun `package scope passes PACKAGE scope to config creator`() {
            tool.handle(TestScope.PACKAGE, listOf("com.example.tests"))

            assertThat(capturedParams!!.scope).isEqualTo(TestScope.PACKAGE)
            assertThat(capturedParams!!.targets).containsExactly("com.example.tests")
        }

        @Test
        fun `class scope passes CLASS scope to config creator`() {
            tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(capturedParams!!.scope).isEqualTo(TestScope.CLASS)
            assertThat(capturedParams!!.targets).containsExactly("com.example.MyTest")
        }

        @Test
        fun `method scope passes METHOD scope and full target to config creator`() {
            tool.handle(TestScope.METHOD, listOf("com.example.MyTest#testFoo"))

            assertThat(capturedParams!!.scope).isEqualTo(TestScope.METHOD)
            assertThat(capturedParams!!.targets).containsExactly("com.example.MyTest#testFoo")
        }

        @Test
        fun `multiple targets are passed through to config creator`() {
            tool.handle(TestScope.CLASS, listOf("com.example.FooTest", "com.example.BarTest"))

            assertThat(capturedParams!!.targets).containsExactly("com.example.FooTest", "com.example.BarTest")
        }

        @Test
        fun `successful invocation includes test results in response`() {
            val result = tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(textOf(result)).contains("Total: 1, Passed: 1, Failed: 0")
            assertThat(result.isError).isFalse()
        }

        @Test
        fun `execution launcher failure produces error with reason`() {
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, _ -> throw RuntimeException("connection refused") },
                sourceReader = { null },
                module = stubModule
            )

            val result = tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(textOf(result)).contains("connection refused")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `injected dependencies are used instead of real APIs`() {
            tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(capturedParams).isNotNull
            assertThat(launcherConfigName).isEqualTo("RunTest-stub")
        }
    }

    @Nested
    inner class FailureFlag {

        @Test
        fun `failed execution result sets isError on tool response`() {
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, _ -> ExecutionResult("Total: 1, Passed: 0, Failed: 1", true, null) },
                sourceReader = { null },
                module = stubModule
            )

            val result = tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(result.isError).isTrue()
        }

        @Test
        fun `successful execution result does not set isError`() {
            val result = tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            assertThat(result.isError).isFalse()
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
                    ClassCoverage("com.example.Foo", 60, 50, 10, 8),
                    ClassCoverage("com.example.Bar", 40, 25, 10, 7)
                )
            )
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, _ -> ExecutionResult("Total: 1, Passed: 1, Failed: 0", false, coverage) },
                sourceReader = { null },
                module = stubModule
            )

            val result = tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            val text = textOf(result)
            assertThat(text).contains("Coverage for package 'com.example':")
            assertThat(text).contains("Lines:    75/100 (75.0%)")
            assertThat(text).contains("Branches: 15/20 (75.0%)")
            assertThat(text).contains("com.example.Foo: 50/60 lines (83.3%)")
            assertThat(text).contains("com.example.Bar: 25/40 lines (62.5%)")
        }

        @Test
        fun `response omits coverage section when execution returns null coverage`() {
            val result = tool.handle(TestScope.CLASS, listOf("com.example.MyTest"))

            val text = textOf(result)
            assertThat(text).doesNotContain("Coverage")
            assertThat(text).contains("Total: 1, Passed: 1, Failed: 0")
        }

        @Test
        fun `class scope derives package from class FQN for coverage`() {
            tool.handle(TestScope.CLASS, listOf("com.example.service.MyTest"))

            assertThat(launcherPackageNames).containsExactly("com.example.service")
        }

        @Test
        fun `method scope derives package from class FQN before hash`() {
            tool.handle(TestScope.METHOD, listOf("com.example.service.MyTest#testFoo"))

            assertThat(launcherPackageNames).containsExactly("com.example.service")
        }

        @Test
        fun `package scope passes package name directly for coverage`() {
            tool.handle(TestScope.PACKAGE, listOf("com.example.tests"))

            assertThat(launcherPackageNames).containsExactly("com.example.tests")
        }

        @Test
        fun `multiple targets from different packages produce merged package set`() {
            tool.handle(TestScope.CLASS, listOf("com.example.a.FooTest", "com.example.b.BarTest"))

            assertThat(launcherPackageNames).containsExactlyInAnyOrder("com.example.a", "com.example.b")
        }

        @Test
        fun `multiple targets from same package deduplicate package names`() {
            tool.handle(TestScope.CLASS, listOf("com.example.FooTest", "com.example.BarTest"))

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
        fun `empty class list omits per-class section`() {
            val coverage = PackageCoverage("com.example", 10, 5, 4, 2, emptyList())
            val text = runWithCoverage(coverage)

            assertThat(text).doesNotContain("Per class:")
        }

        private fun runWithCoverage(coverage: PackageCoverage): String {
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, _ -> ExecutionResult("ok", false, coverage) },
                sourceReader = { null },
                module = stubModule
            )
            return textOf(tool.handle(TestScope.CLASS, listOf("com.example.MyTest")))
        }
    }

    @Nested
    inner class UncoveredLines {

        private fun toolWithCoverage(
            coverage: PackageCoverage,
            sourceReader: (String) -> List<String>? = { null }
        ) = RunTestTool(
            configCreator = { "stub" },
            executionLauncher = { _, _ -> ExecutionResult("ok", false, coverage) },
            sourceReader = sourceReader,
            module = stubModule
        )

        @Test
        fun `coverageFor includes uncovered lines section in response`() {
            val coverage = PackageCoverage(
                "com.example", 5, 3, 0, 0,
                listOf(ClassCoverage("com.example.Foo", 5, 3, 0, 0, listOf(4, 5)))
            )
            val tool = toolWithCoverage(coverage) {
                listOf("package com.example", "", "class Foo {", "    fun a() {}", "    fun b() {}", "}")
            }

            val text = textOf(tool.handle(TestScope.CLASS, listOf("com.example.FooTest"), listOf("com\\.example\\.Foo")))

            assertThat(text).contains("Uncovered lines in com.example.Foo:")
            assertThat(text).contains("4:     fun a() {}")
            assertThat(text).contains("5:     fun b() {}")
        }

        @Test
        fun `null coverageFor omits uncovered lines section`() {
            val coverage = PackageCoverage(
                "com.example", 5, 3, 0, 0,
                listOf(ClassCoverage("com.example.Foo", 5, 3, 0, 0, listOf(4)))
            )
            val tool = toolWithCoverage(coverage) { listOf("a", "b", "c", "d") }

            val text = textOf(tool.handle(TestScope.CLASS, listOf("com.example.FooTest")))

            assertThat(text).doesNotContain("Uncovered lines")
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
