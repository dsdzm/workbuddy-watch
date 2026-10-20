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
        item { PageHeader("历史对话", onBack) }
        if (title.isNotBlank()) {
            item {
                InfoPill {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        color = WatchPalette.OnPillVariant
                    )
                }
            }
        }
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
        items(messages) { msg -> MessageBubble(msg) }
    }
}

/** 单条消息气泡：用户用强调容器、助手用中性药丸，腕上仍保持整行宽度以保证可读性。 */
@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatRole.USER
    Column(Modifier.fillMaxWidth()) {
        Text(
            if (isUser) "我" else "WorkBuddy",
            style = MaterialTheme.typography.labelSmall,
            color = WatchPalette.OnPillVariant,
            modifier = Modifier.padding(start = 10.dp)
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(PillShape)
                .background(if (isUser) WatchPalette.PillHigh else WatchPalette.Pill)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                msg.text,
                style = MaterialTheme.typography.bodyMedium,
                color = WatchPalette.OnPill
            )
        }
    }
}
