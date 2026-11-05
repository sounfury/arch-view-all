'use strict';

// Runs standalone with a fake private copy, or under provider-spec's real sandbox.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { createRequire } = require('node:module');
const { promisify } = require('node:util');
const { test, after } = require('node:test');
const sandboxed = process.env.PI_LOCK_TEST_SANDBOX === '1';
const scratch = sandboxed ? path.dirname(process.env.PI_CODING_AGENT_DIR)
  : fs.mkdtempSync(path.join(os.tmpdir(), 'pi locks "quoted" '));
const agent = path.join(scratch, 'pi-agent');
const host = sandboxed ? process.env.PI_LOCK_TEST_HOST
  : fs.mkdtempSync(path.join(os.tmpdir(), 'pi-host-'));
if (!sandboxed) {
  fs.mkdirSync(agent);
  fs.copyFileSync(path.resolve(__dirname, '../../src/arch_view/context/pi_readonly_locks.cjs'),
    path.join(scratch, 'pi_readonly_locks.cjs'));
  require(path.join(scratch, 'pi_readonly_locks.cjs'));
  after(() => {
    fs.rmSync(scratch, { recursive: true, force: true });
    fs.rmSync(host, { recursive: true, force: true });
  });
}
const utimes = promisify(fs.utimes);
const stat = promisify(fs.stat);
const first = new Date('2001-02-03T04:05:06.007Z');
const second = new Date('2002-03-04T05:06:07.008Z');

function lock(name) {
  const file = path.join(agent, name + '.lock');
  fs.mkdirSync(file);
  return file;
}

function check(value, atime = first, mtime = second) {
  assert.equal(value.atime.getTime(), atime.getTime());
  assert.equal(value.mtime.getTime(), mtime.getTime());
  assert.equal(value.atimeMs, atime.getTime());
  assert.equal(value.mtimeMs, mtime.getTime());
  assert(value.isDirectory());
}

function checkNativeMtime(file, expected) {
  const actual = fs.lstatSync(file).mtimeMs;
  // Native utimes can truncate fractional seconds to microsecond precision.
  assert.ok(Math.abs(actual - expected.getTime()) <= 0.001,
    `native mtime ${actual} differs from ${expected.getTime()}`);
}

async function originalBehavior(file) {
  if (sandboxed) {
    assert.throws(() => fs.utimesSync(file, first, second), { code: 'EPERM' });
    await assert.rejects(utimes(file, first, second), { code: 'EPERM' });
    await assert.rejects(fs.promises.utimes(file, first, second), { code: 'EPERM' });
  } else {
    fs.utimesSync(file, first, second);
    checkNativeMtime(file, second);
    await utimes(file, second, first);
    checkNativeMtime(file, first);
    await fs.promises.utimes(file, first, second);
    checkNativeMtime(file, second);
  }
}

test('callback timestamps are asynchronous and never change native lock metadata', async () => {
  const file = lock('callback');
  const native = fs.lstatSync(file);
  let synchronous = true;
  const done = new Promise((resolve, reject) => {
    fs.utimes(Buffer.from(file), first, second, (error) => {
      try {
        assert.equal(synchronous, false);
        assert.equal(error, null);
        resolve();
      } catch (failure) { reject(failure); }
    });
  });
  synchronous = false;
  await done;
  check(await stat(file));
  check(await stat(file, {}));
  assert.equal(fs.lstatSync(file).mtimeMs, native.mtimeMs);
  assert.equal(fs.lstatSync(file).atimeMs, native.atimeMs);
});

test('sync, promise, URL and relative paths share the same virtual clock', async () => {
  const file = lock('sync-promise');
  fs.utimesSync(pathToFileURL(file), first, second);
  check(fs.statSync(file));
  check(await fs.promises.stat(file));
  await fs.promises.utimes(path.relative(process.cwd(), file), second.getTime() / 1000,
    String(first.getTime() / 1000));
  check(fs.statSync(file), second, first);
  check(await stat(file), second, first);
});

test('bigint stats retain coherent Date, millisecond and nanosecond fields', async () => {
  const file = lock('bigint');
  fs.utimesSync(file, first, second);
  for (const value of [fs.statSync(file, { bigint: true }),
    await stat(file, { bigint: true }), await fs.promises.stat(file, { bigint: true })]) {
    assert.equal(value.mtimeMs, BigInt(second.getTime()));
    assert.equal(value.mtimeNs, BigInt(second.getTime()) * 1000000n);
    assert.equal(value.atimeMs, BigInt(first.getTime()));
    assert.equal(value.atimeNs, BigInt(first.getTime()) * 1000000n);
    assert.equal(value.mtime.getTime(), second.getTime());
    assert.equal(value.atime.getTime(), first.getTime());
    assert.equal(typeof value.ino, 'bigint');
  }
});

test('ESM named imports of fs and fs/promises see the patches', async () => {
  const esm = await import('node:fs');
  const esmPromises = await import('node:fs/promises');
  const file = lock('esm');
  esm.utimesSync(file, first, second);
  check(esm.statSync(file));
  await esmPromises.utimes(file, second, first);
  check(await esmPromises.stat(file), second, first);
  assert.equal(esm.utimes, fs.utimes);
  assert.equal(esmPromises.stat, fs.promises.stat);
});

