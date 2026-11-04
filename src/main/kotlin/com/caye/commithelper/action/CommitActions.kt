package com.caye.commithelper.action

import com.caye.commithelper.adapter.CommitContext
import com.caye.commithelper.adapter.CommitContextAdapter
import com.caye.commithelper.adapter.CommitMessageTarget
import com.caye.commithelper.collect.GitOps
import com.caye.commithelper.collect.GitRootLocator
import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.issue.IssueKeyExtractor
import com.caye.commithelper.model.ExtraContext
import com.caye.commithelper.notify.Notifier
import com.caye.commithelper.notify.OnboardingNotifier
import com.caye.commithelper.service.ChangelogOutcome
import com.caye.commithelper.service.CommitHelperService
import com.caye.commithelper.service.GenerationOutcome
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.ui.ChangelogDialog
import com.caye.commithelper.ui.CommitBoxSizing
import com.caye.commithelper.ui.ExtraContextDialog
import com.caye.commithelper.ui.PreviewDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.io.File

/** Writes the generated text into the commit message box, asking first when it is not empty. */
object CommitMessageWriter {

    fun write(
        project: Project,
        target: CommitMessageTarget,
        text: String,
        allowAppend: Boolean = true,
    ) {
        if (text.isBlank()) return
        val current = target.getText()
        if (current.isBlank()) {
            target.setText(text)
            return
        }
        val dialog = PreviewDialog(project, current, text, allowAppend)
        if (!dialog.showAndGet()) return
        merge(current, text, dialog.choice)?.let { target.setText(it) }
    }

    /**
     * Pure merge behind the preview dialog. Returns null when the user cancelled, so the
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

    fun organize(
        project: Project,
        context: CommitContext,
        useLlm: Boolean = true,
        extra: ExtraContext? = null,
    ) {
        runGeneration(project, context, CommitHelperBundle.message("task.generatingCommitMessage"), allowAppend = true) {
            CommitHelperService(project).organize(context, it, useLlm, extra)
        }
    }

    /** LLM-only reformat of the existing message. The result replaces the text after confirmation. */
    fun format(project: Project, context: CommitContext) {
        runGeneration(project, context, CommitHelperBundle.message("task.formattingCommitMessage"), allowAppend = false) {
            CommitHelperService(project).format(context, it)
        }
    }

