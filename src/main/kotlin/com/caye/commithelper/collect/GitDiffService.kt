package com.caye.commithelper.collect

import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.FileChange
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.io.File

/** Locates the git repository root for a path, without touching IDE APIs (unit-testable). */
object GitRootLocator {

    fun findRoot(start: File?): File? {
        var current = start?.absoluteFile
        var guard = 0
        while (current != null && guard++ < 64) {
            val dotGit = File(current, ".git")
            if (dotGit.exists()) return current
            current = current.parentFile
        }
        return null
    }
}

/**
 * Paths handed to `git diff` must be relative to the repository root, not the project root.
 * In a multi-repo project the project-relative path carries the repository directory name
 * (`repo-name/src/...`), which git never matches, so the diff silently comes back empty.
 */
object RepoPath {

    /** Returns [file] relative to [root] with `/` separators, or null when it is outside [root]. */
    fun relativePath(root: File, file: File): String? = try {
        val rootPath = root.absoluteFile.toPath().normalize()
        val filePath = file.absoluteFile.toPath().normalize()
        if (filePath.startsWith(rootPath)) {
            rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }
}

/** A parsed `diff --git` block. */
data class DiffBlock(val path: String, val text: String)

/** Parses `git diff` output into per-file blocks. Pure logic. */
object DiffBlockParser {

    fun parse(output: String): List<DiffBlock> {
        val blocks = ArrayList<DiffBlock>()
        var currentPath: String? = null
        val sb = StringBuilder()

        fun flush() {
            val path = currentPath
            if (path != null && sb.isNotEmpty()) {
                blocks.add(DiffBlock(path, sb.toString()))
            }
            sb.setLength(0)
        }

        for (line in output.split('\n')) {
            when {
                line.startsWith("diff --git ") -> {
                    flush()
                    currentPath = null
                }
                line.startsWith("+++ ") -> {
                    val candidate = line.removePrefix("+++ ").trim().removePrefix("b/")
                    if (candidate != "/dev/null") currentPath = candidate
                }
                line.startsWith("--- ") && currentPath == null -> {
                    val candidate = line.removePrefix("--- ").trim().removePrefix("a/")
                    if (candidate != "/dev/null") currentPath = candidate
                }
            }
            sb.append(line).append('\n')
        }
        flush()
        return blocks
    }

    fun countLines(diffText: String): Pair<Int, Int> {
        var added = 0
        var removed = 0
        for (line in diffText.split('\n')) {
            when {
                line.startsWith("+++") || line.startsWith("---") -> Unit
                line.startsWith("+") -> added++
                line.startsWith("-") -> removed++
            }
        }
        return added to removed
    }
}

/**
 * Collects diffs for the files the user actually checked in the commit panel.
 *
 * Tracked files: one `git diff HEAD` process per repository (respects the IDE's configured
 * git executable). Unversioned files: synthesized new-file diff from the editor's content.
 */
class GitDiffService(private val project: Project) {

    fun collect(
        changes: List<Change>,
        unversioned: List<File>,
        includeDiff: Boolean,
    ): List<FileChange> {
        val result = ArrayList<FileChange>()
        val byPath = LinkedHashMap<String, Change>()

        for (change in changes) {
            val path = pathOf(change) ?: continue
            byPath[path] = change
        }

        data class TrackedEntry(val changePath: String, val repoPath: String, val change: Change)

        val trackedByRoot = LinkedHashMap<File, MutableList<TrackedEntry>>()
        for ((path, change) in byPath) {
            val absolute = absoluteFile(change) ?: continue
            val root = GitRootLocator.findRoot(absolute.parentFile) ?: project.basePath?.let { File(it) } ?: continue
            // The repository root is not always the project root: git needs the path relative to
            // the repository, while FileChange keeps the project-relative path for display.
            val repoPath = RepoPath.relativePath(root, absolute) ?: path
            trackedByRoot.getOrPut(root) { mutableListOf() }.add(TrackedEntry(path, repoPath, change))
        }

        for ((root, entries) in trackedByRoot) {
            val diffOutput = if (includeDiff) runDiff(root, entries.map { it.repoPath }) else ""
            val blocks = if (includeDiff) DiffBlockParser.parse(diffOutput) else emptyList()
            for (entry in entries) {
                val block = findBlock(blocks, entry.repoPath)
                    ?: findBlock(blocks, entry.changePath)
                val (added, removed) = block?.let { DiffBlockParser.countLines(it.text) } ?: (0 to 0)
                result.add(
                    FileChange(
                        path = entry.changePath,
                        kind = kindOf(entry.change),
                        added = added,
                        removed = removed,
                        diffText = block?.text.orEmpty(),
                    ),
                )
            }
        }

        for (file in unversioned) {
            val relative = file.toRelativePath()
            val content = if (includeDiff) readFileContent(file) else ""
            val diffText = if (content.isNullOrEmpty()) "" else synthesizeNewFileDiff(content)
            val (added, removed) = DiffBlockParser.countLines(diffText)
            result.add(
                FileChange(
                    path = relative,
                    kind = ChangeKind.ADDED,
                    added = added,
                    removed = removed,
                    diffText = diffText,
                ),
            )
        }

        return result
    }

