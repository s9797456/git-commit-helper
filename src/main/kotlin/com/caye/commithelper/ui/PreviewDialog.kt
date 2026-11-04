package com.caye.commithelper.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shown when the commit message box is not empty. Never overwrites silently: the user
 * explicitly picks Replace, Append to end, or Cancel.
 */
class PreviewDialog(
    project: Project,
    private val currentText: String,
    private val generatedText: String,
    private val allowAppend: Boolean = true,
) : DialogWrapper(project) {

    enum class Choice { NONE, REPLACE, APPEND }

    var choice: Choice = Choice.NONE
        private set

    init {
        title = CommitHelperBundle.message("dialog.preview.title")
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridLayout(1, 2, JBUI.scale(8), 0))
        panel.add(labeled(CommitHelperBundle.message("dialog.preview.currentMessage"), textArea(currentText)))
        panel.add(labeled(CommitHelperBundle.message("dialog.preview.generatedMessage"), textArea(generatedText)))
        panel.preferredSize = Dimension(JBUI.scale(980), JBUI.scale(420))
        return panel
    }

    override fun createActions(): Array<Action> = if (allowAppend) {
        arrayOf(
            choiceAction(CommitHelperBundle.message("dialog.preview.replace"), Choice.REPLACE),
            choiceAction(CommitHelperBundle.message("dialog.preview.append"), Choice.APPEND),
            cancelAction,
        )
    } else {
        arrayOf(
            choiceAction(CommitHelperBundle.message("dialog.preview.replace"), Choice.REPLACE),
            cancelAction,
        )
    }

    private fun choiceAction(label: String, value: Choice): Action = object : AbstractAction(label) {
        override fun actionPerformed(e: ActionEvent) {
            choice = value
            close(OK_EXIT_CODE)
        }
    }

    private fun textArea(text: String): JBTextArea = JBTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 18
        columns = 40
        border = JBUI.Borders.empty(4)
    }

    private fun labeled(label: String, area: JBTextArea): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(4)))
        panel.add(JBLabel(label), BorderLayout.NORTH)
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        return panel
    }
}