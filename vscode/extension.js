'use strict';

const vscode = require('vscode');
const cp = require('child_process');
const util = require('util');
const path = require('path');
const core = require('./src/core');

const execFile = util.promisify(cp.execFile);
const SECRET_PREFIX = 'commitHelper.apiKey.';
const PROVIDERS = ['openai-compatible', 'anthropic', 'gemini', 'local'];

let context;
let output;
let statusBar;

function activate(extensionContext) {
  context = extensionContext;
  output = vscode.window.createOutputChannel('Commit Helper');
  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
  statusBar.name = 'Commit Helper';
  statusBar.text = '$(git-commit) Commit Helper';
  statusBar.tooltip = 'Generate a commit message';
  statusBar.command = 'commitHelper.generate';
  statusBar.show();
  context.subscriptions.push(
    output,
    statusBar,
    vscode.commands.registerCommand('commitHelper.generate', () => runGenerate(false)),
    vscode.commands.registerCommand('commitHelper.generateWithContext', () => runGenerate(true)),
    vscode.commands.registerCommand('commitHelper.format', () => formatMessage()),
    vscode.commands.registerCommand('commitHelper.setApiKey', () => setApiKey()),
    vscode.commands.registerCommand('commitHelper.testConnection', () => testConnection()),
    vscode.commands.registerCommand('commitHelper.openSettings', () =>
      vscode.commands.executeCommand('workbench.action.openSettings', '@ext:me-tool.commit-helper')),
    vscode.commands.registerCommand('commitHelper.showOutput', () => output.show()),
  );
  output.appendLine('Commit Helper activated.');
}

function deactivate() {
  if (output) output.dispose();
  if (statusBar) statusBar.dispose();
}

function zh() {
  return String(vscode.env.language || '').toLowerCase().startsWith('zh');
}

function t(en, cn) {
  return zh() ? cn : en;
}

function log(message) {
  if (output) output.appendLine('[' + new Date().toISOString() + '] ' + message);
}

function getConfig() {
  const config = vscode.workspace.getConfiguration('commitHelper');
  return {
    provider: config.get('provider', 'openai-compatible'),
    baseUrl: config.get('baseUrl', 'https://api.openai.com/v1'),
    model: config.get('model', 'gpt-4o-mini'),
    language: config.get('language', 'auto'),
    scope: config.get('scope', 'auto'),
    confirmBeforeSending: config.get('confirmBeforeSending', true),
    includeDiff: config.get('includeDiff', true),
    maxDiffChars: config.get('maxDiffChars', 60000),
    maxTokens: config.get('maxTokens', 4096),
    timeoutSeconds: config.get('timeoutSeconds', 120),
    formatTemplate: config.get('formatTemplate', '{subject}\n\n{items}'),
    itemLine: config.get('itemLine', '- [{type}]{scope} {text}'),
    promptTemplate: config.get('promptTemplate', ''),
    commitTypes: config.get('commitTypes', core.DEFAULT_TYPES),
    temperature: config.get('temperature', 0.2),
  };
}

async function getGit() {
  const extension = vscode.extensions.getExtension('vscode.git');
  if (!extension) {
    throw new Error(t(
      'The built-in Git extension is not available. Enable it in Extensions.',
      '内置 Git 扩展不可用，请在扩展中启用。',
    ));
  }
  const exports = await extension.activate();
  const api = exports.getAPI(1);
  if (!api) throw new Error(t('Unsupported Git extension API.', 'Git 扩展 API 版本不受支持。'));
  return api;
}

async function getRepository() {
  const api = await getGit();
  if (!api.repositories || api.repositories.length === 0) {
    throw new Error(t('No Git repository is open.', '当前没有打开的 Git 仓库。'));
  }
  let repo = api.repositories.find((item) => item.ui && item.ui.selected);
  if (!repo && vscode.window.activeTextEditor) {
    const activePath = vscode.window.activeTextEditor.document.uri.fsPath;
    repo = api.repositories.find((item) => activePath.startsWith(item.rootUri.fsPath));
  }
  repo = repo || api.repositories[0];
  if (!repo.rootUri) throw new Error(t('Repository root is unavailable.', '仓库根目录不可用。'));
  if (!repo.inputBox) throw new Error(t('The SCM input box is unavailable.', 'SCM 提交信息框不可用。'));
  return { api, repo, root: repo.rootUri.fsPath };
}

