// 职责：通过真实 Clojure HTTP 服务与 Chromium 验证 RPC 契约业务卡片、详情、源码跳转及多入口隔离。
// 运行：PLAYWRIGHT_MODULE=playwright-core node --test spec/browser/entry_contract_test.cjs
// 无 Clojure CLI 时可通过 ARCH_VIEW_TEST_CLASSPATH 提供包含 src、spec 与依赖的 Java classpath。
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { test } = require('node:test');

const repository = path.resolve(__dirname, '../..');

async function startServer(t, root) {
  const classpath = process.env.ARCH_VIEW_TEST_CLASSPATH;
  const script = 'spec/browser/entry_contract_server.clj';
  const child = classpath
    ? spawn('java', ['-Xmx768m', '-cp', classpath, 'clojure.main', script, root], { cwd: repository })
    : spawn(process.env.CLOJURE_BIN || 'clojure', ['-Sdeps', '{:paths ["classes" "src" "spec"]}', '-M', script, root], { cwd: repository });
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
    const timer = setTimeout(() => reject(new Error(`HTTP 服务启动超时：${output}\n${errors}`)), 45000);
    const fail = error => { clearTimeout(timer); reject(error); };
    child.once('error', fail);
    child.once('exit', code => fail(new Error(`HTTP 服务退出 ${code}：${errors}`)));
    child.stderr.on('data', data => { errors = (errors + data).slice(-12000); });
    child.stdout.on('data', data => {
      output += data;
      const match = output.match(/^ENTRY_CONTRACT_SERVER (http:\/\/\S+)/m);
      if (match) { clearTimeout(timer); resolve(match[1]); }
    });
  });
}

test('BUG-08：业务说明与契约/实现锚点贯通列表、卡片、详情及源码跳转', { timeout: 90000 }, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'arch-view-contract-browser-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const url = await startServer(t, root);
  const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'],
    ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}) });
  t.after(() => browser.close());
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  const response = await page.request.get(`${url}/api/entries`);
  assert.equal(response.status(), 200);
  const entries = (await response.json()).entries;
  const byLabel = new Map(entries.map(entry => [entry.label, entry]));
  await page.locator('#explore-nav').click();
  await page.locator('#nav-tab-entries').click();

  async function openEntry(label) {
    const entry = byLabel.get(label);
    assert.ok(entry, `应识别 ${label}`);
    await page.getByRole('searchbox', { name: '搜索入口' }).fill(label);
    const link = page.locator(`.entry-link[data-entry-id=${JSON.stringify(entry.id)}]`).first();
    const loaded = page.waitForResponse(response => {
      const parsed = new URL(response.url());
      return parsed.pathname === '/api/entry/chain' && parsed.searchParams.get('id') === entry.id;
    });
    await link.click();
    const response = await loaded;
    assert.equal(response.status(), 200);
    const data = await response.json();
    const node = data.nodes.find(node => node.id === data.root);
    await page.waitForFunction(name => document.querySelector('.chain-node.root strong')?.textContent === name,
      entry.businessName || node.label);
    return { entry, node, card: page.locator('.chain-node.root') };
  }

  const { entry, node, card } = await openEntry('mock.app.rec');
  assert.equal(node.businessName, '服务推荐Rpc');
  assert.equal(node.summary, '按用户画像推荐服务');
  assert.equal(node.docSource.type, 'demo.AppRecRpc');
  assert.equal(node.contractAnchor.path, 'src/main/java/demo/AppRecRpc.java');
  assert.equal(node.implementationAnchor.path, 'src/main/java/demo/AppRecRpcImpl.java');
  const text = await card.innerText();
  for (const expected of ['服务推荐Rpc', 'AppRecRpcImpl#appRec', '按用户画像推荐服务', '说明来自 AppRecRpc#appRec', '查看接口定义', '查看实现']) {
    assert.ok(text.includes(expected), `实际卡片应包含 ${expected}`);
  }
  assert.ok((await page.locator(`.entry-link[data-entry-id=${JSON.stringify(entry.id)}]`).innerText()).includes('服务推荐Rpc'));
  const overflow = await card.evaluate(card => [...card.querySelectorAll('button')].some(button => button.getBoundingClientRect().bottom > card.getBoundingClientRect().bottom));
  assert.equal(overflow, false, '卡片操作不能被截断');

  if (await page.locator('#detail-open').isVisible()) await page.locator('#detail-open').click();
  const detail = page.locator('#detail-panel');
  await detail.waitFor({ state: 'visible' });
  for (const expected of ['服务推荐Rpc', '服务推荐。', 'mock.app.rec', 'demo.AppRecRpcImpl#appRec']) {
    assert.ok((await detail.innerText()).includes(expected), `实际详情应包含 ${expected}`);
  }
  const artifactDir = process.env.ENTRY_CONTRACT_ARTIFACT_DIR || path.join(repository, 'tmp/bug08-artifacts');
  fs.mkdirSync(artifactDir, { recursive: true });
  await page.screenshot({ path: path.join(artifactDir, 'rpc-contract-card.png'), fullPage: true });

  async function checkSource(control, anchor) {
    const loaded = page.waitForResponse(response => {
      const parsed = new URL(response.url());
      return parsed.pathname === '/api/file' && parsed.searchParams.get('path') === anchor.path;
    });
    await control.click();
    assert.equal((await loaded).status(), 200);
    await page.waitForFunction(filename => document.querySelector('#drawer-path')?.textContent === filename, anchor.path);
    assert.equal(await page.locator('#drawer').isVisible(), true);
    assert.equal(await page.locator('#drawer-content .source-target').first().evaluate(el => [...el.parentElement.children].indexOf(el) + 1), anchor.startLine);
    await page.locator('#drawer-close').click();
  }
  await checkSource(card.getByRole('button', { name: '查看接口定义', exact: true }), node.contractAnchor);
  await checkSource(card.getByRole('button', { name: '查看实现', exact: true }), node.implementationAnchor);
  await checkSource(detail.getByRole('button', { name: '查看接口定义', exact: true }), node.contractAnchor);
  await checkSource(detail.getByRole('button', { name: '查看实现', exact: true }), node.implementationAnchor);

  for (const [operation, businessName, description, source] of [
    ['mock.z', 'Z业务', 'Z入口说明', 'demo.ZContract'],
    ['mock.a', 'A业务', 'A入口说明', 'demo.AContract'],
    ['mock.z', 'Z业务', 'Z入口说明', 'demo.ZContract'],
  ]) {
    const { node, card } = await openEntry(operation);
    assert.equal(node.businessName, businessName);
    assert.equal(node.summary, description);
    assert.equal(node.docSource.type, source);
    assert.ok((await card.innerText()).includes(description));
  }
  const middle = await openEntry('mock.middle');
  const ambiguous = page.locator('.chain-node[data-method-id="demo.SharedImpl#shared()"]');
  assert.ok((await ambiguous.innerText()).includes('多个契约说明，需复核'));
  await ambiguous.getByRole('button', { name: '查看说明来源', exact: true }).click();
  await detail.waitFor({ state: 'visible' });
  assert.ok((await detail.innerText()).includes('多个接口或父类提供了不同说明'));
  assert.equal(await detail.getByRole('button', { name: /查看契约：/ }).count(), 2);
  assert.equal(middle.node.businessName, null);
  const plain = await openEntry('mock.plain');
  assert.equal(plain.node.businessName, null);
  assert.equal(plain.node.summary, null);
  assert.ok((await plain.card.innerText()).includes('PlainImpl#run'));
  assert.deepEqual(errors, []);
});
