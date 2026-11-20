package com.caye.commithelper.ui

import com.caye.commithelper.collect.GitOps
import com.caye.commithelper.collect.GitRootLocator
import com.caye.commithelper.collect.RepoPath
import com.caye.commithelper.split.SplitGroup
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import java.io.File

/** Opens the IDE's native before/after diff for one file in a split group. */
object SplitFileDiffer {

    fun show(project: Project, group: SplitGroup, filePath: String) {
        val base = project.basePath ?: return
        val absolute = File(filePath).let { if (it.isAbsolute) it else File(base, filePath) }
        val root = GitRootLocator.findRoot(if (absolute.isDirectory) absolute else absolute.parentFile)
        val repoPath = root?.let { RepoPath.relativePath(it, absolute) } ?: filePath
        val gitOps = root?.let { GitOps.forPath(project, it) }
        val before = gitOps?.showAtHead(repoPath).orEmpty()
        val after = try {
            if (absolute.isFile) absolute.readText() else ""
        } catch (_: Exception) {
            ""
        }
        val factory = DiffContentFactory.getInstance()
        val left = factory.create(project, before)
        val right = factory.create(project, after)
        val request = SimpleDiffRequest(
            group.name + ": " + filePath,
            left,
            right,
            "HEAD:" + repoPath,
            "Working tree",
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}
