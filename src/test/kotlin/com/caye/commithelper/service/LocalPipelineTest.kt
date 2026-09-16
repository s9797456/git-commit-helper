package com.caye.commithelper.service

import com.caye.commithelper.adapter.CommitContext
import com.caye.commithelper.adapter.NoOpTarget
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.LanguageOption
import com.caye.commithelper.settings.ProviderKind
import com.caye.commithelper.template.DefaultTemplates
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * End-to-end run of the offline pipeline against a real temporary git repository:
 * collect (unversioned files) -> filter -> local grouping -> render -> outcome.
 *
 * This is the headless half of the sandbox smoke checklist: the no-key fallback, the sampling
 * note, issue-key injection from the branch name and the language switch are all asserted here
 * without a commit dialog.
 */
class LocalPipelineTest : BasePlatformTestCase() {

    private lateinit var repo: File

    override fun setUp() {
        super.setUp()
        repo = createTempDirectory("commit-helper-repo").toFile()
        git("init")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        git("config", "commit.gpgsign", "false")
        File(repo, "README.md").writeText("# demo\n")
        git("add", ".")
        git("commit", "-m", "chore: initial commit")
        git("checkout", "-b", "feature/ABC-123-add-widget")
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            if (::repo.isInitialized) repo.deleteRecursively()
        }
    }

    fun testLocalOnlyPipelineRendersCheckedFilesAndTheBranchIssueKey() {
        val paths = listOf(
            "src/main/kotlin/demo/Widget.kt",
            "src/test/kotlin/demo/WidgetTest.kt",
            "docs/widget.md",
        ).map { VcsUtil.getFilePath(write(it, "fun widget() = 1\n".repeat(4))) }

        withSettings(
            provider = ProviderKind.LOCAL_ONLY,
            language = LanguageOption.ENGLISH,
            formatTemplate = "{subject}\n\n{items}\n\nRefs: {issue}\n",
        ) {
            val outcome = CommitHelperService(project).organize(CommitContext(project, NoOpTarget(), emptyList(), paths), null)

            assertTrue("local-only generation must report a fallback:\n${outcome.text}", outcome.usedFallback)
            assertEquals(3, outcome.includedFiles)
            assertNull("LOCAL_ONLY is a configuration, not a failure", outcome.errorMessage)
            assertTrue(
                "the itemized body must name the changed files:\n${outcome.text}",
                outcome.text.contains("Widget.kt") && outcome.text.contains("widget.md"),
            )
            assertTrue(
                "the issue key must be taken from the branch name:\n${outcome.text}",
                outcome.text.contains("ABC-123"),
            )
            assertTrue(
                "item lines must use the item template:\n${outcome.text}",
                outcome.text.lines().any { it.startsWith("- [") },
            )
        }
    }

    fun testNoiseFilesAreSampledWithoutLosingTheMessage() {
        val real = (1..4).map { VcsUtil.getFilePath(write("src/main/kotlin/demo/Class$it.kt", "class C$it\n")) }
        val noise = listOf(
            "package-lock.json",
            "yarn.lock",
            "assets/logo.png",
            "node_modules/left-pad/index.js",
            "dist/bundle.js",
        ).map { VcsUtil.getFilePath(write(it, "noise\n".repeat(10))) }

        withSettings(provider = ProviderKind.LOCAL_ONLY, language = LanguageOption.ENGLISH) {
            val outcome = CommitHelperService(project)
                .organize(CommitContext(project, NoOpTarget(), emptyList(), real + noise), null)

            assertTrue("noise files must be dropped, so the result is a sample", outcome.sampled)
            assertEquals("every checked file is still accounted for", 9, outcome.includedFiles)
            assertTrue("the message must survive sampling:\n${outcome.text}", outcome.text.isNotBlank())
            assertTrue(
                "noise paths must not leak into the body:\n${outcome.text}",
                !outcome.text.contains("package-lock.json") && !outcome.text.contains("node_modules"),
            )
        }
    }

    fun testLanguageSwitchChangesTheGeneratedSubject() {
        val paths = listOf(VcsUtil.getFilePath(write("src/main/kotlin/demo/Thing.kt", "class Thing\n")))

        withSettings(provider = ProviderKind.LOCAL_ONLY, language = LanguageOption.CHINESE) {
            val chinese = CommitHelperService(project)
                .organize(CommitContext(project, NoOpTarget(), emptyList(), paths), null)
            assertTrue("Chinese mode must produce a Chinese subject:\n${chinese.text}", chinese.text.startsWith("更新"))
        }
        withSettings(provider = ProviderKind.LOCAL_ONLY, language = LanguageOption.ENGLISH) {
            val english = CommitHelperService(project)
                .organize(CommitContext(project, NoOpTarget(), emptyList(), paths), null)
            assertTrue("English mode must produce an English subject:\n${english.text}", english.text.startsWith("Update"))
        }
    }

    fun testEmptyChangeSetIsReportedInsteadOfWritingAnEmptyMessage() {
        withSettings(provider = ProviderKind.LOCAL_ONLY, language = LanguageOption.ENGLISH) {
            val outcome = CommitHelperService(project)
                .organize(CommitContext(project, NoOpTarget(), emptyList(), emptyList()), null)
            assertEquals("", outcome.text)
            assertEquals("No changes to organize.", outcome.errorMessage)
        }
    }

    private fun write(relative: String, content: String): File {
        val file = File(repo, relative)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    private fun git(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repo)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} failed with $code: $output" }
    }

    /** Applies settings for the duration of [block] and restores the previous values. */
    private fun <T> withSettings(
        provider: ProviderKind,
        language: LanguageOption,
        formatTemplate: String = DefaultTemplates.FORMAT,
        block: () -> T,
    ): T {
        val state = CommitHelperSettings.getInstance().state
        val savedProvider = state.provider
        val savedLanguage = state.language
        val savedFormat = state.formatTemplate
        val savedUseTemplate = state.useRepoCommitTemplate
        try {
            state.provider = provider
            state.language = language
            state.formatTemplate = formatTemplate
            state.useRepoCommitTemplate = false
            return block()
        } finally {
            state.provider = savedProvider
            state.language = savedLanguage
            state.formatTemplate = savedFormat
            state.useRepoCommitTemplate = savedUseTemplate
        }
    }
}
