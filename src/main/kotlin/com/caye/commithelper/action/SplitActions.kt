package com.caye.commithelper.action

import com.caye.commithelper.adapter.CommitContext
import com.caye.commithelper.adapter.CommitContextAdapter
import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.notify.Notifier
import com.caye.commithelper.notify.OnboardingNotifier
import com.caye.commithelper.service.CommitHelperService
import com.caye.commithelper.split.SplitApplier
import com.caye.commithelper.split.SplitCommitResult
import com.caye.commithelper.split.SplitGroup
import com.caye.commithelper.ui.SplitPlanDialog
import com.caye.commithelper.ui.SplitPushDialog
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import git4idea.GitVcs
import java.io.File

/** Commit-panel entry for the smart-split plan. */
class SuggestCommitSplitAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = true
        e.presentation.isEnabled = e.project != null
        e.presentation.text = CommitHelperBundle.message("action.split.text")
        e.presentation.description = CommitHelperBundle.message("action.split.description")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        OnboardingNotifier.onActionStarted(project)
        val context = CommitContextAdapter.read(project, e.dataContext)
        if (context.isEmpty) {
            Notifier.warn(project, context.readFailure ?: CommitHelperBundle.message("error.noChangesChecked"))
            return
        }
        SplitFlow.suggest(project, context)
    }
}

/** Loads a split plan, commits one group at a time, and optionally pushes afterwards. */
object SplitFlow {

    private val LOG = Logger.getInstance(SplitFlow::class.java)

    fun suggest(project: Project, context: CommitContext) {
        object : Task.Backgroundable(project, CommitHelperBundle.message("task.suggestingSplit"), true) {
            override fun run(indicator: ProgressIndicator) {
                val plan = try {
                    CommitHelperService(project).suggestSplit(context, indicator)
                } catch (e: Exception) {
                    LOG.warn("Commit Helper: split plan failed", e)
                    Notifier.error(project, e.message ?: e.javaClass.simpleName)
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    if (plan.groups.isEmpty()) {
                        plan.errorMessage?.let { Notifier.warn(project, it) }
                        return@invokeLater
                    }
                    plan.errorMessage?.let { Notifier.warn(project, it) }
                    val dialog = SplitPlanDialog(
                        project = project,
                        plan = plan,
                        onCommit = { group, message, done -> commitGroup(project, group, message, done) },
                        onPush = { group -> showPushDialog(project, group) },
                    )
                    dialog.show()
                }
            }
        }.queue()
    }

    private fun commitGroup(
        project: Project,
        group: SplitGroup,
        message: String,
        done: (SplitCommitResult) -> Unit,
    ) {
        object : Task.Backgroundable(project, CommitHelperBundle.message("task.applyingSplit"), false) {
            override fun run(indicator: ProgressIndicator) {
                LOG.info("Commit Helper: split commit start, files=" + group.files.size)
                val result = try {
                    SplitApplier.commit(project, group, message)
                } catch (t: Throwable) {
                    LOG.warn("Commit Helper: split commit threw", t)
                    SplitCommitResult(
                        success = false,
                        errorMessage = t.message ?: t.javaClass.simpleName,
                    )
                }
                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (result.success) {
                            ChangeListManager.getInstance(project).scheduleUpdate()
                            Notifier.info(project, CommitHelperBundle.message("split.committed", result.committedFiles))
                            if (result.otherStagedFiles.isNotEmpty()) {
                                Notifier.warn(
                                    project,
                                    CommitHelperBundle.message("split.otherStaged", result.otherStagedFiles.size),
                                )
                            }
                        } else {
                            Notifier.error(
                                project,
                                CommitHelperBundle.message("split.commitFailedDetail", result.errorMessage ?: ""),
                            )
                        }
                    } catch (t: Throwable) {
                        LOG.warn("Commit Helper: split completion callback threw", t)
                    } finally {
                        LOG.info("Commit Helper: split commit callback success=" + result.success)
                        done(result)
                    }
                }
            }
        }.queue()
    }

    /** Opens IntelliJ's native VCS push page instead of reimplementing remote/branch/tag UI. */
    private fun showPushDialog(project: Project, group: SplitGroup) {
        val action = ActionManager.getInstance().getAction("Vcs.Push")
        if (action == null) {
            Notifier.error(project, CommitHelperBundle.message("split.pushFailed"))
            return
        }
        val files = group.files.mapNotNull { path ->
            val file = File(path)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(if (file.isAbsolute) file else File(project.basePath ?: ".", path))
        }
        val virtualFile: VirtualFile? = files.firstOrNull()
        val dataContext = object : DataContext {
            override fun getData(dataId: String): Any? = when (dataId) {
                CommonDataKeys.PROJECT.name -> project
                CommonDataKeys.VIRTUAL_FILE.name -> virtualFile
                CommonDataKeys.VIRTUAL_FILE_ARRAY.name -> files.toTypedArray()
                VcsDataKeys.VCS.name -> GitVcs.getInstance(project)
                else -> null
            }

            override fun <T : Any?> getData(dataId: DataKey<T>): T? {
                val value: Any? = when (dataId) {
                    CommonDataKeys.PROJECT -> project
                    CommonDataKeys.VIRTUAL_FILE -> virtualFile
                    CommonDataKeys.VIRTUAL_FILE_ARRAY -> files.toTypedArray()
                    VcsDataKeys.VCS -> GitVcs.getInstance(project)
                    else -> null
                }
                @Suppress("UNCHECKED_CAST")
                return value as T?
            }
        }
        val event = AnActionEvent.createFromAnAction(action, null, "Commit Helper", dataContext)
        action.actionPerformed(event)
    }
}
