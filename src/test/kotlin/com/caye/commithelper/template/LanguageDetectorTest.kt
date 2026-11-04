package com.caye.commithelper.template

import com.caye.commithelper.settings.LanguageOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * AUTO language detection from recent commit subjects, plus the explicit
 * [LanguageOption] -> [OutputLanguage] mapping used by the generation pipeline.
 */
class LanguageDetectorTest {

    @Test
    fun `kana in the history means japanese`() {
        assertEquals(OutputLanguage.JAPANESE, LanguageDetector.detect(listOf("機能追加の修正")))
        assertEquals(OutputLanguage.JAPANESE, LanguageDetector.detect(listOf("バグを修正", "テストを追加")))
    }

    @Test
    fun `hangul in the history means korean`() {
        assertEquals(OutputLanguage.KOREAN, LanguageDetector.detect(listOf("버그 수정")))
        assertEquals(OutputLanguage.KOREAN, LanguageDetector.detect(listOf("로그인 오류 수정", "문서 갱신")))
    }

    @Test
    fun `cjk-only history means chinese`() {
        assertEquals(OutputLanguage.CHINESE, LanguageDetector.detect(listOf("修复登录问题", "更新文档")))
        assertEquals(OutputLanguage.CHINESE, LanguageDetector.detect(listOf("新增导出功能")))
    }

    @Test
    fun `latin history means english`() {
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(listOf("fix login bug", "update docs")))
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(listOf("feat: add pagination")))
    }

    @Test
    fun `empty or symbol-only history is english`() {
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(emptyList()))
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(listOf("", "   ")))
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(listOf("123 !!! ---")))
    }

    @Test
    fun `a single kana below the ratio threshold does not make it japanese`() {
        val mostlyLatin = "a".repeat(40) + "の"
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(listOf(mostlyLatin)))
    }

    @Test
    fun `a single hangul below the ratio threshold does not make it korean`() {
        val mostlyLatin = "a".repeat(40) + "한"
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(listOf(mostlyLatin)))
    }

    @Test
    fun `a small cjk share below the chinese threshold is english`() {
        val mostlyLatin = "a".repeat(30) + "修复"
        // 2/32 is below the 10% Chinese threshold.
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.detect(listOf(mostlyLatin)))
    }

    @Test
    fun `the explicit language option overrides detection`() {
        val englishHistory = listOf("fix login bug")
        val chineseHistory = listOf("修复登录问题")
        val japaneseHistory = listOf("バグを修正")
        val koreanHistory = listOf("버그 수정")

        assertEquals(OutputLanguage.CHINESE, LanguageDetector.forOption(LanguageOption.CHINESE, englishHistory))
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.forOption(LanguageOption.ENGLISH, chineseHistory))
        assertEquals(OutputLanguage.JAPANESE, LanguageDetector.forOption(LanguageOption.JAPANESE, englishHistory))
        assertEquals(OutputLanguage.KOREAN, LanguageDetector.forOption(LanguageOption.KOREAN, englishHistory))
        assertEquals(OutputLanguage.CHINESE, LanguageDetector.forOption(LanguageOption.CHINESE, emptyList()))
    }

    @Test
    fun `AUTO follows the detected repository language`() {
        assertEquals(OutputLanguage.CHINESE, LanguageDetector.forOption(LanguageOption.AUTO, listOf("修复登录问题")))
        assertEquals(OutputLanguage.ENGLISH, LanguageDetector.forOption(LanguageOption.AUTO, listOf("fix login bug")))
        assertEquals(OutputLanguage.JAPANESE, LanguageDetector.forOption(LanguageOption.AUTO, listOf("バグを修正")))
        assertEquals(OutputLanguage.KOREAN, LanguageDetector.forOption(LanguageOption.AUTO, listOf("버그 수정")))
    }

    @Test
    fun `isChineseHistory follows the chinese detection`() {
        assertTrue(LanguageDetector.isChineseHistory(listOf("修复登录问题", "更新文档")))
        assertFalse(LanguageDetector.isChineseHistory(listOf("fix login", "update docs")))
        assertFalse(LanguageDetector.isChineseHistory(emptyList()))
    }

    @Test
    fun `character classifiers match their unicode ranges`() {
        assertTrue(LanguageDetector.isCjk('中'))
        assertTrue(LanguageDetector.isKana('の'))
        assertTrue(LanguageDetector.isKana('カ'))
        assertTrue(LanguageDetector.isHangul('한'))
        assertFalse(LanguageDetector.isCjk('a'))
        assertFalse(LanguageDetector.isKana('a'))
        assertFalse(LanguageDetector.isHangul('a'))
    }
}
