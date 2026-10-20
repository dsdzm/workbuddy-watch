package com.workbuddy.watch.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.ui.components.CenteredHeader
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.PageHeader
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.VersionFooter
import com.workbuddy.watch.ui.components.WatchColumn
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 关于页：应用简介、版本与数据说明。
 *
 * 不展示任何可点跳转的链接（手表上没有浏览器页面概念），
 * 所有内容都以只读药丸呈现，保持参考图的极简风格。
 */
@Composable
fun AboutScreen(
    roundScreen: Boolean,
    version: String,
    onBack: () -> Unit
) {
    WatchColumn(roundScreen) {
        PageHeader("关于", onBack)

        CenteredHeader(title = "WorkBuddy", subtitle = "手腕上的 AI 工作搭子")

        InfoPill {
            Text(
                "在手表上直接向 WorkBuddy 下达任务：云端执行、结果回传、产物可下载到手机。",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
        }

        PillRow(
            title = "版本",
            subtitle = "v$version",
            leadingIcon = Icons.Filled.Info,
            trailing = "稳定版"
        )

        PillRow(
            title = "数据说明",
            subtitle = "凭据只保存在手表本地，任务与对话在云端执行",
            leadingIcon = Icons.Filled.Star
        )

        VersionFooter("WorkBuddy Watch v$version")
    }
}
