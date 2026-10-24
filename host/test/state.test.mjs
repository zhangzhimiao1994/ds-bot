import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

import { startFakeUpstream, startTestBridge, httpRequest, makeTempDir, readAudit } from './helpers.mjs';

test('首次启动生成 32 字节设备 token，持久化到 bridge.json；重启后 token 不变', async (t) => {
  const upstream = await startFakeUpstream();
  const first = await startTestBridge({ dsh: upstream.authority });
  const stateFile = path.join(first.stateDir, 'bridge.json');
  assert.ok(fs.existsSync(stateFile), 'bridge.json 必须被创建');

  const raw = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
  assert.equal(typeof raw.token, 'string');
  assert.equal(raw.token.length, 43, '32 字节 base64url 应为 43 字符');
  assert.equal(Buffer.from(raw.token, 'base64url').length, 32);
  assert.equal(typeof raw.createdAt, 'number');
  assert.equal(raw.deviceName, 'test-device');
  assert.ok(Array.isArray(raw.scopes) && raw.scopes.includes('session:read'));
  assert.equal(typeof raw.tokenFingerprint, 'string');
  assert.equal(raw.tokenFingerprint.length, 8);
  assert.equal(raw.tokenFingerprint, crypto.createHash('sha256').update(raw.token).digest('hex').slice(0, 8));

  // 审计文件在启动时即存在，并记录 START。
  assert.ok(fs.existsSync(path.join(first.stateDir, 'audit.log')));
  await first.stop();

  const second = await startTestBridge({ dsh: upstream.authority, stateDir: first.stateDir });
  t.after(async () => {
    await second.stop();
    await upstream.close();
  });
  assert.equal(second.token, first.token, '重启后应复用已配对 token');
});

test('--reset 轮换 token；桥接实例标记 resetPerformed', async (t) => {
  const upstream = await startFakeUpstream();
  const first = await startTestBridge({ dsh: upstream.authority });
  const oldToken = first.token;
  const oldState = JSON.parse(fs.readFileSync(path.join(first.stateDir, 'bridge.json'), 'utf8'));
  await first.stop();

  const second = await startTestBridge({ dsh: upstream.authority, stateDir: first.stateDir, reset: true });
  t.after(async () => {
    await second.stop();
    await upstream.close();
  });
  assert.equal(second.bridge.resetPerformed, true);
  assert.notEqual(second.token, oldToken);
  const newState = JSON.parse(fs.readFileSync(path.join(second.stateDir, 'bridge.json'), 'utf8'));
  assert.equal(newState.token, second.token);
  assert.ok(newState.createdAt >= oldState.createdAt);

  // 旧 token 立即失效。
  assert.equal((await httpRequest(second.baseUrl, { method: 'GET', path: '/healthz', token: oldToken })).status, 401);
  assert.equal((await httpRequest(second.baseUrl, { method: 'GET', path: '/healthz', token: second.token })).status, 200);
});

test('bridge.json 损坏时自动重新生成 token（不阻塞启动）', async (t) => {
  const upstream = await startFakeUpstream();
  const first = await startTestBridge({ dsh: upstream.authority });
  const stateFile = path.join(first.stateDir, 'bridge.json');
  await first.stop();

  fs.writeFileSync(stateFile, '{ this is not json ', 'utf8');
  const recovered = await startTestBridge({ dsh: upstream.authority, stateDir: first.stateDir });
  t.after(async () => {
    await recovered.stop();
    await upstream.close();
  });
  assert.equal(recovered.bridge.replacedCorrupt, true);
  assert.match(recovered.token, /^[A-Za-z0-9_-]{43}$/);
  assert.ok(recovered.token !== first.token);
});

test('审计为 JSONL 且字段完整，不含 token / cookie', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: env.token });
  await httpRequest(env.baseUrl, {
    path: '/api/session/list',
    token: env.token,
    body: { type: 'client-request', rpcId: 'r', method: 'session/list', payload: { args: {} } },
  });
  await httpRequest(env.baseUrl, { path: '/api/settings/describe', token: env.token, body: {} });

  const file = path.join(env.stateDir, 'audit.log');
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean);
  assert.ok(lines.length >= 3);
  for (const line of lines) {
    const entry = JSON.parse(line); // 每一行都必须是合法 JSON
    assert.equal(typeof entry.ts, 'string');
    assert.ok(Date.parse(entry.ts) > 0);
    assert.ok(['allow', 'deny'].includes(entry.decision));
    assert.equal(typeof entry.reason, 'string');
    assert.equal(typeof entry.status, 'number');
    assert.equal(typeof entry.ms, 'number');
    assert.equal(typeof entry.bytes, 'number');
    assert.equal(typeof entry.remoteAddr, 'string');
    assert.ok(!('token' in entry));
    assert.ok(!('cookie' in entry));
  }

  const text = fs.readFileSync(file, 'utf8');
  assert.ok(!text.includes(env.token), '审计不能写 token');
  assert.ok(!text.includes('dsh-auth-'), '审计不能写 cookie 名/值');
  const denied = readAudit(env.stateDir).filter((entry) => entry.decision === 'deny');
  assert.ok(denied.some((entry) => entry.path === '/api/settings/describe'));
  const allowed = readAudit(env.stateDir).filter((entry) => entry.decision === 'allow');
  assert.ok(allowed.some((entry) => entry.path === '/api/session/list' && entry.status === 200));
});

test('状态目录权限：POSIX 下 bridge.json 为 0600（Windows 上跳过严格校验）', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });
  const stateFile = path.join(env.stateDir, 'bridge.json');
  const mode = fs.statSync(stateFile).mode & 0o777;
  if (process.platform === 'win32') {
    assert.ok(mode > 0);
  } else {
    assert.equal(mode, 0o600);
  }
});

test('bridge.json 不存在时状态目录会被自动创建', async (t) => {
  const upstream = await startFakeUpstream();
  const base = makeTempDir('fresh');
  const stateDir = path.join(base, 'not-yet-created', 'nested');
  const env = await startTestBridge({ dsh: upstream.authority, stateDir });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });
  assert.ok(fs.existsSync(path.join(stateDir, 'bridge.json')));
});
