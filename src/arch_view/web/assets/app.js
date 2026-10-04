/*
 * 职责：控制网页架构地图、逐层浏览、缩放拖动、页面内全屏，以及文档和源码面板。
 * 核心入口：页面初始化流程（initialize），页面打开后自动读取项目数据。
 */

// 页面脚本作为独立模块加载，内部函数和状态不向外部公开。
'use strict';
import { parseArchitectureDocument, renderDataFlow, uncoveredModules } from './architecture_document.js';
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const CARD = { width: 240, height: 144, stride: 272, row: 178 };
const state = { project: null, view: null, rootView: null, path: [], selected: null, document: null,
  sections: [], roles: new Map(), flowRoles: new Map(), zoom: 1, world: { width: 800, height: 400 }, mode: 'overview',
  request: 0, documentRequest: 0, sourceRequest: 0, cyclesOnly: false, positions: new Map(),
  flows: [], flowIndex: 0, diagramKind: 'code', mapRequest: 0, flowNodes: [], selectedFlow: null, documentBase: [],
  coverageDocument: null, uncovered: new Set(), pendingFlowSelection: null, hovered: null,
  pan: { x: 0, y: 0 }, fullscreenCamera: null, restoreCamera: null,
  navigationView: null, navigationPath: [] };

// ===== 私有方法 =====

