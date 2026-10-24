# DS Bot UI 契约（android-ui 实现，Lead 验收）

> 产品基调：**和 DSH 桌面版一致**（同一套视觉语言），布局按手机做适配，不照抄桌面三栏。
> 桌面版现成素材：`work/probe/web/`（index.html、index-*.css、vendor-*.css、all-js.txt），
> 关键 tokens：深色底 `#151517`、浮层 `#2C2C2E`、正文 `#DDE2E8`、品牌蓝 `#658EFF`、
> 危险红 `#EA2D2E`、成功青 `#00C4B3`；字体栈 `-apple-system, "Segoe UI", "PingFang SC",
> "Microsoft YaHei"`；圆角 `6/8/12/16/999`；代码字体 `"SF Mono","JetBrains Mono",Consolas`。
> 主题已落在 `ui/theme/Theme.kt`（`DsBotColors` / `DsBotTheme`），**必须用它**，不要另起配色。

## 0. 先做视觉基线（第一步，别急着写屏幕）

用 Chrome 无头 + CDP 截 2-3 张 DSH 桌面版截图，自己 `read_image` 看，提炼布局/间距/组件形态：
- Chrome：`C:\Program Files\Google\Chrome\Application\chrome.exe`
- 启动：`--headless=new --remote-debugging-port=9222 --user-data-dir=E:\deepseekharness\work\chrome-profile --no-first-run about:blank`
- CDP：`http://127.0.0.1:9222/json/version` 拿 webSocketDebuggerUrl → 用 WebSocket 发
  `Network.setCookie`（名字/值见 `scripts/probe-dsh.mjs` 的 cookieValue()：DSH authority `127.0.0.1:64005`）
  → `Page.navigate` → `Page.captureScreenshot` → base64 存 `work/design/dsh-*.png`。
  （`scripts/probe-live.mjs` 里有可直接复用的零依赖 WebSocket 客户端；Node 用绝对路径
  `E:\Programs\DeepSeek Harness\resources\runtime\primary-runtime\dependencies\node\bin\node.exe`）
- 目标至少截：会话主界面（含左侧列表+聊天）、设置/插件页、深色模式。把截图路径与你的设计结论发 lead。
- 若 CDP 截图失败超过 2 次，改用 CSS 令牌 + 你自己的判断，别卡住。

## 1. 信息架构（手机适配）

```
底部导航（AppRoot 提供，你不用画）：[Bot 团队] [会话] [设置]
Bot 团队页 ──> Bot 聊天页 / 群聊页（二级页面，全屏，带返回）
            └─ 新建/编辑 Bot 弹层、新建/编辑群组弹层
会话页：DSH 原生会话列表（简版：标题+时间+运行中，点击进入只读/聊天页由 Lead 接）
设置页：连接信息、安全（token 指纹/审计说明/撤销入口）、关于、断开连接
```

- 二级页面自己带 TopAppBar（返回箭头）；三个 Tab 页不要带底部导航（AppRoot 画）。
- 触控目标 ≥44dp；间距 8/12/16/24；卡片圆角 16；列表分隔用间距而非硬线。
- 深/浅色都正常；中文文案；空态/加载/错误态齐全；不做英文占位。

## 2. 必须实现的 Composable（签名固定，Lead 的 AppRoot 直接调用）

```kotlin
// ui/screens/ConnectScreen.kt
@Composable
fun ConnectScreen(
    connection: ConnectionState,
    error: String?,
    savedUrls: List<String>,              // 之前用过的桥接地址（不含 token）
    onConnect: (Endpoint) -> Unit,
    onForgetAll: () -> Unit,
    modifier: Modifier = Modifier,
)

// ui/screens/TeamScreen.kt
@Composable
fun TeamScreen(
    team: TeamState,
    connection: ConnectionState,
    catalog: ModelCatalog?,
    onOpenConversation: (String) -> Unit,
    onCreateBot: () -> Unit,
    onCreateGroup: () -> Unit,
    onEditBot: (BotProfile) -> Unit,
    onDeleteBot: (BotProfile) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
)

// ui/screens/ChatScreen.kt  —— 1:1 与群聊共用
@Composable
fun ChatScreen(
    conversation: ConversationState,
    approvals: List<ApprovalRequest>,      // 已由 Lead 过滤为本会话的
    catalog: ModelCatalog?,
    team: TeamState,                       // 群聊成员头像 / @ 候选用真实 roster
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApproval: (ApprovalRequest, ApprovalDecision) -> Unit,
    onClear: () -> Unit,
    onEditBot: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)

// ui/screens/BotEditorSheet.kt
@Composable
fun BotEditorSheet(
    initial: BotProfile?,                 // null = 新建
    catalog: ModelCatalog?,
    templates: List<BotTemplate> = BotTemplates.all,
    onSave: (BotDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
)

// ui/screens/GroupEditorSheet.kt
@Composable
fun GroupEditorSheet(
    bots: List<BotProfile>,
    initial: BotGroup?,                   // null = 新建
    onSave: (GroupDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
)

// ui/screens/SettingsScreen.kt
@Composable
fun SettingsScreen(
    connection: ConnectionState,
    team: TeamState,
    tokenFingerprint: String?,            // sha256(token) 前 8 位，Lead 传入
    onDisconnect: () -> Unit,
    onForgetAll: () -> Unit,
    modifier: Modifier = Modifier,
)
```

