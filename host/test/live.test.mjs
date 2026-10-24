/**
 * 真实 DSH 冒烟（只读）。
 * 仅当本机 127.0.0.1:64005 有运行中的 DSH Web、且 %USERPROFILE%\.dsh\.credentials.yaml
 * 存在时执行；否则自动跳过。这个测试是“cookie 算法对真上游有效”的最终证据。
 *
 * 只调用 session/list（只读）与 settings/describe（应被桥接 403），绝不发 prompt。
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

import { createBridge } from '../dsbot-bridge.mjs';
import { httpRequest, makeTempDir, readAudit, wsEncodeText, wsHandshake, wsReadMessage } from './helpers.mjs';

const REAL_DSH = '127.0.0.1:64005';
const REAL_CREDENTIALS = path.join(os.homedir(), '.dsh', '.credentials.yaml');

function probeTcp(host, port, timeoutMs = 800) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port });
    const done = (value) => {
      socket.destroy();
      resolve(value);
    };
    socket.setTimeout(timeoutMs, () => done(false));
    socket.on('connect', () => done(true));
    socket.on('error', () => done(false));
  });
}

async function liveAvailable() {
  if (!fs.existsSync(REAL_CREDENTIALS)) return false;
  return await probeTcp('127.0.0.1', 64005);
}

test('真实 DSH：session/list 经桥接 200 + ok:true，settings/describe 403，WS 握手 101', async (t) => {
  if (!(await liveAvailable())) {
    t.skip('本机没有运行中的 DSH Web（127.0.0.1:64005）或缺少 DSH 凭据文件，跳过真实冒烟');
    return;
  }
  const dir = makeTempDir('live');
  const bridge = createBridge({
    port: 0,
    bind: '127.0.0.1',
    dsh: REAL_DSH,
    stateDir: path.join(dir, 'state'),
    credentialsPath: REAL_CREDENTIALS,
  });
  await bridge.start();
  t.after(async () => {
    await bridge.stop();
  });
  const baseUrl = `http://127.0.0.1:${bridge.port}`;

  // 1) 只读：列出会话。
  const listResponse = await httpRequest(baseUrl, {
    path: '/api/session/list',
    token: bridge.token,
    body: {
      type: 'client-request',
      rpcId: 'live-smoke-session-list',
      method: 'session/list',
      payload: { args: { _request: {} } },
    },
  });
  assert.equal(listResponse.status, 200, `真实上游 session/list 应 200，实际 ${listResponse.status}: ${listResponse.body.slice(0, 200)}`);
  assert.equal(listResponse.json.type, 'server-response');
  assert.equal(listResponse.json.result.ok, true, '结果应为 ok:true（说明桥接签的 cookie 被 DSH 接受）');
  assert.ok(Array.isArray(listResponse.json.result.value.items), '应返回 items 数组');

  // 2) 安全边界：设置接口必须被桥接拦下，不能到达上游。
  const settingsResponse = await httpRequest(baseUrl, {
    path: '/api/settings/describe',
    token: bridge.token,
    body: { type: 'client-request', rpcId: 'deny', method: 'settings/describe', payload: { args: {} } },
  });
  assert.equal(settingsResponse.status, 403);
  assert.equal(settingsResponse.json.error.code, 'bridge/forbidden');

  // 3) 审计里既有 allow 也有 deny，且不含 token / cookie。
  const auditText = fs.readFileSync(path.join(dir, 'state', 'audit.log'), 'utf8');
  assert.ok(readAudit(path.join(dir, 'state')).some((entry) => entry.path === '/api/session/list' && entry.status === 200));
  assert.ok(readAudit(path.join(dir, 'state')).some((entry) => entry.path === '/api/settings/describe' && entry.status === 403));
  assert.ok(!auditText.includes(bridge.token));

  // 4) 真实 WS：桥接应把 /api/remote.mux 升级到 DSH 并回 101；再用合法的
  //    session/follow 流验证 mux 白名单能放行真实协议帧（只读：follow 现有会话）。
  const ws = await wsHandshake(bridge.port, '/api/remote.mux', { token: bridge.token });
  assert.equal(ws.status, 101, '真实 DSH 的 WS 升级应 101');
  const items = listResponse.json.result.value.items;
  const rootSession = items.find((item) => item.parentSessionId === null || item.parentSessionId === undefined) ?? items[0];
  assert.ok(rootSession?.sessionId, 'session/list 至少应有一个会话可用于只读 follow');
  ws.socket.write(
    wsEncodeText({
      type: 'open',
      streamId: 'live-follow-1',
      endpoint: 'session/follow',
      payload: { args: { request: { address: { kind: 'session', sessionId: rootSession.sessionId } } } },
    }),
  );
  const firstFrame = await wsReadMessage(ws.socket, { timeoutMs: 8000 });
  assert.ok(firstFrame && typeof firstFrame === 'object', `session/follow 应返回 mux 帧，实际：${JSON.stringify(firstFrame).slice(0, 200)}`);
  assert.notEqual(firstFrame.type, 'error', `真实流不应报错：${JSON.stringify(firstFrame).slice(0, 300)}`);
  ws.socket.write(wsEncodeText({ type: 'cancel', streamId: 'live-follow-1' }));
  ws.socket.destroy();

  // 5) cookie 名与真实 authority 的规则一致。
  assert.match(bridge.cookieName, /^dsh-auth-[A-Za-z0-9_-]{43}$/);
});
