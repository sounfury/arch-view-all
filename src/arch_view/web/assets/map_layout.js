/* 代码图布局：按画布尺寸在有限候选列数里选择排布，默认不缩小卡片。 */

export const CARD = { width: 240, height: 144 };

const DEFAULTS = {
  gapX: 32,
  gapY: 34,
  layerGap: 56,
  marginX: 28,
  marginTop: 44,
  marginBottom: 24,
  gutter: 26,
  laneStep: 4,
};

export function readableZoom() {
  return 1;
}

export function fitZoom(layout, viewport, padding = 12) {
  const width = Math.max(1, layout.width);
  const height = Math.max(1, layout.height);
  const widthFit = (viewport.width - padding) / width;
  const heightFit = (viewport.height - padding) / height;
  return Math.min(1, Math.max(0.15, Math.min(widthFit, heightFit)));
}

export function maxColumnsFor(viewportWidth, card = CARD, spacing = {}) {
  const metrics = metricsFor(card, spacing);
  const inner = Math.max(metrics.cardWidth, viewportWidth - metrics.marginX * 2);
  return Math.max(1, Math.floor((inner + metrics.gapX) / (metrics.cardWidth + metrics.gapX)));
}

export function flowLayers(ids, edges) {
  const incoming = new Map(ids.map(id => [id, 0]));
  const outgoing = new Map(ids.map(id => [id, []]));
  for (const edge of edges) {
    if (!incoming.has(edge.from) || !incoming.has(edge.to) || edge.from === edge.to) continue;
    outgoing.get(edge.from).push(edge.to);
    incoming.set(edge.to, incoming.get(edge.to) + 1);
  }
  const layer = new Map();
  let frontier = ids.filter(id => incoming.get(id) === 0);
  let rank = 0;
  const seen = new Set();
  while (frontier.length && rank <= ids.length) {
    const next = [];
    for (const id of frontier) {
      if (seen.has(id)) continue;
      seen.add(id);
      layer.set(id, rank);
      for (const target of outgoing.get(id)) {
        incoming.set(target, incoming.get(target) - 1);
        if (incoming.get(target) === 0) next.push(target);
      }
    }
    frontier = next;
    rank += 1;
  }
  for (const id of ids) if (!layer.has(id)) layer.set(id, rank++);
  return layer;
}

// 只接受标准 flowchart 连线。无法完整对应节点时返回 null，调用方应保留原图。
export function parseFlowEdges(source, ids) {
  const known = new Set(ids);
  const stripped = String(source || '')
    .replace(/%%[^\n]*/g, '')
    .replace(/"(?:\\.|[^"\\])*"/g, '""');
  const edges = [];
  const pattern = /([A-Za-z][\w]*)\s+-->\s*(?:\|([^|]*)\|)?\s*([A-Za-z][\w]*)/g;
  let match;
  while ((match = pattern.exec(stripped))) {
    if (!known.has(match[1]) || !known.has(match[3])) return null;
    edges.push({ from: match[1], to: match[3], label: (match[2] || '').trim() });
  }
  pattern.lastIndex = 0;
  const residual = stripped.replace(pattern, '');
  if (!edges.length || /--|==>|-\.?(?:-|>)|o--|--o/.test(residual)) return null;
  return edges;
}

export function layoutCodeMap({ nodes, edges = [], viewport, previousColumns = null, card = CARD, spacing = {} }) {
  const metrics = metricsFor(card, spacing);
  const list = (nodes || []).map(node => ({ id: node.id, layer: Number(node.layer) || 0 }));
  const groups = new Map();
  for (const node of list) {
    if (!groups.has(node.layer)) groups.set(node.layer, []);
    groups.get(node.layer).push(node);
  }
  const layers = [...groups.keys()].sort((a, b) => a - b);
  const widest = Math.max(1, ...[...groups.values()].map(group => group.length));
  const upper = Math.min(widest, maxColumnsFor(viewport?.width || metrics.cardWidth, card, spacing));
  const candidates = [];
  for (let columns = 1; columns <= upper; columns += 1) {
    candidates.push(measure(groups, layers, edges, columns, viewport || { width: 960, height: 640 }, metrics));
  }
  return pick(candidates, previousColumns);
}

