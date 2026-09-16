package com.caye.commithelper.llm

import com.caye.commithelper.model.ChangeItem
import com.caye.commithelper.model.GeneratedMessage
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses the model answer into a [GeneratedMessage].
 *
 * Contract with the model: strict JSON, no Markdown fences. A lenient second pass strips
 * fences and extracts the outermost JSON object before giving up.
 */
object LlmResponseParser {

    fun parse(raw: String): GeneratedMessage? {
        val candidates = listOf(raw, stripFences(raw), extractObject(raw))
        for (candidate in candidates) {
            if (candidate.isNullOrBlank()) continue
            parseJson(candidate)?.let { return it }
        }
        return null
    }

    private fun parseJson(text: String): GeneratedMessage? = try {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) return null
        val obj = root.asJsonObject
        val subject = obj.get("subject")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        if (subject.isEmpty()) return null

        val items = ArrayList<ChangeItem>()
        val itemsElement = obj.get("items")
        if (itemsElement != null && itemsElement.isJsonArray) {
            for (element in itemsElement.asJsonArray) {
                if (!element.isJsonObject) continue
                val item = element.asJsonObject
                val text = item.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                if (text.isEmpty()) continue
                items.add(
                    ChangeItem(
                        type = item.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.trim()
                            ?.lowercase()?.takeIf { it.isNotEmpty() } ?: "chore",
                        scope = item.get("scope")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty(),
                        text = text,
                    ),
                )
            }
        }

        val issue = obj.get("issue")?.let { element ->
            if (element.isJsonNull || !element.isJsonPrimitive) null
            else element.asString.trim().takeIf { it.isNotEmpty() && it.lowercase() != "null" }
        }

        GeneratedMessage(subject = subject, items = items, issue = issue)
    } catch (_: Exception) {
        null
    }

    /** Removes ```json ... ``` fences. */
    fun stripFences(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val withoutOpen = trimmed.removePrefix("```")
        val body = withoutOpen.substringAfter('\n', withoutOpen)
        val end = body.lastIndexOf("```")
        return (if (end >= 0) body.substring(0, end) else body).trim()
    }

    /** Extracts the outermost `{ ... }` span, ignoring braces inside strings. */
    fun extractObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** Used by tests and by the request builders to keep JSON escaping honest. */
    fun itemsToJson(items: List<ChangeItem>): JsonArray {
        val array = JsonArray()
        for (item in items) {
            val obj = JsonObject()
            obj.addProperty("type", item.type)
            obj.addProperty("scope", item.scope)
            obj.addProperty("text", item.text)
            array.add(obj)
        }
        return array
    }
}
