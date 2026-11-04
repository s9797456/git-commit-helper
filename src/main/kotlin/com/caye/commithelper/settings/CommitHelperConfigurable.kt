package com.caye.commithelper.settings

import com.caye.commithelper.settings.ui.CommitContentConfigurable
import com.caye.commithelper.settings.ui.GeneralConfigurable
import com.caye.commithelper.settings.ui.GuideConfigurable
import com.caye.commithelper.settings.ui.LlmConfigurable
import com.caye.commithelper.settings.ui.ProjectDefaultsConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings | Tools | Commit Helper (application scope).
 *
 * The five pages are rendered as tabs inside this one settings entry. This is deliberate: the
 * platform wraps EP-based configurables in `ConfigurableWrapper`, which does NOT expose
 * [Configurable.Composite] children to the settings tree (only a raw programmatic configurable
 * would be expanded). The composite interface is still implemented so tools/tests can enumerate
 * the pages, but [createComponent] is what the settings dialog actually shows.
 *
 * The fourth tab edits the project-level defaults through a project picker instead of a separate
 * projectConfigurable entry; the fifth tab is the in-IDE onboarding guide.
 */
class CommitHelperConfigurable : Configurable, Configurable.Composite {

    private val guide = GuideConfigurable()

    private val pages: List<Configurable> = listOf(
        GeneralConfigurable(),
        LlmConfigurable(),
        CommitContentConfigurable(),
        ProjectDefaultsConfigurable(),
        guide,
    )

    private var root: JComponent? = null
    private var tabs: JBTabbedPane? = null
    private var guideComponent: JComponent? = null

    /** Brand name, intentionally not translated (SettingsUiTest asserts it in every locale). */
    override fun getDisplayName(): String = "Commit Helper"

    override fun getConfigurables(): Array<Configurable> = pages.toTypedArray()

    override fun createComponent(): JComponent {
        root?.let {
            selectGuideIfRequested()
            return it
        }
        val tabbed = JBTabbedPane()
        var guideCreated: JComponent? = null
        pages.forEach { page ->
            val component = page.createComponent()
            tabbed.addTab(page.displayName, component)
            if (page === guide) guideCreated = component
        }
        tabbed.addChangeListener {
            if (tabbed.selectedComponent === guideCreated) guide.refresh()
        }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 8, 8)
            add(tabbed, BorderLayout.CENTER)
        }
        tabs = tabbed
        guideComponent = guideCreated
        root = panel
        active = this
        selectGuideIfRequested()
        return panel
    }

    override fun isModified(): Boolean = pages.any { it.isModified() }

    override fun apply() {
        pages.forEach { it.apply() }
    }

    override fun reset() {
        pages.forEach { it.reset() }
    }

    override fun disposeUIResources() {
        if (root != null) {
            pages.forEach { page ->
                runCatching { page.disposeUIResources() }
            }
        }
        tabs?.removeAll()
        tabs = null
        root = null
        guideComponent = null
        if (active === this) active = null
    }

    private fun selectGuideIfRequested() {
        if (!requestGuide) return
        requestGuide = false
        selectGuide()
    }

    private fun selectGuide() {
        val tabbed = tabs ?: return
        val component = guideComponent ?: return
        val index = tabbed.indexOfComponent(component)
        if (index >= 0) {
            tabbed.selectedIndex = index
            guide.refresh()
        }
    }

    companion object {
        @Volatile
        private var requestGuide = false

        @Volatile
        private var active: CommitHelperConfigurable? = null

        /** Opens the settings dialog on the guide tab. Called from the onboarding notification. */
        fun requestGuideTab() {
            requestGuide = true
            val current = active ?: return
            ApplicationManager.getApplication().invokeLater {
                current.selectGuide()
                requestGuide = false
            }
        }
    }
}
