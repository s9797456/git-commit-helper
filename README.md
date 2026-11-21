<p align="center">
  <strong>English</strong> ·
  <a href="README.zh.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a>
</p>

# Commit Helper

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Commit%20Helper-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34303)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![VS Code](https://img.shields.io/badge/VS%20Code-Commit%20Helper-007ACC?logo=visualstudiocode&logoColor=white)](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Commit Helper turns the changes you check in the commit panel into a ready-to-commit message:
a subject line plus a feature-level body.

The plugin is **local-first**: deterministic file grouping runs before any model call. An optional
LLM (OpenAI-compatible, Anthropic, Gemini, or local-only mode) only handles the wording. If the
model is unavailable, the local grouping result is used instead of failing.

Available for **IntelliJ Platform 2024.1+** and **VS Code 1.90+**.

## Install

### IntelliJ Platform

- [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34303-commit-helper) — plugin ID `com.caye.commithelper`.
- Or install the signed ZIP from [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/latest).
- Open **Settings | Plugins | Marketplace**, search for **Commit Helper**, then install.

### VS Code

- [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool) — extension ID `me-tool.commit-helper-me-tool`.
- The Marketplace listing was submitted under Trusted Publishing. While verification is pending,
  install the VSIX from [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/tag/v0.3.0).
- In VS Code: **Extensions → ... → Install from VSIX...**.

## Features

- **Two IDEs, one workflow** — IntelliJ commit-panel actions and VS Code Command Palette/SCM commands.
- **Local-first generation** — deterministic grouping, optional LLM wording, local fallback.
- **Feature-level messages** — business capabilities instead of a list of files or classes.
- **Prompt profiles and templates** — built-in **Developer Summary**, custom profiles, format templates,
  item-line templates and commit types.
- **Smart commit split (IntelliJ 0.3+)** — split a checked change set into 1–6 atomic, file-level groups.
- **Native code review** — double-click a file in the split dialog to open the IDE's native
  `HEAD` vs working-tree diff.
- **Status colors** — added, modified, deleted and renamed files are color-coded.
- **Native Push** — the split flow opens IntelliJ's own VCS Push page; remote and branch options stay native.
- **Issue and CI helpers** — `{issue}` extraction from the branch/recent commits, Skip CI presets,
  changelog/PR description preview.
- **Four UI/output languages** — English, Simplified Chinese, Japanese, Korean.
- **Privacy by default** — API keys in the IDE credential store, no telemetry.

## Smart commit split

1. Check the files you want to split.
2. Run **Suggest Commit Split**.
3. Review 1–6 groups; each group has a message and a file list. The message is editable.
4. Double-click a file to inspect the native HEAD/working-tree diff.
5. **Apply this group** commits only that group's files with its message; the dialog stays open for the rest.
6. **Push** opens IntelliJ's native VCS Push page.

The model must assign every checked file to exactly one group. Unknown, missing or duplicate files
reject the plan. Invalid output is retried once, then falls back to deterministic grouping.

> Smart split is **file-level** in 0.3.0. It does not split individual hunks inside one file.

## Safety boundaries

- **Generate Commit Message** only writes into the commit message box.
- **Apply this group** in Smart Split commits the selected group, only after an explicit click.
- **Push** is delegated to the IDE's native Push page; the plugin does not push by itself.
- Replacing a message you wrote requires confirmation.
- API keys are never written to settings XML or logs.

## Quick start

1. Configure a provider and API key in **Settings | Tools | Commit Helper** (IntelliJ) or
   **Commit Helper** settings (VS Code).
2. Check the files for the commit.
3. Run **Generate Commit Message** or **Suggest Commit Split**.
4. Review and edit the message, then commit or push with the IDE.

## Configuration

| Setting | Meaning |
| --- | --- |
| Provider | OpenAI-compatible, Anthropic, Gemini, or Local only |
| Base URL / model / API key | Per provider; keys are stored in the IDE credential store |
| Language | English, Chinese, Japanese, Korean, or Auto |
| Format template | `{subject}`, `{items}`, `{issue}`, `{skeleton}`; item line supports `{type}`, `{scope}`, `{scopeRaw}`, `{text}` |
| Prompt template | `{language}`, `{format}`, `{skeleton}`, `{diff}`, `{fileList}`; omitted diff/file list is appended automatically |
| Limits | max lines per file, total character budget, extra exclude globs |

Noise files (binaries, lock files, `dist/`, `build/`, `node_modules/`, `*.min.js`,
`*.map`, snapshots) are excluded before sending. Oversized diffs are truncated and sampled;
the prompt states that the diff was sampled.

## Privacy and security

- Keys are stored in IntelliJ `PasswordSafe` or VS Code `SecretStorage`.
- Only checked changes are collected; the diff is filtered and capped.
- **Local only** mode makes no network request.
- No telemetry.
- Report vulnerabilities through [GitHub private vulnerability reporting](https://github.com/s9797456/git-commit-helper/security/advisories/new).
  See [SECURITY.md](SECURITY.md).

## Build

```bash
# IntelliJ plugin
./gradlew test
./gradlew buildPlugin
```

```bash
# VS Code extension
cd vscode
npm install
npm test
npm run package
```

Publishing is manual through `.github/workflows/publish.yml`. IntelliJ uses signing secrets and
`PUBLISH_TOKEN`; VS Code uses OIDC Trusted Publishing and does not store a Marketplace PAT.

## Documentation

- [Wiki](https://github.com/s9797456/git-commit-helper/wiki)
- [Release history](https://github.com/s9797456/git-commit-helper/releases)
- [Roadmap](docs/ROADMAP-0.3.0.md)

## License

Apache License 2.0 — see [LICENSE](LICENSE).
