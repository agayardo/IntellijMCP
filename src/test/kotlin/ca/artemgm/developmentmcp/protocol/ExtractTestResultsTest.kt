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

            assertThat(result).contains("Passed: 2", "Failed: 0")
        }

        @Test
        fun `includes failure details from a failing test`() {
            val root = failingRoot()
            val console = FakeConsoleView(root)

            val result = extractTestResults(console, "MyConfig", 1, "")

            assertThat(result).contains("Failed: 1")
            assertThat(result).contains("boom")
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

            assertThat(result).contains("Passed: 2", "Failed: 0")
        }

        @Test
        fun `includes failure details through the wrapper`() {
            val root = failingRoot()
            val inner = FakeConsoleView(root)
            val wrapper = GradleConsoleWrapper(inner)

            val result = extractTestResults(wrapper, "MyConfig", 1, "")

            assertThat(result).contains("Failed: 1")
            assertThat(result).contains("boom")
        }
    }

    @Nested
    inner class Fallback {

        @Test
        fun `null console falls back to process output`() {
            val result = extractTestResults(null, "MyConfig", 1, "some gradle output")

            assertThat(result).contains("some gradle output")
            assertThat(result).doesNotContain("Total:")
        }

        @Test
        fun `console without getResultsViewer or getConsoleView falls back`() {
            val result = extractTestResults(Object(), "MyConfig", 0, "raw output")

            assertThat(result).contains("raw output")
            assertThat(result).doesNotContain("Total:")
        }
    }
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
