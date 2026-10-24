import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import { createHmac } from 'node:crypto';

import { computeCookieName, signCookie } from '../dsbot-bridge.mjs';
import {
  findClosedPort,
  httpRequest,
  readAudit,
  sha256,
  startFakeUpstream,
  startTestBridge,
} from './helpers.mjs';

test('请求体与 content-type 原样透传；响应里的 Set-Cookie 被剥离', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const rpcBody = {
    type: 'client-request',
    rpcId: 'rpc-123',
    method: 'session/list',
    payload: { args: { _request: {} } },
  };
  const response = await httpRequest(env.baseUrl, {
    path: '/api/session/list',
    token: env.token,
    body: rpcBody,
    headers: { 'content-type': 'application/json', 'x-client-trace': 'phone-1' },
  });

  assert.equal(response.status, 200);
  assert.equal(response.json.type, 'server-response');
  // 上游收到的是原始 JSON（rpcId 保持不变）。
  const record = upstream.requests.at(-1);
  assert.equal(record.method, 'POST');
  assert.equal(record.url, '/api/session/list');
  assert.equal(record.headers['content-type'], 'application/json');
  assert.equal(record.headers['x-client-trace'], 'phone-1');
  const forwarded = JSON.parse(record.body.toString('utf8'));
  assert.deepEqual(forwarded, rpcBody);
  assert.equal(record.headers['content-length'], String(record.body.length));

  // 手机不能拿到 DSH 的 cookie。
  assert.equal(response.headers['set-cookie'], undefined);
  assert.equal(response.json.result.ok, true);
});

test('Host / Origin / Cookie 被改写并注入签名 cookie；手机侧 cookie 与 token 头不外泄', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const response = await httpRequest(env.baseUrl, {
    path: '/api/session/list',
    token: env.token,
    body: { type: 'client-request', rpcId: 'r', method: 'session/list', payload: { args: { _request: {} } } },
    headers: { cookie: 'evil=1; dsh-auth-fake=2', origin: 'http://evil.example' },
  });
  assert.equal(response.status, 200);

  const record = upstream.requests.at(-1);
  assert.equal(record.headers.host, upstream.authority, 'Host 必须改写为上游 authority');
  assert.equal(record.headers.origin, `http://${upstream.authority}`, 'Origin 必须改写为上游 origin');
  assert.equal(record.headers.authorization, undefined, '设备 token 不能转发给上游');
  assert.equal(record.headers['x-dsbot-token'], undefined);

  const cookie = record.headers.cookie;
  assert.ok(typeof cookie === 'string' && cookie.length > 0, '必须向上游注入 cookie');
  assert.ok(!cookie.includes('evil=1'), '手机自带的 cookie 必须丢弃');
  assert.ok(!cookie.includes('dsh-auth-fake=2'), '手机不能伪造 DSH cookie');

  const expectedName = computeCookieName(upstream.authority);
  const cookieName = cookie.slice(0, cookie.indexOf('='));
  assert.equal(cookieName, expectedName, 'cookie 名必须是 dsh-auth-<sha256(authority)>');

  // 手工再验证一遍签名：payload 结构 + HMAC。
  const value = cookie.slice(cookieName.length + 1);
  const [prefix, body, signature] = value.split('.');
  assert.equal(prefix, 'v1');
  const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
  assert.equal(payload.version, 1);
  assert.equal(payload.authority, upstream.authority);
  assert.equal(payload.expiresAt - payload.issuedAt, 7 * 24 * 60 * 60 * 1000);
  const expectedSignature = createHmac('sha256', env.credentials.secret).update(body).digest('base64url');
  assert.equal(signature, expectedSignature);
  assert.ok(Math.abs(Date.now() - payload.issuedAt) < 60_000, 'cookie 必须是本次请求即时签发的');
});

test('?token= 只用于桥接鉴权，不会转发给上游', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const response = await httpRequest(env.baseUrl, {
    path: '/api/session/list?page=2',
    token: env.token,
    tokenMode: 'query',
    body: { type: 'client-request', rpcId: 'r', method: 'session/list', payload: { args: {} } },
  });
  assert.equal(response.status, 200);
  const record = upstream.requests.at(-1);
  assert.ok(!record.url.includes('token'), 'token 查询参数不能转发给上游');
  assert.ok(record.url.includes('page=2'), '其他查询参数应保留');
});

