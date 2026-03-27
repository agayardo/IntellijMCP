package ca.artemgm.developmentmcp.protocol

import com.intellij.openapi.project.Project
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ProjectResolverTest {

    private val moduleA = stubModule("alpha")
    private val moduleB = stubModule("beta")
    private val projectA = stubProject("ProjectA")
    private val projectB = stubProject("ProjectB")

    @Nested
    inner class ResolveByModuleName {

        @Test
        fun `finds module in first project`() {
            val resolver = resolver(
                projects = arrayOf(projectA),
                moduleByName = mapOf(projectA to mapOf("alpha" to moduleA))
            )

            val result = resolver.resolve("irrelevant", "alpha")

            assertThat(result.project).isSameAs(projectA)
            assertThat(result.module).isSameAs(moduleA)
        }

        @Test
        fun `finds module in second project when first lacks it`() {
            val resolver = resolver(
                projects = arrayOf(projectA, projectB),
                moduleByName = mapOf(projectA to emptyMap(), projectB to mapOf("beta" to moduleB))
            )

            val result = resolver.resolve("irrelevant", "beta")

            assertThat(result.project).isSameAs(projectB)
            assertThat(result.module).isSameAs(moduleB)
        }

        @Test
        fun `unknown module name lists all available modules`() {
            val resolver = resolver(
                projects = arrayOf(projectA, projectB),
                moduleNames = mapOf(projectA to listOf("alpha"), projectB to listOf("beta", "gamma"))
            )

            assertThatThrownBy { resolver.resolve("irrelevant", "nonexistent") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("nonexistent")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta")
                .hasMessageContaining("gamma")
        }

        @Test
        fun `no open projects produces error with module name`() {
            val resolver = resolver(projects = emptyArray())

            assertThatThrownBy { resolver.resolve("irrelevant", "any-module") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("any-module")
        }
    }

    @Nested
    inner class ResolveByTarget {

        private fun resolveWithProjectAMatch(projectAMatch: TargetMatch): ResolvedContext {
            val resolver = resolver(
                projects = arrayOf(projectA, projectB),
                targetMatches = mapOf(
                    projectA to projectAMatch,
                    projectB to TargetMatch.Resolved(ResolvedContext(projectB, moduleB))
                )
            )
            return resolver.resolve("com.example.MyClass", null)
        }

        @Test
        fun `resolves when target found in exactly one project`() {
            val result = resolveWithProjectAMatch(TargetMatch.NotFound)

            assertThat(result.project).isSameAs(projectB)
            assertThat(result.module).isSameAs(moduleB)
        }

        @Test
        fun `target not found in any project`() {
            val resolver = resolver(
                projects = arrayOf(projectA),
                targetMatches = mapOf(projectA to TargetMatch.NotFound)
            )

            assertThatThrownBy { resolver.resolve("com.example.Missing", null) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("com.example.Missing")
                .hasMessageContaining("any open project")
        }

        @Test
        fun `target found but module unknown lists available modules`() {
            val resolver = resolver(
                projects = arrayOf(projectA),
                targetMatches = mapOf(projectA to TargetMatch.FoundButModuleUnknown),
                moduleNames = mapOf(projectA to listOf("alpha", "beta"))
            )

            assertThatThrownBy { resolver.resolve("com.example.pkg", null) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("could not determine its module")
                .hasMessageContaining("moduleName")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta")
        }

        @Test
        fun `resolved match takes precedence over found-but-unknown in another project`() {
            val result = resolveWithProjectAMatch(TargetMatch.FoundButModuleUnknown)

            assertThat(result.project).isSameAs(projectB)
        }

        @Test
        fun `target found in multiple projects lists available modules`() {
            val resolver = resolver(
                projects = arrayOf(projectA, projectB),
                targetMatches = mapOf(
                    projectA to TargetMatch.Resolved(ResolvedContext(projectA, moduleA)),
                    projectB to TargetMatch.Resolved(ResolvedContext(projectB, moduleB))
                ),
                moduleNames = mapOf(projectA to listOf("alpha"), projectB to listOf("beta"))
            )

            assertThatThrownBy { resolver.resolve("com.example.Shared", null) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("multiple projects")
                .hasMessageContaining("moduleName")
                .hasMessageContaining("alpha")
                .hasMessageContaining("beta")
        }

        @Test
        fun `no open projects produces not-found error`() {
            val resolver = resolver(projects = emptyArray())

            assertThatThrownBy { resolver.resolve("com.example.Missing", null) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("any open project")
        }

        @Test
        fun `retries after VFS refresh when target not found on first attempt`() {
            var attempt = 0
            var refreshCalled = false
            val resolver = ProjectResolver(
                openProjects = { arrayOf(projectA) },
                findModuleByName = { _, _ -> null },
                resolveTargetInProject = { project, _ ->
                    attempt++
                    if (attempt == 1) TargetMatch.NotFound
                    else TargetMatch.Resolved(ResolvedContext(project, moduleA))
                },
                listModuleNames = { emptyList() },
                refreshAndWait = { refreshCalled = true }
            )

            val result = resolver.resolve("com.example.NewClass", null)

            assertThat(refreshCalled).isTrue()
            assertThat(result.module).isSameAs(moduleA)
        }

        @Test
        fun `does not refresh when target found on first attempt`() {
            var refreshCalled = false
            val resolver = resolver(
                projects = arrayOf(projectA),
                targetMatches = mapOf(projectA to TargetMatch.Resolved(ResolvedContext(projectA, moduleA))),
                refreshAndWait = { refreshCalled = true }
            )

            resolver.resolve("com.example.MyClass", null)

            assertThat(refreshCalled).isFalse()
        }
    }
}

private fun resolver(
    projects: Array<Project> = emptyArray(),
    moduleByName: Map<Project, Map<String, com.intellij.openapi.module.Module>> = emptyMap(),
    targetMatches: Map<Project, TargetMatch> = emptyMap(),
    moduleNames: Map<Project, List<String>> = emptyMap(),
    refreshAndWait: () -> Unit = {}
) = ProjectResolver(
    openProjects = { projects },
    findModuleByName = { project, name -> moduleByName[project]?.get(name) },
    resolveTargetInProject = { project, _ -> targetMatches[project] ?: TargetMatch.NotFound },
    listModuleNames = { project -> moduleNames[project] ?: emptyList() },
    refreshAndWait = refreshAndWait
)
