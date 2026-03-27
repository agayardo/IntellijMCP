package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExtractLogSummaryTest {

    @Test
    fun `extracts test counts and coverage percentage`() {
        val text = """
            Test run 'RunTest' completed (exit code 0)
            Total: 5, Passed: 4, Failed: 1, Ignored: 0

            Coverage for package 'com.example':
              Lines:    42/100 (42.0%)
              Branches: 10/20 (50.0%)
        """.trimIndent()

        val summary = extractLogSummary(text)

        assertThat(summary).contains("Total: 5, Passed: 4, Failed: 1, Ignored: 0")
        assertThat(summary).contains("coverage=42.0%")
    }

    @Test
    fun `extracts test counts when no coverage present`() {
        val text = "Total: 3, Passed: 3, Failed: 0, Ignored: 0"

        assertThat(extractLogSummary(text)).isEqualTo("Total: 3, Passed: 3, Failed: 0, Ignored: 0")
    }

    @Test
    fun `empty string when no recognizable patterns`() {
        assertThat(extractLogSummary("some random output")).isEmpty()
    }
}
