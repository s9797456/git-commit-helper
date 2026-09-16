package com.caye.commithelper.action

import com.caye.commithelper.adapter.CommitContextAdapter
import com.caye.commithelper.adapter.CommitMessageTarget
import com.caye.commithelper.adapter.LegacyMessageTarget
import com.caye.commithelper.ui.PreviewDialog
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The preview dialog itself is modal and cannot be driven headlessly, so the writer's merge
 * semantics and the pre-2024.2 data-context path are asserted directly. Together they cover
 * the promise that an existing commit message is never overwritten without consent.
 */
class CommitMessageWriteTest : BasePlatformTestCase() {

    fun testEmptyBoxIsFilledWithoutAnyDialog() {
        val target = RecordingTarget("")
        CommitMessageWriter.write(project, target, "feat: something\n\n- [feat] (core) a thing\n")
        assertEquals("feat: something\n\n- [feat] (core) a thing\n", target.current)
        assertEquals("setText must be called exactly once", 1, target.writes)
    }

    fun testBlankGeneratedTextNeverTouchesTheExistingMessage() {
        val target = RecordingTarget("fix: hand written")
        CommitMessageWriter.write(project, target, "   \n")
        assertEquals("fix: hand written", target.current)
        assertEquals(0, target.writes)
    }

    fun testReplaceUsesTheGeneratedTextVerbatim() {
        val merged = CommitMessageWriter.merge("old message", "new message\n", PreviewDialog.Choice.REPLACE)
        assertEquals("new message\n", merged)
    }

    fun testAppendKeepsTheExistingTextAndSeparatesItByABlankLine() {
        val merged = CommitMessageWriter.merge("old message", "  new message  \n", PreviewDialog.Choice.APPEND)
        assertEquals("old message\n\nnew message\n", merged)
    }

    fun testCancellingReturnsNullSoTheCallerLeavesTheBoxAlone() {
        assertNull(CommitMessageWriter.merge("old message", "new", PreviewDialog.Choice.NONE))
    }

    fun testLegacyDataContextIsReadThroughTheOldKeys() {
        val document = EditorFactory.getInstance().createDocument("existing text")
        val context = CommitContextAdapter.read(project, FakeDataContext(document))

        val target = context.target
        assertTrue("the pre-2024.2 path must use the legacy target", target is LegacyMessageTarget)
        assertEquals("existing text", target.getText())

        target.setText("replaced")
        assertEquals("replaced", document.text)
        assertEquals("no changes were supplied", emptyList<Change>(), context.includedChanges)
        assertTrue("an empty context must report itself as empty", context.isEmpty)
        assertNotNull(
            "with no changes source at all this must read as an unreadable panel, not as an empty selection",
            context.readFailure,
        )
    }

    private class RecordingTarget(initial: String) : CommitMessageTarget {
        var current: String = initial
            private set
        var writes: Int = 0
            private set

        override fun getText(): String = current

        override fun setText(text: String) {
            current = text
            writes++
        }

        override fun setLoading(loading: Boolean) = Unit
    }

    private class FakeDataContext(private val document: com.intellij.openapi.editor.Document) : DataContext {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getData(dataKey: DataKey<T>): T? =
            if (dataKey == VcsDataKeys.COMMIT_MESSAGE_DOCUMENT) document as T else null

        override fun getData(dataId: String): Any? = null
    }
}
