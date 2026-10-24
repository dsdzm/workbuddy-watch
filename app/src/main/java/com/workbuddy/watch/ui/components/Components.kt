package com.workbuddy.watch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.workbuddy.watch.data.Artifact
import com.workbuddy.watch.ui.theme.PillShape
import com.workbuddy.watch.ui.theme.WatchPalette

/** 药丸卡片统一的内边距：横向 16、纵向 12，做出参考图里那种「矮胖胶囊」。 */
@Composable
private fun pillContentPadding(): PaddingValues =
    if (LocalCompactUi.current) {
        // 267dp 小屏：横向收窄、纵向加高，单点触控更容易点中
        PaddingValues(horizontal = 12.dp, vertical = 14.dp)
    } else {
        PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    }

/* ---------------- 外壳 ---------------- */

/** 全屏外壳：纯黑底，内容自行负责内边距。 */
@Composable
fun WatchFrame(roundScreen: Boolean, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(WatchPalette.Background)
    ) {
        content()
    }
}

/** 右滑（从左往右拖）返回的手势。横向拖动累计超过阈值即触发 [onBack]。 */
fun Modifier.swipeBack(onBack: () -> Unit): Modifier = composed {
    pointerInput(onBack) {
        var dragged = 0f
        detectHorizontalDragGestures(
            onDragStart = { dragged = 0f },
            onDragCancel = { dragged = 0f },
            onDragEnd = {
                if (dragged > 48.dp.toPx()) onBack()
                dragged = 0f
            }
        ) { _, amount -> dragged += amount }
    }
}

/* ---------------- 标题 ---------------- */

/** 居中标题（参考图顶部那条「Tenue Browser」），可带一行副标题。 */
@Composable
fun CenteredHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = WatchPalette.OnPill,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 二级页标题：居中标题 + 左侧返回。 */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        CenteredHeader(title = title, modifier = Modifier.align(Alignment.Center))
        if (onBack != null) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = WatchPalette.Accent)
            }
        }
    }
}

/** 分组小标签。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = WatchPalette.OnPillVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp)
    )
}

/** 版本脚注。 */
@Composable
fun VersionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = WatchPalette.Outline,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    )
}

/* ---------------- 药丸条目 ---------------- */

/**
 * 参考图里的主列表条目：整屏宽度的胶囊，左侧线性图标 + 标题（可带副标题），
 * 右侧可选一段强调文字。
 *
 * [onClick] 为空时退化为不可点按的静态条目（步骤清单、只读信息），此时不做禁用置灰。
 */
@Composable
fun PillRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    trailing: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val container = if (selected) WatchPalette.PillHigh else WatchPalette.Pill

    @Composable
    fun content() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) WatchPalette.Accent else WatchPalette.OnPillVariant
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = WatchPalette.OnPill,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = WatchPalette.OnPillVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (!trailing.isNullOrBlank()) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = WatchPalette.Accent,
                    maxLines = 1
                )
            }
        }
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            shape = PillShape,
            colors = CardDefaults.cardColors(containerColor = container),
            contentPadding = pillContentPadding()
        ) { content() }
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .clip(PillShape)
                .background(container)
                .padding(pillContentPadding())
        ) { content() }
    }
}

/** 不可点按的内容药丸：块状说明文本，如结果摘要、消息气泡。 */
@Composable
fun InfoPill(
    modifier: Modifier = Modifier,
    container: Color = WatchPalette.Pill,
    contentColor: Color = WatchPalette.OnPill,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(container)
            .padding(pillContentPadding())
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor
        ) {
            content()
        }
    }
}

/* ---------------- 按钮 ---------------- */

/** 强调色药丸按钮：参考图底部那颗淡紫蓝按钮。 */
@Composable
fun AccentPillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = WatchPalette.Accent,
            contentColor = WatchPalette.OnAccent,
            disabledContainerColor = WatchPalette.PillHigh,
            disabledContentColor = WatchPalette.OnPillVariant
        ),
        label = {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    )
}

/** 中性灰药丸按钮：次级操作。 */
@Composable
fun MutedPillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = WatchPalette.Pill,
            contentColor = WatchPalette.OnPill,
            disabledContainerColor = WatchPalette.PillDim,
            disabledContentColor = WatchPalette.OnPillVariant
        ),
        label = {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    )
}

/** 错误色药丸按钮：危险操作，如中断任务、退出登录。 */
@Composable
fun DangerPillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = WatchPalette.ErrorContainer,
            contentColor = WatchPalette.OnErrorContainer,
            disabledContainerColor = WatchPalette.PillDim,
            disabledContentColor = WatchPalette.OnPillVariant
        ),
        label = {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    )
}

/* ---------------- 输入 ---------------- */

