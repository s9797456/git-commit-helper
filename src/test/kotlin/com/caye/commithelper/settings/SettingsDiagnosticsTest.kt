package com.caye.commithelper.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Pure-ish tests for the settings self-check; they never touch the UI. */
class SettingsDiagnosticsTest : BasePlatformTestCase() {

    private lateinit var state: CommitHelperSettings.State

    override fun setUp() {
        super.setUp()
        state = CommitHelperSettings.getInstance().state
    }

    fun testDefaultConfigurationHasNoErrors() {
        val issues = SettingsDiagnostics.inspect(null)
        assertEmpty(
            "default settings must not be reported as errors, got: " + issues.filter { it.code.severity == DiagnosticSeverity.ERROR },
            issues.filter { it.code.severity == DiagnosticSeverity.ERROR },
        )
    }

    fun testDefaultBulletItemLineIsNotWarnedAsDuplicatePrefix() {
        assertFalse(codes().contains(DiagnosticCode.ITEM_LINE_PREFIX))
    }

    fun testEmptyFormatIsReported() = withFormat("") {
        assertTrue(codes().contains(DiagnosticCode.TEMPLATE_EMPTY))
    }

    fun testFormatWithoutItemsIsReported() = withFormat("{subject}") {
        assertTrue(codes().contains(DiagnosticCode.TEMPLATE_EMPTY))
    }

    fun testPastedLabelsAndTextInFormatAreReported() =
        withFormat("Format template:\n{items}\n\nItem line:\n{text}") {
            assertTrue(codes().contains(DiagnosticCode.TEMPLATE_POLLUTED))
        }

    fun testItemLinePrefixIsWarning() {
        val template = EffectiveSettings.template(null)
        val old = template.itemLine
        try {
            template.itemLine = "- {text}"
            val issues = SettingsDiagnostics.inspect(null)
            assertTrue(issues.any { it.code == DiagnosticCode.ITEM_LINE_PREFIX })
            assertFalse(issues.any { it.code == DiagnosticCode.ITEM_LINE_PREFIX && it.code.severity == DiagnosticSeverity.ERROR })
        } finally {
            template.itemLine = old
        }
    }

    fun testMissingProjectReferencesAreReported() {
        val projectSettings = ProjectCommitHelperSettings.getInstance(project).state
        val oldPrompt = projectSettings.promptProfileId
        val oldTemplate = projectSettings.templateId
        try {
            projectSettings.promptProfileId = "missing-prompt"
            projectSettings.templateId = "missing-template"
            val codes = SettingsDiagnostics.inspect(project).map { it.code }
            assertTrue(codes.contains(DiagnosticCode.PROJECT_PROMPT_MISSING))
            assertTrue(codes.contains(DiagnosticCode.PROJECT_TEMPLATE_MISSING))
        } finally {
            projectSettings.promptProfileId = oldPrompt
            projectSettings.templateId = oldTemplate
        }
    }

    fun testSignatureIgnoresOrder() {
        val first = Diagnostic(DiagnosticCode.TEMPLATE_EMPTY)
        val second = Diagnostic(DiagnosticCode.ITEM_LINE_EMPTY)
        assertEquals(SettingsDiagnostics.signature(listOf(first, second)), SettingsDiagnostics.signature(listOf(second, first)))
    }

    private fun codes(): List<DiagnosticCode> = SettingsDiagnostics.inspect(null).map { it.code }

    private fun withFormat(format: String, body: () -> Unit) {
        val template = EffectiveSettings.template(null)
        val old = template.format
        try {
            template.format = format
            body()
        } finally {
            template.format = old
        }
    }
}
