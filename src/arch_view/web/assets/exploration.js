/*
 * 职责：网页里的 AI 辅助探索：模块的已有解释与过期状态、复制上下文包、让本机 AI 解释并保存为解释，
 *       以及调用链路列表、逐跳校验结果和链路在架构图上的叠加显示。
 * 核心入口：创建探索功能（createExploration），由 app.js 传入页面能力后挂载。
 */

'use strict';

const HOP_TEXT = {
  'same-module': ['=', '同一模块内'],
  direct: ['✓', '静态依赖直达'],
  indirect: ['≈', '经静态依赖间接到达'],
  reverse: ['↩', '只有反向依赖（回调或返回）'],
  unverified: ['?', '静态依赖里找不到，AI 推断，需要人工复核'],
  unmapped: ['·', '至少一端不是已分析的源码模块，无法校验'],
};
const ANCHOR_TEXT = { fresh: '代码未变', moved: '代码已移动', stale: '代码已改动', missing: '文件已不存在', unanchored: '未引用代码' };
const DEFAULT_QUESTION = '解释它的职责、关键流程，以及它与上下游模块的关系；引用代码时写 路径:行号。';

export function createExploration(host) {
  const { state, api, element, button, icon, richText, toast, openDrawer, showSource, $ } = host;
  const trace = { list: [], active: null, step: null, only: true };
  const conversations = new Map();

  // ===== 公开能力 =====

  /** 侧栏的调用链路列表；无链路时整块隐藏。 */
  async function loadTraces() {
    try { trace.list = await api('/api/traces'); } catch { trace.list = []; }
    const activeId = trace.active?.trace.id;
    if (activeId && !trace.list.some(item => item.id === activeId)) closeTrace();
    else if (activeId) {
      try { trace.active = await api('/api/trace', { id: activeId }); host.refresh(); }
      catch (error) { toast(error.message); }
    }
    renderTraceNav();
  }

  /** 当前是否在查看链路：查看时右侧面板固定显示链路步骤。 */
  function traceActive() { return Boolean(trace.active); }

  function mapNodes(nodes) {
    if (!trace.active || !trace.only) return nodes;
    const modules = new Set(trace.active.steps.map(step => step.module).filter(Boolean));
    return nodes.filter(node => node.members.some(module => modules.has(module)));
  }

  /** 代码依赖图绘制完成后叠加链路：标出步骤序号，并按校验结果画出每一跳。 */
  function decorateMap(world, positions, nodes) {
    if (!trace.active) return;
    const stepsByNode = new Map();
    trace.active.steps.forEach((step, index) => {
      const node = step.module && nodes.find(item => item.members.includes(step.module));
      if (!node) return;
      if (!stepsByNode.has(node.id)) stepsByNode.set(node.id, []);
      stepsByNode.get(node.id).push(index);
    });
    for (const node of nodes) {
      const el = world.querySelector(`button.node[data-node="${CSS.escape(node.id)}"]`); if (!el) continue;
      const indexes = stepsByNode.get(node.id);
      el.classList.toggle('trace-off', !indexes);
      el.classList.toggle('trace-current', Boolean(indexes?.includes(trace.step)));
      if (indexes) el.append(element('span', 'trace-badge', indexes.map(index => index + 1).join(' · ')));
    }
    const overlay = hopOverlay(positions, stepsByNode);
    if (overlay) world.querySelector(':scope > svg')?.after(overlay);
  }

  /** 右侧面板：链路标题、汇总、逐步与逐跳校验结果。 */
  function renderTraceDetail(panel) {
    const scrollTop = panel.scrollTop;
    const { trace: data, steps, hops, summary, verdict } = trace.active;
    panel.replaceChildren(element('div', 'eyebrow', '调用链路'), element('h3', '', data.title));
    const close = button('关闭链路', 'button small', closeTrace);
    const scope = button(trace.only ? '显示全部模块' : '只看链路模块', 'button small', () => {
      trace.only = !trace.only; host.refresh(); host.presentMap();
    });
    const actions = element('div', 'ai-actions'); actions.append(close, scope); panel.append(actions);
    if (data.question) panel.append(element('p', 'trace-question', data.question));
    panel.append(verdictLine(summary, verdict));
    if (data.summary) panel.append(richText(data.summary, 'trace-summary'));
    const list = element('ol', 'trace-steps');
    steps.forEach((step, index) => {
      const incoming = data.edges ? hops.filter(hop => hop.to === index) : index > 0 ? [hops[index - 1]] : [];
      for (const hop of incoming) {
        const line = hopItem(hop);
        if (data.edges) line.prepend(element('small', '', `步骤 ${hop.from + 1} → ${index + 1} `));
        list.append(line);
      }
      list.append(stepItem(data.steps[index] || {}, step, index));
    });
    panel.append(list, element('p', 'detail-provenance', '校验只核对模块依赖；函数调用顺序仍需对照源码。'), element('p', 'detail-provenance',
      `${data.author === 'human' ? '人工整理' : data.author === 'static' ? '源码静态分析生成' : 'AI 整理'} · ${formatDate(data.createdAt)}${data.commit ? ' · 提交 ' + data.commit.slice(0, 8) : ''} · 逐跳结果来自当前源码静态分析`));
    panel.scrollTop = scrollTop;
  }

  /** 模块详情里的 AI 解释区：已有解释、让 AI 解释、复制上下文包。 */
  function aiSection(node) {
    const target = targetFor(node);
    const section = element('section', 'detail-section ai-section');
    const heading = element('h4', '', 'AI 解释'); const count = element('span', '', '…'); heading.append(count); section.append(heading);
    const actions = element('div', 'ai-actions');
    actions.append(button('让 AI 解释', 'button small', () => openAsk(target, node.label)),
      button('复制上下文包', 'button small', () => copyContext(target)));
    const notes = element('div', 'note-list');
    section.append(actions, notes);
    api('/api/notes', { target }).then(list => {
      count.textContent = list.length;
      if (!list.length) notes.append(element('p', '', '还没有保存的解释。让 AI 解释后可保存到 .arch-view/notes，随项目提交。'));
      list.forEach(note => notes.append(noteItem(note)));
    }).catch(() => { count.textContent = '—'; notes.append(element('p', '', '读取解释失败')); });
    return section;
  }

  // ===== 链路 =====

  function renderTraceNav() {
    const nav = $('#trace-nav'), title = $('#trace-title');
    nav.replaceChildren(); nav.hidden = title.hidden = !trace.list.length;
    $('#trace-count').textContent = String(trace.list.length).padStart(2, '0');
    for (const item of trace.list) {
      const link = button('', 'module-link trace-link', () => openTrace(item.id));
      link.dataset.trace = item.id; link.title = item.question || item.title;
      const copy = element('span', 'module-copy');
      copy.append(element('span', 'module-name', item.title),
        element('small', '', item.error ? `读取失败：${item.error}` : `${item.stepCount} 步 · ${verdictText(item.verdict)}`));
      link.append(icon('flow'), copy);
      link.classList.toggle('current', item.id === trace.active?.trace.id);
      link.classList.toggle('needs-review', Boolean(item.error) || ['needs-review', 'stale'].includes(item.verdict));
      nav.append(link);
    }
  }

  async function openTrace(id) {
    try {
      trace.active = await api('/api/trace', { id }); trace.step = null; trace.only = true;
      renderTraceNav(); host.setDiagramKind('code');
      const modules = [...new Set(trace.active.steps.map(step => step.module).filter(Boolean))];
      const locations = await Promise.all(modules.map(module => api('/api/locate', { module })));
      const common = locations.length ? [...locations[0].path] : [];
      for (const location of locations) {
        while (!common.every((part, index) => location.path[index] === part)) common.pop();
      }
      await host.navigate(common, 'explore', null, { expand: false, keepReturn: true });
    } catch (error) { toast(error.message); }
  }

  function closeTrace() {
    trace.active = null; trace.step = null; renderTraceNav(); host.refresh(); host.presentMap();
  }

  async function focusStep(index) {
    trace.step = index;
    const step = trace.active.steps[index];
    host.refresh();
    if (step.module) host.focusModule(step.module);
  }

  function stepItem(raw, step, index) {
    const anchor = step.anchor || raw.anchor || {};
    const line = anchor.status === 'moved' ? anchor.currentStartLine : anchor.startLine;
    const item = element('li', `trace-step${trace.step === index ? ' current' : ''}`);
    const head = button('', 'trace-step-head', () => focusStep(index));
    head.append(element('span', 'trace-number', index + 1), element('span', 'trace-step-title', raw.title || `第 ${index + 1} 步`));
    item.append(head);
    const where = element('div', 'trace-where');
    where.append(button(`${anchor.path || '未知文件'}:${line || '?'}${anchor.symbol ? ' · ' + anchor.symbol : ''}`, 'text-button',
      () => openCode(step.module, anchor.path, line)), anchorBadge(anchor.status));
    item.append(where);
    if (step.module) item.append(element('div', 'trace-module', step.module));
    if (raw.explanation) item.append(answerBody(raw.explanation, 'trace-explanation'));
    return item;
  }

  function hopItem(hop) {
    const [mark, text] = HOP_TEXT[hop?.status] || ['?', '未知'];
    const item = element('li', `trace-hop ${hop?.status || ''}`);
    item.append(element('span', 'trace-hop-mark', mark), element('span', '', text));
    if (hop?.reason) item.append(element('small', 'entry-warning', hop.reason));
    if (hop?.status === 'indirect' && hop.path?.length) item.append(element('small', '', hop.path.join(' → ')));
    return item;
  }

  function verdictLine(summary = {}, verdict = '') {
    const line = element('div', `trace-verdict ${verdict}`);
    line.append(element('strong', '', verdictText(verdict)));
    const parts = Object.entries(HOP_TEXT).filter(([key]) => summary[key]).map(([key, [mark]]) => `${mark} ${summary[key]}`);
    const stale = ['moved', 'stale', 'missing'].filter(key => summary[key]).map(key => `${ANCHOR_TEXT[key]} ${summary[key]}`);
    if (summary.uncertain) parts.push(`虚线关系 ${summary.uncertain}`);
    line.append(element('span', '', [...parts, ...stale].join(' · ')));
    return line;
  }

  function verdictText(verdict) {
    return { ok: '模块关系校验通过', 'needs-review': '有跳缺少静态证据', stale: '引用的代码已变化' }[verdict] || '未校验';
  }

  function hopOverlay(positions, stepsByNode) {
    const nodeOf = new Map(); for (const [id, indexes] of stepsByNode) indexes.forEach(index => nodeOf.set(index, id));
    const svg = host.svgElement('svg', { width: state.world.width, height: state.world.height, class: 'trace-overlay', 'aria-hidden': 'true' });
    const defs = host.svgElement('defs'), marker = host.svgElement('marker', { id: 'trace-arrow', markerWidth: 10, markerHeight: 10, refX: 8, refY: 5, orient: 'auto', markerUnits: 'userSpaceOnUse' });
    marker.append(host.svgElement('path', { d: 'M 0 0 L 10 5 L 0 10 Z', class: 'trace-arrow-head' })); defs.append(marker); svg.append(defs);
    let drawn = 0;
    trace.active.hops.forEach((hop, index) => {
      const from = positions.get(nodeOf.get(hop.from)), to = positions.get(nodeOf.get(hop.to));
      if (!from || !to || from === to) return;
      const route = host.routeEdge(from, to, state.world.width, index);
      const path = host.svgElement('path', { d: route.d, class: `trace-hop-line ${hop.status}${hop.uncertain ? ' uncertain' : ''}`, 'marker-end': 'url(#trace-arrow)' });
      const label = host.svgElement('text', { x: route.x, y: route.y, class: 'trace-hop-label' }); label.textContent = `${hop.from + 1}→${hop.to + 1}`;
      svg.append(path, label); drawn += 1;
    });
    return drawn ? svg : null;
  }

  // ===== 解释 =====

  function targetFor(node) {
    return node.leaf ? node.sourceModule : node.fullName;
  }

  function noteItem(note) {
    const status = note.status || note.check?.status || 'unanchored';
    const item = element('details', `note-item ${status}`);
    const summary = element('summary'); summary.append(element('span', '', note.title), anchorBadge(status));
    item.append(summary, answerBody(note.body || ''));
    const anchors = note.anchors || note.check?.anchors || [];
    if (anchors.length) {
      const list = element('div', 'note-anchors');
      anchors.forEach(anchor => {
        const line = anchor.status === 'moved' ? anchor.currentStartLine : anchor.startLine;
        list.append(button(`${anchor.path}:${line}`, 'text-button', () => openCode(null, anchor.path, line)));
      });
      item.append(list);
    }
    item.append(element('div', 'detail-provenance', `${note.author === 'human' ? '人工' : 'AI'} · ${formatDate(note.createdAt)} · ${note.id}${note.targetMissing ? ' · 目标模块已不存在' : ''}`));
    return item;
  }

  function anchorBadge(status) {
    if (!status) return document.createDocumentFragment();
    return element('span', `anchor-status ${status}`, ANCHOR_TEXT[status] || status);
  }

  async function copyContext(target) {
    try {
      const { markdown } = await api('/api/context', { target });
      await copyText(markdown); toast('上下文包已复制，可粘贴给任意 AI');
    } catch (error) { toast(error.message); }
  }

  function newConversation() {
    return { id: null, turns: [], busy: false, pending: null, render: null, configOptions: [] };
  }

  function fillConfig(container, chat) {
    container.replaceChildren();
    for (const option of chat.configOptions || []) {
      if (option.type !== 'select' || !option.options?.length) continue;
      const select = element('select', 'ask-config');
      select.setAttribute('aria-label', option.name);
      for (const choice of option.options) {
        const item = element('option', '', choice.name); item.value = choice.value; select.append(item);
      }
      select.value = option.currentValue;
      select.disabled = chat.busy;
      select.addEventListener('change', async () => {
        if (!chat.id || chat.busy) return;
        try {
          const result = await post('/api/agent/config', { id: chat.id, configId: option.id, value: select.value }, '');
          chat.configOptions = result.configOptions || []; fillConfig(container, chat);
        } catch (error) { toast(error.message); fillConfig(container, chat); }
      });
      container.append(select);
    }
    container.hidden = !container.children.length;
  }

  async function prepareSession(target, chat, configs) {
    if (!state.project.agentSessions) return;
    try {
      const params = { target }; if (chat.id) params.id = chat.id;
      const prepared = await post('/api/agent/open', params, '');
      chat.id = prepared.sessionId; chat.configOptions = prepared.configOptions || [];
      fillConfig(configs, chat);
    } catch (error) { toast(error.message); }
  }

  function openAsk(target, label) {
    openDrawer('让 AI 解释', target, 'AI 解释');
    const content = $('#drawer-content');
    let chat = conversations.get(target) || newConversation(); conversations.set(target, chat);
    const form = element('form', 'ask-form');
    const input = element('textarea'); input.rows = 4;
    input.value = chat.turns.length ? '' : DEFAULT_QUESTION;
    input.placeholder = '继续追问，AI 会保留本次对话的上下文'; input.setAttribute('aria-label', '要问 AI 的问题');
    const submit = element('button', 'button primary', '提问'); submit.type = 'submit';
    const cancel = button('停止回答', 'button small', async () => {
      cancel.disabled = true; if (chat.pending) chat.pending.cancelling = true;
      try { await post('/api/agent/cancel', { id: chat.id }, ''); }
      catch (error) { toast(error.message); if (chat.pending) chat.pending.cancelling = false; chat.render?.(); }
    });
    const output = element('div', 'ask-output'); output.setAttribute('aria-live', 'polite');
    const history = element('select', 'ask-history'); history.setAttribute('aria-label', '历史对话');
    const configs = element('div', 'ask-configs');
    const fresh = button('新建对话', 'button small', async () => {
      if (chat.id) await post('/api/agent/close', { id: chat.id }, '').catch(error => toast(error.message));
      chat = newConversation(); conversations.set(target, chat); input.value = DEFAULT_QUESTION; bind();
    });
    const tools = element('div', 'ai-actions'); tools.append(history, configs, fresh, cancel);
    tools.hidden = !state.project.agentSessions;
    form.append(input, submit); content.append(tools, form, output);
    const render = () => {
      if (!output.isConnected) return;
      submit.disabled = fresh.disabled = history.disabled = chat.busy;
      for (const select of configs.querySelectorAll('select')) select.disabled = chat.busy;
      cancel.hidden = !chat.busy; cancel.disabled = !chat.id || chat.pending?.cancelling === true;
      renderConversation(output, chat, target, label);
    };
    const bind = () => {
      chat.render = render; chat.refreshHistory = () => loadHistory(history, target, chat.id);
      fillConfig(configs, chat); render(); chat.refreshHistory(); prepareSession(target, chat, configs);
    };
    history.addEventListener('change', async () => {
      if (!history.value) return;
      try {
        const saved = await api('/api/agent/session', { id: history.value });
        chat = newConversation(); chat.id = saved.id; chat.turns = saved.history || [];
        conversations.set(target, chat); input.value = ''; bind();
      } catch (error) { toast(error.message); }
    });
    form.addEventListener('submit', event => {
      event.preventDefault(); const question = input.value.trim();
      if (question && !chat.busy) { ask(target, question, chat, configs); if (state.project.agentSessions) input.value = ''; }
    });
    bind();
  }

  async function loadHistory(select, target, active) {
    if (!state.project.agentSessions) return;
    try {
      const list = await api('/api/agent/sessions', { target });
      const placeholder = element('option', '', '恢复历史对话'); placeholder.value = '';
      select.replaceChildren(placeholder);
      for (const item of list) {
        const option = element('option', '', `${formatDate(item.updatedAt)} · ${item.turnCount} 轮 · ${item.title || '代码解释'}`);
        option.value = item.id; select.append(option);
      }
      select.value = active || '';
    } catch (error) { toast(error.message); }
  }

  function renderConversation(output, chat, target, label) {
    output.replaceChildren();
    if (!state.project.agentConfigured) output.append(agentHint(target));
    for (const turn of chat.turns) {
      const item = element('section', 'ask-turn');
      item.append(element('p', 'ask-question', turn.question));
      item.append(answerBody(turn.answer, 'ask-answer'));
      const actions = element('div', 'ai-actions');
      actions.append(button('保存为解释', 'button small', event => saveNote(event.currentTarget, target, label, turn.question, turn.answer, turn.anchors || [])),
        button('复制回答', 'button small', () => copyText(turn.answer).then(() => toast('回答已复制'))));
      item.append(actions); output.append(item);
    }
    if (chat.pending) {
      output.append(element('p', 'ask-question', chat.pending.question));
      if (chat.pending.text) output.append(answerBody(chat.pending.text, 'ask-answer ask-streaming'));
      output.append(element('p', 'ask-waiting', chat.pending.status || '本机 AI 正在只读沙箱中阅读代码并作答…'));
    }
    if (chat.error) output.append(element('p', 'ask-error', chat.error));
    if (chat.provenance) output.append(element('div', 'detail-provenance', chat.provenance));
  }

  function streamUpdate(chat, params) {
    const update = params.update || params;
    if (!chat.pending) return;
    let dirty = false;
    if (update.sessionUpdate === 'agent_message_chunk' && update.content?.type === 'text') {
      chat.pending.text += update.content.text || ''; dirty = true;
    }
    if (['tool_call', 'tool_call_update'].includes(update.sessionUpdate)) {
      chat.pending.status = update.title || '正在读取代码…'; dirty = true;
    }
    if (!dirty) return;
    const now = performance.now();
    if (update.sessionUpdate !== 'agent_message_chunk' || now - (chat.pending.renderedAt || 0) > 120) {
      chat.pending.renderedAt = now; chat.render?.();
    }
  }

  async function ask(target, question, chat, configs) {
    if (!question) { toast('请先写下问题'); return; }
    chat.busy = true; chat.error = null; chat.pending = { question, text: '', status: '' }; chat.render?.();
    try {
      const params = { target }; if (chat.id) params.sessionId = chat.id;
      const result = state.project.agentSessions
        ? await streamAsk(params, question, (event, data) => {
          if (event === 'session') { chat.id = data.sessionId; chat.render?.(); }
          if (event === 'update') streamUpdate(chat, data);
        })
        : await post('/api/ask', params, question);
      chat.id = result.sessionId || chat.id;
      if (result.configOptions) { chat.configOptions = result.configOptions; if (configs) fillConfig(configs, chat); }
      chat.turns.push({ question, answer: result.answer, anchors: result.anchors || [] });
      const protection = result.sandbox?.readOnly ? ' · 只读沙箱' : '';
      const stopped = result.stopReason === 'cancelled' ? ' · 已停止' : '';
      chat.provenance = `${result.command}${protection}${stopped} · ${(result.elapsedMs / 1000).toFixed(1)} 秒 · 未经核对，请对照源码`;
    } catch (error) { chat.error = error.message; }
    finally { chat.busy = false; chat.pending = null; chat.render?.(); chat.refreshHistory?.(); }
  }

  async function streamAsk(params, body, onEvent) {
    const query = new URLSearchParams(params);
    const response = await fetch(`/api/ask/stream?${query}`, { method: 'POST', body, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
    if (!response.ok) { const data = await response.json(); throw new Error(data.error || `请求失败 (${response.status})`); }
    const reader = response.body.getReader(), decoder = new TextDecoder();
    let buffer = '', result;
    try {
      while (true) {
        const { value, done } = await reader.read(); buffer += decoder.decode(value, { stream: !done });
        let boundary;
        while ((boundary = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
          const event = frame.match(/^event: (.+)$/m)?.[1];
          const raw = frame.match(/^data: (.+)$/m)?.[1]; if (!raw) continue;
          const data = JSON.parse(raw);
          if (event === 'error') { const error = new Error(data.error); error.status = data.status; throw error; }
          if (event === 'result') result = data;
          else onEvent(event, data);
        }
        if (done) break;
      }
      if (!result) throw new Error('AI 连接已中断，可以从历史对话恢复后重试');
      return result;
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
  }

  async function saveNote(control, target, label, question, answer, anchors) {
    control.disabled = true;
    try {
      const title = `${label}：${question.length > 24 ? question.slice(0, 24) + '…' : question}`;
      const note = await post('/api/note', {}, JSON.stringify({ title, body: answer, targets: [target], anchors, author: 'ai', source: 'explain' }));
      toast(`已保存为解释 ${note.id || ''}，位于 .arch-view/notes`); host.refreshDetail();
    } catch (error) { control.disabled = false; toast(error.message); }
  }

  function agentHint(target) {
    const hint = element('div', 'agent-hint');
    hint.append(element('p', '', '在 arch-view 工具目录的 .env 中设置 ARCH_VIEW_AGENT_PROVIDER=pi-acp，并安装 pi-acp，即可使用 pi 多轮对话。ARCH_VIEW_PI_COMMAND 可选择 pi 路径和模型。重启服务后生效，调用均在只读沙箱中运行。也可以复制上下文包。'),
      button('复制上下文包', 'button small', () => copyContext(target)));
    return hint;
  }

  // ===== 源码与工具 =====

  function answerBody(markdown, className = '') {
    const node = richText(markdown, `markdown ${className}`.trim(), { breaks: false, keepLocalLinks: true });
    linkifyCode(node);
    return node;
  }

  function parseCodeRef(text) {
    const match = String(text || '').trim().match(/^([\w@./-]+\.[A-Za-z][\w.]*)(?::(\d+)(?:-\d+)?)?$/);
    if (!match || match[1].includes('://')) return null;
    return match[1].includes('/') || match[2] ? { path: match[1], line: match[2] ? Number(match[2]) : undefined } : null;
  }

  async function openCode(module, path, line) {
    if (document.querySelector('.ask-output')?.isConnected) { await showSourcePreview(module, path, line); return; }
    if (module) { showSource(module, line); return; }
    if (!path) { toast('没有可打开的源码位置'); return; }
    try {
      const file = await api('/api/file', { path });
      openDrawer(path.split('/').at(-1), file.path, '源码文件');
      const list = element('ol', 'source-list'); file.content.split('\n').forEach(text => list.append(element('li', '', text || ' ')));
      $('#drawer-content').append(list);
      const target = line && list.children[line - 1];
      if (target) { target.classList.add('source-target'); target.scrollIntoView({ block: 'center' }); }
    } catch (error) { toast(error.message); }
  }

  async function showSourcePreview(module, path, line) {
    const host = $('#drawer-content');
    if (!host) { if (module) showSource(module, line); return; }
    let preview = host.querySelector('.ask-source-preview');
    if (!preview) {
      preview = element('section', 'ask-source-preview');
      const head = element('div', 'ask-source-head');
      head.append(element('strong', '', path || module || '源码'), button('关闭预览', 'button small', () => preview.remove()));
      preview.append(head, element('div', 'ask-source-body'));
      host.append(preview);
    }
    preview.querySelector('strong').textContent = path || module || '源码';
    const body = preview.querySelector('.ask-source-body');
    body.replaceChildren(element('p', 'ask-waiting', '正在打开源码…'));
    preview.scrollIntoView({ block: 'nearest' });
    try {
      const file = module ? await api('/api/source', { module }) : await api('/api/file', { path });
      const list = element('ol', 'source-list');
      file.content.split('\n').forEach(text => list.append(element('li', '', text || ' ')));
      body.replaceChildren(list);
      preview.querySelector('strong').textContent = file.path;
      const target = line && list.children[line - 1];
      if (target) { target.classList.add('source-target'); target.scrollIntoView({ block: 'center' }); }
    } catch (error) { toast(error.message); preview.remove(); }
  }

  /** 把回答里的路径、`路径:行号` 和项目内链接变成可点击的源码跳转。 */
  function linkifyCode(root) {
    for (const link of root.querySelectorAll('a[href]')) {
      const href = link.getAttribute('href') || '';
      if (/^https?:/i.test(href) || href.startsWith('#')) continue;
      const ref = parseCodeRef(href) || parseCodeRef(decodeURIComponent(href));
      link.removeAttribute('href');
      if (!ref) continue;
      link.classList.add('code-link');
      link.addEventListener('click', event => { event.preventDefault(); openCode(null, ref.path, ref.line); });
    }
    for (const code of root.querySelectorAll('code')) {
      const ref = parseCodeRef(code.textContent);
      if (!ref || code.closest('pre')) continue;
      const link = button(code.textContent, 'text-button code-link', () => openCode(null, ref.path, ref.line));
      code.replaceWith(link);
    }
  }

  async function post(path, params, body) {
    const query = new URLSearchParams(params);
    const response = await fetch(`${path}${query.size ? '?' + query : ''}`, { method: 'POST', body, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
    const data = await response.json();
    if (!response.ok) { const error = new Error(data.error || `请求失败 (${response.status})`); error.status = response.status; throw error; }
    return data;
  }

  async function copyText(text) {
    if (navigator.clipboard?.writeText) { await navigator.clipboard.writeText(text); return; }
    const area = element('textarea'); area.value = text; document.body.append(area); area.select();
    document.execCommand('copy'); area.remove();
  }

  function formatDate(value) {
    return value ? String(value).slice(0, 10) : '时间未知';
  }

  return { loadTraces, openTrace, closeTrace, traceActive, mapNodes, decorateMap, renderTraceDetail, aiSection };
}
