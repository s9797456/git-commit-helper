package com.caye.commithelper.template

/** The language the generated message must be written in. */
enum class OutputLanguage(val promptName: String) {
    CHINESE("Chinese"),
    ENGLISH("English"),
    JAPANESE("Japanese"),
    KOREAN("Korean"),
}

/**
 * Decides the output language. Explicit selections map directly; AUTO looks at recent commit
 * subjects. Kana means Japanese, Hangul means Korean, otherwise a CJK share over the threshold
 * means Chinese, and everything else falls back to English.
 */
object LanguageDetector {

    /** Share of CJK characters above which recent history counts as Chinese. */
    const val CHINESE_RATIO_THRESHOLD = 0.10

    fun detect(recentSubjects: List<String>): OutputLanguage {
        val text = recentSubjects.joinToString(" ")
        if (text.isBlank()) return OutputLanguage.ENGLISH
        var kana = 0
        var hangul = 0
        var cjk = 0
        var letters = 0
        for (ch in text) {
            when {
                isKana(ch) -> { kana++; letters++ }
                isHangul(ch) -> { hangul++; letters++ }
                isCjk(ch) -> { cjk++; letters++ }
                ch.isLetter() -> letters++
            }
        }
        if (letters == 0) return OutputLanguage.ENGLISH
        if (hangul > 0 && hangul.toDouble() / letters >= HANGUL_RATIO_THRESHOLD) return OutputLanguage.KOREAN
        if (kana > 0 && kana.toDouble() / letters >= KANA_RATIO_THRESHOLD) return OutputLanguage.JAPANESE
        return if (cjk.toDouble() / letters >= CHINESE_RATIO_THRESHOLD) OutputLanguage.CHINESE else OutputLanguage.ENGLISH
    }

    fun forOption(option: com.caye.commithelper.settings.LanguageOption, recentSubjects: List<String>): OutputLanguage =
        when (option) {
            com.caye.commithelper.settings.LanguageOption.CHINESE -> OutputLanguage.CHINESE
            com.caye.commithelper.settings.LanguageOption.ENGLISH -> OutputLanguage.ENGLISH
            com.caye.commithelper.settings.LanguageOption.JAPANESE -> OutputLanguage.JAPANESE
            com.caye.commithelper.settings.LanguageOption.KOREAN -> OutputLanguage.KOREAN
            com.caye.commithelper.settings.LanguageOption.AUTO -> detect(recentSubjects)
        }

    fun isChineseHistory(recentSubjects: List<String>): Boolean =
        detect(recentSubjects) == OutputLanguage.CHINESE

    const val KANA_RATIO_THRESHOLD = 0.05
    const val HANGUL_RATIO_THRESHOLD = 0.05

    fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF ||   // CJK unified ideographs
            ch.code in 0x3400..0x4DBF || // extension A
            ch.code in 0xF900..0xFAFF    // compatibility ideographs

    fun isKana(ch: Char): Boolean =
        ch.code in 0x3040..0x309F ||   // hiragana
            ch.code in 0x30A0..0x30FF    // katakana

    fun isHangul(ch: Char): Boolean =
        ch.code in 0xAC00..0xD7AF ||   // syllables
            ch.code in 0x1100..0x11FF    // jamo
}
