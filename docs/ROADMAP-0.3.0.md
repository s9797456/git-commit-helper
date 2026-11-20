# Commit Helper 0.3.0 规划：Preview & Trust

> 依据（2026-11-19）：GitHub Releases 只有 v0.2.0 / v0.2.1，无 open issue；JetBrains Marketplace 0.2.1 已通过审核（0.2.0、0.1.2 也均 listed），下载 97 次，暂无评分/评论；分类标签为 AI / Code Tools / Formatting。
>
> 判断：产品已经过了“能不能用”的阶段，当前最大瓶颈不是再增加模型提供商，而是**让用户在发送前看懂、发送后敢用**。0.3.0 应该围绕“可预览、可撤销、可校验、可共享”做一轮体验版本。

## 一、0.3.0 新增功能项（建议范围）

| 优先级 | 功能 | 用户价值 | 实现要点 | 验收标准 |
|---|---|---|---|---|
| P0 | **发送前预览与编辑** | 解决“点确定后不知道发了什么、生成了什么”的信任问题 | 新增非模态预览面板：左侧变更文件/摘要，中间 diff 摘要，右侧生成消息；可编辑、重新生成、替换/追加、取消 | 不写入提交框前可完整预览；取消零副作用；编辑内容原样写入 |
| P0 | **流式生成 + 取消/撤销** | 长 diff 不再“转圈等 120 秒”，可中途停止 | Provider 流式解析（OpenAI SSE / Anthropic / Gemini），进度条逐字更新；取消即 abort；写入后提供 Undo | 取消后 2 秒内释放请求；Undo 恢复原文；请求日志有 requestId |
| P0 | **提交消息质量门禁** | 减少 `update code`、超长 subject、类型不在白名单等低级消息 | 本地校验 Conventional Commits、subject ≤ 72、body 行宽、issue 格式、模板空值；只警告不阻断 | 违规项在预览中高亮并给一键修复；阻断开关默认关闭 |
| P1 | **团队共享配置** | 让一个团队用同一套 prompt/模板/类型，不再逐人配置 | 读取仓库根目录 `.commit-helper.json`（prompt profile、template、types、language、校验规则），项目配置覆盖全局；支持导入/导出 | 新成员打开仓库即继承团队配置；非法 JSON 有明确报错且回退全局 |
| P1 | **一次生成多种风格** | 同一 diff 可选“简短 / 详细 / 仅 subject” | 预览面板提供 3 个生成按钮，复用同一 diff，不重复采集 | 切换风格不重新读取 diff；结果可对比 |
| P2 | **Ollama / 本地模型预设** | 隐私敏感团队可以完全不出网 | Provider 增加 `ollama`，默认 `http://127.0.0.1:11434`，复用 OpenAI-compatible 协议 | 无 key 可用；错误提示指向 Ollama 启动方式 |

> 不建议放进 0.3.0：Jira/GitHub OAuth 集成、自动 commit/push、云端同步、遥测。前者依赖外部授权和审核，后两者与当前“只写文本、不碰 git 写操作”的安全定位冲突。

## 二、逻辑优化项（工程质量）

1. **拆分生成管线**
   - 现在 `CommitHelperService` 同时承担采集、筛选、prompt、LLM、渲染、确认、通知，修改一个 provider 容易碰到 UI 逻辑。
   - 拆成 `DiffCollector → DiffFilter → PromptComposer → ProviderAdapter → MessageValidator → MessageWriter`，每层纯函数或窄接口，便于单测和 VS Code 复用。

2. **统一 Provider 适配层**
   - 统一超时、取消、HTTP 错误、重试、usage 统计；新增 provider 只实现 `complete(request): Flow<String>`。
   - 支持流式增量；错误分类为 `Auth / RateLimit / Timeout / Network / InvalidResponse / ContentEmpty / Canceled`，通知文案按类型给下一步动作。

3. **取消与超时真正贯通**
   - `ProgressIndicator` 的取消要传到 HTTP 层（OkHttp `Call.cancel()` / `AbortController`）；不要只取消等待。
   - 超时按“连接 / 首 token / 总时长”分开，避免大 diff 被总超时误杀。

4. **Token 预算与上下文压缩**
   - 目前按字符截断；改为“文件级摘要 + 关键 hunk 优先 + token 估算”，优先保留新增/删除、签名、配置、枚举变化。
   - 对重复文件、生成物、lock 文件做二次去重；超过预算时在 prompt 里明确“已采样”，并在预览中展示采样比例。

5. **结果缓存与幂等**
   - 对 `(diff hash, model, prompt version)` 缓存生成结果；相同 diff 重新打开预览直接命中，节省 token。
   - 同一 diff 的“重新生成”带随机种子/温度参数，避免命中旧结果。

6. **安全加固**
   - API key 只走 credential store；日志只记录长度、状态码、requestId，不记录 diff、prompt、key。
   - 只允许 `https` 或 `localhost/127.0.0.1` 的 base URL；禁止跟随跨域重定向；对响应体截断，避免日志爆量。

7. **测试补齐**
   - 增加 mock HTTP provider（成功、401、429、超时、空 items、Markdown 围栏、非 JSON），覆盖所有错误分类。
   - 增加 golden prompt 测试：固定 diff + 固定配置 → 固定 prompt，防止无意的 prompt 漂移。
   - 增加 UI 回归：预览取消无写入、Undo 恢复、空结果必通知。

