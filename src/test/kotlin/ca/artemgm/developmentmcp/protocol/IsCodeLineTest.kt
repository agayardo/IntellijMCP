package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class IsCodeLineTest {

    @Nested
    inner class NonCodeLines {

        @Test
        fun `blank line`() {
            assertThat(isCodeLine("")).isFalse()
            assertThat(isCodeLine("   ")).isFalse()
        }

        @Test
        fun `line comment`() {
            assertThat(isCodeLine("  // this is a comment")).isFalse()
        }

        @Test
        fun `block comment lines`() {
            assertThat(isCodeLine("  /* start")).isFalse()
            assertThat(isCodeLine("   * middle")).isFalse()
            assertThat(isCodeLine("   */")).isFalse()
        }

        @Test
        fun `package declaration`() {
            assertThat(isCodeLine("package com.example")).isFalse()
        }

        @Test
        fun `import statement`() {
            assertThat(isCodeLine("import com.example.Foo")).isFalse()
        }

        @Test
        fun `lone braces`() {
            assertThat(isCodeLine("{")).isFalse()
            assertThat(isCodeLine("  }")).isFalse()
            assertThat(isCodeLine("  )")).isFalse()
        }

        @Test
        fun `annotation`() {
            assertThat(isCodeLine("  @Override")).isFalse()
            assertThat(isCodeLine("@Test")).isFalse()
        }
    }

    @Nested
    inner class CodeLines {

        @Test
        fun `function call`() {
            assertThat(isCodeLine("    println(\"hello\")")).isTrue()
        }

        @Test
        fun `variable declaration`() {
            assertThat(isCodeLine("    val x = 42")).isTrue()
        }

        @Test
        fun `return statement`() {
            assertThat(isCodeLine("    return result")).isTrue()
        }

        @Test
        fun `function declaration with body`() {
            assertThat(isCodeLine("    fun doWork() = compute()")).isTrue()
        }

        @Test
        fun `class with body on same line`() {
            assertThat(isCodeLine("data class Foo(val x: Int)")).isTrue()
        }
    }
}
