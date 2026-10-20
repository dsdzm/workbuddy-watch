package com.workbuddy.watch.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Square
import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.ui.components.CenteredHeader
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.VersionFooter
import com.workbuddy.watch.ui.components.WatchColumn
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 首次启动引导页：**强制**用户先选一次表盘形状。
 *
 * 不做自动探测是刻意的：圆屏 / 方屏判断错了，内容会被圆弧裁掉或留下大片空白，
 * 而手表上用户很难自行补救。选一次很便宜，所以第一次进来必须人工确认，
 * 之后由 [com.workbuddy.watch.data.CloudSettings.shapeChosen] 记住，不再打扰。
 */
@Composable
fun OnboardScreen(
    roundScreen: Boolean,
    version: String,
    onChoose: (round: Boolean) -> Unit
) {
    WatchColumn(roundScreen) {
        CenteredHeader(title = "WorkBuddy", subtitle = "手腕上的 AI 工作搭子")

        InfoPill {
            Text(
                "先确认一下你的表盘形状。选错的后果是内容被裁掉或留白过大，之后可在设置里更改。",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
        }

        PillRow(
            title = "圆形表盘",
            subtitle = "内容自动向内收，避开圆弧裁切",
            leadingIcon = Icons.Filled.Circle,
            trailing = "选择",
            onClick = { onChoose(true) }
        )
        PillRow(
            title = "方形表盘",
            subtitle = "内容铺满屏幕，边距更小",
            leadingIcon = Icons.Filled.Square,
            trailing = "选择",
            onClick = { onChoose(false) }
        )

        VersionFooter(version)
    }
}
