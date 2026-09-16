package com.caye.commithelper.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The settings page is plain Swing but it is only ever constructed when a user opens
 * Settings, so nothing else in the build would catch a broken field, a bad template default
 * or a credential-store failure. These tests instantiate and round-trip it headlessly.
 */
class SettingsUiTest : BasePlatformTestCase() {

    fun testConfigurableBuildsItsComponentAndRoundTripsState() {
        val configurable = CommitHelperConfigurable()
        try {
            val component = configurable.createComponent()
            assertNotNull("createComponent must return a component", component)
            assertTrue("component should have children", component.componentCount > 0)
            assertEquals("Commit Helper", configurable.displayName)

            // A freshly reset page must not look modified, and applying it must be a no-op.
            configurable.reset()
            assertFalse("reset() must restore the stored state", configurable.isModified())
            configurable.apply()
            assertFalse("apply() with no edits must leave the state unmodified", configurable.isModified())
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testAppliedStateSurvivesASecondConfigurableInstance() {
        val first = CommitHelperConfigurable()
        val second = CommitHelperConfigurable()
        val settings = CommitHelperSettings.getInstance()
        val originalLanguage = settings.state.language
        val originalProvider = settings.state.provider
        try {
            first.createComponent()
            first.reset()
            settings.state.language = LanguageOption.CHINESE
            settings.state.provider = ProviderKind.LOCAL_ONLY

            // A new page instance must read the persisted values back.
            second.createComponent()
            second.reset()
            second.apply()
            assertEquals(LanguageOption.CHINESE, settings.state.language)
            assertEquals(ProviderKind.LOCAL_ONLY, settings.state.provider)
        } finally {
            settings.state.language = originalLanguage
            settings.state.provider = originalProvider
            first.disposeUIResources()
            second.disposeUIResources()
        }
    }

    fun testApiKeyStoreIsSafeForEveryProvider() {
        // Must not throw even with no credential store entry present, and must round-trip.
        for (provider in ProviderKind.entries) {
            val existing = ApiKeyStore.get(provider)
            try {
                ApiKeyStore.set(provider, "test-key-${provider.name}")
                assertEquals("test-key-${provider.name}", ApiKeyStore.get(provider))
            } finally {
                if (existing == null) ApiKeyStore.set(provider, null) else ApiKeyStore.set(provider, existing)
            }
        }
    }
}
