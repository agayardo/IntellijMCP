package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ActionRegistryTest {

    private val registry = ActionRegistry()

    @Test
    fun `registered tool is retrievable by name`() {
        registry.register(toolRegistration("my_tool", "my description", """{"type":"object"}"""))

        val found = registry.lookup("my_tool")

        assertThat(found!!.name).isEqualTo("my_tool")
        assertThat(found.description).isEqualTo("my description")
        assertThat(found.inputSchema).isEqualTo("""{"type":"object"}""")
    }

    @Test
    fun `lookup of unregistered name yields null`() {
        registry.register(toolRegistration("registered_tool"))

        assertThat(registry.lookup("other_tool")).isNull()
    }

    @Test
    fun `allTools lists every registered tool`() {
        registry.register(toolRegistration("alpha"))
        registry.register(toolRegistration("beta"))
        registry.register(toolRegistration("gamma"))

        assertThat(registry.allTools().map { it.name })
            .containsExactlyInAnyOrder("alpha", "beta", "gamma")
    }

    @Test
    fun `duplicate name registration is rejected and original preserved`() {
        registry.register(toolRegistration("dup_tool", description = "original"))

        assertThat(registry.register(toolRegistration("dup_tool", description = "replacement"))).isFalse()
        assertThat(registry.lookup("dup_tool")!!.description).isEqualTo("original")
    }

    @Test
    fun `register of new tool succeeds`() {
        assertThat(registry.register(toolRegistration("new_tool"))).isTrue()
    }
}
