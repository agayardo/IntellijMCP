package ca.artemgm.developmentmcp.protocol

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExtractTestResultsTest {

    @Nested
    inner class DirectConsole {

        @Test
        fun `extracts structured results from a console with getResultsViewer and getRoot`() {
            val root = passingRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 0, "")

            assertThat(result.output).contains("Passed: 1", "Failed: 0")
            assertThat(result.failed).isFalse()
        }

        @Test
        fun `includes failure details from a failing test`() {
            val root = failingRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 1, "")

            assertThat(result.output).contains("Failed: 1")
            assertThat(result.output).contains("boom")
            assertThat(result.failed).isTrue()
        }
    }

    @Nested
    inner class WrappedConsole {

        @Test
        fun `unwraps via getConsoleView before extracting results`() {
            val root = passingRoot()
            val inner = FakeConsoleView(root)
            val wrapper = GradleConsoleWrapper(inner)

            val result = extractTestResults(wrapper, "MyConfig", 0, "")

            assertThat(result.output).contains("Passed: 1", "Failed: 0")
        }

        @Test
        fun `includes failure details through the wrapper`() {
            val root = failingRoot()
            val inner = FakeConsoleView(root)
            val wrapper = GradleConsoleWrapper(inner)

            val result = extractTestResults(wrapper, "MyConfig", 1, "")

            assertThat(result.output).contains("Failed: 1")
            assertThat(result.output).contains("boom")
        }
    }

    @Nested
    inner class NonZeroExitCode {

        @Test
        fun `non-zero exit code with no failures includes warning`() {
            val root = passingRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 254, "")

            assertThat(result.output).contains("WARNING")
            assertThat(result.output).contains("254")
            assertThat(result.failed).isTrue()
        }

        @Test
        fun `non-zero exit code with failures omits warning`() {
            val root = failingRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 1, "")

            assertThat(result.output).doesNotContain("WARNING")
        }

        @Test
        fun `zero exit code with no failures omits warning`() {
            val root = passingRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 0, "")

            assertThat(result.output).doesNotContain("WARNING")
            assertThat(result.failed).isFalse()
        }
    }

    @Nested
    inner class EmptyTestSuite {

        @Test
        fun `empty root reports zero tests`() {
            val root = emptyRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 0, "")

            assertThat(result.output).contains("Total: 0")
            assertThat(result.output).contains("WARNING")
            assertThat(result.output).contains("no tests")
            assertThat(result.failed).isTrue()
        }

        @Test
        fun `empty root with non-zero exit code includes warning`() {
            val root = emptyRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 254, "")

            assertThat(result.output).contains("Total: 0")
            assertThat(result.output).contains("WARNING")
            assertThat(result.failed).isTrue()
        }
    }

    @Nested
    inner class StacktraceFormatting {

        @Test
        fun `failure output includes exception class and message from stacktrace`() {
            val root = rootWithStacktrace(
                "java.lang.AssertionError: expected <5> but was <3>\n" +
                    "\tat com.example.MyTest.testFoo(MyTest.java:42)\n" +
                    "\tat org.junit.runners.BlockJUnit4ClassRunner.run(BlockJUnit4ClassRunner.java:50)"
            )
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 1, "")

            assertThat(result.output).contains("java.lang.AssertionError: expected <5> but was <3>")
            assertThat(result.output).contains("com.example.MyTest.testFoo(MyTest.java:42)")
        }

        @Test
        fun `stacktrace starting with at-frame includes it as first line`() {
            val root = rootWithStacktrace(
                "\tat org.junit.Assume.assumeThat(Assume.java:106)\n" +
                    "\tat com.example.MyTest.testFoo(MyTest.java:42)"
            )
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 1, "")

            assertThat(result.output).contains("org.junit.Assume.assumeThat(Assume.java:106)")
            assertThat(result.output).contains("com.example.MyTest.testFoo(MyTest.java:42)")
        }

        @Test
        fun `failure output includes caused-by line`() {
            val root = rootWithStacktrace(
                "org.opentest4j.AssertionFailedError: boom\n" +
                    "\tat org.junit.jupiter.api.Assertions.fail(Assertions.java:100)\n" +
                    "Caused by: java.io.IOException: disk full\n" +
                    "\tat com.example.Storage.write(Storage.java:55)"
            )
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 1, "")

            assertThat(result.output).contains("org.opentest4j.AssertionFailedError: boom")
            assertThat(result.output).contains("Caused by: java.io.IOException: disk full")
            assertThat(result.output).contains("com.example.Storage.write(Storage.java:55)")
        }
    }

    @Nested
    inner class Fallback {

        @Test
        fun `null console falls back to process output`() {
            val result = extractTestResults(null, "MyConfig", 1, "some gradle output")

            assertThat(result.output).contains("some gradle output")
            assertThat(result.output).doesNotContain("Total:")
            assertThat(result.failed).isTrue()
        }

        @Test
        fun `console without getResultsViewer or getConsoleView falls back`() {
            val result = extractTestResults(Object(), "MyConfig", 0, "raw output")

            assertThat(result.output).contains("raw output")
            assertThat(result.output).doesNotContain("Total:")
            assertThat(result.failed).isFalse()
        }
    }
}

private fun rootWithStacktrace(stacktrace: String): SMTestProxy {
    val root = SMTestProxy("[root]", true, null)
    val failing = SMTestProxy("failing test", false, null)
    failing.setTestFailed("assertion failed", stacktrace, false)
    root.addChild(failing)
    root.setFinished()
    return root
}

private fun emptyRoot(): SMTestProxy {
    val root = SMTestProxy("[root]", true, null)
    root.setFinished()
    return root
}

private fun passingRoot(): SMTestProxy {
    val root = SMTestProxy("[root]", true, null)
    val test = SMTestProxy("my test", false, null)
    test.setFinished()
    root.addChild(test)
    root.setFinished()
    return root
}

private fun failingRoot(): SMTestProxy {
    val root = SMTestProxy("[root]", true, null)
    val passing = SMTestProxy("passing test", false, null)
    passing.setFinished()
    val failing = SMTestProxy("failing test", false, null)
    failing.setTestFailed("boom", null, false)
    root.addChild(passing)
    root.addChild(failing)
    root.setFinished()
    return root
}

// Mimics SMTRunnerConsoleView — the real test console that has getResultsViewer().getRoot().
// tryGetTestRoot calls these methods via reflection.
internal class FakeConsoleView(private val root: SMTestProxy) {
    @Suppress("unused")
    fun getResultsViewer() = FakeResultsViewer(root)
}

internal class FakeResultsViewer(private val root: SMTestProxy) {
    @Suppress("unused")
    fun getRoot() = root
}

// Mimics ExternalSystemRunnableState's anonymous inner class that wraps the real console.
// Gradle runs produce this wrapper — tryUnwrapConsole calls getConsoleView() via reflection to
// get through to the real SMTRunnerConsoleView underneath.
internal class GradleConsoleWrapper(private val inner: FakeConsoleView) {
    @Suppress("unused")
    fun getConsoleView() = inner
}
