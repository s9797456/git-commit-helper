'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('../src/core');

test('parseNameStatus handles modification and rename records', () => {
  const input = 'M\u0000src/a.js\u0000R100\u0000old.js\u0000new.js\u0000';
  const changes = core.parseNameStatus(input);
  assert.equal(changes.length, 2);
  assert.deepEqual(changes[0], { status: 'M', path: 'src/a.js' });
  assert.deepEqual(changes[1], { status: 'R', oldPath: 'old.js', path: 'new.js' });
});

test('parseNumStat reads additions and deletions', () => {
  const stats = core.parseNumStat('3\t1\tsrc/a.js\u0000');
  assert.deepEqual(stats['src/a.js'], { additions: 3, deletions: 1 });
});

test('noise files are excluded but kept in the omitted list', () => {
  const result = core.filterChanges([
    { status: 'M', path: 'src/app.js' },
    { status: 'M', path: 'node_modules/pkg/index.js' },
    { status: 'M', path: 'package-lock.json' },
  ]);
  assert.equal(result.included.length, 1);
  assert.equal(result.included[0].path, 'src/app.js');
  assert.equal(result.omitted.length, 2);
});

test('buildPrompt appends diff, file list and types when the profile omits them', () => {
  const prompt = core.buildPrompt({
    promptTemplate: 'Summarize this change in {language}.',
    language: 'Chinese',
    diffText: '--- a\n+++ b\n+hello',
    fileListText: 'modified src/app.js (+1/-0)',
    types: ['feat', 'fix'],
  });
  assert.match(prompt, /Changed files:\nmodified src\/app\.js/);
  assert.match(prompt, /Diff:\n--- a/);
  assert.match(prompt, /only allowed commit types/);
  assert.match(prompt, /Summarize this change in Chinese\./);
});

test('buildPrompt does not duplicate present diff and fileList blocks', () => {
  const prompt = core.buildPrompt({
    promptTemplate: 'Files:\n{fileList}\nDiff:\n{diff}',
    diffText: 'DIFF-BODY',
    fileListText: 'FILE-LIST',
    types: ['feat'],
  });
  assert.equal((prompt.match(/FILE-LIST/g) || []).length, 1);
  assert.equal((prompt.match(/DIFF-BODY/g) || []).length, 1);
  assert.doesNotMatch(prompt, /Changed files:/);
});

test('parseModelResponse accepts fenced JSON and empty items', () => {
  const fenced = '\u0060\u0060\u0060json\n{"subject":"feat: add cache","items":[],"issue":null}\n\u0060\u0060\u0060';
  const parsed = core.parseModelResponse(fenced);
  assert.equal(parsed.subject, 'feat: add cache');
  assert.deepEqual(parsed.items, []);
});

test('parseModelResponse rejects malformed or subject-less payloads', () => {
  assert.equal(core.parseModelResponse('not json'), null);
  assert.equal(core.parseModelResponse('{"items":[]}'), null);
  assert.equal(core.parseModelResponse('{"subject":"  "}'), null);
});

test('renderMessage renders items and falls back to subject when items are empty', () => {
  const withItems = core.renderMessage(
    { subject: 'feat: add cache', items: [{ type: 'feat', scope: 'api', text: 'add cache' }] },
    { formatTemplate: '{subject}\n\n{items}', itemLine: '- [{type}]{scope} {text}' },
  );
  assert.equal(withItems, 'feat: add cache\n\n- [feat] (api) add cache');

  const empty = core.renderMessage(
    { subject: 'chore: update', items: [] },
    { formatTemplate: '{items}' },
  );
  assert.equal(empty, 'chore: update');
});

test('localMessage groups by top-level directory and uses a configured type', () => {
  const message = core.localMessage([
    { status: 'A', path: 'src/a.js' },
    { status: 'A', path: 'src/b.js' },
    { status: 'A', path: 'docs/readme.md' },
  ], 'zh');
  assert.match(message.subject, /^feat: /);
  assert.equal(message.items.length, 2);
  assert.equal(message.items[0].scope, 'src');
});

test('qualityWarnings flags long and non-conventional subjects', () => {
  const warnings = core.qualityWarnings(
    { subject: 'update code and fix many things without a conventional type or a useful scope at all', items: [] },
    { types: ['feat', 'fix'] },
  );
  assert.ok(warnings.some((warning) => warning.includes('Conventional')));
  assert.ok(warnings.some((warning) => warning.includes('72')));
});

test('truncateDiff keeps short input and marks truncation', () => {
  assert.equal(core.truncateDiff('short', 1000), 'short');
  const long = 'x'.repeat(1200);
  const truncated = core.truncateDiff(long, 1000);
  assert.ok(truncated.length > 1000);
  assert.match(truncated, /\[diff truncated/);
});

test('detectLanguage and findIssue', () => {
  assert.equal(core.detectLanguage(['feat: 新增功能'], 'auto'), 'zh');
  assert.equal(core.detectLanguage(['feat: add feature'], 'auto'), 'en');
  assert.equal(core.findIssue('feature/PROJ-123-add-cache'), 'PROJ-123');
  assert.equal(core.findIssue('main'), null);
});
