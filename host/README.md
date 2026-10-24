# DS Bot 主机桥接（dsbot-bridge）

手机端 DS Bot App ⇄ **本桥接**（设备 token）⇄ 本机 DeepSeek Harness（DSH）Web。

桥接是这套架构里**唯一的安全边界**：手机不直连 DSH、永远拿不到 DSH 的浏览器 cookie，
所有请求都要经过方法白名单、设备 token、限流和审计。

```
┌──────────────┐   HTTP + WS（Bearer token）    ┌──────────────────┐   注入 DSH cookie    ┌──────────────────┐
│ DS Bot App   │ ─────────────────────────────▶ │ dsbot-bridge     │ ───────────────────▶ │ DSH Web          │
│ (Android)    │   /api/* + /api/remote.mux      │ (Node, 零依赖)    │  127.0.0.1:<port>     │ (用户已登录的)     │
└──────────────┘                                 └──────────────────┘                       └──────────────────┘
```

## 1. 安装与启动

要求：已安装 DSH 桌面版（默认 Web 地址 `127.0.0.1:64005`），Node.js ≥ 20
（DSH 自带运行时可直接用，无需另装）。

```powershell
# 使用 DSH 自带的 Node（Windows 示例）
$node = 'E:\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
& $node host\dsbot-bridge.mjs --port 8788 --dsh 127.0.0.1:64005
```

启动后会打印（示例，cookie 名已打码、token 已省略）：

```
============================================================
  DS Bot 主机桥接网关 v0.1.0
  手机 ⇄ 本桥接（设备 token）⇄ 本机 DSH Web（浏览器 cookie）
============================================================
上游 DSH 地址 : http://127.0.0.1:64005  (cookie: 已签名 dsh-auth-<8位hash>…)
桥接监听      : 0.0.0.0:8788（所有网卡）
状态目录      : %USERPROFILE%\.dsbot
审计日志      : %USERPROFILE%\.dsbot\audit.log
设备 token 指纹: xxxxxxxx（sha256 前 8 位；App「设置 → 安全」核对用）

本机局域网 IPv4 地址：
  - 192.168.3.36  (WLAN)

一次性配对串（把完整一行交给手机 App；建议复制，不要截图）：
  dsbot://pair?url=http://192.168.3.36:8788&token=<43 字符>
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `--port <n>` | 监听端口，默认 8788 |
| `--dsh <host:port>` | 上游 DSH Web，默认 `127.0.0.1:64005` |
| `--bind <addr>` | 监听地址，默认 `0.0.0.0`；只想本机调试用 `127.0.0.1` |
| `--state-dir <dir>` | 状态/审计目录，默认 `%USERPROFILE%\.dsbot` |
| `--reset` | 重新生成设备 token（旧 token 立即失效），重新打印配对串 |

环境变量 `DSBOT_DSH_SECRET` 可显式指定 DSH 浏览器会话密钥（默认从
`%USERPROFILE%\.dsh\.credentials.yaml` 读取）。

## 2. 手机配对

1. 手机与电脑连**同一个可信局域网**。
2. 把终端里的整条 `dsbot://pair?url=...&token=...` 复制到手机（或点开链接）。
3. App 里粘贴配对串（也可手填地址 + token）→ 连接成功进入 Bot 团队。

设备 token 用 Android Keystore 加密保存在手机上，之后自动重连。

## 3. App 使用的协议（摘要）

完整说明见仓库 `docs/PROTOCOL.md`。

- 一元 RPC：`POST /api/<namespace>/<method>`，`Authorization: Bearer <token>`，
  body 为 `{"type":"client-request","rpcId","method","payload":{"args":{...}}}`。
- 流式：`GET /api/remote.mux`（WebSocket 升级，同样带 `Authorization`），
  客户端 `open/cancel` 逻辑流，服务端 `item/end/error` 帧。
- 审批回传：`POST /api/$events/result`，`{clientId,eventId,outcome:{kind:"allowed-once"|"rejected"}}`。
- `/healthz`（需 token）：返回 `{ok:true,version,upstream,tokenFingerprint}`。

## 4. 安全模型

1. **配对 ≠ 管理员**。手机只能访问：
   - 允许：`session/list|create|prompt|cancel|rename|page|modelCatalog|selectModel|follow|control`、
     `$events`、`$events/result`、`llm/listProviders`、`skills/list`、`fileReferences/list`、
     `workspace/follow`、`workspaceFiles/list|read|readAll|readBytes|stat`；
   - 一律 403 + 审计：`settings/*`、`credentials/*`、`pluginInventory/*`、`agentPresets/*`、
     `dynamicCordisRunner/*`、`goals/*`、`subagents/*`、`fileUploads/*`、`commands/execute`、
     workspace 写操作，以及所有未知方法与非 `/api/` 路径（`/healthz` 除外）。
