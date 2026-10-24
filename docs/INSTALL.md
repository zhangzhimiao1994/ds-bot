# DS Bot 安装与使用

## 0. 你需要什么

- 一台运行 **DeepSeek Harness（DSH）桌面版**的电脑（Windows / macOS 均可跑桥接；本文以 Windows 为例）。
- 一台 Android 8.0+ 手机，和电脑在**同一个可信局域网**。
- 手机安装 `dsbot-0.1.0-release.apk`（本仓库 `release/` 下）。

> 安全前提：桥接只应暴露在可信局域网。**不要**把桥接端口映射到公网；跨公网请自建 TLS/VPN。

## 1. 在电脑上启动桥接

```powershell
cd <仓库目录>
$node = 'E:\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe'
& $node host\dsbot-bridge.mjs --port 8788 --dsh 127.0.0.1:64005
```

启动成功会打印：

```
DS Bot 桥接已启动
  监听:       http://0.0.0.0:8788
  局域网地址: http://192.168.3.36:8788
  配对串:     dsbot://pair?url=http://192.168.3.36:8788&token=xxxxxxxx
  安全:       手机只能访问会话/模型/审批 API；settings、credentials、插件安装等一律拒绝
  审计日志:   %USERPROFILE%\.dsbot\audit.log
```

- 端口/Dsh 地址可用 `--port`、`--dsh` 改；`--bind 127.0.0.1` 可只允许本机调试。
- **撤销所有手机授权**：`& $node host\dsbot-bridge.mjs --reset`（旧 token 立即失效，重新打印配对串）。
- 查看手机做过什么：`Get-Content $env:USERPROFILE\.dsbot\audit.log -Tail 50`（JSONL，不含 token/cookie）。

> 桥接需要读取 `%USERPROFILE%\.dsh\.credentials.yaml` 里的 DSH 浏览器会话密钥来自签 cookie；
> 它**不会**把该 cookie 或任何 `$DSH_SECRET_*` 交给手机。

## 2. 手机连接

1. 安装 APK，打开 **DS Bot**。
2. 复制电脑终端里的整条配对串（`dsbot://pair?url=...&token=...`），粘贴到 App 的「粘贴配对链接」框；
   或把这条链接发到手机上直接点开（App 注册了 `dsbot://pair` 深链）。
3. 也可以手动填「桥接地址」（如 `http://192.168.3.36:8788`）+「设备 token」。
4. 连接成功后进入「Bot 团队」页。设备 token 会用 Android Keystore 加密保存，下次自动连接。

## 3. 创建 Bot 与聊天

- 右下 **＋ 创建 Bot**：填名字、选 emoji、从 6 个模板选角色（或自己写角色设定）、
  可选模型（默认跟随电脑默认模型）、可选工作目录、是否设为主 Bot。
- 点击 Bot 卡片进入 1:1 聊天：流式回复、思考过程折叠、工具调用卡片、停止/清空。
- 顶部菜单「新建群组」：选 ≥1 个成员、可指定管理员 Bot。群聊里：
  - 没有 `@` 且设了管理员 → 管理员先答，并可用 `@成员` 分工；
  - 没有管理员 → 全员按顺序回答；
  - 输入框打 `@` 可弹出成员候选；Bot 之间的 @ 接力最多 2 跳、单次最多 4 条，避免刷屏。
- 审批卡出现在聊天流里：展示**完整命令 + 调用 ID + 指纹**；「批准一次」/「拒绝」；
  危险命令会要求二次确认。不要批准你不理解的命令。

## 4. 安全须知（务必阅读）

1. 手机端**不是**电脑管理员：它改不了 DSH 设置、写不了凭据、装不了插件、改不了权限预设。
   这些只能在电脑上做。
2. 审批卡的命令来自电脑上的 Bot/模型，可能被提示注入操纵；审批前看清完整命令与指纹。
3. 桥接与手机之间是局域网 HTTP 明文 + 设备 token 鉴权；token 泄露 = 对方可读你的会话并代你发消息。
   手机丢失时立刻在电脑上 `--reset`。不要使用公共 Wi-Fi 直连。
4. App 不读取任何 `$DSH_SECRET_*`；不提供 MCP 自动批准；外部链接一律只读展示并标注风险。

## 5. 常见问题

| 现象 | 处理 |
| --- | --- |
| 连接失败「设备未授权」 | 电脑端 `--reset` 重新生成配对串，手机重新粘贴 |
| 连接失败「无法建立实时通道」 | 检查手机与电脑同网段、防火墙放行 8788 端口、桥接仍在运行 |
| 创建 Bot 后一直「未连接」 | 桥接与 DSH 的地址要匹配（默认 8788 / 127.0.0.1:64005）；可在电脑上 curl 验证 |
| 审批卡提示「无法展示完整参数」 | 上游未提供参数关联时按安全策略**禁止批准**，请在电脑端处理 |
| Bot 不回复 | 检查电脑上的 DSH 是否配置了可用模型；App 设置页可看连接与模型目录 |
| 想换电脑/清空 | 设置页「清除配对信息」，然后在新电脑上重新启动桥接并配对 |

## 6. 从源码构建

见根目录 [README.md](../README.md) 的「快速开始」，或：

```powershell
powershell -ExecutionPolicy Bypass -File scripts\setup-toolchain.ps1   # 一次性
powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Variant all -Test
# 产物：release/dsbot-0.1.0-debug.apk / dsbot-0.1.0-release.apk
```

构建脚本会自动用仓库内 `toolchain/`（JDK 21 + Android SDK 35 + Gradle 8.13）并校验签名；
所有 APK 用同一把 `android/keystore/dsbot.jks` 签名，可直接覆盖安装。
