// 职责：使用真实项目、Clojure HTTP 服务和 Chromium 验证 US-3 双画布导航。
// 运行：CLOJURE_BIN=clojure PLAYWRIGHT_MODULE=playwright-core CHROMIUM_EXECUTABLE=/path/to/chrome node --test spec/browser/us3_pin_test.cjs
// Playwright 可以安装在测试专用目录，再将 PLAYWRIGHT_MODULE 指向该包的绝对路径。
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const { test } = require('node:test');

const repository = path.resolve(__dirname, '../..');
const flow = fs.readFileSync(path.join(__dirname, 'fixtures/us3_business_flow.mmd'), 'utf8');
const modules = ['rpc', 'mq', 'scheduler', 'eventcenter', 'aq', 'attribute', 'activecare', 'rec', 'benefit', 'integration', 'dal'];
const flowLabels = [...flow.matchAll(/\|([^|]+)\|/g)].map(match => match[1]);
const nodeLabels = [...flow.matchAll(/^\s+\w+\[\(?"([^"]+)"/gm)].map(match => match[1]);

function projectFixture(directory, name, withFlow = true) {
  const root = path.join(directory, name);
  const write = (filename, content) => {
    fs.mkdirSync(path.dirname(path.join(root, filename)), { recursive: true });
    fs.writeFileSync(path.join(root, filename), content);
  };
  const roles = modules.map(module => `### ${module} · ${module} 子系统\n\n- **职责**：${module} 的测试业务边界。\n- **实现状态**：已完成。\n${module === 'aq' ? '- **覆盖范围**：aq/api、scheduler/impl、integration/api/entry\n' : ''}\n[探索](#module=${module})\n`).join('\n');
  write('ARCHITECTURE.md', `# ${name}\n\n真实浏览器验收项目。\n\n${withFlow ? `## 数据流\n\n\`\`\`mermaid\n${flow}\`\`\`\n\n` : ''}## 核心子系统与职责\n\n${roles}`);
  for (const module of modules) {
    write(`src/demo/${module}/api/entry.clj`, `(ns demo.${module}.api.entry${module === 'integration' ? '' : ' (:require [demo.integration.api.entry :as integration])'})\n(defn run [] :ok)\n`);
    write(`src/demo/${module}/impl/service.clj`, `(ns demo.${module}.impl.service (:require [demo.${module}.api.entry :as entry]))\n(defn service [] :ok)\n`);
  }
  write('src/demo/rpc/ARCHITECTURE.md', '# RPC 包级文档\n\n## 数据流\n\n```mermaid\nflowchart LR\n local_request["包级请求"] --> local_handler["包级处理"]\n```\n\n## 核心子系统与职责\n\n### api · API\n\n- **职责**：RPC 接口。\n\n### impl · 实现\n\n- **职责**：RPC 实现。\n');
  return root;
}

async function startServers(t, roots) {
  const command = process.env.CLOJURE_BIN || 'clojure';
  const child = spawn(command, ['-M', 'spec/browser/us3_server.clj', ...roots], { cwd: repository, stdio: ['ignore', 'pipe', 'pipe'] });
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
      const urls = [...output.matchAll(/^US3_SERVER (http:\/\/\S+)/gm)].map(match => match[1]);
      if (urls.length === roots.length) { clearTimeout(timer); resolve(urls); }
    });
  });
}

// 同一 origin 连接多个真实 server，使 localStorage 项目隔离断言具有实际意义。
async function sameOriginProxy(t, initialTarget) {
  let target = initialTarget;
  const proxy = http.createServer((request, response) => {
    const headers = { ...request.headers, host: new URL(target).host };
    if (headers.origin) headers.origin = new URL(target).origin;
    if (headers.referer) headers.referer = `${new URL(target).origin}/`;
    const upstream = http.request(new URL(request.url, target), { method: request.method, headers }, result => {
      response.writeHead(result.statusCode, result.headers);
      result.pipe(response);
    });
    upstream.on('error', error => { response.writeHead(502); response.end(error.message); });
    request.pipe(upstream);
  });
  proxy.listen(0, '127.0.0.1');
  await once(proxy, 'listening');
  t.after(() => new Promise(resolve => { proxy.closeAllConnections(); proxy.close(resolve); }));
  return { url: `http://127.0.0.1:${proxy.address().port}`, select: url => { target = url; } };
}

