package com.caye.commithelper.template

import com.caye.commithelper.settings.CommitType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Format action is LLM-only: the current message is wrapped in a fixed contract, the selected
 * prompt profile is appended as style guidance, and the diff-only placeholders are neutralised.
 */
class FormatPromptTest {

    private val types = listOf(
        CommitType("feat", "new feature"),
        CommitType("fix", "bug fix"),
    )
    private val format = "{subject}\n\n{items}\n"
    private val currentMessage = "feat(api): add pagination\n\n- add page parameter"

    private fun build(guidance: String = "Keep it terse in {language}."): String =
        FormatPromptBuilder.build(
            language = "Chinese",
            formatTemplate = format,
            types = types,
            currentMessage = currentMessage,
            guidance = guidance,
        )

    private fun String.occurrences(needle: String): Int = split(needle).size - 1

    @Test
    fun `the contract contains the current message`() {
        val rendered = build()

        assertTrue(rendered.contains(currentMessage), "the existing message must be quoted verbatim")
        assertTrue(rendered.contains("- Existing message:"))
    }

    @Test
    fun `the contract contains the selected format`() {
        val rendered = build()

        assertTrue(rendered.contains("The final layout must follow:\n$format"))
    }

    @Test
    fun `the contract lists the allowed commit types`() {
        val rendered = build()

        assertTrue(rendered.contains("- feat: new feature"))
        assertTrue(rendered.contains("- fix: bug fix"))
        assertFalse(rendered.contains("{types}"))
    }

    @Test
    fun `the language selection is substituted`() {
        val rendered = build()

        assertTrue(rendered.contains("- Write the text in Chinese."))
        assertFalse(rendered.contains("{language}"))
    }

    @Test
    fun `it asks for strict json and forbids invention`() {
        val rendered = build()

        assertTrue(rendered.contains("STRICT JSON"))
        assertTrue(rendered.contains("do not invent changes"))
        assertTrue(rendered.contains("Preserve issue links and skip-ci markers"))
    }

    @Test
    fun `the guidance is appended as a writing rules block with its tokens replaced`() {
        val guidance = "Keep it terse in {language} and follow {format}"
        val rendered = build(guidance)

        assertTrue(rendered.contains("Team writing rules (ignore rules that require data not available in this task):"))
        assertTrue(rendered.contains("Keep it terse in Chinese and follow $format"))
        assertFalse(rendered.contains("{language}"), "guidance tokens must be substituted")
    }

    @Test
    fun `guidance placeholders for unavailable data become neutral notes`() {
        val rendered = build("Use {diff}, {fileList} and {skeleton}.")

        assertEquals(3, rendered.occurrences("(not available in this task)"))
        assertFalse(rendered.contains("{diff}"))
        assertFalse(rendered.contains("{fileList}"))
        assertFalse(rendered.contains("{skeleton}"))
    }

    @Test
    fun `guidance receives the allowed type list and empty extra context`() {
        val rendered = build("Allowed: {types}. Extra: {extraContext}.")

        assertTrue(rendered.contains("Allowed: - feat: new feature\n- fix: bug fix."))
        assertTrue(rendered.contains("Extra: (none)."))
    }

    @Test
    fun `empty guidance adds no writing rules block`() {
        val rendered = build("")

        assertFalse(rendered.contains("Team writing rules"))
        assertEquals(1, rendered.occurrences(PromptBuilder.DETAIL_RULES_MARKER))
    }

    @Test
    fun `the detail contract is appended exactly once`() {
        val rendered = build()

        assertEquals(1, rendered.occurrences(PromptBuilder.DETAIL_RULES_MARKER))
        assertTrue(rendered.contains("write change-level detail"))
    }

    @Test
    fun `the current message is trimmed before it is embedded`() {
        val rendered = FormatPromptBuilder.build(
            language = "English",
            formatTemplate = format,
            types = types,
            currentMessage = "   fix: something   \n",
            guidance = "",
        )

        assertTrue(rendered.contains("---\nfix: something\n---"))
    }
}
