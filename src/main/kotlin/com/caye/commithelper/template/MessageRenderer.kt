package com.caye.commithelper.template

import com.caye.commithelper.model.GeneratedMessage
import com.caye.commithelper.model.FilterResult
import com.caye.commithelper.model.ChangeItem
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.group.LocalGrouper
import com.caye.commithelper.settings.CommitType

/** Renders a [GeneratedMessage] into the final commit message text. */
object MessageRenderer {

    fun render(
        message: GeneratedMessage,
        formatTemplate: String = DefaultTemplates.FORMAT,
        itemLineTemplate: String = DefaultTemplates.ITEM_LINE,
        skeleton: String? = null,
    ): String {
        val itemsBlock = message.items.joinToString("\n") { renderItem(it, itemLineTemplate) }
        val subject = message.subject.trim()

        if (!skeleton.isNullOrBlank()) {
            return fillSkeleton(skeleton, subject, itemsBlock, message.issue, itemLineTemplate)
        }

        return formatTemplate
            .replace("{subject}", subject)
            .replace("{items}", itemsBlock)
            .replace("{issue}", message.issue.orEmpty())
            .replace("{skeleton}", "")
            .trimEnd() + "\n"
    }

    fun renderItem(item: ChangeItem, itemLineTemplate: String): String {
        val scope = item.scope.trim()
        return itemLineTemplate
            .replace("{scope}", if (scope.isEmpty()) "" else " ($scope)")
            .replace("{scopeRaw}", scope)
            .replace("{type}", item.type)
            .replace("{text}", item.text)
            .trim()
    }

    /**
     * Fills a repository `.gitmessage` skeleton.
     *
     * Algorithm (deterministic, comment lines are always preserved verbatim):
     * 1. Replace explicit tokens (`{subject}`/`<subject>`, `{items}`/`<items>`, `{type}`, `{scope}`, `{issue}`).
     * 2. If the skeleton had no items token, insert the items under the first section heading
     *    (a `# ...:` comment line; short keyword headings are only used as a fallback, so
     *    boilerplate such as "…for your changes." never wins over a real heading);
     *    with no heading at all, append them at the end.
     * 3. If the skeleton ended up without the subject, prepend `subject` + blank line.
     */
    fun fillSkeleton(
        skeleton: String,
        subject: String,
        itemsBlock: String,
        issue: String?,
        itemLineTemplate: String = DefaultTemplates.ITEM_LINE,
    ): String {
        var hadSubjectToken = false
        var hadItemsToken = false

        val replaced = skeleton.split('\n').joinToString("\n") { line ->
            var out = line
            if (out.contains("{subject}") || out.contains("<subject>")) hadSubjectToken = true
            if (out.contains("{items}") || out.contains("<items>")) hadItemsToken = true
            out = out
                .replace("{subject}", subject)
                .replace("<subject>", subject)
                .replace("{items}", itemsBlock)
                .replace("<items>", itemsBlock)
                .replace("{issue}", issue.orEmpty())
                .replace("<issue>", issue.orEmpty())
                .replace("{type}", "feat")
                .replace("<type>", "feat")
                .replace("{scope}", "")
                .replace("<scope>", "")
            out
        }

        val lines = replaced.split('\n').toMutableList()

        if (!hadItemsToken && itemsBlock.isNotBlank()) {
            val headingIndex = findHeadingIndex(lines)
            val inserted = itemsBlock.split('\n')
            if (headingIndex >= 0) {
                lines.addAll(headingIndex + 1, inserted)
            } else {
                lines.addAll(inserted)
            }
        }

        var result = lines.joinToString("\n")
        if (!hadSubjectToken && subject.isNotEmpty()) {
            result = subject + "\n\n" + result
        }
        return result.trimEnd() + "\n"
    }

    private val HEADING_KEYWORDS = Regex(
        "(?i)(why|what|how|changes?|details?|description|summary|notes?|tests?|issues?|" +
            "变更|改动|内容|描述|说明|原因|测试|关联|背景)",
    )

