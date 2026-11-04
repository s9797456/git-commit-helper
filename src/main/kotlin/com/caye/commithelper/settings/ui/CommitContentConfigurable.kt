package com.caye.commithelper.settings.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.model.ChangeItem
import com.caye.commithelper.model.GeneratedMessage
import com.caye.commithelper.settings.BuiltIns
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.CommitTemplate
import com.caye.commithelper.settings.CommitType
import com.caye.commithelper.settings.PromptProfile
import com.caye.commithelper.settings.newSettingsId
import com.caye.commithelper.template.DefaultTemplates
import com.caye.commithelper.template.MessageRenderer
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.EditableModel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/**
 * Application settings page with the reusable commit-message library:
 *  - prompt profiles (create / duplicate / rename / delete / reset, plus the global default),
 *  - commit templates (format + item line, with a live preview, plus the global default),
 *  - the allowed commit types fed to the model.
 *
 * The page keeps working copies; nothing is written to [CommitHelperSettings] until apply().
 * Built-in entries are protected: the prompt profile cannot be deleted, and the built-in
 * template is read-only (duplicate it to customize).
 */
class CommitContentConfigurable : Configurable {

    private val settings = CommitHelperSettings.getInstance()

    private var profiles: MutableList<PromptProfile> = mutableListOf()
    private var templates: MutableList<CommitTemplate> = mutableListOf()
    private var types: MutableList<CommitType> = mutableListOf()

    private var defaultPromptId: String = BuiltIns.PROMPT_ID
    private var defaultTemplateId: String = BuiltIns.TEMPLATE_ID
    private var selectedPromptId: String? = null
    private var selectedTemplateId: String? = null

    private val promptCombo = JComboBox<PromptProfile>()
    private val defaultPromptCombo = JComboBox<PromptProfile>()
    private val promptArea = SettingsUi.textArea(12, 60)
    private val templateCombo = JComboBox<CommitTemplate>()
    private val defaultTemplateCombo = JComboBox<CommitTemplate>()
    private val formatArea = SettingsUi.textArea(8, 40)
    private val itemLineField = JTextField(40)
    private val previewArea = SettingsUi.textArea(12, 40, editable = false)

    private val newPromptButton = JButton(CommitHelperBundle.message("settings.content.button.new"))
    private val duplicatePromptButton = JButton(CommitHelperBundle.message("settings.content.button.duplicate"))
    private val renamePromptButton = JButton(CommitHelperBundle.message("settings.content.button.rename"))
    private val deletePromptButton = JButton(CommitHelperBundle.message("settings.content.button.delete"))
    private val resetPromptButton = JButton(CommitHelperBundle.message("settings.content.button.reset"))
    private val newTemplateButton = JButton(CommitHelperBundle.message("settings.content.button.new"))
    private val duplicateTemplateButton = JButton(CommitHelperBundle.message("settings.content.button.duplicate"))
    private val renameTemplateButton = JButton(CommitHelperBundle.message("settings.content.button.rename"))
    private val deleteTemplateButton = JButton(CommitHelperBundle.message("settings.content.button.delete"))

    private val typeTableModel = TypeTableModel(types)

    private var typeTable: JBTable? = null
    private var splitter: JBSplitter? = null
    private var root: JComponent? = null
    private var snapshot: ContentSnapshot? = null
    private var updating = false
    private var listenersWired = false

    /** A small, realistic message so the live preview shows subject, items and optional issue. */
    private val sampleMessage = GeneratedMessage(
        subject = "feat(settings): split configuration into pages",
        items = listOf(
            ChangeItem("feat", "settings", "add General, LLM and Commit Content pages"),
            ChangeItem("fix", "template", "keep the built-in template read-only"),
            ChangeItem("docs", "", "document the project defaults page"),
        ),
    )

