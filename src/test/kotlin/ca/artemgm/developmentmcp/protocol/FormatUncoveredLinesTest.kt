package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FormatUncoveredLinesTest {

    private val sourceByClass = mapOf(
        "com.example.Foo" to listOf(
            "package com.example",
            "",
            "class Foo {",
            "    fun doWork() {",
            "        println(\"working\")",
            "    }",
            "",
            "    fun unused() {",
            "        throw UnsupportedOperationException()",
            "    }",
            "}"
        )
    )

    private val sourceReader: (String) -> List<String>? = { sourceByClass[it] }

    @Nested
    inner class Matching {

        @Test
        fun `exact class name match returns uncovered lines with content`() {
            val coverage = packageCoverage(
                ClassCoverage("com.example.Foo", 5, 3, 0, 0, listOf(8, 9))
            )

            val result = formatUncoveredLines(coverage, listOf("com\\.example\\.Foo"), sourceReader)

            assertThat(result).contains("Uncovered lines in com.example.Foo:")
            assertThat(result).contains("8:     fun unused() {")
            assertThat(result).contains("9:         throw UnsupportedOperationException()")
        }

        @Test
        fun `regex pattern matches multiple classes`() {
            val coverage = packageCoverage(
                ClassCoverage("com.example.Foo", 5, 3, 0, 0, listOf(9)),
                ClassCoverage("com.example.Bar", 3, 1, 0, 0, listOf(2))
            )
            val reader: (String) -> List<String>? = {
                when (it) {
                    "com.example.Foo" -> sourceByClass["com.example.Foo"]
                    "com.example.Bar" -> listOf("class Bar {", "    val x = 1", "}")
                    else -> null
                }
            }

            val result = formatUncoveredLines(coverage, listOf("com\\.example\\..*"), reader)

            assertThat(result).contains("com.example.Foo")
            assertThat(result).contains("com.example.Bar")
        }

        @Test
        fun `no matching classes produces empty string`() {
            val coverage = packageCoverage(
                ClassCoverage("com.example.Foo", 5, 3, 0, 0, listOf(9))
            )

            val result = formatUncoveredLines(coverage, listOf("com\\.other\\..*"), sourceReader)

            assertThat(result).isEmpty()
        }

        @Test
        fun `class with no uncovered lines is excluded even if pattern matches`() {
            val coverage = packageCoverage(
                ClassCoverage("com.example.Foo", 5, 5, 0, 0, emptyList())
            )

            val result = formatUncoveredLines(coverage, listOf("com\\.example\\.Foo"), sourceReader)

            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class SourceResolution {

        @Test
        fun `line number without source content shows number only`() {
            val coverage = packageCoverage(
                ClassCoverage("com.example.Missing", 3, 1, 0, 0, listOf(2))
            )

            val result = formatUncoveredLines(coverage, listOf("com\\.example\\.Missing")) { null }

            assertThat(result).contains("  2")
            assertThat(result).doesNotContain("2:")
        }

        @Test
        fun `out-of-range line number shows number only`() {
            val coverage = packageCoverage(
                ClassCoverage("com.example.Foo", 5, 3, 0, 0, listOf(999))
            )

            val result = formatUncoveredLines(coverage, listOf("com\\.example\\.Foo"), sourceReader)

            assertThat(result).contains("999")
        }
    }
}

private fun packageCoverage(vararg classes: ClassCoverage) = PackageCoverage(
    packageName = "com.example",
    totalLines = classes.sumOf { it.totalLines },
    coveredLines = classes.sumOf { it.coveredLines },
    totalBranches = classes.sumOf { it.totalBranches },
    coveredBranches = classes.sumOf { it.coveredBranches },
    classCoverages = classes.toList()
)
