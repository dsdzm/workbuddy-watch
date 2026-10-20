package com.workbuddy.watch.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.workbuddy.watch.data.ExecEnv
import com.workbuddy.watch.data.Task
import com.workbuddy.watch.data.TaskStatus
import com.workbuddy.watch.ui.components.StatusTag
import com.workbuddy.watch.ui.theme.WatchPalette

fun envLabel(env: ExecEnv): String = if (env == ExecEnv.CLOUD) "云端工作" else "连接电脑"

fun statusLabel(status: TaskStatus): String = when (status) {
    TaskStatus.WORKING -> "执行中"
    TaskStatus.BLOCKED -> "需补充"
    TaskStatus.DONE -> "已完成"
    TaskStatus.FAILED -> "失败"
    TaskStatus.STOPPED -> "已中断"
}

@Composable
fun statusColor(status: TaskStatus): Color = when (status) {
    TaskStatus.WORKING -> WatchPalette.Accent
    TaskStatus.BLOCKED -> Color(0xFFA9CBE8)
    TaskStatus.DONE -> WatchPalette.Accent
    TaskStatus.FAILED -> WatchPalette.Error
    TaskStatus.STOPPED -> WatchPalette.OnPillVariant
}

@Composable
fun TaskStatusTag(status: TaskStatus) {
    StatusTag(text = statusLabel(status), color = statusColor(status))
}

fun progressOf(task: Task): Float? {
    if (task.totalSteps <= 0) return null
    return (task.doneSteps.toFloat() / task.totalSteps.toFloat()).coerceIn(0f, 1f)
}

/** 进度文案：云端不回传步数，用不确定进度描述。 */
fun progressLabel(task: Task): String =
    if (task.totalSteps > 0) "${task.doneSteps}/${task.totalSteps} 步" else "云端执行中"
