# DS Bot 安全设计（必须遵守）

> 背景：参考项目（DS-Bot-App）被安全评审指出三类风险。DS Bot 从第一版起就把它们当作
> **架构约束**，而不是事后补丁。实现代码必须与本文一致，评审时逐条对照。

## 0. 威胁模型

- 手机可能丢失 / 被他人物理接触；局域网内可能有其他设备嗅探或重放。
- 电脑上的 Bot（LLM）可能被提示注入劫持，从而尝试通过手机端界面诱导用户批准危险操作。
- 手机 App 只应是一个**受权限约束的聊天终端**，不是电脑的远程管理员。

## 1. 审批（Approval）完整性 —— 对应风险①：一键 Allow 可能执行隐藏命令

风险原样：审批框只显示 Bot 自己写的名称/描述，真实命令藏在展开项里；审批按名称匹配而不绑指纹；
Bot 可在用户看完后、点击前替换命令 ⇒「点 Allow = 主机任意命令执行」。

DS Bot 的约束：

1. **完整展示**：审批卡必须展示 `toolName` + **完整 arguments JSON**（不截断，可滚动/展开），
   以及 `callId`。不得只显示 Bot 写的摘要或 `displayReason`。
2. **指纹绑定**：审批卡显示 `fingerprint = base64url(sha256(toolName + "\n" + callId + "\n" + arguments))`
   的前 16 位；用户点「批准一次」时，App 只提交 `eventId`（Host 侧以 eventId 为准，
   eventId 与那次请求一一对应），并在提交前重新比对当前请求的 fingerprint 与展示时是否一致；
   不一致（请求被替换）→ 卡片变红、禁止批准、提示刷新。
3. **请求失效**：收到 `cancel` 事件（用户切后台、断线、其他端已处理）后卡片立即变为不可操作。
4. **不做默认批准 / 自动批准 / 记住选择**：App 端只有「批准一次」「拒绝」两个动作，
   没有「始终允许」；命令面板与 MCP 配置中不存在自动批准开关。
5. **危险操作额外确认**：`toolName ∈ {pwsh, bash, shell, write, edit, computer, browser}` 或
   arguments 命中危险模式（`rm -rf`、`format`、`danger-full-access`、`sudo`、`curl | sh`、写注册表、
   访问 `settings/*`/`credentials/*`）时，按钮文案变为「确认执行危险命令」，并二次确认弹窗
   重述完整命令；默认焦点在「拒绝」。
6. 审批回传：`POST /api/$events/result`，`outcome: {"kind":"allowed-once"}` 或 `{"kind":"rejected"}`；
   绝不发送 `{"kind":"next"}`（那会把请求漏给其他 listener）。

## 2. 配对 ≠ 管理员 —— 对应风险②：配对手机 = 主机完整权限

风险原样：手机能装任意 npm/git 包、改设置、写密钥、改权限预设，refresh token 30 天；
丢手机或 token 泄露 ⇒ 主机任意代码执行。

DS Bot 的约束：

1. **权限在桥接（host bridge）收口**：手机只能访问桥接，桥接对 DSH API 做**方法白名单**
   （见 `host/dsbot-bridge.mjs` 与 PROTOCOL.md §2）：
   - 允许：会话读写、模型目录、审批回传、只读文件浏览；
   - **禁止**：`settings/*`、`credentials/*`、`pluginInventory/*`、`agentPresets/*`、
     `dynamicCordisRunner/*`、`goals/*`、`subagents/*`、`fileUploads/*`、`commands/execute`、
     workspace 写操作。⇒ 手机**不可能**安装插件/改配置/写密钥/改权限预设。
2. **配对是显式的一次性动作**：桥接启动时生成 32 字节设备 token；用户在电脑终端看到完整
   配对串 `dsbot://pair?url=...&token=...` 后手动交给手机（复制/稍后支持扫码）。
   `--reset` 立即撤销全部已配对设备（旧 token 立刻失效）。
3. **token 只存一处**：手机端把 token 用 **Android Keystore 加密**（AES/GCM，密钥不可导出）
   后再落盘；不上传、不打日志、不出现在截图演示数据里。
4. **无 refresh token 链**：设备 token 就是唯一凭据，可撤销、可轮换；没有 30 天自动续期的隐藏凭据。
5. **审计**：桥接把每次请求（方法、允许/拒绝、状态码、耗时、来源 IP）写入
   `<state-dir>/audit.log`（JSONL，绝不含 token/cookie）。用户可随时查看手机做过什么。
6. **最小暴露**：默认只监听局域网；HTTP 明文仅限可信局域网（如需公网必须自行加 TLS/VPN，
   README 中明确警告）；响应剥离 `Set-Cookie`，手机永远拿不到 DSH 的浏览器 cookie。
7. App 内「设置 → 安全」页展示：桥接地址、设备 token 指纹（sha256 前 8 位）、最近审计条数、
   「撤销本机授权」的操作说明（在电脑端执行 `--reset`）。

## 3. 密钥与 MCP —— 对应风险③：HTTP MCP 自动批准 + 密钥跨 bot 泄露

风险原样：MCP URL 可为任意地址（含内网）；`$DSH_SECRET_X` 使用被挂载 Bot 的权限而非创建者；
被劫持的 Main Bot 可静默把别的 Bot 的密钥发到外部服务器。

DS Bot 的约束：

1. App **不提供** MCP 服务器增删改、不提供「自动批准 MCP」开关；此类配置只能在电脑端 DSH 里做。
2. 桥接**禁止**转发任何形如 `mcp/*`、`connectors/*`、`credentials/*`、`settings/*` 的方法，
   并禁止转发 `fileUploads/*`（上传通道可能被用来外传大文件）。
3. App 渲染外部链接/MCP 调用时，一律当**不可信内容**：只显示为纯文本 + 「在电脑上确认」提示，
   不做自动打开、不做一键信任。URL 若非 `http(s)` 或指向私网地址，标记为风险项。
4. App 不读取、不缓存任何 `$DSH_SECRET_*`；桥接日志与错误信息同样不包含密钥。
5. 群聊中 Bot 的 @ 接力只发生在 DSH 会话之间，提示词里**不注入**任何凭据或环境变量。

## 4. 实现检查清单（Review 用）

- [ ] App 无「始终允许/自动批准」入口；审批卡展示完整命令 + 指纹；cancel 后不可操作。
- [ ] 桥接白名单默认拒绝；对 `settings/*`、`credentials/*`、`pluginInventory/*`、`agentPresets/*`、
      `dynamicCordisRunner/*`、`fileUploads/*`、`commands/execute`、workspace 写操作返回 403 并审计。
- [ ] 桥接鉴权 constant-time；错误 token 限速；`--reset` 立即失效旧 token。
- [ ] 手机端 token 存 Keystore 加密；日志/崩溃信息不含 token。
- [ ] 桥接审计 JSONL 可查；无 token/cookie 泄露。
- [ ] 无自动打开外部链接；MCP/外部 URL 只读展示 + 风险标记。
- [ ] README 明确：局域网内 HTTP 明文、公网必须 TLS/VPN、`--reset` 撤销方法。
