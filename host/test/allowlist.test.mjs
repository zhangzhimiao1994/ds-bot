import test from 'node:test';
import assert from 'node:assert/strict';

import { startFakeUpstream, startTestBridge, httpRequest, readAudit } from './helpers.mjs';

test('明确禁止的方法一律 403，且不会打到上游；审计记 deny', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const forbidden = [
    ['settings/describe', 'forbidden-method'],
    ['settings/mutate', 'forbidden-method'],
    ['credentials/list', 'forbidden-method'],
    ['credentials/set', 'forbidden-method'],
    ['pluginInventory/list', 'forbidden-method'],
    ['agentPresets/list', 'forbidden-method'],
    ['dynamicCordisRunner/invoke', 'forbidden-method'],
    ['goals/list', 'forbidden-method'],
    ['subagents/prompt', 'forbidden-method'],
    ['fileUploads/upload', 'forbidden-method'],
    ['commands/execute', 'forbidden-method'],
    ['workspace/create', 'forbidden-method'],
    ['workspace/delete', 'forbidden-method'],
    ['workspace/rename', 'forbidden-method'],
    ['workspace/archiveSession', 'forbidden-method'],
    ['session/fork', 'forbidden-method'],
    ['session/attachment', 'forbidden-method'],
    ['mcp/list', 'forbidden-method'],
    ['connectors/list', 'forbidden-method'],
    ['totally/unknown', 'unknown-method'],
  ];

  for (const [endpoint, expectedReason] of forbidden) {
    const response = await httpRequest(env.baseUrl, { path: `/api/${endpoint}`, token: env.token, body: { type: 'client-request' } });
    assert.equal(response.status, 403, `${endpoint} 应 403`);
    assert.equal(response.json.ok, false);
    assert.equal(response.json.error.code, 'bridge/forbidden');
    const audit = readAudit(env.stateDir).filter((entry) => entry.path === `/api/${endpoint}` && entry.decision === 'deny');
    assert.ok(audit.length >= 1, `${endpoint} 应写入审计 deny`);
    assert.equal(audit.at(-1).reason, expectedReason, `${endpoint} 审计 reason 应为 ${expectedReason}`);
    assert.equal(audit.at(-1).status, 403);
  }

  assert.equal(upstream.requests.length, 0, '被拒绝的请求绝不能转发到上游');
});

test('非 /api 路径 403（/healthz 例外）', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  for (const path of ['/', '/index.html', '/favicon.ico', '/api', '/api/', '/ws', '/healthz/extra']) {
    const response = await httpRequest(env.baseUrl, { method: 'GET', path, token: env.token });
    assert.equal(response.status, 403, `${path} 应 403`);
    assert.equal(response.json.error.code, 'bridge/forbidden');
  }

  const audit = readAudit(env.stateDir);
  assert.ok(audit.some((entry) => entry.reason === 'non-api-path' && entry.decision === 'deny'));
});

test('HTTP 方法收紧：GET /api/session/list 也 403（上游只接受 POST）', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const response = await httpRequest(env.baseUrl, { method: 'GET', path: '/api/session/list', token: env.token });
  assert.equal(response.status, 403);
  assert.equal(response.json.error.code, 'bridge/forbidden');
  const audit = readAudit(env.stateDir).filter((entry) => entry.path === '/api/session/list');
  assert.equal(audit.at(-1).reason, 'http-method-not-allowed');
});

test('路径穿越/编码变体不会绕过白名单', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const attempts = [
    '/api/settings%2Fdescribe',
    '/api/session//list',
    '/api/session/../settings/describe',
    '/api//settings/describe',
    '/api/session/list/',
    '/api/%73ettings/describe',
  ];
  for (const attempt of attempts) {
    const response = await httpRequest(env.baseUrl, { path: attempt, token: env.token, body: {} });
    assert.equal(response.status, 403, `${attempt} 必须被拒绝`);
    assert.equal(response.json.error.code, 'bridge/forbidden');
  }
  assert.equal(upstream.requests.length, 0);
});

test('白名单方法可以转发到上游', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const allowed = [
    'session/list',
    'session/create',
    'session/prompt',
    'session/cancel',
    'session/rename',
    'session/page',
    'session/modelCatalog',
    'session/selectModel',
    'session/follow',
    'session/control',
    '$events',
    '$events/result',
    'skills/list',
    'llm/listProviders',
    'fileReferences/list',
    'workspace/follow',
    'workspaceFiles/list',
    'workspaceFiles/read',
    'workspaceFiles/readAll',
    'workspaceFiles/readBytes',
    'workspaceFiles/stat',
  ];

  for (const endpoint of allowed) {
    const response = await httpRequest(env.baseUrl, {
      path: `/api/${endpoint}`,
      token: env.token,
      body: { type: 'client-request', rpcId: 'r1', method: endpoint, payload: { args: {} } },
    });
    assert.equal(response.status, 200, `${endpoint} 应放行`);
    assert.equal(response.json.result.ok, true);
  }
  assert.equal(upstream.requests.length, allowed.length);
  const audit = readAudit(env.stateDir).filter((entry) => entry.decision === 'allow');
  assert.ok(audit.length >= allowed.length);
});
