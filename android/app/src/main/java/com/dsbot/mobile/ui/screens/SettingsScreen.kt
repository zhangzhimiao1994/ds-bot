package com.dsbot.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsbot.mobile.BuildConfig
import com.dsbot.mobile.bots.TeamState
import com.dsbot.mobile.core.ConnectionPhase
import com.dsbot.mobile.core.ConnectionState
import com.dsbot.mobile.ui.common.ConfirmDialog
import com.dsbot.mobile.ui.common.ConnectionPill
import com.dsbot.mobile.ui.common.DsCard
import com.dsbot.mobile.ui.common.DsRow
import com.dsbot.mobile.ui.common.DsSurfaces
import com.dsbot.mobile.ui.common.DsTextButton
import com.dsbot.mobile.ui.common.ErrorBanner
import com.dsbot.mobile.ui.common.InfoNote
import com.dsbot.mobile.ui.common.SectionHeader
import com.dsbot.mobile.ui.common.relativeTime
import com.dsbot.mobile.ui.preview.DemoData
import com.dsbot.mobile.ui.theme.DsBotColors
import com.dsbot.mobile.ui.theme.DsBotTheme

/**
 * Settings tab: connection, security (token fingerprint / audit / revoke),
 * local Bot team data and the unofficial-app disclaimer.
 *
 * This is a bottom-navigation tab, so there is no back arrow. Every destructive
 * action asks for a second confirmation.
 */
