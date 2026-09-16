# Commit Helper — 实施规格（Spec）

> 本文件由 grill 会话产出，目标读者是**零上下文的执行者**。所有决策均已定稿；带 `[默认]` 标记的是实现细节默认值，可改但不影响验收。

## 1. 目标

IntelliJ 平台插件 `Commit Helper`：在**提交消息框旁**提供"整理提交信息"入口，一键把**本次将要提交的变更**归纳成可直接提交的 commit message（首行摘要 + 正文分条列出变更项）。

解决动机（Alternatives 结论）：
- **不用**内置 AI Assistant 的 Generate Commit Message：不读 `.gitmessage` 骨架、不能指定自己的 LLM endpoint、输出格式与分组不可控、降级路径不可见。
- **不用** Marketplace 同类插件：同类插件要么只支持 OpenAI、要么中文支持差、要么不读 commit 模板、要么把代码上传到其自有服务。
  - ⚠️ 证据缺口：用户未指出具体插件名与具体痛点，此项为一般性陈述，不影响实现。

## 2. 交付分期

| 期 | 内容 | 独立可交付 |
|---|---|---|
| **M1** | 入口按钮 + 混合归纳 + 格式/提示词模板 + 设置页 + 覆盖策略 + 降级 | ✅ 可安装可用 |
| **M2** | 读取 `git commit.template` / `.gitmessage` 作为输出骨架 | ✅ |
| **M3** | `{issue}` 编号注入 + changelog 生成 + PR 描述生成（仅预览/复制） | ✅ |

每期结束都必须满足第 9 节验收门槛中适用于该期的部分。

## 3. 技术栈与兼容

- Kotlin（JVM toolchain 21，`jvmTarget = 21` 或 17，见 §3.2），Gradle Kotlin DSL。
- `org.jetbrains.intellij.platform` Gradle 插件 `2.19.0`，Gradle `8.13`（wrapper 固定）。
- **编译目标平台：IntelliJ IDEA Community `2024.1`（since-build `241`）**；`until-build` 不设上限。
- 依赖：`com.intellij.modules.platform`、`Git4Idea`（bundled plugin）。**不得**引用任何 Ultimate-only 包（`com.intellij.idea.ultimate.*`）。
- 目标 IDE：IDEA / PyCharm / Android Studio / WebStorm 等（多 IDE 兼容）。
- UI 文案：**英文**；README：中英双语。

### 3.1 已核实的平台事实（本机 IU-262.9437.185 反编译验证）

| 事实 | 证据 |
|---|---|
| EP `com.intellij.vcs.commitMessageProvider` 存在，接口 `com.intellij.openapi.vcs.changes.ui.CommitMessageProvider`，`dynamic="true"` | `lib/intellij.platform.vcs.jar` → `META-INF/plugin.xml` |
| **262 的该接口只剩** `String getCommitMessage(LocalChangeList, Project)`，**无 icon/title/Context** | `javap` 输出 |
| action group `Vcs.MessageActionGroup` 存在（消息框工具栏，公告于 platform vcs plugin.xml） | `lib/intellij.platform.vcs.jar` plugin.xml |
| `VcsDataKeys.COMMIT_WORKFLOW_UI` → `com.intellij.vcs.commit.CommitWorkflowUi`，含 `getIncludedChanges(): List<Change>`、`getIncludedUnversionedFiles(): List<FilePath>`、`getCommitMessageUi()` | `javap` |
| `com.intellij.vcs.commit.CommitMessageUi` 含 `getText/setText/focus/startLoading/stopLoading` | `javap` |
| `VcsDataKeys` 另有 `COMMIT_MESSAGE_CONTROL`(`CommitMessageI`)、`CHANGES`、`CHANGE_LISTS`、`COMMIT_WORKFLOW_HANDLER` | `javap` |

### 3.2 241 兼容策略（关键设计决策）

`com.intellij.vcs.commit.*`（`CommitWorkflowUi` 等）是 2024.2 提交 UI 重构引入的，**241 不存在**。因此：

1. **入口不使用 `commitMessageProvider` EP**（其接口签名跨版本不一致，实现类会在另一版本抛 `AbstractMethodError`）。改为注册普通 `AnAction` 并 `add-to-group group-id="Vcs.MessageActionGroup"`。
2. 数据获取写成两层适配器 `CommitContextAdapter`：
   - **新版（242+）**：`VcsDataKeys.COMMIT_WORKFLOW_UI` → `getIncludedChanges()` / `getIncludedUnversionedFiles()` / `getCommitMessageUi()`。
   - **旧版（241）**：`VcsDataKeys.CHANGES` + `VcsDataKeys.COMMIT_MESSAGE_CONTROL`。
   - `COMMIT_WORKFLOW_UI` 这个 `DataKey` 字段在 241 不存在，**必须通过反射读取静态字段**，并用反射调用 `DataContext.getData` / `getIncludedChanges` / `getIncludedUnversionedFiles`。仅此一个类允许反射，其余代码正常静态编译。
3. `jvmTarget` = `17`（241 运行时 JDK 为 17，避免 `UnsupportedClassVersionError`）；Gradle toolchain 可用 21 编译，但字节码目标必须 ≤17。

## 4. 功能需求

