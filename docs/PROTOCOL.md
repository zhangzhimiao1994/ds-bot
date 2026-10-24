# DS Bot 线协议说明（手机 ⇄ 桥接 ⇄ DSH）

> 本文是 App 端 `data/DshGatewayImpl.kt` 与主机桥接 `host/dsbot-bridge.mjs` 的共同依据。
> 全部字段来自 **2026-10-24 对本机 DSH Web（127.0.0.1:64005）的实测抓包**，
> 原始样本在 `work/probe/`（`session-list.json`、`session-follow-frames.json`、
> `LIVE-SAMPLES.md`、`EVENT-SAMPLES.md`）。DSH 升级后请重跑 `scripts/probe-dsh.mjs`。

## 1. 拓扑与鉴权

```
Android App ──HTTP/WS + Authorization: Bearer <device token>──▶ dsbot-bridge (LAN:8788)
                                                                  │ 注入 DSH 浏览器 cookie
                                                                  ▼
                                                       DSH Web 127.0.0.1:64005
```

- 手机只持有**设备 token**（32B）；桥接用本机凭据签 `dsh-auth-*` cookie 注入上游，
  并在响应里剥掉 `Set-Cookie`。手机永远拿不到 DSH cookie。
- 桥接对方法做**白名单**（见 `docs/SECURITY.md` §2），拒绝 `settings/*`、`credentials/*`、
  `pluginInventory/*`、`agentPresets/*`、`dynamicCordisRunner/*`、`goals/*`、`subagents/*`、
  `fileUploads/*`、`commands/execute` 与 workspace 写操作。
- 错误码（HTTP 200 之外的统一体）：`bridge/unauthorized`(401)、`bridge/forbidden`(403)、
  `bridge/rate-limited`(429)。

## 2. HTTP 一元 RPC

```
POST http://<bridge>/api/<namespace>/<method>
Authorization: Bearer <token>
Content-Type: application/json

{"type":"client-request","rpcId":"<uuid>","method":"<namespace>/<method>","payload":{"args":{...}}}
```

成功：`{"type":"server-response","rpcId":"...","result":{"ok":true,"value":{...}}}`
失败：`{"type":"server-response","rpcId":"...","result":{"ok":false,"error":{"code":"...","message":"..."}}}`

`args` 规则（实测，必须严格遵守）：

| endpoint | args |
| --- | --- |
| `session/list` | `{"_request":{}}` |
| `session/modelCatalog` | `{}` |
| `llm/listProviders` | `{}` |
| `session/create` | `{"request":{"cwd"?:string,"workspaceId"?:string}}`（二选一） |
| `session/rename` | `{"request":{"sessionId","title"}}` |
| `session/selectModel` | `{"request":{"sessionId","provider","model","reasoningEffort"?}}` |
| `session/prompt` | `{"request":{"requestId","sessionId","mode":"queue"\|"steer","content":[{"type":"text","text"}]}}` |
| `session/cancel` | `{"request":{"sessionId"}}` |
| `session/page` | `{"request":{"address":{"kind":"session","sessionId"},"throughSeq","beforeSeq"?,"maxMessages"}}` |
| `$events/result` | `{"clientId","eventId","outcome":{"kind":"allowed-once"\|"rejected"}}` |

`session/list` 返回 `{"items":[{sessionId,updatedAt,running,blank,cwd,parentSessionId?,projections:{values:{title,...}}}]}`。

## 3. WebSocket 多路复用

```
GET ws://<bridge>/api/remote.mux
Authorization: Bearer <token>      # 升级请求也要带（桥接会同时接受 ?token=）
```

客户端帧：

```json
{"type":"open","streamId":"follow-1","endpoint":"session/follow","payload":{"args":{...}}}
{"type":"cancel","streamId":"follow-1"}
```

服务端帧：

```json
{"type":"item","streamId":"follow-1","value":{...}}      // 数据帧
{"type":"end","streamId":"follow-1"}                     // 流结束
{"type":"error","streamId":"follow-1","error":{"code","message"}}  // 流失败
```

一次连接可开多个流；App 固定开两条：`$events`（全局审批）与当前会话的 `session/follow`。

## 4. `session/follow`

open args：

```json
{"request":{"address":{"kind":"session","sessionId":"session-..."},"maxMessages":20,"assistantStream":true}}
```

`value` 依次可能是：

### 4.1 `snapshot`（打开时一次）

```json
{"type":"snapshot",
 "header":{"version":4,"id":"session-...","createdAt":1792818917426,"cwd":"E:\\work","agentPreset":"standard"},
 "cursor":637,
 "records":[{"type":"event","event":{"type":"assistant/message","seq":477,"time":1792835196781,"data":{...}}}],
 "hasMore":true,
 "projections":{"values":{"title":"...","modelSelection":{...},"todos":null,...}},
 "assistantStream":{"revision":97122}}
```

- `cursor` 是本会话最新 seq；历史翻页用 `session/page`（`throughSeq=cursor`）。
- `assistantStream.revision` 是流式帧的起始基线：后续 `assistant-stream.frame.revision`
  **严格递增**；跳号/回退必须整体重开 follow（本 App 用 block-end 权威文本兜底，见 §5）。

### 4.2 `event`（持久化事件）

```json
{"type":"event","event":{"type":"tool/call","seq":557,"time":1792835367378,"data":{...}}}
```

常见事件类型（实测 19 种，App 只用其中这些）：

