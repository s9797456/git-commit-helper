package com.caye.commithelper.settings

import com.caye.commithelper.template.DefaultTemplates
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import kotlin.jvm.JvmName

enum class ProviderKind(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "claude-3-5-haiku-latest"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com", "gemini-2.0-flash"),
    LOCAL_ONLY("Local only (no LLM)", "", ""),
}

enum class LanguageOption(val displayName: String) {
    CHINESE("中文"),
    ENGLISH("English"),
    JAPANESE("日本語"),
    KOREAN("한국어"),
    AUTO("Auto (follow repository)"),
}

/** Per-provider connection settings. Each provider keeps its own values. */
class ProviderConfig {
    var baseUrl: String = ""
    var model: String = ""
    var temperature: Double = 0.2

    // 1024 was too small for a detailed multi-file summary: the JSON was cut off mid-object and
    // the whole run fell back to the file/statistics list. Kept per provider so it stays tunable.
    var maxTokens: Int = 4096
    var timeoutSeconds: Int = 120
}

/**
 * Application-wide settings. API keys never live here: they go to PasswordSafe through ApiKeyStore.
 *
 * 0.2.0 moved the single prompt/format pair into reusable PromptProfile and CommitTemplate
 * lists. The old fields stay for one release so existing installs migrate without losing text.
 */
@State(name = "CommitHelperSettings", storages = [Storage("commit-helper.xml")])
class CommitHelperSettings : PersistentStateComponent<CommitHelperSettings.State> {

    class State {
        var provider: ProviderKind = ProviderKind.OPENAI_COMPATIBLE
        var language: LanguageOption = LanguageOption.CHINESE

        var openAi: ProviderConfig = ProviderConfig()
            .also { it.baseUrl = ProviderKind.OPENAI_COMPATIBLE.defaultBaseUrl; it.model = ProviderKind.OPENAI_COMPATIBLE.defaultModel }

        var anthropic: ProviderConfig = ProviderConfig()
            .also { it.baseUrl = ProviderKind.ANTHROPIC.defaultBaseUrl; it.model = ProviderKind.ANTHROPIC.defaultModel }

        var gemini: ProviderConfig = ProviderConfig()
            .also { it.baseUrl = ProviderKind.GEMINI.defaultBaseUrl; it.model = ProviderKind.GEMINI.defaultModel }

        // --- legacy (pre-0.2.0) single templates, read once during migration ---
        var formatTemplate: String = DefaultTemplates.FORMAT
        var itemLineTemplate: String = DefaultTemplates.ITEM_LINE
        var promptTemplate: String = DefaultTemplates.PROMPT

        // --- reusable library ---
        var promptProfiles: MutableList<PromptProfile> = mutableListOf()
        var commitTemplates: MutableList<CommitTemplate> = mutableListOf()
        var commitTypes: MutableList<CommitType> = mutableListOf()

        var defaultPromptProfileId: String = BuiltIns.PROMPT_ID
        var defaultTemplateId: String = BuiltIns.TEMPLATE_ID

        // --- actions / skip CI ---
        var showGenerateAction: Boolean = true
        var showFormatAction: Boolean = false
        var showContextAction: Boolean = true

        var skipCiPresets: MutableList<String> = mutableListOf()
        var skipCiPosition: SkipCiPosition = SkipCiPosition.END

        var confirmBeforeSending: Boolean = false
        var includeDiff: Boolean = true
        var useRepoCommitTemplate: Boolean = true

        var maxLinesPerFile: Int = 200
        var maxTotalChars: Int = 60_000
        var extraExcludes: MutableList<String> = mutableListOf()

        /** Set to true after the one-time legacy template migration. */
        var migratedTo02: Boolean = false

        /** Set after the first commit-panel action showed the onboarding hint. */
        var onboardingShown: Boolean = false
    }

    private var myState = State()

    /**
     * Always returns an initialized state: built-ins exist and legacy templates were migrated.
     * The JVM getter is renamed so it does not clash with PersistentStateComponent.getState().
     */
    @get:JvmName("currentState")
    val state: State
        get() = ensureReady()

    override fun getState(): State = ensureReady()

    override fun loadState(state: State) {
        myState = state
        ensureReady()
    }

