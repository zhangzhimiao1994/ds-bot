package com.dsbot.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsbot.mobile.core.ConnectionPhase
import com.dsbot.mobile.core.ConnectionState
import com.dsbot.mobile.core.Endpoint
import com.dsbot.mobile.core.PairingLink
import com.dsbot.mobile.ui.common.ConfirmDialog
import com.dsbot.mobile.ui.common.ConnectionPill
import com.dsbot.mobile.ui.common.DsCard
import com.dsbot.mobile.ui.common.DsPrimaryButton
import com.dsbot.mobile.ui.common.DsSurfaces
import com.dsbot.mobile.ui.common.DsTextButton
import com.dsbot.mobile.ui.common.DsTextField
import com.dsbot.mobile.ui.common.ErrorBanner
import com.dsbot.mobile.ui.common.InfoNote
import com.dsbot.mobile.ui.common.SectionHeader
import com.dsbot.mobile.ui.common.maskedSecret
import com.dsbot.mobile.ui.preview.DemoData
import com.dsbot.mobile.ui.theme.DsBotColors
import com.dsbot.mobile.ui.theme.DsBotTheme

/**
 * Pairing / connection screen.
 *
 * The copy is explicit that pairing a phone does **not** make it an
 * administrator of the host computer (docs/SECURITY.md §2): the app can chat
 * and answer approvals, nothing else.
 */
