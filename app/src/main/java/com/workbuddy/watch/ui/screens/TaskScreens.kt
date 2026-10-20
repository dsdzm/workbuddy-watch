package com.workbuddy.watch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.BuildConfig
import com.workbuddy.watch.data.Artifact
import com.workbuddy.watch.data.Task
import com.workbuddy.watch.data.TaskStatus
import com.workbuddy.watch.ui.components.AccentPillButton
import com.workbuddy.watch.ui.components.AttachmentChips
import com.workbuddy.watch.ui.components.CenteredHeader
import com.workbuddy.watch.ui.components.CircleBadge
import com.workbuddy.watch.ui.components.DangerPillButton
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.MutedPillButton
import com.workbuddy.watch.ui.components.PageHeader
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.PillTextField
import com.workbuddy.watch.ui.components.SectionLabel
import com.workbuddy.watch.ui.components.StatusTag
import com.workbuddy.watch.ui.components.TaskCard
import com.workbuddy.watch.ui.components.VersionFooter
import com.workbuddy.watch.ui.components.WatchColumn
import com.workbuddy.watch.ui.components.WatchPage
import com.workbuddy.watch.ui.theme.WatchPalette

/* ---------------- 确认指令（文字输入） ---------------- */

@Composable
fun ConfirmScreen(
    roundScreen: Boolean,
    draft: String,
    envText: String,
    busy: Boolean,
    attachments: List<Artifact>,
    onDraftChange: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit
) {
    WatchColumn(roundScreen) {
        PageHeader("下达任务", onBack)
        SectionLabel("任务描述")
        PillTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = !busy,
            singleLine = false,
            maxLines = 4,
            placeholder = "输入要交给它做的事…"
        )
        SectionLabel("执行环境")
        InfoPill {
            Text(envText, style = MaterialTheme.typography.labelMedium)
        }
        if (attachments.isNotEmpty()) {
            SectionLabel("随任务下发（点按移除）")
            AttachmentChips(attachments, onRemoveAttachment)
        }
        AccentPillButton(
            text = if (busy) "发送中…" else "发送",
            icon = Icons.AutoMirrored.Filled.Send,
            enabled = !busy && draft.isNotBlank(),
            onClick = onSend
        )
        MutedPillButton(text = "取消", enabled = !busy, onClick = onBack)
    }
}

/* ---------------- 执行中 ---------------- */

@Composable
fun RunningScreen(
    roundScreen: Boolean,
    task: Task,
    onStop: () -> Unit,
    onHome: () -> Unit,
    onFollowup: () -> Unit
) {
    val blocked = task.status == TaskStatus.BLOCKED
    WatchPage(roundScreen) {
        item { PageHeader(if (blocked) "需要补充信息" else "执行中", onHome) }
        item {
            TaskCard(
                title = task.title,
                tag = { TaskStatusTag(task.status) },
                subText = "${envLabel(task.env)} · ${progressLabel(task)}",
                progress = progressOf(task)
            ) { onHome() }
        }
        if (task.totalSteps > 0) {
            item { SectionLabel("执行步骤") }
            items(task.totalSteps) { i ->
                val done = i < task.doneSteps
                PillRow(
                    title = "第 ${i + 1} 步",
                    leadingIcon = if (done) Icons.Filled.CheckCircle else Icons.Filled.PlayArrow,
                    trailing = if (done) "已完成" else "进行中",
                    selected = !done && !blocked
                )
            }
        } else {
            item {
                InfoPill {
                    Text(
                        "步骤由云端调度，完成后会自动通知你。",
                        style = MaterialTheme.typography.labelSmall,
                        color = WatchPalette.OnPillVariant
                    )
                }
            }
        }
        if (blocked) {
            item {
                AccentPillButton(text = "补充信息", onClick = onFollowup)
            }
        }
        item {
            DangerPillButton(
                text = "中断任务",
                icon = Icons.Filled.Close,
                onClick = onStop
            )
        }
        item { MutedPillButton(text = "后台运行", onClick = onHome) }
    }
}

/* ---------------- askuser：回答云端提问 ---------------- */