function element(tag, className, text) {
  const el = document.createElement(tag);
  if (className) el.className = className;
  if (text != null) el.textContent = text;
  return el;
}
function button(text, className, action) {
  const decorated = text.match(/^(.*?)\s*([↗↘↻])$/);
  const el = element('button', className, decorated ? null : text);
  if (decorated) el.append(element('span', '', decorated[1]), icon(decorated[2] === '↗' ? 'external' : decorated[2] === '↻' ? 'refresh' : 'arrow'));
  el.type = 'button'; el.addEventListener('click', action); return el;
}
function icon(name, className = '') {
  const svg = svgElement('svg', { viewBox: '0 0 24 24', class: `icon ${className}`, 'aria-hidden': 'true', focusable: 'false' });
  svg.append(svgElement('use', { href: `#icon-${name}` })); return svg;
}
async function api(path, params = {}, method = 'GET') {
  const query = new URLSearchParams(params);
  const response = await fetch(`${path}${query.size ? '?' + query : ''}`, { method });
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `请求失败 (${response.status})`);
  return data;
}
let toastTimer;
function toast(message) {
  $('#toast').textContent = message; $('#toast').hidden = false;
  clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('#toast').hidden = true; }, 5500);
}
function plain(markdown) {
  const container = document.createElement('div');
  container.innerHTML = DOMPurify.sanitize(marked.parse(markdown || ''), { USE_PROFILES: { html: true } });
  return container.textContent.replace(/\s+/g, ' ').trim();
}
function roleFor(node, path = state.path) {
  return state.roles.get([...path, node.id.replace(/\|file$/, '')].join('/'));
}
function flowRoleFor(id) {
  if (state.flowRoles.has(id)) return state.flowRoles.get(id);
  const moduleKey = [...state.documentBase, id].join('/');
  return { moduleKey, role: state.roles.get(moduleKey) };
}
function descriptionFor(node) {
  const role = roleFor(node);
  return role?.summary || node.description || '';
}
function titleFor(node) {
  return roleFor(node)?.label || node.label;
}
function updateProject() {
  const p = state.project;
  document.title = `${p.name} · Arch View`;
  $('#project-name').textContent = p.name;
  $('#project-language').textContent = `${String(p.language).toUpperCase()} / ${(p.sourcePaths || []).join(' · ')}`;
  $('#project-path').textContent = p.root; $('#project-path').title = p.root;
  $('#metric-modules').textContent = p.moduleCount;
  $('#metric-edges').textContent = p.dependencyCount;
  $('#reanalyze').hidden = !p.canReanalyze;
  renderDocumentSelect();
}
function renderDocumentSelect() {
  const select = $('#document-select'); select.replaceChildren();
  const empty = element('option', '', '不使用说明文档'); empty.value = ''; select.append(empty);
  for (const doc of state.project.documents) {
    const option = element('option', '', doc.path); option.value = doc.id; select.append(option);
  }
  select.value = state.document?.id || '';
}
async function loadDocument(id, packagePath = state.document?.id === id ? state.documentBase : state.path) {
  const request = ++state.documentRequest;
  const doc = id ? await api('/api/document', { id }) : null;
  if (request !== state.documentRequest) return;
  state.document = doc;
  const parsed = parseArchitectureDocument(doc?.content || '');
  state.sections = parsed.sections;
  const metadata = state.project.documents.find(d => d.id === id);
  if (!id) state.coverageDocument = null;
  else if (metadata?.scope === 'project') state.coverageDocument = { path: doc.path, roles: parsed.roles };
  state.documentBase = metadata?.scope === 'package' ? [...packagePath] : [];
  state.roles.clear(); state.flowRoles.clear();
  for (const role of parsed.roles) {
    const moduleKey = [...state.documentBase, role.module].join('/');
    state.roles.set(moduleKey, role);
    // 重复的流程标识无法唯一定位，不能任选一个代码包。
    state.flowRoles.set(role.id, state.flowRoles.has(role.id) ? null : { moduleKey, role });
  }
  state.flows = parsed.flows; state.flowIndex = 0; state.selectedFlow = null;
  if (!state.flows.length) state.diagramKind = 'code';
  updateOverview(); renderDocumentSelect();
  renderCoverage();
  if (state.view) { renderMap(); renderDetail(); renderRootNav(); }
}
function renderCoverage() {
  const panel = $('#architecture-coverage'); panel.replaceChildren();
  state.uncovered.clear();
  const doc = state.coverageDocument;
  panel.classList.toggle('coverage-warning', Boolean(doc?.roles.length));
  panel.hidden = !doc || !state.rootView;
  if (panel.hidden) return;
  if (!doc.roles.length) {
    panel.append(element('p', '', `${doc.path} 尚未声明可识别的子系统，暂不检查源码归属。`)); return;
  }
  const modules = [...new Set(state.rootView.nodes.flatMap(node => node.members))].sort();
  state.uncovered = new Set(uncoveredModules(modules, doc.roles, state.project.namespaceRootDepth));
  panel.hidden = !state.uncovered.size;
  if (panel.hidden) return;
  const details = element('details');
  details.open = true;
  details.append(element('summary', '', `${state.uncovered.size} 个源码模块未在架构文档里提到`));
  details.append(element('p', '', `这些模块未归入 ${doc.path} 声明的任何子系统，暂不画进架构图。请补充文档或调整代码归属；点击下面的模块可以查看源码。`));
  const list = element('div', 'coverage-modules');
  for (const module of state.uncovered) list.append(button(module + ' ↗', '', () => showSource(module)));
  details.append(list); panel.append(details);
}
function visibleNodes(view) {
  return view.nodes.filter(node => node.members.some(module => !state.uncovered.has(module)));
}
function updateOverview() {
  const doc = state.document;
  const tokens = doc ? marked.lexer(doc.content) : [];
  const heading = tokens.find(t => t.type === 'heading' && t.depth === 1);
  const intro = tokens.find(t => t.type === 'paragraph' && plain(t.text));
  $('#hero-title').textContent = heading ? plain(heading.text) : state.project.name;
  $('#hero-summary').textContent = intro ? plain(intro.text) :
    `${state.project.name} 包含 ${state.project.moduleCount} 个源码模块。下方地图展示模块边界与实际代码依赖，点击模块可继续查看内部结构。`;
  $('#document-provenance').textContent = doc ? `说明来源：${doc.path} · 结构来源：源码静态分析` : '结构来自源码 · 未选择说明文档';
  $('#document-open').disabled = !doc;
  const notes = $('#design-notes'); notes.replaceChildren();
  const sections = [...state.roles.values()].length ? [...state.roles.values()].slice(0, 6) : state.sections.filter(s => s.summary && s.depth === 2).slice(0, 6);
  if (!sections.length) {
    notes.append(element('div', 'no-doc-note', doc ? '说明文档已加载，可在侧栏阅读完整内容。' : '可选择已有项目说明，也可直接探索架构；不要求每个包编写文档。')); return;
  }
  const headingRow = element('div', 'notes-heading'); headingRow.append(element('h2', '', '架构说明'), element('span', '', doc.path)); notes.append(headingRow);
  const grid = element('div', 'note-grid');
  sections.forEach((section, index) => {
    const card = element('article', 'note-card'); card.append(element('span', 'note-number', `${String(index + 1).padStart(2, '0')} /`), element('h3', '', section.label || section.title), statusBadge(section.status), element('p', '', section.summary));
    const open = button('↗', '', () => showDocument(section.title)); open.setAttribute('aria-label', `阅读${section.title}`); card.append(open); grid.append(card);
  }); notes.append(grid);
}
function setMode(mode) {
  state.mode = mode; document.body.classList.toggle('exploring', mode === 'explore');
  $('#overview-nav').classList.toggle('active', mode === 'overview');
  $('#explore-nav').classList.toggle('active', mode === 'explore');
}
async function expandSinglePackages(view, path, request) {
  // 连续的单子包没有需要选择的分支，直接展开；源码文件保留在图中。
  while (true) {
    const nodes = visibleNodes(view);
    if (nodes.length !== 1 || nodes[0].leaf) break;
    const nextPath = [...path, nodes[0].id.replace(/\|file$/, '')];
    const nextView = await api('/api/view', { path: nextPath.join('/') });
    if (request !== state.request) return null;
    if (!nextView.nodes.length) break;
    path = nextPath; view = nextView;
  }
  return { view, path };
}
async function navigate(path, mode = 'explore', selectedId = null, { expand = true } = {}) {
  const request = ++state.request;
  const fromOverview = !path.length && mode === 'overview';
  $('#analysis-state').textContent = '正在读取结构…';
  try {
    let view = await api('/api/view', { path: path.join('/') });
    if (request !== state.request) return;
    const atRoot = !path.length;
    if (atRoot) { state.rootView = view; state.navigationView = null; renderCoverage(); }
    if (!state.navigationView) {
      const navigation = await expandSinglePackages(state.rootView, [], request);
      if (!navigation || request !== state.request) return;
      state.navigationView = navigation.view; state.navigationPath = navigation.path;
    }
    // 明确定位源码时保留目标所在层，避免自动展开把选中节点跳过去。
    if (!selectedId && expand) {
      const expanded = atRoot ? { view: state.navigationView, path: state.navigationPath }
        : await expandSinglePackages(view, path, request);
      if (!expanded || request !== state.request) return;
      view = expanded.view; path = expanded.path;
    }
    state.view = view; state.path = path; state.selected = null; state.hovered = null; state.cyclesOnly = false;
    state.selectedFlow = null;
    state.diagramKind = fromOverview && state.flows.length ? 'flow' : 'code';
    setMode(mode); $('#search').value = ''; $('#cycle-filter').classList.remove('active');
    $('#map-title').textContent = path.length ? `${path.at(-1)} · 内部架构` : '全局架构地图';
    $('#metric-groups').textContent = view.nodes.length;
    $('#metric-cycles').textContent = view.cycles.length;
    $('#cycle-filter').classList.toggle('has-cycles', view.cycles.length > 0);
    renderCoverage(); renderBreadcrumbs(); renderRootNav(); renderMap(); renderDetail(); renderCycles(); fitMap();
    $('#analysis-state').textContent = state.diagramKind === 'flow' ? '文档数据流' : '源码静态依赖';
    const local = path.map((_, index) => path.slice(0, index + 1)).reverse()
      .map(scope => ({ path: scope, doc: view.documents.find(doc => doc.directory.endsWith(scope.join('/').replace(/-/g, '_'))) }))
      .find(match => match.doc);
    if (local && !(fromOverview && state.project.documents.find(doc => doc.id === state.document?.id)?.scope === 'project')) {
      await loadDocument(local.doc.id, local.path);
    }
    else if (state.document && state.project.documents.find(d => d.id === state.document.id)?.scope === 'package') {
      const projectDoc = state.project.documents.find(d => d.scope === 'project'); await loadDocument(projectDoc?.id || '');
    }
    if (request !== state.request) return false;
    if (selectedId) { selectNode(selectedId); renderRootNav(); focusSelectedNode(); }
    return true;
  } catch (error) { if (request === state.request) { $('#analysis-state').textContent = '读取失败'; toast(error.message); } }
}
function renderBreadcrumbs() {
  const nav = $('#breadcrumbs'); nav.replaceChildren(button('项目总览', '', () => navigate([], 'overview')));
  state.path.forEach((part, index) => { nav.append(element('i', '', '/'), button(part, '', () => navigate(state.path.slice(0, index + 1), 'explore', null, { expand: false }))); });
}
function navigateUp() {
  if ($('#map-back').disabled) return;
  // 返回时保留父层级，避免单子包自动展开又进入刚离开的层级。
  navigate(state.path.slice(0, -1), 'explore', null, { expand: false });
}
function navRoleFor(node, path) {
  // 左侧是代码结构导航，固定使用项目级文档的子系统名，进入带包级文档的目录时不随之改变。
  const key = [...path, node.id.replace(/\|file$/, '')].join('/');
  return state.coverageDocument?.roles.find(role => role.module === key);
}
function renderRootNav() {
  if (state.diagramKind === 'flow' && state.flowNodes.length) { renderFlowNav(); return; }
  const view = state.navigationView || state.rootView, path = state.navigationPath;
  if (!view) return;
  $('#nav-title').textContent = '模块导航';
  $('#root-count').textContent = String(visibleNodes(view).length).padStart(2, '0');
  const nav = $('#module-nav'); nav.replaceChildren();
  for (const node of visibleNodes(view)) {
    const link = button('', 'module-link', () => node.leaf ? navigate(path, 'explore', node.id) : navigate([...path, node.id.replace(/\|file$/, '')]));
    const copy = element('span', 'module-copy'); copy.append(element('span', 'module-name', navRoleFor(node, path)?.label || node.label), element('small', '', node.label));
    link.append(icon(node.leaf ? 'file' : 'folder'), copy, element('span', 'module-count', node.moduleCount));
    link.title = navRoleFor(node, path)?.label || node.fullName;
    link.classList.toggle('current', path.every((part, index) => state.path[index] === part) &&
      (state.path[path.length] === node.id || (state.path.length === path.length && state.selected?.id === node.id))); nav.append(link);
  }
}
function renderFlowNav() {
  // 数据流视图下导航列出图中节点，点击即在图上选中，不切回代码结构。
  $('#nav-title').textContent = '数据流节点';
  $('#root-count').textContent = String(state.flowNodes.length).padStart(2, '0');
  const nav = $('#module-nav'); nav.replaceChildren();
  for (const node of state.flowNodes) {
    const label = node.role?.label || node.label;
    const link = button('', 'module-link', () => { selectFlowNode(node); scrollIntoMap(node.element); });
    link.dataset.flow = node.id; link.title = node.role?.summary || label;
    const copy = element('span', 'module-copy');
    copy.append(element('span', 'module-name', label), element('small', '', `${node.id}${node.role?.status ? ' · ' + node.role.status : ''}`));
    link.append(icon('flow'), copy);
    link.classList.toggle('current', node.id === state.selectedFlow?.id); nav.append(link);
  }
}
function scrollIntoMap(target) {
  const viewport = $('#map-viewport'), box = target.getBoundingClientRect(), view = viewport.getBoundingClientRect();
  if (box.left >= view.left && box.right <= view.right && box.top >= view.top && box.bottom <= view.bottom) return;
  viewport.scrollBy({ left: box.left + box.width / 2 - (view.left + view.width / 2), top: box.top + box.height / 2 - (view.top + view.height / 2) });
}
function positionsFor(nodes) {
  // 根视图和内部视图都按实际依赖层排布，左右留出跨层连线通道。
  const groups = new Map();
  nodes.forEach(node => { if (!groups.has(node.layer)) groups.set(node.layer, []); groups.get(node.layer).push(node); });
  const columns = Math.max(1, Math.min(canvasColumns(), Math.max(0, ...[...groups.values()].map(g => g.length))));
  const width = columns * CARD.stride + 80; const positions = new Map(); let y = 50;
  [...groups.entries()].sort((a, b) => a[0] - b[0]).forEach(([layer, group]) => {
    const rows = Math.ceil(group.length / columns);
    group.forEach((node, index) => {
      const row = Math.floor(index / columns); const rowCount = Math.min(columns, group.length - row * columns);
      const x = (width - (rowCount * CARD.stride - 32)) / 2 + (index % columns) * CARD.stride;
      positions.set(node.id, { x, y: y + row * CARD.row, layer });
    }); y += rows * CARD.row + 48;
  }); return { positions, width, height: Math.max(300, y - 35) };
}
function roundedRoute(points) {
  let d = `M${points[0][0]},${points[0][1]}`;
  for (let index = 1; index < points.length - 1; index++) {
    const previous = points[index - 1], corner = points[index], next = points[index + 1];
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
  return d + ` L${points.at(-1)[0]},${points.at(-1)[1]}`;
}
function routeEdge(from, to, width, index) {
  const sx = from.x + CARD.width / 2, tx = to.x + CARD.width / 2, lane = (index % 4) * 4;
  if (from.y === to.y) {
    const right = to.x > from.x;
    if (Math.abs(to.x - from.x) <= CARD.stride) {
      const start = from.x + (right ? CARD.width : 0), end = to.x + (right ? -3 : CARD.width + 3), y = from.y + CARD.height / 2;
      return { d: `M${start},${y} L${end},${y}`, x: (start + end) / 2, y: y - 8 };
    }
    const y = from.y - 16 - lane;
    return { d: roundedRoute([[sx, from.y], [sx, y], [tx, y], [tx, to.y - 3]]), x: (sx + tx) / 2, y: y - 5 };
  }
  const down = to.y > from.y, sy = from.y + (down ? CARD.height : 0), ty = to.y + (down ? -3 : CARD.height + 3);
  const exit = sy + (down ? 14 + lane : -14 - lane), entry = ty + (down ? -14 - lane : 14 + lane);
  if (Math.abs(to.y - from.y) <= CARD.row + 48) {
    const middle = (sy + ty) / 2;
    return { d: roundedRoute([[sx, sy], [sx, middle], [tx, middle], [tx, ty]]), x: (sx + tx) / 2 + 6, y: middle - 5 };
  }
  const gutter = (sx + tx) / 2 < width / 2 ? 14 + lane : width - 14 - lane;
  return { d: roundedRoute([[sx, sy], [sx, exit], [gutter, exit], [gutter, entry], [tx, entry], [tx, ty]]), x: gutter + 6, y: (exit + entry) / 2 };
}
function canvasColumns() {
  const width = $('#map-viewport').clientWidth;
  return width < 480 ? 1 : width < 650 ? 2 : 3;
}
function svgElement(tag, attributes = {}) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attributes)) el.setAttribute(key, String(value)); return el;
}
function renderMap() {
  if (!state.view) return;
  const request = ++state.mapRequest;
  renderDiagramControls();
  if (state.diagramKind === 'flow') { renderFlowMap(request); return; }
  const world = $('#map-world'); world.replaceChildren();
  world.classList.remove('flow-world');
  const nodes = visibleNodes(state.view);
  $('#metric-groups').textContent = nodes.length;
  const layout = positionsFor(nodes); state.world = layout; state.positions = layout.positions;
  world.style.width = `${layout.width}px`; world.style.height = `${layout.height}px`;
  if (!nodes.length) { world.append(element('p', 'map-empty', state.view.nodes.length ? '当前范围的源码未在架构文档里提到，请先补充文档。' : '当前范围没有源码模块。可返回上层，或检查启动时指定的源码目录。')); return; }
  const svg = svgElement('svg', { width: layout.width, height: layout.height, 'aria-hidden': 'true' });
  const defs = svgElement('defs');
  for (const [id, color] of [['arrow', '#a7b99b'], ['arrow-active', '#26664e'], ['arrow-incoming', '#c36a3e']]) {
    const marker = svgElement('marker', { id, markerWidth: 8, markerHeight: 8, refX: 7, refY: 4, orient: 'auto', markerUnits: 'userSpaceOnUse' });
    marker.append(svgElement('path', { d: 'M 0 0 L 8 4 L 0 8 Z', fill: color })); defs.append(marker);
  } svg.append(defs);
  let edgeIndex = 0;
  for (const edge of state.view.edges) {
    const from = layout.positions.get(edge.from), to = layout.positions.get(edge.to); if (!from || !to) continue;
    const route = routeEdge(from, to, layout.width, edgeIndex++);
    const curve = svgElement('path', { d: route.d, class: 'edge', 'marker-end': 'url(#arrow)' });
    curve.dataset.from = edge.from; curve.dataset.to = edge.to; svg.append(curve);
    if (edge.count > 1) { const text = svgElement('text', { x: route.x, y: route.y, class: 'edge-label' }); text.textContent = `${edge.count}`; text.dataset.from = edge.from; text.dataset.to = edge.to; svg.append(text); }
  } world.append(svg);
  const layers = new Set();
  for (const node of nodes) {
    const position = layout.positions.get(node.id);
    if (!layers.has(node.layer)) { layers.add(node.layer); const caption = element('span', 'layer-caption', `依赖层 ${node.layer + 1}`); caption.style.top = `${position.y - 24}px`; world.append(caption); }
    const card = button('', `node${node.abstract ? ' abstract' : ''}${node.cycle ? ' cyclic' : ''}`, () => { selectNode(node.id); focusSelectedNode(); });
    card.style.left = `${position.x}px`; card.style.top = `${position.y}px`; card.style.width = `${CARD.width}px`; card.style.height = `${CARD.height}px`; card.dataset.node = node.id;
    card.setAttribute('aria-label', `${titleFor(node)}，${node.moduleCount} 个源码模块${node.cycle ? '，含循环依赖' : ''}`);
    card.addEventListener('dblclick', () => enterNode(node));
    for (const event of ['pointerenter', 'focus']) card.addEventListener(event, () => { state.hovered = node.id; applyHighlight(); });
    for (const event of ['pointerleave', 'blur']) card.addEventListener(event, () => { if (state.hovered === node.id) { state.hovered = null; applyHighlight(); } });
    const heading = element('div', 'node-heading'); heading.append(icon(node.leaf ? 'file' : 'folder', 'node-symbol'), element('span', 'node-name', titleFor(node)));
    card.append(heading, element('p', 'node-description', descriptionFor(node) || (node.leaf ? '源码文件 · 点击查看依赖与说明' : `${node.moduleCount} 个源码模块 · 双击查看内部结构`)));
    const bottom = element('div', 'node-bottom'); bottom.append(element('span', '', `${node.label} / ${node.moduleCount}`));
    if (roleFor(node)) bottom.append(statusBadge(roleFor(node).status));
    bottom.append(icon(node.leaf ? 'external' : 'arrow', 'node-arrow')); card.append(bottom); world.append(card);
  } applyHighlight();
}