### M1-1 入口与触发
- 在提交消息框工具栏出现一个图标按钮（英文 title `Organize Commit Message`，description 说明会发送代码内容给配置的模型）。
- **点击才生成**，绝不会在打开提交框时自动请求。
- 该 Action 注册为普通 Action（可在 Keymap 中自行绑定快捷键），但不预设默认快捷键。
- **绝不自执行任何 git 写操作**：不 commit / push / amend / rebase。
- ⚠️ **对问题 3「另外给个打开对话框时自动生成的开关」的偏离**（见 §11 第 13 条）：
  该开关**不实现**，设置页里没有它。原因：跨 241–262 不存在可用的异步钩子
  （2024.2+ 的 `DelayedCommitMessageProvider` 在 241 不存在），
  而同步实现会在提交框打开时阻塞 UI 做网络请求。此项列入非目标。

### M1-2 变更范围（输入界定）
- 归纳对象 = **提交面板中勾选的文件**（`getIncludedChanges()` + `getIncludedUnversionedFiles()`）。
- 旧版回退：`VcsDataKeys.CHANGES`。
- 若范围为空 → 通知 "No changes selected" 并中止，不发任何网络请求。
- diff 采集：优先用 git4idea 的 `GitLineHandler` 执行真实 git（尊重 IDE 配置的 git 可执行文件）：
  - 已跟踪文件：`git diff HEAD --no-color --unified=3 -- <paths>`（同时覆盖 staged 与 unstaged 相对 HEAD 的差异）。
  - 未跟踪新文件：`git diff --no-index --no-color /dev/null <path>`，失败则读 `Change.getAfterRevision().getContent()` 并自行拼 new-file diff。
  - 部分暂存（同一文件同时有 staged/unstaged 片段）时以 `HEAD` 差异为准，并在 UI 上不额外提示（`[默认]`，已知精度损失可接受）。

### M1-3 过滤与预算
- **排除**：二进制扩展名（图片/字体/压缩包/class/jar/so/dylib/exe/pdf…）；lock 文件（`package-lock.json`、`pnpm-lock.yaml`、`yarn.lock`、`Gemfile.lock`、`poetry.lock`、`Cargo.lock`、`composer.lock`、`go.sum`）；目录 `dist/`、`build/`、`out/`、`target/`、`node_modules/`、`.gradle/`、`vendor/`；`*.min.js`、`*.map`、`*.generated.*`；`.snap` 快照文件。
- **单文件 diff 上限**：200 行（超出保留前 200 行并加 `... [truncated N lines]`）。
- **总量预算**：60,000 字符（约 15k tokens）。超出时按"变更行数降序"选取文件直至预算用尽，其余记为 omitted。
- 送入模型的文本必须在开头显式声明抽样状态，例如：
  `NOTE: diff is sampled. Included X of Y changed files, Z files omitted due to size limits: ...`
- 过滤/抽样规则全部集中在一个纯函数模块（可单测，无 IDE 依赖）。

### M1-4 混合生成（本地分组 + LLM 措辞）
1. **本地骨架（不依赖模型）**：按变更文件的顶层模块/目录分组；每条含文件路径、增删行数、变更类型（added/modified/deleted/renamed）。分类启发式：测试路径（`test/`、`*Test.kt`、`__tests__/`）、文档（`*.md`、`docs/`）、构建与 CI（`*.gradle*`、`pom.xml`、`.github/`、`Dockerfile`）、配置（`*.yml`、`*.json`、`*.properties`、`*.toml`）、依赖（lock/manifest）。
2. **LLM 措辞**：把本地骨架 + 抽样 diff 交给模型，产出 `subject` 与 `items[]`。
3. 模型返回**必须**是严格 JSON：`{"subject": "...", "items": [{"type": "feat|fix|refactor|docs|test|build|chore|perf|style", "scope": "...", "text": "..."}], "issue": "PROJ-123"|null}`。解析失败时做一次宽松重试（剥离 ``` 代码块围栏后再解析），仍失败则走降级。

### M1-5 降级路径（必须实现）
触发任一失败（未配置 key、连接失败、超时、非 2xx、JSON 解析失败）时：
- **不写任何报错到消息框**；改为用**本地骨架**渲染 commit message 并填入。
- 同时弹一条 `Notification`（balloon，`NotificationType.WARNING`/`ERROR`），文案含简短原因，并带 **Retry** action 重新走一次 LLM 路径。
- 不自动重试（无内建退避）。

### M1-6 模板系统
两类模板，均在设置页可编辑，均可 **Reset to default**：

**(a) 输出格式模板**（决定最终排版），占位符：
| 占位符 | 含义 |
|---|---|
| `{subject}` | 首行摘要 |
| `{items}` | 分条正文（由 `itemLine` 子模板渲染后以换行连接） |
| `{itemLine}` | 单条格式，可用 `{type}` `{scope}` `{scopeRaw}` `{text}` |
| `{issue}` | issue key 或空串（M3） |
| `{skeleton}` | `.gitmessage` 骨架正文（M2；无骨架时为空） |

默认格式模板：
```
{subject}

