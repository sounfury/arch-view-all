/* 职责：在主画布按类分泳道展示入口方法调用图，保留分叉、虚线原因与出口；支持源码定位、继续展开、返回包视图和另存 trace。 */

const NODE_WIDTH = 264, NODE_HEIGHT = 88, LANE_WIDTH = 292, LANE_GAP = 42, TOP = 78;

export function createEntryChain(host) {
  const { state, api, element, button, icon, svgElement, $, toast, showSource, showAnchor, openDrawer } = host;
  let entry = null, data = null, selected = null, loading = false, error = '', request = 0, previous = null;
  let options = { depth: 6, limit: 60 };

  function active() { return state.diagramKind === 'chain' && Boolean(entry); }
  function modules() { return active() ? data?.modules || [] : []; }
  async function open(nextEntry, nextOptions = { depth: 6, limit: 60 }) {
    if (!active()) {
      host.closeTrace();
      previous = { kind: state.diagramKind, camera: host.captureCamera(), collapsed: state.detailCollapsed, forced: state.detailForced };
    }
    entry = nextEntry; options = nextOptions; selected = null; data = null; error = ''; loading = true;
    state.diagramKind = 'chain'; state.sourceModule = null;
    host.showExplore();
    const current = ++request;
    host.refresh();
    try {
      const result = await api('/api/entry/chain', { id: entry.id, ...options });
      if (current !== request) return;
      data = result; selected = result.root;
    } catch (failure) { if (current === request) error = failure.message; }
    finally {
      if (current === request) {
        loading = false; host.refresh();
        if (data) host.present();
      }
    }
  }
  function close({ restore = true } = {}) {
    if (!entry) return;
    ++request; entry = null; data = null; selected = null; error = ''; loading = false;
    state.diagramKind = restore ? previous?.kind || 'code' : 'code';
    $('#entry-chain-tools').hidden = true;
    if (restore) {
      state.detailCollapsed = previous?.collapsed ?? true; state.detailForced = previous?.forced ?? false;
      host.refresh(); if (previous?.camera) host.restoreCamera(previous.camera);
    }
    previous = null;
  }
  function reload() { if (active()) return open(entry, options); }
  function expand() { return open(entry, { depth: Math.min(30, options.depth + 3), limit: Math.min(500, options.limit + 60) }); }

  // ===== 私有方法 =====

  function nodeName(node) { return node?.label || node?.method || node?.id || ''; }
  function source(node) {
    if (!node?.anchor) return;
    if (node.module && node.kind === 'method') void showSource(node.module, node.anchor.startLine);
    else void showAnchor(node.anchor);
  }
  function select(id) {
    selected = id;
    for (const node of $('#map-world').querySelectorAll('.chain-node')) node.classList.toggle('current', node.dataset.methodId === id);
    host.refreshDetail();
  }
  function controls() {
    const box = $('#entry-chain-tools'); box.hidden = !active(); box.replaceChildren();
    if (!active()) return;
    const nav = $('#nav-float');
    box.style.paddingLeft = `${nav.hidden ? 14 : Math.max(14, nav.getBoundingClientRect().right - box.getBoundingClientRect().left + 12)}px`;
    box.append(button('返回包视图', 'button small', () => close()));
    if (loading) { box.append(element('span', 'chain-status', '正在解析方法调用…')); return; }
    if (error) { box.append(button('重试', 'button small', () => void reload())); return; }
    box.append(element('span', 'chain-status', `${data.nodes.length} 个方法/出口 · ${data.summary.uncertain} 条不确定边`));
    if (data.remaining) {
      const more = button(`还有 ${data.remaining} 个 · 继续展开`, 'button small', () => void expand());
      more.disabled = options.depth >= 30 && options.limit >= 500;
      if (more.disabled) more.title = '已达到 30 层、500 节点上限，请从具体入口或方法继续阅读源码';
      box.append(more);
    }
    const save = button('另存为链路', 'button small', saveForm); save.id = 'entry-chain-save';
    box.append(save, element('span', 'chain-legend', '实线：已解析调用　虚线：多实现 / 异步 / 推断'));
  }
  function layout() {
    const lanes = data.lanes.map(lane => ({ ...lane, items: data.nodes.filter(node => lane.nodes.includes(node.id)) }));
    const depths = new Map();
    for (const lane of lanes) {
      const counts = new Map();
      for (const node of lane.items) counts.set(node.depth, (counts.get(node.depth) || 0) + 1);
      for (const [depth, count] of counts) depths.set(depth, Math.max(depths.get(depth) || 0, count));
    }
    const rows = new Map(); let height = TOP;
    for (const [depth, count] of [...depths].sort((a, b) => a[0] - b[0])) {
      rows.set(depth, height); height += count * (NODE_HEIGHT + 18) + 50;
    }
    const nav = $('#nav-float'), viewport = $('#map-viewport');
    const left = nav.hidden ? 28 : Math.max(28, nav.getBoundingClientRect().right - viewport.getBoundingClientRect().left + 28);
    const positions = new Map();
    lanes.forEach((lane, index) => {
      const counts = new Map(); lane.x = left + index * (LANE_WIDTH + LANE_GAP);
      for (const node of lane.items) {
        const slot = counts.get(node.depth) || 0; counts.set(node.depth, slot + 1);
        positions.set(node.id, { x: lane.x + 14, y: rows.get(node.depth) + slot * (NODE_HEIGHT + 18) });
      }
    });
    return { lanes, positions, width: Math.max(360, lanes.length * (LANE_WIDTH + LANE_GAP) + left), height: Math.max(300, height + 30) };
  }
  function route(from, to, index) {
    const x1 = from.x + NODE_WIDTH / 2, x2 = to.x + NODE_WIDTH / 2;
    if (from === to) {
      const x = from.x + NODE_WIDTH + 12;
      return { d: `M ${from.x + NODE_WIDTH} ${from.y + 52} H ${x} V ${from.y - 12} H ${x1} V ${from.y}`, x: x, y: from.y - 16 };
    }
    const y1 = from.y + NODE_HEIGHT, y2 = to.y;
    const mid = y2 > y1 ? y1 + Math.max(18, (y2 - y1) / 2) + (index % 3) * 5 : Math.min(from.y, to.y) - 22;
    return { d: `M ${x1} ${y1} V ${mid} H ${x2} V ${y2}`, x: (x1 + x2) / 2, y: mid - 5 };
  }
  function drawEdges(world, diagram) {
    const svg = svgElement('svg', { class: 'chain-edges', width: diagram.width, height: diagram.height, 'aria-label': '方法调用关系' });
    const defs = svgElement('defs');
    const marker = svgElement('marker', { id: 'entry-chain-arrow', markerWidth: 8, markerHeight: 8, refX: 7, refY: 4, orient: 'auto', markerUnits: 'userSpaceOnUse' });
    marker.append(svgElement('path', { d: 'M0 0 L8 4 L0 8 Z', fill: '#648777' })); defs.append(marker); svg.append(defs);
    data.edges.forEach((edge, index) => {
      const from = diagram.positions.get(edge.from), to = diagram.positions.get(edge.to);
      if (!from || !to) return;
      const line = route(from, to, index);
      const path = svgElement('path', { d: line.d, class: `chain-edge ${edge.style}`, 'marker-end': 'url(#entry-chain-arrow)' });
      const title = svgElement('title'); title.textContent = [edge.call, edge.reason, edge.anchor && `${edge.anchor.path}:${edge.anchor.startLine}`].filter(Boolean).join('\n');
      path.append(title);
      path.addEventListener('click', () => { if (edge.anchor) void showAnchor(edge.anchor); });
      svg.append(path);
      if (edge.reason) {
        const text = svgElement('text', { x: line.x, y: line.y, class: 'chain-edge-label', 'text-anchor': 'middle' });
        text.textContent = edge.reason.length > 20 ? edge.reason.slice(0, 20) + '…' : edge.reason;
        const title = svgElement('title'); title.textContent = edge.reason; text.append(title); svg.append(text);
      }
    });
    world.append(svg);
  }
  function render(world) {
    controls(); world.replaceChildren(); world.classList.remove('flow-world'); world.classList.add('entry-chain-world');
    if (!data) {
      state.world = { width: 600, height: 300 };
      world.append(element('p', 'map-empty', error ? `调用链读取失败：${error}` : '正在解析入口方法与项目内调用…'));
      host.applyZoom(); return;
    }
    const diagram = layout(); state.world = diagram; state.positions = diagram.positions;
    world.style.width = `${diagram.width}px`; world.style.height = `${diagram.height}px`;
    for (const lane of diagram.lanes) {
      const background = element('section', 'chain-lane'); background.style.left = `${lane.x}px`;
      background.style.width = `${LANE_WIDTH}px`; background.style.height = `${diagram.height - 24}px`;
      const heading = element('h3', '', lane.label); heading.title = lane.id; background.append(heading); world.append(background);
    }
    drawEdges(world, diagram);
    for (const node of data.nodes) {
      const position = diagram.positions.get(node.id);
      const card = button('', `chain-node ${node.kind}${node.id === selected ? ' current' : ''}${node.id === data.root ? ' root' : ''}`, () => select(node.id));
      card.dataset.methodId = node.id; card.style.left = `${position.x}px`; card.style.top = `${position.y}px`;
      card.style.width = `${NODE_WIDTH}px`; card.style.height = `${NODE_HEIGHT}px`;
      card.append(element('small', 'chain-node-kind', node.kind === 'exit' ? `出口 · ${node.exitKind}` : node.kind === 'unresolved' ? '目标未解析' : node.id === data.root ? '入口起点' : '方法'),
        element('strong', '', nodeName(node)), element('span', '', node.summary || (node.kind === 'method' ? `(${(node.params || []).join(', ')})` : node.anchor?.path || '')));
      card.title = `${nodeName(node)}\n${node.summary || ''}\n${node.anchor?.path}:${node.anchor?.startLine}\n单击看详情 · 双击打开源码`;
      card.addEventListener('dblclick', () => source(node)); world.append(card);
    }
    host.applyZoom();
  }
  function renderDetail(panel) {
    panel.replaceChildren(element('div', 'eyebrow', '入口调用链'), element('h3', '', entry.label));
    if (loading || error || !data) { panel.append(element('p', '', error || '正在解析…')); return; }
    panel.append(element('p', 'detail-provenance', '按方法的静态调用展开；泳道与步骤排列不表示运行时顺序。'));
    const node = data.nodes.find(node => node.id === selected) || data.nodes[0];
    panel.append(element('h4', '', nodeName(node)), element('p', 'detail-id', node.type));
    if (node.summary) panel.append(element('p', '', node.summary));
    if (node.abstract) panel.append(element('p', 'entry-warning', '没有找到该方法的项目内具体实现。'));
    if (node.warnings?.length) panel.append(element('p', 'entry-warning', `警告：${node.warnings.join('、')}；属性保留源码表达式。`));
    if (node.rule) panel.append(element('p', 'detail-provenance', `出口规则：${node.rule}`));
    panel.append(button('查看源码', 'button small', () => source(node)));
    const attrs = element('dl', 'entry-attrs');
    for (const [key, value] of Object.entries(node.attrs || {})) attrs.append(element('dt', '', key), element('dd', '', String(value)));
    panel.append(attrs);
    for (const source of node.sources || []) if (source.anchor) {
      panel.append(button(`声明：${source.anchor.path}:${source.anchor.startLine}`, 'text-button', () => void showAnchor(source.anchor)));
    }
    const edges = data.edges.filter(edge => edge.from === node.id || edge.to === node.id);
    const section = element('section', 'detail-section'); section.append(element('h4', '', '相关调用'));
    for (const edge of edges) {
      const from = data.nodes.find(node => node.id === edge.from), to = data.nodes.find(node => node.id === edge.to);
      const item = element('div', 'chain-call');
      item.append(button(`${nodeName(from)} → ${nodeName(to)}`, 'text-button', () => select(edge.to === node.id ? edge.from : edge.to)));
      if (edge.reason) item.append(element('p', 'entry-warning', edge.reason));
      if (edge.anchor) item.append(button(`${edge.anchor.path}:${edge.anchor.startLine}`, 'text-button', () => void showAnchor(edge.anchor)));
      section.append(item);
    }
    if (!edges.length) section.append(element('p', '', '没有可继续展开的项目内方法调用。'));
    panel.append(section);
    if (data.summary.unresolvedCalls) {
      const details = element('details', 'detail-section'); details.append(element('summary', '', `${data.summary.unresolvedCalls} 个外部或未解析调用`));
      for (const call of data.unresolved) details.append(button(`${call.written} · ${call.anchor?.startLine}`, 'text-button', () => void showAnchor(call.anchor)));
      panel.append(details);
    }
  }
  function saveForm() {
    if (!data) return;
    const snapshot = data;
    openDrawer('另存为链路', '', '入口调用图快照');
    const form = element('form', 'chain-save-form');
    const name = element('input'); name.required = true; name.pattern = '[a-z0-9][a-z0-9-]{0,63}'; name.maxLength = 64;
    name.value = `entry-${Date.now().toString(36)}`;
    const title = element('input'); title.required = true; title.value = `入口链路 · ${snapshot.entry.label}`;
    const idLabel = element('label', '', '链路 id（小写字母、数字、连字符）'); idLabel.append(name);
    const titleLabel = element('label', '', '标题'); titleLabel.append(title);
    const note = element('p', '', `保存当前 ${snapshot.nodes.length} 个方法/出口、${snapshot.edges.length} 条边，保留分叉与虚线原因。${snapshot.remaining ? `还有 ${snapshot.remaining} 个节点未展开。` : ''}`);
    const status = element('p', 'entry-warning'); status.setAttribute('role', 'status');
    const submit = button('保存到项目', 'button primary', () => {}); submit.type = 'submit';
    form.append(idLabel, titleLabel, note, status, submit); $('#drawer-content').append(form);
    form.addEventListener('submit', async event => {
      event.preventDefault(); submit.disabled = true; status.textContent = '正在保存与校验…';
      try {
        const result = await api('/api/entry/chain/save', {}, 'POST', {
          id: name.value, title: title.value, entryId: snapshot.entry.id, depth: snapshot.depth, limit: snapshot.limit,
        });
        form.replaceChildren(element('p', '', `已保存 .arch-view/traces/${result.trace.id}.json`),
          element('p', '', result.verdict === 'ok' ? '锚点与静态依赖校验通过。' : '锚点已保存；不确定边与缺少静态依赖证据的跳转仍需复核。'),
          button('查看已保存链路', 'button small', () => void host.openSaved(result.trace.id)));
        void host.loadTraces(); toast('调用链已保存');
      } catch (failure) { status.textContent = failure.message; submit.disabled = false; }
    });
    name.focus();
  }

  return { open, close, reload, active, modules, render, renderDetail, controls };
}