function statusBadge(status) {
  if (!status) return document.createDocumentFragment();
  const kind = status === '已完成' ? 'complete' : status === '未完成' ? 'pending' : 'other';
  return element('span', `implementation-status ${kind}`, status);
}

function statusChoices(role) {
  const field = element('fieldset', 'status-choices'); field.append(element('legend', '', '实现状态'));
  for (const status of ['已完成', '未完成', '进行中']) {
    const label = element('label'); const input = element('input');
    input.type = 'radio'; input.name = 'subsystem-status'; input.value = status; input.checked = role.status === status;
    input.addEventListener('change', async () => {
      const docId = state.document.id;
      for (const radio of $$('input', field)) radio.disabled = true;
      try {
        await api('/api/subsystem-status', { id: docId, heading: role.heading, status }, 'POST');
        state.pendingFlowSelection = state.selectedFlow?.id || null;
        await loadDocument(docId); toast('实现状态已保存到架构文档');
      } catch (error) {
        for (const radio of $$('input', field)) { radio.disabled = false; radio.checked = role.status === radio.value; }
        toast(error.message);
      }
    });
    label.append(input, document.createTextNode(status)); field.append(label);
  }
  return field;
}

function renderDiagramControls() {
  const flow = state.diagramKind === 'flow';
  $('#map-back').disabled = flow || state.path.length <= state.navigationPath.length;
  $('#edge-scope-label').hidden = flow;
  $('#analysis-state').textContent = flow ? '文档数据流' : '源码静态依赖';
  $('#flow-view').disabled = !state.flows.length;
  for (const [id, active] of [['flow-view', flow], ['code-view', !flow]]) {
    $('#' + id).classList.toggle('active', active); $('#' + id).setAttribute('aria-pressed', String(active));
  }
  $('#map-provenance').textContent = flow ? `数据流来自 ${state.document.path} · 状态由文档手动标记` : '连线来自源码分析 · 状态由文档手动标记';
  $('#map-title').textContent = flow ? state.flows[state.flowIndex].title : state.path.length ? `${state.path.at(-1)} · 内部架构` : '全局架构地图';
  $('#cycle-filter').disabled = flow;
  $('.map-footer > span').textContent = flow ? '拖动空白处移动画布 · 单击看详情 · 点击画布外恢复全局 · Ctrl + 滚轮缩放' : '单击看详情 · 双击进入 · 点击画布外恢复全局 · Ctrl + 滚轮缩放';
  $('#cycles-panel').hidden = flow || !state.cyclesOnly;
  $('.map-footer .legend').hidden = flow;
  $('.flow-legend').hidden = !flow;
  const select = $('#flow-select'); select.replaceChildren(); select.hidden = !flow || state.flows.length < 2;
  state.flows.forEach((item, index) => { const option = element('option', '', item.title); option.value = index; select.append(option); });
  select.value = state.flowIndex;
}

