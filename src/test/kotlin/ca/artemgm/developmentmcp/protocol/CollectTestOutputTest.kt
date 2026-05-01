package ca.artemgm.developmentmcp.protocol

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CollectTestOutputTest {

    @Nested
    inner class OutputNodes {

        @Test
        fun `includes leaf and excludes root`() {
            val (root, leaf) = rootWithLeaf()

            val nodes = outputNodes(root)

            assertThat(nodes).containsExactly(leaf)
            assertThat(nodes).doesNotContain(root)
        }

        @Test
        fun `includes intermediate suite nodes`() {
            val root = SMTestProxy("[root]", true, null)
            val suite = SMTestProxy("MyClass", true, null)
            val leaf = SMTestProxy("test", false, null).apply { setFinished() }
            suite.addChild(leaf)
            suite.setFinished()
            root.addChild(suite)
            root.setFinished()

            assertThat(outputNodes(root)).contains(suite, leaf)
        }

        @Test
        fun `includes deeply nested suite and leaf nodes`() {
            val root = SMTestProxy("[root]", true, null)
            val outerSuite = SMTestProxy("OuterClass", true, null)
            val innerSuite = SMTestProxy("InnerClass", true, null)
            val leaf = SMTestProxy("test", false, null).apply { setFinished() }
            innerSuite.addChild(leaf)
            innerSuite.setFinished()
            outerSuite.addChild(innerSuite)
            outerSuite.setFinished()
            root.addChild(outerSuite)
            root.setFinished()

            val nodes = outputNodes(root)

            assertThat(nodes).contains(outerSuite, innerSuite, leaf)
            assertThat(nodes).doesNotContain(root)
        }

        @Test
        fun `empty root produces no nodes`() {
            val root = SMTestProxy("[root]", true, null).apply { setFinished() }

            assertThat(outputNodes(root)).isEmpty()
        }

        @Test
        fun `suite with multiple leaves includes all`() {
            val root = SMTestProxy("[root]", true, null)
            val suite = SMTestProxy("MyClass", true, null)
            val leaf1 = SMTestProxy("test1", false, null).apply { setFinished() }
            val leaf2 = SMTestProxy("test2", false, null).apply { setFinished() }
            suite.addChild(leaf1)
            suite.addChild(leaf2)
            suite.setFinished()
            root.addChild(suite)
            root.setFinished()

            assertThat(outputNodes(root)).containsExactly(suite, leaf1, leaf2)
        }
    }
}

private fun rootWithLeaf(): Pair<SMTestProxy, SMTestProxy> {
    val root = SMTestProxy("[root]", true, null)
    val leaf = SMTestProxy("test", false, null).apply { setFinished() }
    root.addChild(leaf)
    root.setFinished()
    return root to leaf
}
