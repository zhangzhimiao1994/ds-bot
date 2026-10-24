#!/usr/bin/env node
/**
 * DS Bot 桥接真实冒烟（只读）。
 *
 * 对一台正在运行的桥接做端到端验收：
 *   1. GET  /healthz                （带 token）→ 200 {ok:true, version, upstream, tokenFingerprint}
 *   2. POST /api/session/list       （带 token + client-request 包络）→ 200 result.ok:true
 *   3. POST /api/settings/describe  （带 token）→ 403 bridge/forbidden（安全边界）
 *   4. POST /api/credentials/list   （带 token）→ 403 bridge/forbidden
 *   5. 无 token 请求 /healthz        → 401
 *   6. WS   /api/remote.mux         （Authorization 头）→ 101；开一条 session/follow 只读流
 *   7. WS   /api/remote.mux         试图打开 terminal/attach → 桥接 Close 1008 拒绝
 *
 * 不会发送 prompt、不会修改任何会话。任何一步失败退出码为 1。
 *
 * 用法：
 *   node host/smoke.mjs --bridge http://127.0.0.1:18788 --state-dir <状态目录>
 *   node host/smoke.mjs --bridge http://127.0.0.1:18788 --token <设备token>
 */
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

function parseArgs(argv) {
  const options = { bridge: 'http://127.0.0.1:18788', token: '', stateDir: '' };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const name = arg.includes('=') ? arg.slice(0, arg.indexOf('=')) : arg;
    const take = () => {
      if (arg.includes('=')) return arg.slice(arg.indexOf('=') + 1);
      index += 1;
      if (index >= argv.length) throw new Error(`${name} 缺少值`);
      return argv[index];
    };
    if (name === '--bridge') options.bridge = take();
    else if (name === '--token') options.token = take();
    else if (name === '--state-dir') options.stateDir = take();
    else if (name === '--help' || name === '-h') options.help = true;
    else throw new Error(`未知参数：${arg}`);
  }
  return options;
}

function loadToken(options) {
  if (options.token) return options.token;
  if (!options.stateDir) throw new Error('需要 --token 或 --state-dir');
  const file = path.join(options.stateDir, 'bridge.json');
  const state = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (typeof state.token !== 'string') throw new Error(`${file} 里没有 token`);
  return state.token;
}

const results = [];
function check(label, ok, detail = '') {
  results.push({ label, ok, detail });
  console.log(`${ok ? '✔ PASS' : '✖ FAIL'}  ${label}${detail ? '  —  ' + detail : ''}`);
}

function postJson(baseUrl, endpoint, body, token) {
  const url = new URL(endpoint, baseUrl);
  const data = Buffer.from(JSON.stringify(body));
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: url.hostname,
        port: url.port,
        method: 'POST',
        path: url.pathname,
        headers: {
          'content-type': 'application/json',
          'content-length': data.length,
          ...(token ? { authorization: `Bearer ${token}` } : {}),
        },
      },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try {
            json = JSON.parse(text);
          } catch {
            /* 非 JSON */
          }
          resolve({ status: res.statusCode, json, text });
        });
      },
    );
    req.on('error', reject);
    req.end(data);
  });
}

function getJson(baseUrl, endpoint, token) {
  const url = new URL(endpoint, baseUrl);
  return new Promise((resolve, reject) => {
    const req = http.get(
      { host: url.hostname, port: url.port, path: url.pathname, headers: token ? { authorization: `Bearer ${token}` } : {} },
      (res) => {
        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          const text = Buffer.concat(chunks).toString('utf8');
          let json = null;
          try {
            json = JSON.parse(text);
          } catch {
            /* ignore */
          }
          resolve({ status: res.statusCode, json, text });
        });
      },
    );
    req.on('error', reject);
  });
}

// ---------------------------------------------------------------------------
// 极简 WS 客户端（只用于冒烟，不实现完整协议）
// ---------------------------------------------------------------------------