    /**
     * Ask for extra context, then run the normal generation with it attached.
     *
     * Issue detection runs git commands ([GitOps]), which must never happen on the EDT: git4idea's
     * authentication handler waits for the IDE built-in server and the platform asserts
     * "Should not wait for built-in server on EDT" (reported as an IDE internal error, 0.2.0).
     */
    fun generateWithContext(project: Project, context: CommitContext) {
        object : Task.Backgroundable(project, CommitHelperBundle.message("task.preparingContext"), true) {
            override fun run(indicator: ProgressIndicator) {
                val detectedIssue = try {
                    detectIssue(project, context)
                } catch (_: Exception) {
                    null
                }
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    val dialog = ExtraContextDialog(
                        project = project,
                        detectedIssue = detectedIssue,
                        prefillNote = "",
                    )
                    if (!dialog.showAndGet()) return@invokeLater
                    organize(project, context, useLlm = true, extra = dialog.result())
                }
            }
        }.queue()
    }

    private fun runGeneration(
        project: Project,
        context: CommitContext,
        title: String,
        allowAppend: Boolean,
        generation: (ProgressIndicator) -> GenerationOutcome,
    ) {
        val app = ApplicationManager.getApplication()
        // setLoading flips Swing state (disabled editor, spinner), so it must run on the EDT.
        if (app.isDispatchThread) {
            context.target.setLoading(true)
        } else {
            app.invokeLater { context.target.setLoading(true) }
        }
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val outcome = try {
                    generation(indicator)
                } catch (_: ProcessCanceledException) {
                    null
                } catch (e: Exception) {
                    GenerationOutcome(
                        text = "",
                        usedFallback = true,
                        errorMessage = e.message ?: e.javaClass.simpleName,
                    )
                }
                // One EDT callback: stop the spinner, write the text and resize in a deterministic order.
                app.invokeLater {
                    context.target.setLoading(false)
                    outcome?.let { applyOutcome(project, context, it, allowAppend) }
                }
            }
        }.queue()
    }

    fun generateChangelog(project: Project, context: CommitContext) {
        object : Task.Backgroundable(project, CommitHelperBundle.message("task.generatingChangelog"), true) {
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

    private fun applyOutcome(
        project: Project,
        context: CommitContext,
        outcome: GenerationOutcome,
        allowAppend: Boolean,
    ) {
        if (outcome.text.isBlank()) {
            outcome.errorMessage?.let { Notifier.warn(project, it) }
            return
        }
        CommitMessageWriter.write(project, context.target, outcome.text, allowAppend)
        CommitBoxSizing.afterWrite(context.messageComponent)

        val detail = outcome.warning?.let { "\n\n" + it }.orEmpty()
        when {
            outcome.usedFallback && outcome.errorMessage != null -> Notifier.warn(
                project,
                CommitHelperBundle.message("notification.modelCallFailed", outcome.errorMessage.orEmpty()) + detail,
                retry = { organize(project, context, useLlm = true) },
            )
            outcome.usedFallback -> Notifier.info(
                project,
                CommitHelperBundle.message("notification.localFallbackInserted", outcome.providerLabel) + detail,
            )
            outcome.warning != null -> Notifier.warn(
                project,
                CommitHelperBundle.message("notification.generated", outcome.providerLabel) + detail,
            )
            else -> Notifier.info(
                project,
                if (outcome.sampled) {
                    CommitHelperBundle.message(
                        "notification.generatedSampled",
                        outcome.providerLabel,
                        outcome.includedFiles,
                    )
                } else {
                    CommitHelperBundle.message("notification.generated", outcome.providerLabel)
                },
            )
        }
    }

    private fun detectIssue(project: Project, context: CommitContext): String? {
        val gitOps = GitOps.forPath(project, firstChangeRoot(project, context)) ?: return null
        return IssueKeyExtractor.extract(gitOps.branchName(), gitOps.recentSubjects(5))
    }

    private fun firstChangeRoot(project: Project, context: CommitContext): File? {
        val change = context.includedChanges.firstOrNull() ?: return null
        val file = (change.afterRevision?.file ?: change.beforeRevision?.file)?.ioFile ?: return null
        return GitRootLocator.findRoot(file.parentFile)
    }
}

/** Shared visibility switch used by all commit-panel actions. */
object CommitActionVisibility {

    fun apply(e: AnActionEvent, visible: Boolean, textKey: String, descriptionKey: String) {
        e.presentation.isVisible = visible
        e.presentation.isEnabled = e.project != null
        localize(e, textKey, descriptionKey)
    }

    /** Localized label/tooltip; the plugin.xml attributes remain the English fallback. */
    fun localize(e: AnActionEvent, textKey: String, descriptionKey: String) {
        e.presentation.text = CommitHelperBundle.message(textKey)
        e.presentation.description = CommitHelperBundle.message(descriptionKey)
    }
}

/** Entry point in the commit message toolbar (Vcs.MessageActionGroup). */
class OrganizeCommitMessageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val settings = CommitHelperSettings.getInstance()
        CommitActionVisibility.apply(
            e,
            settings.state.showGenerateAction,
            "action.generate.text",
            "action.generate.description",
        )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OnboardingNotifier.onActionStarted(project)
        val context = CommitContextAdapter.read(project, e.dataContext)
        if (context.isEmpty) {
            Notifier.warn(project, context.readFailure ?: CommitHelperBundle.message("error.noChangesChecked"))
            return
        }
        CommitHelperFlow.organize(project, context)
    }
}

/** Generate with extra context (issue links, notes, Skip CI). Visible by default. */
class GenerateWithContextAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val settings = CommitHelperSettings.getInstance()
        CommitActionVisibility.apply(
            e,
            settings.state.showContextAction,
            "action.context.text",
            "action.context.description",
        )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OnboardingNotifier.onActionStarted(project)
        val context = CommitContextAdapter.read(project, e.dataContext)
        if (context.isEmpty) {
            Notifier.warn(project, context.readFailure ?: CommitHelperBundle.message("error.noChangesChecked"))
            return
        }
        CommitHelperFlow.generateWithContext(project, context)
    }
}

/** Reformat the existing message with the selected template. Hidden by default. */
class FormatCommitMessageAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val settings = CommitHelperSettings.getInstance()
        CommitActionVisibility.apply(
            e,
            settings.state.showFormatAction,
            "action.format.text",
            "action.format.description",
        )
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OnboardingNotifier.onActionStarted(project)
        val context = CommitContextAdapter.read(project, e.dataContext)
        if (context.readFailure != null) {
            Notifier.warn(project, context.readFailure)
            return
        }
        CommitHelperFlow.format(project, context)
    }
}

/** M3 entry point: changelog + PR description preview (copy only). */
class GenerateChangelogAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        CommitActionVisibility.localize(e, "action.changelog.text", "action.changelog.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OnboardingNotifier.onActionStarted(project)
        val context = CommitContextAdapter.read(project, e.dataContext)
        if (context.isEmpty) {
            Notifier.warn(project, context.readFailure ?: CommitHelperBundle.message("error.noChangesChecked"))
            return
        }
        CommitHelperFlow.generateChangelog(project, context)
    }
}

/** Kept for future use by tests that need the outcome shape without a dialog. */
internal fun ChangelogOutcome.isUsable(): Boolean = changelog.isNotBlank()