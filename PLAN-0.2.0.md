# 0.2.0 Implementation Plan (scope B)

Decisions confirmed by user (2026-11-02):
1. Project config: global library only; project stores ONLY default selection (prompt profile id, template id). Project selection > global default > built-in.
2. Format action: LLM parses current message -> structured JSON -> render with selected template; failure leaves text untouched.
3. i18n: UI in EN/ZH/JA/KO; generation language adds Japanese/Korean (+ Auto detection).
4. Settings: 3 application pages via Configurable.Composite (General / LLM / Commit Content) + 1 project-level page (Project Defaults).
5. Extra-context: dialog with issue links, free-form note, Skip CI presets; prefill issue candidates from branch name and last used values (per project).
6. Skip CI: configurable presets; placement configurable: end of message or subject prefix.

## Data
- PromptProfile(id, name, prompt, builtIn)
- CommitTemplate(id, name, format, itemLine, builtIn)
- CommitType(type, description)
- SkipCiPosition { END, SUBJECT_PREFIX }
- App state adds: promptProfiles, templates, commitTypes, defaultPromptProfileId, defaultTemplateId, skipCiPresets, skipCiPosition, showGenerateAction, showFormatAction, showContextAction.
- Legacy fields formatTemplate/itemLineTemplate/promptTemplate stay for migration only.
- Project state: promptProfileId, templateId, lastExtraIssue, lastExtraNote, lastSkipCiPreset, lastSkipCiPosition, lastSkipCiEnabled. Stored in .idea/commit-helper.xml.

## Prompt
- PromptBuilder adds {types} and {extraContext} placeholders; appends type list / extra context / detail rules when placeholders absent.
- Format uses fixed FormatPromptBuilder + selected profile prompt as style guidance + selected template.
- Output language: OutputLanguage enum (CHINESE/ENGLISH/JAPANESE/KOREAN); AUTO detects from history (kana=>JA, hangul=>KO, CJK=>ZH else EN).
- Local fallback gets 4-language wording (basic JA/KO translations).

## Actions
- Generate (existing Organize, visible by default)
- Generate with Context (visible by default; new SVG icon; extra dialog; skip CI)
- Format (hidden by default; LLM)
- Visibility toggles via presentation.isVisible from settings; enabled only when project/message available.
- Skip CI applied locally after render.

## Settings UI
- CommitHelperRootConfigurable : Configurable.Composite -> General, LLM, Commit Content.
- ProjectDefaultsConfigurable (projectConfigurable) -> prompt/template selection.
- Commit Content: prompt profiles CRUD + editor; template CRUD + editor + preview split; commit types table.
- Localized via CommitHelperBundle.

## Files / ownership
Lead: settings core, project service, effective settings, model/SkipCi, template/*, service, action/*, ui dialogs, plugin.xml, build.
Teammate settings-ui: settings/ui/* (new files) + old CommitHelperConfigurable becomes root; may extend messages base bundle with new keys.
Teammate tests: new test files only (pure logic) + updates to existing tests after core stable.
Teammate i18n: messages/CommitHelperBundle_zh|ja|ko.properties after UI strings frozen; verify key parity.