async function renderFlowMap(request) {
  const world = $('#map-world'); world.replaceChildren(element('p', 'map-empty', '正在绘制文档数据流…'));
  world.classList.add('flow-world'); state.flowNodes = [];
  try {
    const result = await renderDataFlow(state.flows[state.flowIndex].source);
    if (request !== state.mapRequest) return;
    const svg = result.diagram;
    const modules = state.rootView?.nodes.flatMap(node => node.members) || [];
    const hidden = result.nodes.filter(node => {
      const key = flowRoleFor(node.id)?.moduleKey || [...state.documentBase, node.id].join('/');
      const members = modules.filter(module => {
        const path = module.split('.').slice(state.project.namespaceRootDepth).join('/');
        return path === key || path.startsWith(key + '/');
      });
      return members.length && members.every(module => state.uncovered.has(module));
    });
    for (const node of hidden) node.element.remove();
    const hiddenEdges = hidden.flatMap(node => result.nodes.flatMap(other => [`L_${node.id}_${other.id}_`, `L_${other.id}_${node.id}_`]));
    for (const edge of $$('g.edgePaths path', svg)) {
      if (hiddenEdges.some(prefix => edge.id.includes('-' + prefix))) edge.remove();
    }
    for (const label of $$('g.edgeLabel', svg)) {
      const id = label.querySelector('[data-id]')?.getAttribute('data-id') || '';
      if (hiddenEdges.some(prefix => id.startsWith(prefix))) label.remove();
    }
    const bounds = svg.viewBox.baseVal;
    state.world = { width: Math.max(300, bounds.width), height: Math.max(200, bounds.height) };
    world.style.width = `${state.world.width}px`; world.style.height = `${state.world.height}px`;
    svg.style.maxWidth = 'none'; svg.setAttribute('width', state.world.width); svg.setAttribute('height', state.world.height);
    svg.setAttribute('aria-label', '文档数据流图');
    state.flowNodes = result.nodes.filter(node => !hidden.includes(node));
    for (const node of state.flowNodes) {
      const association = flowRoleFor(node.id);
      node.moduleKey = association?.moduleKey || [...state.documentBase, node.id].join('/');
      const role = node.role = association?.role;
      const pending = role?.status === '未完成';
      node.element.classList.toggle('flow-pending', pending);
      node.element.classList.toggle('flow-complete', role?.status === '已完成');
      if (role) polishFlowLabel(node, role);
      node.element.setAttribute('tabindex', '0'); node.element.setAttribute('role', 'button');
      node.element.setAttribute('aria-label', `${role?.label || node.label}${role?.status ? '，' + role.status : ''}`);
      const title = svgElement('title'); title.textContent = `${role?.summary || node.label}${role?.status ? '\n实现状态：' + role.status : ''}`; node.element.prepend(title);
      node.element.addEventListener('click', () => selectFlowNode(node));
      if (role) node.element.addEventListener('dblclick', () => enterFlowModule(node));
      node.element.addEventListener('keydown', event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectFlowNode(node); } });
    }
    if (state.pendingFlowSelection) {
      state.selectedFlow = state.flowNodes.find(node => node.id === state.pendingFlowSelection) || null;
      state.pendingFlowSelection = null;
    }
    world.replaceChildren(svg); fitMap(); applyHighlight(); renderDetail(); renderRootNav();
  } catch (error) {
    if (request !== state.mapRequest) return;
    state.world = { width: 600, height: 220 }; world.style.width = '600px'; world.style.height = '220px';
    const message = element('div', 'flow-error');
    const diagnostic = element('details', 'flow-diagnostic'); diagnostic.append(element('summary', '', '查看语法错误详情'), element('pre', '', error.message));
    message.append(element('h3', '', '数据流暂时无法绘制'), element('p', '', '请检查文档中的 Mermaid 流程图语法。代码依赖图仍可使用。'),
      diagnostic, button('查看文档原文 ↗', 'button', () => showDocument('数据流')));
    world.replaceChildren(message); fitMap();
  }
}