async function ready(page, url) {
  await page.goto(url);
  await page.locator('#project-name').filter({ hasNotText: '正在加载' }).waitFor();
  await page.locator('#map-world button.node, #map-world g.node').first().waitFor();
  await page.waitForFunction(() => document.querySelector('#analysis-state').textContent !== '正在读取结构…');
}

async function screenshot(page, name) {
  const directory = process.env.US3_ARTIFACT_DIR || path.join(repository, 'tmp/us3-artifacts');
  fs.mkdirSync(directory, { recursive: true });
  await page.screenshot({ path: path.join(directory, `${name}.png`), fullPage: true });
}

async function waitForCount(page, selector, count) {
  await page.waitForFunction(({ selector, count }) => document.querySelectorAll(selector).length === count, { selector, count });
}

async function visibleBounds(page, selector) {
  const box = await page.locator(selector).boundingBox();
  assert.ok(box && box.width > 0 && box.height > 0, `${selector} 必须有可见尺寸`);
  return box;
}

async function codeCamera(page) {
  await page.evaluate(() => new Promise(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  }));
  return page.locator('#map-viewport').evaluate(viewport => ({
    transform: document.querySelector('#map-world').style.transform,
    zoom: document.querySelector('#zoom-value').textContent,
    left: viewport.scrollLeft, top: viewport.scrollTop,
  }));
}

async function shownPinEdges(page) {
  return page.locator('#pin-world .pinned-edge').evaluateAll(edges => edges
    .filter(edge => getComputedStyle(edge).display !== 'none')
    .map(edge => ({ from: edge.dataset.from, to: edge.dataset.to,
      incoming: edge.classList.contains('pinned-edge-incoming'), outgoing: edge.classList.contains('pinned-edge-outgoing') })));
}

async function pinCurrent(page, id) {
  await page.locator(`.pinned-node[data-node-id="${id}"].pinned-current`).waitFor();
}

function overlapping(a, b) {
  return Math.min(a.x + a.width, b.x + b.width) > Math.max(a.x, b.x) + 1 &&
    Math.min(a.y + a.height, b.y + b.height) > Math.max(a.y, b.y) + 1;
}

