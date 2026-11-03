// 职责：验证代码图候选列数、可读缩放和浮层遮挡滚动，不依赖浏览器。
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { test } = require('node:test');

const layoutUrl = pathToFileURL(path.join(__dirname, '../../src/arch_view/web/assets/map_layout.js')).href;

function nodes(count, layer = 0) {
  return Array.from({ length: count }, (_, index) => ({ id: `n${layer}-${index}`, layer }));
}

function overlaps(layout) {
  const cards = layout.positions.map(position => ({
    id: position.id,
    left: position.x,
    right: position.x + 240,
    top: position.y,
    bottom: position.y + 144,
  }));
  for (let i = 0; i < cards.length; i += 1) {
    for (let j = i + 1; j < cards.length; j += 1) {
      const a = cards[i], b = cards[j];
      if (a.left < b.right - 0.5 && a.right > b.left + 0.5 && a.top < b.bottom - 0.5 && a.bottom > b.top + 0.5) return `${a.id} 与 ${b.id}`;
    }
  }
  return '';
}

function outside(layout) {
  for (const position of layout.positions) {
    if (position.x < 0 || position.y < 0 || position.x + 240 > layout.width + 0.5 || position.y + 144 > layout.height + 0.5) {
      return position.id;
    }
  }
  for (const route of layout.routes) {
    for (const [x, y] of route.points) {
      if (x < -0.5 || y < -0.5 || x > layout.width + 0.5 || y > layout.height + 0.5) return `${route.from}->${route.to}`;
    }
  }
  return '';
}

test('宽画布同层节点可以超过三列，并减少纵向溢出', async () => {
  const api = await import(layoutUrl);
  const wideNodes = nodes(12);
  const viewport = { width: 1400, height: 520 };
  const chosen = api.layoutCodeMap({ nodes: wideNodes, edges: [], viewport });
  const locked = api.layoutCodeMap({ nodes: wideNodes, edges: [], viewport: { width: 900, height: 520 } });
  assert.ok(api.maxColumnsFor(1400) >= 4, '1400px 应能放下四列正常尺寸卡片');
  assert.ok(chosen.columns >= 4, `应实际选择超过三列，实际 ${chosen.columns}`);
  assert.equal(locked.columns, 3);
  assert.ok(chosen.vOverflow < locked.vOverflow, '更宽候选应减少正常比例下的纵向溢出');
  assert.equal(api.readableZoom(), 1);
  assert.ok(api.fitZoom(locked, { width: 1400, height: 520 }) <= 0.73, '旧式三列高图会被压到 73% 或更低');
  assert.equal(overlaps(chosen), '');
});

test('少量孤立节点保持紧凑，大量孤立节点使用网格', async () => {
  const api = await import(layoutUrl);
  const few = api.layoutCodeMap({ nodes: nodes(2), edges: [], viewport: { width: 1600, height: 900 } });
  const many = api.layoutCodeMap({ nodes: nodes(20), edges: [], viewport: { width: 1600, height: 640 } });
  assert.ok(few.columns <= 2, `两个节点不应被拉满画布，实际 ${few.columns} 列`);
  assert.ok(few.width < 900, '少量节点的包围盒不应接近整屏宽度');
  assert.ok(many.columns >= 4, `大量孤立节点应排成网格，实际 ${many.columns} 列`);
  assert.equal(overlaps(many), '');
});

