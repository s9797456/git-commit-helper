package com.caye.commithelper.issue

/**
 * Extracts an issue key such as `PROJ-123` from the branch name, then from recent commit
 * subjects. String-only: never calls Jira / GitHub, never authenticates.
 */
object IssueKeyExtractor {

    /** Preferred: an already-uppercase key such as `PROJ-123`. */
    private val KEY_REGEX = Regex("(?<![A-Za-z0-9])[A-Z][A-Z0-9]{1,9}-\\d+(?![0-9])")

    /** Second pass: branch names are often lower case (`feature/proj-7-x`). */
    private val LOOSE_KEY_REGEX = Regex("(?<![A-Za-z0-9])[A-Za-z][A-Za-z0-9]{1,9}-\\d+(?![0-9])")

    /** Technical tokens that look like issue keys but never are. */
    private val BLOCKLIST = setOf(
        "utf", "iso", "sha", "md", "rfc", "http", "https", "tls", "ssl", "ipv",
        "base64", "ascii", "unicode", "node", "jdk", "java", "vue", "es", "sha1", "sha256",
    )

    fun extract(branchName: String?, commitSubjects: List<String>): String? {
        if (branchName != null) {
            firstMatch(branchName)?.let { return it }
        }
        for (subject in commitSubjects) {
            firstMatch(subject)?.let { return it }
        }
        return null
    }

    private fun firstMatch(text: String): String? {
        KEY_REGEX.find(text)?.let { return it.value.uppercase() }
        for (match in LOOSE_KEY_REGEX.findAll(text)) {
            val prefix = match.value.substringBefore('-').lowercase()
            if (prefix in BLOCKLIST) continue
            return match.value.uppercase()
        }
        return null
    }
}