    private fun findHeadingIndex(lines: List<String>): Int {
        val colonHeading = lines.indexOfFirst { isColonHeading(it) }
        if (colonHeading >= 0) return colonHeading
        return lines.indexOfFirst { isKeywordHeading(it) }
    }

    private fun isColonHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#")) return false
        val body = trimmed.trimStart('#').trim()
        return body.isNotEmpty() && (body.endsWith(":") || body.endsWith("："))
    }

    private fun isKeywordHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (!trimmed.startsWith("#")) return false
        val body = trimmed.trimStart('#').trim()
        if (body.isEmpty() || body.length > MAX_KEYWORD_HEADING_LENGTH) return false
        return HEADING_KEYWORDS.containsMatchIn(body)
    }

    private const val MAX_KEYWORD_HEADING_LENGTH = 40
}

/** Builds the LLM-free fallback message from the local grouping. */
object LocalFallbackBuilder {

    fun build(files: List<FileChange>, chinese: Boolean): GeneratedMessage =
        build(files, if (chinese) OutputLanguage.CHINESE else OutputLanguage.ENGLISH)

    fun build(files: List<FileChange>, language: OutputLanguage): GeneratedMessage {
        val groups = LocalGrouper.group(files)
        val modules = groups.map { it.name }
        return GeneratedMessage(
            subject = buildSubject(modules, language),
            items = LocalGrouper.itemize(groups, language),
            issue = null,
        )
    }

    fun buildSubject(modules: List<String>, chinese: Boolean): String =
        buildSubject(modules, if (chinese) OutputLanguage.CHINESE else OutputLanguage.ENGLISH)

    fun buildSubject(modules: List<String>, language: OutputLanguage): String {
        val shown = modules.take(3)
        val separator = when (language) {
            OutputLanguage.CHINESE, OutputLanguage.JAPANESE -> "、"
            OutputLanguage.ENGLISH, OutputLanguage.KOREAN -> ", "
        }
        val text = shown.joinToString(separator)
        val suffix = if (modules.size > shown.size) {
            when (language) {
                OutputLanguage.CHINESE -> " 等多个模块"
                OutputLanguage.ENGLISH -> " and more"
                OutputLanguage.JAPANESE -> " など"
                OutputLanguage.KOREAN -> " 외"
            }
        } else {
            ""
        }
        return when (language) {
            OutputLanguage.CHINESE -> "更新 $text$suffix"
            OutputLanguage.ENGLISH -> "Update $text$suffix"
            OutputLanguage.JAPANESE -> "$text$suffix を更新"
            OutputLanguage.KOREAN -> "$text$suffix 업데이트"
        }
    }
}

/** Fills the user-editable prompt template. */
object PromptBuilder {

    val PROMPT_PLACEHOLDERS = listOf(
        "{language}", "{format}", "{skeleton}", "{diff}", "{fileList}", "{types}", "{extraContext}",
    )

    /**
     * Marks the mandatory detail contract below. Templates that already contain it (e.g. a future
     * shipped default) are left untouched, so the rules are appended exactly once.
     */
    const val DETAIL_RULES_MARKER = "[commit-helper:concrete-detail]"

    /**
     * Appended to every template that does not carry the marker yet.
     *
     * The plugin shipped a deliberately terse prompt for a long time, and users may have edited
     * their saved template (some added a "within 20 characters" rule). Diff-level facts such as a
     * field or enum changing value would then be lost, so the quality floor is appended at build
     * time instead of silently rewriting the user's stored template.
     */
    private val DETAIL_RULES = """

$DETAIL_RULES_MARKER
IMPORTANT — write change-level detail, not file-level labels. This section overrides any earlier
per-line length limit (for example "within 20 characters"):
- Every line must say what changed in the code, not merely which file, module, page or feature was touched.
- When the diff shows a field, enum constant, method signature, config key, API route or condition changing,
  quote the identifier exactly and state the before → after value/behaviour when the diff shows it. Example:
  "upgradeMode: AUTO 由通用可选收敛为仅固件 OTA V2；PROMPT 扩展到固件 V1/V2 与配置下发".
- Include at least one concrete identifier or behavioural fact per line; if a line would still be true for
  another change in the same module, it is too vague — rewrite it.
- Merge changes that serve one behaviour into a single line; do not repeat the same file name or heading.
- Keep lines concise (up to roughly 60 characters); ignore the 20-character cap when it would hide the change.
- Emit at most 12 numbered lines in total; with many files, merge by behaviour instead of listing files.
""".trimIndent()

