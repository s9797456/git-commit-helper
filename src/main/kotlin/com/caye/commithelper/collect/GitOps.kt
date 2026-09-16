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
