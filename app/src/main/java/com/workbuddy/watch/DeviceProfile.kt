package com.workbuddy.watch

import android.content.Context

/**
 * 华为儿童手表 4X 的屏幕特征：320×320 px、192 dpi（约 267dp 方形屏）。
 *
 * 这台设备是**单点触控**，且系统自带「右滑退出」。因此这个分支需要：
 *  - 方形布局，不再走圆屏的圆形内切留白
 *  - 去掉所有横向手势（翻页 / 横滑返回），只保留纵向滚动 + 点按
 *  - 内容右侧多留一点，避免从右边缘起手时被系统手势抢走
 */
object DeviceProfile {

    // 4X 的物理分辨率固定 320×320；densityDpi 标称 192，部分 ROM 会报成 213 一类的相近值，
    // 所以这里按「320 方形 + 小屏密度区间」判定，避免因为密度取整而漏判。
    private const val SQUARE_PX = 320
    private val DPI_RANGE = 160..240

    fun isHuaweiWatch4x(context: Context): Boolean {
        val dm = context.resources.displayMetrics
        return dm.widthPixels == SQUARE_PX &&
            dm.heightPixels == SQUARE_PX &&
            dm.densityDpi in DPI_RANGE
    }
}