@Composable
fun AskScreen(
    roundScreen: Boolean,
    task: Task,
    draft: String,
    busy: Boolean,
    onDraftChange: (String) -> Unit,
    onAnswer: (String) -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit
) {
    val ask = task.ask
    WatchColumn(roundScreen) {
        PageHeader("需要你的选择", onHome)
        ask?.header?.takeIf { it.isNotBlank() }?.let {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StatusTag(text = it, color = WatchPalette.Accent)
            }
        }
        InfoPill {
            Text(
                ask?.question ?: "云端需要你补充信息后继续",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        ask?.options?.takeIf { it.isNotEmpty() }?.let { options ->
            SectionLabel("可选答案")
            options.forEach { opt ->
                PillRow(
                    title = opt.label,
                    subtitle = opt.description,
                    leadingIcon = Icons.Filled.CheckCircle,
                    enabled = !busy,
                    onClick = { onAnswer(opt.label) }
                )
            }
        }
        SectionLabel("或直接回答")
        PillTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = !busy,
            singleLine = false,
            maxLines = 3,
            placeholder = "输入你的回答…"
        )
        AccentPillButton(
            text = if (busy) "提交中…" else "提交回答",
            enabled = !busy && draft.isNotBlank(),
            onClick = { onAnswer(draft) }
        )
        MutedPillButton(text = "后台运行", enabled = !busy, onClick = onHome)
        DangerPillButton(text = "中断任务", icon = Icons.Filled.Close, onClick = onStop)
    }
}

/* ---------------- 结果摘要 ---------------- */

