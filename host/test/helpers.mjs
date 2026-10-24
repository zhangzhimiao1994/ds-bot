/**
 * 桥接测试公共工具（零依赖）。
 * 测试产生的临时状态目录放在 work/bridge-test/tmp/ 下（shell 可写目录），
 * 不要用 os.tmpdir()，因为沙箱里宿主临时目录不一定可写。
 */
import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createBridge } from '../dsbot-bridge.mjs';

export const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const TEST_ROOT = path.join(REPO_ROOT, 'work', 'bridge-test');

let sequence = 0;
function uniqueName(prefix) {
  sequence += 1;
  return `${prefix}-${process.pid}-${Date.now()}-${sequence}-${crypto.randomBytes(3).toString('hex')}`;
}

/** 建一个测试专用临时目录（在 shell 创建的 work/bridge-test 下）。 */
export function makeTempDir(prefix = 'tmp') {
  const dir = path.join(TEST_ROOT, 'tmp', uniqueName(prefix));
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** 造一份 DSH 凭据 YAML（结构模仿真实 %USERPROFILE%\.dsh\.credentials.yaml）。 */
export function writeCredentials(dir, secret = crypto.randomBytes(32)) {
  const file = path.join(dir, 'credentials.yaml');
  const content = [
    'version: 1',
    'records:',
    '  client-connection/browser-session:',
    '    kind: grant',
    '    payload:',
    '      version: 1',
    `      secret: ${Buffer.from(secret).toString('base64url')}`,
    '  deepseek-account-platform/default:',
    '    kind: grant',
    '    payload:',
    '      version: 1',
    '      token: this-is-not-a-real-token',
    'refs:',
    '  DEEPSEEK_API_KEY: sk-test-not-real',
    '',
  ].join('\n');
  fs.writeFileSync(file, content, 'utf8');
  return { path: file, secret };
}

/**
 * 假上游 DSH：记录每个请求（含 WS 升级），并返回可控 JSON。
 * handler(req, res, body, ctx) 可自定义响应；默认返回 server-response 包络。
 */
export async function startFakeUpstream(handler) {
  const requests = [];
  const upgrades = [];
  const sockets = new Set();
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const body = Buffer.concat(chunks);
      const record = { kind: 'http', method: req.method, url: req.url, headers: req.headers, body };
      requests.push(record);
      if (typeof handler === 'function') {
        handler(req, res, body, record);
        return;
      }
      const payload = {
        type: 'server-response',
        rpcId: 'fake-rpc',
        result: { ok: true, value: { path: req.url, bodySha256: sha256(body) } },
      };
      res.writeHead(200, {
        'content-type': 'application/json',
        // 故意设置 Set-Cookie，测试桥接是否剥离。
        'set-cookie': 'dsh-auth-leak=SHOULD-NOT-REACH-PHONE; Path=/',
      });
      res.end(JSON.stringify(payload));
    });
  });
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
  });
  server.on('upgrade', (req, socket) => {
    const record = { method: req.method, url: req.url, headers: req.headers, afterUpgrade: Buffer.alloc(0) };
    upgrades.push(record);
    const key = req.headers['sec-websocket-key'];
    const accept = crypto
      .createHash('sha1')
      .update(String(key) + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
      .digest('base64');
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n' +
        'Upgrade: websocket\r\n' +
        'Connection: Upgrade\r\n' +
        `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    );
    // 极简 echo：桥接只做 TCP pipe，测试不关心 WS 帧格式。
    socket.on('data', (chunk) => {
      record.afterUpgrade = Buffer.concat([record.afterUpgrade, chunk]);
      socket.write(chunk);
    });
    socket.on('error', () => {});
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  return {
    port,
    authority: `127.0.0.1:${port}`,
    requests,
    upgrades,
    async close() {
      for (const socket of sockets) socket.destroy();
      await new Promise((resolve) => server.close(resolve));
    },
  };
}

/** 找一台“确定没人监听”的端口（用于测 502）。 */
export async function findClosedPort() {
  const server = net.createServer();
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise((resolve) => server.close(resolve));
  return port;
}

/**
 * 启动一个完整桥接实例（假上游 + 临时状态目录），返回调用辅助。
 * @returns {Promise<{bridge, baseUrl, token, stateDir, credentials, stop}>}
 */
export async function startTestBridge(options = {}) {
  const dir = makeTempDir('bridge');
  const credentials = writeCredentials(dir, options.secret);
  const stateDir = options.stateDir ?? path.join(dir, 'state');
  const bridge = createBridge({
    port: 0,
    bind: '127.0.0.1',
    dsh: options.dsh,
    stateDir,
    credentialsPath: credentials.path,
    deviceName: options.deviceName ?? 'test-device',
    reset: options.reset === true,
    limits: options.limits,
  });
  await bridge.start();
  const baseUrl = `http://127.0.0.1:${bridge.port}`;
  return {
    bridge,
    baseUrl,
    token: bridge.token,
    stateDir,
    dir,
    credentials,
    port: bridge.port,
    async stop() {
      await bridge.stop();
    },
  };
}

/** 发一个 HTTP 请求；token 通过 headers/query 自动注入（可覆盖）。 */
export function httpRequest(baseUrl, options = {}) {
  const {
    method = 'POST',
    path: requestPath = '/healthz',
    token,
    tokenMode = 'bearer', // bearer | header | query | none
    headers = {},
    body,
    timeoutMs = 10_000,
  } = options;
  let target = new URL(requestPath, baseUrl);
  const finalHeaders = { ...headers };
  if (token && tokenMode === 'bearer') finalHeaders.authorization = `Bearer ${token}`;
  if (token && tokenMode === 'header') finalHeaders['x-dsbot-token'] = token;
  if (token && tokenMode === 'query') target.searchParams.set('token', token);
  const data = body === undefined ? null : Buffer.isBuffer(body) ? body : Buffer.from(JSON.stringify(body));
  if (data) {
    finalHeaders['content-type'] = finalHeaders['content-type'] ?? 'application/json';
    finalHeaders['content-length'] = String(data.length);
  }
  return new Promise((resolve, reject) => {
    const req = http.request(
      {
        host: target.hostname,
        port: target.port,
        method,
        path: target.pathname + target.search,
        headers: finalHeaders,
        timeout: timeoutMs,
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
            json = null;
          }
          resolve({ status: res.statusCode, headers: res.headers, body: text, json });
        });
      },
    );
    req.on('timeout', () => req.destroy(new Error('request timeout')));
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

/** 读取审计 JSONL。 */
export function readAudit(stateDir) {
  const file = path.join(stateDir, 'audit.log');
  if (!fs.existsSync(file)) return [];
  return fs
    .readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.trim() !== '')
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return { parseError: line };
      }
    });
}

