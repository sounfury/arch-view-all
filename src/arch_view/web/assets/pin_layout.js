/* Pin 专用布局：保留依赖层，在固定卡片网格的空隙内分配独立走线。 */
const COMPACT_CARD = { width: 92, height: 32 };
const DEFAULT_SPACING = {
  gapX: 10, gapY: 8, layerGap: 18,
  marginX: 12, marginTop: 12, marginBottom: 12, gutter: 16, laneStep: 3,
};
const round = value => Math.round(value * 1000) / 1000;

export function layoutPinnedGraph({ nodes = [], edges = [], viewport = {}, card = COMPACT_CARD, spacing = {} }) {
  const metrics = {
    ...DEFAULT_SPACING, ...spacing,
    cardWidth: card.width || COMPACT_CARD.width,
    cardHeight: card.height || COMPACT_CARD.height,
  };
  metrics.laneStep = Math.max(3, metrics.laneStep);
  const byId = new Map(nodes.map(node => [node.id, { id: node.id, layer: Number(node.layer) || 0 }]));
  if (byId.size !== nodes.length) throw new Error('Pinned graph node IDs must be unique');
  for (const edge of edges) {
    if (!byId.has(edge.from) || !byId.has(edge.to)) throw new Error('Pinned graph edge endpoint is missing');
  }
  const groups = new Map();
  for (const node of byId.values()) {
    if (!groups.has(node.layer)) groups.set(node.layer, []);
    groups.get(node.layer).push(node.id);
  }
  const layers = [...groups.keys()].sort((a, b) => a - b);
  const ordered = orderLayers(layers.map(layer => groups.get(layer)), edges);
  const widest = Math.max(1, ...ordered.map(group => group.length));
  const available = (viewport.width || 360) - metrics.marginX * 2;
  const upper = Math.min(widest, Math.max(1, Math.floor((available + metrics.gapX) / (metrics.cardWidth + metrics.gapX))));
  let result;
  // 通道宽度也计入换行判断；只有卡片本身已放不下时才允许横向溢出。
  for (let columns = upper; columns >= 1; columns -= 1) {
    result = placeAndRoute(ordered, layers, byId, edges, columns, metrics);
    if (result.width <= (viewport.width || 360) || columns === 1) break;
  }
  return result;
}

function orderLayers(groups, edges) {
  const order = groups.map(group => group.slice());
  const rank = new Map(order.flatMap((group, index) => group.map(id => [id, index])));
  const neighbors = new Map(order.flat().map(id => [id, []]));
  for (const edge of edges) {
    if (rank.get(edge.from) === rank.get(edge.to)) continue;
    neighbors.get(edge.from).push(edge.to);
    neighbors.get(edge.to).push(edge.from);
  }
  const coordinates = () => new Map(order.flatMap(group => group.map((id, index) => [id, (index + 0.5) / group.length])));
  // 比较层之间直线的顺序反转；跳层边按共同的层区间插值。
  const crossings = () => {
    const x = coordinates();
    let count = 0;
    for (let i = 0; i < edges.length; i += 1) {
      const a = edges[i], ay = rank.get(a.from), by = rank.get(a.to);
      if (ay === by) continue;
      for (let j = i + 1; j < edges.length; j += 1) {
        const b = edges[j], cy = rank.get(b.from), dy = rank.get(b.to);
        if (cy === dy || a.from === b.from || a.to === b.to || a.from === b.to || a.to === b.from) continue;
        const top = Math.max(Math.min(ay, by), Math.min(cy, dy));
        const bottom = Math.min(Math.max(ay, by), Math.max(cy, dy));
        if (top >= bottom) continue;
        const delta = y => x.get(a.from) + (x.get(a.to) - x.get(a.from)) * (y - ay) / (by - ay)
          - x.get(b.from) - (x.get(b.to) - x.get(b.from)) * (y - cy) / (dy - cy);
        if (delta(top) * delta(bottom) < 0) count += 1;
      }
    }
    return count;
  };
  let best = order.map(group => group.slice()), bestScore = crossings();
  for (let sweep = 0; sweep < 8 && bestScore > 0; sweep += 1) {
    const down = sweep % 2 === 0;
    for (let step = 0; step < order.length; step += 1) {
      const index = down ? step : order.length - 1 - step;
      const x = coordinates();
      const score = new Map(order[index].map(id => {
        const adjacent = neighbors.get(id).filter(other => down ? rank.get(other) < index : rank.get(other) > index);
        return [id, adjacent.length ? adjacent.reduce((sum, other) => sum + x.get(other), 0) / adjacent.length : x.get(id)];
      }));
      order[index].sort((a, b) => score.get(a) - score.get(b));
    }
    const score = crossings();
    if (score < bestScore) {
      bestScore = score;
      best = order.map(group => group.slice());
    }
  }
  return best;
}

