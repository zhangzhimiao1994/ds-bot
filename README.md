# DS Bot — 手机上的 DSH Bot 团队

> 参考 [DS-Bot-App](https://github.com/QingJ01/DS-Bot-App) 的产品思路，用 **Kotlin + Compose 原生 Android**
> 重写的 DeepSeek Harness 手机客户端：把电脑上的一组 Bot 装进手机，1:1 聊天、拉群、主 Bot 调度、
> Bot 之间用 `@名字` 接力完成任务。
>
> 从零重建，安全优先：**配对手机 ≠ 主机管理员**；审批展示完整命令并绑定指纹；密钥不落手机。

## 它能做什么

| 功能 | 说明 |
| --- | --- |
| Bot 团队 | 创建/编辑 Bot：名字、emoji 头像、角色设定、模型、工作目录、是否主 Bot |
| 1:1 聊天 | 流式回复、思考过程折叠、工具调用卡片、停止/清空 |
| 群聊 | 多个 Bot 同群；管理员 Bot 先答并可 `@成员` 分工；被 @ 的成员接力（最多 2 跳、单次 ≤4 条） |
| 审批 | 内联审批卡：**完整命令 + callId + 指纹**，只有「批准一次 / 拒绝」，危险命令二次确认 |
| 离线可读 | Bot 名册、群组与聊天记录存在手机本地，断网仍可查看 |
| 安全页 | 设备 token 指纹、桥接审计说明、撤销授权方法、公网使用警告 |

## 架构（3 个部分）

```
┌────────────────────┐   HTTP + WS（Bearer 设备 token）   ┌──────────────────┐   注入 DSH cookie   ┌────────────────┐
│ DS Bot Android App │ ─────────────────────────────────▶ │ dsbot-bridge     │ ──────────────────▶ │ DSH Web        │
│ Kotlin + Compose   │   /api/<endpoint> + /api/remote.mux │ (电脑, Node 零依赖)│   127.0.0.1:64005   │ (用户已登录的) │
└────────────────────┘                                     └──────────────────┘                     └────────────────┘
```

- **App**：`core/`（模型与网关接口）、`data/`（HTTP/WS 协议、会话折叠、Keystore 存储、设置）、
  `bots/`（Bot 团队引擎：持久化 + 编排）、`ui/`（Compose 屏幕，视觉与 DSH 桌面版一致）。
- **桥接**：手机唯一入口；**方法白名单**只放行会话/模型/审批/只读文件，拒绝
  `settings/*`、`credentials/*`、`pluginInventory/*`、`agentPresets/*`、`fileUploads/*`、
  `commands/execute` 与 workspace 写操作；带设备 token、限流、JSONL 审计、剥离 `Set-Cookie`。
- **DSH**：Bot 就是 DSH 里的真实会话，共享电脑的模型、工作区、工具与权限。

详细契约：[docs/PROTOCOL.md](docs/PROTOCOL.md) · 安全设计：[docs/SECURITY.md](docs/SECURITY.md) ·
UI 契约：[docs/UI-CONTRACT.md](docs/UI-CONTRACT.md) · 安装使用：[docs/INSTALL.md](docs/INSTALL.md)。

## 快速开始

```powershell
# 1) 一次性：安装构建工具链（JDK 21 / Android SDK / Gradle，约 1.5GB 下载）
powershell -ExecutionPolicy Bypass -File scripts\setup-toolchain.ps1

# 2) 构建（首次需要网络下载依赖；之后可 -Offline）
powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Variant all -Test

# 3) 电脑上启动桥接（自动打印配对串）
$node = 'E:\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
& $node host\dsbot-bridge.mjs --port 8788 --dsh 127.0.0.1:64005
# 终端会打印：dsbot://pair?url=http://192.168.x.x:8788&token=...
```

手机安装 `release/dsbot-0.1.0-release.apk`，把配对串粘贴进 App（或直接点系统剪贴板里的链接），
连接后即可创建 Bot、拉群聊天。**手机与电脑需在同一可信局域网。**

## 已验证（本机实测）

| 项 | 结果 |
| --- | --- |
| 构建 | `BUILD SUCCESSFUL`；`release/dsbot-0.1.0-{debug,release}.apk`，`apksigner verify` 通过（同一证书 SHA-256 `d62eaf4d…`） |
| App 单测 | `:app:testDebugUnitTest` **50 / 50 通过**（Bot 存储、提示词、@解析、1:1 流式折叠、群编排、审批透传…） |
| 桥接测试 | `host/test` **44 / 44 通过**；对真实 DSH 只读冒烟 **7 / 7 通过**（含 `settings/*` 403、WS `terminal/attach` 被拒） |
| 端到端 | `scripts/e2e-app-protocol.mjs` 对真实 DSH 走 App 同一协议：**9 / 9 通过**，建会话 → 发 prompt → 流式回复「你好」 |
| 模拟器截图 | 本构建沙箱无法启动 QEMU/WHPX（Chrome CDP 同样被拦），未产出真机截图；UI 按 DSH Web 设计令牌实现且全部 `@Preview` 编译通过 |

## 安全边界（摘要）

1. **审批不可伪造**：App 展示完整 `toolName / arguments / callId` 与指纹；`cancel` 后立即失效；
   没有「始终允许」，危险命令二次确认。详见 [docs/SECURITY.md §1](docs/SECURITY.md)。
2. **配对 ≠ 管理员**：桥接默认拒绝，手机无法改设置/写凭据/装插件/改权限预设；
   设备 token 存 Android Keystore，`--reset` 立即撤销。详见 [§2](docs/SECURITY.md)。
3. **密钥与 MCP**：App 不读任何 `$DSH_SECRET_*`、不提供 MCP 自动批准；外部 URL 只读展示并标风险。详见 [§3](docs/SECURITY.md)。

## 仓库结构

```
android/            Android 工程（单 :app 模块，Kotlin + Compose）
host/               主机桥接（Node.js，零依赖）+ 测试
scripts/            工具链安装、构建、协议抓包、E2E
docs/               协议、安全、UI 契约、安装说明
work/               构建副本、抓包样本、截图（可再生，不含源码）
release/            签名 APK 与 BUILD-INFO
```

## 已知限制

- 桥接与手机之间是 **局域网 HTTP 明文**（token 鉴权）：仅限可信局域网；跨公网请自建 TLS/VPN。
- Bot 的编排在手机端：手机离线时 Bot 不会自行继续群聊（DSH 会话本身在电脑上保留）。
- 仅支持 Android 8.0+（minSdk 26）；未提供 iOS。
- 非官方项目，与 DeepSeek 官方无关；DSH 版本升级可能改动协议（见 docs/PROTOCOL.md §7 复现方法）。
