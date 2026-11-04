package com.caye.commithelper.model

import com.caye.commithelper.settings.SkipCiPosition

/** A requested Skip CI marker plus where to put it. */
data class SkipCiRequest(
    val marker: String,
    val position: SkipCiPosition = SkipCiPosition.END,
)

/** Pure Skip CI rendering, applied after the message has been rendered from the template. */
object SkipCiApplier {

    fun apply(message: String, request: SkipCiRequest?): String {
        val marker = request?.marker?.trim().orEmpty()
        if (marker.isEmpty()) return message
        val body = message.trimEnd()
        if (body.isEmpty()) return marker + "\n"
        if (body.contains(marker)) return message
        return when (request!!.position) {
            SkipCiPosition.END -> body + "\n\n" + marker + "\n"
            SkipCiPosition.SUBJECT_PREFIX -> marker + " " + body + "\n"
        }
    }
}
