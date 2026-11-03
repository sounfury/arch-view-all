// 职责：验证包树前缀只合并已加载的无分叉包，且不越过构建模块和源码文件。
'use strict';
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { test } = require('node:test');

const prefixUrl = pathToFileURL(path.join(__dirname, '../../src/arch_view/web/assets/tree_prefix.js')).href;
let collapsePackageChain, chainPresence;
test.before(async () => {
  ({ collapsePackageChain, chainPresence } = await import(prefixUrl));
});

const pkg = (id, label = id) => ({ id, label, leaf: false });
const leaf = id => ({ id, label: id, leaf: true });
const build = id => ({ id, label: id, leaf: false, buildModule: { name: id } });

test('未加载的子层保持原节点，不把未知后代并进来', () => {
  const chain = collapsePackageChain(pkg('org'), ['user-api'], () => null);
  assert.deepEqual(chain.nodes.map(node => node.id), ['org']);
  assert.deepEqual(chain.path, ['user-api', 'org']);
});

test('已加载的单子包前缀收成一条，源码文件不并入', () => {
  const views = new Map([
    ['user-api/org', { nodes: [pkg('util')] }],
    ['user-api/org/util', { nodes: [leaf('Helper')] }],
  ]);
  const chain = collapsePackageChain(pkg('org'), ['user-api'], key => views.get(key));
  assert.deepEqual(chain.nodes.map(node => node.id), ['org', 'util']);
  assert.deepEqual(chain.path, ['user-api', 'org', 'util']);
});

test('有兄弟节点或构建模块时不再往下合并', () => {
  const views = new Map([
    ['user-api', { nodes: [pkg('com'), pkg('org')] }],
    ['user-api/com', { nodes: [pkg('foo'), pkg('bar')] }],
  ]);
  const module = collapsePackageChain(build('user-api'), [], key => views.get(key));
  assert.deepEqual(module.nodes.map(node => node.id), ['user-api']);
  const com = collapsePackageChain(pkg('com'), ['user-api'], key => views.get(key));
  assert.deepEqual(com.path, ['user-api', 'com']);
});

test('单节点的当前/路径标记与合并前一致', () => {
  const chain = { nodes: [pkg('com')], path: ['user-api', 'com'] };
  assert.equal(chainPresence(['user-api', 'com'], null, ['user-api'], chain), 'current');
  assert.equal(chainPresence(['user-api', 'com', 'foo'], null, ['user-api'], chain), 'on-path');
  assert.equal(chainPresence(['user-api'], 'com', ['user-api'], chain), 'current');
  assert.equal(chainPresence(['user-app'], null, ['user-api'], chain), '');
});

test('合并前缀覆盖中间路径和最终路径', () => {
  const chain = { nodes: [pkg('org'), pkg('util')], path: ['user-api', 'org', 'util'] };
  assert.equal(chainPresence(['user-api', 'org'], null, ['user-api'], chain), 'current');
  assert.equal(chainPresence(['user-api', 'org', 'util'], null, ['user-api'], chain), 'current');
  assert.equal(chainPresence(['user-api', 'org', 'util', 'Helper'], 'Helper', ['user-api'], chain), 'on-path');
  assert.equal(chainPresence(['user-api', 'com'], null, ['user-api'], chain), '');
});
