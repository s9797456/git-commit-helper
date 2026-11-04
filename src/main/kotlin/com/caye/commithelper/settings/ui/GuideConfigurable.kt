package com.caye.commithelper.settings.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.settings.Diagnostic
import com.caye.commithelper.settings.DiagnosticSeverity
import com.caye.commithelper.settings.SettingsDiagnostics
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Fifth settings tab: quick start, placeholder cheat sheet, the exact mistake that produces
 * literal `Format template:` / `{text}` output, and a live (non-blocking) self-check.
 */
class GuideConfigurable : Configurable {

    private val status = JBLabel()
    private val body = JEditorPane("text/html", "")
    private val copyStatus = JBLabel(" ")
    private var root: JComponent? = null

    override fun getDisplayName(): String = CommitHelperBundle.message("settings.guide.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        body.isEditable = false
        body.isOpaque = false
        body.border = null
        body.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        body.font = UIUtil.getLabelFont()

        val column = SettingsUi.column()
        column.add(statusPanel())
        column.add(body)
        column.add(copyRow())
        column.add(SettingsUi.strut(16))

        val page = SettingsUi.page(column)
        root = page
        refresh()
        return page
    }

    private fun statusPanel(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        add(status)
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, com.intellij.util.ui.JBUI.scale(44))
    }

    private fun copyRow(): JComponent {
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.add(copyButton(CommitHelperBundle.message("settings.guide.copyFormat"), "{items}"))
        buttons.add(copyButton(CommitHelperBundle.message("settings.guide.copyItemLine"), "{text}"))
        buttons.add(copyButton(CommitHelperBundle.message("settings.guide.copySample"), CommitHelperBundle.message("settings.guide.sample")))
        buttons.add(copyStatus)
        buttons.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 34)
        return buttons
    }

    private fun copyButton(label: String, value: String): JButton = JButton(label).apply {
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(value))
            copyStatus.text = CommitHelperBundle.message("settings.guide.copied")
        }
    }

    /** Called when the settings tab becomes visible so the self-check is never stale. */
    fun refresh() {
        val issues = SettingsDiagnostics.inspectAll()
        status.text = statusHtml(issues)
        body.text = html(issues)
        body.caretPosition = 0
    }

    private fun statusHtml(issues: List<Diagnostic>): String {
        if (issues.isEmpty()) {
            return "<html><b>" + CommitHelperBundle.message("settings.guide.status.ok") + "</b></html>"
        }
        val errors = issues.count { it.code.severity == DiagnosticSeverity.ERROR }
        val key = if (errors > 0) "settings.guide.status.issues" else "settings.guide.status.warnings"
        return "<html><b>" + CommitHelperBundle.message(key, issues.size) + "</b></html>"
    }

    private fun html(issues: List<Diagnostic>): String {
        val foreground = ColorUtil.toHex(UIUtil.getLabelForeground())
        val html = StringBuilder()
        html.append("<html><head><style>")
        html.append("body{font-family:sans-serif;color:#").append(foreground).append(";margin:0 4px}")
        html.append("h3{margin-top:14px;margin-bottom:4px}li{margin-bottom:2px}")
        html.append("code,pre{font-family:monospace}pre{background:rgba(127,127,127,0.12);padding:6px;white-space:pre-wrap}")
        html.append("</style></head><body>")
        html.append(CommitHelperBundle.message("settings.guide.html"))
        if (issues.isNotEmpty()) {
            html.append("<h3>").append(CommitHelperBundle.message("settings.guide.diagnostics.title")).append("</h3><ul>")
            for (issue in issues) {
                html.append("<li>").append(issueText(issue)).append("</li>")
            }
            html.append("</ul>")
        }
        html.append("</body></html>")
        return html.toString()
    }

    private fun issueText(issue: Diagnostic): String {
        val text = CommitHelperBundle.message("diagnostics." + issue.code.name)
        return issue.projectName
            ?.let { CommitHelperBundle.message("diagnostics.withProject", text, it) }
            ?: text
    }

    override fun isModified(): Boolean = false

    override fun apply() = Unit

    override fun reset() = refresh()

    override fun disposeUIResources() {
        root = null
    }
}