function git(root, args) {
  return execFile('git', ['-C', root].concat(args), {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
}

async function hasHead(root) {
  try {
    await git(root, ['rev-parse', '--verify', 'HEAD']);
    return true;
  } catch (_) {
    return false;
  }
}

function parseUntracked(outputText) {
  return String(outputText || '').split('\u0000').filter(Boolean).map((file) => ({
    status: '?',
    path: file,
  }));
}

async function collectChanges(root, config) {
  const scope = ['auto', 'staged', 'all'].includes(config.scope) ? config.scope : 'auto';
  let useStaged = scope === 'staged';
  if (scope === 'auto') {
    const staged = await git(root, ['diff', '--cached', '--name-status', '-z']);
    useStaged = core.parseNameStatus(staged.stdout).length > 0;
  }

  let diffArgs;
  let statusArgs;
  let numstatArgs;
  let addUntracked = false;
  const headExists = await hasHead(root);
  if (useStaged) {
    diffArgs = ['diff', '--cached', '--no-ext-diff', '--unified=3'];
    statusArgs = ['diff', '--cached', '--name-status', '-z'];
    numstatArgs = ['diff', '--cached', '--numstat', '-z'];
  } else if (headExists) {
    diffArgs = ['diff', 'HEAD', '--no-ext-diff', '--unified=3'];
    statusArgs = ['diff', 'HEAD', '--name-status', '-z'];
    numstatArgs = ['diff', 'HEAD', '--numstat', '-z'];
    addUntracked = true;
  } else {
    diffArgs = ['diff', '--cached', '--no-ext-diff', '--unified=3'];
    statusArgs = ['diff', '--cached', '--name-status', '-z'];
    numstatArgs = ['diff', '--cached', '--numstat', '-z'];
    addUntracked = true;
  }

  const [statusResult, numstatResult, diffResult] = await Promise.all([
    git(root, statusArgs),
    git(root, numstatArgs),
    git(root, diffArgs),
  ]);
  let changes = core.mergeStats(
    core.parseNameStatus(statusResult.stdout),
    core.parseNumStat(numstatResult.stdout),
  );

  if (addUntracked) {
    try {
      const untracked = await git(root, ['ls-files', '--others', '--exclude-standard', '-z']);
      const extra = parseUntracked(untracked.stdout);
      const existing = new Set(changes.map((change) => change.path));
      changes = changes.concat(extra.filter((change) => !existing.has(change.path)));
    } catch (error) {
      log('Could not list untracked files: ' + error.message);
    }
  }

  const filtered = core.filterChanges(changes);
  if (filtered.included.length === 0) {
    throw new Error(t(
      'No changes are available. Stage a file or check the commit scope setting.',
      '没有可用变更。请先暂存文件，或检查 commit scope 设置。',
    ));
  }
  const diffText = config.includeDiff
    ? core.truncateDiff(diffResult.stdout, config.maxDiffChars)
    : '(diff content disabled)';
  return {
    changes: filtered.included,
    omitted: filtered.omitted,
    diffText,
    fileListText: core.fileListText(filtered.included),
  };
}

async function resolveLanguage(root, config) {
  if (config.language !== 'auto') return config.language;
  let samples = [];
  try {
    const result = await git(root, ['log', '-5', '--pretty=format:%s']);
    samples = String(result.stdout || '').split('\n').filter(Boolean);
  } catch (_) {
    samples = [];
  }
  return core.detectLanguage(samples, 'auto');
}

async function getApiKey(provider) {
  if (provider === 'local') return null;
  return context.secrets.get(SECRET_PREFIX + provider);
}

async function setApiKey() {
  const config = getConfig();
  if (config.provider === 'local') {
    vscode.window.showInformationMessage(t('Local only mode does not need an API key.', '本地模式不需要 API Key。'));
    return;
  }
  const key = await vscode.window.showInputBox({
    title: t('Commit Helper: API key', 'Commit Helper：API Key'),
    prompt: t('Stored in VS Code SecretStorage, never in settings.', '只保存在 VS Code SecretStorage，不写入设置。'),
    placeHolder: config.provider,
    password: true,
    ignoreFocusOut: true,
  });
  if (!key) return;
  await context.secrets.store(SECRET_PREFIX + config.provider, key.trim());
  vscode.window.showInformationMessage(t('API key saved for ' + config.provider + '.', '已保存 ' + config.provider + ' 的 API Key。'));
  log('Saved API key for provider ' + config.provider + ' (value not logged).');
}

function providerLabel(provider) {
  if (provider === 'openai-compatible') return 'OpenAI-compatible';
  if (provider === 'anthropic') return 'Anthropic';
  if (provider === 'gemini') return 'Gemini';
  return 'Local only';
}

function trimBaseUrl(baseUrl) {
  return String(baseUrl || '').replace(/\/+$/, '');
}

async function requestJson(url, options, timeoutMs, token) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const cancellation = token && token.onCancellationRequested(() => controller.abort());
  try {
    const response = await fetch(url, Object.assign({}, options, { signal: controller.signal }));
    const text = await response.text();
    if (!response.ok) {
      throw new Error('HTTP ' + response.status + ': ' + text.slice(0, 240));
    }
    return text;
  } catch (error) {
    if (error && error.name === 'AbortError') {
      const canceled = token && token.isCancellationRequested;
      throw new Error(canceled ? 'Canceled by user' : 'Request timed out');
    }
    throw error;
  } finally {
    clearTimeout(timer);
    if (cancellation) cancellation.dispose();
  }
}

async function callProvider(provider, config, apiKey, prompt, token, maxTokens) {
  const timeoutMs = Math.max(10, Number(config.timeoutSeconds) || 120) * 1000;
  const tokens = Number(maxTokens || config.maxTokens) || 4096;
  if (provider === 'openai-compatible') {
    const base = trimBaseUrl(config.baseUrl || 'https://api.openai.com/v1');
    const url = base.endsWith('/chat/completions') ? base : base + '/chat/completions';
    const body = {
      model: config.model,
      max_tokens: tokens,
      temperature: Number(config.temperature) || 0.2,
      stream: false,
      messages: [{ role: 'user', content: prompt }],
    };
    const text = await requestJson(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + apiKey,
      },
      body: JSON.stringify(body),
    }, timeoutMs, token);
    const data = JSON.parse(text);
    const choice = data.choices && data.choices[0];
    return String(choice && choice.message && choice.message.content ? choice.message.content : '');
  }

  if (provider === 'anthropic') {
    const base = trimBaseUrl(config.baseUrl || 'https://api.anthropic.com');
    const url = base.endsWith('/messages')
      ? base
      : base.endsWith('/v1') ? base + '/messages' : base + '/v1/messages';
    const body = {
      model: config.model,
      max_tokens: tokens,
      temperature: Number(config.temperature) || 0.2,
      messages: [{ role: 'user', content: prompt }],
    };
    const text = await requestJson(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': apiKey,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify(body),
    }, timeoutMs, token);
    const data = JSON.parse(text);
    const parts = (data.content || []).map((part) => part && part.text ? part.text : '');
    return parts.join('');
  }

  if (provider === 'gemini') {
    const base = trimBaseUrl(config.baseUrl || 'https://generativelanguage.googleapis.com/v1beta');
    const url = base.endsWith(':generateContent')
      ? base
      : base + '/models/' + encodeURIComponent(config.model) + ':generateContent?key=' + encodeURIComponent(apiKey);
    const body = {
      contents: [{ role: 'user', parts: [{ text: prompt }] }],
      generationConfig: { maxOutputTokens: tokens, temperature: Number(config.temperature) || 0.2 },
    };
    const text = await requestJson(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }, timeoutMs, token);
    const data = JSON.parse(text);
    const candidate = data.candidates && data.candidates[0];
    const parts = candidate && candidate.content && candidate.content.parts ? candidate.content.parts : [];
    return parts.map((part) => part && part.text ? part.text : '').join('');
  }

  throw new Error('Unsupported provider: ' + provider);
}

