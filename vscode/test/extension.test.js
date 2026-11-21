'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const Module = require('module');

function makeVscodeStub() {
  const commands = new Map();
  const output = {
    appendLine() {},
    show() {},
    dispose() {},
  };
  const statusBar = {
    show() {},
    dispose() {},
    text: '',
  };
  const vscode = {
    env: { language: 'zh-cn' },
    StatusBarAlignment: { Left: 1 },
    ProgressLocation: { Notification: 15 },
    window: {
      createOutputChannel: () => output,
      createStatusBarItem: () => statusBar,
      showInformationMessage: async () => undefined,
      showWarningMessage: async () => undefined,
      showErrorMessage: async () => undefined,
      showInputBox: async () => undefined,
      withProgress: async (_options, task) => task({ report() {} }, { isCancellationRequested: false, onCancellationRequested() { return { dispose() {} }; } }),
      activeTextEditor: null,
    },
    commands: {
      registerCommand: (id, handler) => {
        commands.set(id, handler);
        return { dispose() {} };
      },
      executeCommand: async () => undefined,
    },
    workspace: {
      getConfiguration: () => ({ get: (_key, defaultValue) => defaultValue }),
    },
    extensions: {
      getExtension: () => undefined,
    },
  };
  return { vscode, commands, output, statusBar };
}

test('extension activates and registers all public commands', () => {
  const originalLoad = Module._load;
  const stub = makeVscodeStub();
  Module._load = function load(request, parent, isMain) {
    if (request === 'vscode') return stub.vscode;
    return originalLoad.call(this, request, parent, isMain);
  };
  delete require.cache[require.resolve('../extension')];
  try {
    const extension = require('../extension');
    const context = {
      subscriptions: [],
      secrets: { get: async () => undefined, store: async () => undefined },
    };
    extension.activate(context);
    for (const id of [
      'commitHelper.generate',
      'commitHelper.generateWithContext',
      'commitHelper.format',
      'commitHelper.setApiKey',
      'commitHelper.testConnection',
      'commitHelper.openSettings',
      'commitHelper.showOutput',
    ]) {
      assert.ok(stub.commands.has(id), 'missing command ' + id);
    }
    assert.equal(stub.statusBar.text, '$(git-commit) Commit Helper');
    assert.ok(context.subscriptions.length >= 9);
    extension.deactivate();
  } finally {
    Module._load = originalLoad;
    delete require.cache[require.resolve('../extension')];
  }
});