function placeAndRoute(groups, layers, byId, edges, columns, m) {
  const rows = [], cells = new Map();
  groups.forEach((group, layerIndex) => {
    for (let offset = 0; offset < group.length; offset += columns) {
      const row = rows.length;
      const ids = group.slice(offset, offset + columns);
      rows.push({ ids, layer: layers[layerIndex] });
      ids.forEach((id, col) => cells.set(id, { id, row, col }));
    }
  });
  const corridors = Array.from({ length: columns + 1 }, () => []);
  const plans = edges.map((edge, index) => {
    const from = cells.get(edge.from), to = cells.get(edge.to);
    const backward = byId.get(edge.to).layer < byId.get(edge.from).layer || to.row < from.row || edge.from === edge.to;
    const fromSide = backward || from.row === to.row ? 'top' : 'bottom';
    const toSide = backward ? 'bottom' : 'top';
    const exit = from.row + (fromSide === 'bottom' ? 1 : 0);
    const entry = to.row + (toSide === 'bottom' ? 1 : 0);
    const plan = { edge, index, from, to, fromSide, toSide, exit, entry, backward };
    if (exit !== entry) {
      // 正向跨行边优先走列间空隙；回边始终走卡片网格外围。
      const candidates = backward || columns === 1 ? [0, columns] : Array.from({ length: columns - 1 }, (_, i) => i + 1);
      const low = Math.min(exit, entry), high = Math.max(exit, entry);
      const options = candidates.map(gap => {
        const lanes = corridors[gap];
        let lane = lanes.findIndex(intervals => intervals.every(interval => high < interval.low || low > interval.high));
        if (lane < 0) lane = lanes.length;
        const distance = Math.abs(from.col + 0.5 - gap) + Math.abs(to.col + 0.5 - gap);
        return { gap, lane, score: distance + Math.max(0, lane + 1 - lanes.length) * 0.5 + lane * 0.6 };
      }).sort((a, b) => a.score - b.score || a.gap - b.gap);
      const selected = options[0];
      if (!corridors[selected.gap][selected.lane]) corridors[selected.gap][selected.lane] = [];
      corridors[selected.gap][selected.lane].push({ low, high });
      plan.channel = selected;
    }
    return plan;
  });
  const channelPadding = 4;
  const extra = lanes => lanes.length ? channelPadding * 2 + (lanes.length - 1) * m.laneStep : 0;
  const left = m.marginX + extra(corridors[0]);
  const columnX = [left], channelX = [];
  channelX[0] = lane => left - channelPadding - lane * m.laneStep;
  for (let col = 1; col < columns; col += 1) {
    const gap = Math.max(m.gapX, extra(corridors[col]));
    const end = columnX[col - 1] + m.cardWidth;
    const start = end + (gap - Math.max(0, corridors[col].length - 1) * m.laneStep) / 2;
    channelX[col] = lane => start + lane * m.laneStep;
    columnX.push(end + gap);
  }
  const right = columnX[columns - 1] + m.cardWidth;
  channelX[columns] = lane => right + channelPadding + lane * m.laneStep;
  const width = Math.ceil(right + extra(corridors[columns]) + m.marginX);

  // 每张卡片的每一侧分别分配端口，包括平行边和自环的两个端点。
  const ports = new Map();
  for (const plan of plans) {
    for (const end of ['from', 'to']) {
      const cell = plan[end], side = plan[`${end}Side`];
      const key = `${cell.row}:${cell.col}:${side}`;
      if (!ports.has(key)) ports.set(key, []);
      ports.get(key).push({ plan, end, other: plan[end === 'from' ? 'to' : 'from'] });
    }
  }
  for (const list of ports.values()) {
    list.sort((a, b) => a.other.col - b.other.col || a.other.row - b.other.row || a.plan.index - b.plan.index);
    list.forEach(({ plan, end }, index) => {
      const padding = Math.min(8, m.cardWidth / 4);
      plan[`${end}X`] = round(columnX[plan[end].col] + padding + (m.cardWidth - padding * 2) * (index + 1) / (list.length + 1));
    });
  }
  const bands = Array.from({ length: rows.length + 1 }, () => []);
  for (const plan of plans) {
    if (plan.channel) {
      plan.channelX = channelX[plan.channel.gap](plan.channel.lane);
      bands[plan.exit].push({ plan, end: 'exit', low: Math.min(plan.fromX, plan.channelX), high: Math.max(plan.fromX, plan.channelX) });
      bands[plan.entry].push({ plan, end: 'entry', low: Math.min(plan.toX, plan.channelX), high: Math.max(plan.toX, plan.channelX) });
    } else {
      bands[plan.exit].push({ plan, end: 'exit', low: Math.min(plan.fromX, plan.toX), high: Math.max(plan.fromX, plan.toX) });
    }
  }
  const bandHeights = [], bandCounts = [];
  bands.forEach((segments, index) => {
    const occupied = [];
    segments.sort((a, b) => a.low - b.low || a.high - b.high || a.plan.index - b.plan.index);
    for (const segment of segments) {
      let lane = occupied.findIndex(high => high + channelPadding < segment.low);
      if (lane < 0) lane = occupied.length;
      occupied[lane] = segment.high;
      segment.plan[`${segment.end}Lane`] = lane;
    }
    const base = index === 0 ? m.marginTop : index === rows.length ? m.marginBottom
      : rows[index - 1].layer === rows[index].layer ? m.gapY : m.layerGap;
    bandCounts[index] = occupied.length;
    bandHeights[index] = Math.max(base, occupied.length ? 12 + (occupied.length - 1) * m.laneStep : 0);
  });
  const bandY = [], rowY = [];
  let y = 0;
  bands.forEach((_, index) => {
    bandY[index] = y + (bandHeights[index] - Math.max(0, bandCounts[index] - 1) * m.laneStep) / 2;
    y += bandHeights[index];
    if (index < rows.length) {
      rowY[index] = y;
      y += m.cardHeight;
    }
  });
  const positions = [...cells.values()].map(cell => ({ id: cell.id, x: columnX[cell.col], y: rowY[cell.row] }));
  const routes = plans.map(plan => {
    const startY = rowY[plan.from.row] + (plan.fromSide === 'bottom' ? m.cardHeight : 0);
    const endY = rowY[plan.to.row] + (plan.toSide === 'bottom' ? m.cardHeight : 0);
    const exitY = bandY[plan.exit] + plan.exitLane * m.laneStep;
    const points = [{ x: plan.fromX, y: startY }, { x: plan.fromX, y: exitY }];
    if (plan.channel) {
      const entryY = bandY[plan.entry] + plan.entryLane * m.laneStep;
      points.push({ x: plan.channelX, y: exitY }, { x: plan.channelX, y: entryY }, { x: plan.toX, y: entryY });
    } else points.push({ x: plan.toX, y: exitY });
    points.push({ x: plan.toX, y: endY });
    const clean = points.map(point => ({ x: round(point.x), y: round(point.y) }))
      .filter((point, index, all) => index === 0 || point.x !== all[index - 1].x || point.y !== all[index - 1].y);
    return { ...plan.edge, d: clean.map((point, index) => `${index ? 'L' : 'M'}${point.x},${point.y}`).join(' '), points: clean, backward: plan.backward };
  });
  return { width, height: Math.ceil(y), positions, routes };
}
