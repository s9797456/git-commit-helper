package com.caye.commithelper.settings

import com.caye.commithelper.llm.LlmClient
import com.caye.commithelper.llm.LlmProtocols
import com.caye.commithelper.template.DefaultTemplates
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Settings | Tools | Commit Helper. Built with plain Swing so it stays compatible across
 * platform versions; API keys are handled through [ApiKeyStore] (IDE credential store).
 */
class CommitHelperConfigurable : Configurable {

    private val settings = CommitHelperSettings.getInstance()

    private var root: JPanel? = null
    private var snapshot: List<Any?> = emptyList()
    private var displayedProvider: ProviderKind = ProviderKind.OPENAI_COMPATIBLE

    private val providerBox = JComboBox(ProviderKind.entries.toTypedArray())
    private val baseUrlField = JTextField(32)
    private val modelField = JTextField(24)
    private val apiKeyField = JBPasswordField()
    private val temperatureField = JTextField(6)
    private val maxTokensField = JTextField(8)
    private val timeoutField = JTextField(6)

    private val languageBox = JComboBox(LanguageOption.entries.toTypedArray())
    private val confirmSending = JCheckBox("Confirm before sending code to the model")
    private val includeDiff = JCheckBox("Include diff content (unchecked: file list and statistics only)")
    private val useRepoTemplate = JCheckBox("Use repository commit template (.gitmessage) as the skeleton")

    private val formatArea = JBTextArea(6, 60)
    private val itemLineField = JTextField(60)
    private val promptArea = JBTextArea(12, 60)
    private val presetBox = JComboBox(DefaultTemplates.FormatPreset.entries.toTypedArray())

    private val maxLinesField = JTextField(6)
    private val maxCharsField = JTextField(8)
    private val excludesArea = JBTextArea(4, 40)

    private val testResult = JBLabel(" ")
    private val testButton = JButton("Test connection")

    override fun getDisplayName(): String = "Commit Helper"