test('大请求体（2MB）完整透传', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const big = Buffer.alloc(2 * 1024 * 1024, 0x61);
  const body = {
    type: 'client-request',
    rpcId: 'r-big',
    method: 'session/prompt',
    payload: { args: { prompt: big.toString('utf8') } },
  };
  const response = await httpRequest(env.baseUrl, { path: '/api/session/prompt', token: env.token, body });
  assert.equal(response.status, 200);
  assert.equal(response.json.result.value.bodySha256, sha256(Buffer.from(JSON.stringify(body))));
  const record = upstream.requests.at(-1);
  assert.equal(record.body.length, Buffer.byteLength(JSON.stringify(body)));
});

test('流式响应逐块透传（text/event-stream）', async (t) => {
  const upstream = await startFakeUpstream((req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.write('data: one\n\n');
    setTimeout(() => res.write('data: two\n\n'), 20);
    setTimeout(() => {
      res.write('data: three\n\n');
      res.end();
    }, 40);
  });
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const response = await httpRequest(env.baseUrl, {
    path: '/api/$events',
    token: env.token,
    body: { type: 'client-request', rpcId: 'r', method: '$events', payload: { args: {} } },
  });
  assert.equal(response.status, 200);
  assert.equal(response.headers['content-type'], 'text/event-stream');
  assert.equal(response.body, 'data: one\n\ndata: two\n\ndata: three\n\n');
});

test('上游不可达时返回 502 bridge/upstream-unreachable 并记审计', async (t) => {
  const closedPort = await findClosedPort();
  const env = await startTestBridge({ dsh: `127.0.0.1:${closedPort}` });
  t.after(async () => {
    await env.stop();
  });

  const response = await httpRequest(env.baseUrl, {
    path: '/api/session/list',
    token: env.token,
    body: { type: 'client-request', rpcId: 'r', method: 'session/list', payload: { args: {} } },
  });
  assert.equal(response.status, 502);
  assert.equal(response.json.ok, false);
  assert.equal(response.json.error.code, 'bridge/upstream-unreachable');

  const audit = readAudit(env.stateDir).filter((entry) => entry.path === '/api/session/list');
  assert.equal(audit.at(-1).status, 502);
  assert.equal(audit.at(-1).decision, 'allow');
});

test('超过 16MB 上限的请求体返回 413（不经上游）', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const response = await rawHttpRequest(
    env.port,
    [
      'POST /api/session/prompt HTTP/1.1',
      `Host: 127.0.0.1:${env.port}`,
      `Authorization: Bearer ${env.token}`,
      'Content-Type: application/json',
      'Content-Length: 20000000',
      'Connection: close',
      '',
      '',
    ].join('\r\n'),
  );
  assert.match(response, /^HTTP\/1\.1 413/);
  assert.match(response, /bridge\/body-too-large/);
  assert.equal(upstream.requests.length, 0);
  const audit = readAudit(env.stateDir).filter((entry) => entry.path === '/api/session/prompt');
  assert.equal(audit.at(-1).reason, 'body-too-large');
  assert.equal(audit.at(-1).decision, 'deny');
});

/** 测试用独立实现：按规范算一份 cookie 值，与桥接实现互相印证。 */
void signCookie;

/** 原始 HTTP 请求（允许声明超大 Content-Length，不实际发 body）。 */
function rawHttpRequest(port, requestText) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: '127.0.0.1', port });
    const chunks = [];
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('raw 请求超时'));
    }, 5000);
    socket.on('connect', () => socket.write(requestText));
    socket.on('data', (chunk) => chunks.push(chunk));
    socket.on('end', () => {
      clearTimeout(timer);
      resolve(Buffer.concat(chunks).toString('utf8'));
    });
    socket.on('error', (error) => {
      clearTimeout(timer);
      if (chunks.length > 0) resolve(Buffer.concat(chunks).toString('utf8'));
      else reject(error);
    });
  });
}
