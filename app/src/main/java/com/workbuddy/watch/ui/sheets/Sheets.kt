package com.workbuddy.watch.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.data.ExecEnv
import com.workbuddy.watch.ui.components.AccentPillButton
import com.workbuddy.watch.ui.components.CenteredHeader
import com.workbuddy.watch.ui.components.DangerPillButton
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.MutedPillButton
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.PillTextField
import com.workbuddy.watch.ui.components.SectionLabel
import com.workbuddy.watch.ui.theme.WatchPalette
import com.workbuddy.watch.vm.Sheet
import com.workbuddy.watch.vm.WatchUiState

/**
 * 全局底部弹层：连接 / 中断确认 / 追问。
 *
 * 与业务页同一套「纯黑底 + 灰药丸 + 淡紫蓝」风格：弹层底色纯黑、
 * 去掉默认拖拽把手，条目用 [PillRow]，按钮用药丸按钮，输入用 [PillTextField]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(
    state: WatchUiState,
    onClose: () -> Unit,
    onSetEnv: (ExecEnv) -> Unit,
    onSetConnected: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    onConfirmStop: () -> Unit,
    onQuickReply: (String) -> Unit,
    onDraftChange: (String) -> Unit
) {
    if (state.sheet == Sheet.NONE) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = WatchPalette.Background,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            when (state.sheet) {
                Sheet.CONN -> ConnContent(state, onSetEnv, onSetConnected, onOpenSettings, onOpenLogin)
                Sheet.STOP -> StopContent(onConfirmStop, onClose)
                Sheet.FOLLOWUP -> FollowupContent(state, onQuickReply, onDraftChange, onClose)
                Sheet.NONE -> Unit
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ConnContent(
    state: WatchUiState,
    onSetEnv: (ExecEnv) -> Unit,
    onSetConnected: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit
) {
    CenteredHeader("执行环境")
    Spacer(Modifier.height(10.dp))
    PillRow(
        title = "云端工作",
        subtitle = "云端沙箱，随时可用",
        selected = state.env == ExecEnv.CLOUD,
        trailing = if (state.env == ExecEnv.CLOUD) "✓" else null,
        onClick = { onSetEnv(ExecEnv.CLOUD) }
    )
    PillRow(
        title = "连接电脑",
        subtitle = "远程操控桌面端",
        selected = state.env == ExecEnv.PC,
        trailing = if (state.env == ExecEnv.PC) "✓" else null,
        onClick = { onSetEnv(ExecEnv.PC) }
    )
    Spacer(Modifier.height(4.dp))
    PillRow(
        title = if (state.loggedIn) "已登录 WorkBuddy 云端" else "未登录 WorkBuddy",
        subtitle = when {
            state.loggedIn -> state.accountHint.ifBlank { "点此查看账号" }
            else -> "点此用手机号验证码登录（手表独立直连）"
        },
        onClick = { if (state.loggedIn) onOpenSettings() else onOpenLogin() }
    )
    if (!state.loggedIn) {
        PillRow(
            title = "连接状态",
            subtitle = if (state.connected) "已连接演示环境 · 点击可模拟断开" else "已断开 · 点击可恢复",
            onClick = { onSetConnected(!state.connected) }
        )
    }
}

@Composable
private fun StopContent(onConfirmStop: () -> Unit, onClose: () -> Unit) {
    CenteredHeader("中断该任务？")
    Spacer(Modifier.height(6.dp))
    InfoPill {
        Text(
            "中断后已完成的步骤会保留，可再次追问重新发起。",
            style = MaterialTheme.typography.labelSmall,
            color = WatchPalette.OnPillVariant
        )
    }
    Spacer(Modifier.height(4.dp))
    DangerPillButton(text = "确认中断", onClick = onConfirmStop)
    Spacer(Modifier.height(6.dp))
    MutedPillButton(text = "继续执行", onClick = onClose)
}

@Composable
private fun FollowupContent(
    state: WatchUiState,
    onQuickReply: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onClose: () -> Unit
) {
    CenteredHeader("追问 / 补充信息")
    Spacer(Modifier.height(10.dp))
    PillTextField(
        value = state.draft,
        onValueChange = onDraftChange,
        placeholder = "输入要补充的内容…"
    )
    Spacer(Modifier.height(8.dp))
    AccentPillButton(
        text = "发送",
        enabled = state.draft.isNotBlank(),
        onClick = { onQuickReply(state.draft) }
    )
    Spacer(Modifier.height(8.dp))
    SectionLabel("快捷短语")
    MutedPillButton(text = "导出成 PDF", onClick = { onQuickReply("把结果导出成 PDF") })
    Spacer(Modifier.height(6.dp))
    MutedPillButton(text = "按销售额排序", onClick = { onQuickReply("把表格按销售额排序") })
    Spacer(Modifier.height(6.dp))
    MutedPillButton(text = "取消", onClick = onClose)
}
