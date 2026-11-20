package com.caye.commithelper.split

import com.caye.commithelper.settings.CommitType
import com.caye.commithelper.template.PromptBuilder

/** Prompt for the LLM-only smart-split grouping pass. */
object SplitPromptBuilder {

    fun build(
        language: String,
        files: List<String>,
        diffText: String,
        types: List<CommitType>,
        guidance: String = "",
        formatTemplate: String = "",
    ): String {
        val typesText = PromptBuilder.typeListText(types)
        val paths = files.joinToString("\n") { "- " + it }
        val guidanceBlock = if (guidance.isBlank()) {
            ""
        } else {
            val renderedGuidance = guidance
                .replace("{language}", language)
                .replace("{format}", formatTemplate)
                .replace("{types}", typesText)
                .replace("{fileList}", paths)
                .replace("{diff}", "(see the Diff section below)")
                .replace("{skeleton}", "(none)")
                .replace("{extraContext}", "(none)")
            "\n\nTeam writing rules from the selected prompt profile (use them for body wording, terminology and style; do not copy a fixed subject from them — the group subject must follow the rules above):\n" +
                renderedGuidance
        }
        return """
You split a developer's checked changes into atomic commits.
Return STRICTLY valid JSON only: no prose, no Markdown fences, no trailing explanation.

Schema:
{"groups":[{"name":"short business name","subject":"type(scope): summary","body":["- concrete change"],"files":["exact/file/path"]}]}

Rules:
- Return 1-6 groups. Return 1 group when the change set is genuinely one cohesive feature.
- Every path listed below must appear in exactly one group; never invent a path and never omit one.
- One group = one business capability, not one file or one module. Merge files that implement the same capability.
- subject must be Conventional Commits, at most 72 characters, no trailing period. Allowed types:
${typesText}
- body must contain 1-5 lines, each starting with "- ".
- For a fix, explain in the body (natural language) what broke, under what conditions, why, and how it was fixed, when the diff supports it; omit details you cannot confirm.
- Do not use section headings or labels such as 新增功能 / 影响点 / 触发条件 / 原因 / 解决.
- Write in ${language}.
- Never include personal email, account, password, API key or token.

Exact checked paths:
${paths}

Diff:
${diffText}
""".trim() + guidanceBlock
    }
}
