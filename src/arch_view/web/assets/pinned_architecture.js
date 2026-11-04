/* US-3：独立的项目级架构 pin；图形、相机、文档及偏好不依赖主代码视图。 */
import { parseArchitectureDocument, renderDataFlow } from './architecture_document.js';
import { flowLayers, layoutCodeMap, parseFlowEdges } from './map_layout.js';

const STORAGE_PREFIX = 'arch-view.pinned-architecture:';
const COMPACT_CARD = { width: 92, height: 32 };
const COMPACT_SPACING = { gapX: 10, gapY: 8, layerGap: 18, marginX: 12, marginTop: 14, marginBottom: 12, gutter: 16 };
const PIN_WIDTH = { min: 320, preferred: 360 };
const PIN_HEIGHT_MIN = 168;
let componentSequence = 0;

function shortLabel(label) {
  const raw = String(label || '').trim();
  const tail = (raw.split(/\s*[\uff1a:]\s*/).pop() || raw).replace(/\uff08[^\uff09]*\uff09/g, '').replace(/\([^)]*\)/g, '').replace(/\s+/g, ' ').trim();
  const name = tail || raw;
  return name.length > 8 ? `${name.slice(0, 7)}\u2026` : name;
}
function clampWidth(value, maximum = Number.MAX_SAFE_INTEGER) {
  return Math.min(maximum, Math.max(PIN_WIDTH.min, Math.round(Number(value) || PIN_WIDTH.preferred)));
}

function html(tag, className = '', text) {
  const element = document.createElement(tag);
  element.className = className;
  if (text != null) element.textContent = text;
  return element;
}
function svg(tag, attributes = {}, text) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  if (text != null) element.textContent = text;
  return element;
}
function parts(value) {
  return (Array.isArray(value) ? value : String(value || '').replace(/\|file$/, '').split(/[/.]/))
    .map(part => String(part).replace(/\|file$/, '').trim()).filter(Boolean);
}
function key(value) { return parts(value).join('/'); }
function contains(parent, child) { return Boolean(parent) && (child === parent || child.startsWith(parent + '/')); }
function readPreference(root) {
  try {
    const preference = JSON.parse(localStorage.getItem(STORAGE_PREFIX + root) || '{}');
    return preference && typeof preference === 'object' && !Array.isArray(preference) ? preference : {};
  } catch { return {}; }
}

