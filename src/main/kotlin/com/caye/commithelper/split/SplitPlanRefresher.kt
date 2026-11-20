package com.caye.commithelper.split

import com.caye.commithelper.adapter.CommitContext
import com.caye.commithelper.collect.GitDiffService

/**
 * Rebuilds remaining hunk objects after a commit. HEAD moved, so the next group must use hunks
 * parsed from the current working tree rather than the pre-commit patch.
 */
object SplitPlanRefresher {

    fun refresh(context: CommitContext, remaining: List<SplitGroup>): List<SplitGroup> {
        if (remaining.isEmpty()) return emptyList()
        val files = GitDiffService(context.project).collect(
            changes = context.includedChanges,
            unversioned = context.unversionedFiles(),
            includeDiff = true,
        )
        val hunks = DiffHunkParser.parse(files)
        val byId = hunks.associateBy { it.id }
        return remaining.mapNotNull { group ->
            if (group.hunks.isEmpty()) return@mapNotNull group
            val mapped = group.hunks.mapNotNull { byId[it.id] }.distinctBy { it.id }
            if (mapped.isEmpty()) null else group.withHunks(mapped)
        }
    }
}
