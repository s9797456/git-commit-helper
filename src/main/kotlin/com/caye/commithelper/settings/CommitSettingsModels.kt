package com.caye.commithelper.settings

import com.caye.commithelper.template.DefaultTemplates
import java.util.UUID

/** A reusable, user-editable prompt. The built-in profile cannot be deleted. */
data class PromptProfile(
    var id: String = "",
    var name: String = "",
    var prompt: String = "",
    var builtIn: Boolean = false,
)

/** A reusable commit message layout (format + item line). The built-in template is read-only. */
data class CommitTemplate(
    var id: String = "",
    var name: String = "",
    var format: String = "",
    var itemLine: String = "",
    var builtIn: Boolean = false,
)

/** One allowed commit type and its human description; fed to the model. */
data class CommitType(
    var type: String = "",
    var description: String = "",
)

/** Where a Skip CI marker is placed in the final message. */
enum class SkipCiPosition { END, SUBJECT_PREFIX }

fun newSettingsId(): String = UUID.randomUUID().toString()

/** Stable identifiers and factories for the protected built-in entries. */
object BuiltIns {

    const val PROMPT_ID = "builtin-default"
    const val TEMPLATE_ID = "builtin-default"

    fun promptProfile(): PromptProfile =
        PromptProfile(id = PROMPT_ID, name = "Default", prompt = DefaultTemplates.PROMPT, builtIn = true)

    fun template(): CommitTemplate =
        CommitTemplate(
            id = TEMPLATE_ID,
            name = "Default",
            format = DefaultTemplates.FORMAT,
            itemLine = DefaultTemplates.ITEM_LINE,
            builtIn = true,
        )

    fun commitTypes(): MutableList<CommitType> =
        DefaultTemplates.DEFAULT_TYPES.map { it.copy() }.toMutableList()

    fun skipCiPresets(): MutableList<String> = mutableListOf("[skip ci]", "[ci skip]")
}
