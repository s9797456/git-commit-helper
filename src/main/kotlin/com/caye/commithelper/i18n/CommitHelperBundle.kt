package com.caye.commithelper.i18n

import com.intellij.AbstractBundle

/**
 * All user-facing UI text of the plugin lives in messages/CommitHelperBundle*.properties and is
 * resolved against the IDE locale (EN base, ZH/JA/KO translations). Generated commit message
 * content and the prompt guidance sent to the model are deliberately not localized.
 */
object CommitHelperBundle : AbstractBundle("messages.CommitHelperBundle") {

    /** Kotlin-friendly accessor: [key] plus optional {0}-style arguments. */
    fun message(key: String, vararg params: Any): String = getMessage(key, *params)
}
