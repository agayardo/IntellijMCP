package ca.artemgm.developmentmcp.protocol

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ClassLookupToolTest {

    private var fqnQueries = mutableListOf<String>()
    private var shortNameQueries = mutableListOf<String>()
    private var smartModeCallOrder = mutableListOf<String>()

    private val arrayListInfo = aClassInfo("java.util.ArrayList")
    private val linkedListInfo = aClassInfo("java.util.LinkedList")
    private val hashMapInfo = aClassInfo("java.util.HashMap")
    private val treeMapInfo = aClassInfo("java.util.TreeMap")
    private val langMapInfo = aClassInfo("java.lang.Map")

    private val infoByShortName = mapOf(
        "ArrayList" to listOf(arrayListInfo),
        "LinkedList" to listOf(linkedListInfo),
        "HashMap" to listOf(hashMapInfo),
        "TreeMap" to listOf(treeMapInfo),
        "Map" to listOf(langMapInfo)
    )

    private val tool = ClassLookupTool(
        findClassesByFqn = { fqn ->
            smartModeCallOrder += "fqn:$fqn"
            fqnQueries += fqn
            infoByShortName.values.flatten().filter { it.fqn == fqn }
        },
        findClassesByShortName = { name ->
            smartModeCallOrder += "short:$name"
            shortNameQueries += name
            infoByShortName[name] ?: emptyList()
        },
        getAllClassNames = {
            smartModeCallOrder += "allNames"
            infoByShortName.keys.toTypedArray()
        },
        waitForSmartMode = { smartModeCallOrder += "waitForSmartMode" }
    )

    @Nested
    inner class FqnLookup {

        @Test
        fun `FQN pattern dispatches to findClassesByFqn`() {
            tool.lookup("java.util.ArrayList")

            assertThat(fqnQueries).containsExactly("java.util.ArrayList")
            assertThat(shortNameQueries).isEmpty()
        }

        @Test
        fun `FQN lookup includes matching class in result`() {
            val result = tool.lookup("java.util.ArrayList")

            assertThat(result.classes).containsExactly(arrayListInfo)
        }
    }

    @Nested
    inner class SimpleNameLookup {

        @Test
        fun `simple name dispatches to findClassesByShortName`() {
            tool.lookup("ArrayList")

            assertThat(shortNameQueries).containsExactly("ArrayList")
            assertThat(fqnQueries).isEmpty()
        }

        @Test
        fun `simple name lookup includes matching class in result`() {
            val result = tool.lookup("ArrayList")

            assertThat(result.classes).containsExactly(arrayListInfo)
        }
    }

    @Nested
    inner class WildcardLookup {

        @Test
        fun `wildcard pattern filters FQNs by glob`() {
            val result = tool.lookup("*List")

            assertThat(result.classes.map { it.fqn })
                .contains("java.util.ArrayList", "java.util.LinkedList")
                .doesNotContain("java.util.HashMap", "java.util.TreeMap", "java.lang.Map")
        }

        @Test
        fun `wildcard with dot filters by package and suffix`() {
            val result = tool.lookup("java.util.*Map")

            assertThat(result.classes.map { it.fqn })
                .contains("java.util.HashMap", "java.util.TreeMap")
                .doesNotContain("java.lang.Map")
        }

        @Test
        fun `wildcard only expands short names that could match the pattern`() {
            tool.lookup("*List")

            assertThat(shortNameQueries)
                .contains("ArrayList", "LinkedList")
                .doesNotContain("HashMap", "TreeMap", "Map")
        }
    }

    @Nested
    inner class ResultTruncation {

        @Test
        fun `results capped at maxResults with truncated flag and totalMatches`() {
            val manyClasses = (1..30).map { aClassInfo("com.example.Class$it") }
            val tool = toolReturningFromShortName("Class", manyClasses)

            val result = tool.lookup("Class")

            assertThat(result.classes).hasSize(DEFAULT_MAX_RESULTS)
            assertThat(result.truncated).isTrue()
            assertThat(result.totalMatches).isEqualTo(30)
        }

        @Test
        fun `no truncation when matches are under the limit`() {
            val fewClasses = (1..5).map { aClassInfo("com.example.Class$it") }
            val tool = toolReturningFromShortName("Class", fewClasses)

            val result = tool.lookup("Class")

            assertThat(result.classes).hasSize(5)
            assertThat(result.truncated).isFalse()
            assertThat(result.totalMatches).isEqualTo(5)
        }
    }

    @Nested
    inner class SmartModeOrdering {

        @Test
        fun `waitForSmartMode called before PSI access`() {
            tool.lookup("ArrayList")

            assertThat(smartModeCallOrder.first()).isEqualTo("waitForSmartMode")
        }
    }
}

private fun aClassInfo(fqn: String) = ClassInfo(
    fqn = fqn,
    methods = emptyList(),
    fields = emptyList(),
    interfaces = emptyList(),
    superclass = null
)

private fun toolReturningFromShortName(shortName: String, classes: List<ClassInfo>) =
    ClassLookupTool(
        findClassesByFqn = { emptyList() },
        findClassesByShortName = { name -> if (name == shortName) classes else emptyList() },
        getAllClassNames = { emptyArray() },
        waitForSmartMode = {}
    )
