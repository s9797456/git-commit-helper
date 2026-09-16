package com.caye.commithelper.action

import com.caye.commithelper.adapter.CommitContext
import com.caye.commithelper.adapter.CommitContextAdapter
import com.caye.commithelper.adapter.CommitMessageTarget
import com.caye.commithelper.notify.Notifier
import com.caye.commithelper.service.ChangelogOutcome
import com.caye.commithelper.service.CommitHelperService
import com.caye.commithelper.service.GenerationOutcome
import com.caye.commithelper.ui.ChangelogDialog
import com.caye.commithelper.ui.PreviewDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/** Writes the generated text into the commit message box, asking first when it is not empty. */
object CommitMessageWriter {

    fun write(project: Project, target: CommitMessageTarget, text: String) {
        if (text.isBlank()) return
        val current = target.getText()
        if (current.isBlank()) {
            target.setText(text)
            return
        }
        val dialog = PreviewDialog(project, current, text)
        if (!dialog.showAndGet()) return
        merge(current, text, dialog.choice)?.let { target.setText(it) }
    }

    /**
     * Pure merge behind the preview dialog. Returns `null` when the user cancelled, so the
     * caller leaves the existing message untouched — existing text is never lost silently.
     */
    internal fun merge(current: String, generated: String, choice: PreviewDialog.Choice): String? =
        when (choice) {
            PreviewDialog.Choice.REPLACE -> generated
            PreviewDialog.Choice.APPEND -> current.trimEnd() + "\n\n" + generated.trim() + "\n"
            PreviewDialog.Choice.NONE -> null
        }
}

/** Shared flow: collect the checked changes, generate in the background, then write. */
object CommitHelperFlow {

    fun organize(project: Project, context: CommitContext, useLlm: Boolean = true) {
        object : Task.Backgroundable(project, "Organizing Commit Message", true) {
            override fun run(indicator: ProgressIndicator) {
                context.target.setLoading(true)
                val outcome = try {
                    CommitHelperService(project).organize(context, indicator, useLlm)
                } finally {
                    ApplicationManager.getApplication().invokeLater { context.target.setLoading(false) }
                }
                ApplicationManager.getApplication().invokeLater { applyOutcome(project, context, outcome) }
            }
        }.queue()
    }

    fun generateChangelog(project: Project, context: CommitContext) {
        object : Task.Backgroundable(project, "Generating Changelog", true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = CommitHelperService(project).generateChangelog(context, indicator)
                ApplicationManager.getApplication().invokeLater {
                    ChangelogDialog(
                        project = project,
                        commitMessage = context.target.getText(),
                        changelog = outcome.changelog,
                        prDescription = outcome.prDescription,
                    ).show()
                }
            }
        }.queue()
    }

    private fun applyOutcome(project: Project, context: CommitContext, outcome: GenerationOutcome) {
        if (outcome.text.isBlank()) {
            outcome.errorMessage?.let { Notifier.warn(project, it) }
            return
        }
        CommitMessageWriter.write(project, context.target, outcome.text)

        when {
            outcome.usedFallback && outcome.errorMessage != null -> Notifier.warn(
                project,
                "Model call failed (${outcome.errorMessage}). Inserted the local grouping result instead.",
                retry = { organize(project, context, useLlm = true) },
            )
            outcome.usedFallback -> Notifier.info(
                project,
                "Inserted the local grouping result (${outcome.providerLabel}).",
            )
            else -> Notifier.info(
                project,
                "Commit message generated with ${outcome.providerLabel}." +
                    if (outcome.sampled) " Diff was sampled: ${outcome.includedFiles} files included." else "",
            )
        }
    }
}

/** Entry point in the commit message toolbar (`Vcs.MessageActionGroup`). */
class OrganizeCommitMessageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = CommitContextAdapter.read(project, e.dataContext)
        if (context.isEmpty) {
            Notifier.warn(project, context.readFailure ?: "No changes are checked in the commit panel.")
            return
        }
        CommitHelperFlow.organize(project, context)
    }
}

/** M3 entry point: changelog + PR description preview (copy only). */
class GenerateChangelogAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val context = CommitContextAdapter.read(project, e.dataContext)
        if (context.isEmpty) {
            Notifier.warn(project, context.readFailure ?: "No changes are checked in the commit panel.")
            return
        }
        CommitHelperFlow.generateChangelog(project, context)
    }
}

/** Kept for future use by tests that need the outcome shape without a dialog. */
internal fun ChangelogOutcome.isUsable(): Boolean = changelog.isNotBlank()
