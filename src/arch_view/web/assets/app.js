/*
 * 职责：控制网页架构地图、逐层浏览（含 Maven/Gradle 构建模块标注与视图缓存）、缩放拖动、页面内全屏，以及文档和源码面板。
 * 核心入口：页面初始化流程（initialize），页面打开后自动读取项目数据。
 */

// 页面脚本作为独立模块加载，内部函数和状态不向外部公开。
'use strict';
import { parseArchitectureDocument, renderDataFlow, uncoveredModules } from './architecture_document.js';
import { createExploration } from './exploration.js';
import { createPinnedArchitecture } from './pinned_architecture.js';
import { CARD, layoutCodeMap, routeEdge as layoutRouteEdge, scrollToReveal } from './map_layout.js';
import { collapsePackageChain, chainPresence } from './tree_prefix.js';
const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const state = { project: null, view: null, rootView: null, path: [], selected: null, document: null,
  sections: [], roles: new Map(), flowRoles: new Map(), zoom: 1, world: { width: 800, height: 400, routeWidth: 800 }, mode: 'explore',
  request: 0, documentRequest: 0, sourceRequest: 0, cyclesOnly: false, positions: new Map(),
  flows: [], flowIndex: 0, diagramKind: 'code', mapRequest: 0, flowNodes: [], selectedFlow: null, documentBase: [],
  returnTrail: [], coverageDocument: null, uncovered: new Set(), pendingFlowSelection: null, hovered: null,
  pan: { x: 0, y: 0 }, fullscreenCamera: null, restoreCamera: null, levelCameras: new Map(),
  navigationView: null, navigationPath: [], sourceModule: null, pinScope: null,
  treeExpanded: new Set(), treeViews: new Map(), treeErrors: new Map(), treeLoading: new Set(),
  detailPinned: false, heldDetail: null, detailCollapsed: true, detailForced: false,
  complexity: { status: 'unavailable' }, showComplexity: localStorage.getItem('arch-view.show-complexity') !== 'off' };

