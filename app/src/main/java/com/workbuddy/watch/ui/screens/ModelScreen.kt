package com.workbuddy.watch.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.workbuddy.watch.data.RemoteModel
import com.workbuddy.watch.ui.components.InfoPill
import com.workbuddy.watch.ui.components.MutedPillButton
import com.workbuddy.watch.ui.components.PageHeader
import com.workbuddy.watch.ui.components.PillRow
import com.workbuddy.watch.ui.components.WatchPage
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 模型选择页。清单来自 `GET /console/enterprises/{enterpriseId}/models`，
 * 与网页端下拉里的模型一致；选中后写进 [com.workbuddy.watch.data.CloudSettings.selectedModel]，
 * 后续下达任务会带上这个模型 ID。
 */
@Composable
fun ModelScreen(
    roundScreen: Boolean,
    models: List<RemoteModel>,
    loading: Boolean,
    error: String?,
    selected: String,
    onSelect: (String) -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit
) {
    WatchPage(roundScreen) {
        item { PageHeader("选择模型", onBack) }
        item {
            InfoPill {
                Text(
                    "下发新任务时使用的模型，与网页端可选列表一致。",
                    style = MaterialTheme.typography.labelSmall,
                    color = WatchPalette.OnPillVariant
                )
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
                        Text("正在获取模型列表…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        error?.takeIf { !loading }?.let { message ->
            item {
                PillRow(
                    title = "获取失败",
                    subtitle = message,
                    leadingIcon = Icons.Filled.Warning,
                    trailing = "重试",
                    onClick = onReload
                )
            }
        }

        if (!loading) {
            // 空 ID = 交给云端按账号默认模型执行
            item {
                PillRow(
                    title = "云端默认",
                    subtitle = "由云端按账号设置自动选择",
                    leadingIcon = Icons.Filled.Star,
                    selected = selected.isBlank(),
                    trailing = if (selected.isBlank()) "✓" else null,
                    onClick = { onSelect("") }
                )
            }
            items(models) { m ->
                PillRow(
                    title = m.name,
                    subtitle = m.description ?: m.id,
                    leadingIcon = Icons.Filled.Info,
                    selected = m.id == selected,
                    trailing = if (m.id == selected) "✓" else null,
                    onClick = { onSelect(m.id) }
                )
            }
            item { MutedPillButton(text = "重新获取", onClick = onReload) }
        }
    }
}
