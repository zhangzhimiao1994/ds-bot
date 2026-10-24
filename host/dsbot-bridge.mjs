#!/usr/bin/env node
/**
 * DS Bot 主机桥接网关（dsbot-bridge）
 * ============================================================================
 * 手机 App 不能直连本机 DSH Web（127.0.0.1，且认证靠浏览器 cookie）。本进程是
 * 手机到 DSH 之间唯一的通道，也是整个项目最核心的安全边界：
 *
 *   配对手机 ≠ 主机管理员
 *
 * 设计要点（对照 docs/SECURITY.md §2/§4）：
 *   1. 方法白名单 + 默认拒绝：只反代明确允许的 DSH Remote 方法（会话读写、模型目录、
 *      审批回传、只读文件浏览、流式事件）；settings/*、credentials/*、pluginInventory/*、
 *      agentPresets/*、dynamicCordisRunner/*、goals/*、subagents/*、fileUploads/*、
 *      commands/execute、workspace 写操作、mcp/*、connectors/* 等一律 403 并审计。
 *   2. 设备 token：32 字节随机数，constant-time 比较；支持 Bearer / x-dsbot-token /
 *      ?token=；错误 token 按来源 IP 限速（>5 次/分钟 → 429）。
 *   3. Cookie 只存在于主机侧：桥接按 DSH 的 authority 规则签名浏览器 cookie 注入上游，
 *      手机的请求头与响应里的 Set-Cookie 都不会带出 cookie。
 *   4. 审计：<state-dir>/audit.log JSONL，允许与拒绝都记；绝不写 token / cookie / 密钥。
 *   5. WS 升级走 TCP 级 pipe（/api/remote.mux），只改写握手头（Host/Origin/Cookie），
 *      不解析帧，避免在桥接里重新实现一整套 WebSocket 协议。
 *
 * 零依赖：只用 node:http / node:net / node:fs / node:crypto / node:os / node:path。
 * 运行：node dsbot-bridge.mjs [--port 8788] [--dsh 127.0.0.1:64005]
 *                            [--state-dir <dir>] [--bind 0.0.0.0] [--reset]
 * 测试：node --test host/test
 */

import http from 'node:http';
import net from 'node:net';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

// ---------------------------------------------------------------------------
// 常量
// ---------------------------------------------------------------------------

export const VERSION = '0.1.0';
export const DEFAULT_PORT = 8788;
export const DEFAULT_DSH = '127.0.0.1:64005';
export const DEFAULT_BIND = '0.0.0.0';
export const DEFAULT_DEVICE_NAME = 'dsbot-device';
export const DEFAULT_STATE_DIR = path.join(os.homedir(), '.dsbot');
export const DEFAULT_CREDENTIALS_PATH = path.join(os.homedir(), '.dsh', '.credentials.yaml');

/** 单次 HTTP 请求体上限：16MB（白名单里没有上传通道，这个值只是防御性上限）。 */
export const MAX_BODY_BYTES = 16 * 1024 * 1024;
/** 每 token 的普通请求限流窗口与配额。 */
export const REQUEST_WINDOW_MS = 10_000;
export const REQUEST_LIMIT = 120;
/** 错误 token 的来源 IP 限速：窗口内最多 5 次失败，第 6 次起 429。 */
export const AUTH_FAIL_WINDOW_MS = 60_000;
export const AUTH_FAIL_LIMIT = 5;
/** WS 流式多路复用入口（DSH api-gateway 的固定路径）。 */
export const WS_PATH = '/api/remote.mux';

/**
 * /api/remote.mux 上允许打开的**流式**方法白名单。
 *
 * 为什么 WS 也需要白名单：remote.mux 是 DSH 所有 Typert Remote 流的“多路复用口”，
 * 一个物理 WS 里可以 open 任意 stream endpoint（session/follow、$events 之外，
 * 还有 terminal/attach、job/follow、account/watch……）。如果只做 TCP 透传，
 * 手机就能绕过 unary 方法白名单去开 terminal/attach —— 等于拿到交互式终端，
 * 违反 docs/SECURITY.md「配对手机 ≠ 主机管理员」。
 *
 * 因此桥接在客户端→上游方向做**最小的 WS 帧解析**：只看 `type:"open"` 的
 * endpoint，校验通过后把原始帧原样转发；下行仍然纯 pipe。不是“重新实现 WS”，
 * 只拦截多路复用层的授权决定。
 */
export const ALLOWED_STREAM_ENDPOINTS = new Set([
  'session/follow',
  'session/control',
  '$events',
  'workspace/follow',
]);

/** 单条 WS 帧 / 单条 mux 消息的内存上限（防御恶意超大帧）。 */
export const MAX_MUX_FRAME_BYTES = 8 * 1024 * 1024;
export const MAX_MUX_MESSAGE_BYTES = 16 * 1024 * 1024;

/**
 * 允许反代的 DSH Remote 方法（不带 /api/ 前缀）。
 * 只列白名单，未列出的全部拒绝 —— 默认拒绝是安全模型的一部分。
 */
export const ALLOWED_ENDPOINTS = new Set([
  // 会话读写
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
  // 审批回传（docs/SECURITY.md §1.6：只回传用户对某一次请求的裁决）
  '$events',
  '$events/result',
  // 只读元数据
  'skills/list',
  'llm/listProviders',
  'fileReferences/list',
  // 只读工作区浏览
  'workspace/follow',
  'workspaceFiles/list',
  'workspaceFiles/read',
  'workspaceFiles/readAll',
  'workspaceFiles/readBytes',
  'workspaceFiles/stat',
]);

/**
 * 明确禁止的方法前缀。默认拒绝已经覆盖它们，这里单独列出只为了：
 *   a) 审计里给出更清楚的 reason（forbidden-method 而不是 unknown-method）；
 *   b) 让 review 时能一眼对照 docs/SECURITY.md。
 */
export const FORBIDDEN_PREFIXES = [
  'settings/',
  'credentials/',
  'pluginInventory/',
  'agentPresets/',
  'dynamicCordisRunner/',
  'goals/',
  'subagents/',
  'fileUploads/',
  'commands/execute',
  'mcp/',
  'connectors/',
  'account/',
  'terminal/',
  'job/',
  'schedule/',
  'pluginManager/',
  'pluginMarket/',
  'permissionPresets/',
  'productAnalytics/',
  'messageFeedback/',
  'sessionFeedback/',
  'userQuestions/',
  'sessionReferenceResolver/',
  'directoryPicker/',
  'officeToPdf/',
  'workspace/create',
  'workspace/delete',
  'workspace/rename',
  'workspace/archiveSession',
  'workspace/unarchiveSession',
  'workspace/insertBefore',
  'workspace/insertSessionBefore',
  'workspace/pinSession',
  'workspace/unpinSession',
  'workspace/initializeDefault',
  'session/fork',
  'session/attachment',
  'session/search',
  'session/openWorkspacePath',
  'session/canOpenWorkspacePath',
  'session/projections',
  'session/updateQueue',
  'session/workspacePathApplications',
  'workspaceFiles/changes',
];

/** bridge.json 里声明的设备能力（仅做展示与审计参考，真正的边界是白名单）。 */
export const DEVICE_SCOPES = Object.freeze([
  'session:read',
  'session:write',
  'session:stream',
  'approval:respond',
  'files:read',
  'models:read',
  'skills:read',
]);

const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'expect',
]);

/** 从手机发往上游时必须丢弃的头部：认证/隐私相关，由桥接重建。 */
const CLIENT_HEADERS_DROP = new Set([
  'host',
  'origin',
  'referer',
  'cookie',
  'authorization',
  'x-dsbot-token',
  'forwarded',
  'x-forwarded-for',
  'x-forwarded-host',
  'x-forwarded-proto',
  'x-real-ip',
  'accept-encoding',
  'content-length',
]);

const SAFE_ENDPOINT_PATTERN = /^[A-Za-z0-9_$]+(?:\/[A-Za-z0-9_$]+)*$/;
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const COOKIE_TTL_MS = 7 * 24 * 60 * 60 * 1000;

