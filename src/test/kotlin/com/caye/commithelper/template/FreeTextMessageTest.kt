package com.caye.commithelper.template

import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.model.FilterResult
import com.caye.commithelper.settings.BuiltIns
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.PromptOutputMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreeTextMessageTest {

    private val valid = """
        feat(remote): 支持远程控制超时重试

        - 远程控制增加超时重试，设备离线时会释放会话并返回明确状态。
        - 远程控制回调时间字段改为 ISO8601 字符串，调用方需要按新格式解析。
    """.trimIndent()

    @Test
    fun `valid developer message passes the contract`() {
        assertTrue(FreeTextMessage.validate(valid, listOf("feat", "fix", "refactor")).isEmpty())
    }

    @Test
    fun `normalize strips Markdown fences and normalizes bullet markers`() {
        val raw = "\u0060\u0060\u0060text\nfeat(api): add cache\n\n* first behaviour\n* second behaviour\n\u0060\u0060\u0060"
        val normalized = FreeTextMessage.normalize(raw)
        assertEquals("feat(api): add cache\n\n- first behaviour\n- second behaviour", normalized)
    }

    @Test
    fun `non conventional subject is rejected`() {
        val errors = FreeTextMessage.validate("update code\n\n- one change", listOf("feat"))
        assertTrue(errors.any { it.contains("type(scope)") })
    }

    @Test
    fun `missing bullets and too many bullets are rejected`() {
        assertTrue(FreeTextMessage.validate("feat: change\n\nplain prose", emptyList()).isNotEmpty())
        val tooMany = "feat: change\n\n" + (1..9).joinToString("\n") { "- change " + it }
        assertTrue(FreeTextMessage.validate(tooMany, emptyList()).any { it.contains("more than 8") })
    }

    @Test
    fun `fixed headings and labels are rejected`() {
        val labelled = "feat: change\n\n影响：调用方需要升级。\n- actual change"
        val errors = FreeTextMessage.validate(labelled, emptyList())
        assertTrue(errors.any { it.contains("headings or labels") })
    }

    @Test
    fun `personal email and credential-like text are rejected`() {
        val email = "feat: change\n\n- contact user@example.com for details"
        assertTrue(FreeTextMessage.validate(email, emptyList()).any { it.contains("email") })
        val password = "feat: change\n\n- password: hunter2"
        assertTrue(FreeTextMessage.validate(password, emptyList()).any { it.contains("credential") })
    }

    @Test
    fun `long subject and unknown type are rejected`() {
        val longSubject = "feat: " + "x".repeat(80)
        val errors = FreeTextMessage.validate(longSubject + "\n\n- a change", listOf("feat"))
        assertTrue(errors.any { it.contains("72") })
        assertTrue(
            FreeTextMessage.validate("perf: cache lookup\n\n- a change", listOf("feat"))
                .any { it.contains("not in the configured") },
        )
    }

    @Test
    fun `local fallback is valid free text`() {
        val text = FreeTextMessage.local(
            listOf(FileChange("src/remote/Session.kt", ChangeKind.MODIFIED, 3, 1)),
            OutputLanguage.CHINESE,
        )
        assertTrue(FreeTextMessage.validate(text, listOf("chore", "feat", "refactor")).isEmpty())
        assertTrue(text.contains("- "))
    }

    @Test
    fun `developer summary profile is a built-in free text profile`() {
        val profile = BuiltIns.developerSummaryProfile()
        assertEquals(PromptOutputMode.FREE_TEXT, profile.outputMode)
        assertTrue(profile.builtIn)
        assertTrue(profile.prompt.contains("Conventional Commits"))
        assertTrue(profile.prompt.contains("Never include personal email"))
    }

    @Test
    fun `new installations select the developer summary profile`() {
        assertEquals(BuiltIns.DEVELOPER_SUMMARY_ID, CommitHelperSettings.State().defaultPromptProfileId)
    }

    @Test
    fun `free text prompt building does not append the JSON detail contract`() {
        val filterResult = FilterResult(
            included = listOf(FileChange("src/App.kt", ChangeKind.MODIFIED, 3, 1, "+a")),
            omittedFiles = emptyList(),
            diffText = "--- src/App.kt\n+a",
            fileListText = "modified src/App.kt (+3/-1)",
            sampled = false,
            totalFilesSeen = 1,
        )
        val prompt = PromptBuilder.build(
            promptTemplate = DefaultTemplates.DEVELOPER_SUMMARY,
            language = "Chinese",
            formatTemplate = DefaultTemplates.FORMAT,
            skeleton = null,
            filterResult = filterResult,
            types = emptyList(),
            freeText = true,
        )
        assertFalse(prompt.contains(PromptBuilder.DETAIL_RULES_MARKER))
        assertTrue(prompt.contains("Changed files:"))
        assertTrue(prompt.contains("Diff:"))
    }
}