async function completeWithRetry(provider, config, apiKey, prompt, token, requireItems) {
  const mustHaveItems = requireItems !== false;
  let raw = await callProvider(provider, config, apiKey, prompt, token, config.maxTokens);
  let parsed = core.parseModelResponse(raw);
  if (parsed && (!mustHaveItems || parsed.items.length > 0)) return parsed;

  log('First answer had no usable items; retrying with a compact instruction.');
  const compact = prompt + '\n\nReturn STRICTLY valid JSON only. ' +
    'The items array must contain at least 1 item (at most 8); each item text at most 60 characters. ' +
    'If there is a subject but no item, put the concrete change in one item instead of returning [].\n';
  raw = await callProvider(provider, config, apiKey, compact, token,
    Math.min(8192, Math.max(1024, Number(config.maxTokens) * 2)));
  parsed = core.parseModelResponse(raw);
  if (parsed && (!mustHaveItems || parsed.items.length > 0)) return parsed;
  throw new Error(t('The model returned no usable items.', '模型没有返回可用条目。'));
}

async function confirmSend(provider, changes, diffLength) {
  const detail = changes.length + t(' files, diff payload ', ' 个文件，diff 载荷 ') +
    Number(diffLength || 0).toLocaleString() + t(' characters.', ' 个字符。');
  const choice = await vscode.window.showWarningMessage(
    t('Send these changes to ' + providerLabel(provider) + '?',
      '要将这些更改发送给 ' + providerLabel(provider) + ' 吗？'),
    { modal: true, detail: detail },
    t('Send', '发送'),
  );
  return choice === t('Send', '发送');
}

