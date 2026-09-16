# 发布到 JetBrains Marketplace

工程侧能自动化的部分已经全部就绪；本文件只列"你要做的一次性动作"和"每次发版的动作"。
顺序执行即可，每步都给了可验证的输出。

## 0. 现状

| 项 | 状态 |
|---|---|
| plugin id / name / version | `com.caye.commithelper` / `Commit Helper` / `0.1.0` |
| since-build | `241`（2024.1+），无 until-build |
| 展示内容 | `marketplace/description.html`（2975 字符）、`marketplace/change-notes.html`（750 字符），构建时写入 plugin.xml |
| 本地验收 | `./gradlew clean test buildPlugin verifyPlugin verifyPluginProjectConfiguration` 全绿；67 测试；241/243/262 三版本 verifier `Compatible` |
| 签名 | ✅ 密钥已生成、`signPlugin` + `verifyPluginSignature` 实跑通过（见 §2） |
| 上传配置 | `intellijPlatform { publishing { token = env("PUBLISH_TOKEN") } }` 已就绪 |
| 产物 | `build/distributions/git-commit-helper-0.1.0.zip`（未签名）与 `…-0.1.0-signed.zip`（上传用），约 176 KB，上限 400 MB |

> 沙箱里跑 Gradle 需要 `GRADLE_USER_HOME=/Users/sunpengfei/code/deepseek/.gradle-home`；
> 你自己终端里不需要。

## 1. 发布前必须做的（工程侧已全部就绪）

1. ~~`gradle.properties` 的 `pluginVendorUrl`~~ ✅ 已填 `https://github.com/s9797456/git-commit-helper`。
2. ~~公开源码（Apache-2.0 上架必需）~~ ✅ 已推送到该公开仓库，见 §6。
3. **网页表单要填**：Vendor profile（名称/邮箱/网址）、License（Apache-2.0 + 上面源码链接）、
   Tags（建议 `VCS`/`Git`/`AI`/`Productivity`）、截图（可选但建议）。

## 2. 签名密钥（已完成，勿重复生成）

已在 `~/.commit-helper-signing/` 生成好，**私钥不要挪进工程目录、不要提交**：

| 文件 | 用途 |
|---|---|
| `private.pem`（600） | 签名私钥（`signPlugin` 用） |
| `private_encrypted.pem`（600） | 加密留档版，**建议备份到密码管理器/离线介质** |
| `chain.crt` | 自签证书，`CN=sunpengfei`，有效期 2026-09-16 → 2036-09-13 |
| `publish-env.sh`（600） | 已含 `CERTIFICATE_CHAIN` / `PRIVATE_KEY` / `PRIVATE_KEY_PASSWORD` 的单行 base64 export；密码也在里面 |

已验证可用（会产出 `build/distributions/git-commit-helper-0.1.0-signed.zip` 并校验通过）：

```bash
cd /Users/sunpengfei/code/deepseek/git-commit-helper
source ~/.commit-helper-signing/publish-env.sh
./gradlew signPlugin verifyPluginSignature
```

> 想自己重新生成：官方步骤见 <https://plugins.jetbrains.com/docs/intellij/plugin-signing.html>；
> `chain.crt` 与 `private.pem` 必须转成**单行 base64** 再放进环境变量。
> 注意 `verifyPluginSignature` 不像 `signPlugin` 那样自动解码 base64，构建脚本里已经帮你解码了。

## 3. 拿 PUBLISH_TOKEN

<https://plugins.jetbrains.com/author/me/tokens> → `Generate Token` → **只显示一次**，立刻存下：

```bash
echo "export PUBLISH_TOKEN='perm:...'" >> ~/.commit-helper-signing/publish-env.sh
```

## 4. 首次发布：必须网页手动上传

官方明确"the first plugin publication must always be uploaded manually"，Gradle 的
`publishPlugin` 只能用于**已有条目**的后续版本。

1. 用 JetBrains Account 登录 <https://plugins.jetbrains.com/author/me>；
2. `Upload plugin` → 选择/创建 **Vendor profile**（要接受
   [Developer Agreement](https://plugins.jetbrains.com/legal/developer-agreement)）；
3. 上传 `build/distributions/git-commit-helper-0.1.0.zip`（**签过名的那份**）；
4. 在网页上补：License（Apache-2.0 + 源码链接）、Tags（建议 `VCS`、`Git`、`AI`、
   `Productivity`）、截图（可选，强烈建议放 2–3 张）；
5. 提交后等审核（首次通常 1–3 个工作日）。

## 5. 之后每次发版

```bash
# 1) 改 gradle.properties 的 pluginVersion（Marketplace 不收同版本号的重复上传）
# 2) 更新 marketplace/change-notes.html
# 3) 本地验收 + 发布
cd /Users/sunpengfei/code/deepseek/git-commit-helper
./gradlew clean test buildPlugin verifyPluginProjectConfiguration
source ~/.commit-helper-signing/publish-env.sh
./gradlew publishPlugin
```

想先放 beta/EAP 频道（用户需自行添加对应仓库 URL 才能装）：

```kotlin
// build.gradle.kts
intellijPlatform { publishing { channels = listOf("beta") } }
```

## 6. 公开源码（Apache-2.0 上架必需）

✅ 已发布：<https://github.com/s9797456/git-commit-helper>（公开，默认分支 `main`）。
`origin` 与 `pluginVendorUrl` 都指向它，本地与远端一致（跟踪分支已建立）。

后续提交用带重试的推送（`github.com:443` 实测只有约 **1/5** 的连接成功率，失败表现为
75s 超时，重试即可；`api.github.com` 是稳定的）：

```bash
cd /Users/sunpengfei/code/deepseek/git-commit-helper
for i in $(seq 1 10); do
  git push && break
  echo "第 $i 次失败，重试…"; sleep 3
done
```

首次推送要 GitHub 用户名 + **Personal Access Token**（GitHub 早已不接受账号密码；classic 给
`repo` 权限，或 fine-grained 给 Contents: Read and write）。把 token 存进 keychain 可免去重复输入：

```bash
git config --global credential.helper osxkeychain   # 之后 push 输一次即可
```

有代理的话：`git -c http.proxy=http://127.0.0.1:<端口> push`。

Marketplace 上传表单里的 **Source code** 字段填上面那个仓库地址。

## 7. 排查

| 现象 | 原因 / 处理 |
|---|---|
| `publishPlugin` 报版本已存在 | Marketplace 不接受重复版本号，改 `pluginVersion` |
| 上传后页面显示 "unsigned" 警告 | `signPlugin` 没跑起来：`CERTIFICATE_CHAIN`/`PRIVATE_KEY` 环境变量未注入或不是单行 base64 |
| `signPlugin` 报证书解析失败 | 环境变量里换行没去掉：重新用 `tr -d '\n'` 生成 `publish-env.sh` |
| 审核被拒（开源协议缺源码链接） | 见 §1 第 3 条 |
