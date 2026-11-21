<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <strong>한국어</strong>
</p>

# Commit Helper (커밋 도우미)

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Commit%20Helper-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34303)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![VS Code](https://img.shields.io/badge/VS%20Code-Commit%20Helper-007ACC?logo=visualstudiocode&logoColor=white)](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

커밋 패널에서 선택한 변경 사항을 바로 커밋할 수 있는 메시지로 변환합니다.  
첫 줄 subject와 기능 단위 본문을 생성합니다.

**로컬 우선** 방식입니다. 먼저 결정적인 파일 그룹화를 수행하고, 선택적으로 LLM
(OpenAI 호환 / Anthropic / Gemini / 로컬 전용)이 문장을 다듬습니다. 모델을 사용할 수 없을 때도
로컬 그룹 결과로 대체되며 실패하지 않습니다.

**IntelliJ Platform 2024.1+** 및 **VS Code 1.90+** 를 지원합니다.

## 설치

### IntelliJ Platform

- [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34303-commit-helper), 플러그인 ID `com.caye.commithelper`
- 또는 [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/latest)의 서명된 ZIP 설치
- IDE에서 **Settings | Plugins | Marketplace** → **Commit Helper** 검색 후 설치

### VS Code

- [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool), 확장 ID `me-tool.commit-helper-me-tool`
- Marketplace에는 Trusted Publishing으로 제출되었습니다. 검증이 끝나기 전에는
  [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/tag/v0.3.0)의 VSIX를 설치하세요.
- VS Code에서 **Extensions → ... → Install from VSIX...**

## 기능

- **두 IDE, 하나의 워크플로** — IntelliJ 커밋 패널과 VS Code 명령 팔레트/SCM.
- **로컬 우선 생성** — 결정적 그룹화, 선택적 LLM 문장 생성, 로컬 폴백.
- **기능 단위 메시지** — 파일/클래스 나열 대신 비즈니스 기능을 설명.
- **프롬프트와 템플릿** — 내장 **Developer Summary**, 사용자 프롬프트, 출력 템플릿,
  항목 줄 템플릿, 커밋 타입.
- **스마트 커밋 분할(IntelliJ 0.3+)** — 선택한 변경을 1~6개의 파일 단위 원자적 커밋으로 분할.
- **네이티브 diff** — 분할 창에서 파일을 더블클릭하면 IDE의 `HEAD` 대 작업 트리 diff 표시.
- **상태 색상** — 추가, 수정, 삭제, 이름 변경 파일을 색상으로 구분.
- **네이티브 Push** — 분할 흐름에서 IntelliJ VCS Push 페이지를 엽니다.
- **Issue/CI 지원** — 브랜치/최근 커밋에서 `{issue}` 추출, Skip CI, changelog/PR 설명 미리보기.
- **4개 언어** — English, 简体中文, 日本語, 한국어.
- **개인정보 보호** — API 키는 IDE 자격 증명 저장소에 보관, 텔레메트리 없음.

## 스마트 커밋 분할

1. 분할할 파일을 선택합니다.
2. **Suggest Commit Split** 실행.
3. 1~6개 그룹을 확인합니다. 각 그룹에는 편집 가능한 메시지와 파일 목록이 있습니다.
4. 파일을 더블클릭해 네이티브 HEAD/작업 트리 diff를 확인합니다.
5. **Apply this group**은 해당 그룹 파일만 그룹 메시지로 커밋합니다. 창은 열린 상태로 유지됩니다.
6. **Push**는 IntelliJ 네이티브 VCS Push 페이지를 엽니다.

선택한 모든 파일은 정확히 하나의 그룹에 속해야 합니다. 알 수 없거나 누락/중복된 파일이 있으면
계획을 거부합니다. 잘못된 출력은 한 번 재시도하고, 이후 결정적 그룹화로 폴백합니다.

> 0.3.0의 스마트 분할은 **파일 단위**입니다. 한 파일 내부의 hunk를 나누지는 않습니다.

## 안전 경계

- **Generate Commit Message**는 커밋 메시지 상자에만 씁니다.
- **Apply this group**은 사용자가 명시적으로 클릭할 때만 선택 그룹을 커밋합니다.
- **Push**는 IDE 네이티브 Push 페이지에 위임하며, 플러그인이 직접 push하지 않습니다.
- 직접 작성한 메시지를 바꾸기 전에 확인합니다.
- API 키는 설정 XML이나 로그에 기록하지 않습니다.

## 빠른 시작

1. **Settings | Tools | Commit Helper**(IntelliJ) 또는 **Commit Helper** 설정(VS Code)에서
   Provider와 API 키를 설정합니다.
2. 커밋할 파일을 선택합니다.
3. **Generate Commit Message** 또는 **Suggest Commit Split** 실행.
4. 메시지를 확인/편집한 뒤 IDE에서 커밋하거나 push합니다.

## 설정

| 설정 | 의미 |
| --- | --- |
| Provider | OpenAI 호환 / Anthropic / Gemini / 로컬 전용 |
| Base URL / 모델 / API 키 | Provider별 저장. 키는 IDE 자격 증명 저장소에 보관 |
| Language | English / 中文 / 日本語 / 한국어 / Auto |
| 출력 템플릿 | `{subject}`, `{items}`, `{issue}`, `{skeleton}`; 항목 줄은 `{type}`, `{scope}`, `{scopeRaw}`, `{text}` |
| 프롬프트 템플릿 | `{language}`, `{format}`, `{skeleton}`, `{diff}`, `{fileList}`; 누락된 diff/파일 목록은 자동 추가 |
| 제한 | 파일당 최대 줄 수, 전체 문자 예산, 추가 제외 glob |

바이너리, lock 파일, `dist/`, `build/`, `node_modules/`, `*.min.js`, `*.map`,
스냅샷 등 노이즈 파일은 전송 전에 제외됩니다. 큰 diff는 잘라서 샘플링하며, 프롬프트에
샘플링 사실을 명시합니다.

## 개인정보와 보안

- 키는 IntelliJ `PasswordSafe` 또는 VS Code `SecretStorage`에 저장됩니다.
- 선택한 변경만 수집하며 diff는 필터링되고 문자 예산으로 제한됩니다.
- **로컬 전용** 모드는 네트워크 요청을 하지 않습니다.
- 텔레메트리가 없습니다.
- 취약점은 [GitHub 비공개 취약점 신고](https://github.com/s9797456/git-commit-helper/security/advisories/new)로 보고하세요.
  자세한 내용은 [SECURITY.md](SECURITY.md)를 참고하세요.

## 빌드

```bash
# IntelliJ 플러그인
./gradlew test
./gradlew buildPlugin
```

```bash
# VS Code 확장
cd vscode
npm install
npm test
npm run package
```

게시는 `.github/workflows/publish.yml`을 수동 실행합니다. IntelliJ는 서명 secrets와
`PUBLISH_TOKEN`을, VS Code는 OIDC Trusted Publishing을 사용하며 Marketplace PAT를 저장하지 않습니다.

## 문서

- [Wiki](https://github.com/s9797456/git-commit-helper/wiki)
- [Release](https://github.com/s9797456/git-commit-helper/releases)
- [Roadmap](docs/ROADMAP-0.3.0.md)

## 라이선스

Apache License 2.0 — [LICENSE](LICENSE) 참고.