async function confirmQuality(message, config) {
  const warnings = core.qualityWarnings(message, { types: config.commitTypes });
  if (warnings.length === 0) return true;
  const choice = await vscode.window.showWarningMessage(
    t('Commit message quality checks found issues.', '提交消息质量检查发现问题。'),
    { modal: true, detail: warnings.join('\n') },
    t('Insert anyway', '仍然写入'),
    t('Cancel', '取消'),
  );
  return choice === t('Insert anyway', '仍然写入');
}

async function writeToInputBox(repo, text) {
  const inputBox = repo.inputBox;
  if (!inputBox) throw new Error(t('The SCM input box is unavailable.', 'SCM 提交信息框不可用。'));
  const previous = String(inputBox.value || '');
  let next = text;
  if (previous.trim()) {
    const choice = await vscode.window.showWarningMessage(
      t('The commit message already has content.', '提交信息框已有内容。'),
      { modal: true, detail: t('Replace it with the generated message, or append?', '替换为生成内容，还是追加？') },
      t('Replace', '替换'),
      t('Append', '追加'),
    );
    if (choice === t('Append', '追加')) next = previous.trimEnd() + '\n\n' + text;
    else if (choice !== t('Replace', '替换')) return null;
  }
  inputBox.value = next;
  return { previous, written: next };
}

async function afterWrite(repo, result, config, message, fallbackError) {
  if (!result) return;
  const warnings = core.qualityWarnings(message, { types: config.commitTypes });
  const detail = warnings.length ? '\n\n' + warnings.join('\n') : '';
  const buttons = [t('Undo', '撤销'), t('Show Log', '查看日志')];
  const notice = fallbackError
    ? t('LLM failed; inserted the local grouping instead: ' + fallbackError.message,
      '模型调用失败，已改用本地分组：' + fallbackError.message)
    : t('Commit message inserted.' + detail, '提交信息已写入。' + detail);
  const action = fallbackError
    ? await vscode.window.showWarningMessage(notice, t('Retry', '重试'), ...buttons)
    : await vscode.window.showInformationMessage(notice, ...buttons);
  if (action === t('Undo', '撤销')) {
    if (repo.inputBox && repo.inputBox.value === result.written) repo.inputBox.value = result.previous;
  } else if (action === t('Retry', '重试')) {
    await runGenerate(false);
  } else if (action === t('Show Log', '查看日志')) {
    output.show();
  }
}

