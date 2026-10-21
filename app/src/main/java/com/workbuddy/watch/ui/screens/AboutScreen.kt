package com.workbuddy.watch.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.ui.components.CenteredHeader
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.PageHeader
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.SectionLabel
import com.workbuddy.watch.ui.components.VersionFooter
import com.workbuddy.watch.ui.components.WatchColumn
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 关于页：应用简介、作者、联系方式与免责声明。
 *
 * 入口只在首页左滑菜单里，设置页不再提供。
 * 不展示可点跳转的链接（手表上没有浏览器页面概念），所有内容以只读药丸呈现。
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
            title = "作者",
            subtitle = "东山の字幕",
            leadingIcon = Icons.Filled.Person
        )

        PillRow(
            title = "联系方式",
            subtitle = "2332184782@qq.com",
            leadingIcon = Icons.Filled.Email
        )

        PillRow(
            title = "数据说明",
            subtitle = "凭据只保存在手表本地，任务与对话在云端执行",
            leadingIcon = Icons.Filled.Star
        )

        SectionLabel("免责声明")
        InfoPill {
            Text(
                DISCLAIMER,
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
        }

        VersionFooter("WorkBuddy Watch v$version")
    }
}

private const val DISCLAIMER =
    "本应用是个人基于需求和学习交流，通过 WorkBuddy 网页端接口进行的第三方客户端开发，" +
        "本人及软件并未对 WorkBuddy 接口进行恶意轰炸滥用。" +
        "接口可能会因为 WorkBuddy 官方调整而失效，版权©归腾讯所有。" +
        "本人不对用户不正当使用所造成的账号封禁等行为负责。"