{items}
```
默认 `itemLine`：`- [{type}]{scope} {text}`，其中 `{scope}` **渲染为 ` (模块)`（含前导空格）**，
无 scope 时渲染为空串，因此实际输出为 `- [feat] (api) 文本` 或 `- [feat] 文本`；
需要裸 scope 时用 `{scopeRaw}`。

内置预设（设置页下拉，选中即写入模板文本框，仍可手改）：`Conventional Commits`、`Bullet list (plain)`、`Chinese itemized`。

**(b) 提示词模板**（system prompt），占位符：`{language}`、`{format}`、`{skeleton}`、`{diff}`、`{fileList}`。
默认中文提示词要求：只输出 JSON、不要解释、不要 Markdown 围栏、按变更语义合并同类项、禁止臆造未在 diff 中出现的内容、subject ≤ 50 字符且不加句号。

### M1-7 语言
设置项 `Language`：`中文` / `English` / `Auto (follow repository)`，**默认 `中文`**。
`Auto` = 读取 `git log -20 --pretty=%s`，按中文字符占比 > 10% 判定中文，否则英文；git 调用失败时回落 `中文`。

### M1-8 覆盖策略
- 消息框为空 → 直接写入。
- 消息框非空 → 弹 `DialogWrapper` 预览，左右/上下展示 `Current message` 与 `Generated message`，按钮：**Replace**、**Append to end**、**Cancel**。
- 绝不静默覆盖。

### M1-9 设置页
位置：`Settings/Preferences → Tools → Commit Helper`。

| 分组 | 项 |
|---|---|
| Provider | 下拉：`OpenAI-compatible` / `Anthropic` / `Gemini` / `Local only (no LLM)` |
| Connection | `Base URL`（含默认值：OpenAI 兼容 `https://api.openai.com/v1`、Anthropic `https://api.anthropic.com`、Gemini `https://generativelanguage.googleapis.com`）、`API key`（`PasswordField`，存进 `PasswordSafe`，不落盘明文）、`Model`（纯文本输入，不拉取模型列表）、`Temperature`（默认 0.2）、`Max output tokens`（默认 1024）、`Timeout seconds`（默认 60） |
| Behavior | `Confirm before sending code to model`（默认 off）、`Include diff content`（默认 on；关闭则只发文件列表与统计）、`Use repository commit template`（默认 on，M2） |
| Language | 语言下拉（§M1-7） |
| Templates | 格式模板文本框、itemLine、预设下拉、prompt 文本框、各自 Reset |
| Limits (advanced) | 单文件行上限（200）、总字符预算（60000）、排除规则附加（多行 glob） |
| 验证 | `Test connection` 按钮：发送一个固定极短请求，成功/失败以 inline 文案展示（不弹窗） |

每个 provider 各自独立保存 `baseUrl / apiKey / model`（切换 provider 不丢配置）。

### M1-10 隐私
- 只把**勾选的变更 diff 文本**发送到用户配置的 endpoint；不发全仓库、不发历史提交、不发文件绝对路径之外的内容（路径会发，用户可在排除规则里剔除）。
- 无任何遥测上报、无使用统计。
- API key 存 `PasswordSafe`（`CredentialAttributes(serviceName = "CommitHelper:<provider>")`），**禁止**写入 XML 设置、日志、异常消息。
- `Confirm before sending code to model` 打开时，发送前弹确认框，列出将发送的文件清单与字符数。

### M2-1 `.gitmessage` 骨架
查找顺序（命中即止）：
1. `git config --get commit.template`（仓库级优先，回退全局），相对路径按仓库根解析；
2. `<repoRoot>/.gitmessage`
3. `<repoRoot>/.gitmessage.txt`
4. 无 → 骨架为空。

行为：
- 以 `#` 开头的行视为注释，**保留原样**并留在输出中（git 提交时会自动剥离）。
- 模板中的分区标题行（如 `# Why:`、`## Changes`、`[Type]` 等非空非注释行）作为锚点，把生成的条目插入到最相关的分区标题之下；无法判断时追加到模板末尾前。
- **优先级**：仓库 `.gitmessage` 骨架优先于内置格式模板的 `{subject}/{items}` 排版；冲突时保留骨架结构，只填充条目。生成结果的通知/预览中标注 `Used repository commit template`。
- 设置项 `Use repository commit template` 关闭时不读取。

### M3-1 `{issue}` 编号注入
- 提取顺序：当前分支名（`git rev-parse --abbrev-ref HEAD`）→ 最近 20 条提交 subject；正则 `[A-Z][A-Z0-9]{1,9}-\d+`，取首个命中。
- **不调用** Jira / GitHub API，不做任何鉴权与网络查询。
- 结果作为模板变量 `{issue}` 注入；放哪里完全由用户的格式模板决定（默认模板中不出现 `{issue}`）。

### M3-2 changelog 与 PR 描述
- 入口：独立 `AnAction`，挂到 `Tools` 菜单与 `Vcs.MessageActionGroup` 的第二个图标；结果在带三个 Tab（`Commit Message` / `Changelog` / `PR Description`）的预览对话框中展示，每个 Tab 带 `Copy` 按钮。
- **changelog**：范围 = M1-2 的本次勾选变更，按 type 分组的条目列表。
- **PR 描述**：范围 = 当前分支相对默认分支（`git symbolic-ref refs/remotes/origin/HEAD` → 如 `origin/main`，回退 `main`，再回退 `master`）的 `base...HEAD`；内容 = `git log --oneline base..HEAD` 摘要 + `git diff --stat base...HEAD` 统计。
- **不写文件**（不改 `CHANGELOG.md`）、**不调** GitHub/GitLab API、不创建 PR。