let pinnedArchitecture;
let mainCamera = null;
function pinIsOpen() { return pinnedArchitecture?.isOpen() ?? true; }
function selectedNodeLocation() {
  return { path: state.selected?.scopePath || state.path, id: state.selected?.scopeId || state.selected?.id || null };
}
function updatePinnedLocation() {
  const location = selectedNodeLocation();
  pinnedArchitecture?.updateLocation({
    scopes: state.pinScope?.targets.map(target => [...target.path, ...(target.nodeId ? [target.nodeId] : [])]) || [],
    path: [...location.path], selected: state.selected ? { ...state.selected, id: location.id } : null, sourceModule: state.sourceModule });
}
function loadPinnedArchitecture() {
  // 全局文档独立加载，渲染或读取失败不阻塞当前层代码图。
  void pinnedArchitecture.load(state.project, state.rootView).then(updatePinnedLocation).catch(error => toast(error.message));
}
function onPinVisibilityChange(open) {
  $('#pin-open').hidden = open;
  if (open && state.diagramKind === 'flow') {
    state.diagramKind = 'code'; state.selectedFlow = null;
    renderMap(); applyZoom(); renderDetail(); renderRootNav();
  } else if (state.view) renderDiagramControls();
}

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
// 架构数据在重新分析或复杂度完成前不会变化，同一层只向服务端请求一次；失败的请求不缓存。
const viewCache = new Map();
function fetchView(path) {
  const key = Array.isArray(path) ? path.join('/') : String(path || '');
  if (!viewCache.has(key)) {
    viewCache.set(key, api('/api/view', key ? { path: key } : {}).then(view => {
      state.treeViews.set(key, view); return view;
    }).catch(error => { viewCache.delete(key); throw error; }));
  }
  return viewCache.get(key);
}
// Maven/Gradle 构建模块只出现在根层第一段；旧导出数据没有构建工具时统称构建模块。
const BUILD_TOOLS = { maven: 'Maven', gradle: 'Gradle' };
function buildModuleKind(module) {
  const tool = BUILD_TOOLS[module?.buildTool];
  return tool ? `${tool} 模块` : '构建模块';
}
function buildModuleDirectory(module) {
  return module.directory === '.' ? '项目根目录' : `${module.directory}/`;
}
function buildModuleNamed(name) {
  return (state.project?.buildModules || []).find(module => module.name === name);
}
function buildModuleSummary() {
  const modules = state.project.buildModules || [];
  const tools = [...new Set(modules.map(module => BUILD_TOOLS[module.buildTool]).filter(Boolean))];
  return `${modules.length} 个${tools.length ? ' ' + tools.join('/') + ' ' : ''}构建模块`;
}
// 在构建模块内部时标题保留所属模块，自动展开多层单子包后也能看出当前在哪个模块。
function codeMapTitle() {
  if (state.pinScope) return `${state.pinScope.label || '架构节点'} · 全部代码范围`;
  const path = state.path, module = path.length && buildModuleNamed(path[0]);
  if (!path.length) return state.project.buildModules?.length ? `全局架构地图 · ${buildModuleSummary()}` : '全局架构地图';
  if (!module) return `${path.at(-1)} · 内部架构`;
  return path.length === 1 ? `${path[0]} · ${buildModuleKind(module)}内部架构` : `${path.at(-1)} · 内部架构 · ${buildModuleKind(module)} ${path[0]}`;
}
function nodeIcon(node) {
  return node.buildModule ? 'module' : node.leaf ? 'file' : 'folder';
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
// 文件说明由服务端整理为 Markdown（Javadoc 的 <p>、{@code}、{@link} 已转换），清洗后以 DOM 片段插入。
function richText(markdown, className = '', options = {}) {
  const container = element('div', `rich-text ${className}`.trim());
  container.append(DOMPurify.sanitize(marked.parse(markdown || '', { gfm: true, breaks: options.breaks !== false }), {
    USE_PROFILES: { html: true }, FORBID_TAGS: ['style', 'form', 'input', 'button', 'img'], FORBID_ATTR: ['style', 'id', 'name'], RETURN_DOM_FRAGMENT: true
  }));
  for (const link of $$('a', container)) {
    const href = link.getAttribute('href') || '';
    if (/^https?:/i.test(href)) { link.target = '_blank'; link.rel = 'noopener noreferrer'; }
    else if (!options.keepLocalLinks) link.removeAttribute('href');
  }
  return container;
}
// 卡片只显示两行，大目录下数百张卡片不值得逐个解析 Markdown，去掉标记即可。
function cardText(markdown) {
  return String(markdown || '').replace(/```[\s\S]*?```/g, ' ').replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/[`*]/g, '').replace(/\\([<>])/g, '$1').replace(/^\s*- /gm, '').replace(/\s+/g, ' ').trim();
}
function roleFor(node, path = node.scopePath || state.path) {
  return state.roles.get([...path, (node.scopeId || node.id).replace(/\|file$/, '')].join('/'));
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
  // 多模块项目的源码目录很长，改为显示构建模块概况。
  $('#project-language').textContent = `${String(p.language).toUpperCase()} / ${p.buildModules?.length ? buildModuleSummary() : (p.sourcePaths || []).join(' · ')}`;
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
async function loadDocument(id, packagePath = state.document?.id === id ? state.documentBase : state.path, navigationRequest = null) {
  const request = ++state.documentRequest;
  const doc = id ? await api('/api/document', { id }) : null;
  if (request !== state.documentRequest || (navigationRequest !== null && navigationRequest !== state.request)) return;
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
  return view.pinnedScope ? view.nodes : view.nodes.filter(node => node.members.some(module => !state.uncovered.has(module)));
}
function updateOverview() {
  const doc = state.document;
  const tokens = doc ? marked.lexer(doc.content) : [];
  const heading = tokens.find(t => t.type === 'heading' && t.depth === 1);
  const intro = tokens.find(t => t.type === 'paragraph' && plain(t.text));
  $('#hero-title').textContent = heading ? plain(heading.text) : state.project.name;
  $('#hero-summary').textContent = intro ? plain(intro.text) :
    `${state.project.name} 包含 ${state.project.buildModules?.length ? buildModuleSummary() + '、' : ''}${state.project.moduleCount} 个源码模块。可在总览阅读架构说明，或切换到探索页查看模块边界与代码依赖。`;
  $('#document-provenance').textContent = doc ? `说明来源：${doc.path} · 结构来源：源码静态分析` : '结构来自源码 · 未选择说明文档';
  $('#document-open').disabled = !doc;
  const notes = $('#design-notes'); notes.replaceChildren();
  const sections = [...state.roles.values()].length ? [...state.roles.values()].slice(0, 6) : state.sections.filter(s => s.summary && s.depth === 2).slice(0, 6);
  if (!sections.length) {
    notes.append(element('div', 'no-doc-note', doc ? '说明文档已加载，可在上方打开完整内容。' : '可选择已有项目说明，也可直接探索架构；不要求每个包编写文档。')); return;
  }
  const headingRow = element('div', 'notes-heading'); headingRow.append(element('h2', '', '架构说明'), element('span', '', doc.path)); notes.append(headingRow);
  const grid = element('div', 'note-grid');
  sections.forEach((section, index) => {
    const card = element('article', 'note-card'); card.append(element('span', 'note-number', `${String(index + 1).padStart(2, '0')} /`), element('h3', '', section.label || section.title), statusBadge(section.status), element('p', '', section.summary));
    const open = button('↗', '', () => showDocument(section.title)); open.setAttribute('aria-label', `阅读${section.title}`); card.append(open); grid.append(card);
  }); notes.append(grid);
}
function navFloatPreferred() {
  return localStorage.getItem('arch-view.nav-float') !== 'off';
}
function applyNavFloat() {
  const open = navFloatPreferred();
  const show = document.body.classList.contains('exploring') && !document.body.classList.contains('map-fullscreen');
  $('#nav-float').hidden = !show || !open;
  $('#nav-open').hidden = !show || open;
}
function setNavFloat(open) {
  localStorage.setItem('arch-view.nav-float', open ? 'on' : 'off');
  applyNavFloat();
}
function setMode(mode) {
  const exploring = mode === 'explore';
  state.mode = mode;
  document.body.classList.toggle('exploring', exploring);
  $('#overview-nav').classList.toggle('active', !exploring);
  $('#explore-nav').classList.toggle('active', exploring);
  $('#overview-nav').setAttribute('aria-selected', String(!exploring));
  $('#explore-nav').setAttribute('aria-selected', String(exploring));
  applyNavFloat();
}
function showOverview() {
  if ($('.view-grid').classList.contains('is-fullscreen')) setMapFullscreen(false);
  setMode('overview');
}
function showExplore() {
  setMode('explore');
  requestAnimationFrame(() => { if (state.view) { presentCurrentMap(); if (state.selected && state.diagramKind === 'code') focusSelectedNode(); } });
}
async function expandSinglePackages(view, path, request) {
  // 连续的单子包没有需要选择的分支，直接展开；源码文件保留在图中。
  while (true) {
    const nodes = visibleNodes(view);
    if (nodes.length !== 1 || nodes[0].leaf) break;
    const nextPath = [...path, nodes[0].id.replace(/\|file$/, '')];
    const nextView = await fetchView(nextPath);
    if (request !== state.request) return null;
    if (!nextView.nodes.length) break;
    path = nextPath; view = nextView;
  }
  return { view, path };
}
async function navigate(path, mode = 'explore', selectedId = null, { expand = true, keepReturn = false, pinScope = null } = {}) {
  const request = ++state.request;
  // 依赖列表、数据流节点的临时跳转和智慧返回保留返回记录；其它导航说明用户换了关注点。
  if (!keepReturn) { state.returnTrail = []; renderReturnButton(); }
  const fromOverview = !path.length && mode === 'overview';
  $('#analysis-state').textContent = '正在读取结构…';
  try {
    let view = pinScope ? await fetchPinnedScopeView(pinScope, path) : await fetchView(path);
    if (request !== state.request) return false;
    const atRoot = !path.length && !pinScope;
    if (atRoot) { state.rootView = view; state.navigationView = null; renderCoverage(); }
    if (!state.navigationView) {
      const navigation = await expandSinglePackages(state.rootView, [], request);
      if (!navigation || request !== state.request) return false;
      state.navigationView = navigation.view; state.navigationPath = navigation.path;
    }
    // 明确定位源码时保留目标所在层，避免自动展开把选中节点跳过去。
    if (!pinScope && !selectedId && expand) {
      const expanded = atRoot ? { view: state.navigationView, path: state.navigationPath }
        : await expandSinglePackages(view, path, request);
      if (!expanded || request !== state.request) return false;
      view = expanded.view; path = expanded.path;
    }
    state.view = view; state.path = path; state.pinScope = pinScope;
    state.selected = null; state.hovered = null; state.cyclesOnly = false;
    state.selectedFlow = null; state.detailPinned = false; state.heldDetail = null; state.detailForced = false;
    state.detailCollapsed = !selectedId && !exploration.traceActive();
    state.diagramKind = fromOverview && state.flows.length && !pinIsOpen() ? 'flow' : 'code';
    updatePinnedLocation();
    setMode(mode); $('#search').value = ''; $('#cycle-filter').classList.remove('active');
    $('#map-title').textContent = codeMapTitle();
    $('#metric-groups').textContent = view.nodes.length;
    $('#metric-cycles').textContent = view.cycles.length;
    $('#cycle-filter').classList.toggle('has-cycles', view.cycles.length > 0);
    renderCoverage(); renderBreadcrumbs(); renderRootNav(); renderMap(); renderDetail(); renderCycles(); presentCurrentMap();
    $('#analysis-state').textContent = state.diagramKind === 'flow' ? '文档数据流' : '源码静态依赖';
    const local = !pinScope && path.map((_, index) => path.slice(0, index + 1)).reverse()
      .map(scope => ({ path: scope, doc: view.documents.find(doc => doc.directory.endsWith(scope.join('/').replace(/-/g, '_'))) }))
      .find(match => match.doc);
    if (local && !(fromOverview && state.project.documents.find(doc => doc.id === state.document?.id)?.scope === 'project')) {
      await loadDocument(local.doc.id, local.path, request);
    }
    else if (state.document && state.project.documents.find(d => d.id === state.document.id)?.scope === 'package') {
      const projectDoc = state.project.documents.find(d => d.scope === 'project'); await loadDocument(projectDoc?.id || '', [], request);
    }
    if (request !== state.request) return false;
    if (selectedId) { selectNode(selectedId); renderRootNav(); focusSelectedNode(); }
    updatePinnedLocation();
    return true;
  } catch (error) { if (request === state.request) { $('#analysis-state').textContent = '读取失败'; toast(error.message); } }
  return false;
}
function renderBreadcrumbs() {
  const nav = $('#breadcrumbs'); nav.replaceChildren(button('项目', '', () => navigate([], 'explore')));
  state.path.forEach((part, index) => {
    const crumb = button(part, '', () => navigate(state.path.slice(0, index + 1), 'explore', null, { expand: false }));
    const module = index === 0 && buildModuleNamed(part);
    if (module) { crumb.classList.add('build-crumb'); crumb.prepend(icon('module')); crumb.title = `${buildModuleKind(module)} · ${buildModuleDirectory(module)}`; }
    nav.append(element('i', '', '/'), crumb);
  });
  if (state.pinScope) nav.append(element('i', '', '/'), element('span', '', state.pinScope.label || '全部代码范围'));
}
// 智慧返回：记住临时跳转前的层级和选中节点，可逐级跳回。
function returnTarget(node = state.selected, { allowEmpty = false, camera = false } = {}) {
  let target;
  if (state.diagramKind === 'flow') {
    if (!node) return null;
    target = { path: [...state.path], id: node.id, label: node.role?.label || node.label,
      kind: 'flow', mode: state.mode, documentId: state.document.id, documentBase: [...state.documentBase],
      flowIndex: state.flowIndex };
  } else {
    if (!state.selected && !allowEmpty) return null;
    target = { path: [...state.path], id: state.selected?.id || null, pinScope: state.pinScope,
      label: state.selected ? titleFor(state.selected) : state.pinScope?.label || state.path.at(-1) || '全局架构' };
  }
  if (camera) { target.camera = captureMapCamera(); target.mode = state.mode; }
  return target;
}
function rememberReturn(node = state.selected) {
  const target = returnTarget(node);
  if (target) state.returnTrail.push(target);
  renderReturnButton();
}
function renderReturnButton() {
  const target = state.returnTrail.at(-1), back = $('#map-return');
  back.hidden = !target || state.diagramKind === 'flow';
  if (!target) return;
  back.replaceChildren(icon('return'), element('span', '', `回到 ${target.label}`));
  back.title = state.returnTrail.length > 1 ? `回到跳转前的 ${target.label}（还可再返回 ${state.returnTrail.length - 1} 次）` : `回到跳转前的 ${target.label}`;
}
async function returnBack() {
  const target = state.returnTrail.pop(); renderReturnButton();
  if (!target) return;
  if (target.kind === 'flow') {
    if (!await navigate(target.path, target.mode, null, { expand: false, keepReturn: true })) return;
    if (state.document?.id !== target.documentId) await loadDocument(target.documentId, target.documentBase);
    state.diagramKind = 'flow'; state.flowIndex = target.flowIndex; state.pendingFlowSelection = target.id;
    renderMap(); renderDetail(); renderRootNav();
  } else if (target.path.join('/') === state.path.join('/') && (target.pinScope || null) === state.pinScope) {
    if (target.mode) setMode(target.mode);
    selectNode(target.id); renderRootNav(); if (!target.camera) focusSelectedNode();
  } else if (!await navigate(target.path, target.mode || 'explore', target.id, { expand: false, keepReturn: true, pinScope: target.pinScope })) return;
  if (target.camera) restoreMapCamera(target.camera);
  updatePinnedLocation();
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
function treePathKey(path) {
  return path.join('/');
}
let treeSyncedLocation = '';
let treeGeneration = 0;
function resetTreeCaches() {
  treeGeneration += 1;
  treeSyncedLocation = '';
  state.treeViews.clear();
  state.treeErrors.clear();
  state.treeLoading.clear();
}
function syncTreeToLocation() {
  const current = selectedNodeLocation();
  const location = `${treePathKey(current.path)}|${current.id || ''}`;
  if (location === treeSyncedLocation) return;
  treeSyncedLocation = location;
  const prefixes = current.path.map((_, index) => current.path.slice(0, index + 1));
  for (const prefix of prefixes) {
    const key = treePathKey(prefix);
    state.treeExpanded.add(key);
    if (!state.treeViews.has(key) && !state.treeLoading.has(key)) void loadTreeBranch(prefix);
  }
}
function toggleTree(path) {
  const key = treePathKey(path);
  if (state.treeExpanded.has(key)) {
    state.treeExpanded.delete(key);
    renderRootNav();
    return;
  }
  state.treeExpanded.add(key);
  if (state.treeViews.has(key)) { renderRootNav(); return; }
  void loadTreeBranch(path);
  renderRootNav();
}
async function loadTreeBranch(path) {
  const key = treePathKey(path);
  const generation = treeGeneration;
  if (state.treeLoading.has(key)) return;
  state.treeLoading.add(key);
  state.treeErrors.delete(key);
  try {
    await fetchView(path);
  } catch (error) {
    state.treeErrors.set(key, error);
  } finally {
    state.treeLoading.delete(key);
  }
  if (generation === treeGeneration && state.diagramKind !== 'flow') renderRootNav();
}
function retryTree(path) {
  const key = treePathKey(path);
  viewCache.delete(key);
  state.treeViews.delete(key);
  state.treeErrors.delete(key);
  void loadTreeBranch(path);
  renderRootNav();
}
function loadedTreeView(key) {
  if (state.treeLoading.has(key) || state.treeErrors.has(key)) return null;
  const view = state.treeViews.get(key);
  return view ? { nodes: visibleNodes(view) } : null;
}
function renderTreeItem(path, node) {
  const chain = collapsePackageChain(node, path, loadedTreeView);
  const terminal = chain.nodes.at(-1);
  const childPath = terminal.leaf ? path : chain.path;
  const key = treePathKey(childPath);
  const expanded = !terminal.leaf && state.treeExpanded.has(key);
  const role = chain.nodes.length === 1 ? navRoleFor(node, path) : null;
  const module = node.buildModule;
  const label = chain.nodes.length > 1 ? chain.nodes.map(item => item.label).join('.') : (role?.label || node.label);
  const fullPath = chain.path.join('/');
  const item = element('div', 'tree-item');
  item.dataset.treePath = treePathKey(chain.path);
  item.setAttribute('role', 'treeitem');
  if (!terminal.leaf) item.setAttribute('aria-expanded', String(expanded));
  const row = element('div', 'tree-row');
  let toggle;
  if (terminal.leaf) {
    toggle = element('span', 'tree-toggle leaf');
  } else {
    toggle = button('', 'tree-toggle', event => { event.stopPropagation(); toggleTree(childPath); });
    toggle.append(icon('chevron'));
    toggle.setAttribute('aria-label', expanded ? `收起 ${label}` : `展开 ${label}`);
    toggle.setAttribute('aria-expanded', String(expanded));
    toggle.title = expanded ? `收起 ${label}` : `展开 ${label}`;
  }
  const link = button('', 'module-link', () => terminal.leaf ? navigate(path, 'explore', terminal.id) : navigate(childPath));
  const copy = element('span', 'module-copy');
  const secondary = chain.nodes.length > 1 ? fullPath : module ? `${buildModuleKind(module)} · ${buildModuleDirectory(module)}` : node.label;
  copy.append(element('span', 'module-name', label), element('small', '', secondary));
  link.append(icon(nodeIcon(terminal.leaf ? terminal : node)), copy, element('span', 'module-count', terminal.moduleCount));
  link.classList.toggle('build-module', Boolean(module) && chain.nodes.length === 1);
  const current = selectedNodeLocation();
  const mark = chainPresence(current.path, current.id, path, chain);
  if (mark) link.classList.add(mark);
  link.title = chain.nodes.length > 1 ? fullPath : (role?.label || (module ? `${buildModuleKind(module)} ${node.label}（${buildModuleDirectory(module)}）` : node.fullName));
  const copyPath = button('复制', 'tree-copy', event => {
    event.stopPropagation();
    const text = fullPath;
    const done = () => toast(`已复制 ${text}`);
    if (navigator.clipboard?.writeText) navigator.clipboard.writeText(text).then(done).catch(() => toast(text));
    else toast(text);
  });
  copyPath.title = `复制路径 ${fullPath}`;
  copyPath.setAttribute('aria-label', `复制路径 ${fullPath}`);
  row.append(toggle, link, copyPath);
  item.append(row);
  if (expanded) item.append(renderTreeChildren(childPath));
  return item;
}
function renderTreeChildren(path) {
  const key = treePathKey(path);
  const box = element('div', 'tree-children');
  box.setAttribute('role', 'group');
  if (state.treeLoading.has(key) || (!state.treeViews.has(key) && !state.treeErrors.has(key))) {
    box.append(element('div', 'tree-status', '正在读取…'));
    return box;
  }
  if (state.treeErrors.has(key)) {
    const line = element('div', 'tree-status error');
    line.append(document.createTextNode('读取失败 '));
    const retry = button('重试', '', event => { event.stopPropagation(); retryTree(path); });
    line.append(retry);
    box.append(line);
    return box;
  }
  const nodes = visibleNodes(state.treeViews.get(key));
  if (!nodes.length) {
    box.append(element('div', 'tree-status', '没有子模块'));
    return box;
  }
  for (const node of nodes) box.append(renderTreeItem(path, node));
  return box;
}
function scrollCurrentTreeNode() {
  const current = $('#module-nav .module-link.current') || $$('#module-nav .module-link.on-path').at(-1);
  current?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}
function renderRootNav() {
  if (state.diagramKind === 'flow' && state.flowNodes.length) { renderFlowNav(); return; }
  const view = state.rootView;
  if (!view) return;
  $('#nav-title').textContent = '模块导航';
  const roots = visibleNodes(view);
  $('#root-count').textContent = String(roots.length).padStart(2, '0');
  const nav = $('#module-nav');
  const scroll = nav.scrollTop;
  nav.classList.add('module-tree');
  nav.setAttribute('role', 'tree');
  nav.setAttribute('aria-label', '构建模块与源码目录');
  syncTreeToLocation();
  nav.replaceChildren();
  for (const node of roots) nav.append(renderTreeItem([], node));
  nav.scrollTop = scroll;
  scrollCurrentTreeNode();
}
function renderFlowNav() {
  // 数据流视图下导航列出图中节点，点击即在图上选中，不切回代码结构。
  $('#nav-title').textContent = '数据流节点';
  $('#root-count').textContent = String(state.flowNodes.length).padStart(2, '0');
  const nav = $('#module-nav');
  nav.classList.remove('module-tree');
  nav.removeAttribute('role');
  nav.removeAttribute('aria-label');
  nav.replaceChildren();
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
let layoutKey = '';
let layoutColumns = null;
function codeViewKey() {
  return `${state.path.join('/')}|${state.pinScope ? JSON.stringify(state.pinScope.targets) : ''}`;
}
function codeMapNodes() {
  const nodes = visibleNodes(state.view);
  return state.pinScope ? nodes : exploration.mapNodes(nodes);
}
function layoutIdentity(nodes) {
  return `${codeViewKey()}|${nodes.map(node => node.id).join('|')}`;
}
function layoutFor(nodes, edges) {
  // 根视图和内部视图都按依赖层排布；列数随画布宽度选择，几像素变化不改列。
  const viewport = $('#map-viewport');
  const key = layoutIdentity(nodes);
  const layout = layoutCodeMap({
    nodes: nodes.map(node => ({ id: node.id, layer: node.layer })),
    edges,
    viewport: { width: Math.max(320, viewport.clientWidth || 960), height: Math.max(240, viewport.clientHeight || 640) },
    previousColumns: key === layoutKey ? layoutColumns : null,
  });
  layoutKey = key;
  layoutColumns = layout.columns;
  return {
    positions: new Map(layout.positions.map(position => [position.id, position])),
    width: layout.width, height: layout.height, routeWidth: layout.routeWidth, columns: layout.columns,
  };
}
function routeEdge(from, to, width, index) {
  return layoutRouteEdge(from, to, state.world?.routeWidth || width, index);
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
  const nodes = codeMapNodes();
  $('#metric-groups').textContent = nodes.length;
  const layout = layoutFor(nodes, state.view.edges || []); state.world = layout; state.positions = layout.positions;
  world.dataset.columns = String(layout.columns);
  world.style.width = `${layout.width}px`; world.style.height = `${layout.height}px`;
  if (!nodes.length) { world.append(element('p', 'map-empty', state.view.nodes.length ? '当前范围的源码未在架构文档里提到，请先补充文档。' : '当前范围没有源码模块。可返回上层，或检查启动时指定的源码目录。')); applyZoom(); return; }
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
    const card = button('', `node${node.abstract ? ' abstract' : ''}${node.cycle ? ' cyclic' : ''}${node.buildModule ? ' build-module' : ''}`, () => { selectNode(node.id); focusSelectedNode(); });
    card.style.left = `${position.x}px`; card.style.top = `${position.y}px`; card.style.width = `${CARD.width}px`; card.style.height = `${CARD.height}px`; card.dataset.node = node.id;
    card.setAttribute('aria-label', `${titleFor(node)}，${node.moduleCount} 个源码模块${node.cycle ? '，含循环依赖' : ''}`);
    card.addEventListener('dblclick', () => enterNode(node));
    for (const event of ['pointerenter', 'focus']) card.addEventListener(event, () => { state.hovered = node.id; applyHighlight(); });
    for (const event of ['pointerleave', 'blur']) card.addEventListener(event, () => { if (state.hovered === node.id) { state.hovered = null; applyHighlight(); } });
    const heading = element('div', 'node-heading'); heading.append(icon(nodeIcon(node), 'node-symbol'), element('span', 'node-name', titleFor(node)));
    if (node.buildModule) heading.append(element('span', 'build-tag', BUILD_TOOLS[node.buildModule.buildTool] || '模块'));
    card.append(heading, element('p', 'node-description', cardText(descriptionFor(node)) || (node.leaf ? '源码文件 · 点击查看依赖与说明' :
      node.buildModule ? `${buildModuleKind(node.buildModule)} · ${node.moduleCount} 个源码模块 · 双击查看内部结构` : `${node.moduleCount} 个源码模块 · 双击查看内部结构`)));
    const bottom = element('div', 'node-bottom'); bottom.append(element('span', '', node.buildModule ? `${buildModuleDirectory(node.buildModule)} / ${node.moduleCount}` : `${node.label} / ${node.moduleCount}`));
    bottom.append(complexityBadge(node.complexity));
    if (roleFor(node)) bottom.append(statusBadge(roleFor(node).status));
    bottom.append(icon(node.leaf ? 'external' : 'arrow', 'node-arrow')); card.append(bottom); world.append(card);
  }
  exploration.decorateMap(world, layout.positions, nodes);
  applyHighlight();
  applyZoom();
}

function statusBadge(status) {
  if (!status) return document.createDocumentFragment();
  const kind = status === '已完成' ? 'complete' : status === '未完成' ? 'pending' : 'other';
  return element('span', `implementation-status ${kind}`, status);
}

function complexityLevel(value) {
  const limit = state.complexity.limit || 10;
  // 黄色只提示接近上限的函数；放得太宽会让大半个项目都变黄。
  return value > limit ? 'high' : value > limit * 0.8 ? 'warn' : 'ok';
}
function complexityBadge(metrics) {
  if (!state.showComplexity || !metrics?.count) return document.createDocumentFragment();
  const badge = element('span', `complexity-badge ${complexityLevel(metrics.max)}`, `CC ${metrics.max}`);
  badge.title = `最高圈复杂度 ${metrics.max} · 合计 ${metrics.total} · ${metrics.count} 个函数（上限 ${state.complexity.limit}）`;
  return badge;
}
function complexitySection(node) {
  const metrics = node.complexity;
  if (!state.showComplexity || !metrics?.count) return document.createDocumentFragment();
  const section = element('section', 'detail-section'), heading = element('h4', '', node.leaf ? '函数复杂度' : '最复杂的函数');
  heading.append(element('span', '', `最高 ${metrics.max} · 合计 ${metrics.total} · ${metrics.count} 个函数`)); section.append(heading);
  const list = element('div', 'relation-list');
  for (const fn of metrics.functions.slice(0, node.leaf ? undefined : 30)) {
    const item = button('', 'relation complexity-function', () => showSource(fn.module, fn.line));
    // 只显示类名与函数名；完整符号放在提示里。
    item.title = `${fn.symbol}${fn.line ? ` · 第 ${fn.line} 行` : ''}`;
    item.append(element('span', '', fn.symbol.split('(')[0].split('.').slice(-2).join('.')),
      element('span', `complexity-value ${complexityLevel(fn.complexity)}`, fn.complexity));
    list.append(item);
  }
  if (metrics.count > list.children.length) section.append(list, element('p', '', `另有 ${metrics.count - list.children.length} 个函数未列出`));
  else section.append(list);
  return section;
}
function renderComplexityToggle() {
  const control = $('#complexity-toggle'), { status, error, max, limit, count } = state.complexity;
  // crap 不支持的语言分析结果为空，没有可切换的内容。
  control.hidden = status === 'unavailable' || (status === 'ready' && !count);
  const label = status === 'running' ? '复杂度分析中…' : status === 'failed' ? '复杂度分析失败' : '复杂度';
  control.replaceChildren(icon(state.showComplexity ? 'eye' : 'eye-off'), element('span', '', label));
  control.classList.toggle('busy', status === 'running'); control.classList.toggle('failed', status === 'failed');
  control.setAttribute('aria-pressed', String(state.showComplexity));
  control.title = status === 'failed' ? `crap 复杂度分析失败：${error || '未知原因'}` :
    status === 'running' ? 'crap 正在后台分析函数复杂度' :
    `${state.showComplexity ? '隐藏' : '显示'}函数复杂度（项目最高 ${max ?? '—'}，上限 ${limit}）`;
}
function toggleComplexity() {
  if (state.complexity.status === 'failed') { toast($('#complexity-toggle').title); return; }
  state.showComplexity = !state.showComplexity;
  localStorage.setItem('arch-view.show-complexity', state.showComplexity ? 'on' : 'off');
  renderComplexityToggle();
  if (state.diagramKind === 'code') { renderMap(); applyZoom(); renderDetail(); }
}
let complexityTimer;
async function watchComplexity() {
  // crap 在后台运行，地图先照常显示；分析完成后只刷新当前层的数据。
  clearTimeout(complexityTimer);
  try { state.complexity = await api('/api/complexity'); } catch { return; }
  renderComplexityToggle();
  if (state.complexity.status === 'running') { complexityTimer = setTimeout(watchComplexity, 1500); return; }
  if (state.complexity.status !== 'ready' || !state.view) return;
  // 复杂度进入节点数据，缓存的各层视图全部过期。
  viewCache.clear();
  const request = state.request, scope = state.pinScope, path = [...state.path];
  const view = scope ? await fetchPinnedScopeView(scope, path) : await fetchView(path);
  if (request !== state.request || scope !== state.pinScope || path.join('/') !== state.path.join('/')) return;
  const selected = state.selected?.id;
  state.view = view; state.selected = view.nodes.find(node => node.id === selected) || null;
  if (state.diagramKind === 'code') { renderMap(); applyZoom(); renderDetail(); }
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
  const flow = state.diagramKind === 'flow', pinned = pinIsOpen();
  $('#pin-open').hidden = pinned;
  $('#flow-view').hidden = pinned;
  $('#map-back').disabled = flow || state.path.length <= state.navigationPath.length;
  renderReturnButton();
  $('#edge-scope-label').hidden = flow;
  $('#analysis-state').textContent = flow ? '文档数据流' : '源码静态依赖';
  $('#flow-view').disabled = !state.flows.length;
  for (const [id, active] of [['flow-view', flow], ['code-view', !flow]]) {
    $('#' + id).classList.toggle('active', active); $('#' + id).setAttribute('aria-pressed', String(active));
  }
  $('#map-provenance').textContent = flow ? `数据流来自 ${state.document.path} · 状态由文档手动标记` : '连线来自源码分析 · 状态由文档手动标记';
  $('#map-title').textContent = flow ? state.flows[state.flowIndex].title : codeMapTitle();
  $('#cycle-filter').disabled = flow;
  $('#map-help').textContent = flow ? '拖动空白处移动画布 · 单击看详情 · 点击画布外恢复全局 · Ctrl + 滚轮缩放' : '单击看详情 · 双击进入 · 点击画布外恢复全局 · Ctrl + 滚轮缩放';
  $('#cycles-panel').hidden = flow || !state.cyclesOnly;
  $('#map-menu .legend').hidden = flow;
  $('.flow-legend').hidden = !flow;
  const select = $('#flow-select'); select.replaceChildren(); select.hidden = pinned || !flow || state.flows.length < 2;
  $('label[for="flow-select"]').hidden = select.hidden;
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

function selectFlowNode(node) {
  state.detailCollapsed = false; state.detailForced = false; state.selectedFlow = node;
  if (state.detailPinned) captureHeldDetail();
  renderDetail(); applyHighlight();
}

async function enterFlowModule(node) {
  const path = node.moduleKey.split('/'); const id = path.pop();
  try {
    const view = await fetchView(path);
    const target = view.nodes.find(n => n.id === id);
    if (!target) { toast('此文档节点在当前代码结构中没有对应模块'); return; }
    if (target.leaf) showSource(target.sourceModule);
    else {
      rememberReturn(node);
      await navigate([...path, id], 'explore', null, { keepReturn: true });
    }
  } catch (error) { toast(error.message); }
}

function renderFlowDetail() {
  const panel = $('#detail-panel');
  const node = state.selectedFlow || (state.detailPinned && state.heldDetail?.kind === 'flow' ? state.heldDetail.node : null);
  if (!node) {
    renderEmptyDetail(panel, true); finishDetail(panel); return;
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
  finishDetail(panel);
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
function captureHeldDetail() {
  if (state.diagramKind === 'flow' && state.selectedFlow) state.heldDetail = { kind: 'flow', node: state.selectedFlow };
  else if (state.selected) state.heldDetail = { kind: 'node', node: state.selected, path: state.path.join('/') };
}
function detailDockOpen() {
  if (state.detailCollapsed) return false;
  return Boolean(state.detailForced || state.detailPinned || exploration.traceActive() || state.selected || state.selectedFlow);
}
function syncDetailDock() {
  const open = detailDockOpen();
  const grid = $('.view-grid');
  grid.classList.toggle('detail-collapsed', !open);
  $('#detail-panel').hidden = !open;
  const showTab = !open && document.body.classList.contains('exploring') && !document.body.classList.contains('map-fullscreen') && !document.body.querySelector('.pin-expanded');
  $('#detail-open').hidden = !showTab;
}
function detailChrome() {
  const bar = element('div', 'detail-pin-bar');
  const collapse = button('收起', 'button small', event => {
    event.stopPropagation();
    state.detailCollapsed = true; state.detailForced = false; syncDetailDock();
  });
  collapse.id = 'detail-collapse';
  const hasContent = Boolean(state.selected || state.selectedFlow || state.heldDetail || exploration.traceActive());
  if (hasContent) {
    const pin = button(state.detailPinned ? '取消固定' : '固定详情', 'button small', event => {
      event.stopPropagation();
      state.detailPinned = !state.detailPinned;
      if (state.detailPinned) captureHeldDetail();
      else state.heldDetail = null;
      renderDetail();
    });
    pin.id = 'detail-pin';
    pin.title = state.detailPinned ? '取消固定后，点击空白会收起详情' : '固定后，取消选择仍保留这块详情';
    bar.append(pin);
  }
  bar.append(collapse);
  return bar;
}
function finishDetail(panel) {
  panel.prepend(detailChrome());
  syncDetailDock();
}
function selectNode(id) {
  state.detailCollapsed = false; state.detailForced = false;
  state.selected = state.view.nodes.find(node => node.id === id) || null;
  if (state.detailPinned) captureHeldDetail();
  renderDetail(); applyHighlight(); renderRootNav(); updatePinnedLocation();
}
function clearMapSelection() {
  if (!state.selected && !state.hovered && !state.selectedFlow && !state.detailForced) return;
  if (state.detailPinned) captureHeldDetail();
  state.selected = null; state.hovered = null; state.selectedFlow = null;
  if (!state.detailPinned && !exploration.traceActive()) { state.detailForced = false; state.detailCollapsed = true; }
  renderDetail(); applyHighlight(); renderRootNav(); updatePinnedLocation();
}
function clickOutsideCanvas(event) {
  const target = event.target;
  if (!(target instanceof Element)) return;
  const menu = $('#map-menu');
  if (menu && !menu.hidden && !target.closest('#map-menu, #map-more')) {
    menu.hidden = true; $('#map-more').setAttribute('aria-expanded', 'false');
  }
  if (target.closest('#map-viewport, #map-return, #map-menu, #map-more, #pinned-architecture, #pin-open, #drawer, #drawer-backdrop, #toast, #nav-float, #nav-open, #detail-open')) return;
  if (target.closest('.status-choices, .detail-actions, .detail-pin-bar, .relation-list, .module-link, .cycle-line, .coverage-modules, .trace-steps, .ai-section')) return;
  clearMapSelection();
}

async function locateModule(module, options = {}) {
  const request = ++state.request;
  $('#analysis-state').textContent = '正在定位模块…';
  try {
    const location = await api('/api/locate', { module });
    if (request !== state.request) return false;
    return await navigate(location.path, 'explore', location.nodeId, options);
  } catch (error) {
    if (request === state.request) { $('#analysis-state').textContent = '源码静态依赖'; toast(error.message); }
    return false;
  }
}

async function locatePinnedTarget(target) {
  if (target && typeof target === 'object' && target.module) return locateModule(target.module);
  const rawPath = target && typeof target === 'object' ? target.path : target;
  const path = Array.isArray(rawPath) ? [...rawPath] : String(rawPath || '').split('/').filter(Boolean);
  if (!path.length) return navigate([], 'explore', null, { expand: false });
  // 文档角色和根层节点给的是包路径，不能交给只认识完整源码模块名的 /api/locate。
  const request = ++state.request;
  try {
    const parent = path.slice(0, -1), id = path.at(-1);
    const view = await fetchView(parent);
    if (request !== state.request) return false;
    const node = view.nodes.find(item => item.id === id)
      || view.nodes.find(item => item.id.replace(/\|file$/, '') === id);
    if (node) {
      if (node.leaf) return locateModule(node.sourceModule);
      return navigate(path, 'explore', null, { expand: false });
    }
    if (typeof target === 'string') return locateModule(target);
    toast('此架构节点在当前代码结构中没有对应模块');
  } catch (error) { if (request === state.request) toast(error.message); }
  return false;
}

// 多范围只组合真实视图；保留各节点的原始层级，供源码、树导航和下钻使用。
function pathContains(parent, child) {
  return parent.length <= child.length && parent.every((part, index) => part === child[index]);
}
async function resolvePinnedScope(target) {
  if (target.module) {
    const location = await api('/api/locate', { module: target.module });
    return { path: location.path, nodeId: location.nodeId };
  }
  const path = [...target.path];
  if (!path.length) return { path };
  const parent = path.slice(0, -1), id = path.at(-1);
  const view = await fetchView(parent);
  const node = view.nodes.find(item => item.id === id) || view.nodes.find(item => item.id.replace(/\|file$/, '') === id);
  if (!node) throw new Error('此架构节点在当前代码结构中没有对应模块');
  return node.leaf ? { path: parent, nodeId: node.id } : { path };
}
async function locatePinnedTargets(destination) {
  const request = ++state.request;
  $('#analysis-state').textContent = '正在定位模块…';
  try {
    const scopes = await Promise.all(destination.targets.map(resolvePinnedScope));
    if (request !== state.request) return false;
    // 包范围覆盖其后代；同一路径的文件与包（|file）仍是两个独立范围。
    const targets = scopes.filter((scope, index) => !scopes.some((other, otherIndex) => {
      if (otherIndex === index) return false;
      if (!other.nodeId && pathContains(other.path, scope.path)) {
        return other.path.length < scope.path.length || Boolean(scope.nodeId) || otherIndex < index;
      }
      return other.nodeId === scope.nodeId && other.path.join('/') === scope.path.join('/') && otherIndex < index;
    }));
    const common = [...targets[0].path];
    for (const target of targets) while (!pathContains(common, target.path)) common.pop();
    return navigate(common, 'explore', null, { expand: false, pinScope: { targets, label: destination.label } });
  } catch (error) {
    if (request === state.request) { $('#analysis-state').textContent = '源码静态依赖'; toast(error.message); }
    return false;
  }
}
async function fetchPinnedScopeView(scope, common) {
  const entries = await Promise.all(scope.targets.map(async target => ({ ...target, view: await fetchView(target.path) })));
  const nodes = [], owners = new Map(), dependencyTargets = new Map();
  const relativeId = (path, id) => [...path, id].slice(common.length).join('/');
  for (const entry of entries) {
    const selected = entry.nodeId ? entry.view.nodes.filter(node => node.id === entry.nodeId) : entry.view.nodes;
    if (entry.nodeId && !selected.length) throw new Error('此架构节点在当前代码结构中没有对应模块');
    for (const node of selected) {
      const id = relativeId(entry.path, node.id);
      nodes.push({ ...node, id, label: [...entry.path.slice(common.length), node.label].join('/'), scopePath: entry.path, scopeId: node.id });
      for (const member of node.members) owners.set(member, id);
    }
  }
  const ids = new Set(nodes.map(node => node.id)), pairs = new Map();
  // 相同父层的多个叶子共用一次边投影，避免重复计数。
  const views = new Map(entries.map(entry => [entry.path.join('/'), entry]));
  for (const entry of views.values()) {
    const local = new Map(entry.view.nodes.map(node => [node.id, node]));
    const endpoint = id => {
      const node = local.get(id);
      if (!node) return owners.get(id) || id;
      const mapped = new Set(node.members.map(member => owners.get(member)));
      if (mapped.size === 1 && !mapped.has(undefined)) return mapped.values().next().value;
      // 较宽的包聚合不能猜分到多个目标；对应窄范围的真实外部边会补齐这些关系。
      if ([...mapped].some(owner => owner !== undefined)) return null;
      const key = node.sourceModule || relativeId(entry.path, node.id);
      dependencyTargets.set(key, { path: entry.path, node });
      return key;
    };
    const projected = new Map();
    for (const edge of entry.view.displayEdges) {
      const from = endpoint(edge.from), to = endpoint(edge.to);
      if (from === null || to === null || from === to || (!ids.has(from) && !ids.has(to))) continue;
      const key = JSON.stringify([from, to]);
      const previous = projected.get(key);
      projected.set(key, { ...edge, from, to, count: (previous?.count || 0) + edge.count,
        type: previous?.type === 'abstract' ? 'abstract' : edge.type });
    }
    for (const [key, edge] of projected) {
      const previous = pairs.get(key);
      // 同一条跨范围依赖会在两侧 API 出现，取完整投影的计数，不把它相加两遍。
      pairs.set(key, { ...edge, count: Math.max(previous?.count || 0, edge.count),
        type: previous?.type === 'abstract' ? 'abstract' : edge.type });
    }
  }
  const displayEdges = [...pairs.values()];
  return { path: common, nodes, displayEdges, edges: displayEdges.filter(edge => ids.has(edge.from) && ids.has(edge.to)),
    cycles: [...new Set(entries.filter(entry => !entry.nodeId).flatMap(entry => entry.view.cycles))],
    documents: [...new Map(entries.flatMap(entry => entry.view.documents).map(doc => [doc.id, doc])).values()],
    dependencyTargets, pinnedScope: true };
}

function pinOcclusion() {
  const pin = $('#pinned-architecture');
  const panel = pin?.closest('.map-panel');
  if (!pin || pin.hidden || panel?.classList.contains('pin-expanded')) return null;
  const view = $('#map-viewport').getBoundingClientRect();
  const box = pin.getBoundingClientRect();
  const left = Math.max(view.left, box.left), right = Math.min(view.right, box.right);
  const top = Math.max(view.top, box.top), bottom = Math.min(view.bottom, box.bottom);
  if (right - left < 8 || bottom - top < 8) return null;
  return { left, top, right, bottom };
}
function nudgeMap(dx, dy) {
  if (!dx && !dy) return;
  const viewport = $('#map-viewport');
  const maxLeft = Math.max(0, viewport.scrollWidth - viewport.clientWidth);
  const maxTop = Math.max(0, viewport.scrollHeight - viewport.clientHeight);
  const nextLeft = Math.min(maxLeft, Math.max(0, viewport.scrollLeft + dx));
  const nextTop = Math.min(maxTop, Math.max(0, viewport.scrollTop + dy));
  const usedX = nextLeft - viewport.scrollLeft, usedY = nextTop - viewport.scrollTop;
  viewport.scrollLeft = nextLeft; viewport.scrollTop = nextTop;
  const restX = dx - usedX, restY = dy - usedY;
  if (Math.abs(restX) > 0.5 || Math.abs(restY) > 0.5) {
    state.pan.x -= restX; state.pan.y -= restY; applyZoom();
  }
}
function focusSelectedNode() {
  const card = $$('button.node').find(el => el.dataset.node === state.selected?.id);
  if (!card) return;
  const viewport = $('#map-viewport');
  // 只把浮层矩形当遮挡，卡片仍可经过其余区域；滚动不够时再平移。
  const delta = scrollToReveal(card.getBoundingClientRect(), viewport.getBoundingClientRect(), pinOcclusion(), 16);
  nudgeMap(delta.dx, delta.dy);
  card.focus({ preventScroll: true });
  mainCamera = captureMapCamera();
  rememberLevelCamera();
}
function enterNode(node) {
  if (node.leaf) showSource(node.sourceModule);
  else navigate([...(node.scopePath || state.path), (node.scopeId || node.id).replace(/\|file$/, '')]);
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
  // 悬停也会触发高亮，节点和连线按编号一次建表，避免大目录下逐个重查页面。
  const cards = new Map($$('button.node', $('#map-world')).map(el => [el.dataset.node, el]));
  const cyclic = new Set(state.view.nodes.filter(node => node.cycle).map(node => node.id));
  const showEdges = $('#show-edges').checked, allEdges = $('#edge-scope').value === 'all';
  for (const node of state.view.nodes) {
    const el = cards.get(node.id); if (!el) continue;
    const matched = !query || `${node.label} ${titleFor(node)} ${node.fullName} ${node.members.join(' ')} ${descriptionFor(node)} ${roleFor(node)?.status || ''}`.toLowerCase().includes(query);
    el.classList.toggle('selected', node.id === selected); el.classList.toggle('matched', Boolean(query && matched));
    el.classList.toggle('dimmed', !matched || (focus && !connected.has(node.id)) || (state.cyclesOnly && !node.cycle));
    el.setAttribute('aria-pressed', String(node.id === selected));
  }
  for (const edge of $$('.edge,.edge-label')) {
    const active = edge.dataset.from === focus || edge.dataset.to === focus;
    const incoming = edge.dataset.to === focus;
    const cycle = state.cyclesOnly && cyclic.has(edge.dataset.from) && cyclic.has(edge.dataset.to);
    edge.classList.toggle('active', Boolean(focus && active)); edge.classList.toggle('incoming', Boolean(focus && incoming));
    edge.classList.toggle('faded', Boolean(focus && !active));
    edge.style.display = showEdges && (allEdges || active || cycle) ? '' : 'none';
    if (edge.tagName.toLowerCase() === 'path') edge.setAttribute('marker-end', `url(#${focus && active ? incoming ? 'arrow-incoming' : 'arrow-active' : 'arrow'})`);
  }
  $('#map-help').textContent = $('#edge-scope').value === 'focus' ? '指向预览 · 点击固定 · 拖动空白处移动画布 · 点击画布外恢复全局 · 绿色为依赖，橙色为被依赖' : '拖动空白处移动画布 · 点击画布外恢复全局 · 点击突出上下游 · Ctrl + 滚轮缩放';
}
function relationItem(id, count, incoming, label) {
  const node = state.view.nodes.find(n => n.id === id);
  const dependency = state.view.dependencyTargets?.get(id);
  const item = button('', `relation${incoming ? ' incoming' : ''}`, () => {
    rememberReturn();
    if (node) { selectNode(id); focusSelectedNode(); }
    else if (dependency && !dependency.node.leaf) navigate([...dependency.path, dependency.node.id], 'explore', null, { keepReturn: true });
    else locateModule(dependency?.node.sourceModule || id, { keepReturn: true });
  });
  item.title = node ? '定位这个模块' : `${id}\n跳转到这个模块所在的软件包，并选中它`;
  item.append(element('span', '', node ? titleFor(node) : label));
  if (count > 1) item.append(element('span', 'relation-count', `×${count}`));
  item.append(icon('arrow')); return item;
}
// 视图外的模块按所在软件包分组，包名去掉与当前模块共同的前缀；同视图模块不分组。
function relationGroups(current, ids) {
  const base = String(current.sourceModule || current.fullName || '').split('.');
  const groups = new Map();
  for (const id of ids) {
    const parts = id.split('.'); let shared = 0;
    while (shared < parts.length - 1 && parts[shared] === base[shared]) shared += 1;
    const key = parts.slice(shared, -1).join('.');
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push({ id, label: parts.at(-1) });
  }
  return [...groups].sort(([a], [b]) => a.localeCompare(b));
}
function relationSection(title, edges, incoming, current = state.selected) {
  const section = element('section', 'detail-section'), heading = element('h4', '', title); heading.append(element('span', '', edges.length)); section.append(heading);
  const list = element('div', 'relation-list'), counts = new Map();
  for (const edge of edges) { const id = incoming ? edge.from : edge.to; counts.set(id, (counts.get(id) || 0) + edge.count); }
  const local = [...counts.keys()].filter(id => state.view.nodes.some(n => n.id === id));
  local.forEach(id => list.append(relationItem(id, counts.get(id), incoming)));
  const expanded = counts.size <= 8;
  for (const [key, members] of relationGroups(current, [...counts.keys()].filter(id => !local.includes(id)))) {
    if (!key || members.length === 1) {
      members.forEach(({ id, label }) => list.append(relationItem(id, counts.get(id), incoming, key ? `${key}.${label}` : label))); continue;
    }
    const group = element('details', `relation-group${incoming ? ' incoming' : ''}`); group.open = expanded;
    const summary = element('summary', '', key); summary.title = key; summary.append(element('span', 'relation-count', members.length));
    const items = element('div', 'relation-group-items');
    members.forEach(({ id, label }) => items.append(relationItem(id, counts.get(id), incoming, label)));
    group.append(summary, items);
    list.append(group);
  }
  if (!edges.length) list.append(element('p', '', '当前范围无此类依赖')); section.append(list); return section;
}
let traceDockSeen = false;
function renderDetail() {
  const tracing = exploration.traceActive();
  if (tracing && !traceDockSeen) state.detailCollapsed = false;
  traceDockSeen = tracing;
  if (state.diagramKind === 'flow') { renderFlowDetail(); return; }
  const panel = $('#detail-panel');
  const node = state.selected || (state.detailPinned && state.heldDetail?.kind === 'node' && state.heldDetail.path === state.path.join('/') ? state.heldDetail.node : null);
  // 查看链路时右侧固定显示步骤；点步骤会在图上定位模块，关闭链路后恢复模块详情。
  if (tracing) { exploration.renderTraceDetail(panel); finishDetail(panel); return; }
  if (!node) {
    renderEmptyDetail(panel); finishDetail(panel); return;
  }
  panel.replaceChildren(element('div', 'eyebrow', node.leaf ? '源码文件' : node.buildModule ? buildModuleKind(node.buildModule) : '模块详情'));
  panel.append(element('h3', '', titleFor(node)), element('div', 'detail-id', node.sourcePath || node.fullName));
  if (node.buildModule) panel.append(element('div', 'build-module-info', `构建目录：${buildModuleDirectory(node.buildModule)} · 按目录中的 ${node.buildModule.buildTool === 'gradle' ? 'build.gradle' : node.buildModule.buildTool === 'maven' ? 'pom.xml' : '构建文件'} 识别`));
  const role = roleFor(node);
  if (role) panel.append(statusChoices(role));
  if (node.cycle) panel.append(element('span', 'cycle-badge', '包含循环依赖'));
  const description = element('div', 'detail-description');
  // 文档角色的职责已是纯文本；源码文件头说明按 Markdown 渲染。
  description.append(!role?.summary && node.description ? richText(node.description) :
    element('p', '', descriptionFor(node) || (node.leaf ? '该文件没有可提取的文件头说明。' : `包含 ${node.moduleCount} 个源码模块，可以进入查看内部结构。`)));
  if (descriptionFor(node)) {
    const provenance = role?.summary ? `来自 ${state.document.path} · 实现状态由维护者标注` :
      node.descriptionFromOnlyFile ? '来自目录中唯一源码文件的说明' : '来自文件头注释 / 模块文档字符串';
    description.append(element('div', 'detail-provenance', provenance));
  }
  panel.append(description);
  const actions = element('div', 'detail-actions'); actions.append(button(node.leaf ? '查看源码 ↗' : '查看内部结构 ↘', 'button primary', () => enterNode(node))); panel.append(actions);
  panel.append(exploration.aiSection(node));
  if (role) panel.append(subsystemDetails(role));
  panel.append(complexitySection(node));
  const edges = state.view.displayEdges;
  panel.append(relationSection('依赖这些模块 →', edges.filter(e => e.from === node.id), false, node), relationSection('← 被这些模块依赖', edges.filter(e => e.to === node.id), true, node));
  if (!node.leaf) {
    const members = element('section', 'detail-section'); members.append(element('h4', '', `内部源码 · ${node.moduleCount}`));
    const list = element('ul', 'member-list'); node.members.slice(0, 10).forEach(member => list.append(element('li', '', member))); members.append(list);
    if (node.members.length > 10) members.append(element('p', '', `其余 ${node.members.length - 10} 个模块，进入内部查看`)); panel.append(members);
  }
  finishDetail(panel);
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
  mainCamera = captureMapCamera();
}
function rememberLevelCamera() {
  if (!state.view || state.diagramKind !== 'code') return;
  state.levelCameras.set(codeViewKey(), captureMapCamera());
}
function zoomBy(delta) { state.zoom = Math.min(1.8, Math.max(.15, state.zoom + delta)); applyZoom(); rememberLevelCamera(); }
function readableMap() {
  state.zoom = 1; state.pan = { x: 0, y: 0 }; applyZoom();
  const viewport = $('#map-viewport');
  const width = state.world.width * state.zoom, height = state.world.height * state.zoom;
  state.pan = { x: Math.max(0, (viewport.clientWidth - width) / 2), y: Math.max(0, (viewport.clientHeight - height) / 2) };
  applyZoom();
  viewport.scrollTo(width > viewport.clientWidth ? Math.max(0, (width - viewport.clientWidth) / 2) : 0, 0);
  mainCamera = captureMapCamera(); rememberLevelCamera();
}
function presentCurrentMap() {
  if (state.diagramKind === 'flow') { fitMap(); return; }
  const saved = state.levelCameras.get(codeViewKey());
  if (saved) restoreMapCamera(saved);
  else readableMap();
}
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
  mainCamera = captureMapCamera(); rememberLevelCamera();
}

function captureMapCamera() {
  const viewport = $('#map-viewport');
  return { zoom: state.zoom, pan: { ...state.pan }, left: viewport.scrollLeft, top: viewport.scrollTop };
}
function restoreMapCamera(camera) {
  state.zoom = camera.zoom; state.pan = { ...camera.pan }; applyZoom();
  $('#map-viewport').scrollTo(camera.left, camera.top);
  mainCamera = { ...camera, pan: { ...camera.pan } }; rememberLevelCamera();
}

function setMapFullscreen(active) {
  const grid = $('.view-grid'), control = $('#map-fullscreen');
  if (grid.classList.contains('is-fullscreen') === active) return;
  const key = `${state.diagramKind}:${codeViewKey()}`;
  if (active) state.fullscreenCamera = { key, ...captureMapCamera() };
  else { state.restoreCamera = state.fullscreenCamera?.key === key ? state.fullscreenCamera : null; state.fullscreenCamera = null; }
  grid.classList.toggle('is-fullscreen', active); document.body.classList.toggle('map-fullscreen', active);
  for (const el of $$('.topbar,#overview-page,#nav-float,#nav-open')) el.inert = active;
  applyNavFloat();
  control.setAttribute('aria-label', active ? '退出全屏' : '全屏查看'); control.title = active ? '退出全屏（Esc）' : '全屏查看';
  control.setAttribute('aria-pressed', String(active)); control.replaceChildren(icon(active ? 'collapse' : 'expand'));
  control.focus({ preventScroll: true });
  syncDetailDock();
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
  rememberLevelCamera();
}
let drawerReturnFocus;
function openDrawer(title, path, kind) {
  // 文档、AI 等面板会覆盖源码；同时废弃尚未完成的源码请求。
  if (kind !== '源码文件') { ++state.sourceRequest; state.sourceModule = null; updatePinnedLocation(); }
  drawerReturnFocus = document.activeElement;
  $('#drawer-title').textContent = title; $('#drawer-path').textContent = path;
  $('#drawer-kind').textContent = kind; $('#drawer-content').replaceChildren();
  $('#drawer').hidden = false; $('#drawer-backdrop').hidden = false;
  document.body.style.overflow = 'hidden'; $('#drawer-close').focus();
}
function closeDrawer() {
  ++state.sourceRequest; state.sourceModule = null;
  $('#drawer').hidden = true; $('#drawer-backdrop').hidden = true; document.body.style.overflow = '';
  updatePinnedLocation(); drawerReturnFocus?.focus();
}
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
async function showSource(module, line) {
  if (!module) { toast('该模块没有可用的源码路径'); return; }
  const request = ++state.sourceRequest;
  try {
    const source = await api('/api/source', { module }); if (request !== state.sourceRequest) return;
    openDrawer(module.split('.').at(-1), source.path, '源码文件');
    if (source.description) $('#drawer-content').append(richText(source.description, 'source-description'));
    const list = element('ol', 'source-list'); source.content.split('\n').forEach(line => list.append(element('li', '', line || ' '))); $('#drawer-content').append(list);
    const target = line && list.children[line - 1];
    if (target) { target.classList.add('source-target'); target.scrollIntoView({ block: 'center' }); }
    state.sourceModule = module; updatePinnedLocation();
  } catch (error) { toast(error.message); }
}
async function reanalyze() {
  const control = $('#reanalyze'); control.disabled = true; control.classList.add('busy'); control.replaceChildren(icon('refresh'), element('span', '', '分析中…'));
  try {
    state.project = await api('/api/reanalyze', {}, 'POST'); viewCache.clear(); resetTreeCaches(); updateProject();
    state.complexity = state.project.complexity; renderComplexityToggle();
    state.rootView = await fetchView([]);
    loadPinnedArchitecture(); updatePinnedLocation();
    state.navigationView = null; state.navigationPath = [];
    const doc = state.project.documents.find(d => d.id === state.document?.id) || state.project.documents.find(d => d.scope === 'project');
    try { await loadDocument(doc?.id || ''); }
    catch (error) { await loadDocument(''); toast(`说明文档读取失败：${error.message}`); }
    let path = state.path;
    if (path.length && !(await fetchView(path)).nodes.length) path = [];
    await navigate(path, state.mode); toast('架构与项目说明已更新'); watchComplexity(); exploration.loadTraces();
  } catch (error) { toast(error.message); }
  finally { control.disabled = false; control.classList.remove('busy'); control.replaceChildren(icon('refresh'), element('span', '', '重新分析')); }
}
$$('[data-icon]').forEach(el => el.replaceChildren(icon(el.dataset.icon)));
applyNavFloat();
$('#brand-home').addEventListener('click', event => { event.preventDefault(); showOverview(); });
$('#overview-nav').addEventListener('click', showOverview);
$('#explore-nav').addEventListener('click', showExplore);
$('#start-explore').addEventListener('click', showExplore);
$('#nav-close').addEventListener('click', () => setNavFloat(false));
$('#nav-open').addEventListener('click', () => setNavFloat(true));
$('#document-select').addEventListener('change', async event => { try { await loadDocument(event.target.value); } catch (error) { toast(error.message); } });
$('#document-open').addEventListener('click', () => showDocument());
$('#search').addEventListener('input', applyHighlight);
for (const [id, kind] of [['code-view', 'code'], ['flow-view', 'flow']]) $('#' + id).addEventListener('click', () => {
  state.diagramKind = kind; state.selected = null; state.selectedFlow = null; state.cyclesOnly = false;
  state.detailPinned = false; state.heldDetail = null; state.detailForced = false; state.detailCollapsed = true;
  $('#cycle-filter').classList.remove('active'); renderMap(); renderDetail(); renderRootNav(); presentCurrentMap(); updatePinnedLocation();
});
$('#map-more').addEventListener('click', () => {
  const menu = $('#map-menu');
  menu.hidden = !menu.hidden;
  $('#map-more').setAttribute('aria-expanded', String(!menu.hidden));
});
$('#detail-open').addEventListener('click', () => {
  state.detailCollapsed = false; state.detailForced = true; renderDetail();
});
$('#flow-select').addEventListener('change', event => { state.flowIndex = Number(event.target.value); state.selectedFlow = null; renderMap(); renderDetail(); });
$('#show-edges').addEventListener('change', applyHighlight);
$('#edge-scope').addEventListener('change', applyHighlight);
$('#complexity-toggle').addEventListener('click', toggleComplexity);
$('#map-back').addEventListener('click', navigateUp);
$('#map-return').addEventListener('click', returnBack);
$('#zoom-in').addEventListener('click', () => zoomBy(.1)); $('#zoom-out').addEventListener('click', () => zoomBy(-.1)); $('#zoom-fit').addEventListener('click', fitMap);
$('#zoom-value').addEventListener('click', () => { readableMap(); if (state.selected && state.diagramKind === 'code') focusSelectedNode(); });
$('#map-fullscreen').addEventListener('click', toggleMapFullscreen);
$('#pin-open').addEventListener('click', () => pinnedArchitecture.open());
$('#map-viewport').addEventListener('pointerdown', beginCanvasDrag);
$('#map-viewport').addEventListener('pointermove', moveCanvasDrag);
$('#map-viewport').addEventListener('scroll', () => {
  const bounds = $('#map-viewport').getBoundingClientRect();
  // 浏览器会在尺寸改变时夹紧 scroll；先让 ResizeObserver 恢复改变前的 camera。
  if (bounds.width === observedViewportWidth && bounds.height === observedViewportHeight) { mainCamera = captureMapCamera(); rememberLevelCamera(); }
});
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
let observedFullscreen = false;
new ResizeObserver(() => {
  if (!state.view) return;
  const viewport = $('#map-viewport'), bounds = viewport.getBoundingClientRect();
  // pin 放大时主画布可能被隐藏；恢复后仍使用原来的布局和 camera。
  if (!bounds.width || !bounds.height) return;
  const fullscreen = $('.view-grid').classList.contains('is-fullscreen');
  const fullscreenChanged = fullscreen !== observedFullscreen;
  const widthDelta = Math.abs(bounds.width - observedViewportWidth), heightDelta = Math.abs(bounds.height - observedViewportHeight);
  if (!fullscreenChanged && widthDelta < 16 && heightDelta < 16) {
    observedViewportWidth = bounds.width; observedViewportHeight = bounds.height; return;
  }
  const camera = mainCamera || captureMapCamera();
  const previousColumns = layoutColumns;
  observedViewportWidth = bounds.width; observedViewportHeight = bounds.height; observedFullscreen = fullscreen;
  if (state.diagramKind === 'code') {
    const nodes = codeMapNodes();
    const preview = layoutCodeMap({
      nodes: nodes.map(node => ({ id: node.id, layer: node.layer })),
      edges: state.view.edges || [],
      viewport: { width: bounds.width, height: bounds.height },
      previousColumns: layoutIdentity(nodes) === layoutKey ? layoutColumns : null,
    });
    if (fullscreenChanged || preview.columns !== previousColumns) renderMap();
  }
  if (fullscreenChanged && state.restoreCamera) { restoreMapCamera(state.restoreCamera); state.restoreCamera = null; }
  else restoreMapCamera(camera);
  if (state.selected && state.diagramKind === 'code' && (fullscreenChanged || layoutColumns !== previousColumns)) focusSelectedNode();
}).observe($('#map-viewport'));
// AI 辅助探索（解释、上下文包、调用链路）放在独立模块，只借用这里的页面能力。
const exploration = createExploration({
  state, api, element, button, icon, richText, toast, openDrawer, showSource, navigate, fitMap, presentMap: presentCurrentMap, routeEdge, svgElement, $,
  focusModule: module => {
    const node = state.view?.nodes.find(item => item.members.includes(module));
    if (node) { selectNode(node.id); focusSelectedNode(); }
  },
  setDiagramKind: kind => { state.diagramKind = kind; },
  refresh: () => { if (!state.view) return; renderMap(); applyZoom(); renderDetail(); },
  refreshDetail: () => renderDetail(),
});
pinnedArchitecture = createPinnedArchitecture({
  host: $('#pinned-architecture'), api, toast, onVisibilityChange: onPinVisibilityChange,
  locateModule: destination => destination?.targets?.length > 1 ? locatePinnedTargets(destination) : locatePinnedTarget(destination?.targets?.[0] || destination),
});
(async function initialize() {
  try {
    state.project = await api('/api/project'); updateProject();
    $('#edge-scope').value = state.project.edgeScope || 'focus';
    state.complexity = state.project.complexity || state.complexity; renderComplexityToggle();
    state.rootView = await fetchView([]); loadPinnedArchitecture();
    const doc = state.project.documents.find(d => d.scope === 'project');
    try { await loadDocument(doc?.id || ''); }
    catch (error) { await loadDocument(''); toast(`说明文档读取失败：${error.message}`); }
    await navigate([], 'explore');
    exploration.loadTraces();
    if (state.complexity.status === 'running') watchComplexity();
  } catch (error) { $('#hero-summary').textContent = `加载失败：${error.message}`; toast(error.message); }
})();
