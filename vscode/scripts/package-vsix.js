'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');

const root = path.resolve(__dirname, '..');
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const distDir = path.join(root, 'dist');
fs.mkdirSync(distDir, { recursive: true });
const vsix = path.join(distDir, pkg.name + '-' + pkg.version + '.vsix');
const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'commit-helper-vsix-'));
const extDir = path.join(staging, 'extension');
fs.mkdirSync(extDir, { recursive: true });

const files = [
  'package.json',
  'package.nls.json',
  'package.nls.zh-cn.json',
  'extension.js',
  'README.md',
  'CHANGELOG.md',
  'LICENSE',
  'src/core.js',
];
for (const file of files) {
  const from = path.join(root, file);
  const to = path.join(extDir, file);
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

const xmlEscape = (value) => String(value)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;');

const manifest = [
  '<?xml version="1.0" encoding="utf-8"?>',
  '<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">',
  '  <Metadata>',
  '    <Identity Language="en-US" Id="' + xmlEscape(pkg.name) + '" Version="' + xmlEscape(pkg.version) + '" Publisher="' + xmlEscape(pkg.publisher) + '" />',
  '    <DisplayName>' + xmlEscape('Commit Helper for VS Code') + '</DisplayName>',
  '    <Description xml:space="preserve">' + xmlEscape('Generate a commit message from staged or working-tree changes, with local grouping and optional LLM wording.') + '</Description>',
  '    <Categories>SCM Providers,Other</Categories>',
  '    <GalleryFlags>Public</GalleryFlags>',
  '    <Properties>',
  '      <Property Id="Microsoft.VisualStudio.Code.Engine" Value="^1.90.0" />',
  '      <Property Id="Microsoft.VisualStudio.Code.ExtensionKind" Value="workspace" />',
  '    </Properties>',
  '  </Metadata>',
  '  <Installation>',
  '    <InstallationTarget Id="Microsoft.VisualStudio.Code" />',
  '  </Installation>',
  '  <Dependencies />',
  '  <Assets>',
  '    <Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" />',
  '    <Asset Type="Microsoft.VisualStudio.Services.Content.Details" Path="extension/README.md" Addressable="true" />',
  '    <Asset Type="Microsoft.VisualStudio.Services.Content.License" Path="extension/LICENSE" Addressable="true" />',
  '  </Assets>',
  '</PackageManifest>',
  '',
].join('\n');
fs.writeFileSync(path.join(staging, 'extension.vsixmanifest'), manifest, 'utf8');

const contentTypes = [
  '<?xml version="1.0" encoding="utf-8"?>',
  '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">',
  '  <Default Extension="json" ContentType="application/json" />',
  '  <Default Extension="js" ContentType="application/javascript" />',
  '  <Default Extension="md" ContentType="text/markdown" />',
  '  <Default Extension="txt" ContentType="text/plain" />',
  '  <Default Extension="xml" ContentType="application/xml" />',
  '  <Default Extension="vsixmanifest" ContentType="text/xml" />',
  '</Types>',
  '',
].join('\n');
fs.writeFileSync(path.join(staging, '[Content_Types].xml'), contentTypes, 'utf8');

if (fs.existsSync(vsix)) fs.unlinkSync(vsix);
cp.execFileSync('zip', ['-q', '-r', vsix, '[Content_Types].xml', 'extension.vsixmanifest', 'extension'], {
  cwd: staging,
  stdio: 'inherit',
});
fs.rmSync(staging, { recursive: true, force: true });
console.log('Wrote ' + vsix);
