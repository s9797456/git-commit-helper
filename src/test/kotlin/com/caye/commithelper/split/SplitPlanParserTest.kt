package com.caye.commithelper.split

import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.settings.CommitType
import com.caye.commithelper.template.OutputLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitPlanParserTest {

    private val paths = listOf("src/a/A.kt", "src/a/B.kt", "src/b/C.kt")

    private val validJson = """
        {
          "groups": [
            {
              "name": "feature a",
              "subject": "feat(a): add parser",
              "body": ["- add parser and validation"],
              "files": ["src/a/A.kt", "src/a/B.kt"]
            },
            {
              "name": "bugfix b",
              "subject": "fix(b): guard null input",
              "body": ["- avoid null crash when input is empty"],
              "files": ["src/b/C.kt"]
            }
          ]
        }
    """.trimIndent()

    private fun plan(raw: String): SplitPlan? = SplitPlanParser.parse(raw)

    @Test
    fun `parses a valid plan and validates exact file partition`() {
        val plan = plan(validJson)
        assertNotNull(plan)
        assertEquals(2, plan!!.groups.size)
        assertTrue(SplitPlanParser.validate(plan, paths).isEmpty())
    }

    @Test
    fun `rejects missing and unknown paths`() {
        val missing = plan(validJson)!!.copy(
            groups = plan(validJson)!!.groups.map { group ->
                if (group.name == "bugfix b") group.copy(files = listOf("src/b/other.kt")) else group
            },
        )
        val errors = SplitPlanParser.validate(missing, paths)
        assertTrue(errors.any { it.contains("unknown file") })
        assertTrue(errors.any { it.contains("missing files") })
    }

    @Test
    fun `rejects duplicate file assignments`() {
        val duplicate = plan(validJson)!!.copy(
            groups = plan(validJson)!!.groups.map { group ->
                if (group.name == "bugfix b") group.copy(files = listOf("src/a/A.kt")) else group
            },
        )
        val errors = SplitPlanParser.validate(duplicate, paths)
        assertTrue(errors.any { it.contains("more than one group") })
    }

    @Test
    fun `fallback partitions all files by top-level module`() {
        val files = listOf(
            FileChange("a/A.kt", ChangeKind.MODIFIED, 1, 1),
            FileChange("b/B.kt", ChangeKind.ADDED, 2, 0),
        )
        val plan = SplitPlanParser.fallback(files, OutputLanguage.ENGLISH)
        assertTrue(plan.usedFallback)
        assertTrue(SplitPlanParser.validate(plan, files.map { it.path }).isEmpty())
        assertEquals(2, plan.groups.size)
    }

    @Test
    fun `message has subject and body separated by a blank line`() {
        val group = SplitGroup("g", "feat: add x", listOf("- one", "- two"), listOf("a"))
        assertEquals("feat: add x\n\n- one\n- two", group.message())
    }

    @Test
    fun `prompt contains exact paths and diff`() {
        val prompt = SplitPromptBuilder.build(
            language = "Chinese",
            files = paths,
            diffText = "DIFF-BODY",
            types = listOf(CommitType("feat", "feature")),
            guidance = "Use business language from {language} and keep terms: {types}",
            formatTemplate = "{subject}\n\n{items}",
        )
        for (path in paths) assertTrue(prompt.contains(path))
        assertTrue(prompt.contains("DIFF-BODY"))
        assertTrue(prompt.contains("exactly one group"))
        assertTrue(prompt.contains("Team writing rules"))
        assertTrue(prompt.contains("Use business language from Chinese"))
        assertTrue(!prompt.contains("{language}"))
    }

    @Test
    fun `fenced JSON is accepted`() {
        val fenced = "\u0060\u0060\u0060json\n" + validJson + "\n\u0060\u0060\u0060"
        val plan = plan(fenced)
        assertNotNull(plan)
        assertEquals(2, plan!!.groups.size)
    }

    @Test
    fun `invalid JSON returns null or fails validation`() {
        assertEquals(null, plan("not json"))
        val empty = plan("{\"groups\": []}")
        assertNotNull(empty)
        assertTrue(SplitPlanParser.validate(empty!!, paths).isNotEmpty())
    }
}
