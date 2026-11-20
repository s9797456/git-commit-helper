package com.caye.commithelper.template

/**
 * Shipped defaults. Every one of these is editable in Settings | Tools | Commit Helper.
 */
object DefaultTemplates {

    const val FORMAT: String = "{subject}\n\n{items}\n"

    /**
     * `{scope}` renders as ` (module)` when a scope exists (leading space included) and as an
     * empty string otherwise, which is what makes `- [{type}]{scope} {text}` collapse to
     * `- [feat] text` / expand to `- [feat] (api) text`.
     * `{scopeRaw}` renders the bare scope without parentheses or padding.
     */
    const val ITEM_LINE: String = "- [{type}]{scope} {text}"

    val PROMPT: String = """
You are a commit message writer. Summarize the change set below into a commit message.

Rules:
- Answer with STRICT JSON only: no prose, no Markdown fences, no trailing explanation.
- Schema: {"subject": string, "items": [{"type": string, "scope": string, "text": string}], "issue": string|null}
- "type" must be one of: feat, fix, refactor, docs, test, build, chore, perf, style.
- "subject": at most 50 characters, imperative mood, no trailing period; "type(scope): summary" is allowed.
- "items": one entry per distinct change; merge semantically identical files; never invent anything absent from the diff or file list.
- "text" must be written in {language}.
- If the diff carries a sampling note, do not claim the list is complete.
- "issue": use the provided issue key when present, otherwise null.

Output language: {language}

Layout the message must follow (reference only, do not echo it):
{format}

Repository commit template skeleton (preserve its structure and fill in the items):
{skeleton}

Changed files:
{fileList}

Diff:
{diff}
""".trim()


    /**
     * Built-in free-text profile: developer-facing commit message, grouped by business capability
     * instead of by file/class. It deliberately avoids section headings and labels.
     */
    val DEVELOPER_SUMMARY: String = """
You are a senior developer writing a commit message for the change set below.
Return the final commit message as plain text only: no JSON, no Markdown fences, no commentary.

Output shape:
<conventional subject>

- <feature-level statement>
- <feature-level statement>

Rules:
- First line must be a Conventional Commits subject: type(scope): summary, at most 72 characters,
  no trailing period. Allowed types: {types}.
- Body: 1-8 lines, each starts with "- ". Use 3-8 lines when there are multiple distinct business
  changes; keep one line when the change is genuinely small. One line = one user/business-visible
  capability, not a file, class, DTO or module list. Merge all implementation files that serve the
  same capability into one line.
- For a new or changed capability, say what changed. Only when it has a real external impact
  (API/field/enum/config/data format/compatibility/deployment/performance), explain that impact in
  the same sentence. Do not write impact analysis for pure internal refactors.
- For a bug fix, explain in natural language: what went wrong, under which conditions it happened,
  why it happened, and how it was fixed. If the diff does not show one of these details, omit it;
  never invent.
- Do not use section headings or labels such as "新增功能", "修改功能", "影响点", "Bug 修复",
  "影响：", "触发条件：", "原因：" or "解决：". Write natural developer prose inside each bullet.
- Externally visible contracts (API path, request/response field, enum value, config key, table,
  error code) must keep their exact names. Do not list internal class/DTO/method/annotation names
  unless needed to identify the contract.
- If the diff carries a sampling note, do not claim the list is complete.
- Never include personal email addresses, account names, passwords, API keys or other credentials.
- Write in {language}.

Output language: {language}

Additional context supplied by the user (honour it):
{extraContext}

Changed files:
{fileList}

Diff:
{diff}
""".trim()


    /**
     * Default allowed commit types. Users can edit the list in Settings | Commit Content.
     * Descriptions are shown to the model so it can pick the right type.
     */
    val DEFAULT_TYPES: List<com.caye.commithelper.settings.CommitType> = listOf(
        com.caye.commithelper.settings.CommitType("feat", "new feature"),
        com.caye.commithelper.settings.CommitType("fix", "bug fix"),
        com.caye.commithelper.settings.CommitType("docs", "documentation only"),
        com.caye.commithelper.settings.CommitType("style", "formatting, missing semicolons, no code change"),
        com.caye.commithelper.settings.CommitType("refactor", "code change that neither fixes a bug nor adds a feature"),
        com.caye.commithelper.settings.CommitType("perf", "performance improvement"),
        com.caye.commithelper.settings.CommitType("test", "adding or fixing tests"),
        com.caye.commithelper.settings.CommitType("build", "build system or dependencies"),
        com.caye.commithelper.settings.CommitType("ci", "CI configuration"),
        com.caye.commithelper.settings.CommitType("chore", "other changes that do not modify src or test"),
        com.caye.commithelper.settings.CommitType("revert", "revert a previous commit"),
    )

    /**
     * Prompt for the Format action (LLM-only). The user's selected prompt profile is appended as
     * style guidance by FormatPromptBuilder; this template carries the hard output contract.
     */
    const val FORMAT_PROMPT: String = "" +
        "You reformat an existing commit message. Keep its factual meaning, do not invent changes.\n" +
        "Rules:\n" +
        "- Answer with STRICT JSON only: no prose, no Markdown fences, no trailing explanation.\n" +
        "- Schema: {\"subject\": string, \"items\": [{\"type\": string, \"scope\": string, \"text\": string}], \"issue\": string|null}\n" +
        "- Use only the allowed commit types: {types}.\n" +
        "- Preserve issue links and skip-ci markers found in the original message; put them in \"issue\" or in item text.\n" +
        "- Write the text in {language}.\n" +
        "- Keep the same number of distinct changes as the original message; merge duplicates only.\n" +
        "- The final layout must follow:\n{format}\n" +
        "- Existing message:\n---\n{message}\n---\n"

    /** Built-in one-click presets for the format template. */
    enum class FormatPreset(val displayName: String, val format: String, val itemLine: String) {
        CONVENTIONAL(
            "Conventional Commits",
            "{subject}\n\n{items}\n",
            "- [{type}]{scope} {text}",
        ),
        BULLET_PLAIN(
            "Bullet list (plain)",
            "{subject}\n\n{items}\n",
            "- {text}",
        ),
        CHINESE_ITEMIZED(
            "Chinese itemized",
            "{subject}\n\n{items}\n",
            "- [{type}]{scope} {text}",
        ),
    }
}