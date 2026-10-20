package com.workbuddy.watch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 参考图（Tenue Browser）取色：**纯黑底 + 中性灰药丸卡片 + 淡紫蓝强调色**。
 *
 * 刻意不用动态取色：参考图是「纯黑 + 灰药丸」的强风格，动态色会把它冲掉；
 * 而且表盘上低彩度更省电、对比度更稳。
 */
object WatchPalette {
    /** 屏幕底色，纯黑（AMOLED 省电，也贴合参考图）。 */
    val Background = Color(0xFF000000)

    /** 药丸卡片：列表项、内容块。 */
    val Pill = Color(0xFF2C2C2C)

    /** 更暗的药丸：输入框等次级容器。 */
    val PillDim = Color(0xFF1B1B1B)

    /** 更亮的药丸：选中态、强调容器。 */
    val PillHigh = Color(0xFF3A4152)

    val OnPill = Color(0xFFF2F2F2)
    val OnPillVariant = Color(0xFFB0B0B0)

    /** 强调色：参考图底部那颗淡紫蓝按钮。 */
    val Accent = Color(0xFFB8C5FA)
    val OnAccent = Color(0xFF101B36)

    val Outline = Color(0xFF5C5C5C)
    val OutlineVariant = Color(0xFF333333)

    /** 分页圆点：未选中。 */
    val DotUnselected = Color(0xFF565656)

    val Error = Color(0xFFFFB4AB)
    val ErrorDim = Color(0xFF8C1D18)
    val ErrorContainer = Color(0xFF3B1210)
    val OnError = Color(0xFF601410)
    val OnErrorContainer = Color(0xFFFFDAD6)
}