2. **WS 流端点白名单**。DSH 的 `/api/remote.mux` 复用所有流式方法，纯 TCP 透传会让手机
   打开 `terminal/attach`（交互终端 ≈ 主机 RCE）、`job/follow`、`account/watch` 等。
   桥接因此解析客户端→上游方向的 `{"type":"open","endpoint":...}` 帧，只允许
   `session/follow`、`session/control`、`$events`、`workspace/follow`，违规回 `Close 1008` 并记审计 deny。
3. **凭据不外泄**。DSH 的 `dsh-auth-*` cookie 只由桥接在内存中签发/注入，响应剥离 `Set-Cookie`，
   启动输出里的 cookie 名也打码；手机只持有设备 token。
4. **鉴权与限流**。设备 token 32 字节随机、constant-time 比较；错误 token 5 次/分钟以上 → 429；
   每 token 120 请求/10 秒窗口。
5. **审计**。`<状态目录>/audit.log` 为 JSONL，逐条记录 `ts / method / decision(allow|deny) /
   status / ms / bytes / remoteAddr`，**不含 token 与 cookie**。
6. **撤销**。手机丢失或 token 泄露：`--reset` 立即作废旧 token；建议同时停止桥接进程。

> 桥接与手机之间是 **HTTP 明文**（DSH 本身也不带 TLS）：仅限可信局域网。
> 公网/跨网访问请自建 TLS 反向代理或 VPN，**不要**把端口直接暴露到公网。

## 5. 验证

```powershell
$node = 'E:\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'

# 单元/集成测试（44 项；沙箱环境需关 isolation 才能 fork）
& $node --test --test-isolation=none host/test/*.test.mjs

# 对真实 DSH 的只读冒烟（桥接需先启动；7 项）
& $node host\smoke.mjs --bridge http://127.0.0.1:18788 --state-dir <状态目录>
```

冒烟覆盖：`/healthz` 200、`session/list` 200 且 `result.ok:true`（证明 cookie 被真上游接受）、
`settings/describe`/`credentials/list` 403、无 token 401、WS 101 + `session/follow` 首帧、
WS `terminal/attach` 被 Close 1008。

App 协议级端到端（建会话 → prompt → 流式回复，走真实模型）：

```powershell
& $node scripts\e2e-app-protocol.mjs --bridge http://127.0.0.1:8788 --token <token>
# 期望最后一行：E2E_PASSED
```

## 6. 故障排查

| 现象 | 处理 |
| --- | --- |
| 启动报「无法读取 DSH 凭据」 | 确认 DSH 桌面版已登录并存在 `%USERPROFILE%\.dsh\.credentials.yaml`；或用 `DSBOT_DSH_SECRET` 指定 |
| `/healthz` 200 但 `session/list` 401 | DSH 地址/端口不对，或 DSH 会话密钥已轮换（重启 DSH 后桥接会自动重读） |
| 手机连不上 | 检查同网段、防火墙放行端口、`--bind 0.0.0.0`；先用 `curl /healthz` 从另一台机器验证 |
| 手机报 403 | 该接口被安全白名单拒绝（预期行为）；换用 App 提供的功能 |
| 手机报 401 | token 不匹配：`--reset` 重新生成配对串 |
| WS 一直断 | 检查是否有代理/防火墙拦截 WebSocket；桥接日志会记录 upgrade 失败 |
| 想换电脑 | 在新电脑启动桥接 + 新的 `--state-dir`，手机重新配对 |

## 7. 已知限制

- 明文 HTTP，仅可信局域网；无 TLS 终止。
- 单设备共享一个 token（v0.1.0）；`--reset` 是**全部撤销**。
- token 无自动过期；`bridge.json` 内含 token，依赖用户目录权限（Windows 上 0600 仅尽力）。
- 审计日志无轮转、无防篡改（本地自查级）。
- 与 DSH 0.2.x 协议耦合：DSH 升级后请重跑 `host/smoke.mjs` 与单测，并按 `docs/PROTOCOL.md` §7 重新抓包核对。
- 默认 `0.0.0.0` 监听：请配合系统防火墙，且不要在公共 Wi-Fi 使用。

## 8. 文件

| 文件 | 用途 |
| --- | --- |
| `dsbot-bridge.mjs` | 主程序（零依赖）：CLI、HTTP 反代、WS mux 网关、token、白名单、审计 |
| `smoke.mjs` | 真实 DSH 只读冒烟（7 项） |
| `test/` | `node:test` 测试（44 项：auth/白名单/代理/WS/限流/状态/cookie/CLI/真上游只读） |
| `README.md` | 本文 |
