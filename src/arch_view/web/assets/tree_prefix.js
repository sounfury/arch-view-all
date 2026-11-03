/*
 * 职责：把导航树里已经加载的、没有分叉的包前缀收成一行。
 * 只使用调用方已经拿到的子层，不会为了压缩去请求更深层。
 * 构建模块和源码文件保持独立节点，不能并进包前缀。
 */

function nodeId(node) {
  return String(node.id || '').replace(/\|file$/, '');
}

// 从当前节点沿着“恰好一个子包”往下走，子层没加载就停。
export function collapsePackageChain(node, parentPath, lookup) {
  const chain = [node];
  const path = node.leaf ? [...parentPath, node.id] : [...parentPath, nodeId(node)];
  if (node.leaf || node.buildModule) return { nodes: chain, path };
  let cursor = [...path];
  while (true) {
    const view = lookup(cursor.join('/'));
    if (!view) break;
    const children = view.nodes || [];
    if (children.length !== 1 || children[0].leaf || children[0].buildModule) break;
    chain.push(children[0]);
    cursor = [...cursor, nodeId(children[0])];
  }
  return { nodes: chain, path: cursor };
}

// 合并后的一行仍要标出当前路径和选中项；单节点时与原来的树标记一致。
export function chainPresence(statePath, selectedId, parentPath, chain) {
  const atPath = parentPath.every((part, index) => statePath[index] === part);
  if (!atPath) return '';
  const first = chain.nodes[0];
  if (chain.nodes.length === 1) {
    if (first.leaf) return statePath.length === parentPath.length && selectedId === first.id ? 'current' : '';
    const id = nodeId(first);
    if (statePath[parentPath.length] !== id) {
      return statePath.length === parentPath.length && selectedId === first.id ? 'current' : '';
    }
    return statePath.length === parentPath.length + 1 && !selectedId ? 'current' : 'on-path';
  }
  const ids = chain.nodes.map(nodeId);
  let matched = 0;
  while (matched < ids.length && statePath[parentPath.length + matched] === ids[matched]) matched += 1;
  if (!matched) return '';
  if (statePath.length === parentPath.length + matched && !selectedId) return 'current';
  return matched === ids.length ? 'on-path' : '';
}