const STATUS_TEXT = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  405: 'Method Not Allowed',
  413: 'Payload Too Large',
  429: 'Too Many Requests',
  502: 'Bad Gateway',
  504: 'Gateway Timeout',
};

// ---------------------------------------------------------------------------
// 基础工具
// ---------------------------------------------------------------------------

const nowMs = () => Date.now();

/** base64url 编码（无填充），Node 16+ 原生支持。 */
export function base64url(input) {
  return Buffer.from(input).toString('base64url');
}

/** sha256 十六进制摘要。 */
export function sha256Hex(value) {
  return crypto.createHash('sha256').update(value, 'utf8').digest('hex');
}

/** 设备 token 指纹：sha256 前 8 位（App“设置 → 安全”页展示用，不泄露 token）。 */
export function tokenFingerprint(token) {
  return sha256Hex(String(token)).slice(0, 8);
}

/** 把 “host:port” 解析成上游连接信息；authority 同时用于 cookie 绑定与 Host 头。 */
export function parseAuthority(raw) {
  const text = String(raw ?? '').trim();
  if (!text) throw new Error('上游地址不能为空');
  let host;
  let portText = '80';
  if (text.startsWith('[')) {
    const end = text.indexOf(']');
    if (end < 0) throw new Error(`上游地址格式错误：${text}`);
    host = text.slice(1, end);
    const rest = text.slice(end + 1);
    if (rest.startsWith(':')) portText = rest.slice(1);
  } else {
    const idx = text.lastIndexOf(':');
    if (idx < 0) {
      host = text;
    } else if (text.indexOf(':') !== idx) {
      throw new Error(`上游地址格式错误（IPv6 请加方括号）：${text}`);
    } else {
      host = text.slice(0, idx);
      portText = text.slice(idx + 1);
    }
  }
  host = host.trim();
  if (!host) throw new Error(`上游地址缺少主机名：${text}`);
  // DSH 在本机以 127.0.0.1 绑定，cookie 的 authority 必须与实际的 Host 一致。
  if (host.toLowerCase() === 'localhost') host = '127.0.0.1';
  const port = Number(portText);
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    throw new Error(`上游端口无效：${portText || '(空)'}`);
  }
  const authority = host.includes(':') ? `[${host}]:${port}` : `${host}:${port}`;
  return { authority, host, port, origin: `http://${authority}` };
}

/** DSH 浏览器会话 cookie 名：dsh-auth-<base64url(sha256(authority))>。 */
export function computeCookieName(authority) {
  return 'dsh-auth-' + crypto.createHash('sha256').update(String(authority), 'utf8').digest('base64url');
}

/**
 * 按 DSH 的规则签发一次性的浏览器会话 cookie。
 * payload = {version:1, authority, issuedAt, expiresAt: issuedAt+7d}
 * value   = "v1." + base64url(JSON(payload)) + "." + base64url(HMAC-SHA256(secret, body))
 */
export function signCookie(secret, authority, issuedAt = nowMs()) {
  const payload = {
    version: 1,
    authority,
    issuedAt,
    expiresAt: issuedAt + COOKIE_TTL_MS,
  };
  const body = base64url(JSON.stringify(payload));
  const sig = crypto.createHmac('sha256', secret).update(body, 'utf8').digest('base64url');
  return `v1.${body}.${sig}`;
}

/**
 * 从 DSH 凭据文件里取 `client-connection/browser-session` 记录的 secret。
 * 只做足够用的行解析，不引入 YAML 依赖。返回 32 字节 Buffer。
 */