    override fun createComponent(): JComponent {
        val column = JPanel()
        column.layout = BoxLayout(column, BoxLayout.Y_AXIS)

        column.add(section("Model provider"))
        column.add(row("Provider", providerBox))
        column.add(row("Base URL", baseUrlField))
        column.add(row("Model", modelField))
        column.add(row("API key", apiKeyField))
        column.add(row("Temperature", temperatureField))
        column.add(row("Max output tokens", maxTokensField))
        column.add(row("Timeout (seconds)", timeoutField))

        val testRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        testRow.add(testButton)
        testRow.add(testResult)
        column.add(testRow)

        column.add(section("Behavior"))
        column.add(leftAligned(confirmSending))
        column.add(leftAligned(includeDiff))
        column.add(leftAligned(useRepoTemplate))
        column.add(row("Language", languageBox))

        column.add(section("Message format template"))
        column.add(hint("Placeholders: {subject} {items} {issue} {skeleton}; per item: {type} {scope} {scopeRaw} {text}"))
        column.add(row("Preset", presetBox))
        column.add(scrolling(formatArea, 130))
        column.add(row("Item line", itemLineField))

        column.add(section("Prompt template"))
        column.add(hint("Placeholders: {language} {format} {skeleton} {diff} {fileList}"))
        column.add(scrolling(promptArea, 240))

        val resetRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4)))
        resetRow.add(JButton("Reset templates to default").apply {
            addActionListener { resetTemplates() }
        })
        column.add(resetRow)

        column.add(section("Limits (advanced)"))
        column.add(row("Max lines per file", maxLinesField))
        column.add(row("Total character budget", maxCharsField))
        column.add(hint("Additional exclude globs, one per line (e.g. **/generated/**, *.pb.go)"))
        column.add(scrolling(excludesArea, 90))
        column.add(Box.createVerticalStrut(JBUI.scale(12)))

        root = JPanel(BorderLayout()).apply {
            add(JBScrollPane(column), BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(560))
        }

        wireListeners()
        reset()
        return root!!
    }

    private fun wireListeners() {
        providerBox.addActionListener { onProviderChanged() }
        presetBox.addActionListener {
            (presetBox.selectedItem as? DefaultTemplates.FormatPreset)?.let { preset ->
                formatArea.text = preset.format
                itemLineField.text = preset.itemLine
            }
        }
        testButton.addActionListener { runTest() }
    }

    private fun onProviderChanged() {
        val selected = providerBox.selectedItem as? ProviderKind ?: return
        if (selected == displayedProvider) return
        storeProviderFields(displayedProvider)
        displayedProvider = selected
        loadProviderFields(selected)
    }

    private fun storeProviderFields(kind: ProviderKind) {
        if (kind == ProviderKind.LOCAL_ONLY) return
        val config = settings.configFor(kind)
        config.baseUrl = baseUrlField.text.trim()
        config.model = modelField.text.trim()
        config.temperature = temperatureField.text.trim().toDoubleOrNull() ?: config.temperature
        config.maxTokens = maxTokensField.text.trim().toIntOrNull() ?: config.maxTokens
        config.timeoutSeconds = timeoutField.text.trim().toIntOrNull() ?: config.timeoutSeconds
        ApiKeyStore.set(kind, String(apiKeyField.password))
    }

    private fun loadProviderFields(kind: ProviderKind) {
        val config = settings.configFor(kind)
        baseUrlField.text = config.baseUrl
        modelField.text = config.model
        apiKeyField.text = ApiKeyStore.get(kind).orEmpty()
        temperatureField.text = config.temperature.toString()
        maxTokensField.text = config.maxTokens.toString()
        timeoutField.text = config.timeoutSeconds.toString()
        val editable = kind != ProviderKind.LOCAL_ONLY
        baseUrlField.isEnabled = editable
        modelField.isEnabled = editable
        apiKeyField.isEnabled = editable
        temperatureField.isEnabled = editable
        maxTokensField.isEnabled = editable
        timeoutField.isEnabled = editable
        testButton.isEnabled = editable
    }

    private fun runTest() {
        val kind = providerBox.selectedItem as? ProviderKind ?: return
        val protocol = LlmProtocols.of(kind) ?: return
        storeProviderFields(kind)
        val config = settings.configFor(kind)
        val apiKey = ApiKeyStore.get(kind).orEmpty()
        if (apiKey.isBlank()) {
            testResult.text = "No API key"
            return
        }
        testResult.text = "Testing..."
        object : Task.Backgroundable(null, "Testing ${protocol.displayName} connection", true) {
            override fun run(indicator: ProgressIndicator) {
                val text = try {
                    val reply = LlmClient(protocol, config, apiKey)
                        .complete("Reply with the single word: ok", maxTokens = 16, temperature = 0.0)
                    "OK: " + reply.trim().take(60)
                } catch (e: Exception) {
                    "Failed: " + (e.message ?: e.javaClass.simpleName)
                }
                ApplicationManager.getApplication().invokeLater { testResult.text = text }
            }
        }.queue()
    }

    override fun isModified(): Boolean = currentValues() != snapshot

    override fun apply() {
        storeProviderFields(displayedProvider)
        settings.state.provider = providerBox.selectedItem as? ProviderKind ?: ProviderKind.OPENAI_COMPATIBLE
        settings.state.language = languageBox.selectedItem as? LanguageOption ?: LanguageOption.CHINESE
        settings.state.formatTemplate = formatArea.text
        settings.state.itemLineTemplate = itemLineField.text
        settings.state.promptTemplate = promptArea.text
        settings.state.confirmBeforeSending = confirmSending.isSelected
        settings.state.includeDiff = includeDiff.isSelected
        settings.state.useRepoCommitTemplate = useRepoTemplate.isSelected
        settings.state.maxLinesPerFile = maxLinesField.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: 200
        settings.state.maxTotalChars = maxCharsField.text.trim().toIntOrNull()?.coerceAtLeast(2000) ?: 60_000
        settings.state.extraExcludes = excludesArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        snapshot = currentValues()
    }

    override fun reset() {
        val state = settings.state
        providerBox.selectedItem = state.provider
        displayedProvider = state.provider
        loadProviderFields(state.provider)
        languageBox.selectedItem = state.language
        formatArea.text = state.formatTemplate
        itemLineField.text = state.itemLineTemplate
        promptArea.text = state.promptTemplate
        confirmSending.isSelected = state.confirmBeforeSending
        includeDiff.isSelected = state.includeDiff
        useRepoTemplate.isSelected = state.useRepoCommitTemplate
        maxLinesField.text = state.maxLinesPerFile.toString()
        maxCharsField.text = state.maxTotalChars.toString()
        excludesArea.text = state.extraExcludes.joinToString("\n")
        testResult.text = " "
        snapshot = currentValues()
    }

    override fun disposeUIResources() {
        root = null
    }

    private fun resetTemplates() {
        formatArea.text = DefaultTemplates.FORMAT
        itemLineField.text = DefaultTemplates.ITEM_LINE
        promptArea.text = DefaultTemplates.PROMPT
    }

    private fun currentValues(): List<Any?> = listOf(
        providerBox.selectedItem,
        baseUrlField.text,
        modelField.text,
        String(apiKeyField.password),
        temperatureField.text,
        maxTokensField.text,
        timeoutField.text,
        languageBox.selectedItem,
        formatArea.text,
        itemLineField.text,
        promptArea.text,
        confirmSending.isSelected,
        includeDiff.isSelected,
        useRepoTemplate.isSelected,
        maxLinesField.text,
        maxCharsField.text,
        excludesArea.text,
    )

    private fun section(title: String): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(10)
        add(JBLabel("<html><b>$title</b></html>"), BorderLayout.WEST)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
    }

    private fun hint(text: String): JComponent = JBLabel("<html><i>$text</i></html>").apply {
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        border = JBUI.Borders.emptyLeft(4)
    }

    private fun leftAligned(component: JComponent): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        add(component)
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
    }

    private fun row(label: String, component: JComponent): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        val labelComponent = JBLabel(label)
        labelComponent.preferredSize = Dimension(JBUI.scale(160), labelComponent.preferredSize.height)
        panel.add(labelComponent, BorderLayout.WEST)
        panel.add(component, BorderLayout.CENTER)
        panel.maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
        return panel
    }

    private fun scrolling(area: JBTextArea, height: Int): JComponent {
        area.lineWrap = true
        area.wrapStyleWord = true
        area.font = JTextField().font
        return JBScrollPane(area).apply {
            preferredSize = Dimension(JBUI.scale(600), JBUI.scale(height))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(height + 8))
        }
    }

    private companion object {
        @Suppress("unused")
        fun anyOpenProject() = ProjectManager.getInstance().openProjects.firstOrNull()
    }
}
