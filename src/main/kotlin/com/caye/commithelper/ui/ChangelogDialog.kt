package com.caye.commithelper.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * M3: read-only preview of the generated commit message, changelog and PR description.
 * Copy only — the plugin never writes repository files and never calls a forge API.
 */
class ChangelogDialog(
    project: Project,
    private val commitMessage: String,
    private val changelog: String,
    private val prDescription: String,
) : DialogWrapper(project) {

    init {
        title = "Commit Helper — Commit Message / Changelog / PR Description"
        isResizable = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JTabbedPane()
        tabs.addTab("Commit Message", tab(commitMessage))
        tabs.addTab("Changelog", tab(changelog))
        tabs.addTab("PR Description", tab(prDescription))
        tabs.preferredSize = Dimension(JBUI.scale(900), JBUI.scale(520))
        return tabs
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    private fun tab(text: String): JComponent {
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(4)
        }
        val copy = JButton(object : AbstractAction("Copy") {
            override fun actionPerformed(e: ActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
            }
        })
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        top.add(copy)
        val panel = JPanel(BorderLayout())
        panel.add(top, BorderLayout.NORTH)
        panel.add(JBScrollPane(area), BorderLayout.CENTER)
        return panel
    }
}
