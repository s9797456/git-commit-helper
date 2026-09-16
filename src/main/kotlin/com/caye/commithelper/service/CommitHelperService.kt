package com.caye.commithelper.service

import com.caye.commithelper.adapter.CommitContext
import com.caye.commithelper.collect.GitDiffService
import com.caye.commithelper.collect.GitOps
import com.caye.commithelper.collect.GitRootLocator
import com.caye.commithelper.filter.DiffFilter
import com.caye.commithelper.group.LocalGrouper
import com.caye.commithelper.issue.IssueKeyExtractor
import com.caye.commithelper.llm.LlmClient
import com.caye.commithelper.llm.LlmProtocols
import com.caye.commithelper.llm.LlmResponseParser
import com.caye.commithelper.model.GeneratedMessage
import com.caye.commithelper.model.FilterResult
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.settings.ApiKeyStore
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.LanguageOption
import com.caye.commithelper.settings.ProviderKind
import com.caye.commithelper.template.LanguageDetector
import com.caye.commithelper.template.LocalFallbackBuilder
import com.caye.commithelper.template.MessageRenderer
import com.caye.commithelper.template.PromptBuilder
import com.caye.commithelper.template.SkeletonReader
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File

data class GenerationOutcome(
    val text: String,
    val usedFallback: Boolean,
    val errorMessage: String? = null,
    val providerLabel: String = "",
    val usedRepoTemplate: Boolean = false,
    val includedFiles: Int = 0,
    val sampled: Boolean = false,
)

data class ChangelogOutcome(
    val changelog: String,
    val prDescription: String,
    val usedFallback: Boolean,
    val errorMessage: String? = null,
)

/**
 * Orchestrates one generation run: collect -> filter -> local skeleton -> (optional) LLM
 * wording -> render. Never performs git write operations and never commits.
 */
class CommitHelperService(private val project: Project) {

    fun organize(context: CommitContext, indicator: ProgressIndicator?, useLlm: Boolean = true): GenerationOutcome {
        val state = CommitHelperSettings.getInstance().state
        indicator?.text = "Collecting checked changes..."

        val files = GitDiffService(project).collect(
            changes = context.includedChanges,
            unversioned = context.unversionedFiles(),
            includeDiff = state.includeDiff,
        )
        if (files.isEmpty()) {
            return GenerationOutcome(
                text = "",
                usedFallback = true,
                errorMessage = "No changes to organize.",
            )
        }

        val root = repoRoot(context, files)
        val gitOps = GitOps.forPath(project, root)
        val chinese = resolveChinese(state.language, gitOps)
        val filterResult = DiffFilter.filter(
            files = files,
            maxLinesPerFile = state.maxLinesPerFile,
            maxTotalChars = state.maxTotalChars,
            extraExcludes = state.extraExcludes,
        )
        indicator?.text = "Grouping changes..."

        val localMessage = LocalFallbackBuilder.build(files, chinese)
        val skeleton = if (state.useRepoCommitTemplate) SkeletonReader.read(gitOps, root) else null
        val subjects = gitOps?.recentSubjects(20).orEmpty()
        val branch = gitOps?.branchName()
        val extractedIssue = IssueKeyExtractor.extract(branch, subjects)

        val provider = state.provider
        val protocol = LlmProtocols.of(provider)
        val apiKey = ApiKeyStore.get(provider)

        val renderLocal = { message: GeneratedMessage, issue: String? ->
            MessageRenderer.render(
                message = message.copy(issue = issue),
                formatTemplate = state.formatTemplate,
                itemLineTemplate = state.itemLineTemplate,
                skeleton = skeleton,
            )
        }

        if (!useLlm || protocol == null || apiKey.isNullOrBlank()) {
            val reason = when {
                !useLlm -> null
                protocol == null -> null // "Local only" is a configuration, not a failure
                else -> "No API key configured for ${provider.displayName}."
            }
            return GenerationOutcome(
                text = renderLocal(localMessage, extractedIssue),
                usedFallback = true,
                errorMessage = reason,
                providerLabel = provider.displayName,
                usedRepoTemplate = !skeleton.isNullOrBlank(),
                includedFiles = files.size,
                sampled = filterResult.sampled,
            )
        }

        if (!confirmSending(state, files, filterResult)) {
            return GenerationOutcome(
                text = "",
                usedFallback = true,
                errorMessage = null,
                providerLabel = provider.displayName,
            )
        }

        indicator?.text = "Asking ${provider.displayName}..."
        return try {
            val prompt = PromptBuilder.build(
                promptTemplate = state.promptTemplate,
                language = if (chinese) "Chinese" else "English",
                formatTemplate = state.formatTemplate,
                skeleton = skeleton,
                filterResult = filterResult,
            )
            val config = state.let { CommitHelperSettings.getInstance().configFor(provider) }
            val raw = LlmClient(protocol, config, apiKey).complete(prompt)
            val parsed = LlmResponseParser.parse(raw)
                ?: throw IllegalStateException("Model answer was not valid JSON")
            val finalMessage = parsed.copy(issue = parsed.issue ?: extractedIssue)
            GenerationOutcome(
                text = MessageRenderer.render(
                    message = finalMessage,
                    formatTemplate = state.formatTemplate,
                    itemLineTemplate = state.itemLineTemplate,
                    skeleton = skeleton,
                ),
                usedFallback = false,
                providerLabel = provider.displayName,
                usedRepoTemplate = !skeleton.isNullOrBlank(),
                includedFiles = files.size,
                sampled = filterResult.sampled,
            )
        } catch (e: Exception) {
            GenerationOutcome(
                text = renderLocal(localMessage, extractedIssue),
                usedFallback = true,
                errorMessage = e.message ?: e.javaClass.simpleName,
                providerLabel = provider.displayName,
                usedRepoTemplate = !skeleton.isNullOrBlank(),
                includedFiles = files.size,
                sampled = filterResult.sampled,
            )
        }
    }