## 5. 非目标（明确不做）

1. 不自动执行任何 git 写操作（commit / push / amend / rebase）——避免"AI 误提交"的信任事故。
2. 不做对话式交互或多轮改写：一次生成，不满意就再点一次。
3. 不做团队配置共享、云同步、遥测埋点、使用量上报。
4. 不做基于 tag / 版本区间的 changelog（只做本次提交与当前分支范围）。
5. 不做模型列表自动拉取、不做 token 计费统计、不做多模态（图片）输入。
6. **不做"打开提交框自动生成"**：241–262 之间没有统一的异步钩子可用，
   同步实现会在提交框打开时阻塞 UI 做网络请求（对应用户在问题 3 里提到的那个开关，
   已确认放弃）。

## 6. 数据流

```
[Vcs.MessageActionGroup 图标点击]
  → CommitContextAdapter(project)
      新版: COMMIT_WORKFLOW_UI.getIncludedChanges()/getIncludedUnversionedFiles()
      旧版: CHANGES + COMMIT_MESSAGE_CONTROL
  → ChangeCollector: git diff per file (GitLineHandler)
  → DiffFilter: 排除噪声 → 单文件截断 → 总量预算抽样 → 生成 fileList/diff 文本
  → LocalGrouper: 模块分组 + 类型分类 → skeleton items
  → SkeletonReader (M2): .gitmessage → skeleton 文本
  → PromptBuilder: prompt 模板 + {language}/{format}/{skeleton}/{diff}/{fileList}
  → ProviderClient (OpenAI 兼容 | Anthropic | Gemini)   [Local only 时跳过]
  → LlmResponseParser: 严格 JSON（失败→宽松重试→降级）
  → MessageRenderer: 格式模板 + items (+ skeleton 覆盖) → 最终文本
  → MessageWriter: 空框直写 / 非空弹预览(Replace|Append|Cancel)
  → 失败: 本地骨架渲染 + Notification(Retry)
```

## 7. 模块划分（建议）

```
com.caye.commithelper
├─ action/       OrganizeCommitMessageAction, GenerateChangelogAction
├─ adapter/      CommitContextAdapter (唯一允许反射的类)
├─ collect/      ChangeCollector, GitDiffService
├─ filter/       DiffFilter, ExclusionRules        ← 纯逻辑，可单测
├─ group/        LocalGrouper, ChangeClassifier     ← 纯逻辑，可单测
├─ template/     PromptBuilder, MessageRenderer, DefaultTemplates, SkeletonReader ← 纯逻辑，可单测
├─ issue/        IssueKeyExtractor                   ← 纯逻辑，可单测
├─ llm/          ProviderClient(interface), OpenAiCompatClient, AnthropicClient, GeminiClient, JsonCodec
├─ ui/           PreviewDialog, ChangelogDialog, settings/CommitHelperConfigurable + SettingsState
├─ notify/       ErrorNotifier
└─ CommitHelperBundle (messages)
```

## 8. 默认常量

| 常量 | 值 |
|---|---|
| 单文件 diff 行上限 | 200 |
| 总字符预算 | 60,000 |
| 连接超时 / 读超时 | 10s / 60s |
| temperature | 0.2 |
| max output tokens | 1024 |
| subject 长度上限 | 50 字符 |
| `git log` 语言探测条数 | 20 |
| HTTP 客户端 | JDK `java.net.http.HttpClient`；JSON 用 platform 自带 Gson（不引 okhttp/官方 SDK） |

## 9. 验收标准

**M1**
1. `./gradlew buildPlugin` 成功，产出 `build/distributions/*.zip`。
2. `./gradlew verifyPlugin` 对 `IU-241.*`、`IU-243.*`、`IU-262.*` 无 `compatibility problems`（`internal API` 警告可接受但须列出）。
3. 单测覆盖（纯逻辑，无 IDE 依赖）：
   - `DiffFilter`：二进制/lock/dist 排除、单文件截断、总量预算抽样、抽样声明文本；
   - `MessageRenderer`：三种预设模板渲染、`{itemLine}` 与 `{scope}` 空值分支、骨架覆盖；
   - `PromptBuilder`：占位符全替换、无残留 `{...}`；
   - `IssueKeyExtractor`：分支名命中、提交历史命中、无命中返回 null、`abc-1` 小写不误判；
   - 三个 provider 的请求体构造（HTTP 层 mock，断言 URL / header / body 结构）。
4. 沙箱手工冒烟（`./gradlew runIde`，用本机 IU-262 对照）7 条：
   (a) 空消息框点图标 → 填入消息；
   (b) 已有手写消息 → 弹预览，Cancel 不改变原文；
   (c) 未配置 key → 本地骨架消息 + 带 Retry 的警告通知；
   (d) 勾选 50+ 文件的提交 → 生成的 prompt 含抽样声明且未超预算；
   (e) 仓库放入 `.gitmessage` → 输出保留其分区标题与注释行；
   (f) 切换 `Language` 中/英 → 输出语言随之变化；
   (g) 通知里点 Retry → 重新发起请求。

