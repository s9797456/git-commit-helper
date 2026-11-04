package com.caye.commithelper.settings.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.i18n.localizedName
import com.caye.commithelper.llm.LlmClient
import com.caye.commithelper.llm.LlmProtocols
import com.caye.commithelper.settings.ApiKeyStore
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.ProviderConfig
import com.caye.commithelper.settings.ProviderKind
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Application settings page for the optional LLM. Each provider keeps its own connection values;
 * API keys never go into the XML settings and are stored through [ApiKeyStore] (IDE credential
 * store). The "Test connection" button performs one tiny request with the values shown on screen.
 */
class LlmConfigurable : Configurable {

    private val settings = CommitHelperSettings.getInstance()

    private val providerBox = JComboBox<ProviderKind>(ProviderKind.entries.toTypedArray())
    private val baseUrlField = JTextField(32)
    private val modelField = JTextField(24)
    private val apiKeyField = JBPasswordField()
    private val temperatureField = JTextField(6)
    private val maxTokensField = JTextField(8)
    private val timeoutField = JTextField(6)

    private val testResult = JBLabel(" ")
    private val testButton = JButton(CommitHelperBundle.message("settings.llm.test"))

    private var root: JComponent? = null
    private var snapshot: LlmSnapshot? = null
    private var displayedProvider: ProviderKind = ProviderKind.OPENAI_COMPATIBLE
    private var updating = false
    private var listenersWired = false

    private val providerValues = LinkedHashMap<ProviderKind, ProviderValues>()

    override fun getDisplayName(): String = CommitHelperBundle.message("settings.llm.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        providerBox.renderer = NamedRenderer { value ->
            (value as? ProviderKind)?.localizedName() ?: value?.toString()
        }
        wireListeners()

