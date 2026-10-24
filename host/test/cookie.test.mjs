import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

import {
  base64url,
  computeCookieName,
  loadCookieSecret,
  parseAuthority,
  signCookie,
  tokenFingerprint,
  tokensMatch,
} from '../dsbot-bridge.mjs';
import { makeTempDir, writeCredentials } from './helpers.mjs';

// 真实 DSH 的 cookie 名 = "dsh-auth-" + base64url(sha256(authority))。
// 这里用公式现算，避免把某台机器的真实 cookie 名写进仓库；断言仍然验证
// “桥接实现 == 官方算法” 这一关键事实。
const REAL_AUTHORITY = '127.0.0.1:64005';
const REAL_COOKIE_NAME = 'dsh-auth-' + crypto.createHash('sha256').update(REAL_AUTHORITY).digest('base64url');

test('cookie 名与真实 DSH 抓包一致（sha256(authority) 规则）', () => {
  assert.equal(computeCookieName(REAL_AUTHORITY), REAL_COOKIE_NAME);
  assert.equal(parseAuthority('127.0.0.1:64005').authority, REAL_AUTHORITY);
  assert.equal(parseAuthority('localhost:64005').authority, REAL_AUTHORITY, 'localhost 需归一化为 127.0.0.1');
  // 名字由 authority 唯一决定：换端口就换 cookie 名。
  assert.notEqual(computeCookieName('127.0.0.1:64006'), REAL_COOKIE_NAME);
});

test('cookie 签名：v1 + base64url(payload) + HMAC-SHA256 结构与 7 天有效期', () => {
  const secret = crypto.randomBytes(32);
  const issuedAt = 1_800_000_000_000;
  const value = signCookie(secret, REAL_AUTHORITY, issuedAt);

  const [prefix, body, signature] = value.split('.');
  assert.equal(prefix, 'v1');
  assert.equal(signature, crypto.createHmac('sha256', secret).update(body).digest('base64url'));

  const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
  assert.deepEqual(Object.keys(payload).sort(), ['authority', 'expiresAt', 'issuedAt', 'version']);
  assert.equal(payload.version, 1);
  assert.equal(payload.authority, REAL_AUTHORITY);
  assert.equal(payload.issuedAt, issuedAt);
  assert.equal(payload.expiresAt - payload.issuedAt, 7 * 24 * 60 * 60 * 1000);

  // 篡改 payload 后签名必须失效。
  const tamperedBody = base64url(JSON.stringify({ ...payload, expiresAt: payload.expiresAt + 1 }));
  assert.notEqual(crypto.createHmac('sha256', secret).update(tamperedBody).digest('base64url'), signature);
});

test('loadCookieSecret：解析 credentials.yaml 里的 32 字节 secret', () => {
  const dir = makeTempDir('creds');
  const secret = crypto.randomBytes(32);
  const credentials = writeCredentials(dir, secret);
  const loaded = loadCookieSecret(credentials.path);
  assert.equal(loaded.length, 32);
  assert.deepEqual(loaded, secret);
});

test('loadCookieSecret：secret 非法长度时报错，不会静默用错密钥', () => {
  const dir = makeTempDir('creds-bad');
  const file = path.join(dir, 'credentials.yaml');
  fs.writeFileSync(
    file,
    ['version: 1', 'records:', '  client-connection/browser-session:', '    payload:', `      secret: ${base64url('too-short')}`].join('\n'),
    'utf8',
  );
  assert.throws(() => loadCookieSecret(file), /32 字节/);
});

test('loadCookieSecret：文件缺失时给出可操作的中文错误', () => {
  assert.throws(() => loadCookieSecret(path.join(makeTempDir('creds-missing'), 'nope.yaml')), /无法读取 DSH 凭据文件/);
});

test('loadCookieSecret：DSBOT_DSH_SECRET 环境变量可覆盖', () => {
  const secret = crypto.randomBytes(32);
  const previous = process.env.DSBOT_DSH_SECRET;
  process.env.DSBOT_DSH_SECRET = base64url(secret);
  try {
    const loaded = loadCookieSecret(path.join(makeTempDir('creds-env'), 'missing.yaml'));
    assert.deepEqual(loaded, secret);
  } finally {
    if (previous === undefined) delete process.env.DSBOT_DSH_SECRET;
    else process.env.DSBOT_DSH_SECRET = previous;
  }
});

test('tokensMatch 为常量时间比较且类型安全', () => {
  const token = crypto.randomBytes(32).toString('base64url');
  assert.equal(tokensMatch(token, token), true);
  assert.equal(tokensMatch(token, token + 'x'), false);
  assert.equal(tokensMatch(token.slice(0, 42), token), false);
  assert.equal(tokensMatch(undefined, token), false);
  assert.equal(tokensMatch(null, token), false);
  assert.equal(tokensMatch(123, token), false);
});

test('tokenFingerprint 只暴露 sha256 前 8 位', () => {
  const token = crypto.randomBytes(32).toString('base64url');
  const fingerprint = tokenFingerprint(token);
  assert.match(fingerprint, /^[0-9a-f]{8}$/);
  assert.equal(fingerprint, crypto.createHash('sha256').update(token).digest('hex').slice(0, 8));
});
