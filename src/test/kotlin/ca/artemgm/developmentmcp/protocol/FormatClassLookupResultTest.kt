package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class FormatClassLookupResultTest {

    @Nested
    inner class FullyPopulatedClass {

        private val output = formatClassLookupResult(
            ClassLookupResult(
                classes = listOf(
                    ClassInfo(
                        fqn = "com.example.MyService",
                        methods = listOf(
                            MethodInfo("process", "void", listOf(ParameterInfo("input", "String"))),
                            MethodInfo("getCount", "int", emptyList())
                        ),
                        fields = listOf(
                            FieldInfo("name", "String"),
                            FieldInfo("active", "boolean")
                        ),
                        interfaces = listOf("java.io.Serializable", "java.lang.Comparable"),
                        superclass = "com.example.BaseService"
                    )
                ),
                totalMatches = 1,
                truncated = false
            )
        )

        @Test
        fun `output contains fully qualified name`() {
            assertThat(output).contains("com.example.MyService")
        }

        @Test
        fun `output contains method signatures with return types and parameters`() {
            assertThat(output)
                .contains("void process(String input)")
                .contains("int getCount()")
        }

        @Test
        fun `output contains fields with types`() {
            assertThat(output)
                .contains("String name")
                .contains("boolean active")
        }

        @Test
        fun `output contains implemented interfaces`() {
            assertThat(output).contains("java.io.Serializable", "java.lang.Comparable")
        }

        @Test
        fun `output contains superclass`() {
            assertThat(output).contains("com.example.BaseService")
        }
    }

    @Nested
    inner class AbsentSuperclass {

        private val output = formatClassLookupResult(
            ClassLookupResult(
                classes = listOf(
                    ClassInfo(
                        fqn = "com.example.TopLevel",
                        methods = emptyList(),
                        fields = emptyList(),
                        interfaces = emptyList(),
                        superclass = null
                    )
                ),
                totalMatches = 1,
                truncated = false
            )
        )

        @Test
        fun `null superclass renders as none indicator`() {
            assertThat(output).contains("(none)")
        }
    }

    @Nested
    inner class EmptyMethodsAndFields {

        private val output = formatClassLookupResult(
            ClassLookupResult(
                classes = listOf(
                    ClassInfo(
                        fqn = "com.example.Empty",
                        methods = emptyList(),
                        fields = emptyList(),
                        interfaces = emptyList(),
                        superclass = null
                    )
                ),
                totalMatches = 1,
                truncated = false
            )
        )

        @Test
        fun `empty methods section renders with none indicator`() {
            assertThat(output).contains("Methods:\n  (none)")
        }

        @Test
        fun `empty fields section renders with none indicator`() {
            assertThat(output).contains("Fields:\n  (none)")
        }
    }

    @Nested
    inner class Truncation {

        @Test
        fun `truncated result includes total match count in header`() {
            val output = formatClassLookupResult(
                ClassLookupResult(
                    classes = listOf(aMinimalClass("com.example.First")),
                    totalMatches = 150,
                    truncated = true
                )
            )

            assertThat(output).contains("1 of 150 matching classes (results truncated)")
        }

        @Test
        fun `non-truncated result omits truncation message`() {
            val output = formatClassLookupResult(
                ClassLookupResult(
                    classes = listOf(aMinimalClass("com.example.Only")),
                    totalMatches = 1,
                    truncated = false
                )
            )

            assertThat(output)
                .doesNotContain("truncated")
                .contains("Found 1 matching classes:")
        }
    }
}

private fun aMinimalClass(fqn: String) = ClassInfo(
    fqn = fqn,
    methods = emptyList(),
    fields = emptyList(),
    interfaces = emptyList(),
    superclass = null
)