**M2**：冒烟 (e) 通过；`.gitmessage` 缺失时行为与 M1 一致；设置项关闭后完全不读模板。

**M3**：分支名 `feature/PROJ-123-x` 时 `{issue}` 渲染为 `PROJ-123`；changelog/PR 描述可生成并复制；全程无仓库文件改动（`git status` 干净）与无外部 API 调用。

## 10. 发布

- 免费插件，**本地手动**发布（不在仓库存 Marketplace token）：`./gradlew signPlugin` → `./gradlew publishPlugin`。
- vendor：`sunpengfei <spf@caye.com>`（来自本机 git 全局配置）。
- plugin id `com.caye.commithelper`，name `Commit Helper`，license **Apache-2.0**。
- 签名证书与 `PUBLISH_TOKEN` 由用户在本地 `~/.gradle/gradle.properties` 或环境变量提供，仓库内只留配置位。
- README 中英双语；顺带提供 `README.zh.md`。

## 11. 实施记录（实现期新增/修正的决策与证据）

1. **工具链**：Gradle `9.3.0` + Kotlin `2.4.20` + IntelliJ Platform Gradle Plugin `2.19.0`。
   该插件 **要求 Gradle 9.0.0+**，原先设想的 8.13 不可用（实测报错）。
2. **构建环境约束**：Gradle 官方分发 `services.gradle.org` 会 307 跳转到 github.com，
   在本机网络下不可达；`gradle-wrapper.properties` 因此指向腾讯镜像。
   另：DSH 文件沙箱下 `~/.gradle` 不可写（原生库解压失败），构建必须带
   `GRADLE_USER_HOME=<workspace>/.gradle-home`。
3. **JVM 目标**：Kotlin 插件的 `jvmTarget` 配置会被平台插件覆盖，必须用
   `tasks.withType<KotlinJvmCompile>` 任务级配置才能固定到 17；否则报
   "Inconsistent JVM-target compatibility (compileJava 17 vs compileKotlin 21)"。
4. **入口实现**：未使用 `commitMessageProvider` EP，而是 `AnAction` +
   `add-to-group group-id="Vcs.MessageActionGroup"`（新旧提交 UI 通用）。
   `CommitWorkflowUi` / `COMMIT_WORKFLOW_UI` 仅在 `CommitContextAdapter` 内反射访问。
5. **`git symbolic-ref` 不可用**：`GitCommand` 枚举没有 `SYMBOLIC_REF`，
   默认分支改用 `git rev-parse --abbrev-ref origin/HEAD`，再回退 `main` / `master` 的 `--verify`。
6. **测试框架（结论已修正）**：最初不引入 `TestFrameworkType.Platform` 时，
   测试进程会因 `com.intellij.tests.JUnit5TestSessionListener` 无法实例化而启动失败；
   真正的原因是 `tasks.test` 里手写的 `systemProperty("java.awt.headless", "true")`
   覆盖了平台插件注入的属性。**去掉该覆盖后，平台测试框架工作正常**，
   现在同时跑纯逻辑测试（JUnit 5）与平台测试（`BasePlatformTestCase`，经 vintage engine）。
   注意 `com.intellij.testFramework.junit5.TestApplication` 在 241 **不存在**
   （241 的 `testFramework.junit5` 只有 `DynamicTests`/`NamedFailure`），所以平台测试用 JUnit 3 风格。
7. **`{scope}` 语义**：带前导空格（见 §M1-6），保证默认模板在有无 scope 时都只有单个空格。
8. **glob 语义**：不含 `/` 的排除 glob 按**文件名**匹配（类 gitignore 直觉），
   含 `/` 时按完整相对路径匹配，`**` 跨目录。
9. **骨架标题识别**：先找 `# ...:` 冒号标题；只有当不存在冒号标题时，
   才回退到"长度 ≤ 40 且命中关键词"的注释行。否则 git 自带的 boilerplate
   "…for your changes." 会抢先被当成标题。
10. **2026.2 的发行版类型**：IDEA Community 自 2025.3 (253) 起不再单独发布。
    Plugin Verifier 对 2026.2 必须用统一发行版 `IntelliJPlatformType.IntellijIdea`，
    `IntellijIdeaCommunity` 会解析失败（实测：“Could not find idea:ideaIC:2026.2”）。
11. **未跟踪新文件**：不再尝试从 `Change` 取内容（提交面板里它们常常不在
    `getIncludedChanges()` 中，只在 `getIncludedUnversionedFiles()` 里），
    改为直接读磁盘文件（> 1 MB 跳过），再合成 new-file diff。
12. **多仓库路径前缀**：仓库根可能不是项目根，git diff 里的路径带前缀，
    因此 diff 块查找在精确匹配失败后按后缀匹配。
13. **放弃"自动生成"开关**（对问题 3 回复的偏离，已在 §M1-1 与 §5 标注）：
    新版提交 UI 的异步钩子是 `com.intellij.vcs.commit.DelayedCommitMessageProvider`
    （2024.2+），241 不存在；而 `CommitMessageProvider` 的同步回调会在 UI 线程上
    阻塞一次网络往返。与其留一个会卡界面的开关，不如只保留"点击生成"，
    并把这条写进非目标。