| type | 用途 | data 关键字段 |
| --- | --- | --- |
| `user/message` | 用户消息 | `content:[{type:"text",text}]`, `role` |
| `assistant/message` | 最终回复（权威） | `message:{role,content:[reasoning/text/tool-call]}` |
| `tool/call` | 工具开始 | `callId`,`name`,`arguments`(JSON 字符串) |
| `tool/result` | 工具结束 | `message:{toolCallId,isError,content:[{type:"text",text}]}` |
| `turn/start` / `turn/end` | 回合边界 | `turn`；`turn/end` 带 `reason.kind` |
| `approval/asked` / `approval/decided` | 审批历史 | `id`,`toolName`,`callId`,`reason` / `outcome` |
| `session/title` | 标题变更 | `title` |
| `todo/write` | 待办 | `todos` |
| `step/start` / `step/end` | 步骤 | `step` |

### 4.3 `assistant-stream`（实时增量）

```json
{"type":"assistant-stream","frame":{"type":"start","attemptId":"session-...:65","revision":107850,"index":10}}
{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"...","revision":107856,"index":14,
  "chunk":{"type":"reasoning-delta","index":0,"text":" s"}}}
{"type":"assistant-stream","frame":{"type":"chunk","revision":107875,"index":33,
  "chunk":{"type":"block-end","index":0,"block":{"type":"reasoning","text":"完整思考文本"}}}}
{"type":"assistant-stream","frame":{"type":"chunk","revision":107877,"index":35,
  "chunk":{"type":"tool-call-delta","index":1,"id":"call_...","name":"pwsh","argumentsDelta":"..."}}}
{"type":"assistant-stream","frame":{"type":"chunk","revision":108005,"index":163,
  "chunk":{"type":"finish","reason":{"kind":"tool-calls"}}}}
{"type":"assistant-stream","frame":{"type":"end","revision":108006,"index":164,
  "outcome":{"kind":"committed","eventType":"assistant/message","seq":638}}}
```

- `chunk.type`：`block-start` / `text-delta` / `reasoning-delta` / `tool-call-delta` /
  `usage` / `block-end` / `finish`。
- App 折叠规则（`data/SessionFold.kt`）：delta 追加；`block-end` 用权威文本覆盖当前块；
  `assistant/message` 用最终 message 覆盖整轮；`turn/end`/`frame.type=end` 结束本轮。

## 5. `$events`（审批与全局事件）

open args：`{"args":{}}`（必须空对象，否则 `gateway/arguments-invalid`）。

```json
// 打开后第一帧：给出 clientId 与主机 home
{"type":"item","streamId":"events","value":{"type":"ready",
  "clientId":"9316b806-...","host":{"home":"C:\\Users\\me"}}}

// 审批请求（需要用户动作）
{"type":"item","streamId":"events","value":{"type":"waterfall","event":"approval/request",
  "eventId":"41d4a1db-...","agentId":"session-...",
  "request":{"toolName":"pwsh","callId":"call_...","reason":"...",
             "displayReason":{"en":"...","zh":"..."}}}}

// 请求失效（用户切后台、其他端已处理、断线等）
{"type":"item","streamId":"events","value":{"type":"cancel","eventId":"41d4a1db-..."}}

// 会话列表类通知（可触发 refreshSessions）
{"type":"item","streamId":"events","value":{"type":"emit","event":"api-session/added","args":[...]}}
```

**安全约束（docs/SECURITY.md §1）**：App 审批卡必须展示 `toolName` + 完整 `arguments` +
`callId` + 指纹（`sha256(toolName\ncallId\narguments)` base64url 前 16 位），只提供
「批准一次 / 拒绝」，收到 `cancel` 立即失效；禁止自动批准或 `{"kind":"next"}`。
注意 `waterfall.request` 里**没有** `arguments` 字段（上游只给 toolName/callId/reason），
完整命令由 App 通过 `session/page` 或本地事件流中对应 `tool/call` 的 `arguments` 关联展示；
取不到时 UI 必须明确提示「无法展示完整参数，禁止批准」。

## 6. 会话操作语义

- `session/create` 不会自动开始 turn；创建后立即 `rename` + 可选 `selectModel` + `prompt`。
- `prompt` 的 `requestId` 是幂等键；成功的 `user/message` 事件以 `source.rpcId == requestId` 出现。
- `session/not-found`：会话已被删除/归档，App 重新 `create` 并把新 sessionId 落盘后重试一次。
- `turn/end.reason.kind`：`completed` / `aborted` / `error`；aborted 时 UI 标「已中断」。
- 模型目录：`session/modelCatalog` 返回 `{default, groups:[{id,name,models:[{id,name,reasoning:{efforts}}]}]}`，
  默认 `deepseek-official/deepseek-flash`，推理强度 `off/low/high/max`。

## 7. 复现与升级检查

```powershell
# 只读抓包（不需要完整权限；本机 loopback）
$node = 'E:\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
& $node scripts\probe-dsh.mjs          # unary + follow 快照 + 事件
& $node scripts\probe-live.mjs 60      # 长时抓 assistant-stream / $events
& $node scripts\enumerate-events.mjs   # 事件类型样本
& $node scripts\extract-live.mjs       # 流式帧样本
```

DSH 升级后若 `gateway/arguments-invalid`，先核对 §2 的 args 规则；若事件字段变更，
以 `work/probe/EVENT-SAMPLES.md` 与 `LIVE-SAMPLES.md` 对比修正 `DshGatewayImpl` / `SessionFold`。
