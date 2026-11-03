// 职责：以真实子进程验证私有 pi RPC wrapper 的参数、路径、标准流和退出行为。
'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { once } = require('node:events');
const readline = require('node:readline');
const { test } = require('node:test');

const template = fs.readFileSync(path.resolve(__dirname, '../../src/arch_view/context/pi_acp_launch.cjs'), 'utf8');
const baseline = ['--mode', 'rpc', '--no-themes'];
const forced = ['--mode', 'rpc', '--no-extensions', '--no-mcp', '--tools', 'read,grep,find,ls',
  '--no-skills', '--no-prompt-templates', '--no-themes', '--no-context-files', '--no-approve', '--offline'];

function fixture(t, script) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pi-acp-launch-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const root = path.join(directory, 'space "quote" \'single\' \\slash $dollar;$(literal)`tick`');
  fs.mkdirSync(root, { mode: 0o700 });
  const sessions = path.join(root, 'pi-sessions');
  fs.mkdirSync(sessions, { mode: 0o700 });
  const command = path.join(root, 'fake pi.cjs');
  fs.writeFileSync(command, '#!/usr/bin/env node\n' + script, { mode: 0o700 });
  const args = ['--model', 'custom/demo $literal;`tick`', '--provider', 'custom', '--thinking', 'high',
    '--models', 'custom/*', ...forced];
  const config = { command, args, sessions };
  const wrapper = path.join(root, 'pi_acp_launch.cjs');
  fs.writeFileSync(wrapper, template.replace('__ARCH_VIEW_PI_ACP_CONFIG__', () => JSON.stringify(config)), { mode: 0o700 });
  return { root, sessions, wrapper, config };
}

function execute(f, args = baseline, options = {}) {
  return spawnSync(f.wrapper, args, {
    encoding: 'utf8', timeout: 5000, input: 'stdin\u0000字节\n', ...options,
  });
}

const echo = `
const chunks = [];
process.stdin.on('data', chunk => chunks.push(chunk));
process.stdin.on('end', () => {
  process.stdout.write(JSON.stringify({args:process.argv.slice(2),sessions:process.env.PI_CODING_AGENT_SESSION_DIR}));
  process.stderr.write(Buffer.concat(chunks));
  process.exitCode = 37;
});
`;

test('固化模型与只读 RPC 参数，原样转发标准流、特殊路径和非零退出码', t => {
  const f = fixture(t, echo);
  const result = execute(f, baseline, { env: { ...process.env, PI_CODING_AGENT_SESSION_DIR: '/untrusted' } });
  assert.equal(result.error, undefined);
  assert.equal(result.status, 37);
  assert.equal(result.stderr, 'stdin\u0000字节\n');
  assert.deepEqual(JSON.parse(result.stdout), {
    args: [...f.config.args, '--session-dir', f.sessions], sessions: f.sessions,
  });
  assert.equal(fs.statSync(f.wrapper).mode & 0o777, 0o700);
});

test('拒绝 adapter 覆盖工具、模式、会话目录、扩展或追加参数，不回显凭据', t => {
  const f = fixture(t, echo);
  const attempts = [[], ['--mode', 'text', '--no-themes'], ['--mode', 'rpc'],
    [...baseline, '--tools', 'bash'], [...baseline, '--session-dir', '/tmp'],
    [...baseline, '--extension', 'evil'], [...baseline, '--api-key', 'fixture-private-key'],
    [...baseline, '--no-session'], [...baseline, '--session'],
    [...baseline, '--session', f.sessions, '--tools', 'bash'],
    [...baseline, '--mode', 'rpc'], [...baseline, '--', 'prompt']];
  for (const args of attempts) {
    const result = execute(f, args);
    assert.equal(result.status, 126);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, 'pi-acp 启动参数或私有会话路径被拒绝。\n');
  }
});

