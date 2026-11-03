// 职责：使用真实多模块 Java 项目、Clojure HTTP 服务和 Chromium 验证 US-1 构建模块文件夹树。
// 运行：CLOJURE_BIN=clojure PLAYWRIGHT_MODULE=playwright-core CHROMIUM_EXECUTABLE=/path/to/chrome node --test spec/browser/us1_tree_test.cjs
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { test } = require('node:test');

const repository = path.resolve(__dirname, '../..');

function javaProject(directory) {
  const root = path.join(directory, 'shop');
  const write = (filename, content) => {
    fs.mkdirSync(path.dirname(path.join(root, filename)), { recursive: true });
    fs.writeFileSync(path.join(root, filename), content);
  };
  write('ARCHITECTURE.md', '# shop\n\n多模块导航验收项目。\n');
  write('user-api/pom.xml', '<project/>\n');
  write('user-api/src/main/java/com/foo/Port.java', 'package com.foo; public interface Port {}\n');
  write('user-api/src/main/java/com/bar/Types.java', 'package com.bar; public class Types {}\n');
  write('user-api/src/main/java/org/util/Helper.java', 'package org.util; public class Helper {}\n');
  write('user-app/build.gradle', '// 无需解析的构建脚本\n');
  write('user-app/src/main/java/com/foo/App.java', 'package com.foo; class App implements Port {}\n');
  return root;
}

async function startServer(t, root) {
  const command = process.env.CLOJURE_BIN || 'clojure';
  const child = spawn(command, ['-M', 'spec/browser/us1_server.clj', root], { cwd: repository, stdio: ['ignore', 'pipe', 'pipe'] });
  t.after(async () => {
    if (child.exitCode === null) {
      child.kill('SIGTERM');
      await Promise.race([once(child, 'exit'), new Promise(resolve => {
        const timer = setTimeout(() => { child.kill('SIGKILL'); resolve(); }, 3000);
        timer.unref();
      })]);
    }
  });
  return new Promise((resolve, reject) => {
    let output = '', errors = '';
    const timer = setTimeout(() => reject(new Error(`真实 server 启动超时：${output}\n${errors}`)), 90000);
    const fail = error => { clearTimeout(timer); reject(error); };
    child.once('error', fail);
    child.once('exit', code => fail(new Error(`真实 server 提前退出 ${code}：${output}\n${errors}`)));
    child.stderr.on('data', data => { errors += data; });
    child.stdout.on('data', data => {
      output += data;
      const match = output.match(/^US1_SERVER (http:\/\/\S+)/m);
      if (match) { clearTimeout(timer); resolve(match[1]); }
    });
  });
}

async function ready(page, url) {
  await page.goto(url);
  await page.locator('#project-name').filter({ hasNotText: '正在加载' }).waitFor();
  await page.locator('#map-world button.node, #map-world g.node').first().waitFor();
  await page.waitForFunction(() => document.querySelector('#analysis-state').textContent !== '正在读取结构…');
}

async function screenshot(page, name) {
  const directory = process.env.US1_ARTIFACT_DIR || path.join(repository, 'tmp/us1-artifacts');
  fs.mkdirSync(directory, { recursive: true });
  await page.screenshot({ path: path.join(directory, `${name}.png`), fullPage: true });
}

function viewPath(url) {
  const parsed = url instanceof URL ? url : new URL(url);
  return parsed.pathname === '/api/view' ? (parsed.searchParams.get('path') || '') : null;
}

function item(page, treePath) {
  return page.locator(`.tree-item[data-tree-path="${treePath}"]`);
}

function toggle(page, treePath) {
  return item(page, treePath).locator(':scope > .tree-row .tree-toggle');
}

function link(page, treePath) {
  return item(page, treePath).locator(':scope > .tree-row .module-link');
}

async function crumbs(page) {
  return page.locator('#breadcrumbs button').allInnerTexts();
}

