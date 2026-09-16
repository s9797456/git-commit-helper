package com.caye.commithelper.filter

import com.caye.commithelper.model.FileChange
import com.caye.commithelper.model.FilterResult

/**
 * Noise-file rules. Pure logic, no IDE dependencies, fully unit-testable.
 */
object ExclusionRules {
    private val BINARY_EXTENSIONS = setOf(
        "png", "jpg", "jpeg", "gif", "bmp", "ico", "icns", "webp", "tiff", "svgz",
        "pdf", "zip", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "ear",
        "class", "so", "dylib", "dll", "exe", "bin", "o", "a", "lib",
        "woff", "woff2", "ttf", "otf", "eot",
        "mp3", "mp4", "mov", "avi", "mkv", "wav", "flac", "webm",
        "sqlite", "db", "realm", "keystore", "jks", "p12", "pfx",
    )

    private val EXCLUDED_FILE_NAMES = setOf(
        "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "npm-shrinkwrap.json",
        "Gemfile.lock", "poetry.lock", "Pipfile.lock", "Cargo.lock", "composer.lock",
        "go.sum", "gradle.lockfile", "packages.lock.json", "Podfile.lock",
        ".ds_store", "thumbs.db",
    )

    private val EXCLUDED_DIR_SEGMENTS = setOf(
        "node_modules", "dist", "build", "out", "target", "vendor",
        ".gradle", ".idea", "__pycache__", ".venv", "venv", ".next", ".nuxt",
        "coverage", ".pytest_cache", ".mypy_cache", "DerivedData", "Pods",
    )

    private val EXCLUDED_SUFFIXES = listOf(
        ".min.js", ".min.css", ".map", ".generated.kt", ".generated.java",
        ".generated.ts", ".g.dart", ".freezed.dart", ".designer.cs",
    )

    private val SNAPSHOT_DIR_MARKERS = listOf("__snapshots__", "/snapshots/")

    fun isBinaryPath(path: String): Boolean {
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext in BINARY_EXTENSIONS
    }

    /** @param extraGlobs user-configured glob patterns, `*` = any chars, `**` = any path chars.
     *  A pattern without `/` also matches the file name at any depth (gitignore-like comfort). */
    fun isExcluded(path: String, extraGlobs: List<String> = emptyList()): Boolean {
        val normalized = path.replace('\\', '/')
        val lower = normalized.lowercase()
        val baseName = normalized.substringAfterLast('/')
        val name = baseName.lowercase()

        if (isBinaryPath(normalized)) return true
        if (name in EXCLUDED_FILE_NAMES) return true
        if (EXCLUDED_SUFFIXES.any { lower.endsWith(it) }) return true
        if (EXCLUDED_DIR_SEGMENTS.any { segment -> normalized.split('/').any { it == segment } }) return true
        if (SNAPSHOT_DIR_MARKERS.any { normalized.contains(it) }) return true
        if (lower.endsWith(".snap")) return true
        return extraGlobs.any { glob ->
            val pattern = glob.trim()
            if (pattern.isEmpty()) {
                false
            } else if (pattern.contains('/')) {
                globToRegex(pattern).matches(normalized)
            } else {
                globToRegex(pattern).matches(baseName)
            }
        }
    }

    /** Minimal, predictable glob support: `**` crosses `/`, `*` does not. */
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                c == '*' && i + 1 < glob.length && glob[i + 1] == '*' -> {
                    sb.append(".*"); i += 2
                    if (i < glob.length && glob[i] == '/') i++
                }
                c == '*' -> { sb.append("[^/]*"); i++ }
                c == '?' -> { sb.append("[^/]"); i++ }
                c in ".^$+{}[]()|\\" -> { sb.append('\\').append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        sb.append("$")
        return Regex(sb.toString())
    }
}

/**
 * Turns the raw change set into the diff text actually sent to the model.
 *
 * Order of operations: drop noise files -> truncate each file -> spend the global
 * character budget on the biggest changes -> emit an explicit sampling note.
 */
object DiffFilter {

    const val TRUNCATION_MARKER = "... [truncated %d more lines]"

    fun filter(
        files: List<FileChange>,
        maxLinesPerFile: Int,
        maxTotalChars: Int,
        extraExcludes: List<String> = emptyList(),
    ): FilterResult {
        val kept = ArrayList<FileChange>(files.size)
        val omitted = ArrayList<String>()

        for (f in files) {
            if (f.binary || ExclusionRules.isExcluded(f.path, extraExcludes)) {
                omitted.add(f.path)
            } else {
                kept.add(f)
            }
        }

        val prepared = kept.map { truncate(it, maxLinesPerFile) }
        val bySize = prepared.sortedByDescending { it.changedLines }

        val included = ArrayList<FileChange>(bySize.size)
        val sb = StringBuilder()
        var used = 0
        for (f in bySize) {
            val block = renderBlock(f)
            if (f.diffText.isEmpty()) {
                // No diff content collected: file list only, keep it (cheap).
                included.add(f)
                continue
            }
            if (used + block.length > maxTotalChars) {
                omitted.add(f.path)
                continue
            }
            used += block.length
            sb.append(block)
            included.add(f)
        }

        val sampled = omitted.isNotEmpty()
        val fileList = renderFileList(kept)
        val diffText = buildString {
            if (sampled) {
                append("NOTE: diff is sampled. Included ")
                append(included.size).append(" of ").append(files.size)
                append(" changed files; ").append(omitted.size)
                append(" file(s) omitted (noise rules or size limits): ")
                append(omitted.sorted().take(50).joinToString(", "))
                if (omitted.size > 50) append(", ...")
                append("\n\n")
            }
            append(sb)
        }

        return FilterResult(
            included = included,
            omittedFiles = omitted,
            diffText = diffText,
            fileListText = fileList,
            sampled = sampled,
            totalFilesSeen = files.size,
        )
    }

    fun truncate(file: FileChange, maxLinesPerFile: Int): FileChange {
        if (maxLinesPerFile <= 0 || file.diffText.isEmpty()) return file
        val lines = file.diffText.split('\n')
        if (lines.size <= maxLinesPerFile) return file
        val keptLines = lines.take(maxLinesPerFile)
        val dropped = lines.size - maxLinesPerFile
        val text = keptLines.joinToString("\n") + "\n" + TRUNCATION_MARKER.format(dropped) + "\n"
        return file.copy(diffText = text)
    }

    fun renderBlock(file: FileChange): String = buildString {
        append("--- ").append(file.path).append('\n')
        append("+++ ").append(file.path).append(" (").append(file.kind.name.lowercase())
        append(", +").append(file.added).append("/-").append(file.removed).append(")\n")
        append(file.diffText)
        if (!file.diffText.endsWith("\n")) append('\n')
        append('\n')
    }

    fun renderFileList(files: List<FileChange>): String =
        files.sortedBy { it.path }.joinToString("\n") { f ->
            "${f.kind.name.lowercase()} ${f.path} (+${f.added}/-${f.removed})"
        }
}