// 测试在单个 context 中按顺序进行；各场景进入时显式恢复 UI 状态。
test('US-3：真实 server 的全局 pin、点击导航、缩放与项目隔离', { timeout: 180000 }, async t => {
  assert.equal(nodeLabels.length, 15, '用户原文应为15节点');
  assert.equal((flow.match(/-->/g) || []).length, 24, '用户原文实际24边，不添加臆造关系');
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'arch-view-us3-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const roots = [projectFixture(directory, 'project-A'), projectFixture(directory, 'project-B'), projectFixture(directory, 'project-without-flow', false)];
  const urls = await startServers(t, roots);
  const proxy = await sameOriginProxy(t, urls[0]);
  const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright-core');
  const browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_EXECUTABLE ? { executablePath: process.env.CHROMIUM_EXECUTABLE } : {}), args: ['--no-sandbox'] });
  t.after(() => browser.close());
  const context = await browser.newContext({ viewport: { width: 1600, height: 1200 } });
  const page = await context.newPage();
  const errors = [], unrelatedNotes = [], noteChecks = [];
  let documentFault = false;
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {
    if (response.status() < 400) return;
    const url = new URL(response.url()), detail = `HTTP ${response.status()} ${response.url()}`;
    if (documentFault && response.status() === 503 && url.pathname === '/api/document') return;
    // HEAD exploration.js:210-212 对包使用省略公共根的fullName；workspace/resolve-target要求完整包前缀。
    // 仅排除本夹具已确认的三个既有GET请求，仍校验错误正文；其他所有404均失败。
    if (response.request().method() === 'GET' && response.status() === 404 && url.pathname === '/api/notes' &&
        ['rpc.api', 'aq.api', 'integration'].includes(url.searchParams.get('target'))) {
      noteChecks.push(response.json().then(body => {
        assert.equal(body.error, `未找到目标：${url.searchParams.get('target')}`);
        unrelatedNotes.push(`${detail} (${body.error})`);
      }));
    } else errors.push(detail);
  });

  await t.test('默认同时展示全局文档数据流和代码图，完整保留15节点24边及业务标签', async () => {
    await ready(page, proxy.url);
    await waitForCount(page, '#pinned-architecture .pinned-node', 15);
    await waitForCount(page, '#pinned-architecture .pinned-edge', 24);
    const content = await page.locator('#pinned-architecture').textContent();
    for (const label of [...nodeLabels, ...flowLabels]) assert.ok(content.includes(label), `数据流必须保留原文：${label}`);
    assert.equal(await page.locator('#code-view').getAttribute('aria-pressed'), 'true');
    assert.equal(await page.locator('#flow-view').isVisible(), false);
    assert.equal(await page.locator('.metrics').isVisible(), false, '探索页不显示规模卡片');
    assert.equal(await page.locator('#hero').isVisible(), false);
    assert.equal(await page.locator('#design-notes').isVisible(), false);
    assert.ok(await page.locator('#map-world button.node').count() > 0);
    assert.equal(await page.locator('#zoom-value').textContent(), '100%', '首次进入代码图使用可读比例，而不是适应全图');
    assert.equal(await page.locator('#detail-panel').isVisible(), false, '未选择模块时详情栏不占右侧整列');
    assert.equal(await page.locator('#pin-target, #pin-enter').count(), 0, '直接点击进入，不再提供代码范围选择和进入按钮');
    const pin = await visibleBounds(page, '#pinned-architecture');
    const map = await visibleBounds(page, '#map-viewport');
    assert.ok(pin.width >= 300 && pin.width <= 440, `浮层宽度应接近 360px，实际 ${pin.width}`);
    assert.ok(pin.height < map.height * 0.5, '全局架构不再占主画布一半以上高度');
    assert.ok(pin.x > map.x + map.width * 0.35, '浮层锚定在主画布右侧');
    assert.ok(Math.abs(pin.x + pin.width - (map.x + map.width)) < 28, '浮层贴齐主画布右缘，不另占一整列');
    assert.ok(pin.y >= map.y - 2 && pin.y < map.y + 48, '浮层从主画布顶部开始，不在工具栏外预留整行');
    assert.equal(overlapping(pin, map), true, '浮层盖在代码图上，不再上下分屏');
    const mapHeight = map.height;
    await page.locator('#pin-close').click();
    assert.ok(Math.abs((await visibleBounds(page, '#map-viewport')).height - mapHeight) < 8, '关闭浮层不应把主图高度切成上下两块');
    await page.locator('#pin-open').click();
    await waitForCount(page, '#pinned-architecture .pinned-node', 15);
    await screenshot(page, 'us3-default-large-flow');
  });

  await t.test('浮层仅展示直接上下游，悬停和键盘预览不导航，放大可查看全部连线', async () => {
    await page.locator('#pin-fit').click();
    const camera = await codeCamera(page);
    const shape = await page.locator('#pin-world svg').getAttribute('viewBox');
    const positions = await page.locator('#pin-world .pinned-node').evaluateAll(nodes => nodes.map(node => node.getAttribute('transform')));
    assert.deepEqual(await shownPinEdges(page), [], '项目根层未聚焦时不显示缠绕连线');
    assert.equal(await page.locator('#pin-edge-hint').isVisible(), true);
    await page.locator('.pinned-node[data-node-id="rpc"]').hover();
    const rpcEdges = await shownPinEdges(page);
    assert.equal(rpcEdges.length, 7);
    assert.ok(rpcEdges.every(edge => edge.from === 'rpc' || edge.to === 'rpc'), '只展示直接连线，不递归展开整条链路');
    assert.equal(rpcEdges.filter(edge => edge.incoming).length, 1);
    assert.equal(rpcEdges.filter(edge => edge.outgoing).length, 6);
    assert.equal(await page.locator('#pin-relation-legend').isVisible(), true);
    await page.locator('.pinned-node[data-node-id="mq"]').hover();
    const mqEdges = await shownPinEdges(page);
    assert.equal(mqEdges.length, 4);
    assert.ok(mqEdges.every(edge => edge.from === 'mq' || edge.to === 'mq'));
    assert.deepEqual(await page.locator('#breadcrumbs button').allTextContents(), ['项目'], '悬停不会进入代码');
    await page.locator('#pin-fit').hover();
    assert.deepEqual(await shownPinEdges(page), []);
    await page.locator('.pinned-node[data-node-id="rpc"]').focus();
    assert.equal((await shownPinEdges(page)).length, 7, '键盘聚焦也可预览');
    await page.locator('#pin-expand').click();
    assert.equal(await page.locator('#pin-edge-mode').isVisible(), true);
    await page.locator('#pin-edge-mode').click();
    assert.equal(await page.locator('#pin-edge-mode').getAttribute('aria-pressed'), 'true');
    assert.equal((await shownPinEdges(page)).length, 24, '完整阅读仍能检查所有真实关系');
    assert.equal(await page.locator('#pin-world .pinned-node').count(), 15);
    assert.equal(await page.locator('#pin-world .edgeLabel').evaluateAll(labels => labels.filter(label => getComputedStyle(label).display !== 'none').length), 24);
    await screenshot(page, 'us3-all-edges');
    await page.locator('#pin-edge-mode').click();
    await page.locator('.pinned-node[data-node-id="rpc"]').hover();
    assert.equal((await shownPinEdges(page)).length, 7, '完整图也能切回聚焦阅读');
    await page.keyboard.press('Escape');
    await page.locator('#pin-fit').hover();
    assert.deepEqual(await shownPinEdges(page), []);
    assert.equal(await page.locator('#pin-world svg').getAttribute('viewBox'), shape);
    assert.deepEqual(await page.locator('#pin-world .pinned-node').evaluateAll(nodes => nodes.map(node => node.getAttribute('transform'))), positions, '预览不重排节点');
    assert.deepEqual(await codeCamera(page), camera, '预览和查看全部连线不改变代码图相机');
  });

  await t.test('总览页才显示规模卡片和架构说明，探索页只保留地图', async () => {
    await page.locator('#overview-nav').click();
    assert.equal(await page.locator('.metrics').isVisible(), true);
    assert.equal(await page.locator('#hero').isVisible(), true);
    assert.equal(await page.locator('#design-notes').isVisible(), true);
    assert.equal(await page.locator('#document-select').isVisible(), true);
    assert.equal(await page.locator('#nav-float').isVisible(), false);
    assert.equal(await page.locator('#map-viewport').isVisible(), false);
    await page.locator('#explore-nav').click();
    await page.locator('#map-world button.node').first().waitFor();
    assert.equal(await page.locator('.metrics').isVisible(), false);
    assert.equal(await page.locator('#document-select').isVisible(), false);
    assert.equal(await page.locator('#nav-float').isVisible(), true);
    assert.equal(await page.locator('#pinned-architecture').isVisible(), true);
  });

  await t.test('放大阅读、关闭重开和pin相机操作保持代码相机，控件可访问', async () => {
    await page.locator('#zoom-in').click();
    const before = await codeCamera(page);
    const pinBefore = await page.locator('#pin-world').evaluate(world => world.style.transform);
    await page.locator('#pin-zoom-in').click();
    assert.notEqual(await page.locator('#pin-world').evaluate(world => world.style.transform), pinBefore);
    assert.deepEqual(await codeCamera(page), before, 'pin缩放不应改变代码camera');
    await page.locator('#pin-expand').click();
    await page.waitForFunction(() => document.querySelector('#pin-expand').getAttribute('aria-expanded') === 'true');
    const pin = await visibleBounds(page, '#pinned-architecture');
    for (const selector of ['#pin-source', '#pin-fit', '#pin-expand', '#pin-close']) {
      const bounds = await visibleBounds(page, selector);
      assert.ok(bounds.x >= pin.x - 1 && bounds.x + bounds.width <= pin.x + pin.width + 1, `${selector}横向不能溢出画布`);
      assert.ok(bounds.y >= pin.y - 1 && bounds.y + bounds.height <= pin.y + pin.height + 1, `${selector}纵向不能溢出画布`);
    }
    await screenshot(page, 'us3-expanded-large-flow');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelector('#pin-expand').getAttribute('aria-expanded') === 'false');
    assert.deepEqual(await codeCamera(page), before, '退出放大阅读后跨下一帧仍保留代码camera');
    await page.locator('#pin-close').click();
    assert.equal(await page.locator('#pinned-architecture').isVisible(), false);
    await page.locator('#pin-open').click();
    assert.deepEqual(await codeCamera(page), before, '关闭重新开启pin保留代码camera');
    await page.locator('#pin-splitter').scrollIntoViewIfNeeded();
    const splitter = await visibleBounds(page, '#pin-splitter');
    const pinWidth = (await visibleBounds(page, '#pinned-architecture')).width;
    await page.mouse.move(splitter.x + splitter.width / 2, splitter.y + splitter.height / 2);
    await page.mouse.down();
    await page.mouse.move(splitter.x - 48, splitter.y + splitter.height / 2, { steps: 8 });
    await page.mouse.up();
    await codeCamera(page);
    const resized = await visibleBounds(page, '#pinned-architecture');
    assert.ok(Math.abs(resized.width - pinWidth) >= 5, '拖动左缘应改变浮层宽度');
    assert.ok(resized.width <= 430 && resized.width >= 310, '浮层宽度仍在可操作范围');
    assert.equal(overlapping(resized, await visibleBounds(page, '#map-viewport')), true);
    assert.equal(await page.locator('#detail-panel').isVisible(), false, '未选择时没有右侧详情可被浮层盖住');
    assert.deepEqual(await codeCamera(page), before, '拖动浮层宽度保留代码camera');
    const beforeFullscreen = await codeCamera(page);
    await page.locator('#map-fullscreen').click();
    await page.waitForFunction(() => document.querySelector('#map-fullscreen').getAttribute('aria-pressed') === 'true');
    assert.equal(await page.locator('#pinned-architecture').isVisible(), true, '地图全屏时pin继续可见');
    const fullPin = await visibleBounds(page, '#pinned-architecture');
    const fullMap = await visibleBounds(page, '#map-viewport');
    assert.ok(fullPin.x >= fullMap.x - 2 && fullPin.x + fullPin.width <= fullMap.x + fullMap.width + 28, '全屏时浮层仍留在主画布内');
    await page.locator('#map-fullscreen').click();
    await page.waitForFunction(() => document.querySelector('#map-fullscreen').getAttribute('aria-pressed') === 'false');
    assert.deepEqual(await codeCamera(page), beforeFullscreen, '退出地图全屏跨帧恢复代码camera');
    assert.equal(await page.locator('#pinned-architecture').isVisible(), true);
    assert.equal(overlapping(await visibleBounds(page, '#pinned-architecture'), await visibleBounds(page, '#map-viewport')), true, '退出全屏后仍是右上角浮层');
  });

  await t.test('左下角同时调整宽高，记住尺寸且不越出画布', async () => {
    const original = await visibleBounds(page, '#pinned-architecture');
    const before = await codeCamera(page);
    const corner = await visibleBounds(page, '#pin-resize-corner');
    const x = corner.x + corner.width / 2, y = corner.y + corner.height / 2;
    await page.mouse.move(x, y); await page.mouse.down();
    await page.mouse.move(x - 260, y + 190, { steps: 12 }); await page.mouse.up();
    const enlarged = await visibleBounds(page, '#pinned-architecture');
    assert.ok(enlarged.width >= original.width + 240, '左下角向左拖可以超过原来的420px宽度限制');
    assert.ok(enlarged.height >= original.height + 170, '左下角向下拖增加浮层高度');
    assert.ok(Math.abs(enlarged.x + enlarged.width - original.x - original.width) < 2, '右上角保持锚定');
    assert.ok(Math.abs(enlarged.y - original.y) < 2);
    assert.deepEqual(await codeCamera(page), before, '调整浮层宽高不改变主图相机');
    await page.locator('#pin-fit').click();
    await page.locator('.pinned-node[data-node-id="rpc"]').hover();
    await screenshot(page, 'us3-corner-resized');
    const artifactDirectory = process.env.US3_ARTIFACT_DIR || path.join(repository, 'tmp/us3-artifacts');
    await page.locator('#pinned-architecture').screenshot({ path: path.join(artifactDirectory, 'us3-rpc-preview-resized.png') });
    await page.locator('#pin-close').click(); await page.locator('#pin-open').click();
    const reopened = await visibleBounds(page, '#pinned-architecture');
    assert.ok(Math.abs(reopened.width - enlarged.width) < 2 && Math.abs(reopened.height - enlarged.height) < 2);
    await ready(page, proxy.url);
    await waitForCount(page, '#pinned-architecture .pinned-node', 15);
    const reloaded = await visibleBounds(page, '#pinned-architecture');
    assert.ok(Math.abs(reloaded.width - enlarged.width) < 2 && Math.abs(reloaded.height - enlarged.height) < 2, '刷新后恢复该项目浮层宽高');
    const resizedCorner = await visibleBounds(page, '#pin-resize-corner');
    await page.mouse.move(resizedCorner.x + 9, resizedCorner.y + 9); await page.mouse.down();
    await page.mouse.move(0, 1200, { steps: 10 }); await page.mouse.up();
    const maximum = await visibleBounds(page, '#pinned-architecture'), map = await visibleBounds(page, '#map-viewport');
    assert.ok(maximum.x >= map.x - 2 && maximum.y + maximum.height <= map.y + map.height + 2, '拖到边界时宽高受画布限制');
    const maxCorner = await visibleBounds(page, '#pin-resize-corner');
    await page.mouse.move(maxCorner.x + 9, maxCorner.y + 9); await page.mouse.down();
    await page.mouse.move(maxCorner.x + 9 + maximum.width - original.width, maxCorner.y + 9 - maximum.height + original.height, { steps: 12 });
    await page.mouse.up();
    const restored = await visibleBounds(page, '#pinned-architecture');
    assert.ok(Math.abs(restored.width - original.width) < 3 && Math.abs(restored.height - original.height) < 3, '可以拖回小浮层');
  });

  await t.test('点击业务节点直接进入包，无额外返回入口，包级文档保持全局来源', async () => {
    const invalidLocate = [];
    const listen = request => {
      const url = new URL(request.url());
      if (url.pathname === '/api/locate' && url.searchParams.get('module') === 'rpc') invalidLocate.push(url.href);
    };
    page.on('request', listen);
    const globalSvg = await page.locator('#pin-world svg').getAttribute('id');
    await page.locator('#pin-fit').click();
    await page.locator('.pinned-node[data-node-id="rpc"]').click();
    await page.locator('#breadcrumbs button').filter({ hasText: /^rpc$/ }).waitFor();
    await page.waitForFunction(() => document.querySelector('#document-select').value.includes('rpc/ARCHITECTURE.md'));
    await pinCurrent(page, 'rpc');
    await page.locator('.pinned-node[data-node-id="rpc"].pinned-entry').waitFor();
    await page.locator('#pin-fit').hover();
    assert.equal((await shownPinEdges(page)).length, 7, '离开悬停后恢复当前模块的直接连线');
    await page.locator('.pinned-node[data-node-id="mq"]').hover();
    assert.equal((await shownPinEdges(page)).length, 4);
    await page.locator('#pin-fit').hover();
    assert.equal((await shownPinEdges(page)).length, 7, '临时预览不会覆盖代码当前位置');
    await screenshot(page, 'us3-rpc-focused');
    assert.equal(await page.locator('#pin-world svg').getAttribute('id'), globalSvg, '包级文档不应重绘全局数据流');
    assert.ok((await page.locator('#pin-provenance').textContent()).startsWith('ARCHITECTURE.md'));
    assert.equal(await page.locator('#pinned-architecture .pinned-node').count(), 15);
    assert.deepEqual(invalidLocate, [], 'rpc是包，不得传入只接受叶子的/api/locate');
    assert.equal(await page.locator('#map-return').isVisible(), false, '全局模块点击导航不创建临时返回入口');
    await page.locator('#breadcrumbs button').first().click();
    await page.waitForFunction(() => document.querySelectorAll('#breadcrumbs button').length === 1);
    page.off('request', listen);
  });

  await t.test('共享依赖的当前节点与进入点独立，源码drawer的Escape优先关闭drawer', async () => {
    await page.locator('.pinned-node[data-node-id="rpc"]').click();
    await pinCurrent(page, 'rpc');
    await page.locator('#map-world button.node[data-node="api"]').dblclick();
    await page.locator('#map-world button.node[data-node="entry"]').click();
    await page.locator('#map-world button.node[data-node="entry"]').dblclick();
    await page.locator('#drawer').waitFor({ state: 'visible' });
    await pinCurrent(page, 'rpc');
    await page.keyboard.press('Escape');
    await page.locator('#drawer').waitFor({ state: 'hidden' });
    assert.equal(await page.locator('#pin-expand').getAttribute('aria-expanded'), 'false');
    await page.locator('#trace-nav [data-trace="us3-shared"]').click();
    await page.locator('.trace-step-head').nth(1).click();
    await pinCurrent(page, 'integration');
    await page.locator('.pinned-node[data-node-id="rpc"].pinned-entry').waitFor();
    assert.equal(await page.locator('.pinned-node[data-node-id="rpc"]').getAttribute('aria-current'), null);
    for (const selector of ['.pinned-current', '.pinned-entry']) {
      const vectorEffect = await page.locator(`${selector} :is(rect, path, polygon, ellipse)`).first()
        .evaluate(shape => getComputedStyle(shape).vectorEffect);
      assert.equal(vectorEffect, 'non-scaling-stroke', `${selector}轮廓不能随缩放变细`);
    }
    await screenshot(page, 'us3-current-and-entry');
    await page.getByRole('button', { name: '关闭链路', exact: true }).click();
  });

  await t.test('外部节点只查看说明，点击根代码节点进入且保留主图trace', async () => {
    await page.locator('#pin-fit').click();
    const before = await codeCamera(page);
    await page.locator('.pinned-node[data-node-id="external"]').click();
    assert.equal(await page.locator('#pin-enter').count(), 0);
    assert.ok((await page.locator('#pin-detail-summary').textContent()).includes('不能跳转'));
    assert.deepEqual(await codeCamera(page), before, '外部节点不改代码画布');
    await page.locator('#pin-source').selectOption('code');
    await waitForCount(page, '#pinned-architecture .pinned-node', 11);
    await page.locator('#pin-fit').click();
    await page.locator('#trace-nav [data-trace="us3-shared"]').click();
    await page.locator('#map-world .trace-overlay').waitFor();
    await page.locator('.pinned-node[data-node-id="mq"]').click();
    await page.locator('#breadcrumbs button').filter({ hasText: /^mq$/ }).waitFor();
    assert.equal(await page.locator('#detail-panel h3').textContent(), 'RPC 到共享集成');
    assert.equal(await page.locator('#map-return').isVisible(), false);
    await page.getByRole('button', { name: '关闭链路', exact: true }).click();
    await page.locator('#pin-source').selectOption('flow');
    await waitForCount(page, '#pinned-architecture .pinned-node', 15);
  });

  await t.test('一次点击展示全部覆盖范围，重叠范围去重，保留真实依赖和下钻', async () => {
    await page.locator('#pin-fit').click();
    await page.locator('.pinned-node[data-node-id="aq"]').click();
    await page.locator('#map-world button.node[data-node="aq/api"]').waitFor();
    const ids = await page.locator('#map-world button.node').evaluateAll(cards => cards.map(card => card.dataset.node).sort());
    assert.deepEqual(ids, ['aq/api', 'aq/impl', 'integration/api/entry', 'scheduler/impl/service'].sort(), '同时显示本包与额外范围，无重复、无无关兄弟包');
    assert.equal(await page.locator('#map-world path.edge[data-from="aq/api"][data-to="integration/api/entry"]').count(), 1, '跨范围依赖来自真实源码');
    assert.equal(await page.locator('#pin-target, #pin-enter').count(), 0);
    assert.equal(await page.locator('#map-return').isVisible(), false);
    await pinCurrent(page, 'aq');
    await page.locator('.pinned-node[data-node-id="aq"].pinned-entry').waitFor();
    await screenshot(page, 'us3-all-bound-scopes');
    await page.locator('#map-world button.node[data-node="aq/api"]').dblclick();
    await page.locator('#map-world button.node[data-node="entry"]').waitFor();
    assert.deepEqual(await page.locator('#breadcrumbs button').allTextContents(), ['项目', 'aq', 'api']);
    await page.locator('#map-world button.node[data-node="entry"]').dblclick();
    await page.locator('#drawer').waitFor({ state: 'visible' });
    await page.keyboard.press('Escape');
    await page.locator('#drawer').waitFor({ state: 'hidden' });
    await page.locator('#pin-fit').click();
    await page.locator('.pinned-node[data-node-id="mq"]').focus();
    await page.keyboard.press('Enter');
    await page.locator('#breadcrumbs button').filter({ hasText: /^mq$/ }).waitFor();
    assert.deepEqual(await page.locator('#map-world button.node').evaluateAll(cards => cards.map(card => card.dataset.node).sort()), ['api', 'impl'], '下一次普通模块导航不遗留上次范围过滤');
  });

  await t.test('同一origin下关闭按项目记忆，其他项目默认开启，缺少数据流回退根图', async () => {
    await page.locator('#pin-close').click();
    await ready(page, proxy.url);
    assert.equal(await page.locator('#pinned-architecture').isVisible(), false, '刷新项目A仍保持关闭');
    proxy.select(urls[1]);
    await ready(page, proxy.url);
    await waitForCount(page, '#pinned-architecture .pinned-node', 15);
    assert.equal(await page.locator('#pinned-architecture').isVisible(), true, '项目B不继承A的关闭偏好');
    proxy.select(urls[0]);
    await ready(page, proxy.url);
    assert.equal(await page.locator('#pinned-architecture').isVisible(), false, '回到项目A保持关闭');
    await page.locator('#pin-open').click();
    proxy.select(urls[2]);
    await ready(page, proxy.url);
    await waitForCount(page, '#pinned-architecture .pinned-node', 11);
    assert.equal(await page.locator('#pin-source').inputValue(), 'code');
    assert.equal(await page.locator('#pin-source option[value="flow"]').isDisabled(), true);
    assert.equal(await page.locator('#pinned-architecture').isVisible(), true);
  });

  await t.test('项目文档请求故障回退代码图，重试恢复真实大数据流且主图可用', async () => {
    proxy.select(urls[0]);
    documentFault = true;
    const fault = route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: 'US-3受控文档故障' }) });
    await page.route('**/api/document?*', fault);
    await ready(page, proxy.url);
    await waitForCount(page, '#pinned-architecture .pinned-node', 11);
    assert.ok(await page.locator('#map-world button.node').count() > 0, '文档请求失败不应阻塞主代码图');
    await page.locator('#pin-retry').waitFor({ state: 'visible' });
    assert.ok((await page.locator('#pin-notice').textContent()).includes('回退'));
    await page.unroute('**/api/document?*', fault);
    documentFault = false;
    await page.locator('#pin-retry').click();
    await waitForCount(page, '#pinned-architecture .pinned-node', 15);
    await waitForCount(page, '#pinned-architecture .pinned-edge', 24);
    assert.equal(await page.locator('#pin-notice').isVisible(), false);
    assert.ok(await page.locator('#map-world button.node').count() > 0);
  });

  await Promise.all(noteChecks);
  if (unrelatedNotes.length) t.diagnostic(`既有非pin详情notes请求404：${[...new Set(unrelatedNotes)].join('; ')}`);
  assert.deepEqual(errors, [], '浏览器不应发生脚本异常或pin、资源、导航HTTP错误');
});