## 三、用户体验优化项

1. **首启向导**：第一次使用只问 3 件事——语言、Provider、API Key；其余用默认值；完成后直接给一条“用当前 diff 试一次”的引导。
2. **连接测试升级**：测试连接同时拉取模型列表，成功时显示延迟和模型名；失败时给出“检查 Base URL / 代理 / Key”的分步建议。
3. **预览面板**：不是弹窗堆文字，而是可折叠的 diff 摘要 + 可编辑消息 + 字数/行数/类型校验；底部固定“替换 / 追加 / 重新生成 / 取消”。
4. **撤销与重试**：写入后通知里固定 `Undo` 与 `Retry`；撤销只在提交框内容仍是生成结果时生效，避免覆盖用户后续输入。
5. **降低通知噪音**：成功默认静默，只更新提交框；失败才弹通知；连续失败合并为一条，提供“查看日志”。
6. **快捷键与入口**：为生成/预览/撤销分配可配置快捷键；提交面板右键菜单增加“生成并预览”。
7. **设置可迁移**：导入/导出配置 JSON；支持“恢复内置默认”；团队配置冲突时显示来源（全局 / 项目 / 仓库文件）。
8. **文档内嵌**：指南页增加 30 秒视频/GIF、常见错误一键修复、模型选择建议（成本/速度/隐私）。

## 四、VS Code 版本插件

### 定位

先做 **Commit Helper for VS Code 0.1.0 MVP**，与 IntelliJ 版共享“本地分组 + 可选 LLM + 只写 SCM 输入框、不 commit”的核心，不追求设置页功能完全对齐。

### 0.1.0 范围

- 命令：`Commit Helper: Generate Commit Message`、`Generate with Context`、`Format Commit Message`、`Set API Key`、`Open Settings`。
- 变更来源：默认 auto（有暂存变更用暂存，否则用工作区变更），可配置 `staged / all / auto`。
- Provider：OpenAI-compatible、Anthropic、Gemini、Local only；MVP 不做流式，但支持取消、超时、空结果重试和本地兜底。
- 交互：发送前 modal 确认（文件数 + 字符数）；写入前若已有消息，询问替换/追加；写入后通知带 Undo。
- 配置：provider、baseUrl、model、language、formatTemplate、itemLine、promptTemplate、commitTypes、maxDiffChars、maxTokens、timeoutSeconds、confirmBeforeSending。
- 安全：API key 存 `SecretStorage`；不写 settings；只写 `repo.inputBox.value`；不执行 commit/push。
- 日志：Output Channel `Commit Helper`，记录 provider/model/长度/耗时/错误分类，不记录 diff 和 key。

### 工程结构

`@text
git-commit-helper-vscode/
  package.json          # Manifest + commands + configuration + nls
  extension.js          # VS Code 适配：Git API、SCM input、通知、SecretStorage
  src/core.js           # 纯逻辑：diff 解析、prompt、LLM 响应解析、渲染、本地分组
  test/core.test.js     # node:test 单测，不依赖 VS Code
  scripts/package-vsix.js
  README.md
  CHANGELOG.md
`@

### 与 IntelliJ 版的差异

| 能力 | IntelliJ 0.2.1 | VS Code 0.1.0 |
|---|---|---|
| 生成 / 带上下文 / 格式化 | 有 | 有 |
| Prompt / 模板 CRUD | 有 | 只有设置项，后续补 |
| 项目级默认 | 有 | 仓库级 `.commit-helper.json` 后续补 |
| 指南与自检 | 有 | README + 错误通知，后续补 |
| 预览面板 | 0.3.0 计划 | 0.2.0 计划 |
| 流式 | 0.3.0 计划 | 0.2.0 计划 |
| 撤销/重试 | 有重试 | 有 Undo + Retry |

### 发布方式

1. `npm test` 通过；
2. `node scripts/package-vsix.js` 生成 `dist/commit-helper-0.1.0.vsix`；
3. VS Code → Extensions → `...` → Install from VSIX；
4. 公开发布需创建 Azure DevOps publisher（`me-tool`）并 `vsce publish`；本仓库先提供本地 VSIX。

## 六、0.3.0 追加：智能拆分（2026-11-20 决策）

- **应用范围**：应用一组 = 用可编辑的 message 提交该组文件；其他已暂存文件保留，但提交时会一起带上，需提示。提交后可在同一弹窗推送远程分支，可选附加 Tag；仍有其他组时弹窗不关闭。
- **分组方式**：纯 LLM 分组；插件严格校验完整文件划分，失败重试一次，仍失败回退确定性分组。
- **入口**：提交面板新增独立动作 `Suggest Commit Split`，默认可见。
- **组数**：1–6 组；只有 1 组时明确提示“当前变更较连贯，建议一个提交”，仍可应用。
- **UI**：分组列表 + 每组 message/file 预览；应用前确认；暂存后提示未创建 commit。
- **安全边界**：不 reset 暂存区；commit 使用 path-limited 提交，其他已暂存文件不进入该提交；push 需用户显式点击并确认远程/分支/Tag。
- **版本**：本轮目标版本 `0.3.0`。
