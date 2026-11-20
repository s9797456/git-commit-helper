package com.caye.commithelper.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.split.SplitGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionEvent

/** Left file list + right unified diff preview for one split group. */
class SplitFilePreviewDialog(
    project: Project,
    private val group: SplitGroup,
    selectedFile: String? = null,
) : DialogWrapper(project, true) {

    private val fileModel = DefaultListModel<String>()
    private val fileList = JBList(fileModel)
    private val diffArea = JBTextArea(28, 100)

    init {
        title = CommitHelperBundle.message("split.previewTitle")
        setOKButtonText(CommitHelperBundle.message("split.close"))
        group.files.forEach { fileModel.addElement(it) }
        init()
        fileList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fileList.addListSelectionListener { event: ListSelectionEvent -> if (!event.valueIsAdjusting) showDiff() }
        val index = selectedFile?.let { group.files.indexOf(it) } ?: 0
        fileList.selectedIndex = if (index in 0 until fileModel.size()) index else 0
        diffArea.isEditable = false
        diffArea.lineWrap = false
        showDiff()
    }

    override fun createCenterPanel(): JComponent {
        val left = JPanel(BorderLayout(0, JBUI.scale(4)))
        left.add(JBLabel(CommitHelperBundle.message("split.files")), BorderLayout.NORTH)
        left.add(JBScrollPane(fileList), BorderLayout.CENTER)

        val right = JPanel(BorderLayout(0, JBUI.scale(4)))
        right.add(JBLabel(CommitHelperBundle.message("split.previewDiff")), BorderLayout.NORTH)
        right.add(JBScrollPane(diffArea), BorderLayout.CENTER)

        val splitter = JBSplitter(false, 0.24f)
        splitter.firstComponent = left
        splitter.secondComponent = right
        splitter.preferredSize = Dimension(JBUI.scale(980), JBUI.scale(620))
        return splitter
    }

    private fun showDiff() {
        val file = fileList.selectedValue ?: return
        val diff = group.diffs[file].orEmpty()
        diffArea.text = if (diff.isBlank()) CommitHelperBundle.message("split.noDiff") else diff
        diffArea.caretPosition = 0
    }
}
