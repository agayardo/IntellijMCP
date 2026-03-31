package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MatchesGlobTest {

    @Nested
    inner class DoubleStarSlash {

        @Test
        fun `matches file in nested directory`() {
            assertThat(matchesGlob("**/Foo.kt", "src/main/kotlin/com/example/Foo.kt")).isTrue()
        }

        @Test
        fun `matches file in root directory`() {
            assertThat(matchesGlob("**/Foo.kt", "Foo.kt")).isTrue()
        }

        @Test
        fun `does not match different filename`() {
            assertThat(matchesGlob("**/Foo.kt", "src/main/kotlin/Bar.kt")).isFalse()
        }
    }

    @Nested
    inner class SingleStar {

        @Test
        fun `matches any filename in exact directory`() {
            assertThat(matchesGlob("src/main/*.kt", "src/main/Foo.kt")).isTrue()
        }

        @Test
        fun `does not cross directory boundaries`() {
            assertThat(matchesGlob("src/*.kt", "src/main/Foo.kt")).isFalse()
        }
    }

    @Nested
    inner class ExactMatch {

        @Test
        fun `exact path matches`() {
            assertThat(matchesGlob("src/main/Foo.kt", "src/main/Foo.kt")).isTrue()
        }

        @Test
        fun `different path does not match`() {
            assertThat(matchesGlob("src/main/Foo.kt", "src/main/Bar.kt")).isFalse()
        }
    }

    @Nested
    inner class QuestionMark {

        @Test
        fun `matches single character`() {
            assertThat(matchesGlob("**/Fo?.kt", "src/Foo.kt")).isTrue()
        }

        @Test
        fun `does not match slash`() {
            assertThat(matchesGlob("src?main", "src/main")).isFalse()
        }
    }

    @Nested
    inner class DoubleStarWithoutSlash {

        @Test
        fun `matches across directories at end of pattern`() {
            assertThat(matchesGlob("src/**", "src/main/kotlin/Foo.kt")).isTrue()
        }
    }
}
