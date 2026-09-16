package com.caye.commithelper.issue

import com.caye.commithelper.collect.DiffBlockParser
import com.caye.commithelper.collect.GitRootLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class IssueKeyExtractorTest {

    @Test
    fun `prefers the branch name`() {
        val key = IssueKeyExtractor.extract("feature/PROJ-123-add-pagination", listOf("ABC-9 other"))
        assertEquals("PROJ-123", key)
    }

    @Test
    fun `falls back to recent commit subjects`() {
        val key = IssueKeyExtractor.extract("main", listOf("chore: bump", "fix(api): PROJ-42 crash"))
        assertEquals("PROJ-42", key)
    }

    @Test
    fun `normalises a lowercase branch key to upper case`() {
        assertEquals("PROJ-7", IssueKeyExtractor.extract("bugfix/proj-7", emptyList()))
    }

    @Test
    fun `ignores technical tokens and date-like segments`() {
        assertNull(IssueKeyExtractor.extract("fix/utf-8-handling", emptyList()))
        assertNull(IssueKeyExtractor.extract("release/2024-01", emptyList()))
        assertNull(IssueKeyExtractor.extract("chore/sha-256-support", emptyList()))
    }

    @Test
    fun `returns null when nothing matches`() {
        assertNull(IssueKeyExtractor.extract(null, emptyList()))
        assertNull(IssueKeyExtractor.extract("main", listOf("feat: nothing here")))
    }
}

class DiffBlockParserTest {

    @Test
    fun `splits a multi file diff into blocks`() {
        val output = """
            diff --git a/src/A.kt b/src/A.kt
            index 111..222 100644
            --- a/src/A.kt
            +++ b/src/A.kt
            @@ -1,2 +1,3 @@
             context
            +added
            diff --git a/src/B.kt b/src/B.kt
            --- a/src/B.kt
            +++ b/src/B.kt
            @@ -1 +1 @@
            -old
            +new
        """.trimIndent()

        val blocks = DiffBlockParser.parse(output)
        assertEquals(2, blocks.size)
        assertEquals("src/A.kt", blocks[0].path)
        assertEquals("src/B.kt", blocks[1].path)
        assertTrue(blocks[0].text.contains("+added"))
    }

    @Test
    fun `counts added and removed lines without the file headers`() {
        val (added, removed) = DiffBlockParser.countLines("--- a/x\n+++ b/x\n@@ -1 +1,2 @@\n-old\n+new\n+extra\n")
        assertEquals(2, added)
        assertEquals(1, removed)
    }

    @Test
    fun `skips the dev null path of a new file`() {
        val blocks = DiffBlockParser.parse("diff --git a/n.txt b/n.txt\n--- /dev/null\n+++ b/n.txt\n+hello\n")
        assertEquals("n.txt", blocks.single().path)
    }
}

class GitRootLocatorTest {

    @Test
    fun `finds the closest ancestor containing a git directory`(@TempDir tempDir: File) {
        val repoRoot = File(tempDir, "repo").apply { mkdirs() }
        File(repoRoot, ".git").mkdirs()
        val nested = File(repoRoot, "src/main/kotlin").apply { mkdirs() }

        assertEquals(repoRoot.canonicalFile, GitRootLocator.findRoot(nested)?.canonicalFile)
    }

    @Test
    fun `returns null outside a repository`(@TempDir tempDir: File) {
        val plain = File(tempDir, "plain/dir").apply { mkdirs() }
        assertNull(GitRootLocator.findRoot(plain))
    }
}
