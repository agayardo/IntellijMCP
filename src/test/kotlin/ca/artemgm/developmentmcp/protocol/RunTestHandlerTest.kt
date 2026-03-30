package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RunTestHandlerTest {

    private val stubModule = stubModule()
    private val stubContext = ResolvedContext(stubProject("ProjectA"), stubModule)
    private var capturedContext: ResolvedContext? = null

    private val handler = RunTestHandler(
        contextResolver = stubContextResolver(stubContext),
        toolFactory = { ctx ->
            capturedContext = ctx
            RunTestTool(
                configCreator = { "RunTest-stub" },
                executionLauncher = { _, _ -> ExecutionResult("Total: 1, Passed: 1, Failed: 0", false, null) },
                module = ctx.module
            )
        }
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
        fun `unrecognized scope does not call context resolver`() {
            var resolverCalled = false
            val handler = handlerWithResolver(onResolve = { _, _ -> resolverCalled = true })

            val result = handler.handle("bogus", "com.example", null)

            assertThat(result.isError).isTrue()
            assertThat(resolverCalled).isFalse()
        }

        @Test
        fun `empty target does not call context resolver`() {
            var resolverCalled = false
            val handler = handlerWithResolver(onResolve = { _, _ -> resolverCalled = true })

            val result = handler.handle("class", "", null)

            assertThat(result.isError).isTrue()
            assertThat(resolverCalled).isFalse()
        }

        @Test
        fun `method scope without hash does not call context resolver`() {
            var resolverCalled = false
            val handler = handlerWithResolver(onResolve = { _, _ -> resolverCalled = true })

            val result = handler.handle("method", "com.example.MyTest", null)

            assertThat(result.isError).isTrue()
            assertThat(resolverCalled).isFalse()
        }
    }

    @Nested
    inner class ContextResolution {

        @Test
        fun `resolved context is passed to tool factory`() {
            handler.handle("class", "com.example.MyTest", null)

            assertThat(capturedContext).isSameAs(stubContext)
        }

        @Test
        fun `method scope resolves using class name before hash`() {
            var resolvedTarget: String? = null
            val handler = handlerWithResolver(onResolve = { target, _ -> resolvedTarget = target })

            handler.handle("method", "com.example.MyTest#testFoo", null)

            assertThat(resolvedTarget).isEqualTo("com.example.MyTest")
        }

        @Test
        fun `moduleName is forwarded to context resolver`() {
            var resolvedModuleName: String? = null
            val handler = handlerWithResolver(onResolve = { _, moduleName -> resolvedModuleName = moduleName })

            handler.handle("class", "com.example.MyTest", "my-module")

            assertThat(resolvedModuleName).isEqualTo("my-module")
        }

        @Test
        fun `null moduleName is forwarded when omitted`() {
            var resolvedModuleName: String? = "sentinel"
            val handler = handlerWithResolver(onResolve = { _, moduleName -> resolvedModuleName = moduleName })

            handler.handle("class", "com.example.MyTest", null)

            assertThat(resolvedModuleName).isNull()
        }

        @Test
        fun `resolution failure produces error`() {
            val handler = RunTestHandler(
                contextResolver = failingContextResolver("Could not find 'com.example.Missing' in any open project"),
                toolFactory = { stubTool(it.module) }
            )

            val result = handler.handle("class", "com.example.Missing", null)

            assertThat(textOf(result)).contains("Could not find", "com.example.Missing")
            assertThat(result.isError).isTrue()
        }

        @Test
        fun `successful invocation delegates to tool and returns result`() {
            val result = handler.handle("class", "com.example.MyTest", null)

            assertThat(textOf(result)).contains("Total: 1, Passed: 1, Failed: 0")
            assertThat(result.isError).isFalse()
        }
    }

    private fun handlerWithResolver(onResolve: (String, String?) -> Unit) = RunTestHandler(
        contextResolver = stubContextResolver(stubContext, onResolve = onResolve),
        toolFactory = { stubTool(it.module) }
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