function polishFlowLabel(node, role) {
  const label = node.element.querySelector(':scope > g.label');
  if (!label) return;
  // 图上保留清晰的中文名称与人工状态，详细内容仍在提示和右侧面板中。
  label.removeAttribute('transform'); label.replaceChildren();
  const shape = node.element.querySelector(':scope > rect,:scope > circle,:scope > ellipse');
  const width = Number(shape?.getAttribute('width')) || Number(shape?.getAttribute('r')) * 2 || Number(shape?.getAttribute('rx')) * 2 || 260;
  const characters = Math.min(15, Math.max(4, Math.floor((width - 32) / 17)));
  const lines = role.label.match(new RegExp(`.{1,${characters}}`, 'gu')) || [role.label];
  lines.slice(0, 2).forEach((line, index) => {
    const title = svgElement('text', { class: 'flow-node-title', 'text-anchor': 'middle', y: lines.length > 1 ? -15 + index * 20 : -5 });
    title.textContent = index === 1 && lines.length > 2 ? `${line.slice(0, -1)}…` : line; label.append(title);
  });
  const meta = svgElement('text', { class: 'flow-node-meta', 'text-anchor': 'middle', y: lines.length > 1 ? 25 : 20 });
  meta.textContent = `${node.id}${role.status ? ' · ' + role.status : ''}`; label.append(meta);
}

function selectFlowNode(node) { state.selectedFlow = node; renderDetail(); applyHighlight(); }

async function enterFlowModule(node) {
  const path = node.moduleKey.split('/'); const id = path.pop();
  try {
    const view = await api('/api/view', { path: path.join('/') });
    const target = view.nodes.find(n => n.id === id);
    if (!target) { toast('此文档节点在当前代码结构中没有对应模块'); return; }
    if (target.leaf) showSource(target.sourceModule); else navigate([...path, id]);
  } catch (error) { toast(error.message); }
}

function renderFlowDetail() {
  const panel = $('#detail-panel'), node = state.selectedFlow;
  if (!node) {
    renderEmptyDetail(panel, true); return;
  }
  const role = node.role;
  panel.replaceChildren(element('div', 'eyebrow', '文档数据流'), element('h3', '', role?.label || node.label),
    element('div', 'detail-id', node.id));
  if (role) panel.append(statusChoices(role));
  const description = element('div', 'detail-description'); description.append(element('p', '', role?.summary || '该流程节点没有关联的子系统职责说明。'),
    element('div', 'detail-provenance', `来自 ${state.document.path} · 实现状态由维护者标注`)); panel.append(description);
  if (role) {
    panel.append(button('查看对应代码结构 ↘', 'button primary', () => enterFlowModule(node)), subsystemDetails(role));
  }
}

function subsystemDetails(role) {
  const details = element('details', 'subsystem-description');
  details.append(element('summary', '', '子系统完整说明'), renderMarkdown(role.markdown, state.document.path));
  return details;
}

function renderEmptyDetail(panel, flow = false) {
  const empty = element('div', 'detail-empty');
  empty.append(icon(flow ? 'flow' : 'map', 'detail-glyph'), element('div', 'eyebrow', '理解项目，从这里开始'),
    element('h3', '', flow ? '沿数据流探索' : '选择一个模块'),
    element('p', '', flow ? '点击流程节点，查看它负责什么、实现到了哪一步，再进入对应代码。' : '点击地图中的模块，查看职责说明、内部组成和上下游关系。'));
  const steps = element('div', 'detail-guide');
  for (const [name, label] of [['map', '查看整体结构'], ['book', '理解模块职责'], ['file', '定位对应源码']]) {
    const row = element('div', ''); row.append(icon(name), element('span', '', label)); steps.append(row);
  }
  empty.append(steps); panel.replaceChildren(empty);
}
function selectNode(id) {
  state.selected = state.view.nodes.find(node => node.id === id) || null;
  renderDetail(); applyHighlight();
}
function clearMapSelection() {
  if (!state.selected && !state.hovered && !state.selectedFlow) return;
  state.selected = null; state.hovered = null; state.selectedFlow = null;
  renderDetail(); applyHighlight();
}
function clickOutsideCanvas(event) {
  const target = event.target;
  if (!(target instanceof Element)) return;
  if (target.closest('#map-viewport, #drawer, #drawer-backdrop, #toast')) return;
  if (target.closest('.status-choices, .detail-actions, .relation, .module-link, .cycle-line, .coverage-modules')) return;
  clearMapSelection();
}

async function locateModule(module) {
  const request = ++state.request;
  $('#analysis-state').textContent = '正在定位模块…';
  try {
    const location = await api('/api/locate', { module });
    if (request !== state.request) return;
    await navigate(location.path, 'explore', location.nodeId);
  } catch (error) {
    if (request === state.request) { $('#analysis-state').textContent = '源码静态依赖'; toast(error.message); }
  }
}

