package com.caye.commithelper.settings

import com.caye.commithelper.template.DefaultTemplates
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pre-0.2.0 installs stored one prompt/format pair in the application state. 0.2.0 turns them
 * into reusable [PromptProfile] / [CommitTemplate] libraries while keeping every user edit.
 *
 * Migration is pure state handling: no IDE application is needed. It runs from
 * [CommitHelperSettings.loadState] and from the lazy `state` getter, so these tests drive it with
 * hand-built legacy states instead of platform fixtures.
 */
class SettingsMigrationTest {

    private fun load(configure: CommitHelperSettings.State.() -> Unit = {}): CommitHelperSettings.State {
        val settings = CommitHelperSettings()
        settings.loadState(CommitHelperSettings.State().apply(configure))
        return settings.state
    }

    private fun CommitHelperSettings.State.customProfiles() = promptProfiles.filter { !it.builtIn }
    private fun CommitHelperSettings.State.customTemplates() = commitTemplates.filter { !it.builtIn }

    @Test
    fun `a fresh install exposes exactly the protected built-ins`() {
        val state = load()

        assertEquals(1, state.promptProfiles.size, "a new install starts with only the built-in prompt")
        val builtInPrompt = state.promptProfiles.single()
        assertEquals(BuiltIns.PROMPT_ID, builtInPrompt.id)
        assertTrue(builtInPrompt.builtIn)
        assertEquals(DefaultTemplates.PROMPT, builtInPrompt.prompt)
        assertEquals(BuiltIns.PROMPT_ID, state.defaultPromptProfileId)

        assertEquals(1, state.commitTemplates.size, "a new install starts with only the built-in template")
        val builtInTemplate = state.commitTemplates.single()
        assertEquals(BuiltIns.TEMPLATE_ID, builtInTemplate.id)
        assertTrue(builtInTemplate.builtIn)
        assertEquals(DefaultTemplates.FORMAT, builtInTemplate.format)
        assertEquals(DefaultTemplates.ITEM_LINE, builtInTemplate.itemLine)
        assertEquals(BuiltIns.TEMPLATE_ID, state.defaultTemplateId)

        assertTrue(state.migratedTo02, "the fresh state must be marked as migrated to 0.2.0")
        assertTrue(state.customProfiles().isEmpty())
        assertTrue(state.customTemplates().isEmpty())
    }

    @Test
    fun `commit types and skip ci presets are seeded`() {
        val state = load()

        assertTrue(state.commitTypes.isNotEmpty(), "the allowed commit types must be seeded")
        assertTrue(state.commitTypes.any { it.type == "feat" })
        assertTrue(
            state.commitTypes.all { it.type.isNotBlank() && it.description.isNotBlank() },
            "every seeded commit type needs a type and a description: ${state.commitTypes}",
        )

        assertTrue(state.skipCiPresets.contains("[skip ci]"), "the default skip CI presets must be seeded")
        assertTrue(state.skipCiPresets.contains("[ci skip]"))
        assertEquals(2, state.skipCiPresets.size)
    }

    @Test
    fun `a customised legacy prompt becomes a selected custom profile`() {
        val customPrompt = "Write the message in {language}.\nDiff:\n{diff}"
        val state = load { promptTemplate = customPrompt }

        assertEquals(1, state.customProfiles().size, "the customised legacy prompt must be preserved once")
        val profile = state.customProfiles().single()
        assertEquals("My prompt", profile.name)
        assertEquals(customPrompt, profile.prompt)
        assertFalse(profile.builtIn)
        assertNotEquals(BuiltIns.PROMPT_ID, profile.id)
        assertEquals(profile.id, state.defaultPromptProfileId, "the migrated prompt becomes the global default")

        assertTrue(
            state.promptProfiles.any { it.id == BuiltIns.PROMPT_ID && it.builtIn },
            "the built-in prompt must still be available",
        )
    }

    @Test
    fun `an untouched legacy prompt stays only the built-in`() {
        val state = load { promptTemplate = DefaultTemplates.PROMPT }

        assertTrue(state.customProfiles().isEmpty(), "the shipped default must not be duplicated as My prompt")
        assertEquals(1, state.promptProfiles.size)
        assertEquals(BuiltIns.PROMPT_ID, state.defaultPromptProfileId)
    }

    @Test
    fun `a blank legacy prompt does not create a profile`() {
        val state = load { promptTemplate = "   \n\t" }

        assertTrue(state.customProfiles().isEmpty())
        assertEquals(BuiltIns.PROMPT_ID, state.defaultPromptProfileId)
    }

    @Test
    fun `a customised legacy format and item line become a selected template`() {
        val state = load {
            formatTemplate = "{subject}\n\n{items}"
            itemLineTemplate = "* {text}"
        }

        assertEquals(1, state.customTemplates().size)
        val template = state.customTemplates().single()
        assertEquals("My template", template.name)
        assertEquals("{subject}\n\n{items}", template.format)
        assertEquals("* {text}", template.itemLine)
        assertFalse(template.builtIn)
        assertEquals(template.id, state.defaultTemplateId, "the migrated template becomes the global default")
        assertTrue(state.commitTemplates.any { it.id == BuiltIns.TEMPLATE_ID && it.builtIn })
    }

