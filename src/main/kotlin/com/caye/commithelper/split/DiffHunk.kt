package com.caye.commithelper.split

import com.caye.commithelper.model.FileChange
import java.security.MessageDigest

/** One selectable hunk from a file diff. */
data class DiffHunk(
    val id: String,
    val filePath: String,
    val fileHeader: String,
    val hunkText: String,
    val summary: String,
    val wholeFile: Boolean = false,
) {
    fun patchText(): String = if (wholeFile) hunkText.trimEnd() + "\n" else fileHeader.trimEnd() + "\n" + hunkText.trimEnd() + "\n"
}

/** Splits per-file diff text into stable hunk objects and rebuilds patches for a group. */
object DiffHunkParser {

    fun parse(files: List<FileChange>): List<DiffHunk> {
        val result = ArrayList<DiffHunk>()
        val seen = HashMap<String, Int>()
        for (file in files) {
            val diff = file.diffText.trimEnd()
            if (diff.isBlank()) continue
            val lines = diff.lines()
            val hunkStarts = lines.indices.filter { lines[it].startsWith("@@") }
            if (hunkStarts.isEmpty()) {
                val body = diff
                result.add(
                    DiffHunk(
                        id = nextId(file.path, body, seen),
                        filePath = file.path,
                        fileHeader = "",
                        hunkText = body,
                        summary = "whole file",
                        wholeFile = true,
                    ),
                )
                continue
            }
            val header = lines.subList(0, hunkStarts.first()).joinToString("\n")
            for (index in hunkStarts.indices) {
                val start = hunkStarts[index]
                val end = if (index + 1 < hunkStarts.size) hunkStarts[index + 1] else lines.size
                val body = lines.subList(start, end).joinToString("\n")
                result.add(
                    DiffHunk(
                        id = nextId(file.path, body, seen),
                        filePath = file.path,
                        fileHeader = header,
                        hunkText = body,
                        summary = summaryOf(lines[start], body),
                    ),
                )
            }
        }
        return result
    }

    fun patch(hunks: List<DiffHunk>): String {
        if (hunks.isEmpty()) return ""
        val byFile = hunks.distinctBy { it.id }.groupBy { it.filePath }
        return byFile.values.joinToString("\n") { fileHunks ->
            val first = fileHunks.first()
            if (first.wholeFile) {
                first.hunkText.trimEnd() + "\n"
            } else {
                first.fileHeader.trimEnd() + "\n" + fileHunks.joinToString("\n") { it.hunkText.trimEnd() } + "\n"
            }
        }
    }

    private fun nextId(path: String, body: String, seen: MutableMap<String, Int>): String {
        val stableBody = body.lines().drop(1).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-1").digest(stableBody.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(12)
        val base = path + "#" + hash
        val occurrence = seen.getOrDefault(base, 0)
        seen[base] = occurrence + 1
        return if (occurrence == 0) base else base + "-" + occurrence
    }

    private fun summaryOf(header: String, body: String): String {
        val changed = body.lines().firstOrNull {
            (it.startsWith("+") && !it.startsWith("+++")) || (it.startsWith("-") && !it.startsWith("---"))
        }?.trim().orEmpty()
        return header.trim() + if (changed.isEmpty()) "" else "  " + changed.take(80)
    }
}