/**
 * 原始 WS 握手：成功时返回 101 与 socket；失败时返回 HTTP 状态。
 * 测试里不实现帧编解码，握手后直接收发原始字节（桥接只做 TCP pipe）。
 */
export function wsHandshake(port, requestPath, options = {}) {
  const { token, extraHeaders = {}, timeoutMs = 5000 } = options;
  const key = crypto.randomBytes(16).toString('base64');
  const url = new URL(requestPath, `http://127.0.0.1:${port}`);
  if (token) url.searchParams.set('token', token);
  const lines = [
    `GET ${url.pathname}${url.search} HTTP/1.1`,
    `Host: 127.0.0.1:${port}`,
    `Upgrade: websocket`,
    `Connection: Upgrade`,
    `Sec-WebSocket-Key: ${key}`,
    `Sec-WebSocket-Version: 13`,
  ];
  for (const [name, value] of Object.entries(extraHeaders)) lines.push(`${name}: ${value}`);
  const requestText = lines.join('\r\n') + '\r\n\r\n';
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: '127.0.0.1', port });
    let buffer = Buffer.alloc(0);
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      socket.destroy();
      reject(new Error('WS 握手超时'));
    }, timeoutMs);
    const finish = (result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      resolve(result);
    };
    const onDataHandler = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      const headerEnd = buffer.indexOf('\r\n\r\n');
      if (headerEnd < 0) return;
      const headText = buffer.subarray(0, headerEnd + 4).toString('latin1');
      const statusMatch = headText.match(/^HTTP\/1\.1\s+(\d{3})/);
      const status = statusMatch ? Number(statusMatch[1]) : 0;
      const rest = buffer.subarray(headerEnd + 4);
      if (status === 101) {
        finish({ status, headers: headText, socket, rest });
        return;
      }
      // 非 101：等响应体收完（桥接返回 JSON 错误后 connection: close）。
      const bodyChunks = [rest];
      const onBody = (bodyChunk) => bodyChunks.push(bodyChunk);
      socket.off('data', onDataHandler);
      socket.on('data', onBody);
      const settleRejected = () => {
        socket.off('data', onBody);
        finish({
          status,
          headers: headText,
          socket: null,
          rest: Buffer.alloc(0),
          body: Buffer.concat(bodyChunks).toString('utf8'),
        });
      };
      socket.once('end', settleRejected);
      socket.once('close', settleRejected);
    };
    socket.on('connect', () => socket.write(requestText));
    socket.on('error', (error) => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        reject(error);
      }
    });
    socket.on('data', onDataHandler);
    socket.setTimeout(timeoutMs, () => {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        socket.destroy();
        reject(new Error('WS 握手超时'));
      }
    });
  });
}

