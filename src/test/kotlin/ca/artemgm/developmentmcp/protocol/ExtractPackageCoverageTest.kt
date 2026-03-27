package ca.artemgm.developmentmcp.protocol

import com.intellij.rt.coverage.data.ClassData
import com.intellij.rt.coverage.data.LineData
import com.intellij.rt.coverage.data.ProjectData
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ExtractPackageCoverageTest {

    @Nested
    inner class SinglePackage {

        @Test
        fun `counts covered and uncovered lines`() {
            val projectData = projectData(
                classWithLines("com.example.Foo", hit = listOf(1, 2, 3), miss = listOf(4, 5))
            )

            val coverage = extractPackageCoverage(projectData, setOf("com.example"))

            assertThat(coverage.coveredLines).isEqualTo(3)
            assertThat(coverage.totalLines).isEqualTo(5)
        }

        @Test
        fun `aggregates across multiple classes in the same package`() {
            val projectData = projectData(
                classWithLines("com.example.Foo", hit = listOf(1, 2), miss = listOf(3)),
                classWithLines("com.example.Bar", hit = listOf(1), miss = listOf(2, 3, 4))
            )

            val coverage = extractPackageCoverage(projectData, setOf("com.example"))

            assertThat(coverage.totalLines).isEqualTo(7)
            assertThat(coverage.coveredLines).isEqualTo(3)
        }

        @Test
        fun `excludes test classes from coverage`() {
            val coverage = extractCoverageWithTestClass("com.example.FooTest",
                realHit = listOf(1, 2), realMiss = listOf(3))

            assertThat(coverage.totalLines).isEqualTo(3)
            assertThat(coverage.coveredLines).isEqualTo(2)
        }
    }

    @Nested
    inner class MultiPackage {

        private val twoPackageData = projectData(
            classWithLines("com.example.a.Foo", hit = listOf(1, 2), miss = listOf(3)),
            classWithLines("com.example.b.Bar", hit = listOf(1), miss = listOf(2))
        )

        @Test
        fun `merges coverage across two packages`() {
            val coverage = extractPackageCoverage(twoPackageData, setOf("com.example.a", "com.example.b"))

            assertThat(coverage.totalLines).isEqualTo(5)
            assertThat(coverage.coveredLines).isEqualTo(3)
            assertThat(coverage.classCoverages).hasSize(2)
        }

        @Test
        fun `single-package query ignores classes from other packages`() {
            val coverageA = extractPackageCoverage(twoPackageData, setOf("com.example.a"))

            assertThat(coverageA.totalLines).isEqualTo(3)
            assertThat(coverageA.coveredLines).isEqualTo(2)
            assertThat(coverageA.classCoverages).hasSize(1)
        }

        @Test
        fun `package name label lists all packages when multiple are queried`() {
            val projectData = projectData(
                classWithLines("com.example.a.Foo", hit = listOf(1), miss = emptyList()),
                classWithLines("com.example.b.Bar", hit = listOf(1), miss = emptyList())
            )

            val coverage = extractPackageCoverage(projectData, setOf("com.example.b", "com.example.a"))

            assertThat(coverage.packageName).isEqualTo("com.example.a, com.example.b")
        }

        @Test
        fun `single package uses package name directly as label`() {
            val projectData = projectData(
                classWithLines("com.example.Foo", hit = listOf(1), miss = emptyList())
            )

            val coverage = extractPackageCoverage(projectData, setOf("com.example"))

            assertThat(coverage.packageName).isEqualTo("com.example")
        }
    }

    @Nested
    inner class TestClassExclusion {

        @Test
        fun `excludes classes ending with Test`() {
            assertTestClassExcluded("com.example.FooTest")
        }

        @Test
        fun `excludes classes ending with Tests`() {
            assertTestClassExcluded("com.example.FooTests")
        }

        @Test
        fun `excludes classes ending with TestCase`() {
            assertTestClassExcluded("com.example.FooTestCase")
        }

        @Test
        fun `excludes classes ending with IT`() {
            assertTestClassExcluded("com.example.FooIT")
        }

        @Test
        fun `excludes inner classes of test classes`() {
            val projectData = projectData(
                classWithLines("com.example.Foo", hit = listOf(1), miss = emptyList()),
                classWithLines("com.example.FooTest\$Nested", hit = listOf(1, 2, 3), miss = emptyList())
            )

            val coverage = extractPackageCoverage(projectData, setOf("com.example"))

            assertThat(coverage.totalLines).isEqualTo(1)
        }

        private fun assertTestClassExcluded(testClassName: String) {
            val coverage = extractCoverageWithTestClass(testClassName)
            assertThat(coverage.totalLines).isEqualTo(2)
        }
    }
}

private fun extractCoverageWithTestClass(
    testClassName: String,
    realHit: List<Int> = listOf(1),
    realMiss: List<Int> = listOf(2)
): PackageCoverage {
    val projectData = projectData(
        classWithLines("com.example.Foo", hit = realHit, miss = realMiss),
        classWithLines(testClassName, hit = listOf(1, 2, 3, 4, 5), miss = emptyList())
    )
    return extractPackageCoverage(projectData, setOf("com.example"))
}

private fun projectData(vararg classes: Pair<String, ClassData>): ProjectData {
    val pd = ProjectData()
    for ((name, classData) in classes) pd.addClassData(classData)
    return pd
}

private fun classWithLines(
    className: String,
    hit: List<Int>,
    miss: List<Int>
): Pair<String, ClassData> {
    val allLines = (hit + miss).sorted()
    val maxLine = allLines.maxOrNull() ?: 0
    val lines = arrayOfNulls<LineData>(maxLine + 1)
    for (lineNum in allLines) {
        val ld = LineData(lineNum, "method()V")
        if (lineNum in hit) ld.setHits(1)
        lines[lineNum] = ld
    }
    val classData = ClassData(className)
    classData.setLines(lines)
    return className to classData
}