14. **Plugin Verifier 的 home**：默认写 `~/.pluginVerifier`，在沙箱/受限 CI 下不可写；
    已用 `-Dplugin.verifier.home.dir=<project>/.verifier-home` 指到工程内。
15. **`Document` 写入必须包在 write action 里（由测试发现的真实缺陷）**：
    `LegacyMessageTarget.setText` 原本直接 `document.setText(...)`，而调用方是从
    `invokeLater` 的 EDT 回调进来的、并不持有写锁 —— 在 2024.1 旧提交框上会抛
    "Write access is allowed inside write-action only"。现已改为
    `isWriteAccessAllowed ? run() : runWriteAction(...)`；`ReflectiveWorkflowTarget.setText`
    同样做了防御性包裹（嵌套 write action 合法）。这个缺陷只有无头平台测试能发现，
    纯逻辑单测和 Plugin Verifier 都看不见。

16. **真实 IDE 上"勾选文件读不到"的根因与修复（用户实机反馈的缺陷）**：用户在自己的
    IU-2026.2.1 上点工具栏图标，得到 "No changes are checked in the commit panel."，
    但面板里确实勾了文件。根因不是一个点，而是"只问了一个数据源"：
    - 实测 IC-262 字节码：`CommitMessage.uiDataSnapshot` 只发布
      `COMMIT_MESSAGE_CONTROL` 与 `COMMIT_MESSAGE_DOCUMENT` 两个 key —— 消息框工具栏
      的 data context 里根本看不到勾选文件；
    - 模态提交对话框 `CommitChangeListDialog` 虽然实现了 `SingleChangeListCommitWorkflowUi`
      （即 `CommitWorkflowUi`），但 `DialogWrapper` **不是** `DataProvider`，
      所以它永远不出现在任何 data context 上；
    - `invokeList` 失败时静默返回 null，于是"读不到面板"和"没勾文件"在用户看来完全一样。
    修复按"权威优先"顺序取源，并对不可读做诚实区分：① 事件自带的 data context；
    ② 用发布出来的 `COMMIT_MESSAGE_DOCUMENT` 经平台自己的 `CommitMessage.DATA_KEY`
    （构造函数会写 `document.putUserData(DATA_KEY, this)`）拿到消息组件，
    再用 `DataManager.getDataContext(组件)` 让平台展开外层面板的 `UiDataProvider` 链；
    ③ `DialogWrapper.findInstance(组件)` 拿到模态对话框本体；
    ④ 旧 key 兜底。来源对象用鸭子类型（`getIncludedChanges` + `getIncludedUnversionedFiles`）
    识别，因此不需要在 241 上引用 `com.intellij.vcs.commit.*`。
    `CommitContext.readFailure` 现在把"面板不可读"与"没勾文件"分开提示。
    刻意**没有**采用 `CommitMessage.CHANGES_SUPPLIER_KEY` 作为内容源：实测
    `CommitChangeListDialog` 传的是 `ChangeListChangesSupplier(getChangeList())`，
    即**整个 change list**（含未勾选项），拿它当内容源会把用户没勾的文件 diff 发给模型。
    **已经用户在真机确认修复生效**（其自身 IU-2026.2.1 + 真实多仓库工程）：重装该构建后
    点击工具栏图标不再出现误报。这条确认也覆盖了第 ②/③ 来源——无头测试无法覆盖、
    只可能成立在真实窗口下的路径。

17. **改名与发布准备**（用户要求 `idea-commit-helper` → `git-commit-helper`，并选择下一步做发布）：
    - 目录改名，同时改 `settings.gradle.kts` 的 `rootProject.name`（否则产物还是旧名），
      产物现为 `build/distributions/git-commit-helper-0.1.0.zip`；plugin id / 显示名不变。
    - 展示内容改为 HTML 源文件 `marketplace/description.html`、`marketplace/change-notes.html`
      （Marketplace 渲染 HTML；原先从 Markdown README 截取的描述会把 `##`/`**` 原样显示），
      vendor 链接改由 `pluginVendorUrl` 提供。
    - `verifyPluginProjectConfiguration` 报出 Kotlin stdlib 依赖冲突 → 按提示设
      `kotlin.stdlib.default.dependency=false`（平台自带 stdlib，重复打包会在运行时撞版本）；
      改后 67 个测试仍全绿，该项校验变干净。
    - **`verifyPluginSignature` 有两个真实缺陷**（本地签名自检时暴露，属构建脚本缺口而非插件逻辑）：
      ① 它消费 `signPlugin` 的产物却没声明任务依赖，Gradle 直接以
      "uses this output … without declaring an explicit or implicit dependency" 失败；
      ② 它把 `certificateChain` **原样**写进 `certificate-chain.pem`（不像 `signPlugin` 会解 base64），
      于是环境变量里的单行 base64 被当成证书内容，报 `CertificateException: No certificate data found`
      （已用 2.19.0 字节码确认：`createTemporaryCertificateChainFile` 只做 `writeText`）。
      两者都在 `build.gradle.kts` 修好（显式 `dependsOn` + 带 fallback 的 base64 解码），
      `signPlugin verifyPluginSignature` 实跑通过并产出 `-signed.zip`。
    - 密钥生成在工程外：`~/.commit-helper-signing/`（自签证书 `CN=sunpengfei`，2026-09-16 → 2036-09-13），
      `publish-env.sh` 为 600 权限，含三个环境变量的单行 base64；私钥不进工程、不进版本库。
    - 途中一次 `verifyPlugin` 因 `plugins.jetbrains.com` 连接超时失败（`ConnectException`），
      重跑即通过 —— 记为环境瞬时故障，不是代码问题。
    - 发布清单见 `PUBLISHING.md`；首次上传必须走网页（官方规定），之后才可用 `publishPlugin`。
      选择开源协议（本项目 Apache-2.0）时 Marketplace 要求提供**公开源码链接**，这是当前唯一的前置阻塞项。
    - 公开源码落点：`s9797456/git-commit-helper`（公开，默认分支 `main`；用户先建成
      `git-commit-help` 后改名，API 一度返回 301，已按规范名同步 `origin` 与 `pluginVendorUrl`）。
      `.gitignore` 已含 `*.pem`/`*.crt`/`publish-env.sh` 等，密钥与大目录均未入库。
      `github.com:443` 连接成功率约 1/5（`api.github.com` 稳定），失败表现为 75s 超时；
      连接成功时 push 报的是 `could not read Username … terminal prompts disabled`，
      即网络与配置本就就绪，唯一缺口是凭据（本机无 `gh`、无 SSH key、无 credential helper）。
      用户提供 token 后**首次推送成功**（凭据只经命令行 HTTP header 传递，不落任何文件、
      不进 `.git/config`），`api.github.com` 核对远端 `main` HEAD 与本地一致。

