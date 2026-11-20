package com.caye.commithelper.split

import com.caye.commithelper.collect.GitOps
import com.caye.commithelper.collect.GitRootLocator
import com.caye.commithelper.collect.RepoPath
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.config.GitExecutableManager
import java.io.File
import java.nio.file.Files

data class SplitStageResult(
    val success: Boolean,
    val stagedFiles: Int = 0,
    val otherStagedFiles: List<String> = emptyList(),
    val errorMessage: String? = null,
)

data class SplitCommitResult(
    val success: Boolean,
    val committedFiles: Int = 0,
    val otherStagedFiles: List<String> = emptyList(),
    val errorMessage: String? = null,
)

/**
 * Stages, commits, or pushes a split group. It never resets the index: other staged files are
 * preserved. A commit is path-limited to the group, so those other staged files stay out of the
 * new commit.
 */
object SplitApplier {

    fun stage(project: Project, group: SplitGroup): SplitStageResult {
        val resolved = resolve(project, group) ?: return SplitStageResult(
            false,
            errorMessage = "A split group must stay inside one repository.",
        )
        val existing = resolved.gitOps.stagedPaths()
        val others = existing.filter { it !in resolved.relativePaths }
        val staged = resolved.gitOps.stageWithResult(resolved.relativePaths)
        return SplitStageResult(
            success = staged.success,
            stagedFiles = if (staged.success) resolved.relativePaths.size else 0,
            otherStagedFiles = others,
            errorMessage = if (staged.success) null else "git add failed: " + staged.error.orEmpty(),
        )
    }

    fun commit(project: Project, group: SplitGroup, message: String): SplitCommitResult {
        val resolved = resolve(project, group) ?: return SplitCommitResult(
            false,
            errorMessage = "A split group must stay inside one repository.",
        )
        if (group.hunks.isNotEmpty()) {
            return commitHunks(project, resolved, group, message)
        }
        val existing = resolved.gitOps.stagedPaths()
        val others = existing.filter { it !in resolved.relativePaths }
        val staged = resolved.gitOps.stageWithResult(resolved.relativePaths)
        if (!staged.success) {
            return SplitCommitResult(false, errorMessage = "git add failed: " + staged.error.orEmpty(), otherStagedFiles = others)
        }
        val success = resolved.gitOps.commit(message, resolved.relativePaths)
        return SplitCommitResult(
            success = success,
            committedFiles = if (success) resolved.relativePaths.size else 0,
            otherStagedFiles = others,
            errorMessage = if (success) null else "git commit failed.",
        )
    }

    /**
     * Commits selected hunks through a temporary index. The user's working tree and real index
     * are not used as staging surfaces; the temporary index is discarded after the commit.
     */
    private fun commitHunks(
        project: Project,
        resolved: Resolved,
        group: SplitGroup,
        message: String,
    ): SplitCommitResult {
        val patch = DiffHunkParser.patch(group.hunks)
        if (patch.isBlank()) return SplitCommitResult(false, errorMessage = "No hunk patch was generated.")
        return commitPatch(project, resolved, patch, group.hunks.map { it.filePath }.distinct().size, message)
    }

    private fun commitPatch(
        project: Project,
        resolved: Resolved,
        patch: String,
        fileCount: Int,
        message: String,
    ): SplitCommitResult {
        val temp = Files.createTempFile("commit-helper-index-", ".idx").toFile()
        temp.delete()
        return try {
            val init = GitLineHandler(project, resolved.root, GitCommand.RESET)
            init.addCustomEnvironmentVariable("GIT_INDEX_FILE", temp.absolutePath)
            init.addParameters("--mixed", "HEAD")
            if (!runSuccess(init)) return SplitCommitResult(false, errorMessage = "Could not initialize the temporary index.")
            if (!applyCached(project, resolved.root, patch, temp)) {
                return SplitCommitResult(false, errorMessage = "git apply --cached failed for the selected files.")
            }
            val commit = GitLineHandler(project, resolved.root, GitCommand.COMMIT)
            commit.addCustomEnvironmentVariable("GIT_INDEX_FILE", temp.absolutePath)
            commit.setTerminationTimeout(120_000)
            commit.addParameters("-m", message)
            if (!runSuccess(commit)) return SplitCommitResult(false, errorMessage = "git commit failed or timed out.")
            // HEAD moved; sync the real index without touching the working tree. The user does not
            // stage manually, so transient index contents are not preserved across the hunk commit.
            val sync = GitLineHandler(project, resolved.root, GitCommand.RESET)
            sync.addParameters("--mixed", "HEAD")
            runSuccess(sync)
            SplitCommitResult(
                success = true,
                committedFiles = fileCount,
            )
        } finally {
            temp.delete()
        }
    }

    private fun applyCached(project: Project, root: File, patch: String, indexFile: File): Boolean {
        val git = GitExecutableManager.getInstance().getPathToGit(project).ifBlank { "git" }
        return try {
            val process = ProcessBuilder(
                git, "-C", root.absolutePath, "apply", "--cached", "--recount",
            ).apply {
                environment()["GIT_INDEX_FILE"] = indexFile.absolutePath
                redirectErrorStream(true)
            }.start()
            process.outputStream.use { it.write(patch.toByteArray(Charsets.UTF_8)) }
            process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runSuccess(handler: GitLineHandler): Boolean = try {
        Git.getInstance().runCommand(handler).success()
    } catch (_: Exception) {
        false
    }

    fun push(project: Project, group: SplitGroup, remote: String, branch: String, tag: String?): Boolean {
        val resolved = resolve(project, group) ?: return false
        return resolved.gitOps.push(remote, branch, tag)
    }

    fun remotes(project: Project, group: SplitGroup): List<String> =
        resolve(project, group)?.gitOps?.remotes().orEmpty()

    fun currentBranch(project: Project, group: SplitGroup): String =
        resolve(project, group)?.gitOps?.branchName().orEmpty()

    private data class Resolved(
        val root: File,
        val gitOps: GitOps,
        val relativePaths: List<String>,
    )

    private fun resolve(project: Project, group: SplitGroup): Resolved? {
        val base = project.basePath ?: return null
        if (group.files.isEmpty()) return null
        val absoluteFiles = group.files.map { path ->
            val file = File(path)
            if (file.isAbsolute) file else File(base, path)
        }
        val roots = absoluteFiles.map { file ->
            GitRootLocator.findRoot(if (file.isDirectory) file else file.parentFile) ?: return null
        }.distinct()
        if (roots.size != 1) return null
        val root = roots.single()
        val relative = absoluteFiles.mapNotNull { RepoPath.relativePath(root, it) }
        if (relative.size != absoluteFiles.size) return null
        val gitOps = GitOps.forPath(project, root) ?: return null
        return Resolved(root, gitOps, relative)
    }
}
