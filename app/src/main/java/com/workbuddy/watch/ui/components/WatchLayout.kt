package com.workbuddy.watch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.ScreenScaffold
import com.workbuddy.watch.ui.theme.WatchPalette

/**
 * 列表型页面的骨架：`ScreenScaffold` + `ScalingLazyColumn`。
 *
 * 为什么用 Wear 的 `ScalingLazyColumn` 而不是普通 `LazyColumn`：它会按「离屏幕中心距离」
 * 对条目做缩放与透明渐变，配合圆屏能自然避开上下边缘，这是 Wear Material3 列表的招牌观感。
 *
 * [roundScreen] 决定水平留白：圆屏沿用 Wear 默认内边距（内容避开圆弧裁切），
 * 方屏则把留白收窄，让药丸卡片更贴近参考图里那种几乎满宽的排布。
 *
 * [bottomInset] 给底部额外留白（首页要给分页圆点让位）。
 */
@Composable
fun WatchPage(
    roundScreen: Boolean,
    scrollIndicator: Boolean = true,
    bottomInset: Dp = 0.dp,
    content: ScalingLazyListScope.() -> Unit
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        scrollIndicator = if (scrollIndicator) {
            { ScrollIndicator(state = listState) }
        } else {
            null
        }
    ) { padding ->
        // 圆屏把列表宽度收到内切正方形附近：ScalingLazyColumn 虽然会给边缘条目缩放，
        // 但缩放后的宽度仍可能超出圆弧，长文本就会在左右两侧被表盘裁掉。
        val listModifier = if (roundScreen) {
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(CIRCLE_SAFE_WIDTH)
        } else {
            Modifier.fillMaxSize()
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            ScalingLazyColumn(
                modifier = listModifier,
                state = listState,
                contentPadding = PaddingValues(
                    start = if (roundScreen) 0.dp else 6.dp,
                    end = if (roundScreen) 0.dp else 6.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + bottomInset
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 圆屏可用内容宽度系数：屏幕内切正方形约为 1/√2 ≈ 0.707，
 * 再留一点余量，保证滚动到任意位置时文字都不会被圆弧切掉。
 */
private const val CIRCLE_SAFE_WIDTH = 0.72f

/**
 * 表单 / 输入型页面的骨架：普通 `Column` + `verticalScroll`。
 *
 * 这类页面要放会唤起输入法的文本框，交给 [ScalingLazyColumn] 反而容易和焦点、滚动打架，
 * 所以这里退化为普通纵向滚动，只保留统一的圆屏内边距与顶部给时间让位。
 */
@Composable
fun WatchColumn(
    roundScreen: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = modifier
                .fillMaxHeight()
                .fillMaxWidth(if (roundScreen) CIRCLE_SAFE_WIDTH else 1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (roundScreen) 0.dp else 6.dp)
                .padding(top = 26.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

/**
 * 横向分页骨架：`HorizontalPager` + 底部自绘圆点，对齐参考图里「左右翻页、底部三个点」的样子。
 *
 * 圆点固定在底部居中、不随页面滚动，所以各页内容要自行留出 [PAGER_DOTS_INSET] 的底部空白。
 */
@Composable
fun WatchPager(
    pageCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { pageCount })
    Box(modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            content(page)
        }
        PagerDots(
            pageCount = pageCount,
            currentPage = pagerState.currentPage,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        )
    }
}

/** 分页圆点占用的底部高度，交给各页作为 `bottomInset`。 */
val PAGER_DOTS_INSET = 26.dp

/**
 * 参考图底部的分页圆点：当前页实心发白，其余暗灰。
 *
 * 用自绘而不是 Wear 的 `HorizontalPageIndicator`，是因为参考图里的圆点很小、
 * 间距紧、没有底槽，和 Wear 默认样式差别明显。
 */
@Composable
fun PagerDots(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                Modifier
                    .size(if (selected) 7.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (selected) Color.White else WatchPalette.DotUnselected)
            )
        }
    }
}