export function loadCookieSecret(credentialsPath = DEFAULT_CREDENTIALS_PATH) {
  const envSecret = process.env.DSBOT_DSH_SECRET;
  if (typeof envSecret === 'string' && envSecret.trim() !== '') {
    const decoded = decodeSecretToken(envSecret.trim(), 'DSBOT_DSH_SECRET');
    if (decoded) return decoded;
  }
  let text;
  try {
    text = fs.readFileSync(credentialsPath, 'utf8');
  } catch (error) {
    throw new Error(
      `无法读取 DSH 凭据文件：${credentialsPath}\n` +
        `  请先在本机启动一次 DSH Web（dsh web），或确认当前 Windows 用户与 DSH 一致。\n` +
        `  原始错误：${error?.code ?? error?.message ?? error}`,
    );
  }
  const lines = text.split(/\r?\n/);
  let inRecord = false;
  let indentOfRecord = 0;
  for (const line of lines) {
    if (/^\s*$/.test(line) || /^\s*#/.test(line)) continue;
    const indent = line.match(/^[ \t]*/)?.[0].length ?? 0;
    const trimmed = line.trim();
    if (/^client-connection\/browser-session:\s*$/.test(trimmed)) {
      inRecord = true;
      indentOfRecord = indent;
      continue;
    }
    if (inRecord) {
      if (indent <= indentOfRecord) break; // 进入下一条记录
      const match = trimmed.match(/^secret:\s*(.+)$/);
      if (match) {
        const decoded = decodeSecretToken(match[1], credentialsPath);
        if (decoded) return decoded;
        throw new Error(
          `DSH 凭据里的 secret 不是合法的 32 字节 base64url：${credentialsPath}\n` +
            '  你可以用 DSBOT_DSH_SECRET 环境变量手动指定（同样要求 base64url 编码的 32 字节）。',
        );
      }
    }
  }
  throw new Error(
    `DSH 凭据文件里没有 client-connection/browser-session.payload.secret：${credentialsPath}\n` +
      '  请先在本机打开一次 DSH Web 完成浏览器会话初始化，然后重启桥接。',
  );
}

function decodeSecretToken(rawValue, label) {
  let value = String(rawValue ?? '').trim();
  if (
    (value.startsWith('"') && value.endsWith('"') && value.length >= 2) ||
    (value.startsWith("'") && value.endsWith("'") && value.length >= 2)
  ) {
    value = value.slice(1, -1);
  }
  if (!value) return null;
  let bytes = null;
  try {
    bytes = Buffer.from(value, 'base64url');
  } catch {
    bytes = null;
  }
  if (!bytes || bytes.length !== 32) {
    try {
      const fallback = Buffer.from(value, 'base64');
      if (fallback.length === 32) bytes = fallback;
    } catch {
      /* ignore */
    }
  }
  if (!bytes || bytes.length !== 32) {
    throw new Error(`${label}: secret 不是 32 字节（实际 ${bytes ? bytes.length : '无法解析'} 字节）`);
  }
  return bytes;
}

/** 常量时间比较两个 token（先 sha256 再 timingSafeEqual，长度不同也安全）。 */
export function tokensMatch(candidate, expected) {
  if (typeof candidate !== 'string' || typeof expected !== 'string') return false;
  const a = crypto.createHash('sha256').update(candidate, 'utf8').digest();
  const b = crypto.createHash('sha256').update(expected, 'utf8').digest();
  const equal = crypto.timingSafeEqual(a, b);
  return equal && candidate.length === expected.length;
}

/** 读取/生成设备 token 状态（<state-dir>/bridge.json）。 */
export function loadOrCreateState(options = {}) {
  const {
    stateDir = DEFAULT_STATE_DIR,
    reset = false,
    deviceName = DEFAULT_DEVICE_NAME,
    scopes = DEVICE_SCOPES,
    now = nowMs,
  } = options;
  fs.mkdirSync(stateDir, { recursive: true });
  const statePath = path.join(stateDir, 'bridge.json');
  let existing = null;
  let corrupt = false;
  try {
    existing = JSON.parse(fs.readFileSync(statePath, 'utf8'));
  } catch (error) {
    if (error?.code !== 'ENOENT') corrupt = true;
  }
  const existingToken =
    existing && typeof existing.token === 'string' && TOKEN_PATTERN.test(existing.token) ? existing.token : null;
  if (existingToken && !reset) {
    return {
      path: statePath,
      state: {
        version: 1,
        token: existingToken,
        tokenFingerprint: tokenFingerprint(existingToken),
        createdAt: typeof existing.createdAt === 'number' ? existing.createdAt : now(),
        deviceName: typeof existing.deviceName === 'string' && existing.deviceName ? existing.deviceName : deviceName,
        scopes: Array.isArray(existing.scopes) && existing.scopes.length ? existing.scopes : [...scopes],
      },
      resetPerformed: false,
      replacedCorrupt: corrupt,
    };
  }
  const token = crypto.randomBytes(32).toString('base64url');
  const state = {
    version: 1,
    token,
    tokenFingerprint: tokenFingerprint(token),
    createdAt: now(),
    deviceName,
    scopes: [...scopes],
  };
  writeStateFile(statePath, state);
  return { path: statePath, state, resetPerformed: Boolean(reset && existingToken), replacedCorrupt: corrupt };
}

function writeStateFile(statePath, state) {
  const serialized = JSON.stringify(state, null, 2) + '\n';
  fs.writeFileSync(statePath, serialized, { mode: 0o600 });
  try {
    fs.chmodSync(statePath, 0o600); // Windows 上只是尽力而为；POSIX 上真正生效
  } catch {
    /* Windows 不完整支持 chmod，忽略 */
  }
}

// ---------------------------------------------------------------------------
// 限流器（固定窗口）
// ---------------------------------------------------------------------------

export function createWindowLimiter({ limit, windowMs, now = nowMs }) {
  if (!Number.isFinite(limit) || limit <= 0) throw new Error('limit 必须为正数');
  if (!Number.isFinite(windowMs) || windowMs <= 0) throw new Error('windowMs 必须为正数');
  const buckets = new Map();
  const get = (key, create) => {
    const t = now();
    let bucket = buckets.get(key);
    if (!bucket || t - bucket.start >= windowMs) {
      if (!create) return null;
      bucket = { start: t, count: 0 };
      buckets.set(key, bucket);
    }
    return bucket;
  };
  return {
    /** 记一次请求；返回 {allowed, retryAfterSec}。 */
    consume(key) {
      const bucket = get(key, true);
      bucket.count += 1;
      if (bucket.count > limit) {
        return { allowed: false, retryAfterSec: Math.max(1, Math.ceil((bucket.start + windowMs - now()) / 1000)) };
      }
      return { allowed: true, retryAfterSec: 0 };
    },
    /** 记一次失败（认证失败），返回当前窗口内的失败次数。 */
    recordFailure(key) {
      const bucket = get(key, true);
      bucket.count += 1;
      return bucket.count;
    },
    /** 该 key 是否已经被限速（失败次数达到阈值）。 */
    isBlocked(key) {
      const bucket = get(key, false);
      return Boolean(bucket && bucket.count >= limit);
    },
    retryAfterSec(key) {
      const bucket = get(key, false);
      if (!bucket) return 1;
      return Math.max(1, Math.ceil((bucket.start + windowMs - now()) / 1000));
    },
    clear(key) {
      buckets.delete(key);
    },
    prune() {
      const t = now();
      for (const [key, bucket] of buckets) if (t - bucket.start >= windowMs) buckets.delete(key);
    },
    get size() {
      return buckets.size;
    },
  };
}

// ---------------------------------------------------------------------------
// 审计日志
// ---------------------------------------------------------------------------

/** JSONL 审计：允许与拒绝都记录；绝不包含 token/cookie/密钥。 */
export function createAuditLog(filePath, now = nowMs) {
  let failed = false;
  return {
    path: filePath,
    write(entry) {
      if (failed) return;
      const line = {
        ts: new Date(now()).toISOString(),
        method: sanitizeField(entry.method),
        path: sanitizeField(entry.path),
        decision: entry.decision === 'allow' ? 'allow' : 'deny',
        reason: sanitizeField(entry.reason ?? ''),
        status: Number.isInteger(entry.status) ? entry.status : 0,
        ms: Number.isFinite(entry.ms) ? Math.max(0, Math.round(entry.ms)) : 0,
        bytes: Number.isFinite(entry.bytes) ? Math.max(0, Math.round(entry.bytes)) : 0,
        remoteAddr: sanitizeField(entry.remoteAddr ?? ''),
        upgrade: entry.upgrade === true,
      };
      try {
        fs.appendFileSync(filePath, JSON.stringify(line) + '\n');
      } catch {
        failed = true; // 审计写失败不拖垮桥接，但只提示一次
      }
    },
  };
}

function sanitizeField(value) {
  return String(value ?? '')
    .replace(/[\r\n\t\u0000-\u001f\u007f]/g, '?')
    .slice(0, 256);
}

function normalizeRemote(address) {
  const text = String(address ?? '');
  return text.startsWith('::ffff:') ? text.slice(7) : text;
}

// ---------------------------------------------------------------------------
// 请求分类（白名单）
// ---------------------------------------------------------------------------

/**
 * 判断一个 HTTP 请求是否可被转发。
 * @returns {{allowed:boolean, kind:'health'|'api'|'other', endpoint?:string, reason:string}}
 */
export function classifyRequest(method, pathname) {
  const upper = String(method ?? '').toUpperCase();
  if (pathname === '/healthz') {
    if (upper === 'GET' || upper === 'POST') return { allowed: true, kind: 'health', reason: 'ok' };
    return { allowed: false, kind: 'health', reason: 'http-method-not-allowed' };
  }
  if (!pathname.startsWith('/api/')) {
    return { allowed: false, kind: 'other', reason: 'non-api-path' };
  }
  const endpoint = pathname.slice('/api/'.length);
  if (!SAFE_ENDPOINT_PATTERN.test(endpoint)) {
    return { allowed: false, kind: 'api', endpoint, reason: 'malformed-endpoint' };
  }
  if (upper !== 'POST') {
    return { allowed: false, kind: 'api', endpoint, reason: 'http-method-not-allowed' };
  }
  if (ALLOWED_ENDPOINTS.has(endpoint)) {
    return { allowed: true, kind: 'api', endpoint, reason: 'ok' };
  }
  const forbidden = FORBIDDEN_PREFIXES.some(
    (prefix) => endpoint === prefix || endpoint.startsWith(prefix.endsWith('/') ? prefix : prefix + '/'),
  );
  return {
    allowed: false,
    kind: 'api',
    endpoint,
    reason: forbidden ? 'forbidden-method' : 'unknown-method',
  };
}

/** 从请求里取设备 token：Authorization: Bearer > x-dsbot-token > ?token=。 */
export function extractToken(headers, searchParams) {
  const authorization = headers?.authorization;
  if (typeof authorization === 'string') {
    const match = authorization.match(/^\s*Bearer\s+(.+?)\s*$/i);
    if (match) return match[1];
  }
  const custom = headers?.['x-dsbot-token'];
  if (typeof custom === 'string' && custom.trim() !== '') return custom.trim();
  const queryToken = searchParams?.get?.('token');
  if (typeof queryToken === 'string' && queryToken.trim() !== '') return queryToken.trim();
  return '';
}

/** 尽量安全地解析请求 URL；失败时退回一个只有 pathname 的构造。 */
function safeParseRequestUrl(rawUrl) {
  const raw = typeof rawUrl === 'string' && rawUrl.startsWith('/') ? rawUrl : '/';
  const url = new URL(raw, 'http://bridge.invalid');
  return url;
}

// ---------------------------------------------------------------------------
// 桥接实例
// ---------------------------------------------------------------------------

/**
 * 创建一个桥接实例（不自动监听，方便测试）。
 * @param {object} options
 * @param {number} [options.port]
 * @param {string} [options.bind]
 * @param {string} [options.dsh] 上游 DSH “host:port”
 * @param {string} [options.stateDir]
 * @param {string} [options.credentialsPath]
 * @param {boolean} [options.reset]
 * @param {string} [options.deviceName]
 * @param {object} [options.limits] 测试用限流覆盖
 * @param {() => number} [options.now]
 */
export function createBridge(options = {}) {
  const {
    port = DEFAULT_PORT,
    bind = DEFAULT_BIND,
    dsh = DEFAULT_DSH,
    stateDir = DEFAULT_STATE_DIR,
    credentialsPath = DEFAULT_CREDENTIALS_PATH,
    reset = false,
    deviceName = DEFAULT_DEVICE_NAME,
    limits = {},
    now = nowMs,
  } = options;

  const upstream = parseAuthority(dsh);
  const secret = loadCookieSecret(credentialsPath);
  const cookieName = computeCookieName(upstream.authority);
  const stateInfo = loadOrCreateState({ stateDir, reset, deviceName, now });
  const state = stateInfo.state;
  const audit = createAuditLog(path.join(stateDir, 'audit.log'), now);

  const lim = {
    requestLimit: limits.requestLimit ?? REQUEST_LIMIT,
    requestWindowMs: limits.requestWindowMs ?? REQUEST_WINDOW_MS,
    authFailLimit: limits.authFailLimit ?? AUTH_FAIL_LIMIT,
    authFailWindowMs: limits.authFailWindowMs ?? AUTH_FAIL_WINDOW_MS,
  };
  const requestLimiter = createWindowLimiter({
    limit: lim.requestLimit,
    windowMs: lim.requestWindowMs,
    now,
  });
  const authFailLimiter = createWindowLimiter({
    limit: lim.authFailLimit,
    windowMs: lim.authFailWindowMs,
    now,
  });
  const tokenKey = sha256Hex(state.token);

  const sockets = new Set();
  let server = null;
  let portActual = port;
  let startedAt = 0;
  let pruneTimer = null;

  const bridge = {
    version: VERSION,
    upstream,
    cookieName,
    stateDir,
    credentialsPath,
    auditPath: audit.path,
    state,
    token: state.token,
    tokenFingerprint: state.tokenFingerprint,
    resetPerformed: stateInfo.resetPerformed,
    replacedCorrupt: stateInfo.replacedCorrupt,
    limits: lim,
    get port() {
      return portActual;
    },
    get bind() {
      return bind;
    },
    get startedAt() {
      return startedAt;
    },
    get uptimeSec() {
      return startedAt ? Math.floor((now() - startedAt) / 1000) : 0;
    },
    get auditLogPath() {
      return audit.path;
    },
    /** 本轮请求注入的 cookie 值（仅用于启动诊断/测试断言，不写日志、不回给手机）。 */
    currentCookie() {
      return signCookie(secret, upstream.authority, now());
    },
    verifyToken(candidate) {
      return tokensMatch(candidate, state.token);
    },
    audit(entry) {
      audit.write(entry);
    },
    async start() {
      if (server) return bridge;
      server = http.createServer((req, res) => {
        handleHttpRequest(bridge, req, res).catch((error) => {
          if (process.env.DSBOT_DEBUG) console.error('[bridge] internal error:', error);
          if (!res.headersSent) {
            sendJson(res, 500, 'bridge/internal-error', '桥接内部错误');
          } else {
            try {
              res.destroy();
            } catch {
              /* ignore */
            }
          }
          bridge.audit({ method: req.method, path: '/', decision: 'deny', reason: 'internal-error', status: 500, remoteAddr: normalizeRemote(req.socket?.remoteAddress) });
        });
      });
      // 上游可能长时间不返回（prompt 等），桥接不能替手机超时；只限制建连。
      server.requestTimeout = 0;
      server.headersTimeout = 60_000;
      server.keepAliveTimeout = 65_000;
      server.timeout = 0;
      server.on('connection', (socket) => {
        sockets.add(socket);
        socket.on('close', () => sockets.delete(socket));
        socket.on('error', () => {});
      });
      server.on('upgrade', (req, socket, head) => {
        try {
          handleUpgrade(bridge, req, socket, head);
        } catch {
          try {
            socket.destroy();
          } catch {
            /* ignore */
          }
        }
      });
      server.on('clientError', (error, socket) => {
        if (!socket.destroyed) {
          try {
            socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
          } catch {
            socket.destroy();
          }
        }
        void error;
      });
      await new Promise((resolve, reject) => {
        const onError = (error) => {
          server?.off('listening', onListening);
          if (error?.code === 'EADDRINUSE') {
            reject(new Error(`端口 ${port} 已被占用。换一个端口：--port ${port + 1}`));
          } else if (error?.code === 'EACCES') {
            reject(new Error(`没有权限监听端口 ${port}（小于 1024 需要管理员权限）。`));
          } else {
            reject(new Error(`监听 ${bind}:${port} 失败：${error?.message ?? error}`));
          }
        };
        const onListening = () => {
          server?.off('error', onError);
          resolve();
        };
        server.once('error', onError);
        server.once('listening', onListening);
        try {
          server.listen(port, bind);
        } catch (error) {
          server.off('listening', onListening);
          onError(error);
        }
      });
      const address = server.address();
      portActual = typeof address === 'object' && address ? address.port : port;
      startedAt = now();
      pruneTimer = setInterval(() => {
        requestLimiter.prune();
        authFailLimiter.prune();
      }, 60_000);
      pruneTimer.unref?.();
      audit.write({ method: 'START', path: 'bridge', decision: 'allow', reason: 'listening', status: 0, remoteAddr: '' });
      return bridge;
    },
    async stop() {
      if (pruneTimer) {
        clearInterval(pruneTimer);
        pruneTimer = null;
      }
      if (!server) return;
      const closing = server;
      server = null;
      await new Promise((resolve) => {
        let done = false;
        const finish = () => {
          if (done) return;
          done = true;
          resolve();
        };
        closing.close(finish);
        for (const socket of sockets) {
          try {
            socket.destroy();
          } catch {
            /* ignore */
          }
        }
        sockets.clear();
        setTimeout(finish, 1500).unref?.();
      });
      audit.write({ method: 'STOP', path: 'bridge', decision: 'allow', reason: 'stopped', status: 0, remoteAddr: '' });
    },
  };
  // WS 分支与 HTTP 分支必须共用同一份限流/认证失败状态。
  bridgeLimiters.set(bridge, { authFail: authFailLimiter, request: requestLimiter });
  return bridge;
}

// ---------------------------------------------------------------------------
// HTTP 请求处理
// ---------------------------------------------------------------------------

async function handleHttpRequest(bridge, req, res) {
  const started = nowMs();
  const remoteAddr = normalizeRemote(req.socket?.remoteAddress);
  const parsedUrl = safeParseRequestUrl(req.url);
  const pathname = parsedUrl.pathname;
  const base = { method: req.method ?? '', path: pathname, remoteAddr, upgrade: false };

  const deny = (status, code, message, reason, extraHeaders = {}) => {
    const bytes = sendJson(res, status, code, message, extraHeaders);
    bridge.audit({ ...base, decision: 'deny', reason, status, ms: nowMs() - started, bytes });
    return { denied: true };
  };

  // 1) 认证：无 token / token 错误 —— 都先挡在权限判断之前。
  const token = extractToken(req.headers, parsedUrl.searchParams);
  if (!token) {
    return deny(401, 'bridge/unauthorized', '缺少设备令牌：请在请求头 Authorization: Bearer <token> 或 ?token= 中提供', 'missing-token');
  }
  if (bridgeAuthLimiter(bridge).isBlocked(remoteAddr)) {
    return deny(429, 'bridge/rate-limited', '认证失败次数过多，请稍后再试', 'auth-rate-limited', {
      'retry-after': String(bridgeAuthLimiter(bridge).retryAfterSec(remoteAddr)),
    });
  }
  if (!bridge.verifyToken(token)) {
    bridgeAuthLimiter(bridge).recordFailure(remoteAddr);
    return deny(401, 'bridge/unauthorized', '设备令牌无效', 'invalid-token');
  }
  bridgeAuthLimiter(bridge).clear(remoteAddr);

  // 2) 每 token 的普通请求限流。
  const quota = bridgeRequestLimiter(bridge).consume(sha256Hex(bridge.token));
  if (!quota.allowed) {
    return deny(429, 'bridge/rate-limited', '请求过于频繁，请稍后再试', 'rate-limited', {
      'retry-after': String(quota.retryAfterSec),
    });
  }

  // 3) 白名单/路由判断（默认拒绝）。
  const route = classifyRequest(req.method, pathname);
  if (!route.allowed) {
    return deny(403, 'bridge/forbidden', forbiddenMessage(route), route.reason);
  }

  // 4) /healthz：只读健康信息。
  if (route.kind === 'health') {
    const payload = {
      ok: true,
      upstream: bridge.upstream.authority,
      version: bridge.version,
      deviceName: bridge.state.deviceName,
      tokenFingerprint: bridge.tokenFingerprint,
      scopes: bridge.state.scopes,
      uptimeSec: bridge.uptimeSec,
    };
    const body = Buffer.from(JSON.stringify(payload));
    res.writeHead(200, baseHeaders(body.length));
    res.end(body);
    bridge.audit({ ...base, decision: 'allow', reason: 'health', status: 200, ms: nowMs() - started, bytes: body.length });
    return { denied: false };
  }

  // 5) 反代。
  return proxyHttpRequest(bridge, req, res, parsedUrl, base, started);
}

function forbiddenMessage(route) {
  if (route.kind === 'other') return '桥接只暴露 /api 白名单接口与 /healthz';
  if (route.reason === 'malformed-endpoint') return '接口路径不合法';
  if (route.reason === 'http-method-not-allowed') return '该接口只接受 POST 请求';
  if (route.reason === 'forbidden-method') return `该接口不允许手机访问：${route.endpoint}`;
  return `未知接口，默认拒绝：${route.endpoint ?? ''}`;
}

function baseHeaders(contentLength, extra = {}) {
  return {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(contentLength),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...extra,
  };
}

function sendJson(res, status, code, message, extraHeaders = {}) {
  const body = Buffer.from(JSON.stringify({ ok: false, error: { code, message } }));
  if (res.headersSent || res.writableEnded) return body.length;
  res.writeHead(status, baseHeaders(body.length, extraHeaders));
  res.end(body);
  return body.length;
}

async function readRequestBody(req, limit) {
  return await new Promise((resolve, reject) => {
    const declared = Number(req.headers['content-length']);
    if (Number.isFinite(declared) && declared > limit) {
      const error = new Error('请求体超过上限');
      error.code = 'body-too-large';
      reject(error);
      return;
    }
    const chunks = [];
    let size = 0;
    let settled = false;
    const fail = (error) => {
      if (settled) return;
      settled = true;
      reject(error);
    };
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        const error = new Error('请求体超过上限');
        error.code = 'body-too-large';
        fail(error);
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      if (settled) return;
      settled = true;
      resolve(Buffer.concat(chunks));
    });
    req.on('error', fail);
    req.on('aborted', () => fail(Object.assign(new Error('客户端中止了请求'), { code: 'client-aborted' })));
  });
}

