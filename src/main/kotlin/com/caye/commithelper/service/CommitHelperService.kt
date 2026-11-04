package com.caye.commithelper.service

import com.caye.commithelper.adapter.CommitContext
import com.caye.commithelper.collect.GitDiffService
import com.caye.commithelper.collect.GitOps
import com.caye.commithelper.collect.GitRootLocator
import com.caye.commithelper.filter.DiffFilter
import com.caye.commithelper.filter.ExclusionRules
import com.caye.commithelper.group.LocalGrouper
import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.i18n.localizedName
import com.caye.commithelper.issue.IssueKeyExtractor
import com.caye.commithelper.llm.LlmClient
import com.caye.commithelper.llm.LlmProtocols
import com.caye.commithelper.llm.LlmResponseParser
import com.caye.commithelper.model.ExtraContext
import com.caye.commithelper.model.FilterResult
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.model.GeneratedMessage
import com.caye.commithelper.model.SkipCiApplier
import com.caye.commithelper.settings.ApiKeyStore
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.EffectiveSettings
import com.caye.commithelper.settings.LanguageOption
import com.caye.commithelper.settings.ProviderConfig
import com.caye.commithelper.settings.ProviderKind
import com.caye.commithelper.template.DefaultTemplates
import com.caye.commithelper.template.FormatPromptBuilder
import com.caye.commithelper.template.LanguageDetector
import com.caye.commithelper.template.LocalFallbackBuilder
import com.caye.commithelper.template.MessageRenderer
import com.caye.commithelper.template.OutputLanguage
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
    /** Non-fatal problem to surface to the user after the message has been written. */
    val warning: String? = null,
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

    private val settings: CommitHelperSettings get() = CommitHelperSettings.getInstance()

    fun organize(
        context: CommitContext,
        indicator: ProgressIndicator?,
        useLlm: Boolean = true,
        extra: ExtraContext? = null,
    ): GenerationOutcome {
        val state = settings.state
        indicator?.text = CommitHelperBundle.message("progress.collectingChanges")

        val files = GitDiffService(project).collect(
            changes = context.includedChanges,
            unversioned = context.unversionedFiles(),
            includeDiff = state.includeDiff,
        )
        if (files.isEmpty()) {
            return GenerationOutcome(
                text = "",
                usedFallback = true,
                errorMessage = CommitHelperBundle.message("error.noChangesToOrganize"),
            )
        }

        val root = repoRoot(context, files)
        val gitOps = GitOps.forPath(project, root)
        val recentSubjects = gitOps?.recentSubjects(20).orEmpty()
        val language = LanguageDetector.forOption(state.language, recentSubjects)
        val promptProfile = EffectiveSettings.promptProfile(project)
        val template = EffectiveSettings.template(project)

        val filterResult = DiffFilter.filter(
            files = files,
            maxLinesPerFile = state.maxLinesPerFile,
            maxTotalChars = state.maxTotalChars,
            extraExcludes = state.extraExcludes,
        )
        // A silent empty diff is how the multi-repo path bug looked to the user: only file names
        // and "+0/-0" reached the model. Keep a visible warning for any remaining cause.
        val missingDiffWarning = buildDiffWarning(state.includeDiff, files, filterResult)
        indicator?.text = CommitHelperBundle.message("progress.groupingChanges")

        val localMessage = LocalFallbackBuilder.build(files, language)
        val skeleton = if (state.useRepoCommitTemplate) SkeletonReader.read(gitOps, root) else null
        val branch = gitOps?.branchName()
        val extractedIssue = IssueKeyExtractor.extract(branch, recentSubjects)
        val explicitIssue = extra?.issues?.trim()?.takeIf { it.isNotBlank() }
        val issue = explicitIssue ?: extractedIssue

        fun render(message: GeneratedMessage): String =
            SkipCiApplier.apply(
                MessageRenderer.render(
                    message = message.copy(issue = message.issue ?: issue),
                    formatTemplate = template.format,
                    itemLineTemplate = template.itemLine,
                    skeleton = skeleton,
                ),
                extra?.skipCi,
            )

        val provider = state.provider
        val protocol = LlmProtocols.of(provider)
        val apiKey = ApiKeyStore.get(provider)

        if (!useLlm || protocol == null || apiKey.isNullOrBlank()) {
            val reason = when {
                !useLlm -> null
                protocol == null -> null // "Local only" is a configuration, not a failure
                else -> CommitHelperBundle.message("error.noApiKey", provider.localizedName())
            }
            return GenerationOutcome(
                text = render(localMessage),
                usedFallback = true,
                errorMessage = reason,
                providerLabel = provider.localizedName(),
                usedRepoTemplate = !skeleton.isNullOrBlank(),
                includedFiles = files.size,
                sampled = filterResult.sampled,
                warning = missingDiffWarning,
            )
        }

        if (!confirmSending(state, files, filterResult)) {
            return GenerationOutcome(
                text = "",
                usedFallback = true,
                errorMessage = null,
                providerLabel = provider.localizedName(),
            )
        }

        indicator?.text = CommitHelperBundle.message("progress.askingProvider", provider.localizedName())
        return try {
            val prompt = PromptBuilder.build(
                promptTemplate = promptProfile.prompt,
                language = language.promptName,
                formatTemplate = template.format,
                skeleton = skeleton,
                filterResult = filterResult,
                types = state.commitTypes,
                extraContext = extra?.promptText(),
            )
            val config = settings.configFor(provider)
            val client = LlmClient(protocol, config, apiKey)
            val raw = client.complete(prompt)
            val parsed = LlmResponseParser.parse(raw) ?: retryWithCompactPrompt(client, prompt, config)
                ?: throw IllegalStateException(CommitHelperBundle.message("error.invalidJson"))
            val finalMessage = parsed.copy(issue = parsed.issue ?: issue)
            GenerationOutcome(
                text = render(finalMessage),
                usedFallback = false,
                providerLabel = provider.localizedName(),
                usedRepoTemplate = !skeleton.isNullOrBlank(),
                includedFiles = files.size,
                sampled = filterResult.sampled,
                warning = missingDiffWarning,
            )
        } catch (e: Exception) {
            GenerationOutcome(
                text = render(localMessage),
                usedFallback = true,
                errorMessage = e.message ?: e.javaClass.simpleName,
                providerLabel = provider.localizedName(),
                usedRepoTemplate = !skeleton.isNullOrBlank(),
                includedFiles = files.size,
                sampled = filterResult.sampled,
                warning = missingDiffWarning,
            )
        }
    }

    /**
     * LLM-only: reformat the existing commit message with the selected template. The message is
     * never rewritten locally, because a free-form message cannot be parsed deterministically.
     */
    fun format(context: CommitContext, indicator: ProgressIndicator?): GenerationOutcome {
        val current = context.target.getText().trim()
        if (current.isEmpty()) {
            return GenerationOutcome(
                text = "",
                usedFallback = true,
                errorMessage = CommitHelperBundle.message("error.emptyCommitMessage"),
            )
        }
        val state = settings.state
        val provider = state.provider
        val protocol = LlmProtocols.of(provider)
        val apiKey = ApiKeyStore.get(provider)
        if (protocol == null || apiKey.isNullOrBlank()) {
            return GenerationOutcome(
                text = "",
                usedFallback = true,
                errorMessage = CommitHelperBundle.message("error.formatNeedsProvider"),
                providerLabel = provider.localizedName(),
            )
        }

        val root = repoRoot(context, emptyList())
        val gitOps = GitOps.forPath(project, root)
        val recentSubjects = gitOps?.recentSubjects(20).orEmpty()
        val language = if (state.language == LanguageOption.AUTO) {
            LanguageDetector.detect(recentSubjects.ifEmpty { listOf(current) })
        } else {
            LanguageDetector.forOption(state.language, recentSubjects)
        }
        val template = EffectiveSettings.template(project)
        val promptProfile = EffectiveSettings.promptProfile(project)
        val config = settings.configFor(provider)
        val client = LlmClient(protocol, config, apiKey)
        val prompt = FormatPromptBuilder.build(
            language = language.promptName,
            formatTemplate = template.format,
            types = state.commitTypes,
            currentMessage = current,
            guidance = promptProfile.prompt,
        )
        indicator?.text = CommitHelperBundle.message("progress.reformattingMessage")
        return try {
            val raw = client.complete(prompt)
            val parsed = LlmResponseParser.parse(raw) ?: retryWithCompactPrompt(client, prompt, config)
                ?: throw IllegalStateException(CommitHelperBundle.message("error.invalidJson"))
            val preservedIssue = parsed.issue ?: IssueKeyExtractor.extract(null, listOf(current))
            GenerationOutcome(
                text = MessageRenderer.render(
                    message = parsed.copy(issue = preservedIssue),
                    formatTemplate = template.format,
                    itemLineTemplate = template.itemLine,
                    skeleton = null,
                ),
                usedFallback = false,
                providerLabel = provider.localizedName(),
            )
        } catch (e: Exception) {
            GenerationOutcome(
                text = "",
                usedFallback = true,
                errorMessage = e.message ?: e.javaClass.simpleName,
                providerLabel = provider.localizedName(),
            )
        }
    }

    /**
     * A large change set can exhaust the output budget and cut the JSON mid-object. Retry once
     * with a bigger budget and a compact instruction before falling back to the local list.
     */
    private fun retryWithCompactPrompt(
        client: LlmClient,
        prompt: String,
        config: ProviderConfig,
    ): GeneratedMessage? {
        val compact = prompt + """

Return STRICTLY valid JSON only (no Markdown, no commentary).
At most 8 items; each item text at most 40 characters.
"""
        return try {
            val raw = client.complete(compact, maxTokens = (config.maxTokens * 2).coerceAtMost(8192))
            LlmResponseParser.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    /** M3: changelog for the checked changes plus a PR description for the current branch. */
    fun generateChangelog(context: CommitContext, indicator: ProgressIndicator?): ChangelogOutcome {
        val state = settings.state
        val files = GitDiffService(project).collect(
            changes = context.includedChanges,
            unversioned = context.unversionedFiles(),
            includeDiff = false,
        )
        val root = repoRoot(context, files)
        val gitOps = GitOps.forPath(project, root)
        val language = LanguageDetector.forOption(state.language, gitOps?.recentSubjects(20).orEmpty())
        val template = EffectiveSettings.template(project)

        val groups = LocalGrouper.group(files)
        val changelog = buildString {
            append(changelogTitle(language)).append("\n\n")
            for (group in groups) {
                append("## ").append(group.name).append('\n')
                for (item in LocalGrouper.itemize(listOf(group), language)) {
                    append(MessageRenderer.renderItem(item, template.itemLine)).append('\n')
                }
                append('\n')
            }
        }

        val prBody = buildPrDescription(gitOps, language, indicator)
        return ChangelogOutcome(changelog = changelog, prDescription = prBody, usedFallback = false)
    }

    private fun buildPrDescription(
        gitOps: GitOps?,
        language: OutputLanguage,
        indicator: ProgressIndicator?,
    ): String {
        if (gitOps == null) return if (language == OutputLanguage.CHINESE) "（无法定位 git 仓库）" else "(could not locate the git repository)"
        val base = gitOps.defaultBranch()
        val branch = gitOps.branchName()
        val heading = prTitle(language)
        val range = if (base != null) base + "...HEAD" else null
        val commits = if (range != null) gitOps.logOneline(base + "..HEAD") else emptyList()
        val stat = if (range != null) gitOps.diffStat(range) else ""

        indicator?.text = CommitHelperBundle.message("progress.buildingPrDescription")
        return buildString {
            append(heading).append("\n\n")
            if (branch != null) {
                append(branchLabel(language)).append(branch).append('\n')
            }
            if (base != null) {
                append(baseLabel(language)).append(base).append('\n')
            }
            append('\n')
            if (commits.isNotEmpty()) {
                append(commitsTitle(language)).append("\n\n")
                commits.take(50).forEach { append("- ").append(it).append('\n') }
                append('\n')
            }
            if (stat.isNotBlank()) {
                append(filesTitle(language)).append("\n\n").append("~~~\n")
                append(stat).append("\n~~~\n\n")
            }
            append(testingLabel(language))
        }
    }

    private fun changelogTitle(language: OutputLanguage): String = when (language) {
        OutputLanguage.CHINESE -> "# 变更日志"
        OutputLanguage.ENGLISH -> "# Changelog"
        OutputLanguage.JAPANESE -> "# 変更履歴"
        OutputLanguage.KOREAN -> "# 변경 로그"
    }

    private fun prTitle(language: OutputLanguage): String = when (language) {
        OutputLanguage.CHINESE -> "# 变更说明"
        OutputLanguage.ENGLISH -> "# Summary"
        OutputLanguage.JAPANESE -> "# 変更内容"
        OutputLanguage.KOREAN -> "# 변경 요약"
    }

    private fun branchLabel(language: OutputLanguage): String = when (language) {
        OutputLanguage.CHINESE -> "- 分支："
        OutputLanguage.ENGLISH -> "- Branch: "
        OutputLanguage.JAPANESE -> "- ブランチ："
        OutputLanguage.KOREAN -> "- 브랜치: "
    }

    private fun baseLabel(language: OutputLanguage): String = when (language) {
        OutputLanguage.CHINESE -> "- 对比基线："
        OutputLanguage.ENGLISH -> "- Base: "
        OutputLanguage.JAPANESE -> "- ベース："
        OutputLanguage.KOREAN -> "- 기준: "
    }

    private fun commitsTitle(language: OutputLanguage): String = when (language) {
        OutputLanguage.CHINESE -> "## 提交"
        OutputLanguage.ENGLISH -> "## Commits"
        OutputLanguage.JAPANESE -> "## コミット"
        OutputLanguage.KOREAN -> "## 커밋"
    }

    private fun filesTitle(language: OutputLanguage): String = when (language) {
        OutputLanguage.CHINESE -> "## 文件统计"
        OutputLanguage.ENGLISH -> "## Files"
        OutputLanguage.JAPANESE -> "## ファイル統計"
        OutputLanguage.KOREAN -> "## 파일 통계"
    }

    private fun testingLabel(language: OutputLanguage): String = when (language) {
        OutputLanguage.CHINESE -> "## 测试\n\n- [ ] 未验证"
        OutputLanguage.ENGLISH -> "## Testing\n\n- [ ] Not verified"
        OutputLanguage.JAPANESE -> "## テスト\n\n- [ ] 未検証"
        OutputLanguage.KOREAN -> "## 테스트\n\n- [ ] 미검증"
    }

    /** Null when diff content is present, disabled on purpose, or the changes are all binary. */
    private fun buildDiffWarning(
        includeDiff: Boolean,
        files: List<FileChange>,
        filterResult: FilterResult,
    ): String? {
        if (!includeDiff || files.isEmpty()) return null
        if (filterResult.included.any { it.diffText.isNotBlank() }) return null
        if (files.all { ExclusionRules.isBinaryPath(it.path) }) return null
        return CommitHelperBundle.message("warning.diffUnavailable")
    }

    private fun repoRoot(context: CommitContext, files: List<FileChange>): File? {
        context.unversionedFiles().firstOrNull()?.let { return GitRootLocator.findRoot(it.parentFile) }
        val change = context.includedChanges.firstOrNull()
        val path = change?.afterRevision?.file ?: change?.beforeRevision?.file
        path?.ioFile?.let { return GitRootLocator.findRoot(it.parentFile) }
        if (files.isNotEmpty()) return GitRootLocator.findRoot(File(files.first().path).parentFile)
        return project.basePath?.let { File(it) }
    }

    private fun confirmSending(
        state: CommitHelperSettings.State,
        files: List<FileChange>,
        filterResult: FilterResult,
    ): Boolean {
        if (!state.confirmBeforeSending) return true
        val listing = files.take(20).joinToString("\n") { "  - " + it.path } +
            if (files.size > 20) {
                "\n  " + CommitHelperBundle.message("dialog.confirmSend.more", files.size - 20)
            } else {
                ""
            }
        val message = buildString {
            appendLine(CommitHelperBundle.message("dialog.confirmSend.question", state.provider.localizedName()))
            appendLine()
            appendLine(CommitHelperBundle.message("dialog.confirmSend.files", files.size))
            appendLine(listing)
            appendLine()
            append(CommitHelperBundle.message("dialog.confirmSend.payload", filterResult.diffText.length))
        }
        var answer = false
        val app = ApplicationManager.getApplication()
        val show = Runnable {
            answer = Messages.showYesNoDialog(
                project,
                message,
                CommitHelperBundle.message("dialog.confirmSend.title"),
                Messages.getQuestionIcon(),
            ) == Messages.YES
        }
        if (app.isDispatchThread) show.run() else app.invokeAndWait(show)
        return answer
    }
}
