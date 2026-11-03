// 职责：仅为本次私有 pi 配置的锁目录虚拟化时间戳，兼容只读沙箱中的加锁与续租。
// 核心入口：Node 预加载后覆写 fs 的时间与状态接口；真实文件元数据仍由系统沙箱保护。
'use strict';

// Each invocation owns its pi-agent copy. Only lock-directory timestamps are
// virtual; the sandbox still enforces all real filesystem metadata operations.
const fs = require('node:fs');
const path = require('node:path');
const { syncBuiltinESMExports } = require('node:module');
const promises = fs.promises;
const original = {
  utimes: fs.utimes, utimesSync: fs.utimesSync,
  stat: fs.stat, statSync: fs.statSync, realpathSync: fs.realpathSync,
  promiseUtimes: promises.utimes, promiseStat: promises.stat,
  promiseRealpath: promises.realpath,
};
const root = original.realpathSync(path.join(__dirname, 'pi-agent'));
const times = new Map();

function inside(canonical) {
  return canonical.startsWith(root + path.sep) && canonical.endsWith('.lock');
}

function identity(stat) {
  // Native timestamps also distinguish a replacement if the inode is recycled.
  return [stat.dev, stat.ino, stat.birthtimeNs, stat.ctimeNs, stat.mtimeNs].join(':');
}

async function lockDirectory(file) {
  try {
    const canonical = await original.promiseRealpath(file);
    if (!inside(canonical)) return undefined;
    const stat = await original.promiseStat(canonical, { bigint: true });
    return stat.isDirectory() ? { canonical, identity: identity(stat) } : undefined;
  } catch {
    // Let the original API report path/permission errors with its own semantics.
    return undefined;
  }
}

function lockDirectorySync(file) {
  try {
    const canonical = original.realpathSync(file);
    if (!inside(canonical)) return undefined;
    const stat = original.statSync(canonical, { bigint: true });
    return stat.isDirectory() ? { canonical, identity: identity(stat) } : undefined;
  } catch {
    return undefined;
  }
}

function timestamps(atime, mtime) {
  // Use Node's own conversion for Dates, numeric strings, negative times and
  // invalid arguments, exactly as fs.utimes does before entering the kernel.
  return { atime: fs._toUnixTimestamp(atime) * 1000,
    mtime: fs._toUnixTimestamp(mtime) * 1000 };
}

function remember(lock, value) {
  times.set(lock.canonical, { identity: lock.identity, ...value });
}

function overlay(stat, lock) {
  if (!stat || !stat.isDirectory() || !lock) return stat;
  const value = times.get(lock.canonical);
  if (!value) return stat;
  if (value.identity !== lock.identity) {
    times.delete(lock.canonical);
    return stat;
  }
  for (const name of ['atime', 'mtime']) {
    const ms = value[name];
    stat[name] = new Date(ms);
    if (typeof stat[name + 'Ms'] === 'bigint') {
      const wholeMs = Math.trunc(ms);
      const ns = BigInt(wholeMs) * 1000000n + BigInt(Math.trunc((ms - wholeMs) * 1e6));
      stat[name + 'Ns'] = ns;
      stat[name + 'Ms'] = ns / 1000000n;
    } else {
      stat[name + 'Ms'] = ms;
    }
  }
  return stat;
}

fs.utimes = function utimes(file, atime, mtime, callback) {
  // Delegate invalid callbacks to Node, which throws synchronously.
  if (typeof callback !== 'function') return original.utimes(file, atime, mtime, callback);
  const value = timestamps(atime, mtime);
  // realpath validates path arguments synchronously, while all filesystem work
  // for callback/promise APIs stays asynchronous.
  fs.realpath(file, (error) => {
    if (error) return original.utimes(file, atime, mtime, callback);
    lockDirectory(file).then((lock) => queueMicrotask(() => {
      if (!lock) return original.utimes(file, atime, mtime, callback);
      remember(lock, value);
      callback(null);
    }));
  });
};

fs.utimesSync = function utimesSync(file, atime, mtime) {
  const lock = lockDirectorySync(file);
  if (!lock) return original.utimesSync(file, atime, mtime);
  remember(lock, timestamps(atime, mtime));
};

promises.utimes = async function utimes(file, atime, mtime) {
  const value = timestamps(atime, mtime);
  const lock = await lockDirectory(file);
  if (!lock) return original.promiseUtimes(file, atime, mtime);
  remember(lock, value);
};

fs.stat = function stat(file, options, callback) {
  if (typeof options === 'function') {
    callback = options;
    options = undefined;
  }
  if (typeof callback !== 'function') return original.stat(file, options, callback);
  return original.stat(file, options, (error, stat) => {
    if (error || !stat || !stat.isDirectory()) return callback(error, stat);
    lockDirectory(file).then((lock) => queueMicrotask(() => callback(null, overlay(stat, lock))));
  });
};

fs.statSync = function statSync(file, options) {
  const stat = original.statSync(file, options);
  return stat && stat.isDirectory() ? overlay(stat, lockDirectorySync(file)) : stat;
};

promises.stat = async function stat(file, options) {
  const stat = await original.promiseStat(file, options);
  return stat.isDirectory() ? overlay(stat, await lockDirectory(file)) : stat;
};

syncBuiltinESMExports();
