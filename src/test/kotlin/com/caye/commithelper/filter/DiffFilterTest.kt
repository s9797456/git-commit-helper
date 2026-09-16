package com.caye.commithelper.filter

import com.caye.commithelper.group.ChangeClassifier
import com.caye.commithelper.group.FileCategory
import com.caye.commithelper.group.LocalGrouper
import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.FileChange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffFilterTest {

    private fun file(path: String, added: Int = 5, removed: Int = 1, lines: Int = 6): FileChange =
        FileChange(
            path = path,
            kind = ChangeKind.MODIFIED,
            added = added,
            removed = removed,
            diffText = (1..lines).joinToString("\n") { "+line $it" },
        )

    @Test
    fun `excludes lock files, build directories and binaries`() {
        assertTrue(ExclusionRules.isExcluded("package-lock.json"))
        assertTrue(ExclusionRules.isExcluded("web/dist/app.js"))
        assertTrue(ExclusionRules.isExcluded("src/main/resources/logo.png"))
        assertTrue(ExclusionRules.isExcluded("build/reports/tests.html"))
        assertTrue(ExclusionRules.isExcluded("node_modules/left-pad/index.js"))
        assertFalse(ExclusionRules.isExcluded("src/main/kotlin/App.kt"))
    }

    @Test
    fun `honours user supplied glob patterns`() {
        assertTrue(ExclusionRules.isExcluded("proto/api.pb.go", listOf("*.pb.go")))
        assertTrue(ExclusionRules.isExcluded("gen/deep/nested/file.txt", listOf("gen/**")))
        assertFalse(ExclusionRules.isExcluded("src/api.pb.txt", listOf("*.pb.go")))
    }

    @Test
    fun `drops noise files and reports them as omitted`() {
        val result = DiffFilter.filter(
            files = listOf(file("src/App.kt"), file("package-lock.json"), file("dist/bundle.js")),
            maxLinesPerFile = 200,
            maxTotalChars = 60_000,
        )
        assertEquals(1, result.included.size)
        assertEquals("src/App.kt", result.included.first().path)
        assertEquals(2, result.omittedFiles.size)
        assertTrue(result.sampled)
        assertTrue(result.diffText.contains("NOTE: diff is sampled."))
        assertTrue(result.diffText.contains("package-lock.json"))
    }

    @Test
    fun `truncates a single file and marks the truncation`() {
        val big = file("src/Big.kt", lines = 50)
        val truncated = DiffFilter.truncate(big, maxLinesPerFile = 10)
        assertTrue(truncated.diffText.contains("... [truncated 40 more lines]"))
        assertEquals(11, truncated.diffText.trim().lines().size)
    }

    @Test
    fun `spends the budget on the largest changes first`() {
        val small = file("src/Small.kt", added = 1, removed = 0, lines = 2)
        val large = file("src/Large.kt", added = 40, removed = 0, lines = 40)
        val medium = file("src/Medium.kt", added = 10, removed = 0, lines = 10)

        val result = DiffFilter.filter(
            files = listOf(small, large, medium),
            maxLinesPerFile = 200,
            maxTotalChars = 450,
        )

        assertTrue(result.included.any { it.path == "src/Large.kt" }, "largest change must survive the budget")
        assertTrue(result.omittedFiles.isNotEmpty(), "budget must actually bite")
        assertTrue(result.fileListText.contains("src/Small.kt"), "file list still mentions every kept file")
    }

    @Test
    fun `file list carries kind and line statistics`() {
        val list = DiffFilter.renderFileList(listOf(file("src/App.kt", added = 7, removed = 2)))
        assertTrue(list.contains("modified src/App.kt (+7/-2)"))
    }
}

class LocalGrouperTest {

    @Test
    fun `groups by module and strips java source roots`() {
        assertEquals("com/caye", LocalGrouper.moduleOf("src/main/kotlin/com/caye/App.kt"))
        assertEquals("docs", LocalGrouper.moduleOf("docs/guide.md"))
        assertEquals("(root)", LocalGrouper.moduleOf("README.md"))
    }

    @Test
    fun `classifies tests, docs, builds and dependencies`() {
        assertEquals(FileCategory.TEST, ChangeClassifier.categorize("src/test/kotlin/AppTest.kt"))
        assertEquals(FileCategory.DOCS, ChangeClassifier.categorize("README.md"))
        assertEquals(FileCategory.BUILD, ChangeClassifier.categorize(".github/workflows/ci.yml"))
        assertEquals(FileCategory.DEPENDENCY, ChangeClassifier.categorize("package.json"))
        assertEquals(FileCategory.SOURCE, ChangeClassifier.categorize("src/main/kotlin/App.kt"))
    }

    @Test
    fun `itemize produces one item per module and category`() {
        val files = listOf(
            FileChange("src/main/kotlin/com/caye/App.kt", ChangeKind.MODIFIED, added = 10, removed = 1),
            FileChange("src/test/kotlin/com/caye/AppTest.kt", ChangeKind.MODIFIED, added = 5, removed = 0),
        )
        val groups = LocalGrouper.group(files)
        val items = LocalGrouper.itemize(groups, chinese = false)
        assertEquals(2, items.size)
        assertTrue(items.any { it.type == "feat" })
        assertTrue(items.any { it.type == "test" })
        assertTrue(items.all { it.scope == "com/caye" })
    }
}
