package com.workbuddy.watch.data

/**
 * 账号积分用量。数据来自 web 端「套餐与积分」页（`/profile/plans-usage`）所用的
 * `/billing/meter` 下的接口。
 *
 * 服务端把不同来源的积分放在「资源包」里（每个包有总量与剩余量），
 * 界面上的总积分就是这些包的和。
 */
data class UsagePack(
    val name: String,
    val total: Double,
    val left: Double,
    /** 服务端状态原值（valid / usedUp / expired…）。 */
    val status: String? = null,
    /** 到期时间原始字符串，服务端用的格式是 `yyyy-MM-dd HH:mm:ss`。 */
    val expireAt: String? = null
)

data class UsageSnapshot(
    val packs: List<UsagePack>,
    /** 今日请求次数；接口没返回时为 null。 */
    val todayRequests: Int? = null
) {
    val total: Double get() = packs.sumOf { it.total }
    val left: Double get() = packs.sumOf { it.left }
    val used: Double get() = (total - left).coerceAtLeast(0.0)
}
