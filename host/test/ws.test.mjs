import test from 'node:test';
import assert from 'node:assert/strict';

import { computeCookieName } from '../dsbot-bridge.mjs';
import {
  readAudit,
  socketReadOnce,
  startFakeUpstream,
  startTestBridge,
  wsEncodeText,
  wsHandshake,
  wsReadMessage,
} from './helpers.mjs';

test('WS /api/remote.mux：token 通过 ?token= 校验后 TCP 级透传，双向 pipe 可用', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  const sockets = [];
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    await env.stop();
    await upstream.close();
  });

  const result = await wsHandshake(env.port, '/api/remote.mux', { token: env.token });
  assert.equal(result.status, 101, '带正确 token 的 WS 握手应 101');
  sockets.push(result.socket);

  // 上游收到的握手头必须被改写。
  const upgrade = upstream.upgrades.at(-1);
  assert.ok(upgrade, '上游应收到 WS 升级请求');
  assert.equal(upgrade.headers.host, upstream.authority);
  assert.equal(upgrade.headers.origin, `http://${upstream.authority}`);
  const expectedName = computeCookieName(upstream.authority);
  assert.ok(String(upgrade.headers.cookie).startsWith(`${expectedName}=`), '上游应收到注入的 DSH cookie');

  // 合法的 mux 帧：桥接按原字节转发（假上游是 TCP echo，所以能原样收回来）。
  const openFrame = wsEncodeText({ type: 'open', streamId: 's1', endpoint: 'session/follow', payload: { args: { request: {} } } });
  result.socket.write(openFrame);
  const echoed = await socketReadOnce(result.socket);
  assert.deepEqual(echoed, openFrame, '合法 open 帧必须按原字节透传');
  assert.deepEqual(upgrade.afterUpgrade, openFrame, '上游应收到完全一致的帧字节');

  const itemFrame = wsEncodeText({ type: 'item', streamId: 's1', value: { kind: 'ping' } });
  result.socket.write(itemFrame);
  const echoedItem = await socketReadOnce(result.socket);
  assert.deepEqual(echoedItem, itemFrame);

  result.socket.destroy();
  await new Promise((resolve) => setTimeout(resolve, 100));
  const audit = readAudit(env.stateDir);
  assert.ok(audit.some((entry) => entry.upgrade === true && entry.reason === 'ws-proxied' && entry.status === 101));
});

test('WS mux 端点白名单：terminal/attach、job/follow 等危险流被拦截且不转发给上游', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  const sockets = [];
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    await env.stop();
    await upstream.close();
  });

  const cases = [
    { label: 'terminal/attach（交互终端 = 主机 RCE）', frame: { type: 'open', streamId: 'x1', endpoint: 'terminal/attach', payload: { args: {} } } },
    { label: 'job/follow（可读主机 shell 输出）', frame: { type: 'open', streamId: 'x2', endpoint: 'job/follow', payload: { args: {} } } },
    { label: 'account/watch', frame: { type: 'open', streamId: 'x3', endpoint: 'account/watch', payload: { args: {} } } },
    { label: '未知流端点', frame: { type: 'open', streamId: 'x4', endpoint: 'mcp/stream', payload: { args: {} } } },
    { label: '未知帧类型', frame: { type: 'hijack', streamId: 'x5' } },
  ];

  for (const item of cases) {
    const before = upstream.upgrades.length;
    const ws = await wsHandshake(env.port, '/api/remote.mux', { token: env.token });
    assert.equal(ws.status, 101);
    sockets.push(ws.socket);
    const upgrade = upstream.upgrades[before];
    const frame = wsEncodeText(item.frame);
    ws.socket.write(frame);
    const message = await wsReadMessage(ws.socket, { json: false });
    assert.equal(message.type, 'close', `${item.label}: 桥接应回 WS Close 帧`);
    assert.equal(message.payload.readUInt16BE(0), 1008, `${item.label}: Close code 应为 1008 policy violation`);
    await new Promise((resolve) => setTimeout(resolve, 30));
    assert.equal(upgrade.afterUpgrade.length, 0, `${item.label}: 违规帧绝不能转发到上游`);
  }

  const audit = readAudit(env.stateDir);
  const violations = audit.filter((entry) => entry.reason === 'mux-violation');
  assert.equal(violations.length, cases.length, '每条违规都应写入 deny 审计');
  assert.ok(violations.every((entry) => entry.decision === 'deny' && entry.status === 403 && entry.upgrade === true));
});