    override fun getDisplayName(): String = CommitHelperBundle.message("settings.content.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        wireListeners()

        val column = SettingsUi.column()
        column.add(SettingsUi.section(CommitHelperBundle.message("settings.content.section.prompts")))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.content.profile"), promptControls()))
        column.add(SettingsUi.scrolling(promptArea, 200))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.content.placeholdersHint")))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.content.defaultProfile"), defaultPromptCombo))

        column.add(SettingsUi.section(CommitHelperBundle.message("settings.content.section.templates")))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.content.template"), templateControls()))
        column.add(templateEditor())
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.content.defaultTemplate"), defaultTemplateCombo))

        column.add(SettingsUi.section(CommitHelperBundle.message("settings.content.section.types")))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.content.typesHint")))
        column.add(typeTablePanel())
        column.add(SettingsUi.strut(16))

        val page = SettingsUi.page(column)
        root = page
        reset()
        return page
    }

    private fun promptControls(): JComponent = SettingsUi.controls(
        promptCombo,
        newPromptButton,
        duplicatePromptButton,
        renamePromptButton,
        deletePromptButton,
        resetPromptButton,
    )

    private fun templateControls(): JComponent = SettingsUi.controls(
        templateCombo,
        newTemplateButton,
        duplicateTemplateButton,
        renameTemplateButton,
        deleteTemplateButton,
    )

    private fun templateEditor(): JComponent {
        val editor = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel(CommitHelperBundle.message("settings.content.formatTemplate")), BorderLayout.NORTH)
            add(SettingsUi.scrolling(formatArea, 170, 400), BorderLayout.CENTER)
            add(SettingsUi.row(CommitHelperBundle.message("settings.content.itemLine"), itemLineField), BorderLayout.SOUTH)
        }
        val preview = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBLabel(CommitHelperBundle.message("settings.content.preview")), BorderLayout.NORTH)
            add(SettingsUi.scrolling(previewArea, 170, 400), BorderLayout.CENTER)
        }
        val split = JBSplitter(false, 0.55f)
        split.firstComponent = editor
        split.secondComponent = preview
        split.preferredSize = Dimension(JBUI.scale(860), JBUI.scale(260))
        // Grow with the dialog instead of stopping at a fixed 270px: the editor and preview
        // scroll panes are in BorderLayout.CENTER and follow the splitter height.
        split.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        splitter = split
        return split
    }

    private fun typeTablePanel(): JComponent {
        val table = JBTable(typeTableModel)
        table.rowHeight = JBUI.scale(22)
        table.fillsViewportHeight = true
        table.preferredScrollableViewportSize = Dimension(JBUI.scale(600), JBUI.scale(160))
        typeTable = table
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                typeTableModel.addRow()
                val last = typeTableModel.rowCount - 1
                if (last >= 0) {
                    table.selectionModel.setSelectionInterval(last, last)
                    table.editCellAt(last, 0)
                }
            }
            .setRemoveAction {
                val row = table.selectedRow
                if (row >= 0) typeTableModel.removeRow(row)
            }
            .setAddActionUpdater { true }
            .setRemoveActionUpdater { table.selectedRow >= 0 }
            .disableUpDownActions()
        val panel = decorator.createPanel()
        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))
        return panel
    }

    private fun wireListeners() {
        if (listenersWired) return
        listenersWired = true
        val renderer = NamedRenderer { value ->
            when (value) {
                is PromptProfile -> libraryLabel(value.name, value.builtIn)
                is CommitTemplate -> libraryLabel(value.name, value.builtIn)
                else -> value?.toString()
            }
        }
        promptCombo.renderer = renderer
        defaultPromptCombo.renderer = renderer
        templateCombo.renderer = renderer
        defaultTemplateCombo.renderer = renderer

        promptCombo.addActionListener { if (!updating) onPromptSelectionChanged() }
        templateCombo.addActionListener { if (!updating) onTemplateSelectionChanged() }
        defaultPromptCombo.addActionListener {
            if (!updating) defaultPromptId = (defaultPromptCombo.selectedItem as? PromptProfile)?.id ?: defaultPromptId
        }
        defaultTemplateCombo.addActionListener {
            if (!updating) defaultTemplateId =
                (defaultTemplateCombo.selectedItem as? CommitTemplate)?.id ?: defaultTemplateId
        }

        newPromptButton.addActionListener { newPrompt() }
        duplicatePromptButton.addActionListener { duplicatePrompt() }
        renamePromptButton.addActionListener { renamePrompt() }
        deletePromptButton.addActionListener { deletePrompt() }
        resetPromptButton.addActionListener { resetBuiltInPrompt() }
        newTemplateButton.addActionListener { newTemplate() }
        duplicateTemplateButton.addActionListener { duplicateTemplate() }
        renameTemplateButton.addActionListener { renameTemplate() }
        deleteTemplateButton.addActionListener { deleteTemplate() }

        val previewListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updatePreview()
            override fun removeUpdate(e: DocumentEvent) = updatePreview()
            override fun changedUpdate(e: DocumentEvent) = updatePreview()
        }
        formatArea.document.addDocumentListener(previewListener)
        itemLineField.document.addDocumentListener(previewListener)
    }

    override fun isModified(): Boolean {
        val saved = snapshot ?: return false
        return captureContent() != saved
    }

    override fun apply() {
        storeSelectedPrompt()
        storeSelectedTemplate()
        types.removeAll { it.type.isBlank() }
        typeTableModel.refresh()
        ensureBuiltIns()
        if (profiles.none { it.id == defaultPromptId }) defaultPromptId = BuiltIns.PROMPT_ID
        if (templates.none { it.id == defaultTemplateId }) defaultTemplateId = BuiltIns.TEMPLATE_ID

        val state = settings.state
        state.promptProfiles = profiles.map { it.copy() }.toMutableList()
        state.commitTemplates = templates.map { it.copy() }.toMutableList()
        state.commitTypes = types.map { it.copy() }.toMutableList()
        state.defaultPromptProfileId = defaultPromptId
        state.defaultTemplateId = defaultTemplateId

        refreshPromptControls()
        refreshTemplateControls()
        typeTableModel.refresh()
        snapshot = captureContent()
    }

    override fun reset() {
        val state = settings.state
        profiles.clear()
        profiles.addAll(state.promptProfiles.map { it.copy() })
        templates.clear()
        templates.addAll(state.commitTemplates.map { it.copy() })
        types.clear()
        types.addAll(state.commitTypes.map { it.copy() })

        defaultPromptId = state.defaultPromptProfileId
        defaultTemplateId = state.defaultTemplateId
        selectedPromptId = profiles.firstOrNull { it.id == defaultPromptId }?.id
            ?: profiles.firstOrNull()?.id
        selectedTemplateId = templates.firstOrNull { it.id == defaultTemplateId }?.id
            ?: templates.firstOrNull()?.id

        refreshPromptControls()
        refreshTemplateControls()
        typeTableModel.refresh()
        snapshot = captureContent()
    }

    override fun disposeUIResources() {
        root = null
        splitter = null
        typeTable = null
    }

    // --- prompt profiles -------------------------------------------------------------------

    private fun refreshPromptControls() {
        updating = true
        promptCombo.removeAllItems()
        profiles.forEach { promptCombo.addItem(it) }
        defaultPromptCombo.removeAllItems()
        profiles.forEach { defaultPromptCombo.addItem(it) }
        updating = false

        val profileIndex = profiles.indexOfFirst { it.id == selectedPromptId }
        updating = true
        promptCombo.selectedIndex = if (profileIndex >= 0) profileIndex else if (profiles.isEmpty()) -1 else 0
        updating = false
        selectedPromptId = (promptCombo.selectedItem as? PromptProfile)?.id
        promptArea.text = profiles.firstOrNull { it.id == selectedPromptId }?.prompt.orEmpty()

        val defaultIndex = profiles.indexOfFirst { it.id == defaultPromptId }
        updating = true
        defaultPromptCombo.selectedIndex = if (defaultIndex >= 0) defaultIndex else if (profiles.isEmpty()) -1 else 0
        updating = false
        defaultPromptId = (defaultPromptCombo.selectedItem as? PromptProfile)?.id ?: BuiltIns.PROMPT_ID
        updatePromptButtons()
    }

    private fun updatePromptButtons() {
        val selected = profiles.firstOrNull { it.id == selectedPromptId }
        val builtIn = selected?.builtIn == true
        duplicatePromptButton.isEnabled = selected != null
        renamePromptButton.isEnabled = selected != null && !builtIn
        deletePromptButton.isEnabled = selected != null && !builtIn
        resetPromptButton.isEnabled = profiles.any { it.builtIn }
    }

    private fun onPromptSelectionChanged() {
        storeSelectedPrompt()
        selectedPromptId = (promptCombo.selectedItem as? PromptProfile)?.id
        promptArea.text = profiles.firstOrNull { it.id == selectedPromptId }?.prompt.orEmpty()
        updatePromptButtons()
    }

    private fun storeSelectedPrompt() {
        val profile = profiles.firstOrNull { it.id == selectedPromptId } ?: return
        profile.prompt = promptArea.text
    }

    private fun selectedPrompt(): PromptProfile? = profiles.firstOrNull { it.id == selectedPromptId }

    private fun newPrompt() {
        storeSelectedPrompt()
        val profile = PromptProfile(
            id = newSettingsId(),
            name = uniqueName(CommitHelperBundle.message("settings.content.newPrompt"), profiles.map { it.name }),
            prompt = DefaultTemplates.PROMPT,
        )
        profiles.add(profile)
        selectedPromptId = profile.id
        refreshPromptControls()
    }

    private fun duplicatePrompt() {
        val source = selectedPrompt() ?: return
        storeSelectedPrompt()
        val copy = PromptProfile(
            id = newSettingsId(),
            name = uniqueName(
                CommitHelperBundle.message("settings.content.copyOf", source.name),
                profiles.map { it.name },
            ),
            prompt = source.prompt,
        )
        profiles.add(copy)
        selectedPromptId = copy.id
        refreshPromptControls()
    }

    private fun renamePrompt() {
        val profile = selectedPrompt() ?: return
        if (profile.builtIn) return
        val name = Messages.showInputDialog(
            null as Project?,
            CommitHelperBundle.message("settings.content.renamePrompt.label"),
            CommitHelperBundle.message("settings.content.renamePrompt.title"),
            Messages.getQuestionIcon(),
        )?.trim()
        if (name.isNullOrEmpty()) return
        profile.name = name
        refreshPromptControls()
    }

    private fun deletePrompt() {
        val profile = selectedPrompt() ?: return
        if (profile.builtIn) return
        val answer = Messages.showYesNoDialog(
            null as Project?,
            CommitHelperBundle.message("settings.content.deletePrompt.question", profile.name),
            CommitHelperBundle.message("settings.content.deletePrompt.title"),
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        profiles.remove(profile)
        if (defaultPromptId == profile.id) defaultPromptId = BuiltIns.PROMPT_ID
        selectedPromptId = profiles.firstOrNull()?.id
        refreshPromptControls()
    }

    private fun resetBuiltInPrompt() {
        val builtIn = profiles.firstOrNull { it.builtIn } ?: BuiltIns.promptProfile().also { profiles.add(0, it) }
        val answer = Messages.showYesNoDialog(
            null as Project?,
            CommitHelperBundle.message("settings.content.resetPrompt.question"),
            CommitHelperBundle.message("settings.content.resetPrompt.title"),
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        builtIn.prompt = DefaultTemplates.PROMPT
        defaultPromptId = builtIn.id
        selectedPromptId = builtIn.id
        refreshPromptControls()
    }

    // --- commit templates ------------------------------------------------------------------

    private fun refreshTemplateControls() {
        updating = true
        templateCombo.removeAllItems()
        templates.forEach { templateCombo.addItem(it) }
        defaultTemplateCombo.removeAllItems()
        templates.forEach { defaultTemplateCombo.addItem(it) }
        updating = false

        val templateIndex = templates.indexOfFirst { it.id == selectedTemplateId }
        updating = true
        templateCombo.selectedIndex = if (templateIndex >= 0) templateIndex else if (templates.isEmpty()) -1 else 0
        updating = false
        selectedTemplateId = (templateCombo.selectedItem as? CommitTemplate)?.id
        loadTemplateEditor()

        val defaultIndex = templates.indexOfFirst { it.id == defaultTemplateId }
        updating = true
        defaultTemplateCombo.selectedIndex = if (defaultIndex >= 0) defaultIndex else if (templates.isEmpty()) -1 else 0
        updating = false
        defaultTemplateId = (defaultTemplateCombo.selectedItem as? CommitTemplate)?.id ?: BuiltIns.TEMPLATE_ID
        updateTemplateButtons()
    }

    private fun updateTemplateButtons() {
        val selected = selectedTemplate()
        val builtIn = selected?.builtIn == true
        duplicateTemplateButton.isEnabled = selected != null
        renameTemplateButton.isEnabled = selected != null && !builtIn
        deleteTemplateButton.isEnabled = selected != null && !builtIn
    }

    private fun onTemplateSelectionChanged() {
        storeSelectedTemplate()
        selectedTemplateId = (templateCombo.selectedItem as? CommitTemplate)?.id
        loadTemplateEditor()
    }

    private fun loadTemplateEditor() {
        val template = selectedTemplate()
        formatArea.text = template?.format.orEmpty()
        itemLineField.text = template?.itemLine.orEmpty()
        val editable = template != null && !template.builtIn
        formatArea.isEditable = editable
        itemLineField.isEditable = editable
        updatePreview()
        updateTemplateButtons()
    }

    private fun storeSelectedTemplate() {
        val template = selectedTemplate() ?: return
        if (template.builtIn) return
        template.format = formatArea.text
        template.itemLine = itemLineField.text
    }

    private fun selectedTemplate(): CommitTemplate? = templates.firstOrNull { it.id == selectedTemplateId }

    private fun newTemplate() {
        storeSelectedTemplate()
        val template = CommitTemplate(
            id = newSettingsId(),
            name = uniqueName(CommitHelperBundle.message("settings.content.newTemplate"), templates.map { it.name }),
            format = DefaultTemplates.FORMAT,
            itemLine = DefaultTemplates.ITEM_LINE,
        )
        templates.add(template)
        selectedTemplateId = template.id
        refreshTemplateControls()
    }

    private fun duplicateTemplate() {
        val source = selectedTemplate() ?: return
        storeSelectedTemplate()
        val copy = CommitTemplate(
            id = newSettingsId(),
            name = uniqueName(
                CommitHelperBundle.message("settings.content.copyOf", source.name),
                templates.map { it.name },
            ),
            format = source.format,
            itemLine = source.itemLine,
        )
        templates.add(copy)
        selectedTemplateId = copy.id
        refreshTemplateControls()
    }

    private fun renameTemplate() {
        val template = selectedTemplate() ?: return
        if (template.builtIn) return
        val name = Messages.showInputDialog(
            null as Project?,
            CommitHelperBundle.message("settings.content.renameTemplate.label"),
            CommitHelperBundle.message("settings.content.renameTemplate.title"),
            Messages.getQuestionIcon(),
        )?.trim()
        if (name.isNullOrEmpty()) return
        template.name = name
        refreshTemplateControls()
    }

    private fun deleteTemplate() {
        val template = selectedTemplate() ?: return
        if (template.builtIn) return
        val answer = Messages.showYesNoDialog(
            null as Project?,
            CommitHelperBundle.message("settings.content.deleteTemplate.question", template.name),
            CommitHelperBundle.message("settings.content.deleteTemplate.title"),
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        templates.remove(template)
        if (defaultTemplateId == template.id) defaultTemplateId = BuiltIns.TEMPLATE_ID
        selectedTemplateId = templates.firstOrNull()?.id
        refreshTemplateControls()
    }

    private fun updatePreview() {
        previewArea.text = MessageRenderer.render(sampleMessage, formatArea.text, itemLineField.text)
    }

    // --- helpers ---------------------------------------------------------------------------

    private fun ensureBuiltIns() {
        if (profiles.none { it.id == BuiltIns.PROMPT_ID }) profiles.add(0, BuiltIns.promptProfile())
        if (templates.none { it.id == BuiltIns.TEMPLATE_ID }) templates.add(0, BuiltIns.template())
    }

    private fun libraryLabel(name: String, builtIn: Boolean): String =
        if (builtIn) CommitHelperBundle.message("settings.library.builtInName", name) else name

    private fun uniqueName(base: String, existing: Collection<String>): String {
        if (base !in existing) return base
        var index = 2
        while (base + " " + index in existing) index++
        return base + " " + index
    }

    private fun captureContent(): ContentSnapshot {
        storeSelectedPrompt()
        storeSelectedTemplate()
        return ContentSnapshot(
            profiles = profiles.map { it.copy() },
            templates = templates.map { it.copy() },
            types = types.map { it.copy() },
            defaultPromptId = defaultPromptId,
            defaultTemplateId = defaultTemplateId,
        )
    }

    private data class ContentSnapshot(
        val profiles: List<PromptProfile>,
        val templates: List<CommitTemplate>,
        val types: List<CommitType>,
        val defaultPromptId: String,
        val defaultTemplateId: String,
    )

    /** Two-column editable table backing the commit-type list. */
    private class TypeTableModel(private val rows: MutableList<CommitType>) : AbstractTableModel(), EditableModel {

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = 2

        override fun getColumnName(column: Int): String = if (column == 0) {
            CommitHelperBundle.message("settings.content.column.type")
        } else {
            CommitHelperBundle.message("settings.content.column.description")
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows.getOrNull(rowIndex) ?: return ""
            return if (columnIndex == 0) row.type else row.description
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val row = rows.getOrNull(rowIndex) ?: return
            val text = value?.toString().orEmpty().trim()
            if (columnIndex == 0) row.type = text else row.description = text
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        fun refresh() = fireTableDataChanged()

        override fun addRow() {
            rows.add(CommitType("", ""))
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        override fun removeRow(index: Int) {
            if (index !in rows.indices) return
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        override fun exchangeRows(oldIndex: Int, newIndex: Int) {
            if (oldIndex !in rows.indices || newIndex !in rows.indices) return
            val moved = rows.removeAt(oldIndex)
            rows.add(newIndex, moved)
            fireTableRowsUpdated(minOf(oldIndex, newIndex), maxOf(oldIndex, newIndex))
        }

        override fun canExchangeRows(oldIndex: Int, newIndex: Int): Boolean = true
    }
}