function buildUpstreamHeaders(bridge, incomingHeaders, body) {
  const headers = {};
  for (const [name, value] of Object.entries(incomingHeaders ?? {})) {
    const lower = name.toLowerCase();
    if (HOP_BY_HOP.has(lower) || CLIENT_HEADERS_DROP.has(lower)) continue;
    if (value === undefined) continue;
    headers[lower] = value;
  }
  headers.host = bridge.upstream.authority;
  headers.origin = bridge.upstream.origin;
  headers.cookie = `${bridge.cookieName}=${bridge.currentCookie()}`;
  // 强制上游返回未压缩内容：桥接只做透传，不替手机解压，审计字节数也更直观。
  headers['accept-encoding'] = 'identity';
  if (body && body.length > 0) headers['content-length'] = String(body.length);
  else if (body) headers['content-length'] = '0';
  return headers;
}

/** 去掉查询串里的 token（设备令牌绝不能转发给上游或落上游日志）。 */
function upstreamPath(rawUrl) {
  const url = safeParseRequestUrl(rawUrl);
  url.searchParams.delete('token');
  return url.pathname + url.search;
}

async function proxyHttpRequest(bridge, req, res, parsedUrl, base, started) {
  let body;
  try {
    body = await readRequestBody(req, MAX_BODY_BYTES);
  } catch (error) {
    const tooLarge = error?.code === 'body-too-large';
    const status = tooLarge ? 413 : 400;
    const code = tooLarge ? 'bridge/body-too-large' : 'bridge/bad-request';
    const message = tooLarge ? '请求体超过 16MB 上限' : '无法读取请求体';
    // 请求体没读完就回错误：告知连接即将关闭，并把残余数据排空，避免客户端卡在写。
    const bytes = sendJson(res, status, code, message, tooLarge ? { connection: 'close' } : {});
    bridge.audit({ ...base, decision: 'deny', reason: tooLarge ? 'body-too-large' : 'bad-request', status, ms: nowMs() - started, bytes });
    req.on('error', () => {});
    req.resume();
    return { denied: true };
  }

  const targetPath = upstreamPath(req.url);
  const headers = buildUpstreamHeaders(bridge, req.headers, body);
  const auditBase = { ...base, path: parsedUrl.pathname };
  const finished = { value: false };
  const finish = (outcome) => {
    if (finished.value) return;
    finished.value = true;
    bridge.audit({ ...auditBase, ms: nowMs() - started, ...outcome });
  };

  const upstreamReq = http.request({
    host: bridge.upstream.host,
    port: bridge.upstream.port,
    method: req.method,
    path: targetPath,
    headers,
    agent: false,
  });
  let responded = false;
  upstreamReq.setTimeout(30_000, () => {
    if (!responded) upstreamReq.destroy(Object.assign(new Error('上游连接超时'), { code: 'upstream-timeout' }));
  });
  upstreamReq.on('error', (error) => {
    if (res.headersSent || res.writableEnded) {
      try {
        res.destroy();
      } catch {
        /* ignore */
      }
      finish({ decision: 'allow', reason: 'upstream-error', status: 502, bytes: 0 });
      return;
    }
    const timeout = error?.code === 'upstream-timeout';
    const bytes = sendJson(
      res,
      timeout ? 504 : 502,
      timeout ? 'bridge/upstream-timeout' : 'bridge/upstream-unreachable',
      timeout ? `上游 DSH 无响应：${bridge.upstream.authority}` : `无法连接上游 DSH：${bridge.upstream.authority}`,
    );
    finish({ decision: 'allow', reason: timeout ? 'upstream-timeout' : 'upstream-error', status: timeout ? 504 : 502, bytes });
  });
  upstreamReq.on('response', (upstreamRes) => {
    responded = true;
    upstreamReq.setTimeout(0);
    const responseHeaders = {};
    for (const [name, value] of Object.entries(upstreamRes.headers)) {
      const lower = name.toLowerCase();
      if (HOP_BY_HOP.has(lower)) continue;
      // 手机永远拿不到 DSH 浏览器 cookie。
      if (lower === 'set-cookie') continue;
      responseHeaders[name] = value;
    }
    const status = upstreamRes.statusCode ?? 502;
    res.writeHead(status, responseHeaders);
    let bytes = 0;
    upstreamRes.on('data', (chunk) => {
      bytes += chunk.length;
    });
    upstreamRes.on('end', () => {
      finish({ decision: 'allow', reason: 'proxied', status, bytes });
    });
    upstreamRes.on('error', () => {
      try {
        res.destroy();
      } catch {
        /* ignore */
      }
      finish({ decision: 'allow', reason: 'upstream-stream-error', status, bytes });
    });
    res.on('close', () => {
      if (!res.writableFinished) {
        upstreamRes.destroy();
        finish({ decision: 'allow', reason: 'client-aborted', status, bytes });
      }
    });
    upstreamRes.pipe(res);
  });
  upstreamReq.end(body);
  return { denied: false };
}