18. **上架条目与推广物料**：Marketplace 上**条目已创建**（`api/plugins/34303` 核对到
    `xmlId = com.caye.commithelper`（与包内一致）、名称 Commit Helper、vendor 组织 `me-tool`，
    页面 <https://plugins.jetbrains.com/plugin/34303-commit-helper>），但**尚无已批准版本**：
    公开更新源 `plugins/list?pluginId=com.caye.commithelper` 仍返回空的 `<plugin-repository/>`，
    `api/plugins/34303/updates` 为 `[]`，`downloads: 0` —— 即首次上传未提交或仍在审核，
    用户在 IDE 内还搜不到。为此加了 `scripts/listing-status.sh` 一键查询（条目信息 + 该公开源），
    并把 README 的版本徽章换成静态徽章：`jetbrains/plugin/v/34303` 在无版本时渲染为
    `marketplace: invalid response data`，等有版本后再换回（脚本里有提示）。
    - **平台限制导致的做法区分**：GitHub 会过滤 Markdown 里的 `<script>`，所以官方 widget
      （`card` / `install`）不能放 README —— README 用 shields.io 徽章
      （`jetbrains/plugin/v|d/34303`，实测 200；评分端点目前取不到，暂不放），
      widget 另存到真实承载页 `marketplace/widget.html`（含两个 widget 与源码片段）。
    - 记录一处不一致：包内 vendor 是 `sunpengfei / spf@caye.com`，Marketplace vendor 档案是
      `me-tool / 593259523@qq.com`。不影响安装更新，统一与否属用户决定，未擅自改动。

### 真机确认（此前唯一挂着的"读不到面板"缺口）

| 项 | 状态 |
|---|---|
| 用户本人 IU-2026.2.1、真实多仓库工程中点击工具栏图标 | ✅ 不再是 "No changes are checked in the commit panel."（2026-09-15 用户确认） |
| 出问题/被救回的界面 | 提交**工具窗口面板**（非模态）→ 确认起作用的是第 ② 条来源：`DataManager.getDataContext(提交消息组件)` 让平台展开了 `mainPanel` 的 `UiDataProvider` 链拿到 `COMMIT_WORKFLOW_UI`；第 ③ 条（模态对话框 `DialogWrapper.findInstance`）仍属未经真机验证的防御性代码 |

### 已验证 / 未验证

| 项 | 状态 |
|---|---|
| `./gradlew test`（67 个测试，含 21 个平台级测试） | ✅ 通过（修复前 `CommitContextAdapterTest` 两条如实失败，修复后全绿） |
| `./gradlew buildPlugin`（产出 zip） | ✅ 通过，`build/distributions/git-commit-helper-0.1.0.zip` |
| `patchPluginXml` 产出合法 plugin.xml（since-build 241、action/EP 齐全） | ✅ 已解包核对 |
| `./gradlew verifyPlugin`（241 / 243 / 262） | ✅ 三版本全部 `Compatible`，各 1 处 deprecated API；报告里**无结构性问题**（即 `add-to-group` 的 group-id 在三版本均可解析）。改名后又复跑一次，结论一致 |
| `./gradlew verifyPluginProjectConfiguration` | ✅ 无问题（修掉 Kotlin stdlib 冲突后） |
| `./gradlew signPlugin verifyPluginSignature` | ✅ 产出 `build/distributions/git-commit-helper-0.1.0-signed.zip` 且签名校验通过 |
| `./gradlew runIde` 冷启动（IC-2024.1 空配置沙箱） | ✅ 日志 `Loaded custom plugins: Commit Helper (0.1.0)`，**0 条异常堆栈** |
| 无头自动化的"冒烟"覆盖（见下） | ✅ 8/12 条 |
| GUI 冒烟 | ⚠️ 见下：仅剩需要真实窗口/真实网络的场景留给用户 |

