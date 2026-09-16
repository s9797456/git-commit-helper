package com.caye.commithelper.template

import com.caye.commithelper.llm.LlmResponseParser
import com.caye.commithelper.model.ChangeItem
import com.caye.commithelper.model.FilterResult
import com.caye.commithelper.model.GeneratedMessage
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.model.ChangeKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageRendererTest {

    private val message = GeneratedMessage(
        subject = "feat(api): add pagination",
        items = listOf(
            ChangeItem("feat", "api", "add page parameter to the list endpoint"),
            ChangeItem("test", "", "cover the boundary of page size"),
        ),
        issue = "PROJ-1",
    )

    @Test
    fun `renders the default format with subject and itemized body`() {
        val text = MessageRenderer.render(message)
        assertTrue(text.startsWith("feat(api): add pagination"))
        assertTrue(text.contains("- [feat] (api) add page parameter to the list endpoint"))
        assertTrue(text.contains("- [test] cover the boundary of page size"))
    }

    @Test
    fun `scope placeholder collapses when empty`() {
        val withScope = MessageRenderer.renderItem(ChangeItem("feat", "core", "x"), "- [{type}]{scope} {text}")
        val withoutScope = MessageRenderer.renderItem(ChangeItem("chore", "", "y"), "- [{type}]{scope} {text}")
        assertEquals("- [feat] (core) x", withScope)
        assertEquals("- [chore] y", withoutScope)
    }

    @Test
    fun `scopeRaw renders the bare scope`() {
        assertEquals("[core] x", MessageRenderer.renderItem(ChangeItem("feat", "core", "x"), "[{scopeRaw}] {text}"))
    }

    @Test
    fun `custom format template is honoured`() {
        val text = MessageRenderer.render(message, formatTemplate = "{subject}\n\n{issue}\n{items}\n")
        assertTrue(text.contains("PROJ-1"))
    }

    @Test
    fun `skeleton keeps comment lines and receives the items under a heading`() {
        val skeleton = """
            # Please enter the commit message for your changes.
            # Lines starting with '#' will be ignored.
            # Changes:
        """.trimIndent()

        val text = MessageRenderer.render(message, skeleton = skeleton)

        assertTrue(text.contains("# Please enter the commit message for your changes."))
        assertTrue(text.contains("# Lines starting with '#' will be ignored."))
        assertTrue(text.contains("- [feat] (api) add page parameter to the list endpoint"))
        assertTrue(text.indexOf("# Changes:") < text.indexOf("- [feat] (api)"), "items go below the heading")
        assertTrue(text.startsWith("feat(api): add pagination"), "subject is prepended when the skeleton has no token")
    }

    @Test
    fun `skeleton tokens are replaced in place`() {
        val skeleton = "{subject}\n\n# What:\n{items}\n"
        val text = MessageRenderer.render(message, skeleton = skeleton)
        assertTrue(text.startsWith("feat(api): add pagination"))
        assertFalse(text.contains("{subject}"))
        assertFalse(text.contains("{items}"))
        assertTrue(text.contains("- [test] cover the boundary of page size"))
    }
}

class PromptBuilderTest {

    private val filterResult = FilterResult(
        included = listOf(FileChange("src/App.kt", ChangeKind.MODIFIED, 3, 1, "+a\n-b\n")),
        omittedFiles = emptyList(),
        diffText = "--- src/App.kt\n+a\n-b\n",
        fileListText = "modified src/App.kt (+3/-1)",
        sampled = false,
        totalFilesSeen = 1,
    )

    @Test
    fun `substitutes every documented placeholder`() {
        val rendered = PromptBuilder.build(
            promptTemplate = DefaultTemplates.PROMPT,
            language = "Chinese",
            formatTemplate = DefaultTemplates.FORMAT,
            skeleton = "SKELETON",
            filterResult = filterResult,
        )
        assertTrue(PromptBuilder.isFullySubstituted(rendered))
        assertTrue(rendered.contains("Chinese"))
        assertTrue(rendered.contains("SKELETON"))
        assertTrue(rendered.contains("modified src/App.kt (+3/-1)"))
        assertTrue(rendered.contains("+a"))
    }

    @Test
    fun `missing skeleton and disabled diff get explicit placeholders`() {
        val rendered = PromptBuilder.build(
            promptTemplate = DefaultTemplates.PROMPT,
            language = "English",
            formatTemplate = DefaultTemplates.FORMAT,
            skeleton = null,
            filterResult = filterResult.copy(diffText = ""),
        )
        assertTrue(rendered.contains("(none)"))
        assertTrue(rendered.contains("(diff content disabled)"))
    }
}

class LlmResponseParserTest {

    @Test
    fun `parses strict json`() {
        val parsed = LlmResponseParser.parse(
            """{"subject":"feat: x","items":[{"type":"feat","scope":"api","text":"do x"}],"issue":null}""",
        )
        assertNotNull(parsed)
        assertEquals("feat: x", parsed!!.subject)
        assertEquals(1, parsed.items.size)
        assertEquals("api", parsed.items.first().scope)
        assertNull(parsed.issue)
    }

    @Test
    fun `parses fenced json`() {
        val parsed = LlmResponseParser.parse("```json\n{\"subject\":\"fix: y\",\"items\":[]}\n```")
        assertNotNull(parsed)
        assertEquals("fix: y", parsed!!.subject)
    }

    @Test
    fun `parses json embedded in prose`() {
        val parsed = LlmResponseParser.parse("Sure! Here it is:\n{\"subject\":\"chore: z\",\"items\":[]}\nHope that helps.")
        assertNotNull(parsed)
        assertEquals("chore: z", parsed!!.subject)
    }

    @Test
    fun `rejects content without a subject`() {
        assertNull(LlmResponseParser.parse("not json at all"))
        assertNull(LlmResponseParser.parse("""{"items":[]}"""))
    }

    @Test
    fun `extractObject ignores braces inside strings`() {
        val extracted = LlmResponseParser.extractObject("""prefix {"subject":"a } b","items":[]} suffix""")
        assertEquals("""{"subject":"a } b","items":[]}""", extracted)
    }

    @Test
    fun `lowercase issue string is treated as absent`() {
        val parsed = LlmResponseParser.parse("""{"subject":"a","items":[],"issue":"null"}""")
        assertNull(parsed!!.issue)
    }
}

class LanguageDetectorTest {

    @Test
    fun `detects chinese history`() {
        assertTrue(LanguageDetector.isChineseHistory(listOf("修复登录问题", "更新文档")))
        assertFalse(LanguageDetector.isChineseHistory(listOf("fix login", "update docs")))
        assertFalse(LanguageDetector.isChineseHistory(emptyList()))
    }
}
