package com.caye.commithelper.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.model.ChangeKind
import com.caye.commithelper.split.SplitCommitResult
import com.caye.commithelper.split.SplitGroup
import com.caye.commithelper.split.SplitPlan
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent

/**
 * Preview and apply one file-level group from a smart-split plan.
 *
 * Message is editable. Double-click a file in the lower list to inspect its unified diff in a
 * left/right preview dialog. Apply commits the group and keeps the dialog open while others remain.
 */
class SplitPlanDialog(
    private val project: Project,
    plan: SplitPlan,
    private val onCommit: (SplitGroup, String, (SplitCommitResult) -> Unit) -> Unit,
    private val onPush: (SplitGroup) -> Unit,
) : DialogWrapper(project, true) {

    private val model = DefaultListModel<SplitGroup>()
    private val list = JBList(model)
    private val messageArea = JBTextArea(14, 80)
    private val fileModel = DefaultListModel<String>()
    private val fileList = JBList(fileModel)
    private val hint = JBLabel()
    private lateinit var applyAction: AbstractAction
    private lateinit var pushAction: AbstractAction
    private var lastCommittedGroup: SplitGroup? = null

    init {
        plan.groups.forEach { model.addElement(it) }
        title = CommitHelperBundle.message("split.title")
        setCancelButtonText(CommitHelperBundle.message("split.close"))
        hint.text = when {
            plan.usedFallback -> CommitHelperBundle.message("split.fallback")
            plan.groups.size == 1 -> CommitHelperBundle.message("split.singleGroup")
            else -> CommitHelperBundle.message("split.doubleClickPreview")
        }
        init()
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = SplitGroupRenderer()
        list.addListSelectionListener { event: ListSelectionEvent -> if (!event.valueIsAdjusting) showSelected() }
        list.selectedIndex = 0
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.cellRenderer = FileRenderer()
        fileList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) openPreview()
            }
        })
        messageArea.isEditable = true
        messageArea.lineWrap = true
        messageArea.wrapStyleWord = true
        showSelected()
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout(0, JBUI.scale(4)))
        left.add(JBLabel(CommitHelperBundle.message("split.groups")), BorderLayout.NORTH)
        left.add(JBScrollPane(list), BorderLayout.CENTER)
        left.preferredSize = Dimension(JBUI.scale(260), JBUI.scale(380))

        val right = JPanel(BorderLayout(0, JBUI.scale(6)))
        right.add(JBLabel(CommitHelperBundle.message("split.message")), BorderLayout.NORTH)
        right.add(JBScrollPane(messageArea), BorderLayout.CENTER)
        right.preferredSize = Dimension(JBUI.scale(820), JBUI.scale(380))
        right.minimumSize = Dimension(JBUI.scale(560), JBUI.scale(280))

        val filesPanel = JPanel(BorderLayout(0, JBUI.scale(4)))
        filesPanel.add(JBLabel(CommitHelperBundle.message("split.files")), BorderLayout.NORTH)
        filesPanel.add(JBScrollPane(fileList), BorderLayout.CENTER)
        filesPanel.preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(170))

        val center = JPanel(BorderLayout(0, JBUI.scale(6)))
        center.add(left, BorderLayout.WEST)
        center.add(right, BorderLayout.CENTER)

        val root = JPanel(BorderLayout(0, JBUI.scale(6)))
        root.add(center, BorderLayout.CENTER)
        root.add(filesPanel, BorderLayout.SOUTH)
        root.add(hint, BorderLayout.NORTH)
        root.preferredSize = Dimension(JBUI.scale(1120), JBUI.scale(720))
        root.minimumSize = Dimension(JBUI.scale(900), JBUI.scale(560))
        return root
    }

    override fun createActions(): Array<javax.swing.Action> {
        applyAction = object : AbstractAction(CommitHelperBundle.message("split.apply")) {
            override fun actionPerformed(e: ActionEvent?) = applySelected()
        }
        pushAction = object : AbstractAction(CommitHelperBundle.message("split.push")) {
            override fun actionPerformed(e: ActionEvent?) {
                lastCommittedGroup?.let { onPush(it) }
            }
        }
        pushAction.isEnabled = false
        return arrayOf(applyAction, pushAction, cancelAction)
    }

    private fun applySelected() {
        val group = list.selectedValue ?: return
        val message = messageArea.text.trim()
        if (message.isEmpty()) return
        applyAction.isEnabled = false
        hint.text = CommitHelperBundle.message("split.applying")
        onCommit(group, message) { result ->
            applyAction.isEnabled = true
            if (!result.success) {
                hint.text = result.errorMessage ?: CommitHelperBundle.message("split.commitFailed")
                return@onCommit
            }
            lastCommittedGroup = group
            model.removeElement(group)
            pushAction.isEnabled = true
            if (model.size() > 0) {
                list.selectedIndex = 0
                hint.text = CommitHelperBundle.message("split.committed", result.committedFiles)
            } else {
                messageArea.text = ""
                fileModel.clear()
                hint.text = CommitHelperBundle.message("split.allCommitted")
                applyAction.isEnabled = false
            }
        }
    }

    private fun showSelected() {
        val group = list.selectedValue ?: return
        messageArea.text = group.message()
        messageArea.caretPosition = 0
        fileModel.clear()
        group.files.forEach { fileModel.addElement(it) }
    }

    private fun openPreview() {
        val group = list.selectedValue ?: return
        val selected = fileList.selectedValue ?: return
        SplitFileDiffer.show(project, group, selected)
    }

    private inner class FileRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (component is javax.swing.JLabel && value is String) {
                val group = this@SplitPlanDialog.list.selectedValue
                if (!isSelected) {
                    component.foreground = when (group?.fileKinds?.get(value)) {
                        ChangeKind.ADDED -> ADDED_COLOR
                        ChangeKind.DELETED -> DELETED_COLOR
                        ChangeKind.RENAMED -> RENAMED_COLOR
                        ChangeKind.MODIFIED -> MODIFIED_COLOR
                        else -> component.foreground
                    }
                }
            }
            return component
        }
    }

    private companion object {
        val ADDED_COLOR = JBColor(0x2E7D32, 0x6A9955)
        val MODIFIED_COLOR = JBColor(0x1565C0, 0x4FC1FF)
        val DELETED_COLOR = JBColor(0xC62828, 0xF48771)
        val RENAMED_COLOR = JBColor(0xEF6C00, 0xD7BA7D)
    }

    private inner class SplitGroupRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is SplitGroup) {
                text = "<html><b>" + value.name + "</b><br><span style='font-size:90%'>" +
                    value.files.size + " files</span></html>"
            }
            return component
        }
    }
}
