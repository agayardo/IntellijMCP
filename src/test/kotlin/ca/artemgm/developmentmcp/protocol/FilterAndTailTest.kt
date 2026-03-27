package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FilterAndTailTest {

    private val matchAll = Regex(".*")

    @Test
    fun `returns last N matching lines from longer input`() {
        val input = (1..10).joinToString("\n") { "line $it" }

        assertThat(filterAndTail(input, matchAll, 3)).isEqualTo("line 8\nline 9\nline 10")
    }

    @Test
    fun `returns all matching lines when fewer than limit`() {
        assertThat(filterAndTail("a\nb\nc", matchAll, 10)).isEqualTo("a\nb\nc")
    }

    @Test
    fun `returns empty string for zero maxLines`() {
        assertThat(filterAndTail("a\nb", matchAll, 0)).isEmpty()
    }

    @Test
    fun `returns empty string for negative maxLines`() {
        assertThat(filterAndTail("a\nb", matchAll, -1)).isEmpty()
    }

    @Test
    fun `returns empty string for empty input`() {
        assertThat(filterAndTail("", matchAll, 10)).isEmpty()
    }

    @Test
    fun `trailing newline does not produce extra blank line`() {
        assertThat(filterAndTail("a\nb\n", matchAll, 2)).isEqualTo("a\nb")
    }

    @Test
    fun `single line without newline`() {
        assertThat(filterAndTail("hello", matchAll, 5)).isEqualTo("hello")
    }

    @Test
    fun `only lines matching filter are included`() {
        val input = "noise\nTEST DEBUG: value=42\nmore noise\nTEST DEBUG: value=99"

        assertThat(filterAndTail(input, Regex("TEST DEBUG:"), 10))
            .isEqualTo("TEST DEBUG: value=42\nTEST DEBUG: value=99")
    }

    @Test
    fun `filter applies before tail limit`() {
        val input = (1..100).joinToString("\n") { if (it % 10 == 0) "MATCH $it" else "noise $it" }

        assertThat(filterAndTail(input, Regex("MATCH"), 3))
            .isEqualTo("MATCH 80\nMATCH 90\nMATCH 100")
    }

    @Test
    fun `no matching lines produces empty string`() {
        assertThat(filterAndTail("a\nb\nc", Regex("NOPE"), 10)).isEmpty()
    }

    @Test
    fun `partial match within line is sufficient`() {
        assertThat(filterAndTail("hello world", Regex("world"), 10)).isEqualTo("hello world")
    }
}
