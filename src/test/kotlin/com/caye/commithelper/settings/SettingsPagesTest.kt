package com.caye.commithelper.settings

import com.caye.commithelper.settings.ui.CommitContentConfigurable
import com.caye.commithelper.settings.ui.GeneralConfigurable
import com.caye.commithelper.settings.ui.GuideConfigurable
import com.caye.commithelper.settings.ui.LlmConfigurable
import com.caye.commithelper.settings.ui.ProjectDefaultsConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JScrollPane
import javax.swing.JTabbedPane

/**
 * SettingsUiTest only drives the composite shell. These tests construct every child page so a
 * broken splitter, table model, combo renderer or bundle key fails headlessly instead of only
 * when a user opens Settings.
 */
class SettingsPagesTest : BasePlatformTestCase() {

    fun testGeneralPageBuildsAndRoundTrips() = exercise(GeneralConfigurable())

    fun testLlmPageBuildsAndRoundTrips() = exercise(LlmConfigurable())

    fun testCommitContentPageBuildsAndRoundTrips() = exercise(CommitContentConfigurable())

    fun testProjectDefaultsPageBuildsAndRoundTrips() = exercise(ProjectDefaultsConfigurable())

    fun testGuidePageBuildsAndRoundTrips() = exercise(GuideConfigurable())

    fun testPagesDoNotUseHorizontalScrollbars() {
        for (page in listOf(
            GeneralConfigurable(),
            LlmConfigurable(),
            CommitContentConfigurable(),
            ProjectDefaultsConfigurable(),
            GuideConfigurable(),
        )) {
            try {
                val component = page.createComponent()
                assertNotNull(page.displayName + " must return a component", component)
                val scroll = findScrollPane(component!!)
                assertNotNull(page.displayName + " must be wrapped in a scroll pane", scroll)
                assertEquals(
                    page.displayName + " must not grow a horizontal scrollbar",
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                    scroll!!.horizontalScrollBarPolicy,
                )
            } finally {
                page.disposeUIResources()
            }
        }
    }

    /**
     * Regression for the 0.2.0 bug: the platform wraps EP-based configurables in a plain
     * ConfigurableWrapper, so Configurable.Composite children are never expanded by the settings
     * tree. The root component itself must therefore render the three pages as tabs.
     */
    fun testRootComponentRendersTheFivePagesAsTabs() {
        val root = CommitHelperConfigurable()
        try {
            val tabs = findTabbedPane(root.createComponent())
            assertNotNull("settings component must contain a tabbed pane", tabs)
            assertEquals("all five pages must be attached as tabs", 5, tabs!!.tabCount)
            assertTrue("tab titles must not be blank", (0 until tabs.tabCount).all { tabs.getTitleAt(it).isNotBlank() })
        } finally {
            root.disposeUIResources()
        }
    }

    fun testGuideRequestSelectsTheGuideTab() {
        CommitHelperConfigurable.requestGuideTab()
        val root = CommitHelperConfigurable()
        try {
            val tabs = findTabbedPane(root.createComponent())
            assertNotNull(tabs)
            assertEquals("guide request must select the last tab", 4, tabs!!.selectedIndex)
        } finally {
            root.disposeUIResources()
        }
    }

    fun testCompositeExposesTheFiveSettingsPages() {
        val root = CommitHelperConfigurable()
        try {
            val pages = root.configurables
            assertEquals(5, pages.size)
            // Display names follow the IDE locale; assert classes instead of localized text.
            val classes = pages.map { it.javaClass.simpleName }.toSet()
            assertEquals(
                "expected General/LLM/CommitContent/ProjectDefaults/Guide pages, found: $classes",
                setOf(
                    "GeneralConfigurable",
                    "LlmConfigurable",
                    "CommitContentConfigurable",
                    "ProjectDefaultsConfigurable",
                    "GuideConfigurable",
                ),
                classes,
            )
        } finally {
            root.disposeUIResources()
        }
    }

    private fun findTabbedPane(component: Component): JTabbedPane? {
        if (component is JTabbedPane) return component
        if (component is Container) {
            for (child in component.components) {
                findTabbedPane(child)?.let { return it }
            }
        }
        return null
    }

    private fun findScrollPane(component: Component): JScrollPane? {
        if (component is JScrollPane) return component
        if (component is Container) {
            for (child in component.components) {
                findScrollPane(child)?.let { return it }
            }
        }
        return null
    }

    private fun exercise(page: Configurable) {
        try {
            assertTrue("displayName must not be blank", page.displayName.isNotBlank())
            val component = page.createComponent()
            assertNotNull("createComponent must return a component", component)
            page.reset()
            assertFalse(page.displayName + " must not be modified right after reset", page.isModified())
            page.apply()
            assertFalse(page.displayName + " must stay clean after an unchanged apply", page.isModified())
        } finally {
            page.disposeUIResources()
        }
    }
}