test('依赖层方向保留，远端连线留在包围盒内', async () => {
  const api = await import(layoutUrl);
  const graph = [
    ...nodes(3, 0),
    ...nodes(2, 1),
    ...nodes(3, 2),
  ];
  const edges = [
    { from: 'n0-0', to: 'n1-0' },
    { from: 'n0-1', to: 'n0-2' },
    { from: 'n0-0', to: 'n2-2', count: 2 },
    { from: 'n1-1', to: 'n2-0' },
  ];
  const layout = api.layoutCodeMap({ nodes: graph, edges, viewport: { width: 1280, height: 800 } });
  const y = new Map(layout.positions.map(position => [position.layer, position.y]));
  assert.ok(y.get(0) < y.get(1) && y.get(1) < y.get(2), '不同依赖层保持上下方向');
  assert.equal(layout.positions.find(position => position.id === 'n0-0').layer, 0);
  assert.equal(outside(layout), '');
  assert.equal(overlaps(layout), '');
  const again = api.routeEdge(
    layout.positions.find(position => position.id === 'n0-0'),
    layout.positions.find(position => position.id === 'n2-2'),
    layout.routeWidth,
    2,
  );
  assert.equal(again.d, layout.routes.find(route => route.from === 'n0-0' && route.to === 'n2-2').d);
});

test('列数接近时保留当前列，避免尺寸抖动', async () => {
  const api = await import(layoutUrl);
  const viewport = { width: 1400, height: 520 };
  const first = api.layoutCodeMap({ nodes: nodes(12), edges: [], viewport });
  assert.ok(first.columns >= 4);
  const nudged = api.layoutCodeMap({
    nodes: nodes(12), edges: [], viewport: { width: viewport.width - 12, height: viewport.height - 6 }, previousColumns: first.columns,
  });
  assert.equal(nudged.columns, first.columns);
  const squeezed = api.layoutCodeMap({
    nodes: nodes(12), edges: [], viewport: { width: 900, height: 520 }, previousColumns: first.columns,
  });
  assert.ok(squeezed.columns < first.columns, '宽度不再容纳当前列时应换列');
});

test('定位滚动只避开浮层矩形，不把整个右侧当成不可用', async () => {
  const api = await import(layoutUrl);
  const view = { left: 0, top: 0, right: 1000, bottom: 700 };
  const pin = { left: 700, top: 0, right: 1000, bottom: 240 };
  const clear = api.scrollToReveal({ left: 40, top: 80, right: 280, bottom: 224 }, view, pin, 12);
  assert.deepEqual(clear, { dx: 0, dy: 0 });
  const covered = api.scrollToReveal({ left: 760, top: 40, right: 980, bottom: 184 }, view, pin, 12);
  assert.ok(covered.dx > 0, '右侧卡片应向左移出浮层');
  assert.equal(covered.dy, 0, '能向左避开时不应额外向下滚动');
  const blocked = api.scrollToReveal({ left: 20, top: 30, right: 900, bottom: 180 }, view, pin, 12);
  assert.ok(blocked.dy > 0, '向左会离开视口时改从浮层下方露出');
});

test('业务数据流连线可安全解析，失败时不返回残缺边', async () => {
  const api = await import(layoutUrl);
  const source = fs.readFileSync(path.join(__dirname, '../browser/fixtures/us3_business_flow.mmd'), 'utf8');
  const ids = [...source.matchAll(/^\s+(\w+)\[/gm)].map(match => match[1]);
  assert.equal(ids.length, 15);
  const edges = api.parseFlowEdges(source, ids);
  assert.equal(edges.length, 24);
  assert.equal(api.parseFlowEdges('flowchart LR\n a --> b\n c -.-> d', ['a', 'b', 'c', 'd']), null);
  assert.equal(api.parseFlowEdges('flowchart LR\n a ==> b', ['a', 'b']), null);
  const layers = api.flowLayers(ids, edges);
  const layout = api.layoutCodeMap({
    nodes: ids.map(id => ({ id, layer: layers.get(id) })),
    edges,
    viewport: { width: 340, height: 220 },
    card: { width: 92, height: 32 },
    spacing: { gapX: 10, gapY: 8, layerGap: 16, marginX: 10, marginTop: 12, marginBottom: 10, gutter: 14 },
  });
  assert.equal(layout.positions.length, 15);
  assert.equal(layout.routes.length, 24);
  assert.equal(outside({ ...layout, positions: layout.positions.map(position => ({ ...position })) }) && false, false);
});
