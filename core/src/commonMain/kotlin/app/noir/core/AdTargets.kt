/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

enum class AdTier(val label: String) {
    /** Turning this off costs a marketing surface, not a feature. */
    SAFE("可停用"),

    /** Carries ads, but also carries something the user may rely on. */
    CAUTION("谨慎"),

    /** Matched by the audit but must never be touched; shown for context only. */
    NEVER("别碰")
}

data class AdTarget(
    val pkg: String,
    val name: String,
    val channel: String,
    val tier: AdTier,
    /** `清单` for curated rows, `推断` for pattern matches on the device. */
    val source: String = "清单"
) {
    val actionable: Boolean get() = tier != AdTier.NEVER
}

/**
 * Per-ROM list of the components that actually deliver system advertising.
 *
 * The curated rows below are collected from public ColorOS / MIUI slimming
 * guides, not from a device NOIR has scanned, so a missing entry never hides a
 * finding: whatever is installed on the phone is also matched by package pattern
 * and reported as 推断. Unverified names are therefore only ever *proposed*.
 */
object AdTargets {

    private val COLOROS = listOf(
        AdTarget("com.coloros.analytics", "数据分析", "行为与使用数据上报", AdTier.SAFE),
        AdTarget("com.nearme.statistics.rom", "ROM 使用统计", "使用统计上报", AdTier.SAFE),
        AdTarget("com.nearme.instant.platform", "快应用平台", "快应用推送与广告", AdTier.SAFE),
        AdTarget("com.coloros.smartsidebar", "智能侧边栏", "侧边栏应用推荐位", AdTier.SAFE),
        AdTarget("com.nearme.gamecenter", "游戏中心", "游戏下载推荐、资讯推送", AdTier.CAUTION),
        AdTarget("com.heytap.market", "软件商店", "首页推荐位、更新通知里的应用推广", AdTier.CAUTION),
        AdTarget("com.heytap.browser", "浏览器", "首页资讯流与热榜", AdTier.CAUTION),
        AdTarget("com.heytap.openid", "设备标识服务（OAID）", "向三方 SDK 下发广告标识", AdTier.CAUTION),
        AdTarget("com.oplus.deepthinker", "智慧计算", "个性化推荐所依赖的用户画像", AdTier.CAUTION),
        AdTarget("com.heytap.mcs", "推送服务", "营销推送的下发通道", AdTier.CAUTION),
        AdTarget("com.oppo.ota", "系统更新", "更新组件", AdTier.NEVER),
        AdTarget("com.coloros.sau", "系统更新助手", "更新组件", AdTier.NEVER),
        AdTarget("com.nearme.romupdate", "ROM 更新", "更新组件", AdTier.NEVER),
        AdTarget("com.coloros.activation", "设备激活", "入网与激活", AdTier.NEVER),
        AdTarget("com.finshell.wallet", "钱包", "支付与卡包", AdTier.NEVER),
        AdTarget("com.coloros.securepay", "安全支付", "指纹与支付", AdTier.NEVER)
    )

    private val XIAOMI = listOf(
        AdTarget("com.miui.systemadsolution", "系统广告解决方案", "系统级广告开关后端", AdTier.SAFE),
        AdTarget("com.miui.analytics", "数据分析", "行为数据上报", AdTier.SAFE),
        AdTarget("com.miui.misightservice", "用户体验计划", "诊断与使用数据上报", AdTier.SAFE),
        AdTarget("com.xiaomi.security.onetrack", "OneTrack", "安全数据上报", AdTier.SAFE),
        AdTarget("com.miui.powerinsight", "功耗分析", "使用数据上报", AdTier.SAFE),
        AdTarget("com.miui.newhome", "智能助理资讯", "资讯流与推荐卡片", AdTier.CAUTION),
        AdTarget("com.miui.personalassistant", "负一屏", "智能卡片与应用推荐", AdTier.CAUTION),
        AdTarget("com.xiaomi.migameservice", "游戏服务", "游戏推荐与加速提示", AdTier.CAUTION),
        AdTarget("com.xiaomi.market", "应用商店", "下载后推荐位", AdTier.CAUTION),
        AdTarget("com.xiaomi.xmsf", "推送服务框架", "营销推送的下发通道", AdTier.NEVER),
        AdTarget("com.miui.securitycenter", "手机管家", "安全与权限管理", AdTier.NEVER)
    )

    /**
     * Segment-anchored on purpose: `ad` must be a whole label or the head of a
     * known ad token, so `com.adobe` and `metadata` are not proposed for disabling.
     */
    private val PATTERNS = listOf(
        Regex("(^|[./])(ads?|adx|advert|advertising|advertisement|ums|oms|splash|mobads|pangle|tanx)([.$/]|$)"),
        Regex("(analytics|statistic|statistics|onetrack|misight|logkit|deepthinker|profile)"),
        Regex("(openid|mdid|oaid)"),
        Regex("(gamecenter|migameservice|gameassistant)"),
        Regex("(instant|pictorial|magazine|newhome|personalassistant|smartsidebar|negative)"),
        Regex("(market|appstore|vending|softwarefetch)$")
    )

    fun table(kind: RomKind): List<AdTarget> = when {
        kind == RomKind.COLOROS -> COLOROS
        kind == RomKind.HYPEROS || kind == RomKind.MIUI -> XIAOMI
        else -> emptyList()
    }

    /**
     * Resolves what is really on the device: curated rows for the detected ROM,
     * plus pattern matches for anything the table has never seen.
     */
    fun onDevice(installed: Collection<String>, kind: RomKind): List<AdTarget> {
        val present = installed.toHashSet()
        val curated = table(kind).filter { it.pkg in present }
        val seen = curated.mapTo(HashSet()) { it.pkg }
        val inferred = present.asSequence()
            .filter { it !in seen }
            .mapNotNull { heuristic(it) }
            .toList()

        return (curated + inferred).sortedWith(
            compareBy<AdTarget> { tierRank(it.tier) }.thenBy { it.pkg }
        )
    }

    fun heuristic(pkg: String): AdTarget? {
        val lower = pkg.lowercase()
        if (PATTERNS.none { it.containsMatchIn(lower) }) return null
        return AdTarget(
            pkg = pkg,
            name = pkg.substringAfterLast('.').ifBlank { pkg },
            channel = "按包名推断，不在已验证清单内",
            tier = AdTier.CAUTION,
            source = "推断"
        )
    }

    private fun tierRank(tier: AdTier): Int = when (tier) {
        AdTier.SAFE -> 0
        AdTier.CAUTION -> 1
        AdTier.NEVER -> 2
    }
}
