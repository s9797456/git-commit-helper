package com.caye.commithelper.adapter

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.ui.CommitMessage
import java.io.File

/** Where the generated text is written, and how the "busy" state is shown. */
interface CommitMessageTarget {
    fun getText(): String
    fun setText(text: String)
    fun setLoading(loading: Boolean)
}

class NoOpTarget : CommitMessageTarget {
    override fun getText(): String = ""
    override fun setText(text: String) = Unit
    override fun setLoading(loading: Boolean) = Unit
}

/**
 * 2024.2+ commit UI (`CommitMessageUi`): supports reading text and the loading spinner.
 *
 * Everything here is reflective ON PURPOSE: `com.intellij.vcs.commit.*` does not exist in the
 * 2024.1 compile target, so a static reference would break the since-build 241 promise.
 */
class ReflectiveWorkflowTarget(private val workflowUi: Any) : CommitMessageTarget {

    private val messageUi: Any? = invoke(workflowUi, "getCommitMessageUi")

    override fun getText(): String = invoke(messageUi, "getText") as? String ?: ""

    override fun setText(text: String) {
        // The 2024.2+ implementation may or may not wrap its own document mutation in a write
        // action; nesting one is legal either way, and without it a strict document write would
        // fail on the EDT.
        val app = ApplicationManager.getApplication()
        val apply = Runnable { invoke(messageUi, "setText", text) }
        if (app.isWriteAccessAllowed) apply.run() else app.runWriteAction(apply)
    }

    override fun setLoading(loading: Boolean) {
        invoke(messageUi, if (loading) "startLoading" else "stopLoading")
    }

    private fun invoke(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        return try {
            val method = target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == args.size
            } ?: return null
            method.isAccessible = true
            method.invoke(target, *args)
        } catch (e: Exception) {
            LOG.debug("Commit Helper: reflective call $name failed", e)
            null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ReflectiveWorkflowTarget::class.java)
    }
}

/** Fallback target used by the pre-2024.2 commit dialog. */
class LegacyMessageTarget(
    private val commitMessageI: CommitMessageI?,
    private val document: Document?,
) : CommitMessageTarget {
    override fun getText(): String = document?.text ?: ""

    override fun setText(text: String) {
        // Mutating a Document requires a write action; the caller reaches us on the EDT from an
        // invokeLater callback, which is not inside one.
        val app = ApplicationManager.getApplication()
        val apply = Runnable {
            document?.setText(text)
            commitMessageI?.setCommitMessage(text)
        }
        if (app.isWriteAccessAllowed) apply.run() else app.runWriteAction(apply)
    }

    override fun setLoading(loading: Boolean) = Unit
}

/** Everything the generator needs from the commit UI. */
data class CommitContext(
    val project: Project,
    val target: CommitMessageTarget,
    val includedChanges: List<Change>,
    val includedUnversioned: List<FilePath>,
    /** Non-null when the commit panel could not be read at all, as opposed to being empty. */
    val readFailure: String? = null,
) {
    val isEmpty: Boolean get() = includedChanges.isEmpty() && includedUnversioned.isEmpty()

    fun unversionedFiles(): List<File> = includedUnversioned.map { it.ioFile }
}

/**
 * Reads the commit workflow out of the action's data context.
 *
 * New UI (2024.2+) is reached reflectively through the `COMMIT_WORKFLOW_UI` data key; the
 * older modal dialog is read through the statically available keys. This is the only class
 * in the plugin that uses reflection.
 */
object CommitContextAdapter {

    private val LOG = Logger.getInstance(CommitContextAdapter::class.java)

    private const val VCS_DATA_KEYS = "com.intellij.openapi.vcs.VcsDataKeys"
    private const val WORKFLOW_UI_KEY = "COMMIT_WORKFLOW_UI"

    /** Shown when the commit panel could not be read at all, rather than being empty. */
    const val UNREADABLE_PANEL =
        "Commit Helper could not read the commit panel. Please invoke the action from the commit " +
            "message box (see idea.log for details)."

