package com.caye.commithelper.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-project choices. Deliberately stores only references to the global library and the last
 * extra-context input; the profiles/templates themselves stay global so .idea files stay small.
 */
@State(name = "CommitHelperProjectSettings", storages = [Storage("commit-helper.xml")])
class ProjectCommitHelperSettings : PersistentStateComponent<ProjectCommitHelperSettings.State> {

    class State {
        /** Empty means "inherit the global default". */
        var promptProfileId: String = ""
        /** Empty means "inherit the global default". */
        var templateId: String = ""

        var lastExtraIssue: String = ""
        var lastExtraNote: String = ""
        var lastSkipCiPreset: String = ""
        var lastSkipCiPosition: SkipCiPosition = SkipCiPosition.END
        var lastSkipCiEnabled: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): ProjectCommitHelperSettings =
            project.service<ProjectCommitHelperSettings>()
    }
}
