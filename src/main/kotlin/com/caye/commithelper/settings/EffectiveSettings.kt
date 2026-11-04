package com.caye.commithelper.settings

import com.intellij.openapi.project.Project

/**
 * Resolution order: project selection -> global default -> built-in default.
 * Pure overloads take plain ids so they can be unit tested without a project.
 */
object EffectiveSettings {

    fun resolvePromptProfile(
        profiles: List<PromptProfile>,
        globalId: String,
        projectId: String?,
    ): PromptProfile =
        pick(profiles, projectId) ?: pick(profiles, globalId) ?: pick(profiles, BuiltIns.PROMPT_ID)
            ?: BuiltIns.promptProfile()

    fun resolveTemplate(
        templates: List<CommitTemplate>,
        globalId: String,
        projectId: String?,
    ): CommitTemplate =
        pickTemplate(templates, projectId) ?: pickTemplate(templates, globalId)
            ?: pickTemplate(templates, BuiltIns.TEMPLATE_ID) ?: BuiltIns.template()

    fun promptProfile(project: Project?): PromptProfile {
        val state = CommitHelperSettings.getInstance().state
        val projectId = project?.let { ProjectCommitHelperSettings.getInstance(it).state.promptProfileId }
        return resolvePromptProfile(state.promptProfiles, state.defaultPromptProfileId, projectId)
    }

    fun template(project: Project?): CommitTemplate {
        val state = CommitHelperSettings.getInstance().state
        val projectId = project?.let { ProjectCommitHelperSettings.getInstance(it).state.templateId }
        return resolveTemplate(state.commitTemplates, state.defaultTemplateId, projectId)
    }

    private fun pick(profiles: List<PromptProfile>, id: String?): PromptProfile? =
        id?.takeIf { it.isNotBlank() }?.let { wanted -> profiles.firstOrNull { it.id == wanted } }

    private fun pickTemplate(templates: List<CommitTemplate>, id: String?): CommitTemplate? =
        id?.takeIf { it.isNotBlank() }?.let { wanted -> templates.firstOrNull { it.id == wanted } }
}
