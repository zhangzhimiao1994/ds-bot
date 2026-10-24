import test from 'node:test';
import assert from 'node:assert/strict';

import { httpRequest, readAudit, startFakeUpstream, startTestBridge } from './helpers.mjs';

test('每 token 请求限流：超过窗口配额 429，且不再打上游', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({
    dsh: upstream.authority,
    limits: { requestLimit: 3, requestWindowMs: 10_000 },
  });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  for (let i = 1; i <= 3; i += 1) {
    const response = await httpRequest(env.baseUrl, {
      path: '/api/session/list',
      token: env.token,
      body: { type: 'client-request', rpcId: `r${i}`, method: 'session/list', payload: { args: {} } },
    });
    assert.equal(response.status, 200, `第 ${i} 个请求应放行`);
  }

  const limited = await httpRequest(env.baseUrl, {
    path: '/api/session/list',
    token: env.token,
    body: { type: 'client-request', rpcId: 'r4', method: 'session/list', payload: { args: {} } },
  });
  assert.equal(limited.status, 429);
  assert.equal(limited.json.error.code, 'bridge/rate-limited');
  assert.ok(Number(limited.headers['retry-after']) >= 1);
  assert.equal(upstream.requests.length, 3, '被限流的请求不能转发到上游');

  const audit = readAudit(env.stateDir).filter((entry) => entry.reason === 'rate-limited');
  assert.equal(audit.length, 1);
  assert.equal(audit[0].status, 429);
  assert.equal(audit[0].decision, 'deny');
});

test('错误 token 限速窗口内第 6 次起 429（默认阈值 5）', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const statuses = [];
  for (let i = 0; i < 7; i += 1) {
    const response = await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: `bad-${i}` });
    statuses.push(response.status);
  }
  assert.deepEqual(statuses, [401, 401, 401, 401, 401, 429, 429]);
});

test('认证失败限速可通过参数调小（测试专用）且只影响来源 IP', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({
    dsh: upstream.authority,
    limits: { authFailLimit: 2, authFailWindowMs: 60_000 },
  });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  assert.equal((await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: 'bad-1' })).status, 401);
  assert.equal((await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: 'bad-2' })).status, 401);
  assert.equal((await httpRequest(env.baseUrl, { method: 'GET', path: '/healthz', token: 'bad-3' })).status, 429);
  const audit = readAudit(env.stateDir);
  assert.ok(audit.some((entry) => entry.reason === 'invalid-token'));
  assert.ok(audit.some((entry) => entry.reason === 'auth-rate-limited'));
});
