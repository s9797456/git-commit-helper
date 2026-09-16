package com.caye.commithelper.template

import com.caye.commithelper.model.GeneratedMessage
import com.caye.commithelper.model.FilterResult
import com.caye.commithelper.model.ChangeItem
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.group.LocalGrouper

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

    fun build(files: List<FileChange>, chinese: Boolean): GeneratedMessage {
        val groups = LocalGrouper.group(files)
        val modules = groups.map { it.name }
        val subject = buildSubject(modules, chinese)
        return GeneratedMessage(
            subject = subject,
            items = LocalGrouper.itemize(groups, chinese),
            issue = null,
        )
    }

    fun buildSubject(modules: List<String>, chinese: Boolean): String {
        val shown = modules.take(3)
        val text = if (chinese) shown.joinToString("、") else shown.joinToString(", ")
        val suffix = if (modules.size > shown.size) {
            if (chinese) " 等多个模块" else " and more"
        } else {
            ""
        }
        return if (chinese) "更新 $text$suffix" else "Update $text$suffix"
    }
}

/** Fills the user-editable prompt template. */
object PromptBuilder {

    val PROMPT_PLACEHOLDERS = listOf("{language}", "{format}", "{skeleton}", "{diff}", "{fileList}")

    fun build(
        promptTemplate: String,
        language: String,
        formatTemplate: String,
        skeleton: String?,
        filterResult: FilterResult,
    ): String = promptTemplate
        .replace("{language}", language)
        .replace("{format}", formatTemplate)
        .replace("{skeleton}", skeleton?.takeIf { it.isNotBlank() } ?: "(none)")
        .replace("{fileList}", filterResult.fileListText)
        .replace("{diff}", filterResult.diffText.ifBlank { "(diff content disabled)" })

    /** True when none of the known prompt placeholders survived substitution. */
    fun isFullySubstituted(rendered: String): Boolean =
        PROMPT_PLACEHOLDERS.none { rendered.contains(it) }
}
