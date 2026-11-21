# Security Policy

Commit Helper is a local-first commit assistant for IntelliJ Platform and VS Code. This policy explains which versions receive security fixes, how to report a vulnerability privately, and what is in scope.

## Supported versions

| Component | Version | Security fixes |
| --- | --- | --- |
| IntelliJ Platform plugin | `0.3.x` | Yes |
| IntelliJ Platform plugin | `0.2.x` and older | No; upgrade to the latest release |
| VS Code extension | `0.1.x` | Yes |

Only the latest published release line receives security fixes. Older lines may receive a best-effort fix when the issue is critical, but this is not guaranteed.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a suspected vulnerability.**

Use GitHub private vulnerability reporting:

https://github.com/s9797456/git-commit-helper/security/advisories/new

Private reporting is enabled for this repository. If the form is unavailable, open a minimal issue asking the maintainer to enable a private channel, without disclosing technical details.

Please include:

- affected component and version
- operating system and IDE/VS Code version
- a minimal reproduction or proof of concept
- expected and actual behavior
- security impact you believe is possible
- whether the issue is already public
- any suggested fix or mitigation

Never include real API keys, passwords, tokens, private repository contents or personal data in a report. Use redacted samples.

## Response targets

- Acknowledgement: within 3 business days
- Initial assessment and severity: within 7 business days
- Status update: at least every 14 days while the report is open
- Fix and coordinated disclosure: normally within 90 days, sooner for critical issues

These are targets, not a contract. Complex reports or upstream provider issues may take longer; the maintainer will explain the delay and keep the reporter informed.

## Scope

In scope:

- IntelliJ Platform plugin code and actions in this repository
- VS Code extension code under `vscode/`
- Prompt/template rendering and diff collection paths
- API-key storage and handling
- GitHub Actions workflows, release packaging, signing and publishing
- Trusted Publishing and release artifact integrity

Out of scope:

- vulnerabilities in Git, the IntelliJ Platform, VS Code, or an operating system
- vulnerabilities in an LLM provider's own service or API
- availability-only attacks, spam, social engineering or physical attacks
- issues that require a compromised local machine or an already-malicious IDE extension
- dependency advisories that are already public and have no project-specific impact

## Security model

- API keys are stored in the IDE credential store: IntelliJ `PasswordSafe` and VS Code `SecretStorage`.
- Keys are never written to plugin settings XML, logs or the repository.
- Normal generation writes only to the commit message box. Smart split applies a selected group when the user explicitly chooses it. The plugin does not push automatically.
- Only changes checked in the commit panel are collected. Diff collection is capped and filtered for noise files.
- `Local only` mode makes no network request.
- A remote provider receives only the request needed for generation. Review the provider's data policy before sending private code.
- Release notes and public documentation must not contain personal email addresses, account names, passwords, tokens or other secrets.

## Release integrity

- IntelliJ releases are built and signed with the project signing key.
- VS Code releases are published from GitHub Actions using OIDC Trusted Publishing; no long-lived Marketplace PAT is stored.
- GitHub Releases include checksums or verifiable artifact metadata where available.
- Verify that downloads come from the official repository or the official Marketplace listing.

## Dependency and supply-chain security

- Dependabot alerts and security updates are enabled for Gradle, npm and GitHub Actions.
- CodeQL default setup runs on the default branch.
- Secret scanning and push protection are enabled.
- Workflow permissions are kept minimal; publishing uses short-lived OIDC credentials.

## Safe harbor

Good-faith security research is welcome. The maintainer will not pursue legal action for accidental, non-destructive testing performed under this policy. Do not access data that is not yours, degrade service for others, or publicly disclose an unresolved issue before coordinated disclosure.

## Credit

Reporters may be credited in the advisory or release notes if they wish. Anonymity is respected.