@Composable
fun ResultScreen(
    roundScreen: Boolean,
    task: Task,
    downloading: Set<String>,
    onDownload: (Artifact) -> Unit,
    onOpen: (Artifact) -> Unit,
    onFollowup: () -> Unit,
    onDone: () -> Unit
) {
    WatchPage(roundScreen) {
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircleBadge(
                    icon = Icons.Filled.CheckCircle,
                    tint = WatchPalette.OnAccent,
                    container = WatchPalette.Accent
                )
            }
        }
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StatusTag(text = "已完成", color = WatchPalette.Accent)
            }
        }
        item {
            Text(
                task.title,
                style = MaterialTheme.typography.titleSmall,
                color = WatchPalette.OnPill,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }
        item {
            InfoPill {
                Column(
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text("结果摘要", style = MaterialTheme.typography.labelMedium, color = WatchPalette.Accent)
                    val points = task.result?.points.orEmpty()
                    if (points.isEmpty()) {
                        Text("云端未返回文字摘要", style = MaterialTheme.typography.labelSmall, color = WatchPalette.OnPillVariant)
                    } else {
                        points.forEach { p ->
                            Text("· $p", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        val files = task.result?.files.orEmpty()
        if (files.isNotEmpty()) {
            item { SectionLabel("产物文件") }
            items(files) { a ->
                val busy = a.uri in downloading
                PillRow(
                    title = a.name,
                    subtitle = if (a.downloaded) "已下载，可点开查看" else "云端产物",
                    trailing = when {
                        busy -> "下载中…"
                        a.downloaded -> "打开"
                        else -> "下载"
                    },
                    enabled = !busy,
                    onClick = { if (a.downloaded) onOpen(a) else onDownload(a) }
                )
            }
        }
        item {
            AccentPillButton(text = "追问", onClick = onFollowup)
        }
        item { MutedPillButton(text = "完成", onClick = onDone) }
    }
}

/* ---------------- 历史对话列表 ---------------- */

@Composable
fun ListScreen(
    roundScreen: Boolean,
    tasks: List<Task>,
    onOpen: (String) -> Unit,
    onHome: () -> Unit
) {
    WatchPage(roundScreen) {
        item { PageHeader("历史对话", onHome) }
        item {
            InfoPill {
                Text(
                    "你在网页端的所有云上会话，点开可查看完整对话记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WatchPalette.OnPillVariant
                )
            }
        }
        if (tasks.isEmpty()) {
            item {
                PillRow(
                    title = "还没有历史对话",
                    subtitle = "在首页下达一个任务后，这里会出现它的记录",
                    leadingIcon = Icons.Filled.Info
                )
            }
        }
        val groups = listOf(
            "进行中" to tasks.filter {
                it.status == TaskStatus.WORKING || it.status == TaskStatus.BLOCKED || it.status == TaskStatus.STOPPED
            },
            "已完成" to tasks.filter { it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED }
        )
        groups.forEach { (name, list) ->
            if (list.isEmpty()) return@forEach
            item { SectionLabel(name) }
            items(list) { t ->
                TaskCard(
                    title = t.title,
                    tag = { TaskStatusTag(t.status) },
                    subText = envLabel(t.env),
                    unread = t.unread
                ) { onOpen(t.id) }
            }
        }
        item { MutedPillButton(text = "返回首页", onClick = onHome) }
    }
}

/* ---------------- 失败 / 离线 ---------------- */

@Composable
fun ErrorScreen(
    roundScreen: Boolean,
    task: Task?,
    onRetry: () -> Unit,
    onHome: () -> Unit
) {
    WatchColumn(roundScreen) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircleBadge(
                icon = Icons.Filled.Warning,
                tint = WatchPalette.OnErrorContainer,
                container = WatchPalette.ErrorContainer
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            StatusTag(text = "任务失败", color = WatchPalette.Error)
        }
        if (task != null) {
            CenteredHeader(title = task.title)
            InfoPill {
                Text(
                    task.error ?: "执行未完成",
                    style = MaterialTheme.typography.labelMedium,
                    color = WatchPalette.OnErrorContainer
                )
            }
        } else {
            CenteredHeader(title = "无法完成任务")
        }
        AccentPillButton(
            text = "重试",
            icon = Icons.Filled.Refresh,
            onClick = onRetry
        )
        MutedPillButton(text = "返回首页", onClick = onHome)
    }
}

/* ---------------- 账号与云端（卡片式设置） ---------------- */

@Composable
fun SettingsScreen(
    roundScreen: Boolean,
    loggedIn: Boolean,
    accountHint: String,
    modelLabel: String,
    envReport: String?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onToggleShape: () -> Unit,
    onModels: () -> Unit,
    onAbout: () -> Unit,
    onCheckEnv: () -> Unit,
    onBack: () -> Unit,
    version: String = BuildConfig.VERSION_NAME
) {
    WatchPage(roundScreen) {
        item { PageHeader("设置", onBack) }

        item { SectionLabel("账号") }
        item {
            PillRow(
                title = if (loggedIn) "已登录" else "未登录",
                subtitle = if (loggedIn) {
                    accountHint.ifBlank { "手表独立直连云端，无需手机端" }
                } else {
                    "点此用手机号 + 验证码登录"
                },
                leadingIcon = Icons.Filled.Person,
                trailing = if (loggedIn) null else "登录",
                onClick = if (loggedIn) null else onLogin
            )
        }
        if (loggedIn) {
            item {
                PillRow(
                    title = "退出登录",
                    subtitle = "清除手表上的登录凭据",
                    leadingIcon = Icons.Filled.Close,
                    onClick = onLogout
                )
            }
        }

        item { SectionLabel("外观") }
        item {
            PillRow(
                title = if (roundScreen) "圆形表盘" else "方形表盘",
                subtitle = "点此在圆屏 / 方屏之间切换，内容边距随之调整",
                leadingIcon = Icons.Filled.Refresh,
                trailing = "切换",
                onClick = onToggleShape
            )
        }

        item { SectionLabel("模型") }
        item {
            PillRow(
                title = "当前模型",
                subtitle = modelLabel,
                leadingIcon = Icons.Filled.Star,
                trailing = "更改",
                onClick = onModels
            )
        }

        item { SectionLabel("诊断") }
        item {
            PillRow(
                title = "检测浏览器环境",
                subtitle = "查看本机 WebView 内核与可用浏览器",
                leadingIcon = Icons.Filled.Info,
                onClick = onCheckEnv
            )
        }
        envReport?.let { report ->
            item {
                InfoPill {
                    Text(
                        report,
                        style = MaterialTheme.typography.labelSmall,
                        color = WatchPalette.OnPillVariant
                    )
                }
            }
        }

        item {
            PillRow(
                title = "关于 WorkBuddy",
                subtitle = "版本与开源信息",
                leadingIcon = Icons.Filled.Info,
                onClick = onAbout
            )
        }
        item { VersionFooter("WorkBuddy Watch v$version") }
    }
}