test('加载私有目录下已有普通 session 文件，特殊字符保持字面量', t => {
  const f = fixture(t, echo);
  const nested = path.join(f.sessions, 'nested space');
  fs.mkdirSync(nested);
  const session = path.join(nested, 'session "quote" \\slash $dollar;$(literal).jsonl');
  fs.writeFileSync(session, '{}\n');
  const result = execute(f, [...baseline, '--session', session]);
  assert.equal(result.status, 37);
  assert.deepEqual(JSON.parse(result.stdout).args,
    [...f.config.args, '--session-dir', f.sessions, '--session', session]);
});

test('拒绝越界、前缀碰撞、目录、缺失文件及任意层级的符号链接', t => {
  const f = fixture(t, echo);
  const outside = path.join(f.root, 'outside.jsonl');
  fs.writeFileSync(outside, '{}');
  const prefix = f.sessions + '-outside';
  fs.mkdirSync(prefix);
  fs.writeFileSync(path.join(prefix, 'session.jsonl'), '{}');
  fs.symlinkSync(outside, path.join(f.sessions, 'external-link'));
  const inside = path.join(f.sessions, 'regular.jsonl');
  fs.writeFileSync(inside, '{}');
  fs.symlinkSync(inside, path.join(f.sessions, 'internal-link'));
  fs.symlinkSync(f.root, path.join(f.sessions, 'linked-directory'));
  const fifo = path.join(f.sessions, 'fifo');
  const mkfifo = spawnSync('mkfifo', [fifo]);
  assert.equal(mkfifo.status, 0);
  const attempts = ['relative.jsonl', outside, f.sessions, path.join(f.sessions, 'missing'),
    path.join(prefix, 'session.jsonl'), path.join(f.sessions, '..', 'outside.jsonl'),
    path.join(f.sessions, 'external-link'), path.join(f.sessions, 'internal-link'),
    path.join(f.sessions, 'linked-directory', 'outside.jsonl'), fifo];
  for (const session of attempts) {
    const result = execute(f, [...baseline, '--session', session]);
    assert.equal(result.status, 126);
    assert.equal(result.stdout, '');
    assert.equal(result.stderr, 'pi-acp 启动参数或私有会话路径被拒绝。\n');
  }
});

test('底层可执行文件缺失时返回 127，不回显路径', t => {
  const f = fixture(t, echo);
  fs.unlinkSync(f.config.command);
  const result = execute(f);
  assert.equal(result.status, 127);
  assert.equal(result.stdout, '');
  assert.equal(result.stderr, '无法启动私有 pi RPC 进程。\n');
});

test('转发真实 pi 的终止信号', t => {
  const f = fixture(t, "process.kill(process.pid, 'SIGTERM');");
  const result = execute(f);
  assert.equal(result.status, null);
  assert.equal(result.signal, 'SIGTERM');
});

test('adapter 终止 wrapper 时继续终止真实 pi', { timeout: 5000 }, async t => {
  const f = fixture(t, "process.on('SIGTERM',()=>process.exit(43));process.stdout.write('ready\\n');setInterval(()=>{},1000);");
  const child = spawn(f.wrapper, baseline, { stdio: ['pipe', 'pipe', 'pipe'] });
  t.after(() => child.kill('SIGKILL'));
  const exited = once(child, 'close');
  await once(child.stdout, 'data');
  child.kill('SIGTERM');
  assert.deepEqual(await exited, [43, null]);
});

// 可选验证已安装的真实 adapter；fake RPC 仅提供握手数据，不连接模型。
const adapter = process.env.PI_ACP_ADAPTER_PATH;
const fakeRpc = `
const fs=require('node:fs'),path=require('node:path'),readline=require('node:readline');
const args=process.argv.slice(2), dir=args[args.indexOf('--session-dir')+1];
const index=args.indexOf('--session');
const sessionFile=index<0?path.join(dir,'fake-session.jsonl'):args[index+1];
fs.writeFileSync(process.env.FAKE_PI_ARGS, JSON.stringify(args)+'\\n',{flag:'a'});
fs.writeFileSync(sessionFile,JSON.stringify({type:'session',version:3,id:'fake-session',cwd:process.cwd(),timestamp:new Date().toISOString()})+'\\n');
const model={id:'demo',name:'Demo',provider:'custom',reasoning:true,contextWindow:200000,maxTokens:8192};
readline.createInterface({input:process.stdin}).on('line',line=>{
 const request=JSON.parse(line);
 const values={get_state:{sessionId:'fake-session',sessionFile,model,thinkingLevel:'high',isStreaming:false,messages:[]},
  get_available_models:{models:[model]},get_available_thinking_levels:{levels:['low','high']},
  get_commands:{commands:[]},get_session_stats:{contextTokens:0,contextWindow:200000}};
 process.stdout.write(JSON.stringify({id:request.id,type:'response',command:request.type,success:true,data:values[request.type]||{}})+'\\n');
});
`;