function encodeTextFrame(value) {
  const payload = Buffer.from(JSON.stringify(value));
  const mask = crypto.randomBytes(4);
  const masked = Buffer.from(payload);
  for (let index = 0; index < masked.length; index += 1) masked[index] ^= mask[index & 3];
  let header;
  if (payload.length < 126) header = Buffer.from([0x81, 0x80 | payload.length]);
  else if (payload.length < 65536) {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(payload.length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x81;
    header[1] = 0x80 | 127;
    header.writeBigUInt64BE(BigInt(payload.length), 2);
  }
  return Buffer.concat([header, mask, masked]);
}

function wsConnect(host, port, requestPath, headers = {}) {
  const key = crypto.randomBytes(16).toString('base64');
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host, port });
    let buffer = Buffer.alloc(0);
    let settled = false;
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true;
        socket.destroy();
        reject(new Error('WS 握手超时'));
      }
    }, 5000);
    socket.on('connect', () => {
      socket.write(
        [
          `GET ${requestPath} HTTP/1.1`,
          `Host: ${host}:${port}`,
          'Upgrade: websocket',
          'Connection: Upgrade',
          `Sec-WebSocket-Key: ${key}`,
          'Sec-WebSocket-Version: 13',
          ...Object.entries(headers).map(([name, value]) => `${name}: ${value}`),
          '',
          '',
        ].join('\r\n'),
      );
    });
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const headerEnd = buffer.indexOf('\r\n\r\n');
      if (headerEnd < 0) return;
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const headText = buffer.subarray(0, headerEnd + 4).toString('latin1');
      const match = headText.match(/^HTTP\/1\.1\s+(\d{3})/);
      resolve({ status: match ? Number(match[1]) : 0, socket, leftover: buffer.subarray(headerEnd + 4), headers: headText });
    });
    socket.on('error', (error) => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        reject(error);
      }
    });
  });
}

function wsReadMessage(socket, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    const timer = setTimeout(() => {
      socket.off('data', onData);
      reject(new Error('等待 WS 消息超时'));
    }, timeoutMs);
    const finish = (value) => {
      clearTimeout(timer);
      socket.off('data', onData);
      resolve(value);
    };
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 2) {
        const opcode = buffer[0] & 0x0f;
        let length = buffer[1] & 0x7f;
        let offset = 2;
        if (length === 126) {
          if (buffer.length < 4) return;
          length = buffer.readUInt16BE(2);
          offset = 4;
        } else if (length === 127) {
          if (buffer.length < 10) return;
          length = Number(buffer.readBigUInt64BE(2));
          offset = 10;
        }
        if (buffer.length < offset + length) return;
        const payload = Buffer.from(buffer.subarray(offset, offset + length));
        buffer = buffer.subarray(offset + length);
        if (opcode === 0x9) {
          socket.write(Buffer.concat([Buffer.from([0x8a, payload.length]), payload]));
          continue;
        }
        if (opcode === 0x8) {
          finish({ type: 'close', code: payload.length >= 2 ? payload.readUInt16BE(0) : 0 });
          return;
        }
        if (opcode === 0x1 || opcode === 0x2) {
          try {
            finish({ type: 'message', value: JSON.parse(payload.toString('utf8')) });
          } catch {
            finish({ type: 'message', value: payload.toString('utf8') });
          }
          return;
        }
      }
    };
    socket.on('data', onData);
  });
}