    /** M3: changelog for the checked changes plus a PR description for the current branch. */
    fun generateChangelog(context: CommitContext, indicator: ProgressIndicator?): ChangelogOutcome {
        val state = CommitHelperSettings.getInstance().state
        val files = GitDiffService(project).collect(
            changes = context.includedChanges,
            unversioned = context.unversionedFiles(),
            includeDiff = false,
        )
        val root = repoRoot(context, files)
        val gitOps = GitOps.forPath(project, root)
        val chinese = resolveChinese(state.language, gitOps)

        val groups = LocalGrouper.group(files)
        val changelog = buildString {
            append(if (chinese) "# 变更日志\n\n" else "# Changelog\n\n")
            for (group in groups) {
                append("## ").append(group.name).append('\n')
                for (item in LocalGrouper.itemize(listOf(group), chinese)) {
                    append(MessageRenderer.renderItem(item, state.itemLineTemplate)).append('\n')
                }
                append('\n')
            }
        }

        val prBody = buildPrDescription(gitOps, chinese, indicator)
        return ChangelogOutcome(changelog = changelog, prDescription = prBody, usedFallback = false)
    }

    private fun buildPrDescription(gitOps: GitOps?, chinese: Boolean, indicator: ProgressIndicator?): String {
        if (gitOps == null) return if (chinese) "（无法定位 git 仓库）" else "(could not locate the git repository)"
        val base = gitOps.defaultBranch()
        val branch = gitOps.branchName()
        val heading = if (chinese) "# 变更说明" else "# Summary"
        val range = if (base != null) "$base...HEAD" else null
        val commits = if (range != null) gitOps.logOneline("$base..HEAD") else emptyList()
        val stat = if (range != null) gitOps.diffStat(range) else ""

        indicator?.text = "Building PR description..."
        return buildString {
            append(heading).append("\n\n")
            if (branch != null) {
                append(if (chinese) "- 分支：" else "- Branch: ").append(branch).append('\n')
            }
            if (base != null) {
                append(if (chinese) "- 对比基线：" else "- Base: ").append(base).append('\n')
            }
            append('\n')
            if (commits.isNotEmpty()) {
                append(if (chinese) "## 提交\n\n" else "## Commits\n\n")
                commits.take(50).forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            if (stat.isNotBlank()) {
                append(if (chinese) "## 文件统计\n\n```\n" else "## Files\n\n```\n")
                append(stat).append("\n```\n\n")
            }
            append(if (chinese) "## 测试\n\n- [ ] 未验证" else "## Testing\n\n- [ ] Not verified")
        }
    }

    private fun repoRoot(context: CommitContext, files: List<FileChange>): File? {
        context.unversionedFiles().firstOrNull()?.let { return GitRootLocator.findRoot(it.parentFile) }
        val change = context.includedChanges.firstOrNull()
        val path = change?.afterRevision?.file ?: change?.beforeRevision?.file
        path?.ioFile?.let { return GitRootLocator.findRoot(it.parentFile) }
        if (files.isNotEmpty()) return GitRootLocator.findRoot(File(files.first().path).parentFile)
        return project.basePath?.let { File(it) }
    }

    private fun resolveChinese(language: LanguageOption, gitOps: GitOps?): Boolean = when (language) {
        LanguageOption.CHINESE -> true
        LanguageOption.ENGLISH -> false
        LanguageOption.AUTO -> LanguageDetector.isChineseHistory(gitOps?.recentSubjects(20).orEmpty())
    }

    private fun confirmSending(
        state: CommitHelperSettings.State,
        files: List<FileChange>,
        filterResult: FilterResult,
    ): Boolean {
        if (!state.confirmBeforeSending) return true
        val listing = files.take(20).joinToString("\n") { "  - ${it.path}" } +
            if (files.size > 20) "\n  ... (+${files.size - 20} more)" else ""
        val message = buildString {
            appendLine("Send the following changes to ${state.provider.displayName}?")
            appendLine()
            appendLine("Files (${files.size}):")
            appendLine(listing)
            appendLine()
            append("Payload: ${filterResult.diffText.length} characters of diff")
        }
        var answer = false
        val app = ApplicationManager.getApplication()
        val show = Runnable {
            answer = Messages.showYesNoDialog(
                project,
                message,
                "Commit Helper — Send Code to Model",
                Messages.getQuestionIcon(),
            ) == Messages.YES
        }
        if (app.isDispatchThread) show.run() else app.invokeAndWait(show)
        return answer
    }
}
