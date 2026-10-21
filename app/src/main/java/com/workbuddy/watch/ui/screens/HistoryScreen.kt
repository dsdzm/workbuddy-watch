package com.workbuddy.watch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.data.ChatMessage
import com.workbuddy.watch.data.ChatRole
import com.workbuddy.watch.data.RemoteModel
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.MarkdownText
import com.workbuddy.watch.ui.components.MutedPillButton
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.PillTextField
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 对话页：历史回放 + 继续追问。
 *
 * 布局参考 DSH-Watch 的 ChatScreen：顶部会话名与模型 chip、中间消息流、
 * 底部常驻输入框。气泡用方形圆角（而非全圆药丸），长回复按 Markdown 渲染。
 */
@Composable
fun HistoryDetailScreen(
    roundScreen: Boolean,
    title: String,
    messages: List<ChatMessage>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    draft: String,
    modelLabel: String,
    models: List<RemoteModel>,
    modelsLoading: Boolean,
    modelsError: String?,
    selectedModel: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onLoadModels: () -> Unit,
    onChooseModel: (String) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit
) {
    var showModels by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // 有新消息或正在等回复时滚到底部，保持「最新一条可见」
    LaunchedEffect(messages.size, busy) {
        val target = messages.lastIndex + if (busy) 1 else 0
        if (target >= 0) listState.animateScrollToItem(target)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (roundScreen) 0.74f else 1f)
        ) {
            // 标题栏：返回 + 会话名
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.KeyboardArrowLeft,
                        contentDescription = "返回",
                        modifier = Modifier.size(22.dp),
                        tint = WatchPalette.OnPillVariant
                    )
                }
                Text(
                    text = title.ifBlank { "对话" },
                    style = MaterialTheme.typography.titleSmall,
                    color = WatchPalette.OnPill,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(32.dp))
            }

            // 模型 chip：点击弹出选择
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                ModelChip(modelLabel) {
                    showModels = true
                    if (models.isEmpty() && !modelsLoading) onLoadModels()
                }
            }

            if (loading) {
                InfoPill {
                    Text("正在加载完整对话…", style = MaterialTheme.typography.labelSmall)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { msg -> MessageBubble(msg, roundScreen) }
                if (busy) {
                    item {
                        Text(
                            text = "WorkBuddy 正在回复…",
                            style = MaterialTheme.typography.labelSmall,
                            color = WatchPalette.OnPillVariant,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
            }

            error?.takeIf { !loading }?.let { message ->
                InfoPill(
                    container = WatchPalette.ErrorContainer,
                    contentColor = WatchPalette.OnErrorContainer
                ) {
                    Text(message, style = MaterialTheme.typography.labelSmall)
                }
                MutedPillButton(text = "重新加载", onClick = onReload)
                Spacer(Modifier.height(4.dp))
            }

            // 底部常驻输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = "发消息…",
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                SendButton(enabled = draft.isNotBlank() && !busy, onClick = onSend)
            }
        }

        if (showModels) {
            ModelPickerOverlay(
                models = models,
                selectedModel = selectedModel,
                loading = modelsLoading,
                error = modelsError,
                onPick = {
                    onChooseModel(it)
                    showModels = false
                },
                onDismiss = { showModels = false }
            )
        }
    }
}

/** 单条消息：角色标签在上、方形圆角气泡在下。 */
@Composable
private fun MessageBubble(msg: ChatMessage, roundScreen: Boolean) {
    val isUser = msg.role == ChatRole.USER
    val widthFactor = if (isUser) {
        if (roundScreen) 0.92f else 0.94f
    } else {
        if (roundScreen) 0.98f else 1f
    }
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = if (isUser) "我" else "WorkBuddy",
            style = MaterialTheme.typography.labelSmall,
            color = WatchPalette.OnPillVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFactor)
                .clip(RoundedCornerShape(22.dp))
                .background(if (isUser) WatchPalette.PillHigh else WatchPalette.Pill)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (isUser) {
                Text(
                    msg.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WatchPalette.OnPill
                )
            } else {
                MarkdownText(msg.text)
            }
        }
    }
}

/** 顶部模型 chip：小号药丸，点开选择列表。 */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(WatchPalette.PillDim)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = WatchPalette.Accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 圆形发送键：内容为空时置灰。 */
@Composable
private fun SendButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) WatchPalette.Accent else WatchPalette.PillDim)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Send,
            contentDescription = "发送",
            modifier = Modifier.size(18.dp),
            tint = if (enabled) WatchPalette.OnAccent else WatchPalette.OnPillVariant
        )
    }
}

/** 模型选择浮层：铺满整屏，列表可滚动，点空白处关闭。 */
@Composable
private fun ModelPickerOverlay(
    models: List<RemoteModel>,
    selectedModel: String,
    loading: Boolean,
    error: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.95f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "选择模型",
                style = MaterialTheme.typography.titleSmall,
                color = WatchPalette.OnPill,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))

            when {
                loading -> InfoPill {
                    Text("正在获取模型列表…", style = MaterialTheme.typography.labelSmall)
                }

                models.isEmpty() -> InfoPill(
                    container = WatchPalette.ErrorContainer,
                    contentColor = WatchPalette.OnErrorContainer
                ) {
                    Text(
                        error ?: "云端没有返回可选模型",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                else -> models.forEach { model ->
                    PillRow(
                        title = model.name.ifBlank { model.id },
                        subtitle = model.description,
                        selected = model.id == selectedModel,
                        trailing = if (model.id == selectedModel) "✓" else null,
                        onClick = { onPick(model.id) }
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            MutedPillButton(text = "取消", onClick = onDismiss)
        }
    }
}