    /**
     * The repository root is not always the project root, so a git path may carry a prefix the
     * change-list path does not. Fall back to a suffix match before giving up on the block.
     */
    private fun findBlock(blocks: List<DiffBlock>, path: String): DiffBlock? =
        blocks.firstOrNull { it.path == path }
            ?: blocks.firstOrNull { it.path.endsWith(path) || path.endsWith(it.path) }

    private fun runDiff(root: File, paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val handler = GitLineHandler(project, root, GitCommand.DIFF)
        handler.addParameters("HEAD", "--no-color", "--unified=3", "--")
        handler.addParameters(paths)
        val commandResult = try {
            Git.getInstance().runCommand(handler)
        } catch (_: Exception) {
            return ""
        }
        // Exit code 1 with output means "differences found" for some git versions; only
        // discard truly empty output.
        val output = commandResult.output.joinToString("\n")
        if (!commandResult.success() && output.isBlank()) return ""
        return output
    }

    /** Untracked files are read straight from disk; huge files are skipped. */
    private fun readFileContent(file: File): String? = try {
        if (file.isFile && file.length() <= MAX_UNVERSIONED_BYTES) file.readText() else null
    } catch (_: Exception) {
        null
    }

    /** Caps synthesis at 400 lines: [com.caye.commithelper.filter.DiffFilter] truncates later. */
    fun synthesizeNewFileDiff(content: String): String {
        val lines = content.split('\n')
        val shown = lines.take(MAX_SYNTHETIC_LINES)
        return buildString {
            append("--- /dev/null\n+++ b/new\n")
            for (line in shown) append('+').append(line).append('\n')
            if (lines.size > shown.size) {
                append("... [truncated ").append(lines.size - shown.size).append(" more lines]\n")
            }
        }
    }

    private fun kindOf(change: Change): ChangeKind = when (change.type) {
        Change.Type.NEW -> ChangeKind.ADDED
        Change.Type.DELETED -> ChangeKind.DELETED
        Change.Type.MOVED -> ChangeKind.RENAMED
        Change.Type.MODIFICATION -> ChangeKind.MODIFIED
        else -> ChangeKind.UNKNOWN
    }

    private fun pathOf(change: Change): String? {
        val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return null
        return relativize(filePath.path)
    }

    private fun absoluteFile(change: Change): File? {
        val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: return null
        return filePath.ioFile
    }

    private fun File.toRelativePath(): String = relativize(absolutePath)

    private fun relativize(absolute: String): String {
        val base = project.basePath
        val normalized = absolute.replace('\\', '/')
        if (base != null) {
            val normalizedBase = base.replace('\\', '/').trimEnd('/')
            if (normalized.startsWith("$normalizedBase/")) {
                return normalized.removePrefix("$normalizedBase/")
            }
        }
        return normalized.trimStart('/')
    }

    private companion object {
        const val MAX_SYNTHETIC_LINES = 400
        const val MAX_UNVERSIONED_BYTES = 1_000_000L
    }
}