export function routeEdge(from, to, width, index, metrics = null) {
  const m = metrics || metricsFor(CARD, {});
  const lane = (index % 4) * m.laneStep;
  const sx = from.x + m.cardWidth / 2;
  const tx = to.x + m.cardWidth / 2;
  if (from.y === to.y) {
    const right = to.x >= from.x;
    const stride = m.cardWidth + m.gapX;
    if (Math.abs(to.x - from.x) <= stride + 0.5) {
      const start = from.x + (right ? m.cardWidth : 0);
      const end = to.x + (right ? -3 : m.cardWidth + 3);
      const y = from.y + m.cardHeight / 2;
      return { d: `M${start},${y} L${end},${y}`, x: (start + end) / 2, y: y - 8, points: [[start, y], [end, y]] };
    }
    const y = from.y - 16 - lane;
    const points = [[sx, from.y], [sx, y], [tx, y], [tx, to.y - 3]];
    return { d: roundedRoute(points), x: (sx + tx) / 2, y: y - 5, points };
  }
  const down = to.y > from.y;
  const sy = from.y + (down ? m.cardHeight : 0);
  const ty = to.y + (down ? -3 : m.cardHeight + 3);
  const dy = Math.abs(to.y - from.y);
  if (dy <= m.cardHeight + m.layerGap + m.gapY) {
    const middle = (sy + ty) / 2;
    const points = [[sx, sy], [sx, middle], [tx, middle], [tx, ty]];
    return { d: roundedRoute(points), x: (sx + tx) / 2 + 6, y: middle - 5, points };
  }
  const gutter = (sx + tx) / 2 < width / 2 ? 12 + lane : width - 12 - lane;
  const exit = sy + (down ? 14 + lane : -14 - lane);
  const entry = ty + (down ? -14 - lane : 14 + lane);
  const points = [[sx, sy], [sx, exit], [gutter, exit], [gutter, entry], [tx, entry], [tx, ty]];
  return { d: roundedRoute(points), x: gutter + 6, y: (exit + entry) / 2, points };
}

// 把选中卡片滚出钉住浮层。dx/dy 是 viewport.scrollBy 的增量。
export function scrollToReveal(card, view, occlusion, pad = 12) {
  const safe = {
    left: view.left + pad,
    top: view.top + pad,
    right: view.right - pad,
    bottom: view.bottom - pad,
  };
  let dx = 0;
  let dy = 0;
  if (card.left < safe.left) dx -= safe.left - card.left;
  if (card.right - dx > safe.right) dx += card.right - dx - safe.right;
  if (card.left - dx < safe.left) dx -= safe.left - (card.left - dx);
  if (card.top < safe.top) dy -= safe.top - card.top;
  if (card.bottom - dy > safe.bottom) dy += card.bottom - dy - safe.bottom;
  if (card.top - dy < safe.top) dy -= safe.top - (card.top - dy);
  if (!occlusion) return { dx, dy };
  const moved = () => ({
    left: card.left - dx,
    right: card.right - dx,
    top: card.top - dy,
    bottom: card.bottom - dy,
  });
  const hits = rect => rect.left < occlusion.right - 1 && rect.right > occlusion.left + 1 &&
    rect.top < occlusion.bottom - 1 && rect.bottom > occlusion.top + 1;
  if (!hits(moved())) return { dx, dy };
  const shiftLeft = moved().right - (occlusion.left - 4);
  if (shiftLeft > 0 && card.left - (dx + shiftLeft) >= safe.left - 1) return { dx: dx + shiftLeft, dy };
  const shiftDown = (occlusion.bottom + 4) - moved().top;
  if (shiftDown > 0) return { dx, dy: dy + shiftDown };
  return { dx, dy };
}

function metricsFor(card, spacing) {
  return {
    ...DEFAULTS,
    ...spacing,
    cardWidth: card?.width || CARD.width,
    cardHeight: card?.height || CARD.height,
  };
}

function measure(groups, layers, edges, columns, viewport, metrics) {
  const draft = place(groups, layers, columns, metrics, false);
  const gutter = edgesNeedGutter(draft.positions, edges, metrics);
  const placed = gutter ? place(groups, layers, columns, metrics, true) : draft;
  let routed = routeAll(placed, edges, metrics);
  let box = extent(placed, routed, metrics);
  const shiftX = box.minX < 8 ? 8 - box.minX : 0;
  const shiftY = box.minY < 8 ? 8 - box.minY : 0;
  if (shiftX || shiftY) {
    for (const position of placed.positions.values()) {
      position.x += shiftX;
      position.y += shiftY;
    }
    placed.width += shiftX;
    placed.height += shiftY;
    routed = routeAll(placed, edges, metrics);
    box = extent(placed, routed, metrics);
  }
  // routeWidth 是连线使用的宽度。显示尺寸可更大以容纳标签，但不能反过来改变绕行通道。
  const width = Math.ceil(Math.max(placed.width, box.maxX + 8));
  const height = Math.ceil(Math.max(placed.height, box.maxY + 8));
  const hOverflow = Math.max(0, width - (viewport.width || width));
  const vOverflow = Math.max(0, height - (viewport.height || height));
  return {
    columns,
    positions: [...placed.positions.entries()].map(([id, position]) => ({ id, ...position })),
    width,
    height,
    routeWidth: placed.width,
    routes: routed,
    hOverflow,
    vOverflow,
  };
}

