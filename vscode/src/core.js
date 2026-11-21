'use strict';

/**
 * Pure helpers shared by the extension host and node:test. Nothing here imports
 * vscode, so this file can be tested with plain Node.
 */

const DETAIL_MARKER = '[commit-helper:concrete-detail]';

const DEFAULT_TYPES = [
  'feat', 'fix', 'docs', 'style', 'refactor', 'perf',
  'test', 'build', 'ci', 'chore', 'revert',
];

const NOISE_PATTERNS = [
  /(^|\/)node_modules\//i,
  /(^|\/)dist\//i,
  /(^|\/)build\//i,
  /(^|\/)out\//i,
  /(^|\/)\.gradle\//i,
  /(^|\/)\.idea\//i,
  /(^|\/)target\//i,
  /(^|\/)coverage\//i,
  /\.min\.(js|css)$/i,
  /\.(png|jpe?g|gif|webp|ico|pdf|zip|jar|class|exe|dll|so|dylib)$/i,
  /(^|\/)package-lock\.json$/i,
  /(^|\/)yarn\.lock$/i,
  /(^|\/)pnpm-lock\.yaml$/i,
  /(^|\/)poetry\.lock$/i,
  /(^|\/)Cargo\.lock$/i,
];

const CONCRETE_RULES = [
  '',
  DETAIL_MARKER,
  'IMPORTANT - write change-level detail, not file-level labels:',
  '- Every item must say what changed in the code, not merely which file or module was touched.',
  '- When the diff shows a field, enum, method signature, config key or API route changing, quote the identifier exactly.',
  '- Merge changes that serve one behaviour into one item; do not repeat the same file name.',
  '- Keep each item concise (up to roughly 60 characters when possible).',
  '- Never invent a change that is not present in the diff.',
  '- Emit at most 8 items.',
  '',
].join('\n');

function statusLabel(code) {
  switch (String(code || '').charAt(0)) {
    case 'A': return 'added';
    case 'D': return 'deleted';
    case 'R': return 'renamed';
    case 'C': return 'copied';
    case '?': return 'untracked';
    default: return 'modified';
  }
}

function isNoiseFile(file) {
  const p = String(file || '').replace(/\\/g, '/');
  return NOISE_PATTERNS.some((pattern) => pattern.test(p));
}

/** Parse git diff --name-status -z. Returns [{status, path, oldPath?}]. */
function parseNameStatus(output) {
  const parts = String(output || '').split('\u0000');
  const changes = [];
  let i = 0;
  while (i < parts.length) {
    const status = parts[i++];
    if (!status) continue;
    const code = status.charAt(0);
    if (code === 'R' || code === 'C') {
      const oldPath = parts[i++];
      const newPath = parts[i++];
      if (newPath) changes.push({ status: code, oldPath, path: newPath });
    } else {
      const file = parts[i++];
      if (file) changes.push({ status: code, path: file });
    }
  }
  return changes;
}

/** Parse git diff --numstat -z; returns {path: {additions, deletions}}. */
function parseNumStat(output) {
  const parts = String(output || '').split('\u0000');
  const stats = {};
  for (let i = 0; i < parts.length; i += 1) {
    const record = parts[i];
    if (!record) continue;
    const fields = record.split('\t');
    if (fields.length >= 3) {
      const additions = fields[0] === '-' ? 0 : Number(fields[0]) || 0;
      const deletions = fields[1] === '-' ? 0 : Number(fields[1]) || 0;
      const file = fields[2];
      if (file) stats[file] = { additions, deletions };
    }
  }
  return stats;
}

function mergeStats(changes, stats) {
  return changes.map((change) => {
    const item = stats[change.path] || {};
    return Object.assign({ additions: 0, deletions: 0 }, change, item);
  });
}

function filterChanges(changes) {
  const included = [];
  const omitted = [];
  for (const change of changes || []) {
    if (isNoiseFile(change.path)) omitted.push(change);
    else included.push(change);
  }
  return { included, omitted };
}

function fileListText(changes) {
  if (!changes || changes.length === 0) return '(no changed files)';
  return changes.map((change) => {
    const label = statusLabel(change.status);
    const filePath = change.status === 'R' && change.oldPath
      ? change.oldPath + ' -> ' + change.path
      : change.path;
    const delta = (change.additions || change.deletions)
      ? ' (+' + (change.additions || 0) + '/-' + (change.deletions || 0) + ')'
      : '';
    return label + ' ' + filePath + delta;
  }).join('\n');
}

function truncateDiff(diff, maxChars) {
  const text = String(diff || '');
  const limit = Math.max(1000, Number(maxChars) || 60000);
  if (text.length <= limit) return text;
  const head = text.slice(0, limit);
  return head + '\n\n[diff truncated after ' + limit + ' of ' + text.length + ' characters]\n';
}