    fun read(project: Project, context: DataContext): CommitContext {
        val document = context.getData(VcsDataKeys.COMMIT_MESSAGE_DOCUMENT)
        // The commit message component stores itself on its own document
        // (`document.putUserData(DATA_KEY, this)` in CommitMessage's constructor). That is the
        // only published bridge from the toolbar to the surrounding commit panel or dialog.
        val message = document?.getUserData(CommitMessage.DATA_KEY)

        // 1) Straight from the action's data context. The non-modal commit panel publishes the
        //    workflow UI here.
        // 2) Rebuilt from the message component. The platform then walks the UiDataProvider
        //    chain of the surrounding panel, which the toolbar's own context does not carry.
        // 3) The modal commit dialog is a workflow UI but not a component data provider at all,
        //    so it never shows up in any data context: ask for the enclosing dialog directly.
        val componentContext = message?.let { componentDataContext(it) }
        val workflowUi = selectWorkflowUi(
            rawWorkflowUi(context),
            componentContext?.let { rawWorkflowUi(it) },
            message?.let { enclosingDialog(it) },
        )
        workflowUi?.let { ui -> fromWorkflowUi(project, ui)?.let { return it } }

        // 4) Legacy keys. The rebuilt context is a superset of the toolbar's own.
        val sources = listOfNotNull(context, componentContext)
        val changes = sources.firstNotNullOfOrNull { candidate ->
            candidate.getData(VcsDataKeys.CHANGES)?.toList()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        val target = LegacyMessageTarget(
            commitMessageI = context.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL),
            document = document,
        )
        val readable = message != null || componentContext != null ||
            context.getData(VcsDataKeys.CHANGES) != null

        if (!readable) {
            LOG.warn(
                "Commit Helper could not read the commit panel. context=${context.javaClass.name}" +
                    ", document=${document != null}, messageComponent=null" +
                    ", workflowUi=null, COMMIT_MESSAGE_CONTROL=" +
                    (context.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)?.javaClass?.name ?: "null") +
                    ". Invoke the action from the commit message toolbar.",
            )
        }
        return CommitContext(
            project = project,
            target = target,
            includedChanges = changes,
            includedUnversioned = emptyList(),
            readFailure = if (readable) null else UNREADABLE_PANEL,
        )
    }

    /** Returns null when the workflow UI could not be queried (as opposed to reporting no changes). */
    private fun fromWorkflowUi(project: Project, ui: Any): CommitContext? {
        val changes = invokeList(ui, "getIncludedChanges")
        val unversioned = invokeList(ui, "getIncludedUnversionedFiles")
        if (changes == null && unversioned == null) {
            LOG.debug("Commit Helper: workflow UI ${ui.javaClass.name} did not answer the included-changes calls")
            return null
        }
        return CommitContext(
            project = project,
            target = ReflectiveWorkflowTarget(ui),
            includedChanges = changes.orEmpty().filterIsInstance<Change>(),
            includedUnversioned = unversioned.orEmpty().filterIsInstance<FilePath>(),
        )
    }

    private fun rawWorkflowUi(context: DataContext): Any? {
        return try {
            val dataKey = dataKey() ?: return null
            val getData = DataContext::class.java.getMethod("getData", DataKey::class.java)
            getData.invoke(context, dataKey)
        } catch (e: Exception) {
            LOG.debug("Commit Helper: COMMIT_WORKFLOW_UI unavailable (pre-2024.2 IDE)", e)
            null
        }
    }

    /**
     * The commit panel/dialog is not always on the toolbar's data-context chain. Rebuilding the
     * context from the message component lets the platform walk the surrounding panel's
     * `UiDataProvider` chain for us.
     *
     * NOTE: this only works with the production data manager; the headless one used in tests
     * does not walk the component tree, so this source cannot be covered by a unit test.
     */
    private fun componentDataContext(message: CommitMessage): DataContext? = try {
        DataManager.getInstance().getDataContext(message)
    } catch (e: Exception) {
        LOG.debug("Commit Helper: could not build a data context from the commit message component", e)
        null
    }

    /** The modal commit dialog is a workflow UI but is not on any component data-context chain. */
    private fun enclosingDialog(message: CommitMessage): Any? = try {
        DialogWrapper.findInstance(message)
    } catch (e: Exception) {
        LOG.debug("Commit Helper: no enclosing dialog for the commit message component", e)
        null
    }

    /** First candidate that actually looks like a commit workflow UI. */
    internal fun selectWorkflowUi(vararg candidates: Any?): Any? =
        candidates.firstNotNullOfOrNull { asWorkflowUi(it) }

    /**
     * True when [candidate] looks like a commit workflow UI. Duck-typed on purpose:
     * `com.intellij.vcs.commit.CommitWorkflowUi` does not exist on the 2024.1 compile target.
     */
    internal fun asWorkflowUi(candidate: Any?): Any? {
        if (candidate == null) return null
        val methods = candidate.javaClass.methods
        val hasChanges = methods.any { it.name == "getIncludedChanges" && it.parameterCount == 0 }
        val hasUnversioned = methods.any { it.name == "getIncludedUnversionedFiles" && it.parameterCount == 0 }
        return if (hasChanges && hasUnversioned) candidate else null
    }

    private fun dataKey(): Any? = try {
        val clazz = Class.forName(VCS_DATA_KEYS)
        clazz.getField(WORKFLOW_UI_KEY).get(null)
    } catch (_: Exception) {
        null
    }

    private fun invokeList(target: Any, methodName: String): List<*>? = try {
        val method = target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 }
        method?.invoke(target) as? List<*>
    } catch (e: Exception) {
        LOG.debug("Commit Helper: reflective call $methodName failed", e)
        null
    }
}
