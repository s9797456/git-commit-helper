package com.caye.commithelper.settings.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.ProjectCommitHelperSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Project defaults embedded as the fourth tab of the application settings page. The global
 * library (prompts/templates) stays in Commit Content; this page only stores the two selected ids
 * in .idea/commit-helper.xml. A project picker keeps the tab usable when several projects are
 * open, and the page degrades to a hint when no project is open.
 */
class ProjectDefaultsConfigurable : Configurable {

    private val global = CommitHelperSettings.getInstance()

    private val projectBox = JComboBox<Project>()
    private val promptBox = JComboBox<Choice>()
    private val templateBox = JComboBox<Choice>()
    private val noProjectHint = JBLabel("<html><i>" + CommitHelperBundle.message("settings.project.noProjects") + "</i></html>")

    private var root: JComponent? = null
    private var snapshot: Selection? = null
    private var selectedProject: Project? = null
    private var updating = false
    private var listenersWired = false

    override fun getDisplayName(): String = CommitHelperBundle.message("settings.project.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        wireListeners()
        projectBox.renderer = NamedRenderer { value -> (value as? Project)?.name ?: value?.toString() }

        val column = SettingsUi.column()
        column.add(SettingsUi.section(CommitHelperBundle.message("settings.project.section")))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.project.hint")))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.project.project"), projectBox))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.project.promptProfile"), promptBox))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.project.commitTemplate"), templateBox))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.project.inheritHint")))
        column.add(noProjectHint)
        column.add(SettingsUi.strut(16))

        val page = SettingsUi.page(column)
        root = page
        reset()
        return page
    }

    private fun wireListeners() {
        if (listenersWired) return
        listenersWired = true
        projectBox.addActionListener {
            if (updating) return@addActionListener
            val target = projectBox.selectedItem as? Project ?: return@addActionListener
            if (target === selectedProject) return@addActionListener
            if (isModified()) {
                val discard = Messages.showYesNoDialog(
                    root ?: projectBox,
                    CommitHelperBundle.message("settings.project.discardChanges", selectedProject?.name.orEmpty()),
                    CommitHelperBundle.message("settings.project.discardTitle"),
                    Messages.getWarningIcon(),
                ) == Messages.YES
                if (!discard) {
                    updating = true
                    projectBox.selectedItem = selectedProject
                    updating = false
                    return@addActionListener
                }
            }
            loadProject(target)
        }
    }

    override fun isModified(): Boolean {
        val saved = snapshot ?: return false
        return currentSelection() != saved
    }

    override fun apply() {
        val project = selectedProject ?: return
        val selection = currentSelection()
        val state = ProjectCommitHelperSettings.getInstance(project).state
        state.promptProfileId = selection.promptProfileId
        state.templateId = selection.templateId
        snapshot = selection
    }

    override fun reset() {
        val projects = ProjectManager.getInstance().openProjects.filter { !it.isDefault }
        val previous = selectedProject
        val active = if (previous != null && projects.contains(previous)) {
            previous
        } else {
            guessActiveProject(projects) ?: projects.firstOrNull()
        }
        updating = true
        projectBox.removeAllItems()
        projects.forEach { projectBox.addItem(it) }
        projectBox.isEnabled = projects.isNotEmpty()
        if (active != null) projectBox.selectedItem = active
        updating = false
        val hasProjects = projects.isNotEmpty()
        promptBox.isEnabled = hasProjects
        templateBox.isEnabled = hasProjects
        noProjectHint.isVisible = !hasProjects
        if (active != null) {
            loadProject(active)
        } else {
            selectedProject = null
            fill(promptBox, emptyList(), "")
            fill(templateBox, emptyList(), "")
            snapshot = currentSelection()
        }
    }

    override fun disposeUIResources() {
        root = null
    }

    /**
     * Best-effort "current project". ProjectUtil lives in a client-only jar in some IDE
     * distributions, so resolve it reflectively and fall back to the first open project.
     */
    private fun guessActiveProject(projects: List<Project>): Project? {
        val component = root ?: return null
        return runCatching {
            val clazz = Class.forName("com.intellij.openapi.project.ProjectUtil")
            val method = clazz.getMethod("guessCurrentProject", javax.swing.JComponent::class.java)
            (method.invoke(null, component) as? Project)?.takeIf { it in projects }
        }.getOrNull()
    }

    private fun loadProject(project: Project) {
        selectedProject = project
        val state = global.state
        val projectState = ProjectCommitHelperSettings.getInstance(project).state
        val useGlobalDefault = CommitHelperBundle.message("settings.project.useGlobalDefault")
        fill(
            promptBox,
            listOf(Choice("", useGlobalDefault)) + state.promptProfiles.map { profile ->
                Choice(profile.id, libraryLabel(profile.name, profile.builtIn))
            },
            projectState.promptProfileId,
        )
        fill(
            templateBox,
            listOf(Choice("", useGlobalDefault)) + state.commitTemplates.map { template ->
                Choice(template.id, libraryLabel(template.name, template.builtIn))
            },
            projectState.templateId,
        )
        snapshot = currentSelection()
    }

    private fun fill(combo: JComboBox<Choice>, items: List<Choice>, selectedId: String) {
        updating = true
        combo.removeAllItems()
        items.forEach { combo.addItem(it) }
        val index = items.indexOfFirst { it.id == selectedId }
        combo.selectedIndex = if (index >= 0) index else 0
        updating = false
    }

    private fun currentSelection(): Selection = Selection(
        promptProfileId = (promptBox.selectedItem as? Choice)?.id.orEmpty(),
        templateId = (templateBox.selectedItem as? Choice)?.id.orEmpty(),
    )

    private fun libraryLabel(name: String, builtIn: Boolean): String =
        if (builtIn) CommitHelperBundle.message("settings.library.builtInName", name) else name

    private class Choice(val id: String, private val label: String) {
        override fun toString(): String = label
    }

    private data class Selection(val promptProfileId: String, val templateId: String)
}