function typeNames(types) {
  return (types || []).map((item) => {
    if (typeof item === 'string') return item;
    return item && item.type ? item.type : '';
  }).filter(Boolean);
}

function typeListText(types) {
  const names = typeNames(types && types.length ? types : DEFAULT_TYPES);
  return names.length ? names.map((name) => '- ' + name).join('\n') : '(none configured)';
}

function replaceAll(text, token, value) {
  return String(text).split(token).join(value == null ? '' : String(value));
}

function buildPrompt(options) {
  const opts = options || {};
  const template = String(opts.promptTemplate || '');
  const formatTemplate = String(opts.formatTemplate || '{subject}\n\n{items}');
  const types = typeNames(opts.types || DEFAULT_TYPES);
  const extra = String(opts.extraContext || '').trim();

  let prompt = template;
  prompt = replaceAll(prompt, '{language}', opts.language || 'Chinese');
  prompt = replaceAll(prompt, '{format}', formatTemplate);
  prompt = replaceAll(prompt, '{skeleton}', String(opts.skeleton || '(none)'));
  prompt = replaceAll(prompt, '{fileList}', String(opts.fileListText || '(no changed files)'));
  prompt = replaceAll(prompt, '{diff}', String(opts.diffText || '(diff content disabled)'));
  prompt = replaceAll(prompt, '{types}', typeListText(types));
  prompt = replaceAll(prompt, '{extraContext}', extra || '(none)');

  let suffix = '';
  if (types.length && !template.includes('{types}')) {
    suffix += '\n\nIMPORTANT - the only allowed commit types are:\n' + typeListText(types);
  }
  if (extra && !template.includes('{extraContext}')) {
    suffix += '\n\nAdditional context supplied by the user (must be honoured):\n' + extra;
  }
  if (!template.includes('{fileList}')) {
    suffix += '\n\nChanged files:\n' + String(opts.fileListText || '(no changed files)');
  }
  if (!template.includes('{diff}')) {
    suffix += '\n\nDiff:\n' + String(opts.diffText || '(diff content disabled)');
  }
  if (!template.includes(DETAIL_MARKER)) suffix += CONCRETE_RULES;
  return prompt.trimEnd() + suffix + '\n';
}

function buildFormatPrompt(options) {
  const opts = options || {};
  const types = typeNames(opts.types || DEFAULT_TYPES);
  return [
    'Reformat the existing git commit message below.',
    'Return STRICTLY valid JSON only, no Markdown and no commentary:',
    '{"subject":"one-line summary","items":[{"type":"feat","scope":"optional","text":"change detail"}],"issue":null}',
    '',
    'Rules:',
    '- Keep every fact from the original message; do not invent new changes.',
    '- Use the allowed types: ' + types.join(', ') + '.',
    '- Write in ' + (opts.language || 'Chinese') + '.',
    '- Use this layout: ' + String(opts.formatTemplate || '{subject}\n\n{items}'),
    '',
    'Original message:',
    String(opts.currentMessage || ''),
    '',
    'Important: return JSON only.',
    DETAIL_MARKER,
  ].join('\n');
}

