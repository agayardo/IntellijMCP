package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NestedClassResolutionTest {

    private val module = stubModule("mod")
    private val project = stubProject("Proj")
    private var lastResolvedTarget: String? = null

    private val resolver = ProjectResolver(
        openProjects = { arrayOf(project) },
        findModuleByName = { _, _ -> null },
        resolveTargetInProject = { _, target ->
            lastResolvedTarget = target
            TargetMatch.Resolved(ResolvedContext(project, module))
        },
        listModuleNames = { emptyList() }
    )

    @Nested
    inner class NestedClassTargets {
        @Test
        fun `nested class target strips inner class suffix`() {
            resolver.resolve("com.example.OuterTest\$Inner", null)

            assertThat(lastResolvedTarget).isEqualTo("com.example.OuterTest")
        }

        @Test
        fun `deeply nested class target strips all inner suffixes`() {
            resolver.resolve("com.example.Outer\$Mid\$Inner", null)

            assertThat(lastResolvedTarget).isEqualTo("com.example.Outer")
        }
    }

    @Nested
    inner class TopLevelClassTargets {
        @Test
        fun `top-level class target passes through unchanged`() {
            resolver.resolve("com.example.MyClass", null)

            assertThat(lastResolvedTarget).isEqualTo("com.example.MyClass")
        }
    }
}
