package com.caye.commithelper.adapter

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import kotlin.io.path.createTempDirectory

/**
 * Regression cover for "No changes are checked in the commit panel." shown while files were in
 * fact checked.
 *
 * `CommitMessage.uiDataSnapshot` only publishes `COMMIT_MESSAGE_CONTROL` and
 * `COMMIT_MESSAGE_DOCUMENT` (verified against IC-262 bytecode), so an action living in the
 * commit message field cannot rely on the toolbar's own data context to see the checked
 * changes: the surrounding commit panel/dialog has to be asked separately.
 */
class CommitContextAdapterTest : BasePlatformTestCase() {

    fun testToolbarContextWithRealChangesIsStillPreferred() {
        val change = changeFor("Direct.java")
        val context = object : DataContext {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any?> getData(dataKey: DataKey<T>): T? =
                if (dataKey == CHANGES_KEY) arrayOf(change) as T else null

            override fun getData(dataId: String): Any? =
                if (dataId == CHANGES_KEY.name) arrayOf(change) else null
        }

        val read = CommitContextAdapter.read(project, context)

        assertEquals(1, read.includedChanges.size)
        assertFalse(read.isEmpty)
        assertNull("a readable context is not a failure", read.readFailure)
    }

    fun testUnreadableContextIsReportedAsSuchInsteadOfAsAnEmptySelection() {
        val read = CommitContextAdapter.read(project, DocumentOnlyContext(null))

        assertTrue("nothing was readable, so the context is empty", read.isEmpty)
        assertNotNull(
            "an unreadable panel must be distinguishable from an empty selection",
            read.readFailure,
        )
    }

    fun testWorkflowUiDuckTypingAcceptsOnlyCommitWorkflowShapedObjects() {
        assertNotNull(CommitContextAdapter.asWorkflowUi(FakeWorkflowUi()))
        assertNull(CommitContextAdapter.asWorkflowUi(null))
        assertNull(CommitContextAdapter.asWorkflowUi("not a workflow ui"))
        assertNull(CommitContextAdapter.asWorkflowUi(Any()))
    }

    /**
     * The component-context source (`DataManager.getDataContext(messageComponent)`) cannot be
     * covered here: the platform installs `HeadlessDataManager` in tests, and that one does not
     * walk the component tree. What is covered instead is the candidate selection it feeds.
     */
    fun testWorkflowUiSelectionPrefersTheFirstUsableCandidate() {
        val contextUi = FakeWorkflowUi()
        val componentUi = FakeWorkflowUi()
        val dialogUi = FakeWorkflowUi()

        assertSame(contextUi, CommitContextAdapter.selectWorkflowUi(contextUi, componentUi, dialogUi))
        assertSame(componentUi, CommitContextAdapter.selectWorkflowUi(null, componentUi, dialogUi))
        assertSame(dialogUi, CommitContextAdapter.selectWorkflowUi(null, null, dialogUi))
        assertSame(dialogUi, CommitContextAdapter.selectWorkflowUi("bogus", Any(), dialogUi))
        assertNull(CommitContextAdapter.selectWorkflowUi(null, "bogus", Any()))
    }

    private fun changeFor(name: String): Change {
        val file = createTempDirectory("commit-ctx").resolve(name).toFile().apply { writeText("class X\n") }
        val path = VcsUtil.getFilePath(file, false)
        return Change(FakeRevision(path), FakeRevision(path))
    }

    private class FakeRevision(private val path: FilePath) : ContentRevision {
        override fun getContent(): String = "class X\n"
        override fun getFile(): FilePath = path
        override fun getRevisionNumber(): VcsRevisionNumber = VcsRevisionNumber.Int(1)
    }

    /** Exactly what the commit message toolbar carries in the new UI: the message keys only. */
    private class DocumentOnlyContext(private val document: Document?) : DataContext {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> getData(dataKey: DataKey<T>): T? =
            if (dataKey == VcsDataKeys.COMMIT_MESSAGE_DOCUMENT && document != null) document as T else null

        override fun getData(dataId: String): Any? = null
    }

    /** Shape-compatible with `com.intellij.vcs.commit.CommitWorkflowUi` without referencing it. */
    private class FakeWorkflowUi {
        @Suppress("unused")
        fun getIncludedChanges(): List<Change> = emptyList()

        @Suppress("unused")
        fun getIncludedUnversionedFiles(): List<FilePath> = emptyList()
    }

    private companion object {
        val CHANGES_KEY = VcsDataKeys.CHANGES
    }
}