    @Test
    fun `changing only the item line still migrates the template`() {
        val state = load { itemLineTemplate = "- {scopeRaw}: {text}" }

        assertEquals(1, state.customTemplates().size)
        assertEquals("- {scopeRaw}: {text}", state.customTemplates().single().itemLine)
        assertEquals(DefaultTemplates.FORMAT, state.customTemplates().single().format)
    }

    @Test
    fun `an untouched legacy format stays only the built-in`() {
        val state = load {
            formatTemplate = DefaultTemplates.FORMAT
            itemLineTemplate = DefaultTemplates.ITEM_LINE
        }

        assertTrue(state.customTemplates().isEmpty())
        assertEquals(1, state.commitTemplates.size)
        assertEquals(BuiltIns.TEMPLATE_ID, state.defaultTemplateId)
    }

    @Test
    fun `a second ensure does not duplicate migrated entries`() {
        val settings = CommitHelperSettings()
        val legacy = CommitHelperSettings.State().apply {
            promptTemplate = "my legacy prompt"
            formatTemplate = "{subject}\n{items}"
        }

        settings.loadState(legacy)
        settings.loadState(legacy)
        val after = settings.state
        assertEquals(2, after.promptProfiles.size, "one built-in plus one migrated profile")
        assertEquals(2, after.commitTemplates.size, "one built-in plus one migrated template")
        assertEquals(1, after.customProfiles().size)
        assertEquals(1, after.customTemplates().size)
        assertTrue(after.migratedTo02)

        // A separate instance loading the same already-migrated state must not migrate again either.
        val second = CommitHelperSettings()
        second.loadState(legacy)
        assertEquals(2, second.state.promptProfiles.size)
        assertEquals(2, second.state.commitTemplates.size)
    }

    @Test
    fun `built-ins are repaired after a manual xml edit`() {
        val state = load {
            migratedTo02 = true
            promptProfiles.clear()
            commitTemplates.clear()
            commitTypes.clear()
            skipCiPresets.clear()
            defaultPromptProfileId = "ghost-profile"
            defaultTemplateId = "ghost-template"
        }

        assertTrue(state.promptProfiles.any { it.id == BuiltIns.PROMPT_ID && it.builtIn })
        assertTrue(state.commitTemplates.any { it.id == BuiltIns.TEMPLATE_ID && it.builtIn })
        assertTrue(state.commitTypes.any { it.type == "feat" })
        assertTrue(state.skipCiPresets.contains("[skip ci]"))
        assertEquals(BuiltIns.PROMPT_ID, state.defaultPromptProfileId, "a dangling default must be repaired")
        assertEquals(BuiltIns.TEMPLATE_ID, state.defaultTemplateId, "a dangling default must be repaired")
    }

    @Test
    fun `built-ins are inserted before existing custom entries`() {
        val custom = PromptProfile("custom-a", "Custom A", "prompt a")
        val state = load {
            migratedTo02 = true
            promptProfiles.add(custom)
        }

        assertEquals(BuiltIns.PROMPT_ID, state.promptProfiles.first().id, "the built-in stays first in the list")
        assertEquals("custom-a", state.promptProfiles[1].id)
    }

    @Test
    fun `an already migrated custom selection is preserved`() {
        val customProfile = PromptProfile("custom-a", "Custom A", "prompt a")
        val customTemplate = CommitTemplate("custom-t", "Custom T", "{subject}\n{items}", "* {text}")
        val state = load {
            migratedTo02 = true
            promptProfiles.add(customProfile)
            commitTemplates.add(customTemplate)
            defaultPromptProfileId = customProfile.id
            defaultTemplateId = customTemplate.id
        }

        assertEquals("prompt a", state.promptProfiles.first { it.id == "custom-a" }.prompt)
        assertEquals("{subject}\n{items}", state.commitTemplates.first { it.id == "custom-t" }.format)
        assertEquals("custom-a", state.defaultPromptProfileId)
        assertEquals("custom-t", state.defaultTemplateId)
        assertEquals(2, state.promptProfiles.size)
        assertEquals(2, state.commitTemplates.size)
    }

    @Test
    fun `an interrupted migration does not overwrite an existing custom profile`() {
        val existing = PromptProfile("existing", "Existing", "kept prompt")
        val state = load {
            promptTemplate = "legacy prompt that must not win"
            promptProfiles.add(existing)
            defaultPromptProfileId = existing.id
        }

        assertEquals(1, state.customProfiles().size, "the existing custom profile must be the only one")
        assertEquals("kept prompt", state.promptProfiles.first { it.id == "existing" }.prompt)
        assertEquals("existing", state.defaultPromptProfileId)
        assertTrue(state.migratedTo02, "the interrupted flag must be cleared after the repair")
    }
}
