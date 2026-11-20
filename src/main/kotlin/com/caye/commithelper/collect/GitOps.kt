package com.caye.commithelper.collect

import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.io.File

/**
 * Thin wrapper over `git` for repository metadata. Uses the IDE's configured git executable
 * through [GitLineHandler], so it behaves exactly like the built-in git integration.
 */
class GitOps(private val project: Project, private val root: File) {

    fun branchName(): String? = run(GitCommand.REV_PARSE, "--abbrev-ref", "HEAD").firstOrNull()

    fun recentSubjects(count: Int = 20): List<String> =
        run(GitCommand.LOG, "-$count", "--pretty=%s").filter { it.isNotBlank() }

    /** `origin/main` when known, otherwise the first of `main`/`master` that resolves. */
    fun defaultBranch(): String? {
        run(GitCommand.REV_PARSE, "--abbrev-ref", "origin/HEAD").firstOrNull()
            ?.takeIf { it.isNotBlank() && it != "origin/HEAD" }
            ?.let { return it }
        for (candidate in listOf("main", "master")) {
            if (run(GitCommand.REV_PARSE, "--verify", candidate).isNotEmpty()) return candidate
        }
        return null
    }

    /** Value of `git config --get commit.template`, if any. */
    fun commitTemplatePath(): String? =
        run(GitCommand.CONFIG, "--get", "commit.template").firstOrNull()?.takeIf { it.isNotBlank() }

    fun logOneline(range: String): List<String> =
        run(GitCommand.LOG, "--oneline", range).filter { it.isNotBlank() }

    fun diffStat(range: String): String = run(GitCommand.DIFF, "--stat", range).joinToString("\n")

    /** File content at HEAD, or null when the file does not exist in HEAD. */
    fun showAtHead(repoPath: String): String? {
        val handler = GitLineHandler(project, root, GitCommand.SHOW)
        handler.addParameters("HEAD:" + repoPath)
        return try {
            val result = Git.getInstance().runCommand(handler)
            if (!result.success()) null else result.output.filterNotNull().joinToString("\n")
        } catch (_: Exception) {
            null
        }
    }

    /** Already-staged paths, relative to the repository root. */
    fun stagedPaths(): List<String> = run(GitCommand.DIFF, "--cached", "--name-only").filter { it.isNotBlank() }

    data class StageResult(val success: Boolean, val error: String? = null)

    /** Stages exactly the given repository-relative paths, including deletions and new files. */
    fun stage(paths: List<String>): Boolean = stageWithResult(paths).success

    fun stageWithResult(paths: List<String>): StageResult {
        if (paths.isEmpty()) return StageResult(false, "no paths")
        val handler = GitLineHandler(project, root, GitCommand.ADD)
        handler.addParameters("-A", "--")
        handler.addParameters(paths)
        return try {
            val result = Git.getInstance().runCommand(handler)
            StageResult(result.success(), if (result.success()) null else result.errorOutputAsJoinedString.ifBlank {
                result.outputAsJoinedString
            })
        } catch (e: Exception) {
            StageResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Commit only the given paths, leaving other staged files untouched. */
    fun commit(message: String, paths: List<String>): Boolean {
        if (paths.isEmpty() || message.isBlank()) return false
        val handler = GitLineHandler(project, root, GitCommand.COMMIT)
        handler.addParameters("-m", message)
        handler.addParameters("--")
        handler.addParameters(paths)
        return runSuccess(handler)
    }

    fun remotes(): List<String> = run(GitCommand.REMOTE).filter { it.isNotBlank() }

    /** Push HEAD to the chosen remote branch and optionally create/push a tag at HEAD. */
    fun push(remote: String, branch: String, tag: String?): Boolean {
        val remoteName = remote.trim()
        val branchName = branch.trim()
        if (remoteName.isEmpty() || branchName.isEmpty()) return false
        val pushHandler = GitLineHandler(project, root, GitCommand.PUSH)
        pushHandler.addParameters(remoteName, "HEAD:refs/heads/" + branchName)
        if (!runSuccess(pushHandler)) return false
        val tagName = tag?.trim().orEmpty()
        if (tagName.isEmpty()) return true
        val tagHandler = GitLineHandler(project, root, GitCommand.TAG)
        tagHandler.addParameters(tagName)
        if (!runSuccess(tagHandler)) return false
        val tagPush = GitLineHandler(project, root, GitCommand.PUSH)
        tagPush.addParameters(remoteName, "refs/tags/" + tagName)
        return runSuccess(tagPush)
    }

    private fun runSuccess(handler: GitLineHandler): Boolean = try {
        Git.getInstance().runCommand(handler).success()
    } catch (_: Exception) {
        false
    }

    private fun run(command: GitCommand, vararg parameters: String): List<String> {
        val handler = GitLineHandler(project, root, command)
        handler.addParameters(*parameters)
        return try {
            val result = Git.getInstance().runCommand(handler)
            result.output.filterNotNull()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun forPath(project: Project, file: File?): GitOps? {
            val start = file?.let { if (it.isDirectory) it else it.parentFile } ?: return null
            val root = GitRootLocator.findRoot(start) ?: project.basePath?.let { File(it) } ?: return null
            return GitOps(project, root)
        }
    }
}
