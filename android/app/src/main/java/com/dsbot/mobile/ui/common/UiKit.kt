package com.dsbot.mobile.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsbot.mobile.core.ConnectionPhase
import com.dsbot.mobile.core.ConnectionState
import com.dsbot.mobile.ui.theme.DsBotColors
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// Surface / text colour helpers (single source for both light and dark)
// ---------------------------------------------------------------------------

/** Theme-aware surfaces that mirror the DSH desktop layers. */
object DsSurfaces {
    /** Page canvas: `#151517` dark / `#FFFFFF` light. */
    val canvas: Color
        @Composable get() = MaterialTheme.colorScheme.background

    /** Elevated card / floating layer: `#2C2C2E` dark / `#F4F4F5` light. */
    val card: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant

    /** Sheet / dialog layer (DSH hovercard `#2C2C2E`). */
    val floating: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant

    val border: Color
        @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)

    val textPrimary: Color
        @Composable get() = MaterialTheme.colorScheme.onBackground

    val textSecondary: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

/** Palette colour for a Bot's avatar (`BotProfile.colorIndex`). */
fun avatarColorFor(colorIndex: Int): Color {
    val palette = DsBotColors.AvatarPalette
    if (palette.isEmpty()) return DsBotColors.Accent
    val index = ((colorIndex % palette.size) + palette.size) % palette.size
    return palette[index]
}

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

private val clockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val fullDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

fun clockText(millis: Long): String {
    if (millis <= 0L) return ""
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    return clockFormatter.format(local)
}

/** Human relative timestamp, Chinese copy (刚刚 / n 分钟前 / 今天 HH:mm / 昨天 HH:mm ...). */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0L) return ""
    val diff = now - millis
    if (diff < 0) return "刚刚"
    if (diff < 60_000L) return "刚刚"
    if (diff < 3_600_000L) return "${diff / 60_000L} 分钟前"
    val zone = ZoneId.systemDefault()
    val then = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
    val today = LocalDate.now(zone)
    return when {
        then.toLocalDate() == today -> "今天 ${clockFormatter.format(then)}"
        then.toLocalDate() == today.minusDays(1) -> "昨天 ${clockFormatter.format(then)}"
        then.toLocalDate().year == today.year -> dateFormatter.format(then)
        else -> fullDateFormatter.format(then)
    }
}

/** First [length] characters of an approval fingerprint (the part users compare). */
fun fingerprintShort(fingerprint: String, length: Int = 16): String =
    fingerprint.trim().take(length).ifEmpty { "—" }

/** Show only enough of a secret to recognise it again, never the whole token. */
fun maskedSecret(secret: String): String {
    if (secret.isBlank()) return "—"
    if (secret.length <= 8) return "••••"
    return secret.take(4) + "••••" + secret.takeLast(4)
}

fun statusLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.CONNECTED -> "已连接"
    ConnectionPhase.CONNECTING -> "连接中"
    ConnectionPhase.FAILED -> "连接失败"
    ConnectionPhase.DISCONNECTED -> "未连接"
}

fun statusColor(phase: ConnectionPhase): Color = when (phase) {
    ConnectionPhase.CONNECTED -> DsBotColors.Success
    ConnectionPhase.CONNECTING -> DsBotColors.Warning
    ConnectionPhase.FAILED -> DsBotColors.Danger
    ConnectionPhase.DISCONNECTED -> DsBotColors.DarkTextSecondary
}

// ---------------------------------------------------------------------------
// Avatar / status primitives
// ---------------------------------------------------------------------------

