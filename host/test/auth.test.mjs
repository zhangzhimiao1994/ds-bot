import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { createHash } from 'node:crypto';

import { startFakeUpstream, startTestBridge, httpRequest, readAudit } from './helpers.mjs';

test('无 token：/healthz 返回 401，且审计记录 deny', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const response = await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', tokenMode: 'none' });
  assert.equal(response.status, 401);
  assert.equal(response.json.ok, false);
  assert.equal(response.json.error.code, 'bridge/unauthorized');

  const audit = readAudit(env.stateDir);
  const deny = audit.find((entry) => entry.path === '/healthz' && entry.decision === 'deny');
  assert.ok(deny, '审计里应有 /healthz 的 deny 记录');
  assert.equal(deny.reason, 'missing-token');
  assert.equal(deny.status, 401);
});

test('token 无效：401；连续无效超过阈值后 429', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  for (let i = 1; i <= 5; i += 1) {
    const response = await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: 'wrong-token', tokenMode: 'bearer' });
    assert.equal(response.status, 401, `第 ${i} 次错误 token 应为 401`);
    assert.equal(response.json.error.code, 'bridge/unauthorized');
  }

  const blocked = await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: 'wrong-token', tokenMode: 'bearer' });
  assert.equal(blocked.status, 429, '超过 5 次错误 token 后应 429');
  assert.equal(blocked.json.error.code, 'bridge/rate-limited');
  assert.ok(Number(blocked.headers['retry-after']) >= 1);

  // 即使之后拿对 token，也会在限速窗口内被挡（防止暴力猜测得到 oracle）。
  const evenCorrect = await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: env.token });
  assert.equal(evenCorrect.status, 429);

  const audit = readAudit(env.stateDir);
  assert.ok(audit.some((entry) => entry.reason === 'invalid-token' && entry.decision === 'deny'));
  assert.ok(audit.some((entry) => entry.reason === 'auth-rate-limited' && entry.status === 429));
});

test('正确 token 的三种携带方式都可用，并校验 healthz 字段', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  for (const tokenMode of ['bearer', 'header', 'query']) {
    const response = await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: env.token, tokenMode });
    assert.equal(response.status, 200, `${tokenMode} 方式应鉴权通过`);
    assert.equal(response.json.ok, true);
    assert.equal(response.json.upstream, upstream.authority);
    assert.match(response.json.version, /^\d+\.\d+\.\d+/);
    assert.equal(response.json.tokenFingerprint, createHash('sha256').update(env.token).digest('hex').slice(0, 8));
  }

  // 审计里绝不允许出现 token 原文。
  const rawAudit = readAudit(env.stateDir)
    .map((entry) => JSON.stringify(entry))
    .join('\n');
  assert.ok(!rawAudit.includes(env.token), '审计日志不能包含设备 token');
});

test('token 文件被 --reset 轮换后，旧 token 立即失效', async (t) => {
  const upstream = await startFakeUpstream();
  const first = await startTestBridge({ dsh: upstream.authority });
  const oldToken = first.token;
  await first.stop();

  const second = await startTestBridge({ dsh: upstream.authority, stateDir: first.stateDir, reset: true });
  t.after(async () => {
    await second.stop();
    await upstream.close();
  });
  assert.notEqual(second.token, oldToken, '--reset 必须换新 token');

  const oldTokenResponse = await httpRequest(second.baseUrl, { method: 'GET', path: '/healthz', token: oldToken });
  assert.equal(oldTokenResponse.status, 401);

  const newTokenResponse = await httpRequest(second.baseUrl, { method: 'GET', path: '/healthz', token: second.token });
  assert.equal(newTokenResponse.status, 200);

  const stateFile = path.join(second.stateDir, 'bridge.json');
  assert.ok(stateFile.endsWith('bridge.json'));
});
