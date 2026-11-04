package com.caye.commithelper.group

import com.caye.commithelper.model.ChangeGroup
import com.caye.commithelper.model.ChangeItem
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.template.OutputLanguage

/** Coarse bucket of a changed file, derived from its path only. */
enum class FileCategory(val commitType: String) {
    TEST("test"),
    DOCS("docs"),
    BUILD("build"),
    DEPENDENCY("build"),
    CONFIG("chore"),
    SOURCE("feat"),
    OTHER("chore"),
}

object ChangeClassifier {

    private val DOC_SUFFIXES = listOf(".md", ".rst", ".adoc", ".txt")
    private val CONFIG_SUFFIXES = listOf(".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf", ".properties", ".env")
    private val BUILD_SUFFIXES = listOf(
        ".gradle", ".gradle.kts", ".sbt", ".cmake", ".mk", ".mod",
    )
    private val DEPENDENCY_NAMES = setOf(
        "package.json", "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle.kts",
        "requirements.txt", "pyproject.toml", "cargo.toml", "go.mod", "composer.json",
        "gemfile", "podfile", "pubspec.yaml",
    )

    fun categorize(path: String): FileCategory {
        val normalized = path.replace('\\', '/')
        val lower = normalized.lowercase()
        val name = lower.substringAfterLast('/')

        if (isTestPath(normalized)) return FileCategory.TEST
        if (name in DEPENDENCY_NAMES) return FileCategory.DEPENDENCY
        if (isBuildPath(lower, name)) return FileCategory.BUILD
        if (DOC_SUFFIXES.any { lower.endsWith(it) } || lower.startsWith("docs/") || lower.contains("/docs/")) {
            return FileCategory.DOCS
        }
        if (CONFIG_SUFFIXES.any { lower.endsWith(it) } || name.startsWith(".")) return FileCategory.CONFIG
        return FileCategory.SOURCE
    }

    fun isTestPath(path: String): Boolean {
        val lower = path.replace('\\', '/').lowercase()
        val segments = lower.split('/')
        if (segments.any { it == "test" || it == "tests" || it == "__tests__" || it == "spec" || it == "specs" }) return true
        val name = lower.substringAfterLast('/')
        return name.endsWith("test.kt") || name.endsWith("tests.kt") || name.endsWith("_test.go") ||
            name.endsWith("test.java") || name.endsWith("tests.java") || name.endsWith("_test.py") ||
            name.endsWith("test.ts") || name.endsWith("test.tsx") || name.endsWith("test.js") ||
            name.endsWith("spec.ts") || name.endsWith("spec.js")
    }

    private fun isBuildPath(lower: String, name: String): Boolean {
        if (name == "dockerfile" || name == "makefile" || name == "jenkinsfile") return true
        if (lower.startsWith(".github/") || lower.startsWith(".gitlab-ci") || lower.startsWith(".circleci/")) return true
        if (lower.contains("/ci/") || lower.startsWith("ci/")) return true
        return BUILD_SUFFIXES.any { lower.endsWith(it) }
    }
}

/**
 * Deterministic, LLM-free grouping. This is the plugin's fallback backbone and the
 * skeleton handed to the model, so it must never depend on the network.
 */
object LocalGrouper {

    private val SOURCE_ROOT_PREFIX = Regex("^(src|source)/(main|test|it|integrationTest|androidTest)/(kotlin|java|groovy|resources|res)/")

    /** Top-level area of the repository a path belongs to. Never returns blank. */
    fun moduleOf(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return ROOT
        val stripped = SOURCE_ROOT_PREFIX.replace(normalized, "")
        val wasStripped = stripped != normalized
        val segments = stripped.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return ROOT
        if (segments.size == 1) return ROOT
        // Package-style paths keep two segments readable (e.g. com/caye) after a source root;
        // everything else is grouped by its top-level directory.
        return if (wasStripped) segments.take(2).joinToString("/") else segments[0]
    }

    const val ROOT = "(root)"

    fun group(files: List<FileChange>): List<ChangeGroup> =
        files.groupBy { moduleOf(it.path) }
            .toSortedMap()
            .map { (name, groupFiles) -> ChangeGroup(name, groupFiles.sortedBy { it.path }) }

    /**
     * Converts groups into itemized body lines. Used for the local fallback message and
     * as the skeleton shown to the model.
     */
    fun itemize(groups: List<ChangeGroup>, chinese: Boolean): List<ChangeItem> =
        itemize(groups, if (chinese) OutputLanguage.CHINESE else OutputLanguage.ENGLISH)

    fun itemize(groups: List<ChangeGroup>, language: OutputLanguage): List<ChangeItem> {
        val items = ArrayList<ChangeItem>()
        for (group in groups) {
            val byCategory = group.files.groupBy { ChangeClassifier.categorize(it.path) }
            for ((category, categoryFiles) in byCategory.entries.sortedBy { it.key.ordinal }) {
                val added = categoryFiles.sumOf { it.added }
                val removed = categoryFiles.sumOf { it.removed }
                val type = if (category == FileCategory.SOURCE) {
                    if (added >= removed) "feat" else "refactor"
                } else {
                    category.commitType
                }
                items.add(
                    ChangeItem(
                        type = type,
                        scope = group.name,
                        text = describe(categoryFiles, added, removed, language),
                    ),
                )
            }
        }
        return items
    }

    private fun describe(files: List<FileChange>, added: Int, removed: Int, language: OutputLanguage): String {
        val names = files.map { it.path.substringAfterLast('/') }
        val shown = names.take(3)
        val omitted = names.size - shown.size
        val separator = when (language) {
            OutputLanguage.CHINESE, OutputLanguage.JAPANESE -> "、"
            OutputLanguage.KOREAN, OutputLanguage.ENGLISH -> ", "
        }
        val fileList = shown.joinToString(separator)
        return when (language) {
            OutputLanguage.CHINESE ->
                "${files.size} 个文件变更（+$added/-$removed）：$fileList" + if (omitted > 0) " 等 ${names.size} 个文件" else ""
            OutputLanguage.ENGLISH ->
                "${files.size} file(s) changed (+$added/-$removed): $fileList" + if (omitted > 0) " and $omitted more" else ""
            OutputLanguage.JAPANESE ->
                "${files.size} ファイル変更（+$added/-$removed）：$fileList" + if (omitted > 0) " など ${names.size} ファイル" else ""
            OutputLanguage.KOREAN ->
                "파일 ${files.size}개 변경 (+$added/-$removed): $fileList" + if (omitted > 0) " 외 ${omitted}개" else ""
        }
    }
}