function place(groups, layers, columns, metrics, gutter) {
  const marginX = metrics.marginX + (gutter ? metrics.gutter : 0);
  const contentWidth = columns * metrics.cardWidth + Math.max(0, columns - 1) * metrics.gapX;
  const stride = metrics.cardWidth + metrics.gapX;
  const rowStride = metrics.cardHeight + metrics.gapY;
  const positions = new Map();
  let y = metrics.marginTop;
  for (const layer of layers) {
    const group = groups.get(layer);
    const rows = Math.ceil(group.length / columns);
    group.forEach((node, index) => {
      const row = Math.floor(index / columns);
      const rowCount = Math.min(columns, group.length - row * columns);
      const rowWidth = rowCount * metrics.cardWidth + Math.max(0, rowCount - 1) * metrics.gapX;
      const x = marginX + (contentWidth - rowWidth) / 2 + (index % columns) * stride;
      positions.set(node.id, { x, y: y + row * rowStride, layer: node.layer });
    });
    y += rows * rowStride - metrics.gapY + metrics.layerGap;
  }
  const width = Math.max(metrics.cardWidth + marginX * 2, marginX + contentWidth + marginX);
  const height = Math.max(metrics.cardHeight + metrics.marginTop + metrics.marginBottom, y - metrics.layerGap + metrics.marginBottom);
  return { positions, width, height };
}

function edgesNeedGutter(positions, edges, metrics) {
  return edges.some(edge => {
    const from = positions.get(edge.from);
    const to = positions.get(edge.to);
    return from && to && from.y !== to.y && Math.abs(to.y - from.y) > metrics.cardHeight + metrics.layerGap + metrics.gapY;
  });
}

function routeAll(placed, edges, metrics) {
  const routes = [];
  let index = 0;
  for (const edge of edges) {
    const from = placed.positions.get(edge.from);
    const to = placed.positions.get(edge.to);
    if (!from || !to) continue;
    routes.push({
      from: edge.from,
      to: edge.to,
      label: edge.label || '',
      count: edge.count || 0,
      ...routeEdge(from, to, placed.width, index, metrics),
    });
    index += 1;
  }
  return routes;
}

function extent(placed, routes, metrics) {
  let minX = 0;
  let minY = 0;
  let maxX = placed.width;
  let maxY = placed.height;
  for (const position of placed.positions.values()) {
    minX = Math.min(minX, position.x);
    minY = Math.min(minY, position.y - 26);
    maxX = Math.max(maxX, position.x + metrics.cardWidth);
    maxY = Math.max(maxY, position.y + metrics.cardHeight);
  }
  for (const route of routes) {
    for (const [x, y] of route.points) {
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
    }
    minX = Math.min(minX, route.x - 18);
    maxX = Math.max(maxX, route.x + 18);
    minY = Math.min(minY, route.y - 14);
    maxY = Math.max(maxY, route.y + 6);
  }
  return { minX, minY, maxX, maxY };
}

function pick(candidates, previousColumns) {
  const fitting = candidates.filter(candidate => candidate.hOverflow <= 1);
  const pool = (fitting.length ? fitting : candidates).slice()
    .sort((a, b) => a.vOverflow - b.vOverflow || a.hOverflow - b.hOverflow || a.width - b.width || a.columns - b.columns);
  let best = pool[0];
  if (previousColumns != null) {
    const current = candidates.find(candidate => candidate.columns === previousColumns);
    if (current && current.hOverflow <= 24 && current.vOverflow <= best.vOverflow + 48 && current.hOverflow <= best.hOverflow + 24) best = current;
  }
  return best;
}

function roundedRoute(points) {
  let d = `M${points[0][0]},${points[0][1]}`;
  for (let index = 1; index < points.length - 1; index += 1) {
    const previous = points[index - 1];
    const corner = points[index];
    const next = points[index + 1];
    const before = Math.hypot(corner[0] - previous[0], corner[1] - previous[1]);
    const after = Math.hypot(next[0] - corner[0], next[1] - corner[1]);
    const radius = Math.min(8, before / 2, after / 2);
    if (!before || !after) continue;
    const x1 = corner[0] + (previous[0] - corner[0]) * radius / before;
    const y1 = corner[1] + (previous[1] - corner[1]) * radius / before;
    const x2 = corner[0] + (next[0] - corner[0]) * radius / after;
    const y2 = corner[1] + (next[1] - corner[1]) * radius / after;
    d += ` L${x1},${y1} Q${corner[0]},${corner[1]} ${x2},${y2}`;
  }
  return `${d} L${points.at(-1)[0]},${points.at(-1)[1]}`;
}