function focusSelectedNode() {
  const card = $$('button.node').find(el => el.dataset.node === state.selected?.id);
  if (!card) return;
  const position = state.positions.get(state.selected.id), viewport = $('#map-viewport');
  const cardBox = card.getBoundingClientRect(), viewBox = viewport.getBoundingClientRect();
  const visible = cardBox.left >= viewBox.left && cardBox.right <= viewBox.right && cardBox.top >= viewBox.top && cardBox.bottom <= viewBox.bottom;
  // 保留居中用的平移，只在卡片超出视野时滚动过去，避免选中后整张图跳回左上角。
  if (position && !visible) viewport.scrollTo({
    left: Math.max(0, state.pan.x + (position.x + CARD.width / 2) * state.zoom - viewport.clientWidth / 2),
    top: Math.max(0, state.pan.y + (position.y + CARD.height / 2) * state.zoom - viewport.clientHeight / 2)
  });
  card.focus({ preventScroll: true });
}
function enterNode(node) {
  if (node.leaf) showSource(node.sourceModule);
  else navigate([...state.path, node.id.replace(/\|file$/, '')]);
}
function applyHighlight() {
  if (!state.view) return;
  if (state.diagramKind === 'flow') {
    const query = $('#search').value.trim().toLowerCase();
    state.flowNodes.forEach(node => {
      const role = node.role;
      node.element.classList.toggle('flow-selected', node.id === state.selectedFlow?.id);
      node.element.classList.toggle('flow-dimmed', Boolean(query && !`${node.id} ${node.label} ${role?.summary || ''} ${role?.status || ''}`.toLowerCase().includes(query)));
      node.element.setAttribute('aria-pressed', String(node.id === state.selectedFlow?.id));
    });
    $$('#module-nav [data-flow]').forEach(link => link.classList.toggle('current', link.dataset.flow === state.selectedFlow?.id));
    $$('.edgePaths,.edgeLabels', $('#map-world')).forEach(el => { el.style.display = $('#show-edges').checked ? '' : 'none'; });
    return;
  }
  const selected = state.selected?.id; const focus = selected || state.hovered; const query = $('#search').value.trim().toLowerCase();
  const connected = new Set([focus]);
  state.view.edges.filter(e => e.from === focus || e.to === focus).forEach(e => { connected.add(e.from); connected.add(e.to); });
  for (const node of state.view.nodes) {
    const el = $$('button.node').find(el => el.dataset.node === node.id); if (!el) continue;
    const matched = !query || `${node.label} ${titleFor(node)} ${node.fullName} ${node.members.join(' ')} ${descriptionFor(node)} ${roleFor(node)?.status || ''}`.toLowerCase().includes(query);
    el.classList.toggle('selected', node.id === selected); el.classList.toggle('matched', Boolean(query && matched));
    el.classList.toggle('dimmed', !matched || (focus && !connected.has(node.id)) || (state.cyclesOnly && !node.cycle));
    el.setAttribute('aria-pressed', String(node.id === selected));
  }
  for (const edge of $$('.edge,.edge-label')) {
    const active = edge.dataset.from === focus || edge.dataset.to === focus;
    const incoming = edge.dataset.to === focus;
    const cycle = state.cyclesOnly && state.view.nodes.some(node => node.id === edge.dataset.from && node.cycle) && state.view.nodes.some(node => node.id === edge.dataset.to && node.cycle);
    edge.classList.toggle('active', Boolean(focus && active)); edge.classList.toggle('incoming', Boolean(focus && incoming));
    edge.classList.toggle('faded', Boolean(focus && !active));
    edge.style.display = $('#show-edges').checked && ($('#edge-scope').value === 'all' || active || cycle) ? '' : 'none';
    if (edge.tagName.toLowerCase() === 'path') edge.setAttribute('marker-end', `url(#${focus && active ? incoming ? 'arrow-incoming' : 'arrow-active' : 'arrow'})`);
  }
  $('.map-footer > span').textContent = $('#edge-scope').value === 'focus' ? '指向预览 · 点击固定 · 拖动空白处移动画布 · 点击画布外恢复全局 · 绿色为依赖，橙色为被依赖' : '拖动空白处移动画布 · 点击画布外恢复全局 · 点击突出上下游 · Ctrl + 滚轮缩放';
}
function relationSection(title, edges, incoming) {
  const section = element('section', 'detail-section'), heading = element('h4', '', title); heading.append(element('span', '', edges.length)); section.append(heading);
  const list = element('div', 'relation-list');
  for (const edge of edges.slice(0, 40)) {
    const id = incoming ? edge.from : edge.to; const node = state.view.nodes.find(n => n.id === id);
    const item = button('', `relation${incoming ? ' incoming' : ''}`, () => {
      if (node) { selectNode(id); focusSelectedNode(); }
      else locateModule(id);
    });
    item.title = node ? '定位这个模块' : '跳转到这个模块所在的软件包，并选中它';
    item.append(element('span', '', node ? titleFor(node) : id));
    if (edge.count > 1) item.append(element('span', 'relation-count', `×${edge.count}`));
    item.append(icon('arrow')); list.append(item);
  }
  if (!edges.length) list.append(element('p', '', '当前范围无此类依赖')); section.append(list); return section;
}
function renderDetail() {
  if (state.diagramKind === 'flow') { renderFlowDetail(); return; }
  const panel = $('#detail-panel'); const node = state.selected;
  if (!node) {
    renderEmptyDetail(panel); return;
  }
  panel.replaceChildren(element('div', 'eyebrow', node.leaf ? '源码文件' : '模块详情'));
  panel.append(element('h3', '', titleFor(node)), element('div', 'detail-id', node.sourcePath || node.fullName));
  const role = roleFor(node);
  if (role) panel.append(statusChoices(role));
  if (node.cycle) panel.append(element('span', 'cycle-badge', '包含循环依赖'));
  const description = element('div', 'detail-description'); description.append(element('p', '', descriptionFor(node) || (node.leaf ? '该文件没有可提取的文件头说明。' : `包含 ${node.moduleCount} 个源码模块，可以进入查看内部结构。`)));
  if (descriptionFor(node)) {
    const provenance = role?.summary ? `来自 ${state.document.path} · 实现状态由维护者标注` :
      node.descriptionFromOnlyFile ? '来自目录中唯一源码文件的说明' : '来自文件头注释 / 模块文档字符串';
    description.append(element('div', 'detail-provenance', provenance));
  }
  panel.append(description);
  const actions = element('div', 'detail-actions'); actions.append(button(node.leaf ? '查看源码 ↗' : '查看内部结构 ↘', 'button primary', () => enterNode(node))); panel.append(actions);
  if (role) panel.append(subsystemDetails(role));
  const edges = state.view.displayEdges;
  panel.append(relationSection('依赖这些模块 →', edges.filter(e => e.from === node.id), false), relationSection('← 被这些模块依赖', edges.filter(e => e.to === node.id), true));
  if (!node.leaf) {
    const members = element('section', 'detail-section'); members.append(element('h4', '', `内部源码 · ${node.moduleCount}`));
    const list = element('ul', 'member-list'); node.members.slice(0, 10).forEach(member => list.append(element('li', '', member))); members.append(list);
    if (node.members.length > 10) members.append(element('p', '', `其余 ${node.members.length - 10} 个模块，进入内部查看`)); panel.append(members);
  }
}
function renderCycles() {
  const panel = $('#cycles-panel'); panel.replaceChildren(); panel.hidden = !state.cyclesOnly;
  if (!state.view.cycles.length) { panel.append(element('h3', '', '当前范围未检测到循环依赖')); return; }
  panel.append(element('h3', '', `当前范围包含 ${state.view.cycles.length} 组循环依赖`));
  state.view.cycles.forEach(cycle => panel.append(button(cycle.replace(/->/g, ' → '), 'cycle-line', () => {
    const match = state.view.nodes.find(n => cycle.includes(n.fullName)); if (match) selectNode(match.id);
  })));
}
function applyZoom() {
  const world = $('#map-world'), svg = $('svg', world);
  if (state.diagramKind === 'flow' && svg) {
    world.style.transform = `translate(${state.pan.x}px, ${state.pan.y}px)`; world.style.width = `${state.world.width * state.zoom}px`; world.style.height = `${state.world.height * state.zoom}px`;
    svg.style.transformOrigin = 'top left'; svg.style.transform = `scale(${state.zoom})`;
  } else {
    // 画布的占位空间跟随缩放，避免图已适应视口却仍出现多余滚动条。
    world.style.width = `${state.world.width * state.zoom}px`; world.style.height = `${state.world.height * state.zoom}px`;
    world.style.transform = `translate(${state.pan.x}px, ${state.pan.y}px) scale(${state.zoom})`;
  }
  $('#zoom-value').textContent = `${Math.round(state.zoom * 100)}%`;
}
function zoomBy(delta) { state.zoom = Math.min(1.8, Math.max(.15, state.zoom + delta)); applyZoom(); }
function fitMap() {
  state.pan = { x: 0, y: 0 };
  applyZoom();
  const viewport = $('#map-viewport');
  const widthFit = (viewport.clientWidth - 12) / state.world.width;
  const narrow = viewport.clientWidth < 480;
  const heightFit = (viewport.clientHeight - 12) / state.world.height;
  state.zoom = Math.min(1, Math.max(.15, Math.min(widthFit, heightFit)));
  if (narrow && state.diagramKind === 'flow') state.zoom = Math.max(.65, state.zoom);
  applyZoom();
  const width = state.world.width * state.zoom, height = state.world.height * state.zoom;
  state.pan = { x: Math.max(0, (viewport.clientWidth - width) / 2), y: Math.max(0, (viewport.clientHeight - height) / 2) };
  applyZoom();
  // 小图通过平移居中；受最小缩放限制的大图滚动到中心。
  viewport.scrollTo(Math.max(0, (width - viewport.clientWidth) / 2), Math.max(0, (height - viewport.clientHeight) / 2));
}