@Composable
fun ConnectScreen(
    connection: ConnectionState,
    error: String?,
    savedUrls: List<String>,
    onConnect: (Endpoint) -> Unit,
    onForgetAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var address by rememberSaveable { mutableStateOf(connection.endpoint?.baseUrl.orEmpty()) }
    var token by rememberSaveable { mutableStateOf(connection.endpoint?.token.orEmpty()) }
    var tokenVisible by rememberSaveable { mutableStateOf(false) }
    var fieldError by rememberSaveable { mutableStateOf<String?>(null) }
    var pasteHint by rememberSaveable { mutableStateOf<String?>(null) }
    var showForgetDialog by rememberSaveable { mutableStateOf(false) }

    val connecting = connection.phase == ConnectionPhase.CONNECTING
    val banner = error?.takeIf { it.isNotBlank() } ?: connection.error?.takeIf { it.isNotBlank() }

    fun submit() {
        val raw = address.trim()
        if (raw.isEmpty()) {
            fieldError = "请填写电脑地址，例如 http://192.168.1.5:8788"
            return
        }
        val withScheme = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "http://$raw"
        }
        val endpoint = Endpoint.normalize(withScheme, token)
        if (endpoint == null) {
            fieldError = "地址或设备 token 无效。地址需要以 http:// 或 https:// 开头，token 不能为空。"
            return
        }
        fieldError = null
        pasteHint = null
        onConnect(endpoint)
    }

    fun pastePairingLink() {
        val text = clipboard.getText()?.text.orEmpty()
        val link = PairingLink.parse(text)
        if (link == null) {
            fieldError = null
            pasteHint = "剪贴板里没有有效的配对链接。请在电脑终端复制 dsbot://pair?url=…&token=… 后重试。"
            return
        }
        address = link.endpoint.baseUrl
        token = link.endpoint.token
        fieldError = null
        pasteHint = "已从配对链接读取地址与设备 token，确认后点「连接电脑」。"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DsSurfaces.canvas)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        Spacer(Modifier.height(28.dp))

        // Brand header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color4561EE, DsBotColors.Accent),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("⚡", fontSize = 24.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "DS Bot",
                    style = MaterialTheme.typography.headlineSmall,
                    color = DsSurfaces.textPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "把手机接到你的电脑上",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DsSurfaces.textSecondary,
                )
            }
            ConnectionPill(connection = connection, showTarget = false)
        }

        Spacer(Modifier.height(22.dp))

        banner?.let {
            ErrorBanner(message = it)
            Spacer(Modifier.height(16.dp))
        }

        DsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Text(
                text = "配对电脑",
                style = MaterialTheme.typography.titleMedium,
                color = DsSurfaces.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "在电脑上运行 dsbot-bridge，把终端打印的一次性配对串交到这里。",
                style = MaterialTheme.typography.bodySmall,
                color = DsSurfaces.textSecondary,
            )
            Spacer(Modifier.height(16.dp))
            DsTextField(
                value = address,
                onValueChange = {
                    address = it
                    fieldError = null
                },
                label = "电脑地址",
                placeholder = "http://192.168.1.5:8788",
                keyboardType = KeyboardType.Uri,
                enabled = !connecting,
            )
            Spacer(Modifier.height(12.dp))
            DsTextField(
                value = token,
                onValueChange = {
                    token = it
                    fieldError = null
                },
                label = "设备 token",
                placeholder = "粘贴配对串时自动填入",
                enabled = !connecting,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                leadingIcon = {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (tokenVisible) "隐藏 token" else "显示 token",
                            modifier = Modifier.size(19.dp),
                        )
                    }
                },
            )
            fieldError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = DsBotColors.Danger,
                )
            }
            pasteHint?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = DsBotColors.Success,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DsPrimaryButton(
                    text = when (connection.phase) {
                        ConnectionPhase.CONNECTED -> "重新连接"
                        ConnectionPhase.FAILED -> "重试连接"
                        else -> "连接电脑"
                    },
                    onClick = { submit() },
                    modifier = Modifier.weight(1f),
                    loading = connecting,
                )
                if (connection.phase == ConnectionPhase.CONNECTED) {
                    DsTextButton(
                        text = "已连接",
                        onClick = {},
                        color = DsBotColors.Success,
                        enabled = false,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            DsTextButton(
                text = "粘贴配对链接",
                onClick = { pastePairingLink() },
                enabled = !connecting,
            )
        }

        Spacer(Modifier.height(18.dp))
        InfoNote(
            text = "配对只发生在同一局域网。地址和 token 只保存在这台手机上（token 由 Android Keystore 加密）。",
            icon = Icons.Filled.Link,
        )

        Spacer(Modifier.height(22.dp))
        SectionHeader(
            title = "最近连接",
            count = savedUrls.size,
            trailing = if (savedUrls.isEmpty()) null else {
                {
                    DsTextButton(
                        text = "全部忘记",
                        onClick = { showForgetDialog = true },
                        color = DsBotColors.Danger,
                    )
                }
            },
        )
        if (savedUrls.isEmpty()) {
            Text(
                text = "还没有连接过任何电脑。第一次配对成功后，这里会出现最近地址。",
                style = MaterialTheme.typography.bodySmall,
                color = DsSurfaces.textSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            DsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                savedUrls.forEachIndexed { index, url ->
                    EndpointRow(
                        url = url,
                        selected = connection.endpoint?.baseUrl == url,
                        onClick = {
                            address = url
                            token = ""
                            fieldError = null
                            pasteHint = "已填入历史地址；设备 token 需要重新粘贴配对链接获取。"
                        },
                    )
                    if (index != savedUrls.lastIndex) {
                        HorizontalDivider(color = DsSurfaces.border)
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionHeader(title = "安全提示")
        DsCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Security,
                    contentDescription = null,
                    tint = DsBotColors.Accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "手机不是电脑的管理员",
                    style = MaterialTheme.typography.titleMedium,
                    color = DsSurfaces.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            SecurityPoint("只能通过桥接聊天与查看会话，无法安装插件、改设置或读写密钥。")
            SecurityPoint("每条危险操作都要在手机上逐条审批，展示完整命令与指纹。")
            SecurityPoint("怀疑泄露时在电脑端运行 dsbot-bridge --reset，所有设备 token 立即失效。")
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "配对串只在桥接启动时打印一次；app 内不会上传，也不会写进日志。",
            style = MaterialTheme.typography.bodySmall,
            color = DsSurfaces.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (showForgetDialog) {
            ConfirmDialog(
                title = "忘记全部最近连接？",
                message = "会清除本机保存的所有地址与设备 token（共 ${savedUrls.size} 条）。之后需要重新粘贴配对串才能连接。",
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
}

@Composable
private fun EndpointRow(
    url: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(DsSurfaces.card, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("🖥", fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayHostOf(url),
                style = MaterialTheme.typography.bodyLarge,
                color = DsSurfaces.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "只填地址 · 设备 token 需重新粘贴配对链接",
                style = MaterialTheme.typography.bodySmall,
                color = DsSurfaces.textSecondary,
            )
        }
        if (selected) {
            Text(
                text = "当前",
                style = MaterialTheme.typography.labelSmall,
                color = DsBotColors.Success,
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = "填入这条地址",
                tint = DsSurfaces.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SecurityPoint(text: String) {
    Row(
        Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = DsBotColors.Accent,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = DsSurfaces.textSecondary,
            lineHeight = 18.sp,
        )
    }
}

private val Color4561EE = androidx.compose.ui.graphics.Color(0xFF4561EE)

private fun displayHostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").trimEnd('/').ifBlank { url }

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "连接页 · 未连接", showBackground = true, backgroundColor = 0xFF151517)
@Composable
private fun ConnectScreenPreview() {
    DsBotTheme(darkTheme = true) {
        ConnectScreen(
            connection = DemoData.disconnected,
            error = null,
            savedUrls = DemoData.savedEndpointUrls,
            onConnect = {},
            onForgetAll = {},
        )
    }
}

@Preview(name = "连接页 · 连接中", showBackground = true, backgroundColor = 0xFF151517)
@Composable
private fun ConnectScreenConnectingPreview() {
    DsBotTheme(darkTheme = true) {
        ConnectScreen(
            connection = DemoData.connecting,
            error = null,
            savedUrls = DemoData.savedEndpointUrls,
            onConnect = {},
            onForgetAll = {},
        )
    }
}

@Preview(name = "连接页 · 失败", showBackground = true, backgroundColor = 0xFF151517)
@Composable
private fun ConnectScreenFailedPreview() {
    DsBotTheme(darkTheme = true) {
        ConnectScreen(
            connection = DemoData.failed,
            error = "连接被拒绝：请确认电脑上的 dsbot-bridge 正在运行，且手机与电脑在同一局域网。",
            savedUrls = DemoData.savedEndpointUrls,
            onConnect = {},
            onForgetAll = {},
        )
    }
}
