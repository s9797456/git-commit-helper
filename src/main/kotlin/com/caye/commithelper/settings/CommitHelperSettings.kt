package com.caye.commithelper.settings

import com.caye.commithelper.template.DefaultTemplates
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

enum class ProviderKind(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com", "claude-3-5-haiku-latest"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com", "gemini-2.0-flash"),
    LOCAL_ONLY("Local only (no LLM)", "", ""),
}

enum class LanguageOption(val displayName: String) {
    CHINESE("中文"),
    ENGLISH("English"),
    AUTO("Auto (follow repository)"),
}

/** Per-provider connection settings. Each provider keeps its own values. */
class ProviderConfig {
    var baseUrl: String = ""
    var model: String = ""
    var temperature: Double = 0.2
    var maxTokens: Int = 1024
    var timeoutSeconds: Int = 60
}

/**
 * Application-wide settings. `API keys` never live here: they go to [PasswordSafe]
 * through [ApiKeyStore].
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

        var formatTemplate: String = DefaultTemplates.FORMAT
        var itemLineTemplate: String = DefaultTemplates.ITEM_LINE
        var promptTemplate: String = DefaultTemplates.PROMPT

        var confirmBeforeSending: Boolean = false
        var includeDiff: Boolean = true
        var useRepoCommitTemplate: Boolean = true

        var maxLinesPerFile: Int = 200
        var maxTotalChars: Int = 60_000
        var extraExcludes: MutableList<String> = mutableListOf()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun configFor(provider: ProviderKind): ProviderConfig = when (provider) {
        ProviderKind.OPENAI_COMPATIBLE -> myState.openAi
        ProviderKind.ANTHROPIC -> myState.anthropic
        ProviderKind.GEMINI -> myState.gemini
        ProviderKind.LOCAL_ONLY -> ProviderConfig()
    }

    fun activeConfig(): ProviderConfig = configFor(myState.provider)

    fun resetTemplates() {
        myState.formatTemplate = DefaultTemplates.FORMAT
        myState.itemLineTemplate = DefaultTemplates.ITEM_LINE
        myState.promptTemplate = DefaultTemplates.PROMPT
    }

    companion object {
        fun getInstance(): CommitHelperSettings =
            ApplicationManager.getApplication().service<CommitHelperSettings>()
    }
}

/** Reads/writes API keys through the IDE credential store. Never touches XML settings. */
object ApiKeyStore {

    // NOTE: the Plugin Verifier reports one deprecated API usage for this constructor
    // (`requestor`-taking overloads of CredentialAttributes are deprecated). There is no
    // non-deprecated way to build the attributes object for PasswordSafe; verification stays
    // "Compatible" on 241/243/262 and the usage is recorded in SPEC.md §11.
    private fun attributes(provider: ProviderKind) =
        CredentialAttributes("CommitHelper:${provider.name}", null)

    fun get(provider: ProviderKind): String? =
        PasswordSafe.instance.getPassword(attributes(provider))?.takeIf { it.isNotBlank() }

    fun set(provider: ProviderKind, key: String?) {
        PasswordSafe.instance.setPassword(attributes(provider), key?.takeIf { it.isNotBlank() })
    }
}
