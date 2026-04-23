package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.project.Project
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClassLookupHandlerTest {

    private val projectA = stubProject("ProjectA")
    private val projectB = stubProject("ProjectB")

    private val sampleResult = ClassLookupResult(
        classes = listOf(
            ClassInfo(
                fqn = "com.example.Foo",
                methods = listOf(MethodInfo("doStuff", "void", listOf(ParameterInfo("x", "int")))),
                fields = listOf(FieldInfo("name", "String")),
                interfaces = listOf("java.io.Serializable"),
                superclass = "java.lang.Object"
            )
        ),
        totalMatches = 1,
        truncated = false
    )

    private var capturedModuleName: String? = "sentinel"

    private val handler = ClassLookupHandler(
        contextResolver = { moduleName ->
            capturedModuleName = moduleName
            listOf(ResolvedProject(projectA))
        },
        classLookup = { _, _ -> sampleResult }
    )

    @Nested
    inner class Schema {

        private val registration = handler.registration()
        private val schema = parseSchema(registration)

        @Test
        fun `registration name is lookup_class`() {
            assertThat(registration.name).isEqualTo("lookup_class")
        }

        @Test
        fun `className is required in schema`() {
            assertThat(schemaProperty(schema, "className")["type"]).isEqualTo("string")
            assertThat(requiredParams(schema)).contains("className")
        }

        @Test
        fun `moduleName is optional in schema`() {
            assertOptionalStringParam(schema, "moduleName")
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `blank className produces error`() {
            val result = handler.handle("", null)

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).containsIgnoringCase("blank")
        }

        @Test
        fun `whitespace-only className produces error`() {
            val result = handler.handle("   ", null)

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).containsIgnoringCase("blank")
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `no-match error includes the searched pattern`() {
            val handler = handlerWithLookup { _, _ ->
                ClassLookupResult(classes = emptyList(), totalMatches = 0, truncated = false)
            }

            val result = handler.handle("com.missing.Clazz", null)

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).contains("com.missing.Clazz")
        }

        @Test
        fun `context resolution failure surfaces error message`() {
            val handler = ClassLookupHandler(
                contextResolver = { throw IllegalArgumentException("Module 'bogus' not found") },
                classLookup = { _, _ -> sampleResult }
            )

            val result = handler.handle("com.example.Foo", "bogus")

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).contains("Module 'bogus' not found")
        }

        @Test
        fun `unexpected lookup exception surfaces error summary`() {
            val handler = handlerWithLookup { _, _ ->
                throw RuntimeException("index corrupted")
            }

            val result = handler.handle("com.example.Foo", null)

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).contains("RuntimeException", "index corrupted")
        }
    }

    @Nested
    inner class SuccessfulLookup {

        @Test
        fun `successful lookup produces formatted text`() {
            val result = handler.handle("com.example.Foo", null)

            assertThat(result.isError).isNotEqualTo(true)
            assertThat(textOf(result)).contains("com.example.Foo", "doStuff")
        }
    }

    @Nested
    inner class ContextResolution {

        @Test
        fun `moduleName forwarded to context resolver`() {
            handler.handle("com.example.Foo", "my-module")

            assertThat(capturedModuleName).isEqualTo("my-module")
        }

        @Test
        fun `null moduleName forwarded when omitted`() {
            handler.handle("com.example.Foo", null)

            assertThat(capturedModuleName).isNull()
        }
    }

    @Nested
    inner class MultiProjectSearch {

        @Test
        fun `searches all resolved projects and merges results`() {
            val capturedProjects = mutableListOf<Project>()
            val handler = multiProjectHandler { project, _ ->
                capturedProjects += project
                resultWithClassPerProject(project)
            }

            val result = handler.handle("*Class", null)

            assertThat(capturedProjects).containsExactly(projectA, projectB)
            assertThat(textOf(result))
                .contains("com.example.ProjectAClass")
                .contains("com.example.ProjectBClass")
        }

        @Test
        fun `output includes module name per project section`() {
            val handler = multiProjectHandler { project, _ -> resultWithClassPerProject(project) }

            val result = handler.handle("*Class", null)

            assertThat(textOf(result)).contains("ProjectA", "ProjectB")
        }

        private fun multiProjectHandler(
            classLookup: (Project, String) -> ClassLookupResult
        ) = ClassLookupHandler(
            contextResolver = { listOf(ResolvedProject(projectA), ResolvedProject(projectB)) },
            classLookup = classLookup
        )

        private fun resultWithClassPerProject(project: Project) = ClassLookupResult(
            classes = listOf(aClassInfo("com.example.${project.name}Class")),
            totalMatches = 1,
            truncated = false
        )

        @Test
        fun `no-match across all projects produces error`() {
            val handler = ClassLookupHandler(
                contextResolver = { listOf(ResolvedProject(projectA), ResolvedProject(projectB)) },
                classLookup = { _, _ ->
                    ClassLookupResult(classes = emptyList(), totalMatches = 0, truncated = false)
                }
            )

            val result = handler.handle("com.missing.Clazz", null)

            assertThat(result.isError).isTrue()
            assertThat(textOf(result)).contains("com.missing.Clazz")
        }
    }

    private fun handlerWithLookup(
        lookup: (Project, String) -> ClassLookupResult
    ) = ClassLookupHandler(
        contextResolver = { listOf(ResolvedProject(projectA)) },
        classLookup = lookup
    )
}