test('removed and recreated locks cannot inherit a virtual timestamp', async () => {
  const file = lock('recreated');
  await utimes(file, first, second);
  fs.rmdirSync(file);
  assert.throws(() => fs.statSync(file), { code: 'ENOENT' });
  assert.equal(fs.statSync(file, { throwIfNoEntry: false }), undefined);
  await assert.rejects(stat(file), { code: 'ENOENT' });
  await assert.rejects(fs.promises.stat(file), { code: 'ENOENT' });
  fs.mkdirSync(file);
  const native = fs.lstatSync(file).mtimeMs;
  assert.equal(fs.statSync(file).mtimeMs, native);
  assert.equal((await stat(file)).mtimeMs, native);
  assert.equal((await fs.promises.stat(file)).mtimeMs, native);
  await fs.promises.utimes(file, second, first);
  check(fs.statSync(file), second, first);
});

test('host locks, sibling directories, ordinary files and symlink escapes use original APIs', async () => {
  const hostLock = path.join(host, 'outside.lock');
  if (!sandboxed) fs.mkdirSync(hostLock);
  const sibling = path.join(scratch, 'pi-agent-other');
  fs.mkdirSync(sibling);
  const siblingLock = path.join(sibling, 'sibling.lock');
  fs.mkdirSync(siblingLock);
  const ordinary = path.join(agent, 'ordinary');
  fs.mkdirSync(ordinary);
  const ordinaryFile = path.join(agent, 'file.lock');
  fs.writeFileSync(ordinaryFile, 'no credentials');
  const escape = path.join(agent, 'escape.lock');
  fs.symlinkSync(hostLock, escape);
  const traversal = agent + '/../' + path.relative(scratch, host) + '/outside.lock';
  for (const file of [hostLock, siblingLock, ordinary, ordinaryFile, traversal]) {
    await originalBehavior(file);
  }
  if (sandboxed) {
    await assert.rejects(utimes(escape, first, second), { code: 'EPERM' });
    assert.throws(() => fs.utimesSync(escape, first, second), { code: 'EPERM' });
    await assert.rejects(fs.promises.utimes(escape, first, second), { code: 'EPERM' });
  } else {
    await utimes(escape, first, second);
    checkNativeMtime(hostLock, second);
  }
  assert.equal(fs.statSync(escape).mtimeMs, fs.statSync(hostLock).mtimeMs);
  const replaced = lock('replaced-by-link');
  fs.utimesSync(replaced, first, second);
  fs.rmdirSync(replaced);
  fs.symlinkSync(hostLock, replaced);
  assert.equal(fs.statSync(replaced).mtimeMs, fs.statSync(hostLock).mtimeMs);
});

test('invalid arguments and missing paths retain Node errors and callbacks run once', async () => {
  const file = lock('errors');
  assert.throws(() => fs.utimes(file, first, second), { code: 'ERR_INVALID_ARG_TYPE' });
  assert.throws(() => fs.utimes(file, NaN, second, () => {}), { code: 'ERR_INVALID_ARG_TYPE' });
  assert.throws(() => fs.utimesSync(file, first, NaN), { code: 'ERR_INVALID_ARG_TYPE' });
  await assert.rejects(fs.promises.utimes(file, Infinity, second), { code: 'ERR_INVALID_ARG_TYPE' });
  assert.throws(() => fs.stat(file, {}), { code: 'ERR_INVALID_ARG_TYPE' });
  let calls = 0;
  // seccomp rejects the syscall before the kernel can check path existence.
  const missingCode = sandboxed ? 'EPERM' : 'ENOENT';
  await new Promise((resolve, reject) => fs.utimes(file + '.missing', first, second, (error) => {
    calls++;
    try { assert.equal(error.code, missingCode); resolve(); } catch (failure) { reject(failure); }
  }));
  assert.equal(calls, 1);
  assert.throws(() => fs.utimesSync(file + '.missing', first, second), { code: missingCode });
  await assert.rejects(fs.promises.utimes(file + '.missing', first, second), { code: missingCode });
});

test('proper-lockfile precision probe and repeated mtime ownership comparisons', async () => {
  const file = lock('probe');
  let mtime = new Date(Math.ceil(Date.now() / 1000) * 1000 + 5);
  await utimes(file, mtime, mtime);
  assert.equal((await stat(file)).mtime.getTime(), mtime.getTime());
  for (let update = 0; update < 4; update++) {
    assert.equal((await stat(file)).mtime.getTime(), mtime.getTime());
    mtime = new Date(mtime.getTime() + 1001);
    await utimes(file, mtime, mtime);
    assert.equal((await stat(file)).mtime.getTime(), mtime.getTime());
  }
});

// Exercise Pi's installed dependency when available, without reading settings/auth.
let lockfile;
for (const directory of (process.env.PATH || '').split(path.delimiter)) {
  const executable = path.join(directory, 'pi');
  if (!fs.existsSync(executable)) continue;
  try { lockfile = createRequire(fs.realpathSync(executable))('proper-lockfile'); break; } catch {}
}
test('installed Pi proper-lockfile sync acquisition, contention and two actual renewals',
  { skip: !lockfile }, async () => {
    const file = path.join(agent, 'actual.json');
    fs.writeFileSync(file, '{}');
    lockfile.lockSync(file, { realpath: false })();
    lockfile.lockSync(file, { realpath: false })();
    let compromised;
    const release = await lockfile.lock(file, { realpath: false, update: 1000, stale: 4000,
      onCompromised: (error) => { compromised = error; } });
    try {
      assert.throws(() => lockfile.lockSync(file, { realpath: false }), { code: 'ELOCKED' });
      let previous = fs.statSync(file + '.lock').mtimeMs;
      for (let update = 0; update < 2; update++) {
        await new Promise((resolve) => setTimeout(resolve, 1150));
        const current = fs.statSync(file + '.lock').mtimeMs;
        assert(current > previous);
        previous = current;
      }
      assert.equal(compromised, undefined);
    } finally { await release(); }
  });
