package com.caye.commithelper.group

import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.model.FileChange
import com.caye.commithelper.template.LocalFallbackBuilder
import com.caye.commithelper.template.OutputLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The LLM-free fallback must read naturally in every shipped generation language; 0.2.0 added
 * Japanese and Korean on top of Chinese and English.
 */
class LocalLanguageTest {

    private val files = listOf(
        FileChange("src/main/kotlin/com/caye/service/GitOps.kt", ChangeKind.MODIFIED, 12, 3),
        FileChange("src/main/kotlin/com/caye/service/CommitHelperService.kt", ChangeKind.MODIFIED, 5, 1),
        FileChange("README.md", ChangeKind.MODIFIED, 2, 0),
        FileChange("src/test/kotlin/com/caye/service/AppTest.kt", ChangeKind.ADDED, 30, 0),
    )

    @Test
    fun `local grouping is deterministic and sorted`() {
        val names = LocalGrouper.group(files).map { it.name }
        assertEquals(listOf("(root)", "com/caye"), names)
    }

    @Test
    fun `japanese fallback has a real subject and wording`() {
        val message = LocalFallbackBuilder.build(files, OutputLanguage.JAPANESE)
        val itemText = message.items.joinToString("\n") { it.text }

        assertTrue(message.subject.isNotBlank())
        assertTrue(message.subject.contains("を更新"), "Japanese subject must use the Japanese verb: ${message.subject}")
        assertTrue(message.subject.contains("(root)"))
        assertTrue(message.subject.contains("com/caye"))
        assertTrue(message.subject.contains("、"), "Japanese lists use the ideographic comma")

        assertTrue(message.items.isNotEmpty())
        assertTrue(message.items.all { it.text.isNotBlank() })
        assertTrue(itemText.contains("ファイル変更"), "Japanese item wording: $itemText")
        assertTrue(itemText.contains("："), "Japanese item wording uses the full-width colon: $itemText")
    }

    @Test
    fun `korean fallback has a real subject and wording`() {
        val message = LocalFallbackBuilder.build(files, OutputLanguage.KOREAN)
        val itemText = message.items.joinToString("\n") { it.text }

        assertTrue(message.subject.isNotBlank())
        assertTrue(message.subject.contains("업데이트"), "Korean subject must use the Korean verb: ${message.subject}")
        assertTrue(message.subject.contains("com/caye"))

        assertTrue(message.items.isNotEmpty())
        assertTrue(message.items.all { it.text.isNotBlank() })
        assertTrue(itemText.contains("파일"), "Korean item wording: $itemText")
        assertTrue(itemText.contains("개 변경"), "Korean item wording: $itemText")
    }

    @Test
    fun `chinese and english fallbacks keep their existing wording`() {
        val chinese = LocalFallbackBuilder.build(files, OutputLanguage.CHINESE)
        val english = LocalFallbackBuilder.build(files, OutputLanguage.ENGLISH)

        assertTrue(chinese.subject.startsWith("更新 "))
        assertTrue(chinese.items.joinToString("\n") { it.text }.contains("个文件变更"))

        assertTrue(english.subject.startsWith("Update "))
        assertTrue(english.items.joinToString("\n") { it.text }.contains("file(s) changed"))
    }

    @Test
    fun `the boolean overload keeps the old behaviour`() {
        assertEquals(
            LocalFallbackBuilder.build(files, OutputLanguage.CHINESE),
            LocalFallbackBuilder.build(files, chinese = true),
        )
        assertEquals(
            LocalFallbackBuilder.build(files, OutputLanguage.ENGLISH),
            LocalFallbackBuilder.build(files, chinese = false),
        )
    }

    @Test
    fun `every generation language produces a usable message`() {
        for (language in OutputLanguage.entries) {
            val message = LocalFallbackBuilder.build(files, language)
            assertTrue(message.subject.isNotBlank(), "blank subject for $language")
            assertTrue(message.items.isNotEmpty(), "no items for $language")
            assertTrue(
                message.items.all { it.type.isNotBlank() && it.scope.isNotBlank() && it.text.isNotBlank() },
                "a fallback item is incomplete for $language: ${message.items}",
            )
        }
    }

    @Test
    fun `japanese and korean wording is actually translated`() {
        val english = LocalFallbackBuilder.build(files, OutputLanguage.ENGLISH)
        val japanese = LocalFallbackBuilder.build(files, OutputLanguage.JAPANESE)
        val korean = LocalFallbackBuilder.build(files, OutputLanguage.KOREAN)

        assertNotEquals(english.subject, japanese.subject)
        assertNotEquals(english.subject, korean.subject)
        assertNotEquals(
            english.items.map { it.text },
            japanese.items.map { it.text },
        )
        assertNotEquals(
            english.items.map { it.text },
            korean.items.map { it.text },
        )
    }

    @Test
    fun `LocalGrouper itemize matches the fallback items`() {
        val groups = LocalGrouper.group(files)
        val itemized = LocalGrouper.itemize(groups, OutputLanguage.KOREAN)
        val fallback = LocalFallbackBuilder.build(files, OutputLanguage.KOREAN)

        assertEquals(fallback.items, itemized)
        assertTrue(itemized.all { it.text.isNotBlank() })
    }

    @Test
    fun `the fallback still never reports an empty scope`() {
        val message = LocalFallbackBuilder.build(files, OutputLanguage.JAPANESE)
        assertFalse(message.items.any { it.scope.isBlank() })
    }
}
