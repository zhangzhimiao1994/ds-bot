import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';

import { formatHelp, formatStartupReport, parseArgs, parseAuthority } from '../dsbot-bridge.mjs';
import { httpRequest, startFakeUpstream, startTestBridge, wsHandshake } from './helpers.mjs';

test('CLI 参数解析：默认值与显式参数', () => {
  const defaults = parseArgs([]);
  assert.equal(defaults.port, 8788);
  assert.equal(defaults.dsh, '127.0.0.1:64005');
  assert.equal(defaults.bind, '0.0.0.0');
  assert.equal(defaults.reset, false);
  assert.equal(defaults.help, false);
  assert.ok(defaults.stateDir.endsWith('.dsbot'));

  const custom = parseArgs([
    '--port', '18788',
    '--dsh=127.0.0.1:64006',
    '--bind', '192.168.1.10',
    '--state-dir', 'E:\\tmp\\dsbot-state',
    '--reset',
  ]);
  assert.equal(custom.port, 18788);
  assert.equal(custom.dsh, '127.0.0.1:64006');
  assert.equal(custom.bind, '192.168.1.10');
  assert.equal(custom.stateDir, path.resolve('E:\\tmp\\dsbot-state'));
  assert.equal(custom.reset, true);

  assert.throws(() => parseArgs(['--port', 'abc']), /--port/);
  assert.throws(() => parseArgs(['--port', '0']), /--port/);
  assert.throws(() => parseArgs(['--port', '70000']), /--port/);
  assert.throws(() => parseArgs(['--dsh']), /缺少值/);
  assert.throws(() => parseArgs(['--unknown']), /未知参数/);
});

test('CLI 帮助信息包含端口/上游/重置/安全说明', () => {
  const help = formatHelp();
  for (const keyword of ['--port', '--dsh', '--state-dir', '--reset', '默认拒绝', '审计']) {
    assert.ok(help.includes(keyword), `帮助应包含 ${keyword}`);
  }
});

test('启动输出：中文、LAN 地址、完整配对串、安全提示', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  t.after(async () => {
    await env.stop();
    await upstream.close();
  });

  const report = formatStartupReport(env.bridge, { port: env.bridge.port, bind: '0.0.0.0' });
  assert.ok(report.includes('DS Bot 主机桥接网关'));
  assert.ok(report.includes('本机局域网 IPv4 地址'));
  assert.ok(report.includes(`dsbot://pair?url=http://`), '必须给出配对串');
  assert.ok(report.includes(`&token=${env.token}`), '配对串必须带完整 token');
  assert.ok(report.includes('安全提示'));
  assert.ok(report.includes('--reset'), '安全提示必须说明如何撤销设备');
  assert.ok(report.includes('配对手机 ≠ 主机管理员'));
  assert.ok(report.includes(env.bridge.auditPath), '必须显示审计日志路径');
  // 启动输出可能被截图：cookie 只能出现「已签名 + 8 位 hash 前缀」，不能出现完整 cookie 名/值。
  assert.ok(!report.includes(env.bridge.cookieName), '启动输出不能包含完整 cookie 名');
  assert.match(report, /cookie: 已签名 dsh-auth-[A-Za-z0-9_-]{8}…/, 'cookie 应打码为 8 位 hash 前缀');
  assert.ok(!report.includes(env.bridge.currentCookie()), '启动输出不能包含 cookie 值');
  // 配对串就是 App 深链解析的格式：scheme=dsbot, host=pair, url/token 查询参数。
  const pairingLine = report.split('\n').find((line) => line.trim().startsWith('dsbot://pair?'));
  const pairingUrl = new URL(pairingLine.trim());
  assert.equal(pairingUrl.protocol, 'dsbot:');
  assert.equal(pairingUrl.host, 'pair');
  assert.match(pairingUrl.searchParams.get('url'), /^http:\/\/.+:\d+$/);
  assert.equal(pairingUrl.searchParams.get('token'), env.token);
});

test('parseAuthority 拒绝非法地址', () => {
  assert.deepEqual(parseAuthority('127.0.0.1:8788'), { authority: '127.0.0.1:8788', host: '127.0.0.1', port: 8788, origin: 'http://127.0.0.1:8788' });
  assert.equal(parseAuthority('localhost:64005').host, '127.0.0.1');
  assert.equal(parseAuthority('[::1]:64005').host, '::1');
  assert.throws(() => parseAuthority(''), /不能为空/);
  assert.throws(() => parseAuthority('host:abc'), /端口无效/);
});

test('优雅停止：stop() 可重复调用、停止后端口不再接受连接、长连接不会让 stop 挂住', async (t) => {
  const upstream = await startFakeUpstream();
  const env = await startTestBridge({ dsh: upstream.authority });
  const port = env.port;
  const token = env.token;

  // 打开一条 WS 长连接，模拟手机持有流式通道。
  const ws = await wsHandshake(port, '/api/remote.mux', { token });
  assert.equal(ws.status, 101);

  const stopped = await Promise.race([
    env.stop().then(() => 'stopped'),
    new Promise((resolve) => {
      const timer = setTimeout(() => resolve('timeout'), 3000);
      timer.unref?.();
    }),
  ]);
  assert.equal(stopped, 'stopped', 'stop() 不应被长连接挂住');
  ws.socket?.destroy();

  await env.stop(); // 幂等

  await assert.rejects(httpRequest(`http://127.0.0.1:${port}`, { method: 'GET', path: '/healthz', token }), /ECONNREFUSED/);
  await upstream.close();
});