/** 只读药丸输入框（参考图里的搜索条）：点击后进入真正的输入页。 */
@Composable
fun PillInput(
    hint: String,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = PillShape,
        colors = CardDefaults.cardColors(containerColor = WatchPalette.PillDim),
        contentPadding = PaddingValues(
            horizontal = if (LocalCompactUi.current) 12.dp else 16.dp,
            vertical = if (LocalCompactUi.current) 12.dp else 14.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = WatchPalette.OnPillVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailingIcon != null) {
                Icon(
                    trailingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = WatchPalette.OnPillVariant
                )
            }
        }
    }
}

/** 可编辑药丸输入框。用 `BasicTextField` 自绘容器，避免通用 M3 输入框把纯黑底顶开。 */
@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        cursorBrush = SolidColor(WatchPalette.Accent),
        textStyle = TextStyle(color = WatchPalette.OnPill, fontSize = 14.sp),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(PillShape)
                    .background(WatchPalette.PillDim)
                    .padding(
                        horizontal = if (LocalCompactUi.current) 12.dp else 16.dp,
                        vertical = if (LocalCompactUi.current) 12.dp else 14.dp
                    )
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WatchPalette.OnPillVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}

/* ---------------- 小标签 / 附件 / 场景 ---------------- */

/** 状态标签：文字 + 语义色的小药丸。 */
@Composable
fun StatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.20f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** 首页快捷场景：普通屏横向滚动；紧凑屏改成自动换行，避免横滑和系统右滑冲突。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SceneChips(scenes: List<String>, onPick: (String) -> Unit) {
    if (LocalCompactUi.current) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            scenes.forEach { label -> SceneChip(label, onPick) }
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        scenes.forEach { label -> SceneChip(label, onPick) }
    }
}

@Composable
private fun SceneChip(label: String, onPick: (String) -> Unit) {
    Card(
        onClick = { onPick(label) },
        shape = PillShape,
        colors = CardDefaults.cardColors(containerColor = WatchPalette.Pill),
        contentPadding = PaddingValues(
            horizontal = if (LocalCompactUi.current) 12.dp else 14.dp,
            vertical = if (LocalCompactUi.current) 10.dp else 8.dp
        )
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = WatchPalette.OnPill, maxLines = 1)
    }
}

/** 待下发附件：逐条展示文件名，点击即移除。 */
@Composable
fun AttachmentChips(attachments: List<Artifact>, onRemove: (String) -> Unit) {
    if (attachments.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        attachments.forEach { a ->
            Card(
                onClick = { onRemove(a.uri) },
                modifier = Modifier.fillMaxWidth(),
                shape = PillShape,
                colors = CardDefaults.cardColors(containerColor = WatchPalette.Pill),
                contentPadding = PaddingValues(
                    horizontal = if (LocalCompactUi.current) 12.dp else 14.dp,
                    vertical = if (LocalCompactUi.current) 12.dp else 10.dp
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📄", style = MaterialTheme.typography.labelMedium)
                    Text(
                        a.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = WatchPalette.OnPill,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text("✕", style = MaterialTheme.typography.labelMedium, color = WatchPalette.OnPillVariant)
                }
            }
        }
    }
}

/** 圆形徽标：结果 / 失败页顶部的语义色圆底图标。 */
@Composable
fun CircleBadge(
    icon: ImageVector,
    tint: Color,
    container: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = tint)
    }
}

/* ---------------- 任务卡片 / 提示 ---------------- */

/** 任务卡片：标题 + 状态标签 + 可推进度条。 */
@Composable
fun TaskCard(
    title: String,
    tag: @Composable () -> Unit,
    subText: String? = null,
    progress: Float? = null,
    unread: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = PillShape,
        colors = CardDefaults.cardColors(containerColor = WatchPalette.Pill),
        contentPadding = PaddingValues(
            horizontal = if (LocalCompactUi.current) 12.dp else 14.dp,
            vertical = if (LocalCompactUi.current) 12.dp else 10.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = WatchPalette.OnPill,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (unread) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(WatchPalette.Accent)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tag()
            if (!subText.isNullOrBlank()) {
                Text(
                    subText,
                    style = MaterialTheme.typography.labelSmall,
                    color = WatchPalette.OnPillVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = WatchPalette.Accent,
                trackColor = WatchPalette.OutlineVariant
            )
        }
    }
}

/** 顶部临时提醒（完成通知）。 */
@Composable
fun ToastBar(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(WatchPalette.PillHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = WatchPalette.Accent
        )
        Column(Modifier.weight(1f)) {
            Text("任务完成", style = MaterialTheme.typography.labelMedium, color = WatchPalette.Accent)
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPill,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