export function createPinnedArchitecture({ host, api, locateModule, toast = () => {}, onVisibilityChange = () => {} }) {
  const panel = host.closest('.map-panel');
  const instance = ++componentSequence;
  let project = null, rootView = null, preference = {}, opened = true, expanded = false;
  let loadVersion = 0, renderVersion = 0, navigationVersion = 0;
  let flows = [], nodes = [], activeKey = '', selectedNode = null, entry = null;
  let location = { path: [], selected: null, sourceModule: null };
  let documentError = '', drawingError = '', loading = false;
  let camera = { x: 0, y: 0, zoom: 1 }, size = { width: 1, height: 1 };
  let splitCamera = null, drag = null, resizeDrag = null, suppressClick = false;
  let pendingFit = false, lastViewport = null, renderedKind = 'code';
  let compactDiagram = null, fullDiagram = null, ignoreResize = 0;
  let cachedModulePaths = [];
  const cameras = new Map(), modulePathCache = new Map(), modulesByPath = new Map(), rootOwners = new Map();

  host.classList.add('pinned-architecture');
  host.setAttribute('aria-label', '钉住的全局架构');
  const toolbar = html('div', 'pinned-toolbar');
  const title = html('strong', 'pinned-title', '全局架构');
  const source = html('select', 'pinned-source'); source.id = 'pin-source'; source.setAttribute('aria-label', '钉住架构来源');
  for (const [value, label] of [['flow', '项目数据流'], ['code', '根层代码架构']]) {
    const option = html('option', '', label); option.value = value; source.append(option);
  }
  const flowSelect = html('select', 'pinned-flow'); flowSelect.id = 'pin-flow'; flowSelect.setAttribute('aria-label', '项目数据流选择');
  const tools = html('div', 'pinned-tools');
  function control(id, label, action, text = label) {
    const button = html('button', 'pinned-button', text); button.id = id; button.type = 'button';
    button.title = label; button.setAttribute('aria-label', label); button.addEventListener('click', action); tools.append(button); return button;
  }
  control('pin-zoom-out', '缩小全局架构', () => zoomAt(camera.zoom / 1.25), '−');
  const zoomLabel = html('output', 'pinned-zoom', '100%'); zoomLabel.id = 'pin-zoom-value'; tools.append(zoomLabel);
  control('pin-zoom-in', '放大全局架构', () => zoomAt(camera.zoom * 1.25), '+');
  const fitButton = control('pin-fit', '适应完整图', () => fit(), '适应');
  const locateButton = control('pin-locate', '定位当前代码', () => focusCurrent(), '定位当前');
  const expandButton = control('pin-expand', '放大阅读全局架构', () => setExpanded(!expanded), '放大阅读');
  expandButton.setAttribute('aria-expanded', 'false');
  control('pin-close', '关闭全局架构', () => close(), '关闭');
  toolbar.append(title, source, flowSelect, tools);
  const provenance = html('div', 'pinned-provenance'); provenance.id = 'pin-provenance';
  const viewport = html('div', 'pinned-viewport'); viewport.id = 'pin-viewport'; viewport.tabIndex = 0;
  viewport.setAttribute('aria-label', '全局架构画布，可拖动平移，Ctrl 加滚轮缩放');
  const world = html('div', 'pinned-world'); world.id = 'pin-world';
  const notice = html('div', 'pinned-notice'); notice.id = 'pin-notice'; notice.setAttribute('role', 'status');
  const message = html('span', 'pinned-message');
  const retry = html('button', 'pinned-button', '重试'); retry.id = 'pin-retry'; retry.type = 'button';
  retry.addEventListener('click', () => { if (project) void load(project, rootView); });
  notice.append(message, retry); viewport.append(world, notice);
  const detail = html('div', 'pinned-detail'); detail.id = 'pin-detail';
  const copy = html('div', 'pinned-detail-copy');
  const detailTitle = html('strong', 'pinned-detail-title', '点击模块进入代码'); detailTitle.id = 'pin-detail-title';
  const detailSummary = html('span', 'pinned-detail-summary', '一次展示该模块覆盖的全部代码范围'); detailSummary.id = 'pin-detail-summary';
  copy.append(detailTitle, detailSummary);
  detail.append(copy);
  const footer = html('div', 'pinned-footer');
  const status = html('span', 'pinned-location'); status.id = 'pin-location'; status.setAttribute('role', 'status');
  const legend = html('span', 'pinned-legend');
  legend.append(html('span', 'pinned-current-key', '当前所在'), html('span', 'pinned-entry-key', '进入点'));
  footer.append(status, legend);
  const splitter = html('div', 'pinned-splitter'); splitter.id = 'pin-splitter'; splitter.tabIndex = 0;
  splitter.setAttribute('role', 'separator'); splitter.setAttribute('aria-label', '调整全局架构浮层宽度');
  splitter.setAttribute('aria-orientation', 'vertical'); splitter.setAttribute('aria-valuemin', String(PIN_WIDTH.min));
  const corner = html('button', 'pinned-resize-corner'); corner.id = 'pin-resize-corner'; corner.type = 'button';
  corner.setAttribute('aria-label', '调整全局架构浮层宽高');
  corner.title = '拖动左下角调整宽高；方向键微调';
  host.replaceChildren(toolbar, provenance, viewport, detail, footer, splitter, corner);

  function savePreference() {
    if (!project) return;
    try { localStorage.setItem(STORAGE_PREFIX + project.root, JSON.stringify(preference)); } catch { /* 隐私模式与存储配额不影响探索。 */ }
  }
  function notify() {
    try { onVisibilityChange(opened); } catch { /* 主视图回调不能阻碍 pin。 */ }
  }
  function applyVisibility() {
    host.hidden = !opened;
    panel?.classList.toggle('pin-open', opened);
    notify();
    if (opened) requestAnimationFrame(() => { if (opened && pendingFit) fit(); });
  }
  function open() { opened = true; preference.closed = false; savePreference(); applyVisibility(); }
  function close() {
    if (expanded) setExpanded(false);
    opened = false; preference.closed = true; savePreference(); applyVisibility();
  }
  function setWidth(px, persist = true) {
    preference.width = clampWidth(px, sizeLimits().width);
    layoutFrame();
    if (persist) savePreference();
  }
  function sizeLimits() {
    const bounds = host.parentElement?.getBoundingClientRect();
    return { width: Math.max(1, (bounds?.width || PIN_WIDTH.preferred + 16) - 16),
      height: Math.max(1, (bounds?.height || 520) - 16) };
  }
  function layoutFrame() {
    if (expanded) return;
    const bounds = host.parentElement?.getBoundingClientRect();
    if (!bounds?.width || !bounds?.height) return;
    const cramped = bounds.width < 560 || bounds.height < 320;
    host.classList.toggle('pinned-cramped', cramped);
    if (cramped) {
      host.style.setProperty('--pin-width', 'auto');
      host.style.setProperty('--pin-height', 'auto');
      return;
    }
    const limits = sizeLimits();
    const width = clampWidth(preference.width, limits.width);
    const defaultHeight = Math.max(PIN_HEIGHT_MIN, Math.min(bounds.height / 3, Math.round(width * 0.78)));
    const height = Math.min(limits.height, Math.max(PIN_HEIGHT_MIN, Number(preference.floatingHeight) || defaultHeight));
    host.style.setProperty('--pin-width', `${Math.round(width)}px`);
    host.style.setProperty('--pin-height', `${Math.round(height)}px`);
    splitter.setAttribute('aria-valuenow', String(width));
    splitter.setAttribute('aria-valuemax', String(limits.width));
  }
  function buildCompact(items, edges, layered) {
    const layers = layered ? null : flowLayers(items.map(item => item.id), edges);
    const layout = layoutCodeMap({
      nodes: items.map(item => ({ id: item.id, layer: layered ? Number(item.layer) || 0 : layers.get(item.id) || 0 })),
      edges,
      viewport: { width: Math.max(280, clampWidth(preference.width || PIN_WIDTH.preferred) - 28), height: 188 },
      card: COMPACT_CARD,
      spacing: COMPACT_SPACING,
    });
    const diagram = svg('svg', { viewBox: `0 0 ${layout.width} ${layout.height}`, width: layout.width, height: layout.height, class: 'pinned-compact-diagram' });
    diagram.__size = { width: layout.width, height: layout.height };
    const markerId = `pin-compact-arrow-${instance}`;
    const defs = svg('defs');
    const marker = svg('marker', { id: markerId, markerWidth: 8, markerHeight: 8, refX: 7, refY: 4, orient: 'auto', markerUnits: 'userSpaceOnUse' });
    marker.append(svg('path', { d: 'M0 0 L8 4 L0 8 Z', fill: '#91a89b' }));
    defs.append(marker); diagram.append(defs);
    for (const route of layout.routes) {
      const path = svg('path', { d: route.d, class: 'pinned-edge', 'data-from': route.from, 'data-to': route.to, 'marker-end': `url(#${markerId})` });
      const caption = route.label || (route.count > 1 ? String(route.count) : '');
      if (caption) path.append(svg('title', {}, caption));
      diagram.append(path);
    }
    const elements = new Map();
    for (const position of layout.positions) {
      const item = items.find(node => node.id === position.id);
      const full = item?.label || position.id;
      const group = svg('g', { transform: `translate(${position.x},${position.y})` });
      group.append(svg('title', {}, full));
      group.append(svg('rect', { width: COMPACT_CARD.width, height: COMPACT_CARD.height, rx: 6 }));
      group.append(svg('text', { x: 8, y: 21, class: 'pinned-compact-label' }, shortLabel(full)));
      diagram.append(group);
      elements.set(position.id, group);
    }
    return { diagram, elements };
  }
  function diagramSize(diagram) {
    if (diagram?.__size) return diagram.__size;
    const bounds = diagram?.viewBox?.baseVal;
    return { width: Math.max(1, bounds?.width || Number(diagram?.getAttribute('width')) || 1), height: Math.max(1, bounds?.height || Number(diagram?.getAttribute('height')) || 1) };
  }
  function mountActiveDiagram() {
    const diagram = !expanded && compactDiagram ? compactDiagram : fullDiagram;
    if (!diagram) return;
    const compact = diagram === compactDiagram;
    host.classList.toggle('pinned-compact', compact);
    for (const node of nodes) node.element = (compact ? node.compactElement : node.fullElement) || node.fullElement || node.element;
    size = diagramSize(diagram);
    diagram.style.maxWidth = 'none';
    diagram.setAttribute('width', String(size.width)); diagram.setAttribute('height', String(size.height));
    world.replaceChildren(diagram); world.style.width = `${size.width}px`; world.style.height = `${size.height}px`;
  }
  function settleCamera() {
    if (cameras.has(activeKey) && !expanded) { camera = { ...cameras.get(activeKey) }; pendingFit = false; applyCamera(); return; }
    if (!expanded && compactDiagram) { camera = { zoom: 1, x: 12, y: 8 }; pendingFit = false; applyCamera(); return; }
    pendingFit = true; if (opened) fit();
  }
  function modulePath(module) {
    if (!modulePathCache.has(module)) modulePathCache.set(module, parts(module).slice(Number(project?.namespaceRootDepth) || 0).join('/'));
    return modulePathCache.get(module);
  }
  function indexRootModules() {
    modulePathCache.clear(); modulesByPath.clear(); rootOwners.clear();
    const unique = new Set();
    for (const node of rootView?.nodes || []) for (const module of node.members || []) {
      const path = modulePath(module);
      if (!rootOwners.has(path)) rootOwners.set(path, new Set());
      rootOwners.get(path).add(node.id);
      if (unique.has(module)) continue;
      unique.add(module);
      if (!modulesByPath.has(path)) modulesByPath.set(path, []);
      modulesByPath.get(path).push(module);
    }
    cachedModulePaths = [...modulesByPath.keys()];
  }
  function validTarget(target) { return cachedModulePaths.some(path => contains(target, path)); }
  function targetFor(path) {
    const canonical = key(path), exact = modulesByPath.get(canonical) || [];
    if (!canonical || !validTarget(canonical)) return null;
    if (exact.length === 1 && !cachedModulePaths.some(member => member !== canonical && contains(canonical, member))) {
      return { key: canonical, target: { module: exact[0] } };
    }
    return { key: canonical, target: { path: parts(canonical) } };
  }
  function sourceKey() { return source.value === 'flow' && flows.length ? flows[flowIndex()]?.key || 'code' : 'code'; }
  function flowIndex() {
    const index = flows.findIndex(flow => flow.key === flowSelect.value);
    return index < 0 ? 0 : index;
  }
  function updateControls() {
    source.value = preference.source === 'code' || !flows.length ? 'code' : 'flow';
    source.options[0].disabled = !flows.length;
    flowSelect.replaceChildren();
    for (const flow of flows) {
      const option = html('option', '', flow.title); option.value = flow.key; flowSelect.append(option);
    }
    flowSelect.value = flows.some(flow => flow.key === preference.flow) ? preference.flow : flows[0]?.key || '';
    flowSelect.hidden = source.value !== 'flow' || flows.length < 2;
    host.dataset.source = source.value;
  }
  async function load(nextProject, nextRootView) {
    const version = ++loadVersion; ++renderVersion; ++navigationVersion;
    const changedProject = project?.root !== nextProject?.root;
    project = nextProject; rootView = nextRootView; indexRootModules();
    if (changedProject) {
      preference = readPreference(project?.root);
      cameras.clear(); activeKey = ''; entry = null; location = { path: [], selected: null, sourceModule: null };
    } else if (activeKey && !expanded) cameras.set(activeKey, { ...camera });
    if (expanded) setExpanded(false);
    opened = preference.closed !== true;
    delete preference.height;
    setWidth(preference.width, false); applyVisibility();
    flows = []; nodes = []; selectedNode = null; documentError = ''; drawingError = ''; loading = true;
    world.replaceChildren(); showDetail(); showNotice(); updateControls();
    // 只读取项目文档；包级文档切换没有进入此组件的通道。
    const documents = (project?.documents || []).filter(doc => doc.scope === 'project');
    const results = await Promise.allSettled(documents.map(async metadata => {
      const doc = await api('/api/document', { id: metadata.id });
      const parsed = parseArchitectureDocument(doc.content || '');
      return parsed.flows.map((flow, index) => ({ ...flow, roles: parsed.roles,
        key: `${metadata.id}:${index}`, title: `${documents.length > 1 ? (doc.path || metadata.path) + ' · ' : ''}${flow.title}`,
        path: doc.path || metadata.path || '项目架构文档' }));
    }));
    if (version !== loadVersion) return;
    for (const result of results) {
      if (result.status === 'fulfilled') flows.push(...result.value);
      else documentError = '项目文档暂不可用，已回退根层代码架构；可重试。';
    }
    if (documentError && flows.length) documentError = '部分项目文档暂不可用，可重试；当前架构与代码视图仍可使用。';
    loading = false; updateControls();
    await renderGraph(version);
  }
  async function renderGraph(expectedLoad = loadVersion) {
    const version = ++renderVersion, nextKey = sourceKey();
    ++navigationVersion;
    if (activeKey && !expanded) cameras.set(activeKey, { ...camera });
    activeKey = nextKey; nodes = []; selectedNode = null; drawingError = ''; loading = true;
    compactDiagram = null; fullDiagram = null;
    world.replaceChildren(); showDetail(); showNotice();
    let diagram;
    try {
      if (source.value === 'flow' && flows.length) {
        const flow = flows[flowIndex()];
        const result = await renderDataFlow(flow.source);
        if (version !== renderVersion || expectedLoad !== loadVersion) return;
        diagram = result.diagram; renderedKind = 'flow';
        nodes = result.nodes.map(node => {
          const roles = flow.roles.filter(role => role.id === node.id);
          const explicit = [...new Set(roles.flatMap(role => [role.module, ...(role.scopes || [])]).map(key).filter(Boolean))];
          // 无职责声明时仅接受节点 ID 对真实代码路径的精确匹配，不按标签猜测。
          const scopes = roles.length ? explicit : validTarget(key(node.id)) ? [key(node.id)] : [];
          const targets = scopes.map(scope => targetFor(scope)).filter(Boolean);
          return { ...node, roles, scopes, targets,
            summary: roles.map(role => role.summary).filter(Boolean).join('；') };
        });
        for (const edge of diagram.querySelectorAll('.edgePaths path')) edge.classList.add('pinned-edge');
        fullDiagram = diagram; compactDiagram = null;
        for (const node of nodes) node.fullElement = node.element;
        const parsed = parseFlowEdges(flow.source, nodes.map(node => node.id));
        if (parsed) {
          const compact = buildCompact(nodes.map(node => ({ id: node.id, label: node.label })), parsed, false);
          compactDiagram = compact.diagram;
          for (const node of nodes) node.compactElement = compact.elements.get(node.id) || null;
        }
        provenance.textContent = `${flow.path} · 项目级文档数据流 · 保留全部节点与连线`;
      } else {
        ({ diagram, nodes } = renderRootGraph());
        provenance.textContent = '源码静态分析 · 原始根层代码架构';
      }
    } catch (error) {
      if (version !== renderVersion || expectedLoad !== loadVersion) return;
      drawingError = `数据流暂时无法绘制，已回退根层代码架构。${error?.message || ''}`;
      ({ diagram, nodes } = renderRootGraph());
      provenance.textContent = '源码静态分析 · 数据流绘制失败，暂用根层代码架构';
    }
    if (version !== renderVersion || expectedLoad !== loadVersion) return;
    if (!fullDiagram) fullDiagram = diagram;
    for (const node of nodes) if (!node.fullElement) node.fullElement = node.element;
    (fullDiagram || diagram).setAttribute('aria-label', source.value === 'flow' && !drawingError ? '钉住的项目数据流图' : '钉住的根层代码架构图');
    for (const node of nodes) bindNode(node);
    mountActiveDiagram();
    loading = false;
    settleCamera();
    updateHighlight(); showNotice();
  }
  function renderRootGraph() {
    renderedKind = 'code';
    const rootNodes = rootView?.nodes || [], positions = new Map(), groups = new Map();
    for (const node of rootNodes) {
      const layer = Number(node.layer) || 0;
      if (!groups.has(layer)) groups.set(layer, []);
      groups.get(layer).push(node);
    }
    const columns = Math.min(4, Math.max(1, ...[...groups.values()].map(group => group.length)));
    const width = columns * 270 + 80; let y = 50;
    for (const [, group] of [...groups.entries()].sort((a, b) => a[0] - b[0])) {
      group.forEach((node, index) => positions.set(node.id, { x: 40 + (index % columns) * 270, y: y + Math.floor(index / columns) * 150 }));
      y += Math.ceil(group.length / columns) * 150 + 35;
    }
    const diagram = svg('svg', { viewBox: `0 0 ${width} ${Math.max(200, y)}`, class: 'pinned-code-diagram' });
    const defs = svg('defs'), markerId = `pin-arrow-${instance}`;
    const marker = svg('marker', { id: markerId, markerWidth: 8, markerHeight: 8, refX: 7, refY: 4, orient: 'auto', markerUnits: 'userSpaceOnUse' });
    marker.append(svg('path', { d: 'M0 0 L8 4 L0 8 Z', fill: '#91a89b' })); defs.append(marker); diagram.append(defs);
    (rootView?.edges || []).forEach((edge, index) => {
      const from = positions.get(edge.from), to = positions.get(edge.to); if (!from || !to) return;
      const x1 = from.x + 110, x2 = to.x + 110;
      let d, labelX, labelY;
      if (from.y === to.y) {
        const lane = from.y - 20 - (index % 5) * 5;
        d = `M${x1},${from.y} C${x1},${lane} ${x2},${lane} ${x2},${to.y}`;
        labelX = (x1 + x2) / 2; labelY = lane - 4;
      } else {
        const down = to.y > from.y, y1 = from.y + (down ? 88 : 0), y2 = to.y + (down ? 0 : 88);
        const mid = (y1 + y2) / 2;
        d = `M${x1},${y1} C${x1},${mid} ${x2},${mid} ${x2},${y2}`;
        labelX = (x1 + x2) / 2 + 6; labelY = mid - 5;
      }
      diagram.append(svg('path', { d, class: 'pinned-edge', 'data-from': edge.from, 'data-to': edge.to, 'marker-end': `url(#${markerId})` }));
      const label = edge.label || (edge.count > 1 ? String(edge.count) : '');
      if (label) diagram.append(svg('text', { x: labelX, y: labelY, class: 'pinned-edge-label', 'text-anchor': 'middle' }, label));
    });
    const graphNodes = rootNodes.map(node => {
      const position = positions.get(node.id), element = svg('g', { transform: `translate(${position.x},${position.y})` });
      element.append(svg('rect', { width: 220, height: 88, rx: 9 }));
      const label = node.label || node.id;
      // 长名称限定两行避免覆盖下一层；title 和说明栏保留完整名称。
      const chunks = String(label).match(/.{1,19}/gu) || [''];
      chunks.slice(0, 2).forEach((chunk, index) => element.append(svg('text', { x: 12, y: 25 + index * 19, class: 'pinned-code-label' },
        index === 1 && chunks.length > 2 ? `${chunk.slice(0, -1)}…` : chunk)));
      element.prepend(svg('title', {}, label));
      element.append(svg('text', { x: 12, y: 69, class: 'pinned-code-meta' }, `${node.members?.length || 0} 个源码模块`));
      diagram.append(element);
      // 根层同名包和文件以原始 ID 区分；叶子只能用其完整源码模块定位。
      const leafModule = node.sourceModule || (node.leaf && node.members?.length === 1 ? node.members[0] : null);
      const resolved = node.leaf ? leafModule && { key: modulePath(leafModule), target: { module: leafModule } } :
        { key: key(node.id), target: { path: [node.id] } };
      const targets = resolved ? [resolved] : [];
      return { id: node.id, label, element, scopes: [], targets,
        summary: node.description || `${node.members?.length || 0} 个源码模块 · 根层代码边界` };
    });
    fullDiagram = diagram;
    for (const node of graphNodes) node.fullElement = node.element;
    const compact = buildCompact(rootNodes.map(node => ({ id: node.id, label: node.label || node.id, layer: node.layer })),
      (rootView?.edges || []).map(edge => ({ from: edge.from, to: edge.to, count: edge.count || 0, label: edge.label || (edge.count > 1 ? String(edge.count) : '') })), true);
    compactDiagram = compact.diagram;
    for (const node of graphNodes) node.compactElement = compact.elements.get(node.id) || null;
    return { diagram, nodes: graphNodes };
  }
  function bindNode(node) {
    const elements = [...new Set([node.fullElement, node.compactElement, node.element].filter(Boolean))];
    for (const element of elements) bindElement(node, element);
  }
  function bindElement(node, element) {
    if (element.dataset.pinBound) return;
    element.dataset.pinBound = '1';
    element.classList.add('pinned-node'); element.classList.toggle('pinned-unbound', !node.targets.length);
    element.dataset.nodeId = node.id;
    element.dataset.targets = JSON.stringify(node.targets.map(target => target.target));
    if (node.targets.length === 1) element.dataset.target = node.targets[0].key;
    element.setAttribute('tabindex', '0'); element.setAttribute('role', 'button');
    element.setAttribute('aria-label', `${node.label}${node.targets.length ? '，点击进入全部覆盖代码' : '，无代码绑定'}`);
    element.addEventListener('click', event => {
      event.stopPropagation(); if (suppressClick || event.detail > 1) return;
      void enterNode(node);
    });
    element.addEventListener('dblclick', event => event.stopPropagation());
    element.addEventListener('keydown', event => {
      if (event.key === ' ' || event.key === 'Enter') {
        event.preventDefault(); event.stopPropagation();
        if (!event.repeat) void enterNode(node);
      }
    });
  }
  function showDetail() {
    detailTitle.textContent = selectedNode?.label || '点击模块进入代码';
    const targets = selectedNode?.targets || [];
    detailSummary.textContent = selectedNode ?
      [selectedNode.summary, targets.map(target => target.key).join('、') || '外部节点或未绑定代码，不能跳转'].filter(Boolean).join(' · ') :
      '一次展示该模块覆盖的全部代码范围';
    detailSummary.title = detailSummary.textContent;
  }
  async function enterNode(node) {
    selectedNode = node; showDetail(); updateHighlight();
    if (!node.targets.length || !locateModule) return;
    const destination = node.targets.length === 1 ? node.targets[0].target :
      { targets: node.targets.map(target => target.target), label: node.label };
    const version = ++navigationVersion, expectedLoad = loadVersion, expectedKey = activeKey;
    try {
      const result = await locateModule(destination);
      if (version !== navigationVersion || expectedLoad !== loadVersion || expectedKey !== activeKey) return;
      if (result !== false) {
        entry = { key: activeKey, id: node.id, targets: node.targets.map(target => target.key) };
        if (expanded) setExpanded(false);
      }
      updateHighlight();
    } catch (error) {
      if (version === navigationVersion && expectedLoad === loadVersion) toast(`未能进入代码：${error?.message || '请重试'}`);
    } finally {
      if (version === navigationVersion && expectedLoad === loadVersion) showDetail();
    }
  }
  function locationPaths() {
    if (location.sourceModule) return [modulePath(location.sourceModule)];
    const selected = location.selected;
    if (selected && typeof selected === 'object' && selected.members?.length) return selected.members.map(modulePath);
    // app 传入选中节点对象；字符串仍按当前层下的节点 ID 兼容。
    if (selected) return [[...parts(location.path), ...parts(typeof selected === 'string' ? selected : selected.id)].join('/')];
    if (location.scopes?.length) return cachedModulePaths.filter(module => location.scopes.some(scope => contains(key(scope), module)));
    const path = key(location.path);
    // 当前包可能横跨多个更细的职责范围：按实际成员逐一投影，不能只拿包名匹配。
    return path ? cachedModulePaths.filter(module => contains(path, module)) : [];
  }
  function matchedNodes(paths) {
    const matched = new Set();
    if (!nodes.length) return matched;
    if (renderedKind === 'code') {
      // 根层归属在 load 时索引：每个模块只查一次表，不扫描节点的全部成员。
      for (const path of paths) for (const id of rootOwners.get(path) || []) matched.add(id);
      return matched;
    }
    for (const path of paths) {
      let specificity = -1, candidates = [];
      for (const node of nodes) {
        let score = -1;
        for (const scope of node.scopes) {
          if (contains(scope, path)) score = Math.max(score, parts(scope).length);
        }
        if (score < 0) continue;
        if (score > specificity) { specificity = score; candidates = [node]; }
        else if (score === specificity) candidates.push(node);
      }
      for (const node of candidates) matched.add(node.id);
    }
    return matched;
  }
  function updateHighlight() {
    const current = matchedNodes(locationPaths());
    const entryPaths = entry && entry.key !== activeKey ? cachedModulePaths.filter(module => entry.targets.some(target => contains(target, module))) : [];
    const entered = entry ? (entry.key === activeKey ? new Set([entry.id]) : matchedNodes(entryPaths.length ? entryPaths : entry.targets)) : new Set();
    for (const node of nodes) {
      node.element.classList.toggle('pinned-current', current.has(node.id));
      node.element.classList.toggle('pinned-entry', entered.has(node.id));
      node.element.classList.toggle('pinned-selected', node === selectedNode);
      if (current.has(node.id)) node.element.setAttribute('aria-current', 'location');
      else node.element.removeAttribute('aria-current');
    }
    locateButton.disabled = !current.size;
    const currentNames = nodes.filter(node => current.has(node.id)).map(node => node.label);
    const enteredNames = nodes.filter(node => entered.has(node.id)).map(node => node.label);
    status.textContent = current.size ? `当前：${currentNames.join('、')}${current.size > 1 ? `（${current.size} 个节点）` : ''}` :
      location.sourceModule || location.selected || key(location.path) ? '当前代码尚未映射到全局节点' : '项目根层 · 尚未选择代码';
    if (enteredNames.length) status.textContent += ` · 进入点：${enteredNames.join('、')}`;
    status.title = status.textContent;
    host.dataset.mapping = current.size > 1 ? 'multiple' : current.size ? 'matched' : 'unmapped';
  }
  function updateLocation(nextLocation = {}) {
    location = { path: nextLocation.path || [], selected: nextLocation.selected || null,
      sourceModule: nextLocation.sourceModule || null, scopes: nextLocation.scopes || [] };
    updateHighlight();
  }
  function showNotice() {
    const warning = drawingError || documentError;
    notice.hidden = !loading && !warning && nodes.length > 0;
    notice.classList.toggle('pinned-warning', Boolean(warning) && !loading);
    message.textContent = loading ? '正在读取全局架构…' : warning || '项目尚无可显示的全局架构；代码视图仍可使用。';
    retry.hidden = loading || !warning;
    viewport.setAttribute('aria-busy', String(loading));
    fitButton.disabled = loading || !nodes.length;
  }
  function applyCamera() {
    world.style.transform = `translate(${camera.x}px,${camera.y}px) scale(${camera.zoom})`;
    zoomLabel.textContent = `${Math.round(camera.zoom * 100)}%`;
  }
  function fit() {
    if (!viewport.clientWidth || !viewport.clientHeight || !nodes.length) { pendingFit = true; return; }
    const zoom = Math.min(1, (viewport.clientWidth - 32) / size.width, (viewport.clientHeight - 24) / size.height);
    camera = { zoom: Math.max(.005, zoom), x: (viewport.clientWidth - size.width * zoom) / 2, y: (viewport.clientHeight - size.height * zoom) / 2 };
    pendingFit = false; lastViewport = { width: viewport.clientWidth, height: viewport.clientHeight }; applyCamera();
  }
  function zoomAt(zoom, x = viewport.clientWidth / 2, y = viewport.clientHeight / 2) {
    zoom = Math.min(4, Math.max(.005, zoom));
    const ratio = zoom / camera.zoom;
    camera = { zoom, x: x - (x - camera.x) * ratio, y: y - (y - camera.y) * ratio };
    pendingFit = false; applyCamera();
  }
  function centerElements(elements) {
    if (!elements.length) return false;
    const boxes = elements.map(element => element.getBoundingClientRect());
    const left = Math.min(...boxes.map(box => box.left)), right = Math.max(...boxes.map(box => box.right));
    const top = Math.min(...boxes.map(box => box.top)), bottom = Math.max(...boxes.map(box => box.bottom));
    const bounds = viewport.getBoundingClientRect();
    camera.x += bounds.left + bounds.width / 2 - (left + right) / 2;
    camera.y += bounds.top + bounds.height / 2 - (top + bottom) / 2; applyCamera(); return true;
  }
  function focusCurrent() { centerElements(nodes.filter(node => node.element.classList.contains('pinned-current')).map(node => node.element)); }
  function setInert(element, active) {
    if (active) { element.dataset.pinWasInert = String(element.inert); element.inert = true; }
    else { element.inert = element.dataset.pinWasInert === 'true'; delete element.dataset.pinWasInert; }
  }
  function setExpanded(active) {
    if (active === expanded || (active && !opened)) return;
    ignoreResize += 1;
    expanded = active;
    if (active) splitCamera = { ...camera };
    panel?.classList.toggle('pin-expanded', active); host.classList.toggle('pinned-expanded', active);
    expandButton.textContent = active ? '退出放大' : '放大阅读'; expandButton.title = active ? '退出放大（Esc）' : '放大阅读全局架构';
    expandButton.setAttribute('aria-label', expandButton.title); expandButton.setAttribute('aria-expanded', String(active));
    // 浮层在画布容器内，不能把容器本身设为 inert，否则放大后的控件也无法聚焦。
    const stage = host.parentElement;
    for (const sibling of panel?.children || []) if (sibling !== stage && sibling !== host) setInert(sibling, active);
    if (stage) for (const child of stage.children) if (child !== host) setInert(child, active);
    mountActiveDiagram(); updateHighlight();
    lastViewport = null;
    if (!active && splitCamera) { camera = splitCamera; splitCamera = null; pendingFit = false; applyCamera(); }
    requestAnimationFrame(() => {
      if (expanded !== active) return;
      layoutFrame();
      if (active) {
        fit(); zoomAt(Math.max(.8, camera.zoom));
        if (!centerElements(nodes.filter(node => node.element.classList.contains('pinned-current')).map(node => node.element))) {
          camera.x = (viewport.clientWidth - size.width * camera.zoom) / 2;
          camera.y = (viewport.clientHeight - size.height * camera.zoom) / 2; applyCamera();
        }
      }
      lastViewport = { width: viewport.clientWidth, height: viewport.clientHeight };
      ignoreResize = Math.max(0, ignoreResize - 1);
    });
    expandButton.focus({ preventScroll: true });
  }
  source.addEventListener('change', () => {
    preference.source = source.value; savePreference();
    flowSelect.hidden = source.value !== 'flow' || flows.length < 2; host.dataset.source = source.value;
    if (expanded) setExpanded(false);
    void renderGraph();
  });
  flowSelect.addEventListener('change', () => {
    preference.flow = flowSelect.value; savePreference();
    if (expanded) setExpanded(false);
    void renderGraph();
  });
  viewport.addEventListener('wheel', event => {
    if (!nodes.length) return;
    event.preventDefault(); event.stopPropagation();
    if (event.ctrlKey || event.metaKey) {
      const bounds = viewport.getBoundingClientRect();
      zoomAt(camera.zoom * Math.exp(-event.deltaY * .002), event.clientX - bounds.left, event.clientY - bounds.top);
    } else {
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? viewport.clientHeight : 1;
      camera.x -= (event.shiftKey ? event.deltaY : event.deltaX) * unit;
      camera.y -= (event.shiftKey ? 0 : event.deltaY) * unit; applyCamera();
    }
  }, { passive: false });
  viewport.addEventListener('pointerdown', event => {
    if (![0, 1].includes(event.button) || event.pointerType === 'touch') return;
    if (event.button === 0 && event.target.closest('.pinned-node,button')) return;
    event.preventDefault(); drag = { id: event.pointerId, x: event.clientX, y: event.clientY, camera: { ...camera } };
    viewport.setPointerCapture(event.pointerId); viewport.classList.add('pinned-dragging');
  });
  viewport.addEventListener('pointermove', event => {
    if (!drag || drag.id !== event.pointerId) return;
    const x = event.clientX - drag.x, y = event.clientY - drag.y;
    if (Math.abs(x) + Math.abs(y) > 4) suppressClick = true;
    camera = { ...camera, x: drag.camera.x + x, y: drag.camera.y + y }; applyCamera();
  });
  function finishDrag(event) {
    if (!drag || drag.id !== event.pointerId) return;
    drag = null; viewport.classList.remove('pinned-dragging');
    if (viewport.hasPointerCapture(event.pointerId)) viewport.releasePointerCapture(event.pointerId);
    setTimeout(() => { suppressClick = false; }, 0);
  }
  viewport.addEventListener('pointerup', finishDrag); viewport.addEventListener('pointercancel', finishDrag);
  viewport.addEventListener('keydown', event => {
    if (event.target !== viewport) return;
    const delta = { ArrowLeft: [40, 0], ArrowRight: [-40, 0], ArrowUp: [0, 40], ArrowDown: [0, -40] }[event.key];
    if (delta) { event.preventDefault(); camera.x += delta[0]; camera.y += delta[1]; applyCamera(); }
    else if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomAt(camera.zoom * 1.25); }
    else if (event.key === '-') { event.preventDefault(); zoomAt(camera.zoom / 1.25); }
  });
  function beginResize(event) {
    if (event.button !== 0 || expanded || host.classList.contains('pinned-cramped')) return;
    event.preventDefault(); event.stopPropagation();
    const bounds = host.getBoundingClientRect();
    resizeDrag = { id: event.pointerId, width: bounds.width, height: bounds.height,
      x: event.clientX, y: event.clientY, corner: event.currentTarget === corner, handle: event.currentTarget };
    event.currentTarget.setPointerCapture(event.pointerId); host.classList.add('pinned-resizing');
  }
  function moveResize(event) {
    if (!resizeDrag || event.pointerId !== resizeDrag.id) return;
    const limits = sizeLimits();
    preference.width = clampWidth(resizeDrag.width + resizeDrag.x - event.clientX, limits.width);
    if (resizeDrag.corner) preference.floatingHeight = Math.min(limits.height,
      Math.max(PIN_HEIGHT_MIN, resizeDrag.height + event.clientY - resizeDrag.y));
    layoutFrame();
  }
  function finishResize(event) {
    if (!resizeDrag || event.pointerId !== resizeDrag.id) return;
    const { handle } = resizeDrag;
    resizeDrag = null; host.classList.remove('pinned-resizing');
    if (handle.hasPointerCapture(event.pointerId)) handle.releasePointerCapture(event.pointerId);
    savePreference();
  }
  for (const handle of [splitter, corner]) {
    handle.addEventListener('pointerdown', beginResize);
    handle.addEventListener('pointermove', moveResize);
    handle.addEventListener('pointerup', finishResize);
    handle.addEventListener('pointercancel', finishResize);
    handle.addEventListener('lostpointercapture', finishResize);
  }
  splitter.addEventListener('keydown', event => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault(); setWidth(Number(splitter.getAttribute('aria-valuenow')) + (event.key === 'ArrowLeft' ? 16 : -16));
  });
  corner.addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return;
    event.preventDefault(); event.stopPropagation();
    const bounds = host.getBoundingClientRect(), limits = sizeLimits();
    preference.width = clampWidth(bounds.width + (event.key === 'ArrowLeft' ? 16 : event.key === 'ArrowRight' ? -16 : 0), limits.width);
    preference.floatingHeight = Math.min(limits.height, Math.max(PIN_HEIGHT_MIN,
      bounds.height + (event.key === 'ArrowDown' ? 16 : event.key === 'ArrowUp' ? -16 : 0)));
    layoutFrame(); savePreference();
  });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && expanded && !document.querySelector('#drawer:not([hidden])')) {
      event.preventDefault(); event.stopImmediatePropagation(); setExpanded(false);
    }
  }, true);
  if (globalThis.ResizeObserver) {
    new ResizeObserver(() => {
      const width = viewport.clientWidth, height = viewport.clientHeight;
      if (!width || !height || ignoreResize) { if (width && height) lastViewport = { width, height }; return; }
      layoutFrame();
      const nextWidth = viewport.clientWidth, nextHeight = viewport.clientHeight;
      if (pendingFit && nodes.length) fit();
      else if (lastViewport) { camera.x += (nextWidth - lastViewport.width) / 2; camera.y += (nextHeight - lastViewport.height) / 2; applyCamera(); }
      lastViewport = { width: nextWidth, height: nextHeight };
    }).observe(viewport);
  }
  setWidth(PIN_WIDTH.preferred, false); applyVisibility(); showNotice();
  return { load, updateLocation, isOpen: () => opened, open, close };
}
