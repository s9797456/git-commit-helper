package com.caye.commithelper.i18n

import com.caye.commithelper.settings.LanguageOption
import com.caye.commithelper.settings.ProviderKind
import com.caye.commithelper.template.DefaultTemplates

/**
 * Display names localized at render time. The enum values and their technical names stay
 * untouched: they are persisted in settings and sent to the model, so only the label shown in
 * the UI is translated here.
 */

fun ProviderKind.localizedName(): String = when (this) {
    ProviderKind.OPENAI_COMPATIBLE -> CommitHelperBundle.message("provider.openAiCompatible")
    ProviderKind.ANTHROPIC -> CommitHelperBundle.message("provider.anthropic")
    ProviderKind.GEMINI -> CommitHelperBundle.message("provider.gemini")
    ProviderKind.LOCAL_ONLY -> CommitHelperBundle.message("provider.localOnly")
}

fun LanguageOption.localizedName(): String = when (this) {
    LanguageOption.CHINESE -> CommitHelperBundle.message("language.chinese")
    LanguageOption.ENGLISH -> CommitHelperBundle.message("language.english")
    LanguageOption.JAPANESE -> CommitHelperBundle.message("language.japanese")
    LanguageOption.KOREAN -> CommitHelperBundle.message("language.korean")
    LanguageOption.AUTO -> CommitHelperBundle.message("language.auto")
}

fun DefaultTemplates.FormatPreset.localizedName(): String = when (this) {
    DefaultTemplates.FormatPreset.CONVENTIONAL -> CommitHelperBundle.message("preset.conventional")
    DefaultTemplates.FormatPreset.BULLET_PLAIN -> CommitHelperBundle.message("preset.bulletPlain")
    DefaultTemplates.FormatPreset.CHINESE_ITEMIZED -> CommitHelperBundle.message("preset.chineseItemized")
}