function setMapFullscreen(active) {
  const grid = $('.view-grid'), viewport = $('#map-viewport'), control = $('#map-fullscreen');
  if (grid.classList.contains('is-fullscreen') === active) return;
  const key = `${state.diagramKind}:${state.path.join('/')}`;
  if (active) state.fullscreenCamera = { key, zoom: state.zoom, pan: { ...state.pan }, left: viewport.scrollLeft, top: viewport.scrollTop };
  else { state.restoreCamera = state.fullscreenCamera?.key === key ? state.fullscreenCamera : null; state.fullscreenCamera = null; }
  grid.classList.toggle('is-fullscreen', active); document.body.classList.toggle('map-fullscreen', active);
  for (const el of $$('.rail,.topbar,.hero,.metrics,#architecture-coverage,#design-notes,#cycles-panel,.page-footer')) el.inert = active;
  control.setAttribute('aria-label', active ? '退出全屏' : '全屏查看'); control.title = active ? '退出全屏（Esc）' : '全屏查看';
  control.setAttribute('aria-pressed', String(active)); control.replaceChildren(icon(active ? 'collapse' : 'expand'));
  control.focus({ preventScroll: true });
}

function toggleMapFullscreen() {
  setMapFullscreen(!$('.view-grid').classList.contains('is-fullscreen'));
}

