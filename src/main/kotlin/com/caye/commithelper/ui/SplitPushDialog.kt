package com.caye.commithelper.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

data class SplitPushOptions(
    val remote: String,
    val branch: String,
    val tag: String?,
)

/** Push the current HEAD to an editable remote branch, optionally with a tag. */
class SplitPushDialog(
    project: Project,
    remotes: List<String>,
    defaultBranch: String,
) : DialogWrapper(project, true) {

    private val remoteBox = JComboBox((remotes.ifEmpty { listOf("origin") }).toTypedArray()).apply {
        isEditable = true
        selectedItem = remotes.firstOrNull() ?: "origin"
    }
    private val branchField = JBTextField(defaultBranch)
    private val tagField = JBTextField()

    init {
        title = CommitHelperBundle.message("split.pushTitle")
        setOKButtonText(CommitHelperBundle.message("split.push"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val form = JPanel(BorderLayout(0, JBUI.scale(8)))
        form.add(row(CommitHelperBundle.message("split.remote"), remoteBox), BorderLayout.NORTH)
        val branchRow = row(CommitHelperBundle.message("split.remoteBranch"), branchField)
        val tagRow = row(CommitHelperBundle.message("split.tag"), tagField)
        val fields = JPanel()
        fields.layout = javax.swing.BoxLayout(fields, javax.swing.BoxLayout.Y_AXIS)
        fields.add(branchRow)
        fields.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        fields.add(tagRow)
        form.add(fields, BorderLayout.CENTER)
        form.preferredSize = Dimension(JBUI.scale(560), JBUI.scale(150))
        return form
    }

    private fun row(label: String, field: JComponent): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        val labelComponent = JBLabel(label)
        labelComponent.preferredSize = Dimension(JBUI.scale(150), labelComponent.preferredSize.height)
        panel.add(labelComponent, BorderLayout.WEST)
        panel.add(field, BorderLayout.CENTER)
        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
        return panel
    }

    override fun doOKAction() {
        if (options() == null) {
            setErrorText(CommitHelperBundle.message("split.pushRequired"))
            return
        }
        super.doOKAction()
    }

    fun options(): SplitPushOptions? {
        val remote = (remoteBox.editor.item as? String)?.trim().orEmpty()
        val branch = branchField.text.trim()
        if (remote.isEmpty() || branch.isEmpty()) return null
        return SplitPushOptions(remote, branch, tagField.text.trim().takeIf { it.isNotBlank() })
    }
}
