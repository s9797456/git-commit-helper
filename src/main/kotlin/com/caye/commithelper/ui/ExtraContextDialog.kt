package com.caye.commithelper.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.model.ExtraContext
import com.caye.commithelper.model.SkipCiRequest
import com.caye.commithelper.settings.ProjectCommitHelperSettings
import com.caye.commithelper.settings.SkipCiPosition
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Small dialog for the "generate with extra context" action. The values are remembered per
 * project (last issue text, note, Skip CI choice) so a repeated release flow is one Enter away.
 */
class ExtraContextDialog(
    private val project: Project,
    detectedIssue: String?,
    prefillNote: String,
) : DialogWrapper(project) {

    private val projectSettings = ProjectCommitHelperSettings.getInstance(project).state
    private val presets: List<String> = com.caye.commithelper.settings.CommitHelperSettings.getInstance()
        .state.skipCiPresets.filter { it.isNotBlank() }.ifEmpty { listOf("[skip ci]", "[ci skip]") }

    private val issuesField = JTextField(42).apply {
        text = projectSettings.lastExtraIssue.ifBlank { detectedIssue.orEmpty() }
    }
    private val noteArea = JBTextArea(projectSettings.lastExtraNote.ifBlank { prefillNote }, 6, 42).apply {
        lineWrap = true
        wrapStyleWord = true
        font = JTextField().font
    }
    private val skipCiCheck = JCheckBox(CommitHelperBundle.message("dialog.extra.skipCiCheckbox")).apply {
        isSelected = projectSettings.lastSkipCiEnabled
    }
    private val presetBox = JComboBox(presets.toTypedArray()).apply {
        selectedItem = projectSettings.lastSkipCiPreset.takeIf { presets.contains(it) } ?: presets.first()
    }
    private val positionBox = JComboBox(SkipCiPosition.entries.toTypedArray()).apply {
        selectedItem = projectSettings.lastSkipCiPosition
    }

    init {
        title = CommitHelperBundle.message("dialog.extra.title")
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        var row = 0
        fun addRow(label: String, component: JComponent) {
            val c = GridBagConstraints()
            c.gridx = 0
            c.gridy = row
            c.anchor = GridBagConstraints.NORTHWEST
            c.insets = Insets(JBUI.scale(4), 0, JBUI.scale(4), JBUI.scale(8))
            form.add(JBLabel(label), c)
            val value = GridBagConstraints()
            value.gridx = 1
            value.gridy = row
            value.weightx = 1.0
            value.fill = GridBagConstraints.HORIZONTAL
            value.insets = Insets(JBUI.scale(4), 0, JBUI.scale(4), 0)
            form.add(component, value)
            row++
        }
        addRow(CommitHelperBundle.message("dialog.extra.issuesLabel"), issuesField)
        addRow(CommitHelperBundle.message("dialog.extra.contextLabel"), JBScrollPane(noteArea))
        addRow(CommitHelperBundle.message("dialog.extra.skipCiLabel"), skipCiCheck)
        addRow(CommitHelperBundle.message("dialog.extra.markerLabel"), presetBox)
        addRow(CommitHelperBundle.message("dialog.extra.placementLabel"), positionBox)

        val hint = JBLabel(CommitHelperBundle.message("dialog.extra.hint"))
        val c = GridBagConstraints()
        c.gridx = 0
        c.gridy = row
        c.gridwidth = 2
        c.anchor = GridBagConstraints.WEST
        c.insets = Insets(JBUI.scale(8), 0, 0, 0)
        form.add(hint, c)

        val root = JPanel(BorderLayout())
        root.add(form, BorderLayout.CENTER)
        root.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(320))
        skipCiCheck.addActionListener { updateSkipCiEnabled() }
        updateSkipCiEnabled()
        return root
    }

    private fun updateSkipCiEnabled() {
        presetBox.isEnabled = skipCiCheck.isSelected
        positionBox.isEnabled = skipCiCheck.isSelected
    }

    override fun doOKAction() {
        projectSettings.lastExtraIssue = issuesField.text.trim()
        projectSettings.lastExtraNote = noteArea.text.trim()
        projectSettings.lastSkipCiEnabled = skipCiCheck.isSelected
        projectSettings.lastSkipCiPreset = (presetBox.selectedItem as? String).orEmpty()
        projectSettings.lastSkipCiPosition = positionBox.selectedItem as? SkipCiPosition ?: SkipCiPosition.END
        super.doOKAction()
    }

    fun result(): ExtraContext {
        val skipCi = if (skipCiCheck.isSelected) {
            SkipCiRequest(
                marker = (presetBox.selectedItem as? String).orEmpty(),
                position = positionBox.selectedItem as? SkipCiPosition ?: SkipCiPosition.END,
            )
        } else {
            null
        }
        return ExtraContext(
            issues = issuesField.text.trim(),
            note = noteArea.text.trim(),
            skipCi = skipCi,
        )
    }
}