## 3. 聊天页细节（安全要求见 docs/SECURITY.md §1）

- 消息流：用户右侧蓝底；Bot 左侧带头像（emoji + `DsBotColors.AvatarPalette[colorIndex]` 圆底）
  和名字；群聊每条 Bot 消息都显示作者；`SYSTEM` 消息居中弱化提示条。
- 流式：`MessageStatus.STREAMING` 显示打字光标；`reasoning` 可折叠（默认收起，标题「思考过程」）；
  `tools` 渲染为小卡片：图标 + 工具名 + 状态色点 + 摘要（可展开）。
- 审批卡（`ApprovalRequest`）：
  - 必须展示 `toolName`、`callId`、**完整 `argumentsJson`**（等宽字体、可滚动、不可折叠省略）；
  - 展示 `fingerprint` 前 16 位与「这是你正在批准的请求指纹」；
  - `dangerHints` 非空时用危险色边框 + 提示清单，按钮文案「确认执行危险命令」，点击后**二次确认弹窗**
    （弹窗里再次完整展示命令，默认焦点在「拒绝」）；
  - 只提供「批准一次」「拒绝」；没有「始终允许」；提交后卡片置灰显示「已处理」。
- 输入框：多行、发送按钮、工作中显示「停止」；群聊支持 `@` 弹出成员候选（输入 `@` 后列成员，
  点选插入「@名字 」）；发送后清空。
- 顶部：头像+名字+（群）成员头像行；右侧菜单：编辑（Bot 才有）/ 清空记录。
- 群聊顶部显示「正在思考：A、B」（`conversation.workingBotIds`）。

## 4. 团队页细节

- 顶部 AppBar：「DS Bot · Bot 团队」+ 连接状态胶囊（`ConnectionState`：绿点=已连接/黄=连接中/红=失败，
  显示 `displayTarget`）。
- 第一段「Bots」：卡片列表（每卡：头像、名字、`shortPersona`、主 Bot 徽标「主」、最后活动时间、
  工作中呼吸点）；长按/更多菜单：编辑、删除（删除要二次确认）。
- 第二段「群组」：卡片（群名、成员头像堆叠最多 4 个 + "+N"、最后消息）。
- 右下 FAB「＋ 创建 Bot」；AppBar 右侧图标「新建群组」。
- 空态：插画式文案「Bot 都住在你的电脑上，先把电脑连上」+ 创建引导按钮。

## 5. 设置页细节

- 分组：连接（地址、模式、断开/重新配对）、安全（设备 token 指纹、审计说明「电脑端
  ~/.dsbot/audit.log」、如何撤销「在电脑端运行 --reset」、公网使用警告）、Bot 团队（Bot 数/群数/清空本地记录）、关于（版本、非官方说明）。
- 所有危险操作二次确认。

## 6. 工程约束

- 只改 `android/app/src/main/java/com/dsbot/mobile/ui/**`（theme 可微调但保留 `DsBotColors` API）；
  不要改 `core/**`、`bots/**`、`data/**`、`MainActivity.kt`、`AndroidManifest.xml`、`build.gradle.kts`。
- 预览：每个屏幕一个 `@Preview`，用 `ui/preview/DemoData.kt`（你自己写）里的假数据；
  假数据要覆盖：3 Bot（1 主 Bot）、1 群（3 成员）、流式消息、工具卡、审批卡（含危险项）、空态。
- `ui/AppRoot.kt`/`ui/AppViewModel.kt` 由 Lead 写；你只需保证上面的签名与它匹配。
- 构建：`powershell -ExecutionPolicy Bypass -File E:\deepseekharness\scripts\build.ps1 -Tag ui -Variant debug -NoReleaseCopy -SkipVerify`
  （沙箱内 Gradle 可写 work/ 副本；首次构建可能因依赖下载失败 → 告诉 Lead 用完整权限预热一次缓存，之后再构建）。