function acpClient(child) {
  let nextId = 0;
  const pending = new Map();
  const lines = readline.createInterface({ input: child.stdout });
  lines.on('line', line => {
    const response = JSON.parse(line);
    if (pending.has(response.id)) {
      pending.get(response.id)(response);
      pending.delete(response.id);
    }
  });
  return async (method, params) => {
    const id = ++nextId;
    const response = new Promise(resolve => pending.set(id, resolve));
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    const result = await response;
    assert.equal(result.error, undefined, JSON.stringify(result.error));
    return result.result;
  };
}

test('发布版 adapter 通过私有 wrapper 新建并加载会话，HOME 隔离 session-map',
  { skip: !adapter, timeout: 15000 }, async t => {
    const f = fixture(t, fakeRpc);
    const home = path.join(f.root, 'home');
    const agent = path.join(f.root, 'pi-agent');
    fs.mkdirSync(home);
    fs.mkdirSync(agent);
    const log = path.join(f.root, 'args.jsonl');
    const environment = { ...process.env, NODE_OPTIONS: '', HOME: home, PI_CODING_AGENT_DIR: agent,
      PI_CODING_AGENT_SESSION_DIR: f.sessions, PI_ACP_PI_COMMAND: f.wrapper,
      PI_SKIP_VERSION_CHECK: '1', PI_OFFLINE: '1', PI_TELEMETRY: '0', FAKE_PI_ARGS: log };
    const child = spawn(process.execPath, [adapter], {
      cwd: f.root, stdio: 'pipe', env: environment,
    });
    t.after(() => child.kill('SIGKILL'));
    let stderr = '';
    child.stderr.on('data', chunk => { stderr += chunk; });
    const request = acpClient(child);
    const initialized = await request('initialize', { protocolVersion: 1, clientCapabilities: {} });
    assert.equal(initialized.agentInfo.version, '0.0.34');
    const created = await request('session/new', { cwd: f.root, mcpServers: [] });
    const mapFile = path.join(home, '.pi/pi-acp/session-map.json');
    // adapter 在 newSession 后异步保存会话，等待其已排队的本地 RPC 完成。
    await request('session/list', {});
    assert.equal(fs.existsSync(mapFile), true, stderr);
    const entries = JSON.parse(fs.readFileSync(mapFile, 'utf8')).sessions;
    assert.equal(entries[created.sessionId].sessionFile, path.join(f.sessions, 'fake-session.jsonl'));
    child.kill('SIGTERM');
    await once(child, 'close');
    const restored = spawn(process.execPath, [adapter], {
      cwd: f.root, stdio: 'pipe', env: environment,
    });
    t.after(() => restored.kill('SIGKILL'));
    restored.stderr.resume();
    const restoreRequest = acpClient(restored);
    await restoreRequest('initialize', { protocolVersion: 1, clientCapabilities: {} });
    await restoreRequest('session/load', { sessionId: created.sessionId, cwd: f.root, mcpServers: [] });
    const launches = fs.readFileSync(log, 'utf8').trim().split('\n').map(line => JSON.parse(line));
    assert.deepEqual(launches[0], [...f.config.args, '--session-dir', f.sessions]);
    assert.deepEqual(launches[1], [...f.config.args, '--session-dir', f.sessions,
      '--session', path.join(f.sessions, 'fake-session.jsonl')]);
    restored.kill('SIGTERM');
    await once(restored, 'close');
  });
