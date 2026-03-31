package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CorrectForUnloadedClassesTest {

    @Nested
    inner class AllClassesLoaded {

        @Test
        fun `no correction when all classes in file were loaded`() {
            val fileCoverages = listOf(
                FileCoverage("src/Foo.kt", 10, 8, 0, 0, listOf(1, 2, 3, 4, 5, 6, 7, 8), listOf(9, 10))
            )

            val result = correctForUnloadedClasses(
                fileCoverages,
                patterns = listOf("**/Foo.kt"),
                loadedClassNames = setOf("com.example.Foo", "com.example.FooKt"),
                classesInFile = { setOf("com.example.Foo", "com.example.FooKt") },
                sourceReader = { null }
            )

            assertThat(result[0].totalLines).isEqualTo(10)
            assertThat(result[0].coveredLines).isEqualTo(8)
            assertThat(result[0].uncoveredLineNumbers).containsExactly(9, 10)
        }
    }

    @Nested
    inner class UnloadedClass {

        @Test
        fun `heuristic marks all code lines as uncovered when no classes were loaded`() {
            val fileCoverages = listOf(
                FileCoverage("src/Foo.kt", 0, 0, 0, 0, emptyList(), emptyList())
            )
            val source = listOf(
                "package com.example",       // 1 - not code
                "",                          // 2 - not code
                "fun topLevel() = 42",       // 3 - code → heuristic uncovered
                "val topVal = \"hello\""     // 4 - code → heuristic uncovered
            )

            val result = correctForUnloadedClasses(
                fileCoverages,
                patterns = listOf("**/*.kt"),
                loadedClassNames = emptySet(),
                classesInFile = { setOf("com.example.FooKt") },
                sourceReader = { source }
            )

            assertThat(result[0].coveredLines).isEqualTo(0)
            assertThat(result[0].uncoveredLineNumbers).containsExactly(3, 4)
            assertThat(result[0].totalLines).isEqualTo(2)
        }

        @Test
        fun `mixed loaded and unloaded classes skips heuristic`() {
            val fileCoverages = listOf(
                FileCoverage("src/Foo.kt", 3, 3, 0, 0, listOf(4, 5, 6), emptyList())
            )

            val result = correctForUnloadedClasses(
                fileCoverages,
                patterns = listOf("**/*.kt"),
                loadedClassNames = setOf("com.example.Foo"),
                classesInFile = { setOf("com.example.Foo", "com.example.FooKt") },
                sourceReader = { error("should not be called") }
            )

            assertThat(result[0]).isEqualTo(fileCoverages[0])
        }
    }

    @Nested
    inner class SkipsCorrection {

        private val fileCoverages = listOf(
            FileCoverage("src/Foo.kt", 5, 3, 0, 0, listOf(1, 2, 3), listOf(4, 5))
        )

        private fun correctUnchanged(
            patterns: List<String> = listOf("**/Foo.kt"),
            classesInFile: (String) -> Set<String> = { emptySet() }
        ) {
            val result = correctForUnloadedClasses(
                fileCoverages, patterns, loadedClassNames = emptySet(),
                classesInFile = classesInFile, sourceReader = { null }
            )
            assertThat(result[0]).isEqualTo(fileCoverages[0])
        }

        @Test
        fun `file not matching pattern is unchanged`() =
            correctUnchanged(patterns = listOf("**/Bar.kt"), classesInFile = { setOf("com.example.Foo") })

        @Test
        fun `no correction when classesInFile returns empty`() =
            correctUnchanged()
    }
}
