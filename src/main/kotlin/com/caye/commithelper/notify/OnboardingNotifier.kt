package com.caye.commithelper.notify

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.Diagnostic
import com.caye.commithelper.settings.SettingsDiagnostics
import com.caye.commithelper.settings.CommitHelperConfigurable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * One-time onboarding and non-blocking configuration warnings. The guide tab always shows the
 * full self-check result; these notifications only make users aware that the tab exists.
 */
object OnboardingNotifier {

    private const val GROUP_ID = "Commit Helper"

    private val warnedSignatures = mutableSetOf<String>()
    private var warningsMutedForSession = false

    /** Called from every commit-panel action before the generation starts. */
    fun onActionStarted(project: Project?) {
        val state = CommitHelperSettings.getInstance().state
        // Warnings such as ".gitmessage overrides the template" are visible in the guide but do
        // not trigger a popup; only errors that are known to break the output are notified.
        val errors = SettingsDiagnostics.inspect(project)
            .filter { it.code.severity == com.caye.commithelper.settings.DiagnosticSeverity.ERROR }
        if (!state.onboardingShown) {
            if (errors.isNotEmpty()) {
                showConfigWarning(project, errors)
            } else {
                showWelcome(project)
            }
            state.onboardingShown = true
        } else if (errors.isNotEmpty()) {
            showConfigWarning(project, errors)
        }
    }

    private fun showWelcome(project: Project?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                CommitHelperBundle.message("notification.onboarding.title"),
                CommitHelperBundle.message("notification.onboarding.content"),
                NotificationType.INFORMATION,
            )
        notification.addAction(GuideAction(project))
        notification.notify(project)
    }

    private fun showConfigWarning(project: Project?, issues: List<Diagnostic>) {
        if (warningsMutedForSession) return
        val signature = SettingsDiagnostics.signature(issues)
        if (!warnedSignatures.add(signature)) return
        val first = issues.first()
        val text = first.projectName
            ?.let { CommitHelperBundle.message("diagnostics.withProject", issueText(first), it) }
            ?: issueText(first)
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(
                CommitHelperBundle.message("notification.config.title"),
                CommitHelperBundle.message("notification.config.content", text, issues.size),
                NotificationType.ERROR,
            )
        notification.addAction(GuideAction(project))
        notification.addAction(MuteAction())
        notification.notify(project)
    }

    private fun issueText(issue: Diagnostic): String =
        CommitHelperBundle.message("diagnostics." + issue.code.name)

    fun openGuide(project: Project?) {
        CommitHelperConfigurable.requestGuideTab()
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, CommitHelperConfigurable::class.java)
        }
    }

    private class GuideAction(private val project: Project?) :
        AnAction(CommitHelperBundle.message("notification.action.guide")) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) = openGuide(project)
    }

    private class MuteAction : AnAction(CommitHelperBundle.message("notification.action.mute")) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            warningsMutedForSession = true
        }
    }
}