@Composable
fun DsAvatar(
    emoji: String,
    colorIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    working: Boolean = false,
) {
    val color = avatarColorFor(colorIndex)
    val pulse = rememberBreathing(enabled = working)
    val density = LocalDensity.current
    val emojiSize = with(density) { (size * 0.44f).toSp() }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (working) {
            Box(
                Modifier
                    .matchParentSize()
                    .alpha(0.15f + 0.45f * pulse)
                    .border(1.5.dp, color, CircleShape),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(color.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emoji.ifBlank { "🤖" },
                fontSize = emojiSize,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

/** Breathing pulse value in 0f..1f, driven by an infinite transition. */
@Composable
fun rememberBreathing(enabled: Boolean = true, durationMillis: Int = 1100): Float {
    if (!enabled) return 0f
    val transition = rememberInfiniteTransition(label = "breathing")
    val value by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis), RepeatMode.Reverse),
        label = "breathingValue",
    )
    return value
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, pulsing: Boolean = false) {
    val alpha = if (pulsing) 0.35f + 0.65f * rememberBreathing(enabled = true, durationMillis = 900) else 1f
    Box(
        modifier
            .size(size)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun ConnectionPill(
    connection: ConnectionState,
    modifier: Modifier = Modifier,
    showTarget: Boolean = true,
) {
    val color = statusColor(connection.phase)
    val label = statusLabel(connection.phase)
    val target = connection.displayTarget
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusDot(
                color = color,
                size = 7.dp,
                pulsing = connection.phase == ConnectionPhase.CONNECTING,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium,
            )
            if (showTarget && !target.isNullOrBlank() && connection.isConnected) {
                Text(
                    text = "· $target",
                    style = MaterialTheme.typography.labelSmall,
                    color = DsSurfaces.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Layout building blocks
// ---------------------------------------------------------------------------

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = DsSurfaces.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = DsSurfaces.textSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    onClick != null || onLongClick != null -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                    else -> Modifier
                },
            ),
        shape = shape,
        color = DsSurfaces.card,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.6f) ?: DsSurfaces.border),
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun DsBadge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DsBotColors.Accent,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun DsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DsDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DsBotColors.Danger,
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun DsTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, color = if (enabled) color else color.copy(alpha = 0.4f), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun DsToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingEmoji: String? = null,
) {
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else DsSurfaces.card,
        label = "chipBg",
    )
    val borderColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else DsSurfaces.border
    Surface(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        shape = CircleShape,
        color = bg,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingEmoji != null) {
                Text(leadingEmoji, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else DsSurfaces.textPrimary,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

@Composable
fun DsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    onImeAction: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = DsSurfaces.textSecondary) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        shape = RoundedCornerShape(12.dp),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onSend = { onImeAction?.invoke() },
            onDone = { onImeAction?.invoke() },
        ),
        visualTransformation = visualTransformation,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = DsSurfaces.border,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            errorBorderColor = DsBotColors.Danger,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = DsSurfaces.textSecondary,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

// ---------------------------------------------------------------------------
// Banners / empty state
// ---------------------------------------------------------------------------

@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DsBotColors.Danger.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, DsBotColors.Danger.copy(alpha = 0.45f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = DsBotColors.Danger,
                modifier = Modifier.size(18.dp).padding(top = 1.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = DsBotColors.Danger,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = DsBotColors.Danger,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
fun InfoNote(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Info,
    tint: Color = DsBotColors.Accent,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = tint.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.22f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp).padding(top = 1.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = DsSurfaces.textSecondary,
                modifier = Modifier.weight(1f),
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
fun EmptyState(
    emoji: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(DsSurfaces.card),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 32.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = DsSurfaces.textPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = DsSurfaces.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (primaryLabel != null && onPrimary != null) {
            Spacer(Modifier.height(18.dp))
            DsPrimaryButton(text = primaryLabel, onClick = onPrimary)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(4.dp))
            DsTextButton(text = secondaryLabel, onClick = onSecondary)
        }
    }
}

@Composable
fun LoadingState(text: String = "加载中…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = DsSurfaces.textSecondary)
    }
}

// ---------------------------------------------------------------------------
// Dialog / sheet scaffolding
// ---------------------------------------------------------------------------

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissLabel: String = "取消",
    extraContent: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = DsSurfaces.floating,
        titleContentColor = DsSurfaces.textPrimary,
        textContentColor = DsSurfaces.textSecondary,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = DsSurfaces.textSecondary)
                if (extraContent != null) {
                    Spacer(Modifier.height(12.dp))
                    extraContent()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) DsBotColors.Danger else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, color = DsSurfaces.textSecondary)
            }
        },
    )
}

/** Standard DSH-style bottom sheet: rounded 24 top, floating surface, scrolls + IME padded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DsSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        containerColor = DsSurfaces.floating,
        contentColor = DsSurfaces.textPrimary,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            content = content,
        )
    }
}

/** Grouped settings/list row used by the settings screen and sheets. */
@Composable
fun DsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val color = if (danger) DsBotColors.Danger else DsSurfaces.textPrimary
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = DsSurfaces.textSecondary,
                    lineHeight = 17.sp,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = DsSurfaces.textSecondary,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
        }
        trailing?.invoke()
    }
}

// ---------------------------------------------------------------------------
// Pickers
// ---------------------------------------------------------------------------

/** Emoji candidates for bot / group avatars (DSH-style neutral robot default first). */
val BotEmojiOptions: List<String> = listOf(
    "🤖", "⚡", "🛠", "🔎", "✍️", "📋", "🧐", "🧠", "🦾", "🛰", "📚", "🎯", "🧪", "🎨", "💬", "🚀",
)

val GroupEmojiOptions: List<String> = listOf("👥", "🤝", "💬", "🧑‍💻", "🚀", "📚", "🎯", "🧪", "🛰", "🏗", "🌐", "🔥")

@Composable
fun EmojiPickerRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options) { emoji ->
            val isSelected = emoji == selected
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .clickable { onSelect(emoji) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else DsSurfaces.card,
                border = BorderStroke(
                    1.5.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else DsSurfaces.border,
                ),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(emoji, fontSize = 20.sp)
                }
            }
        }
    }
}

@Composable
fun ColorPickerRow(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DsBotColors.AvatarPalette.forEachIndexed { index, color ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (selected) Modifier.border(2.dp, DsSurfaces.textPrimary, CircleShape) else Modifier,
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
