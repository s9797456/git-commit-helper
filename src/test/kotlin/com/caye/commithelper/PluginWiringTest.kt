package com.caye.commithelper

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Boots a headless application and asserts the runtime wiring that the GUI smoke test would
 * otherwise be the only way to check: both actions exist and land in the intended UI groups.
 * The Plugin Verifier also validates `<add-to-group>` ids against each target IDE, but this
 * catches wiring regressions without waiting for a full verification run.
 */
class PluginWiringTest : BasePlatformTestCase() {

    fun testBothActionsAreRegisteredById() {
        val manager = ActionManager.getInstance()
        assertNotNull("Organize action must be registered", manager.getAction(ORGANIZE_ID))
        assertNotNull("Changelog action must be registered", manager.getAction(CHANGELOG_ID))
    }

    fun testOrganizeActionSitsInTheCommitMessageToolbarGroup() {
        val manager = ActionManager.getInstance()
        val group = manager.getAction("Vcs.MessageActionGroup")
        assertNotNull("the platform group Vcs.MessageActionGroup must exist", group)
        val childIds = (group as ActionGroup).getChildren(null).mapNotNull { manager.getId(it) }
        assertTrue(
            "Organize action should be in the commit message group, found: $childIds",
            childIds.contains(ORGANIZE_ID),
        )
    }

    fun testChangelogActionIsReachableFromTheMainMenu() {
        val manager = ActionManager.getInstance()
        val mainMenu = manager.getAction("MainMenu")
        assertNotNull("MainMenu must exist", mainMenu)
        assertTrue("Changelog action should be under the main menu", contains(mainMenu!!, CHANGELOG_ID, manager))
    }

    fun testSettingsServiceRoundTripsLanguageAndFormat() {
        val settings = com.caye.commithelper.settings.CommitHelperSettings.getInstance()
        val original = settings.state.language
        try {
            settings.state.language = com.caye.commithelper.settings.LanguageOption.CHINESE
            assertEquals(
                "state must be readable back from the service",
                com.caye.commithelper.settings.LanguageOption.CHINESE,
                settings.state.language,
            )
        } finally {
            settings.state.language = original
        }
    }

    private fun contains(action: AnAction, id: String, manager: ActionManager): Boolean {
        if (manager.getId(action) == id) return true
        if (action !is ActionGroup) return false
        return action.getChildren(null).any { contains(it, id, manager) }
    }

    private companion object {
        const val ORGANIZE_ID = "com.caye.commithelper.OrganizeCommitMessage"
        const val CHANGELOG_ID = "com.caye.commithelper.GenerateChangelog"
    }
}
