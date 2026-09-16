# Commit Helper

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Commit%20Helper-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34303)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

An IntelliJ Platform plugin that turns the changes you checked in the commit panel into a
ready-to-commit message: a subject line plus an itemized body.

Local, deterministic grouping runs first; an optional LLM (OpenAI-compatible, Anthropic or
Gemini) only handles the wording. If the model is unavailable, the plugin writes the local
grouping result instead of failing.

## Install

From the IDE: **Settings | Plugins | Marketplace** → search for *Commit Helper*, or open the
[JetBrains Marketplace page](https://plugins.jetbrains.com/plugin/34303-commit-helper) and click
*Install*. Plugin ID `com.caye.commithelper`, requires IntelliJ Platform 2024.1 or newer.

The plugin page also ships official widgets (a card and an install button). They need a real web
page, because GitHub strips `<script>` from Markdown: `marketplace/widget.html` is a ready host
page for them, while the badges above are the GitHub-friendly equivalent.

## Features

- **Toolbar action in the commit message box** — click *Organize Commit Message*; nothing is
  sent until you click.
- **Hybrid generation** — deterministic file/module grouping locally, LLM wording on top.
- **Editable templates** — output format template and prompt template, with presets.
- **Repository commit template** — `.gitmessage` / `git config commit.template` is used as the
  output skeleton and its comment lines are preserved.
- **Issue key injection** — `{issue}` is extracted from the branch name or recent commits
  (no Jira/GitHub API calls).
- **Changelog and PR description** — preview and copy only; no files are written.
- **Graceful degradation** — no key, timeout, HTTP error or malformed JSON falls back to the
  local grouping result plus a notification with a *Retry* action.
- **Privacy** — API keys live in the IDE credential store, never in settings XML or logs.
  No telemetry.

## Safety

The plugin never runs a git write operation. It does not commit, push, amend or rebase — it
only writes text into the commit message box, and asks before replacing a message you wrote.

## Requirements

- IntelliJ Platform 2024.1 or newer (IntelliJ IDEA, PyCharm, Android Studio, WebStorm, …)
- A git repository (`Git4Idea` is a bundled dependency)

## Build

```bash
./gradlew buildPlugin        # produces build/distributions/*.zip
./gradlew test               # pure-logic tests + headless platform tests (actions, settings, pipeline)
./gradlew verifyPlugin       # compatibility check against 2024.1 / 2024.3 / 2026.2
./gradlew runIde             # sandbox IDE for manual smoke tests
```

Install the zip through *Settings | Plugins | ⚙ | Install Plugin from Disk*.

> In restricted networks Gradle cannot reach `services.gradle.org` (it redirects to GitHub).
> `gradle/wrapper/gradle-wrapper.properties` points at a mirror for that reason; swap the
> `distributionUrl` back if you prefer the official distribution.
>
> If your home directory is not writable (sandboxed or locked-down builds), point Gradle at a
> workspace path: `GRADLE_USER_HOME=<workspace>/.gradle-home ./gradlew test`. The Plugin
> Verifier keeps its own home in `<project>/.verifier-home` for the same reason.

## Configure

*Settings | Tools | Commit Helper*.

| Setting | Meaning |
|---|---|
| Provider | `OpenAI-compatible`, `Anthropic`, `Gemini`, or `Local only (no LLM)` |
| Base URL / Model / API key | per provider; the key is stored in the IDE credential store |
| Language | `中文`, `English`, `Auto (follow repository)` |
| Format template | placeholders `{subject}` `{items}` `{issue}` `{skeleton}`; item line uses `{type}` `{scope}` `{scopeRaw}` `{text}` |
| Prompt template | placeholders `{language}` `{format}` `{skeleton}` `{diff}` `{fileList}` |
| Limits | max lines per file, total character budget, extra exclude globs |

`{scope}` renders as ` (module)` (leading space) when a scope exists and vanishes otherwise, so
`- [{type}]{scope} {text}` yields `- [feat] text` or `- [feat] (api) text`.

Noise files (binaries, lock files, `dist/`, `build/`, `node_modules/`, `*.min.js`, `*.map`,
snapshots) are excluded before anything is sent. Oversized diffs are truncated per file and
sampled to the character budget, and the prompt says so explicitly.

[中文说明](README.zh.md)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