    fun configFor(provider: ProviderKind): ProviderConfig = when (provider) {
        ProviderKind.OPENAI_COMPATIBLE -> state.openAi
        ProviderKind.ANTHROPIC -> state.anthropic
        ProviderKind.GEMINI -> state.gemini
        ProviderKind.LOCAL_ONLY -> ProviderConfig()
    }

    fun activeConfig(): ProviderConfig = configFor(state.provider)

    /** Restores the shipped prompt/template content. Custom entries are kept. */
    fun resetTemplates() {
        val current = state
        current.promptProfiles.firstOrNull { it.builtIn }?.let { it.prompt = DefaultTemplates.PROMPT }
        current.commitTemplates.firstOrNull { it.builtIn }?.let {
            it.format = DefaultTemplates.FORMAT
            it.itemLine = DefaultTemplates.ITEM_LINE
        }
        current.defaultPromptProfileId = BuiltIns.PROMPT_ID
        current.defaultTemplateId = BuiltIns.TEMPLATE_ID
    }

    private fun ensureReady(): State {
        migrate(myState)
        return myState
    }

    private fun migrate(state: State) {
        if (state.migratedTo02) {
            ensureBuiltInsAndSelections(state)
            return
        }
        ensureBuiltInsAndSelections(state)

        // The shipped default prompt is one profile; a customized legacy prompt becomes "My prompt"
        // so the user's text is preserved without silently rewriting it.
        if (state.promptProfiles.none { !it.builtIn } &&
            state.promptTemplate.isNotBlank() &&
            state.promptTemplate != DefaultTemplates.PROMPT
        ) {
            val profile = PromptProfile(newSettingsId(), "My prompt", state.promptTemplate)
            state.promptProfiles.add(profile)
            state.defaultPromptProfileId = profile.id
        }

        if (state.commitTemplates.none { !it.builtIn } &&
            (state.formatTemplate != DefaultTemplates.FORMAT || state.itemLineTemplate != DefaultTemplates.ITEM_LINE)
        ) {
            val template = CommitTemplate(
                id = newSettingsId(),
                name = "My template",
                format = state.formatTemplate,
                itemLine = state.itemLineTemplate,
            )
            state.commitTemplates.add(template)
            state.defaultTemplateId = template.id
        }

        state.migratedTo02 = true
    }

    /** Repairs missing built-ins after a manual XML edit or an interrupted migration. */
    private fun ensureBuiltInsAndSelections(state: State) {
        if (state.promptProfiles.none { it.id == BuiltIns.PROMPT_ID }) {
            state.promptProfiles.add(0, BuiltIns.promptProfile())
        }
        if (state.commitTemplates.none { it.id == BuiltIns.TEMPLATE_ID }) {
            state.commitTemplates.add(0, BuiltIns.template())
        }
        if (state.commitTypes.isEmpty()) {
            state.commitTypes.addAll(BuiltIns.commitTypes())
        }
        if (state.skipCiPresets.isEmpty()) {
            state.skipCiPresets.addAll(BuiltIns.skipCiPresets())
        }
        if (state.promptProfiles.none { it.id == state.defaultPromptProfileId }) {
            state.defaultPromptProfileId = BuiltIns.PROMPT_ID
        }
        if (state.commitTemplates.none { it.id == state.defaultTemplateId }) {
            state.defaultTemplateId = BuiltIns.TEMPLATE_ID
        }
    }

    companion object {
        fun getInstance(): CommitHelperSettings =
            ApplicationManager.getApplication().service<CommitHelperSettings>()
    }
}

/** Reads/writes API keys through the IDE credential store. Never touches XML settings. */
object ApiKeyStore {

    // NOTE: the Plugin Verifier reports one deprecated API usage for this constructor
    // (requestor-taking overloads of CredentialAttributes are deprecated). There is no
    // non-deprecated way to build the attributes object for PasswordSafe; verification stays
    // "Compatible" on 241/243/262 and the usage is recorded in SPEC.md section 11.
    private fun attributes(provider: ProviderKind) =
        CredentialAttributes("CommitHelper:" + provider.name, null)

    fun get(provider: ProviderKind): String? =
        PasswordSafe.instance.getPassword(attributes(provider))?.takeIf { it.isNotBlank() }

    fun set(provider: ProviderKind, key: String?) {
        PasswordSafe.instance.setPassword(attributes(provider), key?.takeIf { it.isNotBlank() })
    }
}