    fun build(
        promptTemplate: String,
        language: String,
        formatTemplate: String,
        skeleton: String?,
        filterResult: FilterResult,
        types: List<CommitType> = emptyList(),
        extraContext: String? = null,
    ): String {
        val typesText = typeListText(types)
        val extra = extraContext?.trim().orEmpty()
        val rendered = promptTemplate
            .replace("{language}", language)
            .replace("{format}", formatTemplate)
            .replace("{skeleton}", skeleton?.takeIf { it.isNotBlank() } ?: "(none)")
            .replace("{fileList}", filterResult.fileListText)
            .replace("{diff}", filterResult.diffText.ifBlank { "(diff content disabled)" })
            .replace("{types}", typesText)
            .replace("{extraContext}", extra.ifBlank { "(none)" })

        val suffix = StringBuilder()
        if (types.isNotEmpty() && !promptTemplate.contains("{types}")) {
            suffix.append("\n\nIMPORTANT — the only allowed commit types are:\n").append(typesText)
        }
        if (extra.isNotBlank() && !promptTemplate.contains("{extraContext}")) {
            suffix.append("\n\nAdditional context supplied by the user (must be honoured):\n").append(extra)
        }
        if (!promptTemplate.contains(DETAIL_RULES_MARKER)) {
            suffix.append("\n\n").append(DETAIL_RULES)
        }
        return if (suffix.isEmpty()) rendered else rendered.trimEnd() + suffix + "\n"
    }

    fun typeListText(types: List<CommitType>): String =
        if (types.isEmpty()) {
            "(none configured)"
        } else {
            types.joinToString("\n") { "- " + it.type + (if (it.description.isBlank()) "" else ": " + it.description) }
        }

    /** For the Format action: true when no known placeholder remains. */
    fun isFullySubstituted(rendered: String): Boolean =
        PROMPT_PLACEHOLDERS.none { rendered.contains(it) }

    /** Used by FormatPromptBuilder so format output keeps the same concreteness floor. */
    internal fun detailRules(): String = DETAIL_RULES
}

/**
 * Builds the LLM prompt for "reformat the existing message". The selected prompt profile is
 * included as style guidance, with diff-only placeholders replaced by a neutral note.
 */
object FormatPromptBuilder {

    fun build(
        language: String,
        formatTemplate: String,
        types: List<CommitType>,
        currentMessage: String,
        guidance: String,
    ): String {
        val typesText = PromptBuilder.typeListText(types)
        val body = DefaultTemplates.FORMAT_PROMPT
            .replace("{types}", typesText)
            .replace("{language}", language)
            .replace("{format}", formatTemplate)
            .replace("{message}", currentMessage.trim())

        val guidanceText = guidance.trim()
            .replace("{language}", language)
            .replace("{format}", formatTemplate)
            .replace("{skeleton}", "(not available in this task)")
            .replace("{diff}", "(not available in this task)")
            .replace("{fileList}", "(not available in this task)")
            .replace("{types}", typesText)
            .replace("{extraContext}", "(none)")
        val guidanceBlock = if (guidanceText.isBlank()) {
            ""
        } else {
            "\n\nTeam writing rules (ignore rules that require data not available in this task):\n" + guidanceText
        }
        val rules = if (guidance.contains(PromptBuilder.DETAIL_RULES_MARKER)) "" else "\n\n" + PromptBuilder.detailRules()
        return body + guidanceBlock + rules + "\n"
    }
}