@Composable
fun SettingsScreen(
    connection: ConnectionState,
    team: TeamState,
    tokenFingerprint: String?,
    onDisconnect: () -> Unit,
    onForgetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var showDisconnectDialog by remember { mutableStateOf(false) }
    var showForgetDialog by remember { mutableStateOf(false) }
    var copiedFingerprint by remember { mutableStateOf(false) }
    val endpoint = connection.endpoint
    val privateNetwork = endpoint?.baseUrl?.let { isPrivateHost(it) } ?: true
    val plainHttp = endpoint?.baseUrl?.startsWith("http://") == true

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(DsSurfaces.canvas),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 40.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        color = DsSurfaces.textPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "连接、安全与本地数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = DsSurfaces.textSecondary,
                    )
                }
                ConnectionPill(connection = connection)
            }
        }

        // ------------------------------------------------------------------
        // Connection
        // ------------------------------------------------------------------
        item { SectionHeader(title = "连接") }
        item {
            DsCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                DsRow(
                    title = "电脑地址",
                    subtitle = endpoint?.baseUrl ?: "尚未连接",
                    value = null,
                )
                DsRow(
                    title = "主机",
                    subtitle = connection.hostName?.takeIf { it.isNotBlank() } ?: "未报告",
                    value = connection.hostHome,
                )
                DsRow(
                    title = "模式",
                    subtitle = when {
                        endpoint == null -> "未连接"
                        plainHttp -> "局域网 HTTP（明文，仅限可信网络）"
                        else -> "HTTPS / 加密通道"
                    },
                )
                DsRow(
                    title = "状态",
                    subtitle = when (connection.phase) {
                        ConnectionPhase.CONNECTED -> connection.connectedAtMillis?.let {
                            "${connection.displayTarget ?: "已连接"} · ${connectedFor(it)}"
                        } ?: "已连接"
                        ConnectionPhase.CONNECTING -> "正在连接 ${connection.displayTarget ?: ""}…"
                        ConnectionPhase.FAILED -> connection.error ?: "连接失败"
                        ConnectionPhase.DISCONNECTED -> "未连接"
                    },
                )
                HorizontalDivider(color = DsSurfaces.border)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (connection.phase != ConnectionPhase.DISCONNECTED) {
                        DsTextButton(
                            text = "断开连接",
                            onClick = { showDisconnectDialog = true },
                            color = DsBotColors.Danger,
                        )
                    } else {
                        Text(
                            text = "回到「连接」页粘贴配对链接即可重新连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = DsSurfaces.textSecondary,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
        connection.error?.takeIf { it.isNotBlank() }?.let { message ->
            item {
                Spacer(Modifier.height(8.dp))
                ErrorBanner(message = message)
            }
        }

        // ------------------------------------------------------------------
        // Security
        // ------------------------------------------------------------------
        item { SectionHeader(title = "安全") }
        item {
            DsCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                DsRow(
                    title = "设备 token 指纹",
                    subtitle = "sha256(token) 前 8 位，用来核对电脑端打印的配对信息。",
                    leading = {
                        Icon(
                            Icons.Filled.Fingerprint,
                            contentDescription = null,
                            tint = DsSurfaces.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    value = tokenFingerprint ?: "未配对",
                    trailing = if (tokenFingerprint != null) {
                        {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(tokenFingerprint))
                                    copiedFingerprint = true
                                },
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "复制指纹",
                                    tint = if (copiedFingerprint) DsBotColors.Success else DsSurfaces.textSecondary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
                DsRow(
                    title = "token 存储",
                    subtitle = "设备 token 只用 Android Keystore 加密后存在本机，不上传、不写日志、不会出现在截图演示数据里。",
                    leading = {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = DsSurfaces.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                DsRow(
                    title = "审计日志",
                    subtitle = "电脑端把手机做过的每次请求写入 ~/.dsbot/audit.log（JSONL，含方法/放行状态/耗时/IP，不含 token 与 cookie）。",
                    leading = {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = DsSurfaces.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                HorizontalDivider(color = DsSurfaces.border)
                Column(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
                    Text(
                        text = "如何撤销本机授权",
                        style = MaterialTheme.typography.bodyLarge,
                        color = DsSurfaces.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "在电脑上停止桥接后运行：",
                        style = MaterialTheme.typography.bodySmall,
                        color = DsSurfaces.textSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DsSurfaces.canvas,
                        border = BorderStroke(1.dp, DsSurfaces.border),
                    ) {
                        Text(
                            text = "node dsbot-bridge.mjs --reset",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = DsSurfaces.textPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "执行后所有已配对设备的旧 token 立即失效，需要在电脑终端重新复制配对串。",
                        style = MaterialTheme.typography.bodySmall,
                        color = DsSurfaces.textSecondary,
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            if (!privateNetwork || (plainHttp && !isPrivateHost(endpoint?.baseUrl.orEmpty()))) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DsBotColors.Danger.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, DsBotColors.Danger.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = DsBotColors.Danger,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "公网使用警告",
                                style = MaterialTheme.typography.bodyMedium,
                                color = DsBotColors.Danger,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "当前地址看起来不是局域网。明文 HTTP 暴露在公网时，设备 token 可能被嗅探或重放；如需公网访问，请自行加 TLS 或 VPN，并定期在电脑端 --reset 轮换 token。",
                                style = MaterialTheme.typography.bodySmall,
                                color = DsBotColors.Danger,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            } else {
                InfoNote(
                    text = "只在可信局域网使用。默认桥接只监听局域网，HTTP 明文不会离开你的网络。",
                    icon = Icons.Filled.Security,
                )
            }
        }

        // ------------------------------------------------------------------
        // Bot team
        // ------------------------------------------------------------------
        item { SectionHeader(title = "Bot 团队") }
        item {
            DsCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                DsRow(title = "Bot 数量", value = team.bots.size.toString())
                DsRow(title = "群组数量", value = team.groups.size.toString())
                DsRow(
                    title = "本地数据",
                    subtitle = "Bot 配置与聊天记录只保存在这台手机上；电脑上的 DSH 会话归桌面版所有。",
                )
                HorizontalDivider(color = DsSurfaces.border)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DsTextButton(
                        text = "忘记全部配对",
                        onClick = { showForgetDialog = true },
                        color = DsBotColors.Danger,
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        // About
        // ------------------------------------------------------------------
        item { SectionHeader(title = "关于") }
        item {
            DsCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                DsRow(title = "DS Bot", value = "v${BuildConfig.VERSION_NAME}")
                DsRow(
                    title = "配套版本",
                    subtitle = "需要电脑端运行 dsbot-bridge（DSH 桌面版插件）。",
                )
                DsRow(
                    title = "非官方应用",
                    subtitle = "DS Bot 是第三方客户端，不是 DeepSeek 官方产品；与 DSH 桌面版配套使用。",
                    leading = {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = DsSurfaces.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                DsRow(
                    title = "手机不是管理员",
                    subtitle = "这个 App 只能聊天、查看会话和逐条审批；不能安装插件、改设置、读写密钥或执行任意命令。",
                    leading = {
                        Icon(
                            Icons.Filled.Security,
                            contentDescription = null,
                            tint = DsBotColors.Accent,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
            }
        }
        item {
            Spacer(Modifier.height(14.dp))
            Text(
                text = "危险操作都会在聊天页逐条展示完整命令与指纹。App 端没有「始终允许」「自动批准」。",
                style = MaterialTheme.typography.labelSmall,
                color = DsSurfaces.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (showDisconnectDialog) {
        ConfirmDialog(
            title = "断开连接？",
            message = "断开后 Bot 暂时不能收发消息；配对信息会保留，重新连接不需要再输入 token。",
            confirmLabel = "断开",
            destructive = true,
            onConfirm = {
                showDisconnectDialog = false
                onDisconnect()
            },
            onDismiss = { showDisconnectDialog = false },
        )
    }
    if (showForgetDialog) {
        ConfirmDialog(
            title = "忘记全部配对？",
            message = "会清除本机保存的所有地址与设备 token。之后需要重新粘贴配对串；建议先在电脑端执行 --reset 轮换 token。",
            confirmLabel = "全部忘记",
            destructive = true,
            onConfirm = {
                showForgetDialog = false
                onForgetAll()
            },
            onDismiss = { showForgetDialog = false },
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun connectedFor(sinceMillis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - sinceMillis).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "刚刚连接"
        diff < 3_600_000L -> "已连接 ${diff / 60_000L} 分钟"
        diff < 86_400_000L -> "已连接 ${diff / 3_600_000L} 小时"
        else -> "已连接 ${diff / 86_400_000L} 天 · 自 ${relativeTime(sinceMillis)}"
    }
}

/** Best-effort check whether the bridge address is on the local network. */
internal fun isPrivateHost(baseUrl: String): Boolean {
    if (baseUrl.isBlank()) return true
    val host = baseUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .substringBeforeLast(':')
        .trim('[', ']')
        .lowercase()
    if (host.isBlank()) return false
    if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
    if (host.startsWith("10.") || host.startsWith("192.168.")) return true
    if (host.startsWith("172.")) {
        val second = host.split('.').getOrNull(1)?.toIntOrNull()
        if (second != null && second in 16..31) return true
    }
    if (host.startsWith("169.254.")) return true
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home")) return true
    if (!host.contains('.')) return true // mDNS / plain hostname on the LAN
    return false
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "设置页 · 深色", showBackground = true, backgroundColor = 0xFF151517, heightDp = 1300)
@Composable
private fun SettingsScreenPreview() {
    DsBotTheme(darkTheme = true) {
        SettingsScreen(
            connection = DemoData.connected,
            team = DemoData.team,
            tokenFingerprint = "a1b2c3d4",
            onDisconnect = {},
            onForgetAll = {},
        )
    }
}

@Preview(name = "设置页 · 浅色未连接", showBackground = true, backgroundColor = 0xFFFFFFFF, heightDp = 1300)
@Composable
private fun SettingsScreenLightPreview() {
    DsBotTheme(darkTheme = false) {
        SettingsScreen(
            connection = DemoData.disconnected,
            team = DemoData.emptyTeam,
            tokenFingerprint = null,
            onDisconnect = {},
            onForgetAll = {},
        )
    }
}
