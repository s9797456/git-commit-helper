package com.caye.commithelper.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Resolution order for the reusable library: project selection > global default > built-in.
 * The id-based overloads are pure functions, so no project or application is instantiated here.
 */
class EffectiveSettingsTest {

    private val builtInPrompt = BuiltIns.promptProfile()
    private val globalPrompt = PromptProfile("global-prompt", "Global", "global prompt text")
    private val projectPrompt = PromptProfile("project-prompt", "Project", "project prompt text")
    private val promptProfiles = listOf(builtInPrompt, globalPrompt, projectPrompt)

    private val builtInTemplate = BuiltIns.template()
    private val globalTemplate = CommitTemplate("global-template", "Global", "{subject}\n{items}\n", "- {text}")
    private val projectTemplate = CommitTemplate("project-template", "Project", "{subject}\n", "* {text}")
    private val templates = listOf(builtInTemplate, globalTemplate, projectTemplate)

    @Test
    fun `project selection wins over the global default`() {
        val resolved = EffectiveSettings.resolvePromptProfile(promptProfiles, "global-prompt", "project-prompt")
        assertEquals(projectPrompt, resolved)
        assertEquals("project-prompt", resolved.id)
    }

    @Test
    fun `project selection wins for templates too`() {
        val resolved = EffectiveSettings.resolveTemplate(templates, "global-template", "project-template")
        assertEquals(projectTemplate, resolved)
        assertEquals("project-template", resolved.id)
    }

    @Test
    fun `empty project selection inherits the global default`() {
        for (projectId in listOf(null, "", "   ")) {
            val resolved = EffectiveSettings.resolvePromptProfile(promptProfiles, "global-prompt", projectId)
            assertEquals(globalPrompt, resolved, "project id '$projectId' must fall back to the global default")
        }
        for (projectId in listOf(null, "", "   ")) {
            val resolved = EffectiveSettings.resolveTemplate(templates, "global-template", projectId)
            assertEquals(globalTemplate, resolved)
        }
    }

    @Test
    fun `unknown project id inherits the global default`() {
        assertEquals(
            globalPrompt,
            EffectiveSettings.resolvePromptProfile(promptProfiles, "global-prompt", "deleted-project-profile"),
        )
        assertEquals(
            globalTemplate,
            EffectiveSettings.resolveTemplate(templates, "global-template", "deleted-project-template"),
        )
    }

    @Test
    fun `unknown selections fall back to the built-in entry`() {
        val prompt = EffectiveSettings.resolvePromptProfile(promptProfiles, "missing-global", "missing-project")
        assertEquals(BuiltIns.PROMPT_ID, prompt.id)
        assertTrue(prompt.builtIn)

        val template = EffectiveSettings.resolveTemplate(templates, "missing-global", "missing-project")
        assertEquals(BuiltIns.TEMPLATE_ID, template.id)
        assertTrue(template.builtIn)
    }

    @Test
    fun `empty global selection falls back to the built-in entry`() {
        val prompt = EffectiveSettings.resolvePromptProfile(promptProfiles, "", null)
        assertEquals(BuiltIns.PROMPT_ID, prompt.id)
        val template = EffectiveSettings.resolveTemplate(templates, "", null)
        assertEquals(BuiltIns.TEMPLATE_ID, template.id)
    }

    @Test
    fun `a synthesized built-in is returned when the library does not contain one`() {
        val prompt = EffectiveSettings.resolvePromptProfile(listOf(globalPrompt), "missing", "missing")
        assertEquals(BuiltIns.PROMPT_ID, prompt.id)
        assertTrue(prompt.builtIn)

        val template = EffectiveSettings.resolveTemplate(listOf(globalTemplate), "missing", "missing")
        assertEquals(BuiltIns.TEMPLATE_ID, template.id)
        assertTrue(template.builtIn)
    }

    @Test
    fun `an empty library resolves to the shipped built-in content`() {
        val prompt = EffectiveSettings.resolvePromptProfile(emptyList(), "anything", "anything")
        assertEquals(BuiltIns.promptProfile(), prompt)
        val template = EffectiveSettings.resolveTemplate(emptyList(), "anything", "anything")
        assertEquals(BuiltIns.template(), template)
    }

    @Test
    fun `a profile with a blank id is never selected`() {
        val blankId = PromptProfile("", "Blank", "blank prompt")
        val prompt = EffectiveSettings.resolvePromptProfile(listOf(builtInPrompt, blankId), "", null)
        assertEquals(BuiltIns.PROMPT_ID, prompt.id)
    }
}
