package com.caye.commithelper.split

import com.caye.commithelper.model.FileChange
import com.caye.commithelper.template.LocalFallbackBuilder
import com.caye.commithelper.template.OutputLanguage
import com.caye.commithelper.i18n.CommitHelperBundle
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/** Parses and validates the model answer for the smart-split plan. */
object SplitPlanParser {

    private val CONVENTIONAL = Regex("^([a-z][a-z0-9-]*)(\\([^)]+\\))?!?:\\s+\\S.*")
    private val MAX_GROUPS = 6

    fun parse(raw: String): SplitPlan? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = text.removePrefix("\uFEFF")
        text = text.replace(Regex("^[\\x60]{3}(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\s*[\\x60]{3}$", RegexOption.IGNORE_CASE), "")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = try {
            JsonParser.parseString(text.substring(start, end + 1))
        } catch (_: Exception) {
            return null
        }
        if (!root.isJsonObject) return null
        val groupsElement = root.asJsonObject.get("groups") as? JsonArray ?: return null
        val groups = ArrayList<SplitGroup>()
        for (element in groupsElement) {
            if (!element.isJsonObject) continue
            val obj = element.asJsonObject
            val subject = obj.get("subject")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            val bodyElement = obj.get("body")
            val body = when {
                bodyElement == null || bodyElement.isJsonNull -> emptyList()
                bodyElement.isJsonArray -> bodyElement.asJsonArray.mapNotNull {
                    if (it.isJsonPrimitive) it.asString.trim() else null
                }.filter { it.isNotEmpty() }
                bodyElement.isJsonPrimitive -> bodyElement.asString.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                else -> emptyList()
            }
            val filesElement = obj.get("files")
            if (filesElement == null || !filesElement.isJsonArray) continue
            val files = filesElement.asJsonArray.mapNotNull {
                if (it.isJsonPrimitive) normalizePath(it.asString) else null
            }.filter { it.isNotEmpty() }.distinct()
            if (subject.isEmpty() || files.isEmpty()) continue
            groups.add(SplitGroup(name = name.ifEmpty { subject }, subject = subject, body = body, files = files))
        }
        return SplitPlan(groups)
    }

    /** Returns contract violations; empty means the plan partitions exactly the checked files. */
    fun validate(plan: SplitPlan, expectedPaths: Collection<String>): List<String> {
        val errors = mutableListOf<String>()
        if (plan.groups.size !in 1..MAX_GROUPS) {
            errors.add("groups must contain 1-$MAX_GROUPS entries")
            return errors
        }
        val expected = expectedPaths.map { normalizePath(it) }.toSet()
        val seen = HashSet<String>()
        for (group in plan.groups) {
            if (group.files.isEmpty()) errors.add("group '" + group.name + "' has no files")
            if (!CONVENTIONAL.matches(group.subject)) {
                errors.add("group '" + group.name + "' subject is not Conventional Commits")
            }
            if (group.subject.length > 72) errors.add("group '" + group.name + "' subject exceeds 72 characters")
            if (group.body.size > 8) errors.add("group '" + group.name + "' has more than 8 body lines")
            for (file in group.files) {
                val normalized = normalizePath(file)
                if (normalized !in expected) errors.add("unknown file: " + normalized)
                if (!seen.add(normalized)) errors.add("file appears in more than one group: " + normalized)
            }
        }
        val missing = expected - seen
        if (missing.isNotEmpty()) errors.add("missing files: " + missing.joinToString(", "))
        return errors
    }

    fun normalizePath(path: String): String = path.trim().replace('\\', '/').removePrefix("./")

    /**
     * Deterministic fallback used when the model answer is invalid or unavailable. It groups by
     * top-level directory and still produces conventional subjects and feature-level bullets.
     */
    fun fallback(files: List<FileChange>, language: OutputLanguage): SplitPlan {
        if (files.isEmpty()) {
            return SplitPlan(emptyList(), usedFallback = true, errorMessage = CommitHelperBundle.message("error.noChangesToOrganize"))
        }
        val byModule = LinkedHashMap<String, MutableList<FileChange>>()
        for (file in files) {
            val module = file.path.substringBefore('/').ifBlank { "." }
            byModule.getOrPut(module) { mutableListOf() }.add(file)
        }
        var groups: List<Pair<String, List<FileChange>>> = byModule.map { it.key to it.value }
        if (groups.size > MAX_GROUPS) {
            val head = groups.take(MAX_GROUPS - 1)
            val tail = groups.drop(MAX_GROUPS - 1).flatMap { it.second }
            groups = head + ("other" to tail)
        }
        val result = groups.map { (module, groupFiles) ->
            val local = LocalFallbackBuilder.build(groupFiles, language)
            val subject = if (CONVENTIONAL.matches(local.subject)) {
                local.subject
            } else {
                if (language == OutputLanguage.ENGLISH) "chore: update " + module else "chore: 更新 " + module
            }
            val body = local.items.map { item -> "- " + item.text.replace('\n', ' ') }.ifEmpty {
                listOf("- update " + groupFiles.size + " file(s) in " + module)
            }
            SplitGroup(
                name = module,
                subject = subject,
                body = body,
                files = groupFiles.map { it.path },
            )
        }
        return SplitPlan(result, usedFallback = true)
    }
}