// 左键拖动空白处平移；中键也可从模块上开始拖动，不影响单击和双击模块。
let canvasDrag = null;
function beginCanvasDrag(event) {
  if (event.pointerType === 'touch' || ![0, 1].includes(event.button)) return;
  if (event.button === 0 && event.target.closest('button,a,input,select,[role="button"],g.node')) return;
  event.preventDefault();
  canvasDrag = { id: event.pointerId, x: event.clientX, y: event.clientY, pan: { ...state.pan } };
  $('#map-viewport').setPointerCapture(event.pointerId); $('#map-viewport').classList.add('dragging');
}
function moveCanvasDrag(event) {
  if (!canvasDrag || canvasDrag.id !== event.pointerId) return;
  state.pan = { x: canvasDrag.pan.x + event.clientX - canvasDrag.x, y: canvasDrag.pan.y + event.clientY - canvasDrag.y };
  applyZoom();
}
function endCanvasDrag(event) {
  if (!canvasDrag || canvasDrag.id !== event.pointerId) return;
  canvasDrag = null; const viewport = $('#map-viewport'); viewport.classList.remove('dragging');
  if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
}
let drawerReturnFocus;
function openDrawer(title, path, kind) {
  drawerReturnFocus = document.activeElement;
  $('#drawer-title').textContent = title; $('#drawer-path').textContent = path;
  $('#drawer-kind').textContent = kind; $('#drawer-content').replaceChildren();
  $('#drawer').hidden = false; $('#drawer-backdrop').hidden = false;
  document.body.style.overflow = 'hidden'; $('#drawer-close').focus();
}
function closeDrawer() { ++state.sourceRequest; $('#drawer').hidden = true; $('#drawer-backdrop').hidden = true; document.body.style.overflow = ''; drawerReturnFocus?.focus(); }
function renderMarkdown(content, docPath) {
  const article = element('article', 'markdown');
  article.innerHTML = DOMPurify.sanitize(marked.parse(content), { USE_PROFILES: { html: true }, FORBID_TAGS: ['style', 'form', 'input', 'button'], FORBID_ATTR: ['style', 'id', 'name'] });
  for (const img of $$('img', article)) {
    const src = img.getAttribute('src'); if (src && !/^(?:https?:|data:)/i.test(src)) {
      const url = new URL(src, `http://local/${docPath}`); img.src = `/api/image?path=${encodeURIComponent(decodeURIComponent(url.pathname.slice(1)))}`;
    }
  }
  for (const link of $$('a', article)) {
    const href = link.getAttribute('href') || '';
    if (href.startsWith('#module=')) {
      link.addEventListener('click', event => { event.preventDefault(); closeDrawer(); navigate(href.slice(8).split('/').filter(Boolean)); });
    } else if (href.startsWith('#')) {
      link.addEventListener('click', event => {
        event.preventDefault(); const target = decodeURIComponent(href.slice(1)).replace(/[-_\s]/g, '').toLowerCase();
        const heading = $$('h1,h2,h3,h4,h5,h6', article).find(h => h.textContent.replace(/[-_\s]/g, '').toLowerCase() === target); heading?.scrollIntoView({ block: 'start' });
      });
    } else if (/^https?:/i.test(href)) { link.target = '_blank'; link.rel = 'noopener noreferrer'; }
    else {
      link.addEventListener('click', async event => {
        event.preventDefault(); const path = decodeURIComponent(new URL(href, `http://local/${docPath}`).pathname.slice(1));
        const doc = state.project.documents.find(d => d.id === path);
        if (doc) { await loadDocument(doc.id); showDocument(); }
        else toast(`项目内链接：${path}。当前文档列表未收录此文件。`);
      });
    }
  } return article;
}
function showDocument(sectionTitle) {
  if (!state.document) return;
  openDrawer('架构说明', state.document.path, '项目说明');
  const article = renderMarkdown(state.document.content, state.document.path); $('#drawer-content').append(article);
  if (sectionTitle) $$('h2,h3,h4,h5,h6', article).find(h => h.textContent === sectionTitle)?.scrollIntoView({ block: 'start' });
  for (const code of $$('pre code.language-mermaid', article)) {
    const source = code.textContent, pre = code.parentElement;
    renderDataFlow(source).then(({ diagram }) => {
      if (!article.isConnected) return;
      const visual = element('div', 'document-flow'); visual.append(diagram);
      const original = element('details', 'flow-source'); original.append(element('summary', '', '查看 Mermaid 原文'), pre.cloneNode(true));
      pre.replaceWith(visual, original);
    }).catch(() => { pre.before(element('p', 'flow-error-note', '此流程图暂时无法绘制，以下保留原文。')); });
  }
}
async function showSource(module) {
  if (!module) { toast('该模块没有可用的源码路径'); return; }
  const request = ++state.sourceRequest;
  try {
    const source = await api('/api/source', { module }); if (request !== state.sourceRequest) return;
    openDrawer(module.split('.').at(-1), source.path, '源码文件');
    if (source.description) $('#drawer-content').append(element('div', 'source-description', source.description));
    const list = element('ol', 'source-list'); source.content.split('\n').forEach(line => list.append(element('li', '', line || ' '))); $('#drawer-content').append(list);
  } catch (error) { toast(error.message); }
}
async function reanalyze() {
  const control = $('#reanalyze'); control.disabled = true; control.classList.add('busy'); control.replaceChildren(icon('refresh'), element('span', '', '分析中…'));
  try {
    state.project = await api('/api/reanalyze', {}, 'POST'); updateProject();
    state.rootView = await api('/api/view');
    state.navigationView = null; state.navigationPath = [];
    const doc = state.project.documents.find(d => d.id === state.document?.id) || state.project.documents.find(d => d.scope === 'project');
    await loadDocument(doc?.id || '');
    let path = state.path;
    if (path.length && !(await api('/api/view', { path: path.join('/') })).nodes.length) path = [];
    await navigate(path, state.mode); toast('架构与项目说明已更新');
  } catch (error) { toast(error.message); }
  finally { control.disabled = false; control.classList.remove('busy'); control.replaceChildren(icon('refresh'), element('span', '', '重新分析')); }
}
$$('[data-icon]').forEach(el => el.replaceChildren(icon(el.dataset.icon)));
$('#brand-home').addEventListener('click', event => { event.preventDefault(); navigate([], 'overview'); });
$('#overview-nav').addEventListener('click', () => navigate([], 'overview'));
$('#explore-nav').addEventListener('click', () => { setMode('explore'); fitMap(); });
$('#start-explore').addEventListener('click', () => { setMode('explore'); fitMap(); });
$('#document-select').addEventListener('change', async event => { try { await loadDocument(event.target.value); } catch (error) { toast(error.message); } });
$('#document-open').addEventListener('click', () => showDocument());
$('#search').addEventListener('input', applyHighlight);
for (const [id, kind] of [['code-view', 'code'], ['flow-view', 'flow']]) $('#' + id).addEventListener('click', () => {
  state.diagramKind = kind; state.selected = null; state.selectedFlow = null; state.cyclesOnly = false;
  $('#cycle-filter').classList.remove('active'); renderMap(); renderDetail(); renderRootNav(); fitMap();
});
$('#flow-select').addEventListener('change', event => { state.flowIndex = Number(event.target.value); state.selectedFlow = null; renderMap(); renderDetail(); });
$('#show-edges').addEventListener('change', applyHighlight);
$('#edge-scope').addEventListener('change', applyHighlight);
$('#map-back').addEventListener('click', navigateUp);
$('#zoom-in').addEventListener('click', () => zoomBy(.1)); $('#zoom-out').addEventListener('click', () => zoomBy(-.1)); $('#zoom-fit').addEventListener('click', fitMap);
$('#map-fullscreen').addEventListener('click', toggleMapFullscreen);
$('#map-viewport').addEventListener('pointerdown', beginCanvasDrag);
$('#map-viewport').addEventListener('pointermove', moveCanvasDrag);
for (const event of ['pointerup', 'pointercancel', 'lostpointercapture']) $('#map-viewport').addEventListener(event, endCanvasDrag);
$('#map-viewport').addEventListener('wheel', event => { if (event.ctrlKey || event.metaKey) { event.preventDefault(); zoomBy(event.deltaY < 0 ? .05 : -.05); } }, { passive: false });
$('#cycle-filter').addEventListener('click', () => { state.cyclesOnly = !state.cyclesOnly; applyHighlight(); renderCycles(); if (state.cyclesOnly) $('#cycles-panel').scrollIntoView({ block: 'nearest', behavior: 'smooth' }); });
$('#reanalyze').addEventListener('click', reanalyze);
$('#drawer-close').addEventListener('click', closeDrawer); $('#drawer-backdrop').addEventListener('click', closeDrawer);
document.addEventListener('click', clickOutsideCanvas);
document.addEventListener('keydown', event => {
    if (event.key === 'Escape') { if (!$('#drawer').hidden) closeDrawer(); else if ($('.view-grid').classList.contains('is-fullscreen')) toggleMapFullscreen(); else clearMapSelection(); }
  if (event.key === 'Tab' && !$('#drawer').hidden) {
    const focusable = $$('button,a[href],input,select,[tabindex="0"]', $('#drawer'));
    const first = focusable[0], last = focusable.at(-1);
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
  }
});
let observedViewportWidth = 0;
let observedViewportHeight = 0;
new ResizeObserver(() => {
  if (!state.view) return;
  // 平移可能让滚动条出现；只在画布外框真正改变时重排，避免拖动被重置。
  const bounds = $('#map-viewport').getBoundingClientRect();
  const width = bounds.width;
  const height = bounds.height;
  if (width === observedViewportWidth && height === observedViewportHeight) return;
  if (width !== observedViewportWidth) {
    observedViewportWidth = width;
    if (state.diagramKind === 'code') renderMap();
  }
  observedViewportHeight = height;
  if (state.restoreCamera) {
    const camera = state.restoreCamera; state.restoreCamera = null;
    state.zoom = camera.zoom; state.pan = camera.pan; applyZoom(); $('#map-viewport').scrollTo(camera.left, camera.top);
  } else { fitMap(); if (state.selected && state.diagramKind === 'code') focusSelectedNode(); }
}).observe($('#map-viewport'));
(async function initialize() {
  try {
    state.project = await api('/api/project'); updateProject();
    $('#edge-scope').value = state.project.edgeScope || 'focus';
    const doc = state.project.documents.find(d => d.scope === 'project'); await loadDocument(doc?.id || '');
    await navigate([], 'overview');
  } catch (error) { $('#hero-summary').textContent = `加载失败：${error.message}`; toast(error.message); }
})();
