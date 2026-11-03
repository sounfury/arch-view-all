#!/usr/bin/env node
// 职责：把 adapter 的有限启动参数转换为固定的只读 pi RPC 启动，不经过 shell。
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { constants } = require('node:os');
const config = __ARCH_VIEW_PI_ACP_CONFIG__;

function sessionFile(file) {
  if (typeof file !== 'string' || !path.isAbsolute(file)) throw new Error();
  const root = path.resolve(config.sessions);
  const relative = path.relative(root, file);
  if (!relative || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) throw new Error();
  // 每级都拒绝符号链接；加载仅接受私有会话目录内已存在的普通文件。
  let current = root;
  const parts = relative.split(path.sep);
  if (!fs.lstatSync(root).isDirectory()) throw new Error();
  for (const [index, part] of parts.entries()) {
    current = path.join(current, part);
    const stat = fs.lstatSync(current);
    const valid = index === parts.length - 1 ? stat.isFile() : stat.isDirectory();
    if (!valid) throw new Error();
  }
  return current;
}

function adapterArgs(args) {
  if (args[0] !== '--mode' || args[1] !== 'rpc' || args[2] !== '--no-themes') throw new Error();
  if (args.length === 3) return [];
  if (args.length !== 5 || args[3] !== '--session') throw new Error();
  return ['--session', sessionFile(args[4])];
}

function run(args) {
  const child = spawn(config.command, [...config.args, '--session-dir', config.sessions, ...args], {
    stdio: 'inherit', shell: false,
    env: { ...process.env, PI_CODING_AGENT_SESSION_DIR: config.sessions },
  });
  // adapter 关闭底层进程时，把终止信号继续交给真实 pi，避免留下孤儿进程。
  const handlers = new Map();
  for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
    const handler = () => child.kill(signal);
    handlers.set(signal, handler);
    process.on(signal, handler);
  }
  child.on('error', (error) => {
    process.stderr.write('无法启动私有 pi RPC 进程。\n');
    process.exitCode = error.code === 'ENOENT' ? 127 : 126;
  });
  child.on('close', (code, signal) => {
    for (const [name, handler] of handlers) process.removeListener(name, handler);
    if (signal) {
      process.exitCode = 128 + constants.signals[signal];
      process.kill(process.pid, signal);
    } else if (Number.isInteger(code) && code >= 0) {
      process.exitCode = code;
    }
  });
}

try {
  run(adapterArgs(process.argv.slice(2)));
} catch {
  // 错误不包含配置、凭据、参数或文件路径。
  process.stderr.write('pi-acp 启动参数或私有会话路径被拒绝。\n');
  process.exitCode = 126;
}
