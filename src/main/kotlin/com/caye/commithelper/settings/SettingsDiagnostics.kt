package com.caye.commithelper.settings

import com.intellij.openapi.project.Project

/** Severity of a settings self-check issue. ERRORS are the ones that usually produce broken output. */
enum class DiagnosticSeverity { ERROR, WARNING }

/** Which settings tab the user should open to fix an issue. */
enum class DiagnosticArea { GENERAL, LLM, COMMIT_CONTENT, PROJECT_DEFAULTS }

/**
 * Stable issue codes. The UI localizes them through the bundle, tests assert on the codes.
 */
enum class DiagnosticCode(val severity: DiagnosticSeverity, val area: DiagnosticArea) {
    TEMPLATE_EMPTY(DiagnosticSeverity.ERROR, DiagnosticArea.COMMIT_CONTENT),
    TEMPLATE_POLLUTED(DiagnosticSeverity.ERROR, DiagnosticArea.COMMIT_CONTENT),
    ITEM_LINE_EMPTY(DiagnosticSeverity.ERROR, DiagnosticArea.COMMIT_CONTENT),
    ITEM_LINE_PREFIX(DiagnosticSeverity.WARNING, DiagnosticArea.COMMIT_CONTENT),
    REPO_TEMPLATE_OVERRIDE(DiagnosticSeverity.WARNING, DiagnosticArea.GENERAL),
    PROJECT_PROMPT_MISSING(DiagnosticSeverity.WARNING, DiagnosticArea.PROJECT_DEFAULTS),
    PROJECT_TEMPLATE_MISSING(DiagnosticSeverity.WARNING, DiagnosticArea.PROJECT_DEFAULTS),
    PROMPT_MISSING_CONTEXT(DiagnosticSeverity.WARNING, DiagnosticArea.COMMIT_CONTENT),
}

/** One found issue. [projectName] is set for project-scoped checks so the guide can point at the project. */
data class Diagnostic(
    val code: DiagnosticCode,
    val projectName: String? = null,
)

/**
 * Read-only validation of the effective settings. It never writes anything and never blocks a
 * generation; it only feeds the guide tab and the one-time onboarding notification.
 */
object SettingsDiagnostics {

    /** Checks the effective prompt/template for [project] (global default when null). */
    fun inspect(project: Project? = null): List<Diagnostic> {
        val state = CommitHelperSettings.getInstance().state
        val result = mutableListOf<Diagnostic>()

        val template = EffectiveSettings.template(project)
        val format = template.format
        if (format.isBlank() || !format.contains("{items}")) {
            result += Diagnostic(DiagnosticCode.TEMPLATE_EMPTY)
        }
        if (format.contains("Format template:") || format.contains("Item line:") || format.contains("{text}")) {
            result += Diagnostic(DiagnosticCode.TEMPLATE_POLLUTED)
        }

        val itemLine = template.itemLine
        if (itemLine.isBlank()) {
            result += Diagnostic(DiagnosticCode.ITEM_LINE_EMPTY)
        } else if (!itemLine.contains("{type}") && (
                itemLine.trimStart().startsWith("- ") ||
                    itemLine.trimStart().startsWith("* ") ||
                    itemLine.trimStart().startsWith("\u2022 ")
                )
        ) {
            // "- {text}" turns every category block (which already starts with its heading) into a
            // bullet list item. A conventional "- [{type}] ..." line is fine and must not warn.
            result += Diagnostic(DiagnosticCode.ITEM_LINE_PREFIX)
        }

        if (state.useRepoCommitTemplate) {
            result += Diagnostic(DiagnosticCode.REPO_TEMPLATE_OVERRIDE)
        }

        val prompt = EffectiveSettings.promptProfile(project).prompt
        if (!prompt.contains("{fileList}") || !prompt.contains("{diff}")) {
            result += Diagnostic(DiagnosticCode.PROMPT_MISSING_CONTEXT)
        }

        if (project != null) {
            result += projectIssues(project, state)
        }
        return result.distinct()
    }

    /** Checks the global defaults plus every open project's .idea selection. */
    fun inspectAll(): List<Diagnostic> {
        val state = CommitHelperSettings.getInstance().state
        val result = inspect(null).toMutableList()
        for (project in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            if (project.isDefault) continue
            result += projectIssues(project, state)
        }
        return result.distinct()
    }

    private fun projectIssues(project: Project, state: CommitHelperSettings.State): List<Diagnostic> {
        val projectState = ProjectCommitHelperSettings.getInstance(project).state
        val name = project.name
        val result = mutableListOf<Diagnostic>()
        if (projectState.promptProfileId.isNotBlank() &&
            state.promptProfiles.none { it.id == projectState.promptProfileId }
        ) {
            result += Diagnostic(DiagnosticCode.PROJECT_PROMPT_MISSING, name)
        }
        if (projectState.templateId.isNotBlank() &&
            state.commitTemplates.none { it.id == projectState.templateId }
        ) {
            result += Diagnostic(DiagnosticCode.PROJECT_TEMPLATE_MISSING, name)
        }
        return result
    }

    /** Stable key for "the same set of problems" so a session does not show the same warning twice. */
    fun signature(issues: List<Diagnostic>): String = issues
        .map { it.code.name + "|" + (it.projectName ?: "") }
        .sorted()
        .joinToString(",")
}
