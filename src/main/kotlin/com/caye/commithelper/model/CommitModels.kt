package com.caye.commithelper.model

/** How a file changed relative to `HEAD`. */
enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, UNKNOWN }

/**
 * A single file participating in the commit, plus (optionally) its diff text.
 *
 * [diffText] is empty when diff content was not collected (see `Include diff content` setting).
 */
data class FileChange(
    val path: String,
    val kind: ChangeKind,
    val added: Int = 0,
    val removed: Int = 0,
    val diffText: String = "",
    val binary: Boolean = false,
) {
    val changedLines: Int get() = added + removed
}

/** Files grouped by module (top-level area of the repository). */
data class ChangeGroup(val name: String, val files: List<FileChange>)

/** One itemized line of the commit body. */
data class ChangeItem(val type: String, val scope: String, val text: String)

/** The structured result the generator works with. */
data class GeneratedMessage(
    val subject: String,
    val items: List<ChangeItem>,
    val issue: String? = null,
)

/** Result of filtering/truncating/sampling the raw change set. */
data class FilterResult(
    val included: List<FileChange>,
    val omittedFiles: List<String>,
    val diffText: String,
    val fileListText: String,
    val sampled: Boolean,
    val totalFilesSeen: Int,
)
