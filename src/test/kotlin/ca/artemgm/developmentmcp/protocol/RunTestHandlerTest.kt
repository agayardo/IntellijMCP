package ca.artemgm.developmentmcp.protocol

import ca.artemgm.developmentmcp.protocol.RunTestTool.TestScope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RunTestHandlerTest {

    private val stubModule = stubModule()
    private val stubContext = ResolvedContext(stubProject("ProjectA"), stubModule)
    private var capturedTargets: List<String>? = null

    private val handler = RunTestHandler(
        contextResolver = stubContextResolver(stubContext),
        toolFactory = { ctx ->
            RunTestTool(
                configCreator = { params -> capturedTargets = params.targets; "RunTest-stub" },
                executionLauncher = { _, _ -> ExecutionResult("Total: 1, Passed: 1, Failed: 0", false, null) },
                filePathResolver = { null },
                classesInFile = { emptySet() },
                sourceReader = { null },
                module = ctx.module
            )
        },
    )

    @Nested
    inner class Schema {

        private val schema = parseSchema(handler.registration())

        @Test
        fun `registration name is run_test`() {
            assertThat(handler.registration().name).isEqualTo("run_test")
        }

        @Test
        fun `scope is required in schema`() {
            assertThat(schemaProperty(schema, "scope")["type"]).isEqualTo("string")
            assertThat(requiredParams(schema)).contains("scope")
        }

        @Test
        fun `targets is required array of strings in schema`() {
            assertThat(schemaProperty(schema, "targets")["type"]).isEqualTo("array")
            @Suppress("UNCHECKED_CAST")
            val items = schemaProperty(schema, "targets")["items"] as Map<String, Any?>
            assertThat(items["type"]).isEqualTo("string")
            assertThat(requiredParams(schema)).contains("targets")
        }

        @Test
        fun `moduleName is optional in schema`() {
            assertOptionalStringParam(schema, "moduleName")
        }

        @Test
        fun `coverageFor is optional`() {
            assertThat(schemaProperty(schema, "coverageFor")["type"]).isEqualTo("array")
            assertThat(requiredParams(schema)).doesNotContain("coverageFor")
        }

    }

    @Nested
    inner class Validation {

        @Test
        fun `unrecognized scope does not call context resolver`() {
            var resolverCalled = false
            val handler = handlerWithResolver(onResolve = { _, _ -> resolverCalled = true })

            val result = handler.handle("bogus", listOf("com.example"), null)

            assertThat(result.isError).isTrue()
            assertThat(resolverCalled).isFalse()
        }

        @Test
        fun `empty targets list produces error`() {
            val result = handler.handle("class", emptyList(), null)

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).containsIgnoringCase("empty")
        }

        @Test
        fun `empty target string produces error`() {
            var resolverCalled = false
            val handler = handlerWithResolver(onResolve = { _, _ -> resolverCalled = true })

            val result = handler.handle("class", listOf(""), null)

            assertThat(result.isError).isTrue()
            assertThat(resolverCalled).isFalse()
        }

        @Test
        fun `method scope without hash does not call context resolver`() {
            var resolverCalled = false
            val handler = handlerWithResolver(onResolve = { _, _ -> resolverCalled = true })

            val result = handler.handle("method", listOf("com.example.MyTest"), null)

            assertThat(result.isError).isTrue()
            assertThat(resolverCalled).isFalse()
        }

        @Test
        fun `invalid target among multiple targets produces error before execution`() {
            val result = handler.handle("method", listOf("com.example.A#foo", "com.example.B"), null)

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).contains("#")
        }
    }

    @Nested
    inner class ContextResolution {

        @Test
        fun `method scope resolves using class name before hash`() {
            var resolvedTarget: String? = null
            val handler = handlerWithResolver(onResolve = { target, _ -> resolvedTarget = target })

            handler.handle("method", listOf("com.example.MyTest#testFoo"), null)

            assertThat(resolvedTarget).isEqualTo("com.example.MyTest")
        }

        @Test
        fun `moduleName is forwarded to context resolver`() {
            var resolvedModuleName: String? = null
            val handler = handlerWithResolver(onResolve = { _, moduleName -> resolvedModuleName = moduleName })

            handler.handle("class", listOf("com.example.MyTest"), "my-module")

            assertThat(resolvedModuleName).isEqualTo("my-module")
        }

        @Test
        fun `null moduleName is forwarded when omitted`() {
            var resolvedModuleName: String? = "sentinel"
            val handler = handlerWithResolver(onResolve = { _, moduleName -> resolvedModuleName = moduleName })

            handler.handle("class", listOf("com.example.MyTest"), null)

            assertThat(resolvedModuleName).isNull()
        }

        @Test
        fun `resolution failure produces error`() {
            val handler = RunTestHandler(
                contextResolver = failingContextResolver("Could not find 'com.example.Missing' in any open project"),
                toolFactory = { stubTool(it.module) },
            )

            val result = handler.handle("class", listOf("com.example.Missing"), null)

            assertThat(textOf(result)).contains("Could not find", "com.example.Missing")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `successful single-target invocation delegates to tool and returns result`() {
            val result = handler.handle("class", listOf("com.example.MyTest"), null)

            assertThat(textOf(result)).contains("Total: 1, Passed: 1, Failed: 0")
            assertThat(result.isError).isFalse()
        }
    }

    @Nested
    inner class MultiTarget {

        @Test
        fun `all targets are passed to a single tool invocation`() {
            handler.handle("class", listOf("com.example.FooTest", "com.example.BarTest"), null)

            assertThat(capturedTargets).containsExactly("com.example.FooTest", "com.example.BarTest")
        }
    }

    private fun handlerWithResolver(onResolve: (String, String?) -> Unit) = RunTestHandler(
        contextResolver = stubContextResolver(stubContext, onResolve = onResolve),
        toolFactory = { stubTool(it.module) },
    )
}

private fun stubContextResolver(
    result: ResolvedContext,
    onResolve: (String, String?) -> Unit = { _, _ -> }
): (String, String?) -> ResolvedContext = { target, moduleName ->
    onResolve(target, moduleName)
    result
}

private fun failingContextResolver(message: String): (String, String?) -> ResolvedContext =
    { _, _ -> throw IllegalArgumentException(message) }
