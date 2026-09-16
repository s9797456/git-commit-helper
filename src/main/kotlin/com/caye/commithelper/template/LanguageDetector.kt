package com.caye.commithelper.template

/** Decides the output language for `Auto (follow repository)`. Pure logic, unit-testable. */
object LanguageDetector {

    /** Share of CJK characters above which recent history counts as Chinese. */
    const val CHINESE_RATIO_THRESHOLD = 0.10

    fun isChineseHistory(recentSubjects: List<String>): Boolean {
        val text = recentSubjects.joinToString(" ")
        if (text.isBlank()) return false
        var cjk = 0
        var letters = 0
        for (ch in text) {
            if (isCjk(ch)) {
                cjk++
                letters++
            } else if (ch.isLetter()) {
                letters++
            }
        }
        if (letters == 0) return false
        return cjk.toDouble() / letters >= CHINESE_RATIO_THRESHOLD
    }

    fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF ||   // CJK unified ideographs
            ch.code in 0x3400..0x4DBF || // extension A
            ch.code in 0xF900..0xFAFF    // compatibility ideographs
}