test('WS mux 非 JSON 文本帧与二进制帧被拒绝（默认拒绝）', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  const sockets = [];
  t.after(async () => {
    for (const socket of sockets) socket.destroy();
    await env.stop();
    await upstream.close();
  });

  // 非 JSON 文本帧。
  const ws1 = await wsHandshake(env.port, '/api/remote.mux', { token: env.token });
  sockets.push(ws1.socket);
  ws1.socket.write(wsEncodeText('not-json-at-all'));
  const close1 = await wsReadMessage(ws1.socket, { json: false });
  assert.equal(close1.type, 'close');

  // 二进制帧。
  const ws2 = await wsHandshake(env.port, '/api/remote.mux', { token: env.token });
  sockets.push(ws2.socket);
  const binaryPayload = Buffer.from([0x01, 0x02, 0x03]);
  const binaryFrame = Buffer.concat([Buffer.from([0x82, 0x80 | binaryPayload.length]), Buffer.from([1, 2, 3, 4]), binaryPayload]);
  ws2.socket.write(binaryFrame);
  const close2 = await wsReadMessage(ws2.socket, { json: false });
  assert.equal(close2.type, 'close');

  const violations = readAudit(env.stateDir).filter((entry) => entry.reason === 'mux-violation');
  assert.ok(violations.length >= 2);
});

test('WS 缺 token 401；错 token 401；非白名单 WS 路径 403', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const missing = await wsHandshake(env.port, '/api/remote.mux', {});
  assert.equal(missing.status, 401);
  assert.match(missing.headers, /\n/);

  const wrong = await wsHandshake(env.port, '/api/remote.mux', { token: 'not-the-token' });
  assert.equal(wrong.status, 401);

  const forbidden = await wsHandshake(env.port, '/api/settings/describe', { token: env.token });
  assert.equal(forbidden.status, 403);
  assert.match(forbidden.body ?? '', /bridge\/forbidden/);

  const forbiddenEvents = await wsHandshake(env.port, '/api/$events', { token: env.token });
  assert.equal(forbiddenEvents.status, 403, 'WS 只允许 remote.mux，其他入口默认拒绝');

  assert.equal(upstream.upgrades.length, 0, '被拒绝的 WS 不能连到上游');

  const audit = readAudit(env.stateDir);
  assert.ok(audit.some((entry) => entry.upgrade === true && entry.reason === 'missing-token' && entry.status === 401));
  assert.ok(audit.some((entry) => entry.upgrade === true && entry.reason === 'invalid-token' && entry.status === 401));
  assert.ok(audit.some((entry) => entry.upgrade === true && entry.reason === 'forbidden-ws-path' && entry.status === 403));
});

test('WS 握手请求不会把手机 token 或客户端 cookie 带给上游', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const result = await wsHandshake(env.port, '/api/remote.mux', {
    extraHeaders: { cookie: 'evil=1', authorization: `Bearer ${env.token}` },
  });
  t.after(() => result.socket?.destroy());
  assert.equal(result.status, 101);
  const upgrade = upstream.upgrades.at(-1);
  assert.equal(String(upgrade.headers.cookie).includes('evil=1'), false);
  assert.equal(upgrade.headers.authorization, undefined, '手机 Authorization 头不能透传给上游');
  assert.equal(String(upgrade.headers.cookie).includes(env.token), false, 'token 绝不能出现在上游 cookie 里');
});