/** 从 socket 上收集接下来的一段数据。 */
export function socketReadOnce(socket, timeoutMs = 3000) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    const timer = setTimeout(() => {
      socket.off('data', onData);
      reject(new Error('等待 socket 数据超时'));
    }, timeoutMs);
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      clearTimeout(timer);
      socket.off('data', onData);
      resolve(buffer);
    };
    socket.on('data', onData);
  });
}

/** 往 WS 客户端 socket 写一段原始字节并读回 echo。 */
export async function wsEcho(socket, payload) {
  const data = Buffer.isBuffer(payload) ? payload : Buffer.from(String(payload));
  socket.write(data);
  return await socketReadOnce(socket);
}

/** 编码一个 WS 文本帧（默认按客户端语义加 mask）。 */
export function wsEncodeText(value, { masked = true } = {}) {
  const payload = Buffer.isBuffer(value) ? value : Buffer.from(typeof value === 'string' ? value : JSON.stringify(value));
  const header = [];
  header.push(0x81); // FIN + text
  if (payload.length < 126) {
    header.push((masked ? 0x80 : 0) | payload.length);
  } else if (payload.length < 65536) {
    header.push((masked ? 0x80 : 0) | 126, (payload.length >> 8) & 0xff, payload.length & 0xff);
  } else {
    const size = Buffer.alloc(8);
    size.writeBigUInt64BE(BigInt(payload.length));
    header.push((masked ? 0x80 : 0) | 127, ...size);
  }
  if (!masked) return Buffer.concat([Buffer.from(header), payload]);
  const mask = crypto.randomBytes(4);
  const body = Buffer.from(payload);
  for (let index = 0; index < body.length; index += 1) body[index] ^= mask[index & 3];
  return Buffer.concat([Buffer.from(header), mask, body]);
}

/**
 * 从 socket 读一条完整的 WS 文本消息（服务端→客户端方向，无 mask）。
 * 自动处理 ping（回 pong）与分片；返回 Buffer/JSON 解析后的对象。
 */
export function wsReadMessage(socket, { timeoutMs = 3000, json = true } = {}) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0);
    let fragments = [];
    let fragmentOpcode = 0;
    const timer = setTimeout(() => {
      socket.off('data', onData);
      reject(new Error('等待 WS 消息超时'));
    }, timeoutMs);
    const finish = (value) => {
      clearTimeout(timer);
      socket.off('data', onData);
      resolve(value);
    };
    const sendPong = (payload) => {
      const frame = Buffer.concat([Buffer.from([0x8a, payload.length]), payload]);
      try {
        socket.write(frame);
      } catch {
        /* ignore */
      }
    };
    const onData = (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 2) {
        const fin = (buffer[0] & 0x80) !== 0;
        const opcode = buffer[0] & 0x0f;
        const masked = (buffer[1] & 0x80) !== 0;
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
        if (masked) offset += 4;
        if (buffer.length < offset + length) return;
        const payload = Buffer.from(buffer.subarray(offset, offset + length));
        buffer = buffer.subarray(offset + length);
        if (opcode === 0x9) {
          sendPong(payload);
          continue;
        }
        if (opcode === 0x8) {
          finish({ type: 'close', payload });
          return;
        }
        if (opcode === 0xa) continue;
        if (opcode === 0x1 || opcode === 0x2) {
          fragmentOpcode = opcode;
          fragments = [payload];
        } else if (opcode === 0x0) {
          fragments.push(payload);
        } else {
          continue;
        }
        if (!fin) continue;
        const full = Buffer.concat(fragments);
        fragments = [];
        if (fragmentOpcode === 0x2) {
          finish(full);
          return;
        }
        const text = full.toString('utf8');
        if (!json) {
          finish(text);
          return;
        }
        try {
          finish(JSON.parse(text));
        } catch {
          finish(text);
        }
        return;
      }
    };
    socket.on('data', onData);
  });
}

export function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
