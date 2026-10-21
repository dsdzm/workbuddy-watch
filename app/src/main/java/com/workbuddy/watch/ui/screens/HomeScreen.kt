package com.workbuddy.watch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.data.Artifact
import com.workbuddy.watch.data.Task
import com.workbuddy.watch.ui.components.AccentPillButton
import com.workbuddy.watch.ui.components.AttachmentChips
import com.workbuddy.watch.ui.components.CenteredHeader
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.PAGER_DOTS_INSET
import com.workbuddy.watch.ui.components.PillInput
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.SceneChips
import com.workbuddy.watch.ui.components.TaskCard
import com.workbuddy.watch.ui.components.WatchPage
import com.workbuddy.watch.ui.components.WatchPager
import com.workbuddy.watch.ui.theme.WatchPalette

private val SCENES = listOf("深度研究", "文档处理", "幻灯片")

/** 首页分两页：① 下达任务 ② 快捷菜单，底部圆点指示当前页（对齐参考图）。 */
private const val PAGE_TASK = 0
private const val PAGE_MENU = 1

@Composable
fun HomeScreen(
    roundScreen: Boolean,
    connected: Boolean,
    cloudError: String?,
    loggedIn: Boolean,
    accountHint: String,
    workingTask: Task?,
    unreadCount: Int,
    attachments: List<Artifact>,
    uploading: Boolean,
    modelLabel: String,
    onScene: (String) -> Unit,
    onCompose: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onList: () -> Unit,
    onUsage: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onModels: () -> Unit,
    onConn: () -> Unit,
    onLogin: () -> Unit,
    onOpenTask: (String) -> Unit
) {
    WatchPager(pageCount = 2) { page ->
        if (page == PAGE_TASK) {
            TaskPage(
                roundScreen = roundScreen,
                connected = connected,
                cloudError = cloudError,
                loggedIn = loggedIn,
                workingTask = workingTask,
                attachments = attachments,
                modelLabel = modelLabel,
                onScene = onScene,
                onCompose = onCompose,
                onRemoveAttachment = onRemoveAttachment,
                onLogin = onLogin,
                onModels = onModels,
                onOpenTask = onOpenTask
            )
        } else {
            MenuPage(
                roundScreen = roundScreen,
                loggedIn = loggedIn,
                accountHint = accountHint,
                unreadCount = unreadCount,
                uploading = uploading,
                onList = onList,
                onUsage = onUsage,
                onConn = onConn,
                onPickFile = onPickFile,
                onSettings = onSettings,
                onAbout = onAbout,
                onLogin = onLogin
            )
        }
    }
}

/** 第 ① 页：标题 + 搜索式输入条 + 场景 + 正在跑的任务 + 主按钮。 */
@Composable
private fun TaskPage(
    roundScreen: Boolean,
    connected: Boolean,
    cloudError: String?,
    loggedIn: Boolean,
    workingTask: Task?,
    attachments: List<Artifact>,
    modelLabel: String,
    onScene: (String) -> Unit,
    onCompose: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onLogin: () -> Unit,
    onModels: () -> Unit,
    onOpenTask: (String) -> Unit
) {
    WatchPage(roundScreen = roundScreen, scrollIndicator = false, bottomInset = PAGER_DOTS_INSET) {
        item { CenteredHeader("WorkBuddy", "手腕上的 AI 工作搭子") }
        // 模型选择：下达任务前就能看到并切换，位置对齐参考图顶部的模型 chip
        item { ModelChip(modelLabel, onModels) }
        item {
            PillInput(
                hint = "输入任务…",
                trailingIcon = Icons.Filled.Search,
                onClick = onCompose
            )
        }
        if (loggedIn && !connected) {
            item {
                InfoPill(
                    container = WatchPalette.ErrorContainer,
                    contentColor = WatchPalette.OnErrorContainer
                ) {
                    Text("云端未连通，任务暂不能发出" + (cloudError?.let { "：$it" } ?: ""))
                }
            }
        }
        if (!loggedIn) {
            item {
                PillRow(
                    title = "未登录",
                    subtitle = "点此用手机号 + 验证码登录，手表独立直连云端",
                    leadingIcon = Icons.Filled.Person,
                    trailing = "登录",
                    onClick = onLogin
                )
            }
        }
        if (workingTask != null) {
            item {
                TaskCard(
                    title = workingTask.title,
                    tag = { TaskStatusTag(workingTask.status) },
                    subText = "${envLabel(workingTask.env)} · ${progressLabel(workingTask)}",
                    progress = progressOf(workingTask),
                    unread = workingTask.unread,
                    onClick = { onOpenTask(workingTask.id) }
                )
            }
        }
        item { SceneChips(SCENES, onScene) }
        if (attachments.isNotEmpty()) {
            item { AttachmentChips(attachments, onRemoveAttachment) }
        }
        item {
            AccentPillButton(
                text = "下达任务",
                icon = Icons.AutoMirrored.Filled.Send,
                onClick = onCompose
            )
        }
    }
}

/** 第 ② 页：整屏宽度的药丸菜单，和参考图的菜单页一一对应。 */
@Composable
private fun MenuPage(
    roundScreen: Boolean,
    loggedIn: Boolean,
    accountHint: String,
    unreadCount: Int,
    uploading: Boolean,
    onList: () -> Unit,
    onUsage: () -> Unit,
    onConn: () -> Unit,
    onPickFile: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
    onLogin: () -> Unit
) {
    WatchPage(roundScreen = roundScreen, scrollIndicator = false, bottomInset = PAGER_DOTS_INSET) {
        item { CenteredHeader("WorkBuddy") }
        item {
            PillRow(
                title = "历史对话",
                subtitle = "查看云上会话的完整记录",
                leadingIcon = Icons.Filled.History,
                trailing = if (unreadCount > 0) "$unreadCount 条新" else null,
                onClick = onList
            )
        }
        item {
            PillRow(
                title = "积分用量",
                subtitle = "剩余积分 / 今日调用",
                leadingIcon = Icons.Filled.Paid,
                onClick = onUsage
            )
        }
        item {
            PillRow(
                title = "执行环境",
                subtitle = "云端沙箱 / 连接电脑",
                leadingIcon = Icons.Filled.Cloud,
                trailing = "切换",
                onClick = onConn
            )
        }
        item {
            PillRow(
                title = "上传附件",
                subtitle = if (uploading) "上传中…" else "选中后随任务一起下发",
                leadingIcon = Icons.Filled.AttachFile,
                onClick = onPickFile
            )
        }
        item {
            PillRow(
                title = if (loggedIn) "已登录" else "未登录",
                subtitle = if (loggedIn) {
                    accountHint.ifBlank { "手表独立直连云端" }
                } else {
                    "点此用手机号 + 验证码登录"
                },
                leadingIcon = Icons.Filled.Person,
                trailing = if (loggedIn) "重新登录" else "登录",
                onClick = onLogin
            )
        }
        item {
            PillRow(
                title = "设置",
                leadingIcon = Icons.Filled.Settings,
                onClick = onSettings
            )
        }
        item {
            PillRow(
                title = "关于",
                leadingIcon = Icons.Filled.Info,
                onClick = onAbout
            )
        }
    }
}

/** 模型 chip：显示当前模型，点击进入模型选择页。 */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(WatchPalette.PillDim)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text(
                text = "模型 · $label",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.Accent
            )
        }
    }
}
