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
 * platform wraps EP-based configurables in ConfigurableWrapper, which does NOT expose
 * Configurable.Composite children to the settings tree (only a raw programmatic configurable
 * would be expanded). The composite interface is still implemented so tools/tests can enumerate
 * the pages, but createComponent is what the settings dialog actually shows.
 *
 * Pages are created lazily: opening Settings used to construct five full Swing trees and run the
 * guide self-check before the user saw anything. Now only the selected tab is built, and each
 * page is reset from the current state when it is first opened.
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

    private val guideIndex: Int = pages.indexOf(guide)
    private val pageComponents = arrayOfNulls<JComponent>(pages.size)

    private var root: JComponent? = null
    private var tabs: JBTabbedPane? = null
    private var loadingComponent = false

    /** Brand name, intentionally not translated (SettingsUiTest asserts it in every locale). */
    override fun getDisplayName(): String = "Commit Helper"

    override fun getConfigurables(): Array<Configurable> = pages.toTypedArray()

    override fun createComponent(): JComponent {
        root?.let {
            selectGuideIfRequested()
            return it
        }
        val tabbed = JBTabbedPane()
        pages.forEach { tabbed.addTab(it.displayName, JPanel()) }
        tabbed.addChangeListener {
            if (loadingComponent) return@addChangeListener
            val index = tabbed.selectedIndex
            if (index >= 0) {
                ensurePage(index)
                if (index == guideIndex) guide.refresh()
            }
        }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8, 8, 8)
            add(tabbed, BorderLayout.CENTER)
        }
        tabs = tabbed
        root = panel
        active = this
        ensurePage(0)
        selectGuideIfRequested()
        return panel
    }

    /** Builds one tab on first selection and restores its current state. */
    private fun ensurePage(index: Int) {
        if (index !in pages.indices || pageComponents[index] != null) return
        val page = pages[index]
        val component = page.createComponent()
        if (page !== guide) page.reset()
        pageComponents[index] = component
        val tabbed = tabs ?: return
        val selected = tabbed.selectedIndex
        loadingComponent = true
        try {
            tabbed.setComponentAt(index, component)
            tabbed.setTitleAt(index, page.displayName)
            tabbed.selectedIndex = selected
        } finally {
            loadingComponent = false
        }
    }

    override fun isModified(): Boolean =
        pages.indices.any { pageComponents[it] != null && pages[it].isModified() }

    override fun apply() {
        pages.indices.forEach { index ->
            if (pageComponents[index] != null) pages[index].apply()
        }
    }

    override fun reset() {
        pages.indices.forEach { index ->
            if (pageComponents[index] != null) pages[index].reset()
        }
    }

    override fun disposeUIResources() {
        pages.forEach { page ->
            runCatching { page.disposeUIResources() }
        }
        pageComponents.fill(null)
        tabs?.removeAll()
        tabs = null
        root = null
        if (active === this) active = null
    }

    private fun selectGuideIfRequested() {
        if (!requestGuide) return
        requestGuide = false
        selectGuide()
    }

    private fun selectGuide() {
        val tabbed = tabs ?: return
        ensurePage(guideIndex)
        tabbed.selectedIndex = guideIndex
        guide.refresh()
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
