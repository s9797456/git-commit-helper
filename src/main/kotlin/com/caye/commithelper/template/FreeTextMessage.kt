package com.caye.commithelper.template

import com.caye.commithelper.model.FileChange

/**
 * The free-text output mode used by the Developer Summary profile.
 *
 * The model writes the final commit message directly: first line is the subject, the rest is
 * feature-level bullets. The commit template is intentionally bypassed. This object only
 * normalizes and validates that contract; it never pretends to understand the semantics.
 */
object FreeTextMessage {

    /** Appended for the one compact retry when the first answer does not satisfy the contract. */
    const val RETRY_INSTRUCTION: String = """
Rewrite the answer as plain text only: no JSON, no Markdown fences, no commentary.
First line: a Conventional Commits subject in the form type(scope): summary, at most 72 characters.
Then one blank line, then 1-8 lines, each starting with "- ".
Each line must describe one business capability, not a file/class/module.
Do not use section headings or labels such as 新增功能 / 影响点 / 触发条件 / 原因 / 解决.
For a fix, explain symptom, trigger condition, cause and solution in natural language when the diff
supports them; omit details you cannot confirm. Never include personal email, account, password or key.
"""

    private val CONVENTIONAL = Regex("^([a-z][a-z0-9-]*)(\\([^)]+\\))?!?:\\s+\\S.*")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val CREDENTIAL = Regex(
        "(?i)(密码|password|passwd|pwd|账号|account|api[ _-]?key|token|secret)\\s*[:=：]\\s*\\S+",
    )
    private val FORBIDDEN_LABEL = Regex(
        "(?m)^\\s*(影响|触发条件|原因|解决|新增功能|修改功能|优化项|修复项|Bug\\s*修复)\\s*[:：]",
    )
    private val FORBIDDEN_HEADING = Regex(
        "(?m)^\\s*(新增功能|修改功能|影响点|Bug\\s*修复|优化项|修复项|新增项)\\s*$",
    )

    /** Removes outer Markdown fences, normalizes bullet markers and ensures one blank line. */
    fun normalize(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("\u0060\u0060\u0060")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) text = text.substring(firstNewline + 1)
            val endFence = text.lastIndexOf("\u0060\u0060\u0060")
            if (endFence >= 0) text = text.substring(0, endFence)
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val lines = trimmed.lines().toMutableList()
        lines[0] = lines[0].trim().removePrefix("# ").trim()
        for (index in 1 until lines.size) {
            val line = lines[index]
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val body = line.substring(indent.length)
            if (body.startsWith("* ") || body.startsWith("• ")) {
                lines[index] = indent + "- " + body.substring(2)
            }
        }
        val body = lines.drop(1).dropWhile { it.isBlank() }
        return if (body.isEmpty()) lines[0] else lines[0] + "\n\n" + body.joinToString("\n")
    }

    /** Returns human-readable contract violations; empty means valid. */
    fun validate(text: String, allowedTypes: List<String>): List<String> {
        val errors = mutableListOf<String>()
        if (text.isBlank()) return listOf("answer is empty")
        val lines = text.lines()
        val subject = lines.firstOrNull()?.trim().orEmpty()
        if (subject.isEmpty()) {
            errors += "subject is empty"
        } else {
            if (subject.length > 72) errors += "subject is longer than 72 characters"
            if (subject.endsWith(".")) errors += "subject must not end with a period"
            val match = CONVENTIONAL.matchEntire(subject)
            if (match == null) {
                errors += "subject must use type(scope): summary"
            } else {
                val type = match.groupValues[1]
                if (allowedTypes.isNotEmpty() && type !in allowedTypes) {
                    errors += "type '\$type' is not in the configured type list"
                }
            }
        }

        val body = lines.drop(1).filter { it.isNotBlank() }
        val bullets = body.filter { it.trimStart().startsWith("- ") }
        if (bullets.isEmpty()) {
            errors += "body has no '- ' feature bullets"
        } else if (bullets.size > 8) {
            errors += "body has more than 8 bullets"
        } else {
            for (bullet in bullets) {
                if (bullet.removePrefix("- ").trim().isEmpty()) errors += "a bullet is empty"
            }
        }
        if (body.any { it.trimStart().startsWith("* ") || it.trimStart().startsWith("• ") }) {
            errors += "bullets must start with '- '"
        }

        if (FORBIDDEN_LABEL.containsMatchIn(text) || FORBIDDEN_HEADING.containsMatchIn(text)) {
            errors += "do not use section headings or labels"
        }
        if (EMAIL.containsMatchIn(text)) errors += "answer contains a personal email"
        if (CREDENTIAL.containsMatchIn(text)) errors += "answer contains credential-like text"
        return errors
    }

    /**
     * Local fallback in the same free-text shape. The local grouping is still file-derived, but
     * the result is explicitly marked as a fallback and never pretends to be a model answer.
     */
    fun local(files: List<FileChange>, language: OutputLanguage): String {
        val fallback = LocalFallbackBuilder.build(files, language)
        val subject = fallback.subject.trim().let {
            if (CONVENTIONAL.matches(it)) it else "chore: " + it
        }
        val bullets = fallback.items.map { item ->
            val scope = if (item.scope.isBlank()) "" else item.scope + ": "
            "- " + scope + item.text.replace('\n', ' ').trim()
        }.filter { it.length > 2 }
        val safeBullets = if (bullets.isEmpty()) listOf("- update the checked changes") else bullets
        return subject + "\n\n" + safeBullets.joinToString("\n") + "\n"
    }

}