/** Remove fences and parse the first JSON object; null when unusable. */
function parseModelResponse(raw) {
  let text = String(raw || '').trim();
  if (!text) return null;
  text = text.replace(/^\uFEFF/, '');
  text = text.replace(/^\u0060{3}(?:json)?\s*/i, '');
  text = text.replace(/\s*\u0060{3}$/i, '');
  const start = text.indexOf('{');
  const end = text.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  let parsed;
  try {
    parsed = JSON.parse(text.slice(start, end + 1));
  } catch (_) {
    return null;
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
  const subject = String(parsed.subject || '').trim();
  if (!subject) return null;
  const rawItems = Array.isArray(parsed.items) ? parsed.items : [];
  const items = rawItems.map((item) => {
    if (typeof item === 'string') return { type: 'chore', scope: '', text: item.trim() };
    if (!item || typeof item !== 'object') return null;
    return {
      type: String(item.type || 'chore').trim(),
      scope: String(item.scope || '').trim(),
      text: String(item.text || '').trim(),
    };
  }).filter((item) => item && item.text);
  return {
    subject,
    items,
    issue: parsed.issue == null ? null : String(parsed.issue),
  };
}

function fillItemLine(line, item) {
  let out = String(line || '{text}');
  out = replaceAll(out, '{type}', item.type || 'chore');
  out = replaceAll(out, '{scope}', item.scope ? ' (' + item.scope + ')' : '');
  out = replaceAll(out, '{scopeRaw}', item.scope || '');
  out = replaceAll(out, '{text}', item.text || '');
  return out;
}

function renderMessage(message, options) {
  const opts = options || {};
  const subject = String((message && message.subject) || '').trim();
  const items = (message && message.items) || [];
  const itemLine = String(opts.itemLine || '{text}');
  const lines = items.map((item) => fillItemLine(itemLine, item)).filter((line) => line.trim());
  let output = String(opts.formatTemplate || '{subject}\n\n{items}');
  output = replaceAll(output, '{subject}', subject);
  output = replaceAll(output, '{items}', lines.join('\n'));
  output = replaceAll(output, '{issue}', message.issue || '');
  output = replaceAll(output, '{skeleton}', '');
  if (!output.trim()) output = [subject, lines.join('\n')].filter(Boolean).join('\n\n');
  if (!output.trim()) output = subject;
  return output.replace(/\n{3,}/g, '\n\n').trim();
}

function detectLanguage(samples, configured) {
  const option = String(configured || 'auto');
  if (option !== 'auto') return option;
  const text = (samples || []).join('\n');
  const kana = (text.match(/[\u3040-\u30ff]/g) || []).length;
  const hangul = (text.match(/[\uac00-\ud7af]/g) || []).length;
  const han = (text.match(/[\u4e00-\u9fff]/g) || []).length;
  if (kana > 0) return 'ja';
  if (hangul > 0) return 'ko';
  if (han > 0) return 'zh';
  return 'en';
}

function languageName(code) {
  return ({ zh: 'Chinese', en: 'English', ja: 'Japanese', ko: 'Korean' })[code] || 'Chinese';
}

const LOCAL_LABELS = {
  zh: { added: '新增', deleted: '删除', modified: '更新', subject: '更新 {n} 个文件', item: '{dir}（{n} 个文件）' },
  en: { added: 'add', deleted: 'remove', modified: 'update', subject: 'update {n} files', item: '{dir} ({n} files)' },
  ja: { added: '追加', deleted: '削除', modified: '更新', subject: '{n} ファイルを更新', item: '{dir}（{n} ファイル）' },
  ko: { added: '추가', deleted: '삭제', modified: '업데이트', subject: '{n}개 파일 업데이트', item: '{dir}({n}개 파일)' },
};

function localMessage(changes, language) {
  const lang = ['zh', 'en', 'ja', 'ko'].includes(language) ? language : 'zh';
  const labels = LOCAL_LABELS[lang];
  const groups = new Map();
  for (const change of changes || []) {
    const file = String(change.path || '');
    const dir = file.includes('/') ? file.split('/')[0] : '.';
    if (!groups.has(dir)) groups.set(dir, []);
    groups.get(dir).push(change);
  }
  const allAdded = changes && changes.length > 0 && changes.every((change) => change.status === 'A');
  const allDeleted = changes && changes.length > 0 && changes.every((change) => change.status === 'D');
  const type = allAdded ? 'feat' : allDeleted ? 'refactor' : 'chore';
  const subject = replaceAll(labels.subject, '{n}', (changes || []).length);
  const items = Array.from(groups.entries()).map(([dir, files]) => ({
    type,
    scope: dir === '.' ? '' : dir,
    text: replaceAll(replaceAll(labels.item, '{dir}', dir === '.' ? labels.modified : dir), '{n}', files.length),
  }));
  return { subject: type + ': ' + subject, items, issue: null };
}

function qualityWarnings(message, options) {
  const opts = options || {};
  const warnings = [];
  const subject = String((message && message.subject) || '').trim();
  const allowed = typeNames(opts.types || DEFAULT_TYPES);
  if (!subject) warnings.push('subject is empty');
  if (subject.length > 72) warnings.push('subject is longer than 72 characters');
  const conventional = /^([a-z]+)(\([^)]+\))?!?:\s+.+/.exec(subject);
  if (!conventional) warnings.push('subject does not look like Conventional Commits');
  else if (allowed.length && !allowed.includes(conventional[1])) {
    warnings.push("type '" + conventional[1] + "' is not in the configured type list");
  }
  for (const item of (message && message.items) || []) {
    if (String(item.text || '').length > 120) warnings.push('an item is longer than 120 characters');
  }
  return warnings;
}

function findIssue(text) {
  const match = /([A-Z][A-Z0-9]+-\d+)/.exec(String(text || ''));
  return match ? match[1] : null;
}

module.exports = {
  DEFAULT_TYPES,
  DETAIL_MARKER,
  buildFormatPrompt,
  buildPrompt,
  detectLanguage,
  fileListText,
  filterChanges,
  findIssue,
  isNoiseFile,
  languageName,
  localMessage,
  mergeStats,
  parseModelResponse,
  parseNameStatus,
  parseNumStat,
  qualityWarnings,
  renderMessage,
  statusLabel,
  truncateDiff,
  typeNames,
};
