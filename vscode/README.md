# Commit Helper for VS Code

把 VS Code 源码管理面板里的变更整理成可直接提交的 commit message：本地分组兜底 + 可选 LLM 措辞，**只写入 SCM 提交框，不执行 commit / push**。

这是 IntelliJ 插件 [Commit Helper](https://plugins.jetbrains.com/plugin/34303-commit-helper) 的 VS Code 版本 MVP，核心行为对齐：先采集 diff，再确认发送，再生成并写入。

## 功能

- **Generate Commit Message**：读取暂存区或工作区变更，生成本地分组 + 可选 LLM 结果。
- **Generate with Context**：额外填写 issue/PR 与补充说明。
- **Format Commit Message**：把提交框里已有消息按模板重新排版。
- **Set API Key**：API Key 保存在 VS Code SecretStorage，不写入 settings。
- **Test LLM Connection**：快速验证 Base URL / Key / Model。
- **本地兜底**：没有 key、网络失败、模型返回空 items 时使用本地分组并明确提示。
- **Undo**：写入后通知里可撤销；撤销只在提交框仍是生成内容时生效。
- **质量检查**：Conventional Commits、subject 长度、类型白名单，发现问题先确认再写入。

## 安装（本地 VSIX）

1. 打开扩展视图；
2. 右上角 `...` → **Install from VSIX...**；
3. 选择 `dist/commit-helper-0.1.0.vsix`。

## 配置

在 VS Code Settings 搜索 `Commit Helper`：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `commitHelper.provider` | `openai-compatible` | `openai-compatible` / `anthropic` / `gemini` / `local` |
| `commitHelper.baseUrl` | `https://api.openai.com/v1` | 按 provider 填写 |
| `commitHelper.model` | `gpt-4o-mini` | 模型名 |
| `commitHelper.language` | `auto` | `auto` / `zh` / `en` / `ja` / `ko` |
| `commitHelper.scope` | `auto` | `auto` / `staged` / `all` |
| `commitHelper.confirmBeforeSending` | `true` | 发送 diff 前确认 |
| `commitHelper.includeDiff` | `true` | 关闭时只发文件列表 |
| `commitHelper.maxDiffChars` | `60000` | diff 字符预算 |
| `commitHelper.maxTokens` | `4096` | 输出 token 上限 |
| `commitHelper.timeoutSeconds` | `120` | 请求超时 |
| `commitHelper.formatTemplate` | `{subject}\n\n{items}` | 最终布局 |
| `commitHelper.itemLine` | `- [{type}]{scope} {text}` | 每条事项布局 |
| `commitHelper.promptTemplate` | 内置提示词 | 支持 `{diff}` / `{fileList}` / `{types}` 等占位符 |
| `commitHelper.commitTypes` | feat/fix/... | 允许类型 |

### Provider 示例

- OpenAI / DeepSeek / 其他兼容网关：provider=`openai-compatible`，Base URL 填到 `/v1`，例如 `https://openapi.deepseek.com/v1`。
- Anthropic：provider=`anthropic`，Base URL `https://api.anthropic.com`。
- Gemini：provider=`gemini`，Base URL `https://generativelanguage.googleapis.com/v1beta`。
- 本地：provider=`local`，不访问网络，只做确定性分组。

## 安全

- 只写 `repo.inputBox.value`，不执行 git commit/push/amend/rebase。
- API Key 用 SecretStorage 保存；日志只记录 provider/model/长度/耗时，不记录 diff、prompt 或 key。
- 发送前默认弹窗确认文件数和 diff 字符数；可在设置中关闭。

## 与 IntelliJ 版的差异

VS Code 0.1.0 先覆盖“生成 / 带上下文 / 格式化 / 撤销 / 本地兜底”。Prompt/模板 CRUD、项目级默认、指南页、预览面板和流式生成在后续版本补齐。

## 开发

`@bash
npm test
node --check extension.js && node --check src/core.js
node scripts/package-vsix.js
`@

## 已知限制

- 未实现流式输出；长 diff 仍是一次性响应。
- 没有 Prompt/模板 CRUD UI，先用 Settings JSON 编辑。
- 未读取 .gitmessage 作为 skeleton。
- 未实现 Jira/GitHub Issue API，仅从分支名/手填内容提取 issue。
- 未自动检测模型可用性；请先用 **Test LLM Connection** 验证。

## License

Apache-2.0