        val column = SettingsUi.column()
        column.add(SettingsUi.section(CommitHelperBundle.message("settings.llm.section.connection")))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.llm.connectionHint")))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.llm.provider"), providerBox))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.llm.baseUrl"), baseUrlField))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.llm.model"), modelField))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.llm.apiKey"), apiKeyField))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.llm.temperature"), temperatureField))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.llm.maxTokens"), maxTokensField))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.llm.timeout"), timeoutField))

        val testRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(testButton)
            add(testResult)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 32)
        }
        column.add(testRow)
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.llm.keyHint")))
        column.add(SettingsUi.strut(16))

        val page = SettingsUi.page(column)
        root = page
        reset()
        return page
    }

    private fun wireListeners() {
        if (listenersWired) return
        listenersWired = true
        providerBox.addActionListener { if (!updating) onProviderChanged() }
        testButton.addActionListener { runTest() }
    }

    private fun onProviderChanged() {
        val selected = providerBox.selectedItem as? ProviderKind ?: return
        if (selected == displayedProvider) return
        captureFields(displayedProvider)
        displayedProvider = selected
        loadFields(selected)
    }

    private fun captureFields(kind: ProviderKind) {
        if (kind == ProviderKind.LOCAL_ONLY) return
        providerValues[kind] = ProviderValues(
            baseUrl = baseUrlField.text.trim(),
            model = modelField.text.trim(),
            apiKey = String(apiKeyField.password),
            temperature = temperatureField.text.trim(),
            maxTokens = maxTokensField.text.trim(),
            timeout = timeoutField.text.trim(),
        )
    }

    private fun loadFields(kind: ProviderKind) {
        val values = providerValues.getOrPut(kind) { readFromSettings(kind) }
        baseUrlField.text = values.baseUrl
        modelField.text = values.model
        apiKeyField.text = values.apiKey
        temperatureField.text = values.temperature
        maxTokensField.text = values.maxTokens
        timeoutField.text = values.timeout
        val editable = kind != ProviderKind.LOCAL_ONLY
        baseUrlField.isEnabled = editable
        modelField.isEnabled = editable
        apiKeyField.isEnabled = editable
        temperatureField.isEnabled = editable
        maxTokensField.isEnabled = editable
        timeoutField.isEnabled = editable
        testButton.isEnabled = editable
        testResult.text = " "
    }

    private fun readFromSettings(kind: ProviderKind): ProviderValues {
        if (kind == ProviderKind.LOCAL_ONLY) {
            return ProviderValues("", "", "", "0.2", "4096", "120")
        }
        val config = settings.configFor(kind)
        return ProviderValues(
            baseUrl = config.baseUrl,
            model = config.model,
            apiKey = ApiKeyStore.get(kind).orEmpty(),
            temperature = config.temperature.toString(),
            maxTokens = config.maxTokens.toString(),
            timeout = config.timeoutSeconds.toString(),
        )
    }

    private fun runTest() {
        val kind = providerBox.selectedItem as? ProviderKind ?: return
        if (kind == ProviderKind.LOCAL_ONLY) {
            testResult.text = CommitHelperBundle.message("settings.llm.test.localOnly")
            return
        }
        val protocol = LlmProtocols.of(kind) ?: return
        captureFields(displayedProvider)
        val values = providerValues[kind] ?: readFromSettings(kind)
        if (values.apiKey.isBlank()) {
            testResult.text = CommitHelperBundle.message("settings.llm.test.noApiKey")
            return
        }
        val config = ProviderConfig().apply {
            baseUrl = values.baseUrl
            model = values.model
            temperature = values.temperature.toDoubleOrNull() ?: 0.2
            maxTokens = values.maxTokens.toIntOrNull() ?: 4096
            timeoutSeconds = values.timeout.toIntOrNull() ?: 120
        }
        val apiKey = values.apiKey
        testResult.text = CommitHelperBundle.message("settings.llm.test.running")
        object : Task.Backgroundable(
            null,
            CommitHelperBundle.message("settings.llm.test.taskTitle", kind.localizedName()),
            true,
        ) {
            override fun run(indicator: ProgressIndicator) {
                val text = try {
                    val reply = LlmClient(protocol, config, apiKey)
                        .complete("Reply with the single word: ok", maxTokens = 16, temperature = 0.0)
                    CommitHelperBundle.message("settings.llm.test.ok", reply.trim().take(60))
                } catch (e: Exception) {
                    CommitHelperBundle.message("settings.llm.test.failed", e.message ?: e.javaClass.simpleName)
                }
                ApplicationManager.getApplication().invokeLater { testResult.text = text }
            }
        }.queue()
    }

    override fun isModified(): Boolean {
        val saved = snapshot ?: return false
        return currentSnapshot() != saved
    }

    override fun apply() {
        captureFields(displayedProvider)
        for ((kind, values) in providerValues) {
            if (kind == ProviderKind.LOCAL_ONLY) continue
            val config = settings.configFor(kind)
            config.baseUrl = values.baseUrl
            config.model = values.model
            config.temperature = values.temperature.toDoubleOrNull() ?: config.temperature
            config.maxTokens = values.maxTokens.toIntOrNull() ?: config.maxTokens
            config.timeoutSeconds = values.timeout.toIntOrNull() ?: config.timeoutSeconds
            ApiKeyStore.set(kind, values.apiKey)
        }
        settings.state.provider = providerBox.selectedItem as? ProviderKind ?: ProviderKind.OPENAI_COMPATIBLE
        snapshot = currentSnapshot()
    }

    override fun reset() {
        val state = settings.state
        providerValues.clear()
        providerValues[ProviderKind.OPENAI_COMPATIBLE] = readFromSettings(ProviderKind.OPENAI_COMPATIBLE)
        providerValues[ProviderKind.ANTHROPIC] = readFromSettings(ProviderKind.ANTHROPIC)
        providerValues[ProviderKind.GEMINI] = readFromSettings(ProviderKind.GEMINI)
        updating = true
        providerBox.selectedItem = state.provider
        updating = false
        displayedProvider = state.provider
        loadFields(state.provider)
        snapshot = currentSnapshot()
    }

    override fun disposeUIResources() {
        root = null
    }

    private fun currentSnapshot(): LlmSnapshot {
        captureFields(displayedProvider)
        return LlmSnapshot(
            provider = providerBox.selectedItem as? ProviderKind ?: ProviderKind.OPENAI_COMPATIBLE,
            values = LinkedHashMap(providerValues),
        )
    }

    private data class ProviderValues(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val temperature: String,
        val maxTokens: String,
        val timeout: String,
    )

    private data class LlmSnapshot(
        val provider: ProviderKind,
        val values: Map<ProviderKind, ProviderValues>,
    )
}