test('US-1：构建模块导航是可展开的文件夹树', { timeout: 180000 }, async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arch-view-us1-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const root = javaProject(directory);
  const url = await startServer(t, root);
  const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
  const browser = await chromium.launch({
    headless: true,
    ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}),
    args: ['--no-sandbox']
  });
  t.after(() => browser.close());
  const page = await browser.newPage({ viewport: { width: 1600, height: 1200 } });
  const views = [];
  page.on('request', request => {
    const pathValue = viewPath(request.url());
    if (pathValue != null) views.push(pathValue);
  });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (response.status() < 400) return;
    if (response.status() === 500 && viewPath(response.url()) === 'user-app') return;
    errors.push(`HTTP ${response.status()} ${response.url()}`);
  });

  await t.test('N1 根层是构建模块，带立方体图标、工具标签和目录', async () => {
    await ready(page, url);
    await item(page, 'user-api').waitFor();
    await item(page, 'user-app').waitFor();
    assert.equal(await page.locator('#module-nav > .tree-item').count(), 2);
    assert.match(await link(page, 'user-api').innerText(), /Maven 模块 · user-api\//);
    assert.match(await link(page, 'user-app').innerText(), /Gradle 模块 · user-app\//);
    assert.equal(await item(page, 'user-api').locator(':scope > .tree-row .module-link .icon use').getAttribute('href'), '#icon-module');
    assert.equal(await page.locator('#root-count').textContent(), '02');
    assert.equal(await page.locator('#nav-float').evaluate(el => getComputedStyle(el).position), 'fixed');
    const navBox = await page.locator('#nav-float').boundingBox();
    const mapBox = await page.locator('.map-panel').boundingBox();
    assert.ok(navBox && mapBox && navBox.x >= mapBox.x - 8 && navBox.x < mapBox.x + 48, '模块树应叠在地图上，不独占左列');
    assert.equal(await page.locator('#document-select').isVisible(), false, '探索页不显示架构说明选择');
    assert.ok(!views.some(value => value && value !== ''), `打开项目时不应预拉子层：${views.join(',')}`);
  });

  await t.test('N2 点三角只展开树，点名称才导航；未展开的兄弟分支不请求', async () => {
    const before = await crumbs(page);
    await toggle(page, 'user-api').click();
    await item(page, 'user-api/com').waitFor();
    assert.deepEqual(await crumbs(page), before);
    assert.equal(await item(page, 'user-api').getAttribute('aria-expanded'), 'true');
    assert.equal(await item(page, 'user-app/com').count(), 0);
    assert.ok(views.includes('user-api'));
    assert.ok(!views.includes('user-app'));
    await link(page, 'user-api').click();
    await page.locator('#breadcrumbs button').filter({ hasText: 'user-api' }).waitFor();
    assert.ok((await crumbs(page)).includes('user-api'));
    assert.ok(await link(page, 'user-api').evaluate(el => el.classList.contains('current')));
  });

  await t.test('N3 主图下钻后自动展开祖先并标出当前节点', async () => {
    const mapNode = name => page.locator('#map-world button.node').filter({ has: page.locator('.node-name', { hasText: new RegExp(`^${name}$`) }) });
    await mapNode('com').dblclick();
    await mapNode('foo').waitFor();
    await mapNode('foo').dblclick();
    await mapNode('Port').waitFor();
    await mapNode('Port').click();
    await item(page, 'user-api/com/foo/Port').waitFor();
    assert.equal(await item(page, 'user-api').getAttribute('aria-expanded'), 'true');
    assert.equal(await item(page, 'user-api/com').getAttribute('aria-expanded'), 'true');
    assert.equal(await item(page, 'user-api/com/foo').getAttribute('aria-expanded'), 'true');
    assert.ok(await link(page, 'user-api/com/foo/Port').evaluate(el => el.classList.contains('current')));
    assert.ok(await link(page, 'user-api').evaluate(el => el.classList.contains('on-path')));
    await screenshot(page, 'us1-tree-current-leaf');
  });

  await t.test('N4 展开失败只影响该分支，重试后恢复且不清空整栏', async () => {
    await page.locator('#breadcrumbs button').filter({ hasText: /^项目$/ }).click();
    await item(page, 'user-app').waitFor();
    let failOnce = true;
    await page.route(url => viewPath(url) === 'user-app', async route => {
      if (failOnce) {
        failOnce = false;
        await route.fulfill({ status: 500, contentType: 'application/json', body: JSON.stringify({ error: '模拟读取失败' }) });
        return;
      }
      await route.continue();
    });
    await toggle(page, 'user-app').click();
    await item(page, 'user-app').locator('.tree-status.error').waitFor();
    assert.equal(await item(page, 'user-api').count(), 1);
    assert.equal(await item(page, 'user-app').count(), 1);
    await item(page, 'user-app').locator('.tree-status.error button', { hasText: '重试' }).click();
    await item(page, 'user-app/com').waitFor();
    assert.equal(await item(page, 'user-app').locator('.tree-status.error').count(), 0);
    await screenshot(page, 'us1-tree-expanded');
  });

  assert.deepEqual(errors, [], errors.join('\n'));
});
