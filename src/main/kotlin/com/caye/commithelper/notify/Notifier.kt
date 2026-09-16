package com.caye.commithelper.notify

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * All user-facing feedback goes through here. Failures are surfaced as a notification with
 * a Retry action; the fallback message is written to the commit box without an error banner.
 */
object Notifier {

    private const val GROUP_ID = "Commit Helper"
    private const val TITLE = "Commit Helper"

    fun info(project: Project, content: String) {
        notify(project, content, NotificationType.INFORMATION, null)
    }

    fun warn(project: Project, content: String, retry: (() -> Unit)? = null) {
        notify(project, content, NotificationType.WARNING, retry)
    }

    fun error(project: Project, content: String, retry: (() -> Unit)? = null) {
        notify(project, content, NotificationType.ERROR, retry)
    }

    private fun notify(project: Project, content: String, type: NotificationType, retry: (() -> Unit)?) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
        val notification = group.createNotification(TITLE, content, type)
        if (retry != null) {
            notification.addAction(object : AnAction("Retry") {
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
                override fun actionPerformed(e: AnActionEvent) = retry()
            })
        }
        notification.notify(project)
    }
}
