/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassifierTest {

    @Test
    fun `known packages win over keyword guesses`() {
        assertEquals(Category.TELEMETRY, Classifier.category("com.miui.analytics", emptyList(), emptyList()))
        assertEquals(Category.SECURITY, Classifier.category("com.miui.securitycenter", emptyList(), emptyList()))
        assertEquals(Category.SOCIAL, Classifier.category("com.tencent.mm", emptyList(), emptyList()))
        assertEquals(Category.FINANCE, Classifier.category("com.eg.android.AlipayGphone", emptyList(), emptyList()))
        assertEquals(Category.SHOPPING, Classifier.category("com.taobao.taobao", emptyList(), emptyList()))
    }

    @Test
    fun `token boundaries prevent statementservice being called telemetry`() {
        val category = Classifier.category("com.android.statementservice", emptyList(), emptyList(), isSystem = true)
        assertEquals(Category.SYSTEM, category)

        val qti = Classifier.category("com.qti.qualcomm.datastatusnotification", emptyList(), emptyList(), isSystem = true)
        assertEquals(Category.SYSTEM, qti)
    }

    @Test
    fun `telemetry is detected from endpoint paths`() {
        val category = Classifier.category(
            "com.example.unknown",
            listOf("https://tracking.example.com/track/v1"),
            emptyList()
        )
        assertEquals(Category.TELEMETRY, category)
    }

    @Test
    fun `unknown system packages fall back to system instead of other`() {
        assertEquals(Category.SYSTEM, Classifier.category("com.unknown.system", emptyList(), emptyList(), isSystem = true))
        assertEquals(Category.OTHER, Classifier.category("com.unknown.user", emptyList(), emptyList(), isSystem = false))
    }

    @Test
    fun `vendor prefers the package name over embedded sdk domains`() {
        assertEquals("Tencent", Classifier.vendor("com.tencent.mm", listOf("https://schemas.android.com/aapt", "https://google.com/x")))
        assertEquals("ByteDance", Classifier.vendor("com.ss.android.ugc.aweme", listOf("https://lf-3.ecombdstatic.com/x")))
        assertEquals("Alibaba", Classifier.vendor("com.taobao.taobao", listOf("https://register.xmpush.xiaomi.com/x")))
        assertEquals("Alibaba", Classifier.vendor("com.eg.android.AlipayGphone", listOf("http://schemas.android.com/apk/res/android")))
        assertEquals("Pinduoduo", Classifier.vendor("com.xunmeng.pinduoduo", listOf("http://schemas.android.com/apk/res/android")))
        assertEquals("Meituan", Classifier.vendor("com.sankuai.meituan", listOf("https://maps.google.com/x")))
        assertEquals("OPPO", Classifier.vendor("com.heytap.mcs", listOf("https://connect.rom.miui.com/generate_204")))
    }

    @Test
    fun `vendor ignores infrastructure hosts`() {
        assertNull(
            Classifier.vendor(
                "com.example.app",
                listOf("http://schemas.android.com/apk/res/android", "https://github.com/google/gson")
            )
        )
    }

    @Test
    fun `vendor falls back to domain suffix scoring`() {
        assertEquals(
            "Baidu",
            Classifier.vendor("com.example.map", listOf("https://api.map.baidu.com/x", "https://bdstatic.com/y"))
        )
        assertEquals(
            "Tencent",
            Classifier.vendor(
                "com.example.chat",
                listOf("https://a.qq.com/x", "https://b.tencent.com/y", "https://c.example.org/z")
            )
        )
    }

    @Test
    fun `vendor detection uses domains`() {
        assertEquals("Xiaomi", Classifier.vendor("com.example", listOf("https://api.sec.miui.com/x")))
        assertEquals("Alibaba", Classifier.vendor("com.example", listOf("https://cgicol.amap.com/x")))
    }

    @Test
    fun `coloros components are categorised without relying on endpoints`() {
        assertEquals(Category.TELEMETRY, Classifier.category("com.coloros.analytics", emptyList(), emptyList()))
        assertEquals(Category.TELEMETRY, Classifier.category("com.nearme.statistics.rom", emptyList(), emptyList()))
        assertEquals(Category.TELEMETRY, Classifier.category("com.heytap.openid", emptyList(), emptyList()))
        assertEquals(Category.TELEMETRY, Classifier.category("com.oplus.deepthinker", emptyList(), emptyList()))
        assertEquals(Category.PUSH, Classifier.category("com.heytap.mcs", emptyList(), emptyList()))
        assertEquals(Category.GAME, Classifier.category("com.nearme.gamecenter", emptyList(), emptyList()))
        assertEquals(Category.APP_STORE, Classifier.category("com.heytap.market", emptyList(), emptyList()))
    }

    @Test
    fun `every coloros prefix maps to the oppo vendor`() {
        listOf(
            "com.heytap.mcs",
            "com.oplus.deepthinker",
            "com.coloros.analytics",
            "com.oppo.ota",
            "com.nearme.gamecenter"
        ).forEach { pkg ->
            assertEquals("OPPO", Classifier.vendor(pkg, emptyList()), pkg)
        }
    }

    @Test
    fun `scoring rewards upload and command endpoints`() {
        val base = Rules.score(listOf("INTERNET", "READ_SMS"), emptyList(), false, false)
        val withUpload = Rules.score(listOf("INTERNET", "READ_SMS"), listOf("https://a.example.com/upload"), false, false)
        val withCommand = Rules.score(listOf("INTERNET", "READ_SMS"), listOf("https://a.example.com/api/command/query"), false, false)
        assertTrue(withUpload > base)
        assertTrue(withCommand > base)
    }
}
