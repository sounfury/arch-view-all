'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { test } = require('node:test');

const assets = path.join(__dirname, '../../src/arch_view/web/assets');
const pinApi = import(pathToFileURL(path.join(assets, 'pin_layout.js')).href);
const mapApi = import(pathToFileURL(path.join(assets, 'map_layout.js')).href);
const card = { width: 92, height: 32 };
const spacing = { gapX: 10, gapY: 8, layerGap: 18, marginX: 12, marginTop: 14, marginBottom: 12, gutter: 16 };

async function businessGraph() {
  const source = fs.readFileSync(path.join(__dirname, '../browser/fixtures/us3_business_flow.mmd'), 'utf8');
  const ids = [...source.matchAll(/^\s+(\w+)\[/gm)].map(match => match[1]);
  const { parseFlowEdges, flowLayers } = await mapApi;
  const edges = parseFlowEdges(source, ids);
  const layers = flowLayers(ids, edges);
  return { nodes: ids.map(id => ({ id, layer: layers.get(id) })), edges };
}

function segments(route) {
  return route.points.slice(1).map((point, i) => [route.points[i], point]);
}
function insideSegment(a, b, box, size) {
  const epsilon = 0.001;
  if (a.x === b.x) return a.x > box.x + epsilon && a.x < box.x + size.width - epsilon
    && Math.max(a.y, b.y) > box.y + epsilon && Math.min(a.y, b.y) < box.y + size.height - epsilon;
  return a.y > box.y + epsilon && a.y < box.y + size.height - epsilon
    && Math.max(a.x, b.x) > box.x + epsilon && Math.min(a.x, b.x) < box.x + size.width - epsilon;
}
function onBoundary(point, box, size = card) {
  const between = (value, start, length) => value >= start && value <= start + length;
  return ((point.x === box.x || point.x === box.x + size.width) && between(point.y, box.y, size.height))
    || ((point.y === box.y || point.y === box.y + size.height) && between(point.x, box.x, size.width));
}
function assertGeometry(layout, size = card) {
  const positions = new Map(layout.positions.map(position => [position.id, position]));
  for (const box of layout.positions) {
    assert.ok(box.x >= 0 && box.y >= 0 && box.x + size.width <= layout.width && box.y + size.height <= layout.height, `${box.id} within bounds`);
    for (const other of layout.positions) {
      if (box === other) continue;
      assert.ok(box.x + size.width <= other.x || other.x + size.width <= box.x
        || box.y + size.height <= other.y || other.y + size.height <= box.y, `${box.id}/${other.id} overlap`);
    }
  }
  const usedPorts = new Set();
  for (const route of layout.routes) {
    assert.ok(route.points.length >= 2);
    for (const [id, point] of [[route.from, route.points[0]], [route.to, route.points.at(-1)]]) {
      const key = `${id}:${point.x}:${point.y}`;
      assert.ok(!usedPorts.has(key), `${id} has a shared port`);
      usedPorts.add(key);
    }
    assert.ok(onBoundary(route.points[0], positions.get(route.from), size), `${route.from} source port`);
    assert.ok(onBoundary(route.points.at(-1), positions.get(route.to), size), `${route.to} target port`);
    const fromPath = [...route.d.matchAll(/[ML](-?[\d.]+),(-?[\d.]+)/g)].map(match => ({ x: Number(match[1]), y: Number(match[2]) }));
    assert.deepEqual(fromPath, route.points, 'rendered path must match the checked geometry');
    for (const point of route.points) assert.ok(point.x >= 0 && point.y >= 0 && point.x <= layout.width && point.y <= layout.height, 'route within bounds');
    for (const [a, b] of segments(route)) {
      assert.ok(a.x === b.x || a.y === b.y, 'orthogonal route');
      for (const box of layout.positions) {
        // 检查端点卡片本身也不可被路径穿透。
        assert.ok(!insideSegment(a, b, box, size), `${route.from}->${route.to} crosses ${box.id}`);
      }
    }
  }
}
function maxSharedRun(layout) {
  let longest = 0;
  for (let i = 0; i < layout.routes.length; i += 1) {
    for (let j = i + 1; j < layout.routes.length; j += 1) {
      for (const [a, b] of segments(layout.routes[i])) {
        for (const [c, d] of segments(layout.routes[j])) {
          const vertical = a.x === b.x && c.x === d.x && a.x === c.x;
          const horizontal = a.y === b.y && c.y === d.y && a.y === c.y;
          if (!vertical && !horizontal) continue;
          const axis = vertical ? 'y' : 'x';
          longest = Math.max(longest, Math.min(Math.max(a[axis], b[axis]), Math.max(c[axis], d[axis]))
            - Math.max(Math.min(a[axis], b[axis]), Math.min(c[axis], d[axis])));
        }
      }
    }
  }
  return longest;
}
function crossingCount(layout) {
  let count = 0;
  for (let i = 0; i < layout.routes.length; i += 1) {
    for (let j = i + 1; j < layout.routes.length; j += 1) {
      for (const [a, b] of segments(layout.routes[i])) {
        for (const [c, d] of segments(layout.routes[j])) {
          const sameVertical = a.x === b.x && c.x === d.x && a.x === c.x;
          const sameHorizontal = a.y === b.y && c.y === d.y && a.y === c.y;
          if (sameVertical || sameHorizontal) {
            const axis = sameVertical ? 'y' : 'x';
            if (Math.min(Math.max(a[axis], b[axis]), Math.max(c[axis], d[axis]))
              > Math.max(Math.min(a[axis], b[axis]), Math.min(c[axis], d[axis]))) count += 1;
          }
          const [v1, v2, h1, h2] = a.x === b.x ? [a, b, c, d] : [c, d, a, b];
          if (v1.x !== v2.x || h1.y !== h2.y) continue;
          if (v1.x > Math.min(h1.x, h2.x) && v1.x < Math.max(h1.x, h2.x)
            && h1.y > Math.min(v1.y, v2.y) && h1.y < Math.max(v1.y, v2.y)) count += 1;
        }
      }
    }
  }
  return count;
}

test('真实 15 节点 24 连线保留标签、元数据、层级与确定性，不改变输入', async () => {
  const { layoutPinnedGraph } = await pinApi;
  const graph = await businessGraph();
  graph.edges = graph.edges.map((edge, index) => ({ ...edge, count: index + 1, detail: { index } }));
  const before = structuredClone(graph);
  graph.nodes.forEach(Object.freeze);
  graph.edges.forEach(Object.freeze);
  const options = { ...graph, viewport: { width: 340, height: 220 }, card, spacing };
  const layout = layoutPinnedGraph(options);
  assert.equal(layout.positions.length, 15);
  assert.equal(layout.routes.length, 24);
  assert.deepEqual(new Set(layout.positions.map(position => position.id)), new Set(graph.nodes.map(node => node.id)));
  layout.routes.forEach((route, index) => {
    for (const [key, value] of Object.entries(graph.edges[index])) assert.deepEqual(route[key], value);
  });
  const positions = new Map(layout.positions.map(position => [position.id, position]));
  for (const a of graph.nodes) for (const b of graph.nodes) {
    if (a.layer < b.layer) assert.ok(positions.get(a.id).y + card.height < positions.get(b.id).y, 'every supplied layer precedes the next');
  }
  assert.deepEqual(layoutPinnedGraph(options), layout);
  assert.deepEqual(graph, before);
  assert.ok(layout.width <= 340, `compact width: ${layout.width}`);
  assert.ok(layout.height < 850, `compact height: ${layout.height}`);
  assertGeometry(layout);
  assert.ok(maxSharedRun(layout) < 24, `long shared run: ${maxSharedRun(layout)}`);
});

test('窄视口换行不缩小卡片，跳层边不穿过中间卡片', async () => {
  const { layoutPinnedGraph } = await pinApi;
  const graph = await businessGraph();
  for (const width of [120, 220, 280, 360, 720]) {
    const layout = layoutPinnedGraph({ ...graph, viewport: { width, height: 200 }, card, spacing });
    assert.equal(layout.positions.length, 15);
    assert.equal(layout.routes.length, 24);
    assertGeometry(layout);
    if (width >= 220) assert.ok(layout.width <= width, `${layout.width} fits ${width}`);
  }
  const isolated = Array.from({ length: 8 }, (_, i) => ({ id: `n${i}`, layer: 7 }));
  const narrow = layoutPinnedGraph({ nodes: isolated, viewport: { width: 220 } });
  const wide = layoutPinnedGraph({ nodes: isolated, viewport: { width: 840 } });
  assert.ok(narrow.height > wide.height);
  assert.equal(new Set(wide.positions.map(position => position.y)).size, 1);
  assertGeometry(narrow);
  assertGeometry(wide);
});

test('同层重排降低简单交叉图的实际连线交叉', async () => {
  const { layoutPinnedGraph } = await pinApi;
  const { layoutCodeMap } = await mapApi;
  const nodes = [{ id: 'a', layer: 0 }, { id: 'b', layer: 0 }, { id: 'c', layer: 1 }, { id: 'd', layer: 1 }];
  const edges = [{ from: 'a', to: 'd' }, { from: 'b', to: 'c' }];
  const options = { nodes, edges, viewport: { width: 280, height: 100 }, card, spacing };
  const old = layoutCodeMap(options);
  old.routes.forEach(route => { route.points = route.points.map(([x, y]) => ({ x, y })); });
  const layout = layoutPinnedGraph(options);
  const positions = new Map(layout.positions.map(position => [position.id, position]));
  assert.ok((positions.get('a').x - positions.get('b').x) * (positions.get('d').x - positions.get('c').x) > 0, 'connected pairs have the same left-to-right order');
  assert.ok(crossingCount(layout) < crossingCount(old), `${crossingCount(old)} -> ${crossingCount(layout)} crossings/overlaps`);
  assert.equal(crossingCount(layout), 0);
  assertGeometry(layout);
});

test('根代码层不按业务拓扑重算，回边走外围，自环、平行边、同排边均保留', async () => {
  const { layoutPinnedGraph } = await pinApi;
  const nodes = [
    { id: 'a', layer: -2 }, { id: 'b', layer: -2 }, { id: 'c', layer: -2 },
    { id: 'd', layer: 5 }, { id: 'e', layer: 9 },
  ];
  const edges = [
    { from: 'a', to: 'e', label: 'skip', count: 3 },
    { from: 'e', to: 'a', label: 'return' },
    { from: 'e', to: 'b', label: 'return 2' },
    { from: 'a', to: 'c', label: 'same row' },
    { from: 'b', to: 'b', label: 'self' },
    { from: 'a', to: 'd', label: 'first' },
    { from: 'a', to: 'd', label: 'second' },
  ];
  const layout = layoutPinnedGraph({ nodes, edges, viewport: { width: 420 }, card, spacing });
  const positions = new Map(layout.positions.map(position => [position.id, position]));
  assert.equal(positions.get('a').y, positions.get('b').y, 'incoming dependency must not change the supplied root code layer');
  assert.ok(positions.get('d').y > positions.get('a').y && positions.get('e').y > positions.get('d').y);
  const left = Math.min(...layout.positions.map(position => position.x));
  const right = Math.max(...layout.positions.map(position => position.x + card.width));
  for (const route of layout.routes.filter(route => ['return', 'return 2', 'self'].includes(route.label))) {
    assert.equal(route.backward, true);
    assert.ok(segments(route).some(([a, b]) => a.x === b.x && a.y !== b.y && (a.x < left || a.x > right)), 'backward vertical channel is outside all cards');
  }
  assert.notDeepEqual(layout.routes[5].points, layout.routes[6].points, 'parallel edges have distinct geometry');
  const endpoints = layout.routes.filter(route => route.from === 'a').map(route => route.points[0]);
  assert.equal(new Set(endpoints.map(point => `${point.x},${point.y}`)).size, endpoints.length, 'distinct source ports');
  assertGeometry(layout);
  assert.ok(maxSharedRun(layout) < 24);
});

test('混合扇入扇出和回路在换行后仍不穿卡、不产生长段重合', async () => {
  const { layoutPinnedGraph } = await pinApi;
  const nodes = Array.from({ length: 12 }, (_, i) => ({ id: `n${i}`, layer: Math.floor(i / 3) }));
  const edges = [];
  for (let from = 0; from < nodes.length; from += 1) {
    for (let to = 0; to < nodes.length; to += 1) {
      if ((from * 7 + to * 3) % 5 === 0) edges.push({ from: `n${from}`, to: `n${to}`, label: `${from}/${to}` });
    }
  }
  for (const width of [160, 280, 420]) {
    const layout = layoutPinnedGraph({ nodes, edges, viewport: { width } });
    assert.equal(layout.routes.length, edges.length);
    assertGeometry(layout);
    assert.ok(maxSharedRun(layout) < 24, `long shared run at ${width}: ${maxSharedRun(layout)}`);
  }
});

test('空图、自定义卡片与不合法端点的显式处理', async () => {
  const { layoutPinnedGraph } = await pinApi;
  const empty = layoutPinnedGraph({ nodes: [], edges: [] });
  assert.deepEqual(empty.positions, []);
  assert.deepEqual(empty.routes, []);
  assert.ok(Number.isFinite(empty.width) && Number.isFinite(empty.height));
  const size = { width: 140, height: 48 };
  const layout = layoutPinnedGraph({ nodes: [{ id: 'a', layer: 0 }, { id: 'b', layer: 1 }], edges: [{ from: 'a', to: 'b' }], card: size });
  assertGeometry(layout, size);
  assert.throws(() => layoutPinnedGraph({ nodes: [{ id: 'a' }], edges: [{ from: 'a', to: 'missing' }] }), /endpoint/);
});