// ---------------------------------------------------------------------------

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    console.log('用法：node host/smoke.mjs --bridge http://127.0.0.1:18788 (--token <token> | --state-dir <dir>)');
    return;
  }
  const token = loadToken(options);
  const base = options.bridge.replace(/\/$/, '');
  const url = new URL(base);
  console.log(`桥接冒烟：${base}  token指纹 ${crypto.createHash('sha256').update(token).digest('hex').slice(0, 8)}`);
  console.log('---');

  // 1) healthz
  const healthz = await getJson(base, '/healthz', token);
  check(
    'GET /healthz (带 token) → 200 ok:true',
    healthz.status === 200 && healthz.json?.ok === true && typeof healthz.json?.tokenFingerprint === 'string',
    `HTTP ${healthz.status}, version=${healthz.json?.version}, upstream=${healthz.json?.upstream}, fingerprint=${healthz.json?.tokenFingerprint}`,
  );

  // 2) session/list
  const list = await postJson(
    base,
    '/api/session/list',
    { type: 'client-request', rpcId: 'smoke-list', method: 'session/list', payload: { args: { _request: {} } } },
    token,
  );
  const items = list.json?.result?.value?.items ?? [];
  check(
    'POST /api/session/list → 200 result.ok:true（cookie 被真上游接受）',
    list.status === 200 && list.json?.result?.ok === true && Array.isArray(items),
    `HTTP ${list.status}, ok=${list.json?.result?.ok}, 会话数=${items.length}`,
  );

  // 3) settings/describe 必须 403
  const settings = await postJson(
    base,
    '/api/settings/describe',
    { type: 'client-request', rpcId: 'smoke-deny-1', method: 'settings/describe', payload: { args: {} } },
    token,
  );
  check(
    'POST /api/settings/describe → 403 bridge/forbidden',
    settings.status === 403 && settings.json?.error?.code === 'bridge/forbidden',
    `HTTP ${settings.status} ${JSON.stringify(settings.json?.error ?? {})}`,
  );

  // 4) credentials/list 必须 403
  const credentials = await postJson(
    base,
    '/api/credentials/list',
    { type: 'client-request', rpcId: 'smoke-deny-2', method: 'credentials/list', payload: { args: {} } },
    token,
  );
  check(
    'POST /api/credentials/list → 403 bridge/forbidden',
    credentials.status === 403 && credentials.json?.error?.code === 'bridge/forbidden',
    `HTTP ${credentials.status}`,
  );

  // 5) 无 token 401
  const anonymous = await getJson(base, '/healthz', '');
  check('GET /healthz (无 token) → 401 bridge/unauthorized', anonymous.status === 401 && anonymous.json?.error?.code === 'bridge/unauthorized', `HTTP ${anonymous.status}`);

  // 6) WS 升级 + 合法 session/follow 流
  const host = url.hostname;
  const port = Number(url.port || 80);
  const ws = await wsConnect(host, port, '/api/remote.mux', { authorization: `Bearer ${token}` });
  let liveFlowOk = false;
  let liveDetail = `HTTP ${ws.status}`;
  if (ws.status === 101) {
    const rootSession = items.find((item) => item.parentSessionId === null || item.parentSessionId === undefined) ?? items[0];
    if (rootSession?.sessionId) {
      ws.socket.write(
        encodeTextFrame({
          type: 'open',
          streamId: 'smoke-follow',
          endpoint: 'session/follow',
          payload: { args: { request: { address: { kind: 'session', sessionId: rootSession.sessionId } } } },
        }),
      );
      try {
        const frame = await wsReadMessage(ws.socket, 8000);
        liveFlowOk = frame.type === 'message' && frame.value?.type !== 'error';
        liveDetail = `首个 mux 帧 type=${frame.value?.type ?? frame.type}`;
      } catch (error) {
        liveDetail = error.message;
      }
      ws.socket.write(encodeTextFrame({ type: 'cancel', streamId: 'smoke-follow' }));
    } else {
      liveDetail = '没有可用会话，仅验证 101';
      liveFlowOk = true;
    }
    ws.socket.destroy();
  }
  check('WS /api/remote.mux（Authorization 头）→ 101 且 session/follow 流可用', ws.status === 101 && liveFlowOk, liveDetail);

  // 7) WS mux 危险流必须被拒
  const ws2 = await wsConnect(host, port, '/api/remote.mux', { authorization: `Bearer ${token}` });
  let muxDenyOk = false;
  let muxDenyDetail = `HTTP ${ws2.status}`;
  if (ws2.status === 101) {
    ws2.socket.write(encodeTextFrame({ type: 'open', streamId: 'smoke-evil', endpoint: 'terminal/attach', payload: { args: {} } }));
    try {
      const frame = await wsReadMessage(ws2.socket, 5000);
      muxDenyOk = frame.type === 'close' && frame.code === 1008;
      muxDenyDetail = `Close code=${frame.code}`;
    } catch (error) {
      muxDenyDetail = error.message;
    }
    ws2.socket.destroy();
  }
  check('WS mux 打开 terminal/attach → Close 1008（端点白名单生效）', muxDenyOk, muxDenyDetail);

  const failed = results.filter((item) => !item.ok);
  console.log('---');
  console.log(failed.length === 0 ? `全部通过（${results.length}/${results.length}）` : `有失败：${failed.length}/${results.length}`);
  process.exitCode = failed.length === 0 ? 0 : 1;
}

const invokedDirectly =
  typeof process.argv[1] === 'string' && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (invokedDirectly) {
  main().catch((error) => {
    console.error('冒烟脚本异常：', error.message);
    process.exitCode = 1;
  });
}
