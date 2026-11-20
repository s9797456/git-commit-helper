package com.caye.commithelper.split

import com.caye.commithelper.model.ChangeKind

/** One proposed atomic commit inside a smart-split plan. */
data class SplitGroup(
    val name: String,
    val subject: String,
    val body: List<String>,
    val files: List<String>,
    /** Empty means a file-level group (no hunk override). */
    val hunks: List<DiffHunk> = emptyList(),
    /** Unified diff per file, used by the double-click preview. */
    val diffs: Map<String, String> = emptyMap(),
    /** File status used by the colored file list. */
    val fileKinds: Map<String, ChangeKind> = emptyMap(),
) {
    /** The exact text that will be written into the commit message box. */
    fun message(): String {
        val text = if (body.isEmpty()) subject else subject + "\n\n" + body.joinToString("\n")
        return text.trim()
    }

    fun effectiveFiles(): List<String> =
        if (hunks.isEmpty()) files else hunks.map { it.filePath }.distinct()

    fun withHunks(next: List<DiffHunk>): SplitGroup =
        copy(hunks = next, files = next.map { it.filePath }.distinct().ifEmpty { files })
}

/** A full split plan. groups == 1 means the change set is already one atomic commit. */
data class SplitPlan(
    val groups: List<SplitGroup>,
    val usedFallback: Boolean = false,
    val errorMessage: String? = null,
)
