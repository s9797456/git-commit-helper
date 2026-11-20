package com.caye.commithelper.action

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class SplitPushActionWiringTest : BasePlatformTestCase() {
    fun testIntelliJNativePushActionIsAvailable() {
        assertNotNull(
            "Split push must use the IDE action id Vcs.Push",
            ActionManager.getInstance().getAction("Vcs.Push"),
        )
    }
}
