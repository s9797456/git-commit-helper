package com.caye.commithelper.model

/** Optional user context for one generation run: issue links, free-form notes, Skip CI. */
data class ExtraContext(
    val issues: String = "",
    val note: String = "",
    val skipCi: SkipCiRequest? = null,
) {
    val isEmpty: Boolean
        get() = issues.isBlank() && note.isBlank() && skipCi == null

    /** Rendered as an extra prompt section; skip CI is applied locally, not by the model. */
    fun promptText(): String = buildString {
        if (issues.isNotBlank()) {
            append("Issue / PR links: ").append(issues.trim()).append('\n')
        }
        if (note.isNotBlank()) {
            append("Additional notes:\n").append(note.trim()).append('\n')
        }
    }.trim()
}
