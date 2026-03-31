package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class GroupByFileTest {

    @Nested
    inner class FileGrouping {

        @Test
        fun `classes in the same file are merged into one FileCoverage`() {
            val classes = listOf(
                ClassCoverage("com.example.Foo", 10, 8, 4, 3,
                    coveredLineNumbers = listOf(1, 2, 3, 4, 6, 7, 8, 10),
                    uncoveredLineNumbers = listOf(5, 9)),
                ClassCoverage("com.example.FooKt", 6, 4, 2, 1,
                    coveredLineNumbers = listOf(1, 2, 7, 8),
                    uncoveredLineNumbers = listOf(3))
            )

            val result = groupByFile(classes) { "src/main/kotlin/com/example/Foo.kt" }

            assertThat(result).hasSize(1)
            assertThat(result[0].filePath).isEqualTo("src/main/kotlin/com/example/Foo.kt")
            // Deduplicated: covered={1,2,3,4,6,7,8,10}, uncovered={5,9} (3 removed — also covered)
            assertThat(result[0].coveredLineNumbers).containsExactly(1, 2, 3, 4, 6, 7, 8, 10)
            assertThat(result[0].uncoveredLineNumbers).containsExactly(5, 9)
            assertThat(result[0].totalLines).isEqualTo(10)
            assertThat(result[0].coveredLines).isEqualTo(8)
            assertThat(result[0].totalBranches).isEqualTo(6)
            assertThat(result[0].coveredBranches).isEqualTo(4)
        }

        @Test
        fun `classes in different files produce separate FileCoverages`() {
            val classes = listOf(
                ClassCoverage("com.example.Foo", 10, 8, 0, 0),
                ClassCoverage("com.example.Bar", 5, 3, 0, 0)
            )
            val resolver: (String) -> String? = {
                when (it) {
                    "com.example.Foo" -> "src/main/kotlin/com/example/Foo.kt"
                    "com.example.Bar" -> "src/main/kotlin/com/example/Bar.kt"
                    else -> null
                }
            }

            val result = groupByFile(classes, resolver)

            assertThat(result).hasSize(2)
            assertThat(result.map { it.filePath }).containsExactly(
                "src/main/kotlin/com/example/Foo.kt",
                "src/main/kotlin/com/example/Bar.kt"
            )
        }

        @Test
        fun `unresolved class uses class name as fallback file path`() {
            val classes = listOf(
                ClassCoverage("com.example.Foo", 10, 8, 0, 0)
            )

            val result = groupByFile(classes) { null }

            assertThat(result).hasSize(1)
            assertThat(result[0].filePath).isEqualTo("com.example.Foo")
        }

        @Test
        fun `uncovered line numbers are deduplicated and sorted across merged classes`() {
            val classes = listOf(
                ClassCoverage("com.example.Foo", 5, 3, 0, 0,
                    coveredLineNumbers = listOf(1, 2, 3),
                    uncoveredLineNumbers = listOf(20, 30)),
                ClassCoverage("com.example.FooKt", 3, 1, 0, 0,
                    coveredLineNumbers = listOf(1),
                    uncoveredLineNumbers = listOf(5, 10))
            )

            val result = groupByFile(classes) { "src/main/kotlin/com/example/Foo.kt" }

            assertThat(result[0].uncoveredLineNumbers).containsExactly(5, 10, 20, 30)
            assertThat(result[0].coveredLineNumbers).containsExactly(1, 2, 3)
        }

        @Test
        fun `empty class list produces empty result`() {
            val result = groupByFile(emptyList()) { null }

            assertThat(result).isEmpty()
        }
    }
}
