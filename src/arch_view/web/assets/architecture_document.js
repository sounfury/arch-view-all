/* 工具函数：读取架构文档的子系统职责、人工实现状态和 Mermaid 数据流，并生成安全的流程图。 */

export function parseArchitectureDocument(content) {
  const tokens = marked.lexer(content);
  const sections = [];
  let parent = '';
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index];
    if (token.type !== 'heading' || token.depth < 2) continue;
    if (token.depth === 2) parent = text(token.text, true);
    let end = index + 1;
    while (end < tokens.length && !(tokens[end].type === 'heading' && tokens[end].depth <= token.depth)) end++;
    const body = tokens.slice(index + 1, end);
    const markdown = body.map(t => t.raw || '').join('');
    const title = text(token.text, true);
    const cleanTitle = title.replace(/^\d+[.、．]\s*/, '').replace(/\s*[（(]`?[^()（）]*\/[^()（）]*[）)]\s*$/, '').trim();
    const direct = cleanTitle.match(/^([\w\u4e00-\u9fff/.-]+)\s*[·：:|—–]\s*(.+)$/);
    const reverse = cleanTitle.match(/^(.+?)\s*[（(]([\w\u4e00-\u9fff/.-]+)[）)]$/);
    const link = markdown.match(/\]\(#module=([^\s)]+)\)/);
    const module = link ? decodeModule(link[1]) : direct?.[1] || reverse?.[2];
    const fields = body.filter(t => t.type === 'list').flatMap(t => t.items).map(item => text(item.text));
    const summary = field(fields, '职责|责任|用途') || text(body.find(t => t.type === 'paragraph')?.text || '');
    const rawStatus = field(fields, '实现状态').replace(/[。；;]\s*$/, '');
    const status = ({ '已实现': '已完成', '待实现': '未完成' })[rawStatus] || (['已完成', '未完成', '进行中'].includes(rawStatus) ? rawStatus : '');
    const scopes = field(fields, '覆盖范围').replace(/[。；;]\s*$/, '').split(/[、,，;；\s]+/).filter(Boolean);
    sections.push({ title, heading: token.text, depth: token.depth, parent, markdown, summary, status,
      module: module || null, scopes, label: direct?.[2] || reverse?.[1] || cleanTitle });
  }
  const subsystemSection = sections.find(s => s.depth === 2 && s.title === '核心子系统与职责');
  const roles = sections.filter(s => s.module && (subsystemSection ? s.parent === subsystemSection.title && s.depth > 2 : true));
  const flowSection = sections.find(s => s.depth === 2 && s.title === '数据流');
  const flowTokens = flowSection ? marked.lexer(flowSection.markdown) : tokens;
  const flows = flowTokens.filter(t => t.type === 'code' && /^mermaid(?:\s|$)/i.test(t.lang || '') &&
    /^\s*(?:%%[^\n]*\n\s*)*(?:flowchart|graph)\s/i.test(t.text)).map((t, index) => ({
      title: `${flowSection?.title || '数据流'}${index ? ` ${index + 1}` : ''}`, source: t.text
    }));
  return { sections, roles, flows };
}

export async function renderDataFlow(source) {
  if (!globalThis.mermaid) throw new Error('流程图组件未加载，请刷新页面后重试。');
  if (!initialized) {
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true,
      layout: 'dagre', look: 'classic', theme: 'base', htmlLabels: false,
      flowchart: { htmlLabels: false, nodeSpacing: 36, rankSpacing: 46, wrappingWidth: 300 },
      themeVariables: { fontFamily: 'Microsoft YaHei, sans-serif', fontSize: '16px', primaryColor: '#ffffff',
        primaryTextColor: '#243d34', primaryBorderColor: '#c3d4cc', lineColor: '#9bb2a8', background: '#fbfcfa',
        edgeLabelBackground: '#f5f8f5', tertiaryColor: '#f0f5f1' } });
    initialized = true;
  }
  const { svg } = await mermaid.render(`architecture-flow-${++sequence}`, source);
  const host = document.createElement('div');
  host.innerHTML = DOMPurify.sanitize(svg, { USE_PROFILES: { svg: true, svgFilters: true },
    FORBID_TAGS: ['foreignObject', 'a'], FORBID_ATTR: ['onload', 'onclick'] });
  const diagram = host.querySelector('svg');
  if (!diagram) throw new Error('未能生成数据流图。');
  const nodes = [...diagram.querySelectorAll('g.node')].map(el => ({
    id: el.getAttribute('data-id') || el.id.match(/flowchart-(.+)-\d+$/)?.[1] || el.id,
    label: el.textContent.trim(), element: el
  }));
  if (nodes.length && nodes.every(node => !node.label)) throw new Error('节点文字未能安全绘制，请使用标准 Mermaid 文本标签。');
  return { diagram, nodes };
}

// 软件包声明覆盖自身及子包；只比较归属，不从源码推断人工实现状态。
export function uncoveredModules(modules, roles, rootDepth) {
  const prefixes = roles.flatMap(role => [role.module, ...(role.scopes || [])])
    .map(scope => scope.replace(/\|file$/, '').split(/[/.]/).filter(Boolean)).filter(parts => parts.length);
  return modules.filter(module => {
    const parts = module.split('.').slice(rootDepth);
    return !prefixes.some(prefix => prefix.length <= parts.length && prefix.every((part, index) => part === parts[index]));
  });
}

// ===== 私有方法 =====

let initialized = false;
let sequence = 0;

function text(markdown, inline = false) {
  const host = document.createElement('div');
  host.innerHTML = DOMPurify.sanitize(inline ? marked.parseInline(markdown || '') : marked.parse(markdown || ''), { USE_PROFILES: { html: true } });
  return host.textContent.replace(/\s+/g, ' ').trim();
}

function field(items, name) {
  const pattern = new RegExp(`^(?:${name})\\s*[:：]\\s*(.+)$`);
  return items.map(item => item.match(pattern)?.[1]).find(Boolean) || '';
}

function decodeModule(value) {
  try { return decodeURIComponent(value); } catch { return value; }
}