async function runGenerate(withContext) {
  let generation;
  try {
    generation = await getRepository();
  } catch (error) {
    vscode.window.showErrorMessage(error.message);
    return;
  }
  const { repo, root } = generation;
  const config = getConfig();
  const provider = config.provider;
  let extraContext = '';
  if (withContext) {
    const issue = await vscode.window.showInputBox({
      title: t('Commit Helper: issue / PR', 'Commit Helper：关联 Issue / PR'),
      prompt: t('Optional issue or pull request link/number.', '可选的 issue 或 PR 链接/编号。'),
      value: core.findIssue(repo.state && repo.state.HEAD ? repo.state.HEAD.name : '') || '',
      ignoreFocusOut: true,
    });
    if (issue === undefined) return;
    const note = await vscode.window.showInputBox({
      title: t('Commit Helper: extra context', 'Commit Helper：补充说明'),
      prompt: t('Optional notes, constraints or Skip CI.', '可选的说明、约束或 Skip CI。'),
      ignoreFocusOut: true,
    });
    if (note === undefined) return;
    extraContext = [issue ? 'Issue/PR: ' + issue : '', note ? 'Note: ' + note : ''].filter(Boolean).join('\n');
  }

  statusBar.text = '$(sync~spin) Commit Helper';
  try {
    const result = await vscode.window.withProgress({
      location: vscode.ProgressLocation.Notification,
      title: t('Commit Helper: generating...', 'Commit Helper：生成中...'),
      cancellable: true,
    }, async (progress, token) => {
      const collected = await collectChanges(root, config);
      const language = await resolveLanguage(root, config);
      const languageLabel = core.languageName(language);
      let apiKey = null;

      if (provider !== 'local') {
        apiKey = await getApiKey(provider);
        if (!apiKey) {
          const choice = await vscode.window.showWarningMessage(
            t('No API key for ' + providerLabel(provider) + '.', providerLabel(provider) + ' 还没有 API Key。'),
            t('Set API Key', '设置 API Key'),
            t('Use Local Only', '仅本地生成'),
          );
          if (choice === t('Set API Key', '设置 API Key')) {
            await setApiKey();
            return { canceled: true };
          }
          if (choice !== t('Use Local Only', '仅本地生成')) return { canceled: true };
          apiKey = null;
        }
        if (apiKey && config.confirmBeforeSending) {
          const ok = await confirmSend(provider, collected.changes, collected.diffText.length);
          if (!ok) return { canceled: true };
        }
      }

      let modelMessage = null;
      let fallbackError = null;
      if (provider !== 'local' && apiKey) {
        progress.report({ message: t('Asking ' + providerLabel(provider) + '...', '正在请求 ' + providerLabel(provider) + '...') });
        const prompt = core.buildPrompt({
          promptTemplate: config.promptTemplate,
          language: languageLabel,
          formatTemplate: config.formatTemplate,
          diffText: collected.diffText,
          fileListText: collected.fileListText,
          types: config.commitTypes,
          extraContext: extraContext,
        });
        try {
          modelMessage = await completeWithRetry(provider, config, apiKey, prompt, token);
        } catch (error) {
          if (token.isCancellationRequested) throw error;
          log('Provider failed: ' + error.message);
          fallbackError = error;
        }
      }
      if (token.isCancellationRequested) throw new Error('Canceled by user');

      const message = modelMessage || core.localMessage(collected.changes, language);
      const rendered = core.renderMessage(message, {
        formatTemplate: config.formatTemplate,
        itemLine: config.itemLine,
      });
      if (!rendered.trim()) throw new Error(t('Generated message is empty.', '生成结果为空。'));
      if (!(await confirmQuality(message, config))) return { canceled: true };
      return {
        rendered,
        message,
        changes: collected.changes,
        diffLength: collected.diffText.length,
        fallbackError,
      };
    });

    if (result.canceled) return;
    const writeResult = await writeToInputBox(repo, result.rendered);
    if (!writeResult) return;
    log('Generated ' + result.changes.length + ' files, ' + result.diffLength +
      ' chars, provider=' + provider + ', model=' + config.model +
      ', fallback=' + Boolean(result.fallbackError));
    await afterWrite(repo, writeResult, config, result.message, result.fallbackError);
  } catch (error) {
    const message = error && error.message ? error.message : String(error);
    if (/Canceled by user|aborted/i.test(message) || (error && error.name === 'AbortError')) {
      vscode.window.showInformationMessage(t('Generation canceled.', '生成已取消。'));
      return;
    }
    log('Generation failed: ' + message);
    vscode.window.showErrorMessage(
      t('Commit Helper failed: ' + message, 'Commit Helper 失败：' + message),
      t('Show Log', '查看日志'),
    ).then((action) => {
      if (action === t('Show Log', '查看日志')) output.show();
    });
  } finally {
    statusBar.text = '$(git-commit) Commit Helper';
  }
}

