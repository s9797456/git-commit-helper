package com.caye.commithelper.template

import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.ExtraContext
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.model.FilterResult
import com.caye.commithelper.settings.CommitType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.2.0 additions to the prompt builder: {types} and {extraContext} placeholders, plus the
 * detail contract that is appended at most once.
 */
class PromptBuilderV2Test {

    private val filterResult = FilterResult(
        included = listOf(FileChange("src/App.kt", ChangeKind.MODIFIED, 3, 1, "+a\n-b\n")),
        omittedFiles = emptyList(),
        diffText = "--- src/App.kt\n+a\n-b\n",
        fileListText = "modified src/App.kt (+3/-1)",
        sampled = false,
        totalFilesSeen = 1,
    )

    private val types = listOf(
        CommitType("feat", "new feature"),
        CommitType("fix", "bug fix"),
    )

    private fun build(template: String, types: List<CommitType> = emptyList(), extra: String? = null): String =
        PromptBuilder.build(
            promptTemplate = template,
            language = "Chinese",
            formatTemplate = DefaultTemplates.FORMAT,
            skeleton = null,
            filterResult = filterResult,
            types = types,
            extraContext = extra,
        )

    private fun String.occurrences(needle: String): Int = split(needle).size - 1

    @Test
    fun `the types placeholder is substituted in place`() {
        val rendered = build("Allowed types:\n{types}\nDiff:\n{diff}", types)

        assertTrue(rendered.contains("Allowed types:\n- feat: new feature\n- fix: bug fix"))
        assertFalse(rendered.contains("{types}"))
        assertFalse(
            rendered.contains("the only allowed commit types are:"),
            "the type block must not be appended twice when the placeholder exists",
        )
    }

    @Test
    fun `types are appended when the placeholder is absent`() {
        val rendered = build("Summarize this change:\n{diff}", types)

        assertTrue(
            rendered.contains("IMPORTANT — the only allowed commit types are:\n- feat: new feature\n- fix: bug fix"),
        )
        assertEquals(1, rendered.occurrences("the only allowed commit types are:"))
        assertFalse(rendered.contains("{types}"))
    }

    @Test
    fun `an empty type list renders the explicit placeholder and adds no block`() {
        val rendered = build("Allowed types:\n{types}\nDiff:\n{diff}", emptyList())

        assertTrue(rendered.contains("(none configured)"))
        assertFalse(rendered.contains("the only allowed commit types are:"))
    }

    @Test
    fun `a type without a description renders as a bare bullet`() {
        val rendered = build("Allowed types:\n{types}", listOf(CommitType("chore", "")))

        assertTrue(rendered.contains("- chore"))
        assertFalse(rendered.contains("- chore:"))
    }

    @Test
    fun `the extra context placeholder is substituted in place`() {
        val rendered = build("User context:\n{extraContext}\nDiff:\n{diff}", emptyList(), "fixes #12")

        assertTrue(rendered.contains("User context:\nfixes #12"))
        assertFalse(rendered.contains("Additional context supplied by the user"))
        assertFalse(rendered.contains("{extraContext}"))
    }

    @Test
    fun `extra context is appended when the placeholder is absent`() {
        val rendered = build("Summarize:\n{diff}", emptyList(), "fixes #12, keep the old flag")

        assertTrue(
            rendered.contains(
                "Additional context supplied by the user (must be honoured):\nfixes #12, keep the old flag",
            ),
        )
        assertEquals(1, rendered.occurrences("Additional context supplied by the user"))
    }

    @Test
    fun `extra context rendered by ExtraContext is appended verbatim`() {
        val extra = ExtraContext(issues = "PROJ-123", note = "keep the legacy behaviour").promptText()
        val rendered = build("Summarize:\n{diff}", emptyList(), extra)

        assertTrue(rendered.contains("Issue / PR links: PROJ-123"))
        assertTrue(rendered.contains("Additional notes:\nkeep the legacy behaviour"))
    }

    @Test
    fun `a blank extra context renders the explicit placeholder`() {
        val rendered = build("User context: {extraContext}\nDiff:\n{diff}", emptyList(), "   ")

        assertTrue(rendered.contains("User context: (none)"))
        assertFalse(rendered.contains("Additional context supplied by the user"))
    }

    @Test
    fun `a null extra context renders the explicit placeholder`() {
        val rendered = build("User context: {extraContext}\nDiff:\n{diff}", emptyList(), null)

        assertTrue(rendered.contains("User context: (none)"))
    }

    @Test
    fun `the detail rules are appended exactly once`() {
        val rendered = build("Summarize:\n{diff}")

        assertEquals(1, rendered.occurrences(PromptBuilder.DETAIL_RULES_MARKER))
        assertTrue(rendered.contains("write change-level detail"))
        assertTrue(rendered.contains("before → after"))
    }

    @Test
    fun `a template that already carries the marker is not patched twice`() {
        val template = "Summarize:\n{diff}\n" + PromptBuilder.DETAIL_RULES_MARKER
        val rendered = build(template)

        assertEquals(1, rendered.occurrences(PromptBuilder.DETAIL_RULES_MARKER))
        assertFalse(
            rendered.contains("IMPORTANT — write change-level detail, not file-level labels."),
            "the detail body must not be appended when the marker is already present",
        )
    }

    @Test
    fun `types, extra context and detail rules coexist once each`() {
        val rendered = build("Summarize:\n{diff}", types, "fixes #12")

        assertEquals(1, rendered.occurrences("the only allowed commit types are:"))
        assertEquals(1, rendered.occurrences("Additional context supplied by the user"))
        assertEquals(1, rendered.occurrences(PromptBuilder.DETAIL_RULES_MARKER))
    }

    @Test
    fun `the built prompt has no known placeholder left`() {
        val template = "L {language} M {format} S {skeleton} D {diff} F {fileList} T {types} C {extraContext}"
        val rendered = build(template, types, "fixes #12")

        assertTrue(PromptBuilder.isFullySubstituted(rendered))
    }

    @Test
    fun `the shipped default prompt still receives the type and context blocks`() {
        val rendered = build(DefaultTemplates.PROMPT, types, "fixes #12")

        assertTrue(PromptBuilder.isFullySubstituted(rendered))
        assertTrue(rendered.contains("the only allowed commit types are:"))
        assertTrue(rendered.contains("Additional context supplied by the user"))
        assertEquals(1, rendered.occurrences(PromptBuilder.DETAIL_RULES_MARKER))
    }
}
