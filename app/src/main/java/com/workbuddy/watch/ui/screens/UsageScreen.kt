package com.workbuddy.watch.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.data.UsagePack
import com.workbuddy.watch.data.UsageSnapshot
import com.workbuddy.watch.ui.components.AccentPillButton
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.MutedPillButton
import com.workbuddy.watch.ui.components.PageHeader
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.SectionLabel
import com.workbuddy.watch.ui.components.WatchPage
import com.workbuddy.watch.ui.theme.WatchPalette
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

/**
 * 积分用量：余额 + 今日调用 + 各来源积分包。
 *
 * 数据来自 web 端「套餐与积分」页所用的 `/billing/meter` 接口。
 */
@Composable
fun UsageScreen(
    roundScreen: Boolean,
    usage: UsageSnapshot?,
    loading: Boolean,
    error: String?,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit
) {
    WatchPage(roundScreen = roundScreen) {
        item { PageHeader(title = "积分", onBack = onBack) }

        if (!loggedIn) {
            item { InfoPill { Text("登录后可以查看账号积分与用量") } }
            item { AccentPillButton(text = "去登录", onClick = onLogin) }
        } else {
            if (usage != null) {
                item { BalanceCard(usage) }
                usage.todayRequests?.let { count ->
                    item {
                        PillRow(
                            title = "今日调用",
                            subtitle = "本机时间 0 点起",
                            trailing = "$count 次"
                        )
                    }
                }
                if (usage.packs.size > 1) {
                    item { SectionLabel("积分包") }
                    items(usage.packs.size) { i -> PackRow(usage.packs[i]) }
                }
            }
            if (loading) {
                item { InfoPill { Text("正在加载积分…") } }
            }
            error?.let { message ->
                item {
                    InfoPill(
                        container = WatchPalette.ErrorContainer,
                        contentColor = WatchPalette.OnErrorContainer
                    ) { Text(message) }
                }
            }
            item {
                MutedPillButton(
                    text = if (loading) "加载中…" else "刷新",
                    enabled = !loading,
                    onClick = onReload
                )
            }
        }
    }
}

/** 余额主卡：大号数字 + 已用/总量 + 剩余占比进度条。 */
@Composable
private fun BalanceCard(usage: UsageSnapshot) {
    InfoPill {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "剩余积分",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                formatAmount(usage.left),
                style = MaterialTheme.typography.displaySmall,
                color = WatchPalette.Accent,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "共 ${formatAmount(usage.total)} · 已用 ${formatAmount(usage.used)}",
                style = MaterialTheme.typography.labelSmall,
                color = WatchPalette.OnPillVariant,
                maxLines = 1
            )
            if (usage.total > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (usage.left / usage.total).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = WatchPalette.Accent,
                    trackColor = WatchPalette.OutlineVariant
                )
            }
        }
    }
}

@Composable
private fun PackRow(pack: UsagePack) {
    PillRow(
        title = pack.name,
        subtitle = pack.expireAt?.let { "到期 $it" } ?: statusLabel(pack.status),
        trailing = "剩 ${formatAmount(pack.left)}"
    )
}

private fun statusLabel(status: String?): String? = when (status?.lowercase()) {
    "valid" -> "有效"
    "usedup" -> "已用完"
    "expired" -> "已过期"
    else -> null
}

/** 整数不带小数点，有小数时保留一位，避免「500.0 积分」这种别扭写法。 */
private fun formatAmount(value: Double): String {
    val rounded = value.roundToInt()
    return if (abs(value - rounded) < 0.01) rounded.toString()
    else String.format(Locale.US, "%.1f", value)
}
