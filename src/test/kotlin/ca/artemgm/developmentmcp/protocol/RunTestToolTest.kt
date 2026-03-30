package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.module.Module
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.TextContent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class RunTestToolTest {

    private var capturedParams: RunTestTool.ConfigParams? = null
    private var launcherConfigName: String? = null

    private val stubModule = stubModule()
    private val tool = RunTestTool(
        configCreator = { params -> capturedParams = params; "RunTest-stub" },
        executionLauncher = { name, _ ->
            launcherConfigName = name
            ExecutionResult("Total: 1, Passed: 1, Failed: 0", null)
        },
        moduleResolver = { _, _ -> stubModule }
    )

    @Nested
    inner class Schema {

        private val schema = parseSchema(tool.registration())

        @Test
        fun `registration name is run_test`() {
            assertThat(tool.registration().name).isEqualTo("run_test")
        }

        @Test
        fun `scope is required in schema`() {
            assertThat(schemaProperty(schema, "scope")["type"]).isEqualTo("string")
            assertThat(requiredParams()).contains("scope")
        }

        @Test
        fun `target is required in schema`() {
            assertThat(schemaProperty(schema, "target")["type"]).isEqualTo("string")
            assertThat(requiredParams()).contains("target")
        }

        @Test
        fun `moduleName is optional in schema`() {
            assertThat(schemaProperty(schema, "moduleName")["type"]).isEqualTo("string")
            assertThat(requiredParams()).doesNotContain("moduleName")
        }

        @Suppress("UNCHECKED_CAST")
        private fun requiredParams() = schema["required"] as List<String>
    }

    @Nested
    inner class Validation {

        @Test
        fun `unrecognized scope produces error listing valid values`() {
            val result = tool.handle("bogus", "com.example", null)

            assertThat(textOf(result)).contains("package", "class", "method")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `empty target produces error`() {
            val result = tool.handle("class", "", null)

            assertThat(textOf(result)).containsIgnoringCase("target")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `method scope without hash separator produces error`() {
            val result = tool.handle("method", "com.example.MyTest", null)

            assertThat(textOf(result)).contains("#")
            assertThat(result.isError).isTrue()
        }
    }

    @Nested
    inner class ConfigurationAndExecution {

        @Test
        fun `package scope passes PACKAGE scope to config creator`() {
            tool.handle("package", "com.example.tests", null)

            assertThat(capturedParams!!.scope).isEqualTo(RunTestTool.TestScope.PACKAGE)
            assertThat(capturedParams!!.target).isEqualTo("com.example.tests")
        }

        @Test
        fun `class scope passes CLASS scope to config creator`() {
            tool.handle("class", "com.example.MyTest", null)

            assertThat(capturedParams!!.scope).isEqualTo(RunTestTool.TestScope.CLASS)
            assertThat(capturedParams!!.target).isEqualTo("com.example.MyTest")
        }

        @Test
        fun `method scope passes METHOD scope and full target to config creator`() {
            tool.handle("method", "com.example.MyTest#testFoo", null)

            assertThat(capturedParams!!.scope).isEqualTo(RunTestTool.TestScope.METHOD)
            assertThat(capturedParams!!.target).isEqualTo("com.example.MyTest#testFoo")
        }

        @Test
        fun `method scope resolves module using class name before hash`() {
            var resolvedTarget: String? = null
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, _ -> ExecutionResult("ok", null) },
                moduleResolver = { target, _ -> resolvedTarget = target; stubModule }
            )

            tool.handle("method", "com.example.MyTest#testFoo", null)

            assertThat(resolvedTarget).isEqualTo("com.example.MyTest")
        }

        @Test
        fun `module resolution failure produces error`() {
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, _ -> ExecutionResult("ok", null) },
                moduleResolver = { _, _ -> throw IllegalArgumentException("Could not find 'com.example.Missing' in any module") }
            )

            val result = tool.handle("class", "com.example.Missing", null)

            assertThat(textOf(result)).contains("Could not find", "com.example.Missing")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `successful invocation includes test results in response`() {
            val result = tool.handle("class", "com.example.MyTest", null)

            assertThat(textOf(result)).contains("Total: 1, Passed: 1, Failed: 0")
            assertThat(result.isError).isFalse()
        }

        @Test
        fun `execution launcher failure produces error with reason`() {
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, _ -> throw RuntimeException("connection refused") },
                moduleResolver = { _, _ -> stubModule }
            )

            val result = tool.handle("class", "com.example.MyTest", null)

            assertThat(textOf(result)).contains("connection refused")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `injected dependencies are used instead of real APIs`() {
            tool.handle("class", "com.example.MyTest", null)

            assertThat(capturedParams).isNotNull
            assertThat(launcherConfigName).isEqualTo("RunTest-stub")
        }
    }

    @Nested
    inner class ModuleName {

        private var capturedModuleName: String? = null
        private val tool = RunTestTool(
            configCreator = { "stub" },
            executionLauncher = { _, _ -> ExecutionResult("ok", null) },
            moduleResolver = { _, moduleName -> capturedModuleName = moduleName; stubModule }
        )

        @Test
        fun `moduleName is forwarded to module resolver`() {
            tool.handle("class", "com.example.MyTest", "my-module")
            assertThat(capturedModuleName).isEqualTo("my-module")
        }

        @Test
        fun `null moduleName is forwarded when omitted`() {
            tool.handle("class", "com.example.MyTest", null)
            assertThat(capturedModuleName).isNull()
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
                executionLauncher = { _, _ -> ExecutionResult("Total: 1, Passed: 1, Failed: 0", coverage) },
                moduleResolver = { _, _ -> stubModule }
            )

            val result = tool.handle("class", "com.example.MyTest", null)

            val text = textOf(result)
            assertThat(text).contains("Coverage for package 'com.example':")
            assertThat(text).contains("Lines:    75/100 (75.0%)")
            assertThat(text).contains("Branches: 15/20 (75.0%)")
            assertThat(text).contains("com.example.Foo: 50/60 lines (83.3%)")
            assertThat(text).contains("com.example.Bar: 25/40 lines (62.5%)")
        }

        @Test
        fun `response omits coverage section when execution returns null coverage`() {
            val result = tool.handle("class", "com.example.MyTest", null)

            val text = textOf(result)
            assertThat(text).doesNotContain("Coverage")
            assertThat(text).contains("Total: 1, Passed: 1, Failed: 0")
        }

        @Test
        fun `class scope derives package from class FQN for coverage`() {
            assertPackagePassedToLauncher("class", "com.example.service.MyTest", "com.example.service")
        }

        @Test
        fun `method scope derives package from class FQN before hash`() {
            assertPackagePassedToLauncher("method", "com.example.service.MyTest#testFoo", "com.example.service")
        }

        @Test
        fun `package scope passes package name directly for coverage`() {
            assertPackagePassedToLauncher("package", "com.example.tests", "com.example.tests")
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
                executionLauncher = { _, _ -> ExecutionResult("ok", coverage) },
                moduleResolver = { _, _ -> stubModule }
            )
            return textOf(tool.handle("class", "com.example.MyTest", null))
        }

        private fun assertPackagePassedToLauncher(scope: String, target: String, expectedPackage: String) {
            var capturedPackage: String? = null
            val tool = RunTestTool(
                configCreator = { "stub" },
                executionLauncher = { _, pkg -> capturedPackage = pkg; ExecutionResult("ok", null) },
                moduleResolver = { _, _ -> stubModule }
            )

            tool.handle(scope, target, null)

            assertThat(capturedPackage).isEqualTo(expectedPackage)
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

private fun textOf(result: CallToolResult) =
    (result.content.first() as TextContent).text

private fun stubModule(): Module = Proxy.newProxyInstance(
    Module::class.java.classLoader,
    arrayOf(Module::class.java)
) { _, _, _ -> null } as Module
