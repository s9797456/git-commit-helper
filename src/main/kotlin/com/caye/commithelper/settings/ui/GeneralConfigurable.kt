package com.caye.commithelper.settings.ui

import com.caye.commithelper.i18n.CommitHelperBundle
import com.caye.commithelper.i18n.localizedName
import com.caye.commithelper.settings.CommitHelperSettings
import com.caye.commithelper.settings.LanguageOption
import com.caye.commithelper.settings.SkipCiPosition
import com.intellij.openapi.options.Configurable
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JTextField

/**
 * Application settings page: output language, behaviour toggles, action visibility, Skip CI
 * presets/placement and the advanced generation limits. All values are written to
 * [CommitHelperSettings] on apply; this page is application-scoped and never needs a project.
 */
class GeneralConfigurable : Configurable {

    private val settings = CommitHelperSettings.getInstance()

    private val languageBox = JComboBox<LanguageOption>(LanguageOption.entries.toTypedArray())
    private val confirmSending = JCheckBox(CommitHelperBundle.message("settings.general.confirmSending"))
    private val includeDiff = JCheckBox(CommitHelperBundle.message("settings.general.includeDiff"))
    private val useRepoTemplate = JCheckBox(CommitHelperBundle.message("settings.general.useRepoTemplate"))

    private val showGenerate = JCheckBox(
        CommitHelperBundle.message("settings.general.showGenerate", CommitHelperBundle.message("action.generate.text")),
    )
    private val showFormat = JCheckBox(
        CommitHelperBundle.message("settings.general.showFormat", CommitHelperBundle.message("action.format.text")),
    )
    private val showContext = JCheckBox(
        CommitHelperBundle.message("settings.general.showContext", CommitHelperBundle.message("action.context.text")),
    )

    private val skipCiPositionBox = JComboBox<SkipCiPosition>(SkipCiPosition.entries.toTypedArray())
    private val presetsArea = SettingsUi.textArea(4, 40)

    private val maxLinesField = JTextField(6)
    private val maxCharsField = JTextField(8)
    private val excludesArea = SettingsUi.textArea(4, 40)

    private var root: JComponent? = null
    private var snapshot: List<Any?>? = null

    override fun getDisplayName(): String = CommitHelperBundle.message("settings.general.title")

    override fun createComponent(): JComponent {
        root?.let { return it }
        languageBox.renderer = NamedRenderer { value ->
            (value as? LanguageOption)?.localizedName() ?: value?.toString()
        }
        skipCiPositionBox.renderer = NamedRenderer { value ->
            when (value) {
                SkipCiPosition.END -> CommitHelperBundle.message("settings.general.skipCiEnd")
                SkipCiPosition.SUBJECT_PREFIX -> CommitHelperBundle.message("settings.general.skipCiPrefix")
                else -> value?.toString()
            }
        }

        val column = SettingsUi.column()
        column.add(SettingsUi.section(CommitHelperBundle.message("settings.general.section.language")))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.general.language"), languageBox))
        column.add(SettingsUi.leftAligned(confirmSending))
        column.add(SettingsUi.leftAligned(includeDiff))
        column.add(SettingsUi.leftAligned(useRepoTemplate))

        column.add(SettingsUi.section(CommitHelperBundle.message("settings.general.section.actions")))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.general.actionsHint")))
        column.add(SettingsUi.leftAligned(showGenerate))
        column.add(SettingsUi.leftAligned(showFormat))
        column.add(SettingsUi.leftAligned(showContext))

        column.add(SettingsUi.section(CommitHelperBundle.message("settings.general.section.skipCi")))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.general.skipCiHint")))
        column.add(SettingsUi.scrolling(presetsArea, 100))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.general.skipCiPlacement"), skipCiPositionBox))

        column.add(SettingsUi.section(CommitHelperBundle.message("settings.general.section.limits")))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.general.maxLines"), maxLinesField))
        column.add(SettingsUi.row(CommitHelperBundle.message("settings.general.maxChars"), maxCharsField))
        column.add(SettingsUi.hint(CommitHelperBundle.message("settings.general.excludesHint")))
        column.add(SettingsUi.scrolling(excludesArea, 100))
        column.add(SettingsUi.strut(16))

        val page = SettingsUi.page(column)
        root = page
        reset()
        return page
    }

    override fun isModified(): Boolean {
        val saved = snapshot ?: return false
        return currentValues() != saved
    }

    override fun apply() {
        val state = settings.state
        state.language = languageBox.selectedItem as? LanguageOption ?: LanguageOption.CHINESE
        state.confirmBeforeSending = confirmSending.isSelected
        state.includeDiff = includeDiff.isSelected
        state.useRepoCommitTemplate = useRepoTemplate.isSelected
        state.showGenerateAction = showGenerate.isSelected
        state.showFormatAction = showFormat.isSelected
        state.showContextAction = showContext.isSelected
        state.skipCiPresets = presetsArea.text.lines().map { it.trim() }
            .filter { it.isNotEmpty() }.toMutableList()
        state.skipCiPosition = skipCiPositionBox.selectedItem as? SkipCiPosition ?: SkipCiPosition.END
        state.maxLinesPerFile = maxLinesField.text.trim().toIntOrNull()?.coerceAtLeast(1) ?: 200
        state.maxTotalChars = maxCharsField.text.trim().toIntOrNull()?.coerceAtLeast(2000) ?: 60_000
        state.extraExcludes = excludesArea.text.lines().map { it.trim() }
            .filter { it.isNotEmpty() }.toMutableList()
        snapshot = currentValues()
    }

    override fun reset() {
        val state = settings.state
        languageBox.selectedItem = state.language
        confirmSending.isSelected = state.confirmBeforeSending
        includeDiff.isSelected = state.includeDiff
        useRepoTemplate.isSelected = state.useRepoCommitTemplate
        showGenerate.isSelected = state.showGenerateAction
        showFormat.isSelected = state.showFormatAction
        showContext.isSelected = state.showContextAction
        presetsArea.text = state.skipCiPresets.joinToString("\n")
        skipCiPositionBox.selectedItem = state.skipCiPosition
        maxLinesField.text = state.maxLinesPerFile.toString()
        maxCharsField.text = state.maxTotalChars.toString()
        excludesArea.text = state.extraExcludes.joinToString("\n")
        snapshot = currentValues()
    }

    override fun disposeUIResources() {
        root = null
    }

    private fun currentValues(): List<Any?> = listOf(
        languageBox.selectedItem,
        confirmSending.isSelected,
        includeDiff.isSelected,
        useRepoTemplate.isSelected,
        showGenerate.isSelected,
        showFormat.isSelected,
        showContext.isSelected,
        presetsArea.text,
        skipCiPositionBox.selectedItem,
        maxLinesField.text,
        maxCharsField.text,
        excludesArea.text,
    )
}
