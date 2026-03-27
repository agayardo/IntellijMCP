package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BuildNotStartedMessageTest {

    @Nested
    inner class NoBuildErrors {

        @Test
        fun `includes config name and build error label`() {
            val message = buildNotStartedMessage("RunTest-MyTest", null, emptyList())

            assertThat(message).contains("RunTest-MyTest")
            assertThat(message).containsIgnoringCase("build")
        }

        @Test
        fun `includes cause message when present`() {
            val cause = RuntimeException("Compilation failed: unresolved reference 'foo'")

            val message = buildNotStartedMessage("RunTest-MyTest", cause, emptyList())

            assertThat(message).contains("Compilation failed: unresolved reference 'foo'")
        }

        @Test
        fun `single line when no cause and no build errors`() {
            val message = buildNotStartedMessage("RunTest-MyTest", null, emptyList())

            assertThat(message.lines()).hasSize(1)
        }

        @Test
        fun `uses toString when cause has no message`() {
            val cause = object : Exception() {
                override val message: String? = null
                override fun toString() = "CustomException(no details)"
            }

            val message = buildNotStartedMessage("RunTest-MyTest", cause, emptyList())

            assertThat(message).contains("CustomException(no details)")
        }
    }

    @Nested
    inner class WithBuildErrors {

        @Test
        fun `build errors take precedence over cause`() {
            val cause = RuntimeException("generic failure")
            val errors = listOf("Unresolved reference: foo")

            val message = buildNotStartedMessage("RunTest-MyTest", cause, errors)

            assertThat(message).contains("Unresolved reference: foo")
            assertThat(message).doesNotContain("generic failure")
        }

        @Test
        fun `includes all build error messages`() {
            val errors = listOf(
                "Unresolved reference: foo",
                "Type mismatch: expected String, got Int"
            )

            val message = buildNotStartedMessage("RunTest-MyTest", null, errors)

            assertThat(message).contains("Unresolved reference: foo")
            assertThat(message).contains("Type mismatch: expected String, got Int")
        }

        @Test
        fun `empty build errors list falls back to cause`() {
            val cause = RuntimeException("fallback reason")

            val message = buildNotStartedMessage("RunTest-MyTest", cause, emptyList())

            assertThat(message).contains("fallback reason")
        }
    }
}
