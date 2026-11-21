<p align="center">
  <a href="README.md">English</a> ·
  <a href="README.zh.md">简体中文</a> ·
  <strong>日本語</strong> ·
  <a href="README.ko.md">한국어</a>
</p>

# Commit Helper（コミットヘルパー）

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Commit%20Helper-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/34303)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![VS Code](https://img.shields.io/badge/VS%20Code-Commit%20Helper-007ACC?logo=visualstudiocode&logoColor=white)](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

コミットパネルで選択した変更を、そのままコミットできるメッセージに変換します。  
1 行目の subject と、機能単位の本文を生成します。

**ローカルファースト**設計です。まず決定的なファイルグループ化を行い、その後に任意の LLM
（OpenAI 互換 / Anthropic / Gemini / ローカルのみ）が文章を整えます。モデルが使えない場合も
ローカルのグループ結果を使い、失敗しません。

**IntelliJ Platform 2024.1+** と **VS Code 1.90+** に対応しています。

## インストール

### IntelliJ Platform

- [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34303-commit-helper)（プラグイン ID `com.caye.commithelper`）
- または [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/latest) の署名済み ZIP
- IDE で **Settings | Plugins | Marketplace** → **Commit Helper** を検索してインストール

### VS Code

- [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool)（拡張 ID `me-tool.commit-helper-me-tool`）
- Marketplace には Trusted Publishing で申請済みです。検証完了までは
  [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/tag/v0.3.0) の VSIX をインストールしてください。
- VS Code で **Extensions → ... → Install from VSIX...**

## 機能

- **2 つの IDE、1 つのワークフロー** — IntelliJ のコミットパネルと VS Code のコマンドパレット/SCM。
- **ローカルファースト生成** — 決定的グループ化、任意の LLM 文章生成、ローカルへのフォールバック。
- **機能単位の説明** — ファイルやクラスの列挙ではなく、ビジネス上の機能を説明。
- **プロンプトとテンプレート** — 組み込み **Developer Summary**、カスタムプロンプト、出力テンプレート、
  アイテム行テンプレート、コミットタイプ。
- **スマート分割（IntelliJ 0.3+）** — 選択した変更を 1〜6 個のファイル単位のコミットに分割。
- **ネイティブ差分** — 分割ダイアログでファイルをダブルクリックすると、IDE ネイティブの
  `HEAD` と作業ツリーの差分を表示。
- **ステータス色** — 追加・変更・削除・リネームを色分け。
- **ネイティブ Push** — 分割フローから IntelliJ の VCS Push ページを開きます。
- **Issue / CI 支援** — ブランチ名や最近のコミットから `{issue}` を抽出、Skip CI、
  changelog / PR 説明のプレビュー。
- **4 言語** — English、简体中文、日本語、한국어。
- **プライバシー** — API キーは IDE の資格情報ストアに保存。テレメトリなし。

## スマートコミット分割

1. 分割したいファイルを選択します。
2. **Suggest Commit Split** を実行します。
3. 1〜6 個のグループを確認します。各グループには編集可能なメッセージとファイル一覧があります。
4. ファイルをダブルクリックして、ネイティブの HEAD/作業ツリー差分を確認します。
5. **Apply this group** は、そのグループのファイルだけをそのメッセージでコミットします。
   ダイアログは開いたままで、残りのグループを続けられます。
6. **Push** は IntelliJ ネイティブの VCS Push ページを開きます。

選択したすべてのファイルがちょうど 1 つのグループに含まれる必要があります。未知・欠落・重複が
ある場合は計画を拒否します。不正な出力は 1 回再試行し、その後は決定的グループ化にフォールバックします。

> 0.3.0 のスマート分割は**ファイル単位**です。1 ファイル内の hunk 分割は行いません。

## 安全境界

- **Generate Commit Message** はコミットメッセージ欄だけを更新します。
- **Apply this group** は、ユーザーが明示的にクリックしたときだけ選択グループをコミットします。
- **Push** は IDE のネイティブ Push ページに委譲し、プラグイン自身は push しません。
- 手入力したメッセージを置き換える前に確認します。
- API キーは設定 XML やログに書き込みません。

## クイックスタート

1. **Settings | Tools | Commit Helper**（IntelliJ）または **Commit Helper** 設定（VS Code）で
   Provider と API キーを設定します。
2. コミット対象のファイルを選択します。
3. **Generate Commit Message** または **Suggest Commit Split** を実行します。
4. メッセージを確認・編集し、IDE からコミットまたは push します。

## 設定

| 設定 | 意味 |
| --- | --- |
| Provider | OpenAI 互換 / Anthropic / Gemini / ローカルのみ |
| Base URL / モデル / API キー | Provider ごとに保存。キーは IDE の資格情報ストア |
| Language | English / 中文 / 日本語 / 한국어 / Auto |
| 出力テンプレート | `{subject}`、`{items}`、`{issue}`、`{skeleton}`。アイテム行は `{type}`、`{scope}`、`{scopeRaw}`、`{text}` |
| プロンプトテンプレート | `{language}`、`{format}`、`{skeleton}`、`{diff}`、`{fileList}`。未指定の diff/ファイル一覧は自動追加 |
| 制限 | ファイルごとの最大行数、総文字数、追加の除外 glob |

バイナリ、lock ファイル、`dist/`、`build/`、`node_modules/`、`*.min.js`、`*.map`、
スナップショットなどは送信前に除外されます。大きな diff は切り詰めてサンプリングし、その旨を
プロンプトに明記します。

## プライバシーとセキュリティ

- キーは IntelliJ `PasswordSafe` または VS Code `SecretStorage` に保存。
- 選択した変更だけを収集し、diff はフィルタと文字数制限を適用。
- **ローカルのみ**モードではネットワーク通信を行いません。
- テレメトリはありません。
- 脆弱性は [GitHub の非公開脆弱性報告](https://github.com/s9797456/git-commit-helper/security/advisories/new) から報告してください。
  詳細は [SECURITY.md](SECURITY.md)。

## ビルド

```bash
# IntelliJ プラグイン
./gradlew test
./gradlew buildPlugin
```

```bash
# VS Code 拡張
cd vscode
npm install
npm test
npm run package
```

公開は `.github/workflows/publish.yml` を手動実行します。IntelliJ は署名 secrets と
`PUBLISH_TOKEN`、VS Code は OIDC Trusted Publishing を使用し、Marketplace PAT は保存しません。

## ドキュメント

- [Wiki](https://github.com/s9797456/git-commit-helper/wiki)
- [Release](https://github.com/s9797456/git-commit-helper/releases)
- [Roadmap](docs/ROADMAP-0.3.0.md)

## ライセンス

Apache License 2.0 — [LICENSE](LICENSE) を参照してください。