// ---------------------------------------------------------------------------
// WebSocket 升级：TCP 级 pipe
// ---------------------------------------------------------------------------

function handleUpgrade(bridge, req, socket, head) {
  const started = nowMs();
  const remoteAddr = normalizeRemote(socket.remoteAddress);
  const parsedUrl = safeParseRequestUrl(req.url);
  const base = { method: 'GET', path: parsedUrl.pathname, remoteAddr, upgrade: true, ms: 0 };

  socket.on('error', () => {});
  socket.setNoDelay?.(true);

  const reject = (status, code, message, reason) => {
    const body = JSON.stringify({ ok: false, error: { code, message } });
    const headText =
      `HTTP/1.1 ${status} ${STATUS_TEXT[status] ?? 'Error'}\r\n` +
      'content-type: application/json; charset=utf-8\r\n' +
      `content-length: ${Buffer.byteLength(body)}\r\n` +
      'cache-control: no-store\r\n' +
      'connection: close\r\n\r\n';
    try {
      socket.end(headText + body);
    } catch {
      try {
        socket.destroy();
      } catch {
        /* ignore */
      }
    }
    bridge.audit({ ...base, decision: 'deny', reason, status, ms: nowMs() - started, bytes: Buffer.byteLength(body) });
  };

  // 1) 认证（浏览器 WS 不能自定义头，所以 ?token= 是主要方式；原生客户端也可用头）。
  const token = extractToken(req.headers, parsedUrl.searchParams);
  if (!token) return reject(401, 'bridge/unauthorized', '缺少设备令牌：WS 连接请使用 ?token=<token>', 'missing-token');
  if (authFailLimiterIsBlocked(bridge, remoteAddr)) {
    return reject(429, 'bridge/rate-limited', '认证失败次数过多，请稍后再试', 'auth-rate-limited');
  }
  if (!bridge.verifyToken(token)) {
    recordAuthFailure(bridge, remoteAddr);
    return reject(401, 'bridge/unauthorized', '设备令牌无效', 'invalid-token');
  }
  clearAuthFailures(bridge, remoteAddr);

  // 2) 普通请求限流。
  if (!consumeRequestQuota(bridge)) {
    return reject(429, 'bridge/rate-limited', '连接过于频繁，请稍后再试', 'rate-limited');
  }

  // 3) 只允许流式多路复用入口，且必须是合法 WS 握手。
  if (parsedUrl.pathname !== WS_PATH) {
    return reject(403, 'bridge/forbidden', `WS 只允许 ${WS_PATH}，其他流式入口默认拒绝`, 'forbidden-ws-path');
  }
  const clientKey = req.headers['sec-websocket-key'];
  const protocolHeader = req.headers['sec-websocket-protocol'];
  if (typeof clientKey !== 'string' || clientKey.trim() === '') {
    return reject(400, 'bridge/bad-request', 'WS 握手缺少 Sec-WebSocket-Key', 'bad-ws-handshake');
  }

  // 4) 连接上游，改写握手头（Host/Origin/Cookie），其余保持原始 WS 语义。
  const upstreamSocket = net.connect({ host: bridge.upstream.host, port: bridge.upstream.port });
  let settled = false;
  let buffered = Buffer.alloc(0);
  let bytesUp = 0;
  let bytesDown = 0;
  const dispose = () => {
    try {
      socket.destroy();
    } catch {
      /* ignore */
    }
    try {
      upstreamSocket.destroy();
    } catch {
      /* ignore */
    }
  };
  const finishAudit = (outcome) => {
    if (settled) return;
    settled = true;
    bridge.audit({ ...base, ms: nowMs() - started, ...outcome });
  };
  const failUpstream = (reason, status, code, message) => {
    if (settled) return;
    finishAudit({ decision: 'allow', reason, status, bytes: bytesDown });
    if (!upstreamSocket.destroyed) {
      const body = JSON.stringify({ ok: false, error: { code, message } });
      const text =
        `HTTP/1.1 ${status} ${STATUS_TEXT[status] ?? 'Error'}\r\n` +
        'content-type: application/json; charset=utf-8\r\n' +
        `content-length: ${Buffer.byteLength(body)}\r\nconnection: close\r\n\r\n`;
      try {
        socket.end(text + body);
      } catch {
        /* ignore */
      }
    }
    dispose();
  };

  upstreamSocket.setTimeout(10_000, () => {
    upstreamSocket.destroy(Object.assign(new Error('上游握手超时'), { code: 'upstream-timeout' }));
  });
  upstreamSocket.on('error', () => {
    failUpstream('upstream-error', 502, 'bridge/upstream-unreachable', `无法连接上游 DSH：${bridge.upstream.authority}`);
  });
  upstreamSocket.on('connect', () => {
    const lines = [
      `GET ${WS_PATH} HTTP/1.1`,
      `Host: ${bridge.upstream.authority}`,
      `Origin: ${bridge.upstream.origin}`,
      `Cookie: ${bridge.cookieName}=${bridge.currentCookie()}`,
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Key: ${clientKey.trim()}`,
      `Sec-WebSocket-Version: ${req.headers['sec-websocket-version'] ?? '13'}`,
    ];
    // 只转发协议子协商（DSH 目前不使用，但保留语义）；不转发扩展，避免透传压缩协商。
    if (typeof protocolHeader === 'string' && protocolHeader !== '') {
      lines.push(`Sec-WebSocket-Protocol: ${protocolHeader}`);
    }
    lines.push('User-Agent: dsbot-bridge/' + VERSION, '', '');
    upstreamSocket.write(lines.join('\r\n'));
  });
  upstreamSocket.on('data', (chunk) => {
    if (settled) return;
    if (buffered.length === 0) {
      buffered = Buffer.from(chunk);
    } else {
      buffered = Buffer.concat([buffered, chunk]);
    }
    const headerEnd = buffered.indexOf('\r\n\r\n');
    if (headerEnd < 0) {
      if (buffered.length > 64 * 1024) failUpstream('upstream-bad-handshake', 502, 'bridge/upstream-error', '上游握手响应异常');
      return;
    }
    const headText = buffered.subarray(0, headerEnd + 4).toString('latin1');
    const rest = buffered.subarray(headerEnd + 4);
    const statusLine = headText.split('\r\n', 1)[0] ?? '';
    const statusMatch = statusLine.match(/^HTTP\/1\.[01]\s+(\d{3})/);
    const status = statusMatch ? Number(statusMatch[1]) : 502;
    if (status !== 101) {
      // 上游拒绝升级：把它的响应原样还给客户端，然后断开。
      finishAudit({ decision: 'allow', reason: 'upstream-rejected-ws', status, bytes: buffered.length });
      settled = true;
      try {
        socket.end(buffered);
      } catch {
        /* ignore */
      }
      dispose();
      return;
    }
    // 握手成功：把上游 101 响应（含可能已经捎带的数据）转给客户端。
    settled = true;
    upstreamSocket.setTimeout(0);
    try {
      socket.write(buffered);
    } catch {
      dispose();
      return;
    }
    bytesDown += rest.length;
    upstreamSocket.on('data', (upChunk) => {
      bytesDown += upChunk.length;
    });

    let closedAudited = false;
    let violated = false;
    const complete = () => {
      if (closedAudited) return;
      closedAudited = true;
      dispose();
      if (violated) return; // 违规已按 deny 审计过了
      bridge.audit({ ...base, decision: 'allow', reason: 'ws-proxied', status: 101, ms: nowMs() - started, bytes: bytesDown });
    };
    const violation = (detail) => {
      if (closedAudited) return;
      violated = true;
      closedAudited = true;
      bridge.audit({
        ...base,
        decision: 'deny',
        reason: 'mux-violation',
        status: 403,
        ms: nowMs() - started,
        bytes: bytesDown,
      });
      // 给客户端回一个标准 WS Close 帧（1008 = policy violation），再断开两端。
      try {
        socket.write(Buffer.from([0x88, 0x02, 0x03, 0xf0]));
      } catch {
        /* ignore */
      }
      try {
        socket.end();
      } catch {
        /* ignore */
      }
      try {
        upstreamSocket.destroy();
      } catch {
        /* ignore */
      }
      if (process.env.DSBOT_DEBUG) console.error('[bridge] mux violation:', detail);
    };

    // 客户端 → 上游：只解析 mux 层的 open/item/end/cancel 帧，做流式端点白名单校验；
    // 校验通过的帧按原字节转发（不做任何改写），下行继续纯 pipe。
    const muxFilter = createMuxClientFilter({
      write(frame) {
        if (upstreamSocket.destroyed) return;
        if (!upstreamSocket.write(frame)) {
          try {
            socket.pause();
          } catch {
            /* ignore */
          }
        }
      },
      onViolation: violation,
    });
    upstreamSocket.on('drain', () => {
      if (!socket.destroyed) socket.resume();
    });
    socket.on('data', (clientChunk) => {
      bytesUp += clientChunk.length;
      muxFilter.push(clientChunk);
    });
    if (head && head.length > 0) {
      bytesUp += head.length;
      muxFilter.push(head);
    }
    upstreamSocket.pipe(socket, { end: true });
    // 双端半开兜底：HTTP server 升级出来的 socket 是 allowHalfOpen=true，读到 FIN 后不会
    // 自动关闭写方向。两端都显式 end()，保证对端断线后能尽快 close 并写审计。
    socket.on('end', () => {
      try {
        upstreamSocket.end();
      } catch {
        /* ignore */
      }
      try {
        socket.end();
      } catch {
        /* ignore */
      }
    });
    upstreamSocket.on('end', () => {
      try {
        socket.end();
      } catch {
        /* ignore */
      }
      try {
        upstreamSocket.end();
      } catch {
        /* ignore */
      }
    });
    socket.on('close', complete);
    socket.on('error', complete);
    upstreamSocket.on('error', complete);
    upstreamSocket.on('close', () => {
      try {
        socket.end();
      } catch {
        /* ignore */
      }
      complete();
    });
  });
}

/**
 * 最小 WS 帧过滤器（只处理客户端→上游方向）。
 *
 * 目的：/api/remote.mux 把 DSH 全部 Typert Remote 流复用在一条 WS 上，纯 TCP 透传
 * 会让手机绕过 unary 白名单打开 terminal/attach 这类危险流。这里只解析出完整的
 * 文本帧、看 `{type:"open", endpoint}` 是否在 ALLOWED_STREAM_ENDPOINTS 里：
 *   - 通过：把收到的原始帧字节按序转发（完全不改写，压缩/掩码/扩展都原样）；
 *   - 违规：不转发该消息，回调 onViolation（审计 + Close 1008 断连）。
 * 非文本帧、二进制帧、未知帧类型一律按违规处理（默认拒绝）。
 */
export function createMuxClientFilter({ write, onViolation }) {
  let buffer = Buffer.alloc(0);
  let closed = false;
  let message = null; // { opcode, payloads, raws, size }
  const fail = (detail) => {
    closed = true;
    onViolation(detail);
  };
  const inspectTextPayload = (payload) => {
    let parsed;
    try {
      parsed = JSON.parse(payload.toString('utf8'));
    } catch {
      return 'invalid-json-frame';
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return 'invalid-mux-frame';
    if (parsed.type === 'open') {
      const endpoint = parsed.endpoint;
      if (typeof endpoint !== 'string' || endpoint === '') return 'invalid-stream-endpoint';
      if (!ALLOWED_STREAM_ENDPOINTS.has(endpoint)) return `forbidden-stream-endpoint:${endpoint}`;
      return null;
    }
    if (parsed.type === 'item' || parsed.type === 'end' || parsed.type === 'cancel') return null;
    return `unknown-mux-frame:${String(parsed.type)}`;
  };
  const handleFrame = (fin, opcode, payload, raw) => {
    // 控制帧：原样转发（ping/pong/close 不影响 mux 授权）。
    if (opcode === 0x8) {
      write(raw);
      closed = true;
      return null;
    }
    if (opcode === 0x9 || opcode === 0xa) {
      write(raw);
      return null;
    }
    if (opcode === 0x1 || opcode === 0x2) {
      message = { opcode, payloads: [payload], raws: [raw], size: payload.length };
    } else if (opcode === 0x0) {
      if (!message) return 'unexpected-continuation';
      message.payloads.push(payload);
      message.raws.push(raw);
      message.size += payload.length;
      if (message.size > MAX_MUX_MESSAGE_BYTES) return 'mux-message-too-large';
    } else {
      return `unsupported-opcode:${opcode}`;
    }
    if (!fin) return null;
    const finished = message;
    message = null;
    if (finished.opcode === 0x2) return 'binary-mux-frame';
    const detail = inspectTextPayload(Buffer.concat(finished.payloads));
    if (detail) return detail;
    for (const frame of finished.raws) write(frame);
    return null;
  };
  return {
    push(chunk) {
      if (closed || chunk.length === 0) return;
      buffer = buffer.length === 0 ? chunk : Buffer.concat([buffer, chunk]);
      let detail = null;
      while (!closed && buffer.length >= 2) {
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
          const big = buffer.readBigUInt64BE(2);
          if (big > BigInt(MAX_MUX_FRAME_BYTES)) {
            detail = 'mux-frame-too-large';
            break;
          }
          length = Number(big);
          offset = 10;
        }
        if (length > MAX_MUX_FRAME_BYTES) {
          detail = 'mux-frame-too-large';
          break;
        }
        const maskLength = masked ? 4 : 0;
        const total = offset + maskLength + length;
        if (buffer.length < total) return;
        const raw = Buffer.from(buffer.subarray(0, total));
        const payload = Buffer.from(buffer.subarray(offset + maskLength, total));
        if (masked) {
          const mask = buffer.subarray(offset, offset + 4);
          for (let index = 0; index < payload.length; index += 1) payload[index] ^= mask[index & 3];
        }
        buffer = buffer.subarray(total);
        detail = handleFrame(fin, opcode, payload, raw);
        if (detail) break;
      }
      if (detail) fail(detail);
    },
  };
}

/** WS 分支用的限流/认证小工具：与 HTTP 分支共享同一个桥接实例上的 limiter。 */
function authFailLimiterIsBlocked(bridge, remoteAddr) {
  return bridgeAuthLimiter(bridge).isBlocked(remoteAddr);
}
function recordAuthFailure(bridge, remoteAddr) {
  bridgeAuthLimiter(bridge).recordFailure(remoteAddr);
}
function clearAuthFailures(bridge, remoteAddr) {
  bridgeAuthLimiter(bridge).clear(remoteAddr);
}
function consumeRequestQuota(bridge) {
  const limiter = bridgeRequestLimiter(bridge);
  return limiter.consume(sha256Hex(bridge.token)).allowed;
}

// createBridge 把 limiter 挂在内部闭包里；这两个 WeakMap 让 WS 分支拿到同一份状态。
const bridgeLimiters = new WeakMap();

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

export function parseArgs(argv) {
  const options = {
    port: DEFAULT_PORT,
    bind: DEFAULT_BIND,
    dsh: DEFAULT_DSH,
    stateDir: DEFAULT_STATE_DIR,
    credentialsPath: DEFAULT_CREDENTIALS_PATH,
    reset: false,
    help: false,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    const readValue = (name) => {
      const eq = arg.indexOf('=');
      if (eq >= 0) return arg.slice(eq + 1);
      index += 1;
      if (index >= argv.length) throw new Error(`参数 ${name} 缺少值`);
      return argv[index];
    };
    const name = arg.includes('=') ? arg.slice(0, arg.indexOf('=')) : arg;
    if (name === '--help' || name === '-h') options.help = true;
    else if (name === '--reset') options.reset = true;
    else if (name === '--port') {
      const value = Number(readValue('--port'));
      if (!Number.isInteger(value) || value <= 0 || value > 65535) throw new Error(`--port 必须是 1-65535 的整数，收到：${value}`);
      options.port = value;
    } else if (name === '--bind') options.bind = requireText(readValue('--bind'), '--bind');
    else if (name === '--dsh') options.dsh = requireText(readValue('--dsh'), '--dsh');
    else if (name === '--state-dir') options.stateDir = path.resolve(requireText(readValue('--state-dir'), '--state-dir'));
    else if (name === '--credentials') options.credentialsPath = path.resolve(requireText(readValue('--credentials'), '--credentials'));
    else if (name === '--version' || name === '-v') {
      options.version = true;
    } else throw new Error(`未知参数：${arg}（用 --help 查看用法）`);
  }
  return options;
}

function requireText(value, name) {
  const text = String(value ?? '').trim();
  if (!text) throw new Error(`${name} 不能为空`);
  return text;
}

export function lanIPv4Addresses(interfaces = os.networkInterfaces()) {
  const result = [];
  for (const [name, addresses] of Object.entries(interfaces)) {
    for (const address of addresses ?? []) {
      if (address.family === 'IPv4' && !address.internal) result.push({ name, address: address.address });
    }
  }
  return result;
}

function pairingUri(host, port, token) {
  return `dsbot://pair?url=http://${host}:${port}&token=${token}`;
}

export function formatStartupReport(bridge, options = {}) {
  const { port = bridge.port, bind = bridge.bind } = options;
  const token = bridge.token;
  const lan = lanIPv4Addresses();
  const lines = [];
  lines.push('============================================================');
  lines.push(`  DS Bot 主机桥接网关 v${bridge.version}`);
  lines.push('  手机 ⇄ 本桥接（设备 token）⇄ 本机 DSH Web（浏览器 cookie）');
  lines.push('============================================================');
  // 启动输出可能被截图/贴日志：cookie 只展示名字里的 8 位 hash 前缀，
  // 绝不打印完整 cookie 名/值（与 host/README「日志/终端不含 cookie」一致）。
  const cookieHint = `${bridge.cookieName.slice(0, 'dsh-auth-'.length + 8)}…`;
  lines.push(`上游 DSH 地址 : http://${bridge.upstream.authority}  (cookie: 已签名 ${cookieHint})`);
  lines.push(`桥接监听      : ${bind}:${port}${bind === '0.0.0.0' ? '（所有网卡）' : ''}`);
  lines.push(`状态目录      : ${bridge.stateDir}`);
  lines.push(`审计日志      : ${bridge.auditPath}`);
  lines.push(`设备 token 指纹: ${bridge.tokenFingerprint}（sha256 前 8 位；App「设置 → 安全」核对用）`);
  if (bridge.resetPerformed) {
    lines.push('');
    lines.push('⚠ 已按 --reset 轮换设备 token：以前配对过的手机全部立即失效。');
  }
  if (bridge.replacedCorrupt) {
    lines.push('');
    lines.push('⚠ 原 bridge.json 损坏或格式不对，已重新生成设备 token。');
  }
  lines.push('');
  lines.push('本机局域网 IPv4 地址（手机必须与电脑在同一可信局域网）：');
  if (lan.length === 0) {
    lines.push('  （没有检测到局域网 IPv4 网卡；手机可能连不上，请检查 Wi-Fi / 网线连接）');
  } else {
    for (const item of lan) lines.push(`  - ${item.address}  (${item.name})`);
  }
  lines.push('');
  lines.push('一次性配对串（把下面完整一行交给手机 App；建议复制，不要截图）：');
  const hosts = lan.length > 0 ? lan.map((item) => item.address) : [];
  if (bind === '127.0.0.1' || bind === 'localhost' || bind === '::1') {
    lines.push(`  ${pairingUri('127.0.0.1', port, token)}`);
    lines.push('  ⚠ 当前只绑定了回环地址，手机无法访问；需要局域网配对请用 --bind 0.0.0.0。');
  } else if (hosts.length === 0) {
    lines.push(`  ${pairingUri('<本机局域网IP>', port, token)}`);
  } else {
    for (const host of hosts) lines.push(`  ${pairingUri(host, port, token)}`);
  }
  if (bind !== '0.0.0.0' && bind !== '127.0.0.1' && bind !== '::') {
    lines.push(`  ${pairingUri(bind, port, token)}`);
  }
  lines.push('');
  lines.push('安全提示（务必阅读）：');
  lines.push('  1. 只在你信任的局域网里使用。桥接是 HTTP 明文传输（DSH 本身也不带 TLS），');
  lines.push('     公共 Wi-Fi/不可信网络请先自建 TLS 或 VPN 隧道，绝不要把端口直接暴露到公网。');
  lines.push('  2. 配对手机 ≠ 主机管理员：手机只能做会话读写、模型目录、审批回传、只读文件浏览；');
  lines.push('     修改设置、读写密钥、安装插件、执行命令等请求会被桥接 403 并记入审计。');
  lines.push(`  3. 手机丢失或 token 泄露：先在电脑上 Ctrl+C 停止桥接，再运行：`);
  lines.push('         node host/dsbot-bridge.mjs --reset');
  lines.push('     旧 token 立即作废，需要重新配对。');
  lines.push(`  4. 审计日志（JSONL，允许/拒绝都有；不含 token/cookie）：`);
  lines.push(`         ${bridge.auditPath}`);
  lines.push('  5. 本终端输出包含配对令牌，请勿截图或转发到聊天群；关闭窗口后需要重新查看请重跑启动命令。');
  lines.push('');
  lines.push(`已就绪，等待手机连接…（Ctrl+C 停止）`);
  return lines.join('\n');
}

export function formatHelp() {
  return [
    'DS Bot 主机桥接网关（把手机变成受限的 DSH 聊天终端，而不是主机管理员）',
    '',
    '用法：',
    '  node host/dsbot-bridge.mjs [选项]',
    '',
    '选项：',
    `  --port <端口>          桥接监听端口（默认 ${DEFAULT_PORT}）`,
    `  --dsh <host:port>      上游 DSH Web 地址（默认 ${DEFAULT_DSH}）`,
    `  --bind <地址>          监听地址（默认 ${DEFAULT_BIND}；只允许本机访问用 127.0.0.1）`,
    '  --state-dir <目录>     状态目录（默认 %USERPROFILE%\\.dsbot；bridge.json + audit.log）',
    '  --credentials <文件>   DSH 凭据文件（默认 %USERPROFILE%\\.dsh\\.credentials.yaml）',
    '  --reset                轮换设备 token，立即撤销所有已配对手机',
    '  --version              打印版本号',
    '  --help                 显示本帮助',
    '',
    '示例：',
    '  node host/dsbot-bridge.mjs',
    '  node host/dsbot-bridge.mjs --port 8788 --dsh 127.0.0.1:64005',
    '  node host/dsbot-bridge.mjs --reset',
    '',
    '安全模型：默认拒绝，只反代方法白名单；错误 token 限速；审计 JSONL；手机拿不到 DSH cookie。',
  ].join('\n');
}

async function main() {
  let options;
  try {
    options = parseArgs(process.argv.slice(2));
  } catch (error) {
    console.error(`参数错误：${error.message}`);
    process.exitCode = 1;
    return;
  }
  if (options.help) {
    console.log(formatHelp());
    return;
  }
  if (options.version) {
    console.log(VERSION);
    return;
  }
  let bridge;
  try {
    bridge = createBridge(options);
  } catch (error) {
    console.error(`启动失败：${error.message}`);
    process.exitCode = 1;
    return;
  }
  try {
    await bridge.start();
  } catch (error) {
    console.error(`启动失败：${error.message}`);
    process.exitCode = 1;
    return;
  }
  console.log(formatStartupReport(bridge, options));
  const shutdown = async (signal) => {
    console.log(`\n收到 ${signal}，正在关闭桥接…`);
    try {
      await bridge.stop();
    } catch {
      /* ignore */
    }
    console.log('桥接已关闭。');
    process.exit(0);
  };
  process.on('SIGINT', () => void shutdown('SIGINT'));
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGBREAK', () => void shutdown('SIGBREAK'));
  process.on('uncaughtException', (error) => {
    console.error('未捕获异常（桥接继续运行）：', error);
  });
  process.on('unhandledRejection', (reason) => {
    console.error('未处理的 Promise 拒绝（桥接继续运行）：', reason);
  });
}

function bridgeAuthLimiter(bridge) {
  const entry = bridgeLimiters.get(bridge);
  if (!entry) throw new Error('限流器未初始化');
  return entry.authFail;
}
function bridgeRequestLimiter(bridge) {
  const entry = bridgeLimiters.get(bridge);
  if (!entry) throw new Error('限流器未初始化');
  return entry.request;
}

// 只在被直接运行时启动 CLI，import 时保持纯库语义（测试会 import）。
const invokedDirectly =
  typeof process.argv[1] === 'string' && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (invokedDirectly) {
  void main();
}
