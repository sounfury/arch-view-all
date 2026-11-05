/* 业务入口导航：按业务域分节列出入口处理类；选择入口打开方法调用图，双击打开源码。 */

const SOURCE_LABELS = { rules: '规则 domains', architecture: '架构文档', annotation: '注解分组', inferred: '推断' };
const UNCLASSIFIED = { id: '_unclassified', label: '未归类' };

export function createEntriesNav({ host, api, element, button, icon, toast, locateModule, openChain, showSource, showEntry, highlightGroup, onLoaded = () => {} }) {
  let data = null, loading = false, error = '', projectKey = '', version = 0;
  let groupBy = null, query = '', selected = null, activeGroup = null, supported = false;
  let body = null, resultCount = null;
  const channels = new Set(), collapsed = new Set(), expandedClasses = new Set();

  async function load(project, path = '/api/entries', method = 'GET') {
    const current = ++version;
    const nextProject = project?.root || '';
    if (projectKey !== nextProject) {
      data = null; selected = null; activeGroup = null; query = ''; groupBy = null;
      channels.clear(); collapsed.clear(); expandedClasses.clear();
    }
    projectKey = nextProject;
    supported = project?.language === 'java' || supported;
    loading = true; error = ''; render();
    try {
      const next = await api(path, {}, method);
      if (current !== version) return;
      data = next; restorePreference(); seedCollapsed();
    } catch (failure) {
      if (current !== version) return;
      error = failure.message || '入口读取失败';
    } finally {
      if (current === version) { loading = false; render(); onLoaded(summary()); }
    }
  }
  function reload() { return load({ root: projectKey }, '/api/entries/reload', 'POST'); }
  function summary() {
    return { available: supported && !data?.unsupported, count: data?.entries?.length || 0 };
  }

  // ===== 私有方法 =====

  function storageKey() { return `arch-view.entries.group-by:${projectKey}`; }
  function restorePreference() {
    const sources = (data?.grouping?.sources || []).map(source => source.id);
    const stored = localStorage.getItem(storageKey());
    groupBy = sources.includes(stored) ? stored : data?.grouping?.default || 'inferred';
    for (const channel of [...channels]) if (!data.channels.some(item => item.label === channel)) channels.delete(channel);
    if (selected && !data.entries.some(entry => entry.id === selected)) selected = null;
  }
  function seedCollapsed() {
    // 旁置分组（测试、示例）默认收起，其它业务域默认展开。
    collapsed.clear();
    for (const entry of data?.entries || []) if (entry.aside) collapsed.add(`_aside:${entry.aside}`);
  }
  function domainsOf(entry) {
    if (entry.aside) return [{ id: `_aside:${entry.aside}`, label: entry.aside }];
    const found = entry.domains?.[groupBy];
    return found?.length ? found : [UNCLASSIFIED];
  }
  function special(domain) { return domain.id.startsWith('_'); }
  function multiDomain(entry) { return (entry.domains?.[groupBy] || []).filter(domain => !special(domain)).length > 1; }
  function haystack(entry) {
    const attrs = Object.values(entry.attrs || {}).flat();
    return [entry.businessName, entry.label, entry.handler?.type, entry.handler?.method, entry.summary, entry.channel?.label, ...attrs, ...(entry.tags || [])]
      .filter(Boolean).join('\n').toLowerCase();
  }
  function visibleEntries() {
    const needle = query.trim().toLowerCase();
    return (data?.entries || []).filter(entry => (!channels.size || channels.has(entry.channel?.label))
      && (!needle || haystack(entry).includes(needle)));
  }
  function groupRank(domain) {
    if (domain.id === '_infra') return 1;
    if (domain.id.startsWith('_aside:')) return 2;
    if (domain.id === UNCLASSIFIED.id) return 3;
    return 0;
  }
  function grouped(entries) {
    const groups = new Map();
    for (const entry of entries) for (const domain of domainsOf(entry)) {
      if (!groups.has(domain.id)) groups.set(domain.id, { domain, entries: [] });
      groups.get(domain.id).entries.push(entry);
    }
    return [...groups.values()].sort((a, b) => groupRank(a.domain) - groupRank(b.domain)
      || b.entries.length - a.entries.length || a.domain.label.localeCompare(b.domain.label, 'zh'));
  }
  function classRows(entries) {
    const rows = new Map();
    for (const entry of entries) {
      const type = entry.handler?.type || entry.module;
      if (!rows.has(type)) rows.set(type, []);
      rows.get(type).push(entry);
    }
    return [...rows.entries()].map(([type, items]) => ({ type, items }))
      .sort((a, b) => (a.items[0].channel?.label || '').localeCompare(b.items[0].channel?.label || '', 'zh')
        || simpleName(a.type).localeCompare(simpleName(b.type)));
  }
  function simpleName(type) { return String(type || '').split('.').at(-1); }
  function tooltip(entry) {
    return [entry.businessName, entry.label, `${entry.handler?.type}#${entry.handler?.method}`, entry.summary,
      `${entry.anchor?.path}:${entry.anchor?.startLine}`, entry.warnings?.length ? `警告：${entry.warnings.join('、')}` : '']
      .filter(Boolean).join('\n');
  }
  function select(entry, chain = true) {
    selected = entry.id;
    for (const link of host.querySelectorAll('.entry-link')) {
      const current = link.dataset.entryId === selected || link.dataset.handler === entry.handler?.type;
      link.classList.toggle('current', current);
      link.setAttribute('aria-current', current ? 'true' : 'false');
    }
    if (chain && openChain) void openChain(entry);
    else void locateModule(entry.module);
  }
  function open(entry) { void showSource(entry.module, entry.anchor?.startLine); }
  function entryButton(entry, className, content, chain = true) {
    const link = button('', `entry-link ${className}`, () => select(entry, chain));
    link.dataset.entryId = entry.id;
    link.addEventListener('dblclick', () => open(entry));
    link.title = `${tooltip(entry)}\n单击查看调用链 · 双击打开源码`;
    link.classList.toggle('current', selected === entry.id);
    link.setAttribute('aria-current', String(selected === entry.id));
    link.append(...content);
    return link;
  }
  function tag(text, className = '') { return element('span', `entry-tag ${className}`, text); }
  function badges(items) {
    const marks = [];
    if (items.some(multiDomain)) marks.push(tag('多归属', 'multi'));
    if (items.some(entry => entry.warnings?.length)) marks.push(tag('⚠', 'warn'));
    for (const text of [...new Set(items.flatMap(entry => entry.tags || []))]) marks.push(tag(text));
    return marks;
  }
  function entryInfo(items) {
    const info = button('', 'entry-info', () => showEntry(items));
    info.append(icon('book'));
    info.setAttribute('aria-label', `查看 ${simpleName(items[0].handler?.type)} 的入口详情`);
    info.title = '入口详情、声明位置与分发来源';
    return info;
  }
  function renderMethod(entry) {
    const copy = element('span', 'module-copy');
    copy.append(element('span', 'module-name', entry.businessName || entry.label),
      element('small', '', [entry.businessName ? entry.label : '', entry.summary || `${entry.handler?.method}()`].filter(Boolean).join(' · ')));
    const row = element('div', 'entry-method-row');
    row.append(entryButton(entry, 'entry-method', [copy, ...badges([entry])]), entryInfo([entry]));
    return row;
  }
  function renderClass(groupId, { type, items }) {
    const key = `${groupId}|${type}`, multi = items.length > 1;
    const expanded = multi && (expandedClasses.has(key) || Boolean(query.trim()));
    const box = element('div', 'entry-class');
    const row = element('div', 'tree-row');
    let toggle;
    if (multi) {
      toggle = button('', 'tree-toggle', event => {
        event.stopPropagation();
        if (expandedClasses.has(key)) expandedClasses.delete(key); else expandedClasses.add(key);
        render();
      });
      toggle.append(icon('chevron'));
      toggle.setAttribute('aria-expanded', String(expanded));
      toggle.setAttribute('aria-label', `${expanded ? '收起' : '展开'} ${simpleName(type)} 的 ${items.length} 个入口`);
    } else toggle = element('span', 'tree-toggle leaf');
    const first = items[0];
    const copy = element('span', 'module-copy');
    copy.append(element('span', 'module-name', !multi && first.businessName ? first.businessName : simpleName(type)),
      element('small', '', multi ? `${items.length} 个入口` : [first.businessName ? simpleName(type) : '', first.label, first.summary].filter(Boolean).join(' · ')));
    const channelTags = [...new Set(items.map(entry => entry.channel?.label))].map(label => tag(label, 'channel'));
    const link = entryButton(first, 'entry-class-link', [...channelTags, copy, ...badges(items)], !multi);
    link.dataset.handler = type;
    const current = items.some(entry => entry.id === selected);
    link.classList.toggle('current', current);
    link.setAttribute('aria-current', String(current));
    if (multi) link.title = `${type}\n${items.map(entry => entry.label).join('\n')}\n单击定位处理类 · 双击打开源码`;
    row.append(toggle, link, entryInfo(items));
    box.append(row);
    if (expanded) {
      const children = element('div', 'tree-children');
      for (const entry of items) children.append(renderMethod(entry));
      box.append(children);
    }
    return box;
  }
  function toggleGroup(id) {
    if (collapsed.has(id)) collapsed.delete(id); else collapsed.add(id);
    render();
  }
  async function focusGroup(domain) {
    const target = activeGroup === domain.id ? null : domain.id;
    activeGroup = target; renderBody();
    const matched = await highlightGroup(target ? [target] : []);
    if (target && activeGroup === target && !matched) toast('项目数据流中没有对应的业务子系统');
  }
  function renderGroup({ domain, entries }) {
    const open = !collapsed.has(domain.id) || Boolean(query.trim());
    const section = element('section', 'entry-group');
    const head = element('div', 'entry-group-head');
    const toggle = button('', 'tree-toggle', () => toggleGroup(domain.id));
    toggle.append(icon('chevron'));
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', `${open ? '收起' : '展开'}${domain.label}`);
    const linkable = groupBy === 'architecture' && !special(domain);
    const name = button(domain.label, `entry-group-name${special(domain) ? ' special' : ''}`,
      () => linkable ? focusGroup(domain) : toggleGroup(domain.id));
    name.title = linkable ? `在钉住的架构上高亮 ${domain.id}` : `${open ? '收起' : '展开'}${domain.label}`;
    name.classList.toggle('current', activeGroup === domain.id);
    head.append(toggle, name, element('span', 'module-count', String(entries.length)));
    section.append(head);
    if (open) {
      const rows = element('div', 'entry-rows');
      for (const row of classRows(entries)) rows.append(renderClass(domain.id, row));
      section.append(rows);
    }
    return section;
  }
  function renderRulesBanner() {
    const rules = data?.rules;
    if (rules?.status !== 'error') return null;
    const banner = element('div', 'entry-banner error');
    banner.append(element('strong', '', `规则文件有错误，暂只用内置规则：${rules.file}`));
    const list = element('ul');
    for (const message of (rules.errors || []).slice(0, 6)) list.append(element('li', '', message));
    banner.append(list, button('重新加载', 'text-button', () => void reload()));
    return banner;
  }
  function renderToolbar() {
    const bar = element('div', 'entry-toolbar');
    const search = element('input', 'entry-search');
    search.type = 'search'; search.placeholder = '搜索路径、类、方法、topic、cron…'; search.value = query;
    search.setAttribute('aria-label', '搜索入口');
    search.addEventListener('input', () => {
      query = search.value; renderBody();
    });
    const source = element('select', 'entry-source');
    source.setAttribute('aria-label', '业务分组来源');
    for (const item of data.grouping?.sources || []) {
      const option = element('option', '', `${SOURCE_LABELS[item.id] || item.id} · ${Math.round(item.coverage * 100)}%${item.id === data.grouping.default ? '（默认）' : ''}`);
      option.value = item.id; source.append(option);
    }
    source.value = groupBy;
    source.addEventListener('change', () => {
      groupBy = source.value; activeGroup = null; highlightGroup([]);
      localStorage.setItem(storageKey(), groupBy); renderBody();
    });
    const refresh = button('', 'icon-button small', () => void reload());
    refresh.append(icon('refresh'));
    refresh.title = loading ? '正在重新读取入口…' : '重新读取入口规则'; refresh.setAttribute('aria-label', refresh.title);
    refresh.disabled = loading;
    bar.append(search, source, refresh);
    return bar;
  }
  function renderChannels() {
    const box = element('div', 'entry-channels');
    box.setAttribute('role', 'group'); box.setAttribute('aria-label', '按接入机制筛选');
    for (const channel of data.channels || []) {
      const chip = button('', 'entry-chip', () => {
        if (channels.has(channel.label)) channels.delete(channel.label); else channels.add(channel.label);
        render();
      });
      chip.append(element('span', '', channel.label), element('small', '', String(channel.count)));
      chip.setAttribute('aria-pressed', String(channels.has(channel.label)));
      chip.title = `只看 ${channel.label}（规则：${(channel.rules || []).join('、')}）；可多选`;
      box.append(chip);
    }
    return box;
  }
  function renderBody() {
    if (!body) return;
    const entries = visibleEntries();
    if (resultCount) resultCount.textContent = `显示 ${entries.length} / ${data.entries.length} 个入口`;
    body.replaceChildren();
    if (!entries.length) {
      body.append(element('div', 'tree-status', data.entries.length ? '没有匹配的入口' :
        '没有识别到入口。可在项目根运行 arch-view entries candidates 查看规则尚未覆盖的写法。'));
      return;
    }
    if (groupBy !== 'architecture') void highlightGroup([]);
    for (const group of grouped(entries)) body.append(renderGroup(group));
  }
  function render() {
    const scroll = host.scrollTop;
    host.setAttribute('aria-busy', String(loading));
    host.replaceChildren();
    body = null; resultCount = null;
    if (loading && !data) { host.append(element('div', 'tree-status', '正在识别入口…')); return; }
    if (error) {
      const line = element('div', 'tree-status error', `入口读取失败：${error} `);
      line.append(button('重试', '', () => void load({ root: projectKey })));
      host.append(line); return;
    }
    if (!data) return;
    if (data.unsupported) { host.append(element('div', 'tree-status', `入口导航目前只支持 Java 项目（本项目：${data.language || '未知'}）。`)); return; }
    const banner = renderRulesBanner();
    if (banner) host.append(banner);
    host.append(renderToolbar(), renderChannels());
    resultCount = element('div', 'entry-result-count');
    resultCount.setAttribute('role', 'status');
    body = element('div', 'entry-body');
    host.append(resultCount, body);
    renderBody();
    host.scrollTop = scroll;
  }

  return { load, reload, render, summary };
}
