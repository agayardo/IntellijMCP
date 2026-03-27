package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FormatUncoveredLinesTest {

    private val sourceByFile = mapOf(
        "src/main/kotlin/com/example/Foo.kt" to listOf(
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

    private val sourceReader: (String) -> List<String>? = { sourceByFile[it] }

    @Nested
    inner class Matching {

        @Test
        fun `exact file path match returns uncovered lines with content`() {
            val fileCoverages = listOf(
                FileCoverage("src/main/kotlin/com/example/Foo.kt", 5, 3, 0, 0, emptyList(), listOf(8, 9))
            )

            val result = formatUncoveredLines(fileCoverages, listOf("**/Foo.kt"), sourceReader)

            assertThat(result).contains("Uncovered lines in src/main/kotlin/com/example/Foo.kt:")
            assertThat(result).contains("8:     fun unused() {")
            assertThat(result).contains("9:         throw UnsupportedOperationException()")
        }

        @Test
        fun `glob pattern matches multiple files`() {
            val fileCoverages = listOf(
                FileCoverage("src/main/kotlin/com/example/Foo.kt", 5, 3, 0, 0, emptyList(), listOf(9)),
                FileCoverage("src/main/kotlin/com/example/Bar.kt", 3, 1, 0, 0, emptyList(), listOf(2))
            )
            val reader: (String) -> List<String>? = {
                when (it) {
                    "src/main/kotlin/com/example/Foo.kt" -> sourceByFile.values.first()
                    "src/main/kotlin/com/example/Bar.kt" -> listOf("class Bar {", "    val x = 1", "}")
                    else -> null
                }
            }

            val result = formatUncoveredLines(fileCoverages, listOf("**/*.kt"), reader)

            assertThat(result).contains("Foo.kt")
            assertThat(result).contains("Bar.kt")
        }

        @Test
        fun `no matching files produces empty string`() {
            val fileCoverages = listOf(
                FileCoverage("src/main/kotlin/com/example/Foo.kt", 5, 3, 0, 0, emptyList(), listOf(9))
            )

            val result = formatUncoveredLines(fileCoverages, listOf("**/Other.kt"), sourceReader)

            assertThat(result).isEmpty()
        }

        @Test
        fun `file with no uncovered lines is excluded even if pattern matches`() {
            val fileCoverages = listOf(
                FileCoverage("src/main/kotlin/com/example/Foo.kt", 5, 5, 0, 0, emptyList(), emptyList())
            )

            val result = formatUncoveredLines(fileCoverages, listOf("**/Foo.kt"), sourceReader)

            assertThat(result).isEmpty()
        }
    }

    @Nested
    inner class SourceResolution {

        @Test
        fun `line number without source content shows number only`() {
            val fileCoverages = listOf(
                FileCoverage("src/main/kotlin/com/example/Missing.kt", 3, 1, 0, 0, emptyList(), listOf(2))
            )

            val result = formatUncoveredLines(fileCoverages, listOf("**/Missing.kt")) { null }

            assertThat(result).contains("  2")
            assertThat(result).doesNotContain("2:")
        }

        @Test
        fun `out-of-range line number shows number only`() {
            val fileCoverages = listOf(
                FileCoverage("src/main/kotlin/com/example/Foo.kt", 5, 3, 0, 0, emptyList(), listOf(999))
            )

            val result = formatUncoveredLines(fileCoverages, listOf("**/Foo.kt"), sourceReader)

            assertThat(result).contains("999")
        }
    }
}