async function formatMessage() {
  let generation;
  try {
    generation = await getRepository();
  } catch (error) {
    vscode.window.showErrorMessage(error.message);
    return;
  }
  const { repo } = generation;
  const current = String((repo.inputBox && repo.inputBox.value) || '').trim();
  if (!current) {
    vscode.window.showWarningMessage(t('Write a commit message first.', '请先在提交信息框写一条消息。'));
    return;
  }
  const config = getConfig();
  if (config.provider === 'local') {
    vscode.window.showWarningMessage(t('Formatting needs an LLM provider.', '格式化需要 LLM 提供商。'));
    return;
  }
  const apiKey = await getApiKey(config.provider);
  if (!apiKey) {
    const choice = await vscode.window.showWarningMessage(
      t('No API key for ' + providerLabel(config.provider) + '.', providerLabel(config.provider) + ' 还没有 API Key。'),
      t('Set API Key', '设置 API Key'),
    );
    if (choice === t('Set API Key', '设置 API Key')) await setApiKey();
    return;
  }
  if (config.confirmBeforeSending) {
    const choice = await vscode.window.showWarningMessage(
      t('Send the current commit message to ' + providerLabel(config.provider) + '?',
        '要将当前提交消息发送给 ' + providerLabel(config.provider) + ' 吗？'),
      { modal: true }, t('Send', '发送'));
    if (choice !== t('Send', '发送')) return;
  }
  try {
    const rendered = await vscode.window.withProgress({
      location: vscode.ProgressLocation.Notification,
      title: t('Commit Helper: formatting...', 'Commit Helper：格式化中...'),
      cancellable: true,
    }, async (progress, token) => {
      const language = await resolveLanguage(generation.root, config);
      const prompt = core.buildFormatPrompt({
        currentMessage: current,
        language: core.languageName(language),
        formatTemplate: config.formatTemplate,
        types: config.commitTypes,
      });
      const message = await completeWithRetry(config.provider, config, apiKey, prompt, token, false);
      return {
        message,
        rendered: core.renderMessage(message, {
          formatTemplate: config.formatTemplate,
          itemLine: config.itemLine,
        }),
      };
    });
    const writeResult = await writeToInputBox(repo, rendered.rendered);
    if (!writeResult) return;
    await afterWrite(repo, writeResult, config, rendered.message, null);
  } catch (error) {
    const message = error && error.message ? error.message : String(error);
    if (/Canceled by user|aborted/i.test(message)) {
      vscode.window.showInformationMessage(t('Formatting canceled.', '格式化已取消。'));
      return;
    }
    log('Format failed: ' + message);
    vscode.window.showErrorMessage(t('Commit Helper failed: ' + message, 'Commit Helper 失败：' + message));
  }
}

async function testConnection() {
  const config = getConfig();
  if (config.provider === 'local') {
    vscode.window.showInformationMessage(t('Local only mode is ready.', '本地模式可用。'));
    return;
  }
  const apiKey = await getApiKey(config.provider);
  if (!apiKey) {
    const choice = await vscode.window.showWarningMessage(
      t('Set an API key first.', '请先设置 API Key。'), t('Set API Key', '设置 API Key'));
    if (choice === t('Set API Key', '设置 API Key')) await setApiKey();
    return;
  }
  try {
    await vscode.window.withProgress({
      location: vscode.ProgressLocation.Notification,
      title: t('Commit Helper: testing connection...', 'Commit Helper：测试连接...'),
      cancellable: false,
    }, async () => {
      const started = Date.now();
      const text = await callProvider(config.provider, config, apiKey,
        'Reply with the single word OK.', undefined, 16);
      if (!text || !text.trim()) throw new Error(t('Empty response.', '响应为空。'));
      vscode.window.showInformationMessage(t(
        'Connection OK (' + (Date.now() - started) + ' ms).',
        '连接成功（' + (Date.now() - started) + ' ms）。'));
    });
  } catch (error) {
    vscode.window.showErrorMessage(t('Connection failed: ' + error.message, '连接失败：' + error.message));
  }
}

module.exports = { activate, deactivate };
