<p align="center">
  <a href="README.md">English</a> ·
  <strong>简体中文</strong> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a>
</p>

# Commit Helper（提交助手）

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Commit%20Helper-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![下载量](https://img.shields.io/jetbrains/plugin/d/34303)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![VS Code](https://img.shields.io/badge/VS%20Code-Commit%20Helper-007ACC?logo=visualstudiocode&logoColor=white)](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

把你**在提交面板里勾选的变更**整理成可直接提交的 commit message：  
首行摘要 + 按功能分条的正文。

插件采用**本地优先**策略：先做确定性的文件分组，再决定是否调用模型。可选 LLM
（OpenAI 兼容 / Anthropic / Gemini / 本地模式）只负责措辞；模型不可用时使用本地分组结果，
不会直接失败。

支持 **IntelliJ Platform 2024.1+** 与 **VS Code 1.90+**。

## 安装

### IntelliJ Platform

- [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/34303-commit-helper)，插件 ID `com.caye.commithelper`。
- 或从 [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/latest) 安装签名 ZIP。
- IDE 内：**Settings | Plugins | Marketplace** 搜索 **Commit Helper** 安装。

### VS Code

- [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=me-tool.commit-helper-me-tool)，扩展 ID `me-tool.commit-helper-me-tool`。
- Marketplace 已通过 Trusted Publishing 提交；验证完成前，请从
  [GitHub Releases](https://github.com/s9797456/git-commit-helper/releases/tag/v0.3.0) 安装 VSIX。
- VS Code 内：**Extensions → ... → Install from VSIX...**。

## 功能

- **两个 IDE，一套流程** —— IntelliJ 提交面板工具栏与 VS Code 命令面板 / SCM 命令。
- **本地优先生成** —— 确定性分组、可选模型措辞、模型失败时本地降级。
- **功能级说明** —— 描述业务能力，而不是逐文件或逐类罗列。
- **提示词与模板** —— 内置 **Developer Summary**、自定义 Prompt、格式模板、条目行模板和提交类型。
- **智能拆分（IntelliJ 0.3+）** —— 把勾选变更拆成 1–6 个文件级原子提交。
- **原生代码对比** —— 在拆分窗口双击文件，打开 IDE 原生 `HEAD` 与工作区 diff。
- **文件状态颜色** —— 新增、修改、删除、重命名分别着色。
- **原生 Push** —— 拆分流程打开 IntelliJ 自己的 VCS Push 页面，远端与分支选项保持原生。
- **Issue 与 CI 辅助** —— 从分支名/最近提交提取 `{issue}`、Skip CI 预设、Changelog 与 PR 描述预览。
- **四种界面/输出语言** —— English、简体中文、日本語、한국어。
- **隐私默认** —— API Key 存在 IDE 凭据库，无遥测。

## 智能提交拆分

1. 勾选要拆分的文件。
2. 点击 **Suggest Commit Split**。
3. 检查 1–6 个分组；每组包含可编辑消息和文件列表。
4. 双击文件查看原生 HEAD/工作区 diff。
5. 点击 **Apply this group** 只会用该组消息提交该组文件；窗口保持打开，继续处理其他组。
6. 点击 **Push** 打开 IntelliJ 原生 VCS Push 页面。

模型必须把每个勾选文件恰好分到一个组；未知、遗漏或重复文件会拒绝计划。模型输出非法时
重试一次，之后回退到确定性分组。

> 0.3.0 的智能拆分是**文件级**的，不会拆分同一个文件内部的 hunk。

## 安全边界

- **Generate Commit Message** 只写入提交消息框。
- **Apply this group** 在用户明确点击后提交选中的分组。
- **Push** 交给 IDE 原生 Push 页面；插件不会自行 push。
- 覆盖你已手写的消息前会先确认。
- API Key 不会写入设置 XML 或日志。

## 快速开始

1. 在 **Settings | Tools | Commit Helper**（IntelliJ）或 **Commit Helper** 设置（VS Code）中
   配置 Provider 与 API Key。
2. 勾选本次提交的文件。
3. 运行 **Generate Commit Message** 或 **Suggest Commit Split**。
4. 检查并编辑消息，然后用 IDE 提交或推送。

## 配置

| 设置 | 说明 |
| --- | --- |
| Provider | OpenAI 兼容 / Anthropic / Gemini / 仅本地 |
| Base URL / 模型 / API Key | 每个 Provider 独立保存；Key 存入 IDE 凭据库 |
| Language | English / 中文 / 日本語 / 한국어 / Auto |
| 格式模板 | `{subject}`、`{items}`、`{issue}`、`{skeleton}`；条目行支持 `{type}`、`{scope}`、`{scopeRaw}`、`{text}` |
| 提示词模板 | `{language}`、`{format}`、`{skeleton}`、`{diff}`、`{fileList}`；未写的 diff/文件列表会自动追加 |
| 限额 | 单文件最大行数、总字符预算、附加排除 glob |

二进制、lock 文件、`dist/`、`build/`、`node_modules/`、`*.min.js`、`*.map`、快照等噪声文件
会在发送前排除；超大 diff 会截断并抽样，提示词中会显式说明已抽样。

## 隐私与安全

- Key 存在 IntelliJ `PasswordSafe` 或 VS Code `SecretStorage`。
- 只采集勾选变更；diff 会过滤并按预算截断。
- **仅本地**模式不发起网络请求。
- 无遥测。
- 通过 [GitHub 私密漏洞报告](https://github.com/s9797456/git-commit-helper/security/advisories/new) 报告安全问题；
  详见 [SECURITY.md](SECURITY.md)。

## 构建

```bash
# IntelliJ 插件
./gradlew test
./gradlew buildPlugin
```

```bash
# VS Code 扩展
cd vscode
npm install
npm test
npm run package
```

发布通过 `.github/workflows/publish.yml` 手动触发。IntelliJ 使用签名 secrets 与
`PUBLISH_TOKEN`；VS Code 使用 OIDC Trusted Publishing，不保存 Marketplace PAT。

## 文档

- [Wiki](https://github.com/s9797456/git-commit-helper/wiki)
- [Release](https://github.com/s9797456/git-commit-helper/releases)
- [Roadmap](docs/ROADMAP-0.3.0.md)

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。
