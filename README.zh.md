# Commit Helper（提交助手）

[![JetBrains Marketplace](https://img.shields.io/badge/JetBrains%20Marketplace-Commit%20Helper-000000?logo=jetbrains&logoColor=white)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![下载量](https://img.shields.io/jetbrains/plugin/d/34303)](https://plugins.jetbrains.com/plugin/34303-commit-helper)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

一个 IntelliJ 平台插件：把你**在提交面板里勾选的变更**整理成可直接提交的 commit message
（首行摘要 + 正文分条列出变更项）。

先用本地确定性规则做分组归类，再由可选的大模型（OpenAI 兼容 / Anthropic / Gemini）负责措辞。
模型不可用时，插件写入本地分组结果而不是直接失败。

## 安装

IDE 内：**Settings | Plugins | Marketplace** 搜索 *Commit Helper*，或打开
[JetBrains Marketplace 页面](https://plugins.jetbrains.com/plugin/34303-commit-helper)点 *Install*。
插件 ID `com.caye.commithelper`，要求 IntelliJ 平台 2024.1 及以上。

插件页还提供官方 widget（卡片 / 安装按钮），它们需要真实网页才能渲染（GitHub 会过滤 Markdown
里的 `<script>`）——`marketplace/widget.html` 就是现成的承载页，上面那排徽章是 GitHub 上的等价做法。

## 功能

- **提交消息框工具栏入口** —— 点 *Organize Commit Message* 才触发，绝不自动发请求。
- **混合生成** —— 本地按文件/模块确定性分组，模型只做措辞。
- **可编辑模板** —— 输出格式模板与提示词模板，内置预设可一键套用。
- **仓库提交模板** —— 读取 `.gitmessage` / `git config commit.template` 作为输出骨架，
  注释行原样保留。
- **Issue 编号注入** —— `{issue}` 从分支名或最近提交中提取，**不调用** Jira/GitHub API。
- **Changelog 与 PR 描述** —— 仅预览与复制，不写任何仓库文件。
- **优雅降级** —— 未配 key、超时、HTTP 报错、JSON 解析失败，都会回落为本地分组结果，
  并弹出带 *Retry* 按钮的通知。
- **隐私** —— API key 存在 IDE 凭据库，不写进设置 XML 也不进日志；无任何遥测。

## 安全边界

插件**永不执行任何 git 写操作**：不 commit、不 push、不 amend、不 rebase。它只往提交消息框里
写文本，且在覆盖你已手写内容前一定会先问你。

## 环境要求

- IntelliJ Platform 2024.1 及以上（IntelliJ IDEA / PyCharm / Android Studio / WebStorm 等）
- 一个 git 仓库（依赖内置 `Git4Idea` 插件）

## 构建

```bash
./gradlew buildPlugin        # 产出 build/distributions/*.zip
./gradlew test               # 纯逻辑单测 + 无头平台测试（Action 注册、设置页、完整管线）
./gradlew verifyPlugin       # 对 2024.1 / 2024.3 / 2026.2 做兼容性校验
./gradlew runIde             # 打开沙箱 IDE 做手工冒烟
```

通过 *Settings | Plugins | ⚙ | Install Plugin from Disk* 安装该 zip。

> 受限网络下 Gradle 无法访问 `services.gradle.org`（会 307 跳转到 GitHub），
> 因此 `gradle/wrapper/gradle-wrapper.properties` 指向了镜像；如无需要可换回官方地址。
>
> 若 home 目录不可写（沙箱或受限 CI），把 Gradle 目录指到工作区内：
> `GRADLE_USER_HOME=<workspace>/.gradle-home ./gradlew test`。
> Plugin Verifier 的 home 同理固定在 `<project>/.verifier-home`。

## 配置

*Settings | Tools | Commit Helper*。

| 设置项 | 说明 |
|---|---|
| Provider | `OpenAI-compatible` / `Anthropic` / `Gemini` / `Local only (no LLM)` |
| Base URL / Model / API key | 每个 provider 独立保存；key 存入 IDE 凭据库 |
| Language | `中文` / `English` / `Auto (follow repository)` |
| 格式模板 | 占位符 `{subject}` `{items}` `{issue}` `{skeleton}`；条目行可用 `{type}` `{scope}` `{scopeRaw}` `{text}` |
| 提示词模板 | 占位符 `{language}` `{format}` `{skeleton}` `{diff}` `{fileList}` |
| 限额 | 单文件最大行数、总字符预算、附加排除 glob |

`{scope}` 有值时渲染为 ` (模块)`（含前导空格），无值时消失；因此
`- [{type}]{scope} {text}` 会得到 `- [feat] text` 或 `- [feat] (api) text`。

噪声文件（二进制、lock、`dist/`、`build/`、`node_modules/`、`*.min.js`、`*.map`、快照）在发送前
就会被排除；超大 diff 先按单文件截断、再按字符预算抽样，且提示词里会**显式声明抽样**，
避免模型把"部分"说成"全部"。

## 许可证

Apache License 2.0，见 [LICENSE](LICENSE)。
