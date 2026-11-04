package com.caye.commithelper.notify

import com.caye.commithelper.settings.CommitHelperSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** The onboarding flag is persisted; the notification itself is not asserted (no UI in tests). */
class OnboardingNotifierTest : BasePlatformTestCase() {

    fun testFirstActionMarksOnboardingAsShown() {
        val state = CommitHelperSettings.getInstance().state
        val old = state.onboardingShown
        try {
            state.onboardingShown = false
            OnboardingNotifier.onActionStarted(project)
            assertTrue("the first action must set the persisted onboarding flag", state.onboardingShown)
            OnboardingNotifier.onActionStarted(project)
            assertTrue(state.onboardingShown)
        } finally {
            state.onboardingShown = old
        }
    }
}