### 已被自动化测试覆盖的冒烟点

| 冒烟场景 | 覆盖它的测试 |
|---|---|
| Action 出现在提交框工具栏（而非只是"注册了"） | `PluginWiringTest.testOrganizeActionSitsInTheCommitMessageToolbarGroup` |
| 未选任何文件时的提示 | `LocalPipelineTest.testEmptyChangeSetIsReportedInsteadOfWritingAnEmptyMessage` |
| 无 key / LOCAL_ONLY 走本地降级而不是报错 | `LocalPipelineTest.testLocalOnlyPipelineRendersCheckedFilesAndTheBranchIssueKey` |
| 大变更集/噪声文件抽样后仍能出消息 | `LocalPipelineTest.testNoiseFilesAreSampledWithoutLosingTheMessage` |
| 中英切换改变产出 | `LocalPipelineTest.testLanguageSwitchChangesTheGeneratedSubject` |
| `{issue}` 从分支名注入 | 同上第一条（分支 `feature/ABC-123-...`） |
| 设置页能构建、读写状态、Key 存取得回去 | `SettingsUiTest`（3 条） |
| changelog 入口挂到 Tools 菜单 | `PluginWiringTest.testChangelogActionIsReachableFromTheMainMenu` |
| 消息框为空 → 直接填入，不弹窗 | `CommitMessageWriteTest.testEmptyBoxIsFilledWithoutAnyDialog` |
| "面板读不到"与"没勾文件"被区分开（不再误报） | `CommitContextAdapterTest.testUnreadableContextIsReportedAsSuchInsteadOfAsAnEmptySelection` |
| 提交工作流 UI 的识别（鸭子类型）与多来源优先级 | `CommitContextAdapterTest.testWorkflowUiDuckTypingAcceptsOnlyCommitWorkflowShapedObjects`、`testWorkflowUiSelectionPrefersTheFirstUsableCandidate` |
| 已有文本绝不会被静默覆盖（替换/追加/取消语义） | `CommitMessageWriteTest` 的 merge 三条 |
| 2024.1 旧提交框（`COMMIT_MESSAGE_DOCUMENT`）读写 | `CommitMessageWriteTest.testLegacyDataContextIsReadThroughTheOldKeys` |

### 仍只能由人验证的部分（原因）

| 场景 | 为什么不能自动化 |
|---|---|
| 消息框非空 → 预览弹窗「替换/追加/取消」 | `PreviewDialog` 是模态 `DialogWrapper`，无头下无法点击；但**合并语义**已由 `CommitMessageWriteTest` 直接断言（替换=原文、追加=原有内容+空行+新内容、取消=null 即保持原样），2024.1 的旧 DataContext 读取路径也已断言 |
| 真实模型联通（OpenAI/Anthropic/Gemini） | 需要真实 API key 与出网；请求体已由 `ProtocolRequestTest` 断言 |
| `.gitmessage` 在真实提交框里的呈现 | 渲染逻辑已单测，但"出现在提交框里"需要 GUI |

实测 verifier 输出（1.410）：

```
IC-241.14494.240 against com.caye.commithelper:0.1.0: Compatible
IC-243.21565.193 against com.caye.commithelper:0.1.0: Compatible. 1 usage of deprecated API
IU-262.8665.258  against com.caye.commithelper:0.1.0: Compatible. 1 usage of deprecated API
Dynamic Plugin Eligibility: Plugin can probably be enabled or disabled without IDE restart
```

那 1 处 deprecated 用法是 `com.intellij.credentialStore.CredentialAttributes` 的
`requestor` 重载（`ApiKeyStore.attributes`）。`PasswordSafe` 只接受
`CredentialAttributes`，没有非废弃的等价构造方式；Kotlin 的三参写法会被解析到
`(String, String, Class)` 重载而编译失败，故保留并在此登记。

## 12. 证据缺口 / 待补充（不阻塞实现）

1. 被否决的第三方 Marketplace 插件具体名称与其具体痛点（用户只给了一般性理由）。
2. Marketplace vendor 账号与签名证书尚未生成（发布前需要，用户提供）。
3. Android Studio / PyCharm 只做 API 级兼容校验（Plugin Verifier），**没有**真机运行时冒烟证据。
4. Anthropic 与 Gemini 的请求/响应格式以 2026-09 当时的公开 API 为准，未在真实账号上验证；实现时需把"响应解析"隔离在各自 client 内，便于后续修正。
5. ~~第 16 条修复里的第 ②/③ 来源无法用无头测试覆盖~~ **已闭环**：测试态下平台装的是
   `com.intellij.ide.impl.HeadlessDataManager`（不遍历组件树，已用 241 字节码确认），
   所以这两条路径只能真机确认；用户已在自己的 IU-2026.2.1 上确认修复生效（见第 16 条）。
   保留的兜底：若将来在别的 IDE 上仍读不到，`CommitContextAdapter` 会打一条 WARN
   （含 data context 类名与各 key 的存在性），据此可一步定位。
