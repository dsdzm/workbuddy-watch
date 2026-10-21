package com.workbuddy.watch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.data.ChatMessage
import com.workbuddy.watch.data.ChatRole
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.MarkdownText
import com.workbuddy.watch.ui.components.MutedPillButton
import com.workbuddy.watch.ui.components.PageHeader
import com.workbuddy.watch.ui.components.WatchPage
import com.workbuddy.watch.ui.theme.PillShape
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 历史对话详情：把该会话的**完整消息**按角色逐条铺开，与网页端看到的对话一致。
 *
 * 消息由 ACP `session/load` 回放得到（见 [com.workbuddy.watch.data.WorkBuddyCloudClient.loadHistory]），
 * 因此这里是真实的历史记录，而不是任务状态摘要。
 */
@Composable
fun HistoryDetailScreen(
    roundScreen: Boolean,
    title: String,
    messages: List<ChatMessage>,
    loading: Boolean,
    error: String?,
    onReload: () -> Unit,
    onBack: () -> Unit
) {
    WatchPage(roundScreen) {
        item { PageHeader(title = title.ifBlank { "历史对话" }, onBack = onBack) }
        // 圆屏顶部是弧形，先空出一段，避免第一条消息贴着表盘边缘被裁掉
        item { Spacer(Modifier.height(if (roundScreen) 26.dp else 10.dp)) }
        if (loading) {
            item {
                InfoPill {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = WatchPalette.Accent
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在加载完整对话…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        error?.takeIf { !loading }?.let { message ->
            item {
                InfoPill(
                    container = WatchPalette.ErrorContainer,
                    contentColor = WatchPalette.OnErrorContainer
                ) {
                    Text(message, style = MaterialTheme.typography.labelSmall)
                }
            }
            item { MutedPillButton(text = "重新加载", onClick = onReload) }
        }
        if (!loading && error == null && messages.isEmpty()) {
            item { InfoPill { Text("未读取到该会话的消息", style = MaterialTheme.typography.labelSmall) } }
        }
        items(messages) { msg -> MessageBubble(msg, roundScreen) }
        item { Spacer(Modifier.height(if (roundScreen) 44.dp else 16.dp)) }
    }
}

/** 单条消息气泡：角色标签在上、容器在下，与参考对话页一致。 */
@Composable
private fun MessageBubble(msg: ChatMessage, roundScreen: Boolean) {
    val isUser = msg.role == ChatRole.USER
    // 圆屏两侧是弧线，气泡略收窄，长文本才不会被表盘边缘切掉
    val widthFactor = when {
        !roundScreen -> 1f
        isUser -> 0.90f
        else -> 0.96f
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (roundScreen) 2.dp else 0.dp)
    ) {
        Text(
            if (isUser) "我" else "WorkBuddy",
            style = MaterialTheme.typography.labelSmall,
            color = WatchPalette.OnPillVariant,
            modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)
        )
        Box(
            Modifier
                .fillMaxWidth(widthFactor)
                .clip(PillShape)
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
                // 助手回复是 Markdown，原样铺开会满屏 `**` 和表格竖线
                MarkdownText(msg.text)
            }
        }
    }
}
