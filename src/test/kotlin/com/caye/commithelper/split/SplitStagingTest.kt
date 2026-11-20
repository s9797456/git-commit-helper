package com.caye.commithelper.split

import com.caye.commithelper.collect.GitDiffService
import com.caye.commithelper.collect.GitOps
import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.FileChange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import kotlin.io.path.createTempDirectory

class SplitStagingTest : BasePlatformTestCase() {

    private lateinit var repo: File

    override fun setUp() {
        super.setUp()
        repo = createTempDirectory("commit-helper-split").toFile()
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        File(repo, "a.txt").writeText("a\n")
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            if (::repo.isInitialized) repo.deleteRecursively()
        }
    }

    fun testStageAddsExactlyTheSelectedFiles() {
        val ops = GitOps(project, repo)
        assertTrue(ops.stage(listOf("a.txt")))
        assertEquals(listOf("a.txt"), ops.stagedPaths())
    }

    fun testPushAndTagUseTheGroupRepositoryRoot() {
        File(repo, "a.txt").writeText("base\n")
        git("add", "a.txt")
        git("commit", "-m", "chore: base")
        git("branch", "-M", "main")
        val remote = createTempDirectory("commit-helper-remote").toFile()
        git("init", "--bare", remote.absolutePath)
        git("remote", "add", "origin", remote.absolutePath)
        val group = SplitGroup(
            name = "a",
            subject = "feat: base",
            body = listOf("- base"),
            files = listOf(File(repo, "a.txt").absolutePath),
        )
        assertEquals(listOf("origin"), SplitApplier.remotes(project, group))
        assertEquals("main", SplitApplier.currentBranch(project, group))
        assertTrue("push must succeed", SplitApplier.push(project, group, "origin", "main", "v-test"))
        assertTrue(gitOutputAt(remote, "rev-parse", "refs/heads/main").isNotBlank())
        assertEquals(gitOutput("rev-parse", "HEAD").trim(), gitOutputAt(remote, "rev-parse", "refs/tags/v-test").trim())
    }

    fun testFileLevelCommitUsesDiffWithoutManualStaging() {
        File(repo, "a.txt").writeText("base\n")
        git("add", "a.txt")
        git("commit", "-m", "chore: base")
        File(repo, "a.txt").writeText("changed\n")
        val diff = gitOutput("diff", "--", "a.txt")
        val group = SplitGroup(
            name = "a",
            subject = "feat: change a",
            body = listOf("- change a"),
            files = listOf(File(repo, "a.txt").absolutePath),
            diffs = mapOf("a.txt" to diff),
        )
        val result = SplitApplier.commit(project, group, "feat: change a")
        assertTrue("file-level temp-index commit must succeed: " + result.errorMessage, result.success)
        assertEquals("changed\n", gitOutput("show", "HEAD:a.txt"))
    }

    fun testHunkCommitUsesTemporaryIndexAndLeavesWorkingTreeIntact() {
        val base = (1..20).joinToString("\n") { "line" + it } + "\n"
        File(repo, "a.txt").writeText(base)
        git("add", "a.txt")
        git("commit", "-m", "chore: base")
        File(repo, "a.txt").writeText(base.replace("line1\n", "ONE\n").replace("line20", "TWENTY"))
        val diff = gitOutput("diff", "--", "a.txt")
        val hunks = DiffHunkParser.parse(listOf(FileChange("a.txt", ChangeKind.MODIFIED, 2, 2, diff)))
        assertEquals(2, hunks.size)
        val group = SplitGroup(
            name = "first",
            subject = "feat: change one",
            body = listOf("- change one"),
            files = listOf(File(repo, "a.txt").absolutePath),
            hunks = listOf(hunks.first()),
        )
        val result = SplitApplier.commit(project, group, "feat: change one")
        assertTrue("hunk commit must succeed: " + result.errorMessage, result.success)
        val committed = gitOutput("show", "HEAD:a.txt")
        assertTrue("first hunk must be committed", committed.contains("ONE"))
        assertTrue("second hunk must not be committed", committed.contains("line20"))
        assertTrue("working tree keeps both edits", File(repo, "a.txt").readText().contains("TWENTY"))

        // Same file, remaining hunk: re-parse after HEAD moved and commit it in a second group.
        val remainingDiff = gitOutput("diff", "--", "a.txt")
        val remainingHunks = DiffHunkParser.parse(
            listOf(FileChange("a.txt", ChangeKind.MODIFIED, 1, 1, remainingDiff)),
        )
        assertEquals(1, remainingHunks.size)
        assertEquals("remaining hunk id must stay stable after the first commit", hunks[1].id, remainingHunks[0].id)
        val secondGroup = group.copy(
            name = "second",
            subject = "feat: change twenty",
            body = listOf("- change twenty"),
            hunks = remainingHunks,
        )
        val second = SplitApplier.commit(project, secondGroup, "feat: change twenty")
        assertTrue("second hunk commit must succeed: " + second.errorMessage, second.success)
        val finalFile = gitOutput("show", "HEAD:a.txt")
        assertTrue("both hunks must now be committed", finalFile.contains("ONE") && finalFile.contains("TWENTY"))
        assertTrue("working tree must be clean after both commits", gitOutput("status", "--porcelain").isBlank())
    }

    fun testHunkCommitHandlesAnUntrackedNewFile() {
        git("add", "a.txt")
        git("commit", "-m", "chore: base")
        val newFile = File(repo, "new.txt")
        newFile.writeText("hello\nworld\n")
        val diff = GitDiffService(project).synthesizeNewFileDiff(newFile.readText(), "new.txt")
        val hunks = DiffHunkParser.parse(listOf(FileChange("new.txt", ChangeKind.ADDED, 2, 0, diff)))
        assertTrue("new file must produce a hunk", hunks.isNotEmpty())
        val group = SplitGroup(
            name = "new file",
            subject = "feat: add new file",
            body = listOf("- add new file"),
            files = listOf(newFile.absolutePath),
            hunks = hunks,
        )
        val result = SplitApplier.commit(project, group, "feat: add new file")
        assertTrue("new-file hunk commit must succeed: " + result.errorMessage, result.success)
        assertEquals("hello\nworld\n", gitOutput("show", "HEAD:new.txt"))
    }

    fun testCommitOnlyStagesTheSelectedGroup() {
        File(repo, "b.txt").writeText("b\n")
        val ops = GitOps(project, repo)
        assertTrue(ops.stage(listOf("a.txt", "b.txt")))
        assertTrue(ops.commit("feat: add a", listOf("a.txt")))
        assertEquals(listOf("b.txt"), ops.stagedPaths())
        assertTrue(ops.logOneline("--all").any { it.contains("feat: add a") })
    }

    private fun git(vararg args: String) {
        gitOutput(*args)
    }

    private fun gitOutputAt(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "--git-dir", dir.absolutePath) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git " + args.joinToString(" ") + " failed: " + output }
        return output
    }

    private fun gitOutput(vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repo)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "git " + args.joinToString(" ") + " failed: " + output }
        return output
    }
}
