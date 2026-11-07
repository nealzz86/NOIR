/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdTargetsTest {

    private val colorosInstalled = listOf(
        "com.coloros.analytics",
        "com.heytap.market",
        "com.oppo.ota",
        "com.oplus.gallery",
        "com.nearme.splash"
    )

    @Test
    fun `the never tier is never actionable`() {
        (AdTargets.table(RomKind.COLOROS) + AdTargets.table(RomKind.HYPEROS))
            .filter { it.tier == AdTier.NEVER }
            .forEach { assertFalse(it.actionable, "${it.pkg} must never be offered for disable") }

        AdTargets.onDevice(colorosInstalled, RomKind.COLOROS)
            .filter { it.tier == AdTier.NEVER }
            .forEach { assertFalse(it.actionable) }
    }

    @Test
    fun `only packages actually installed are proposed`() {
        val pkgs = AdTargets.onDevice(colorosInstalled, RomKind.COLOROS).map { it.pkg }
        assertEquals(listOf("com.coloros.analytics", "com.heytap.market", "com.nearme.splash", "com.oppo.ota"), pkgs)
    }

    @Test
    fun `ordering puts the safe tier first and the untouchable tier last`() {
        val tiers = AdTargets.onDevice(colorosInstalled, RomKind.COLOROS).map { it.tier }
        assertEquals(listOf(AdTier.SAFE, AdTier.CAUTION, AdTier.CAUTION, AdTier.NEVER), tiers)
    }

    @Test
    fun `a curated row is not duplicated by the pattern guess`() {
        val market = AdTargets.onDevice(colorosInstalled, RomKind.COLOROS)
            .filter { it.pkg == "com.heytap.market" }
        assertEquals(1, market.size)
        assertEquals("清单", market.single().source)
    }

    @Test
    fun `unknown packages are still matched and labelled as guesses`() {
        val splash = AdTargets.onDevice(colorosInstalled, RomKind.COLOROS)
            .single { it.pkg == "com.nearme.splash" }
        assertEquals("推断", splash.source)
        assertEquals(AdTier.CAUTION, splash.tier)
        assertTrue(splash.channel.contains("推断"))
    }

    @Test
    fun `roms without a table fall back to patterns only`() {
        assertEquals(emptyList(), AdTargets.table(RomKind.AOSP))
        val targets = AdTargets.onDevice(listOf("com.miui.analytics", "com.example.shop"), RomKind.AOSP)
        assertEquals(listOf("com.miui.analytics"), targets.map { it.pkg })
        assertEquals("推断", targets.single().source)
    }

    @Test
    fun `the xiaomi table is used for both miui and hyperos`() {
        assertTrue(AdTargets.table(RomKind.MIUI).isNotEmpty())
        assertEquals(AdTargets.table(RomKind.MIUI), AdTargets.table(RomKind.HYPEROS))
    }

    @Test
    fun `ordinary apps are not guessed as ad components`() {
        assertNull(AdTargets.heuristic("com.android.settings"))
        assertNull(AdTargets.heuristic("com.tencent.mm"))
        assertNull(AdTargets.heuristic("com.example.photos"))
        assertNull(AdTargets.heuristic("com.spotify.music"))
    }

    @Test
    fun `ad networks and oaid services are guessed`() {
        assertEquals(AdTier.CAUTION, AdTargets.heuristic("com.company.ads")?.tier)
        assertEquals(AdTier.CAUTION, AdTargets.heuristic("com.vendor.ad.sdk")?.tier)
        assertEquals(AdTier.CAUTION, AdTargets.heuristic("com.baidu.mobads")?.tier)
        assertEquals(AdTier.CAUTION, AdTargets.heuristic("com.heytap.ums")?.tier)
        assertEquals(AdTier.CAUTION, AdTargets.heuristic("com.alibaba.openid")?.tier)
    }

    @Test
    fun `a label that merely contains ad is not a guess`() {
        assertNull(AdTargets.heuristic("com.adobe.reader"))
        assertNull(AdTargets.heuristic("com.example.metadata"))
        assertNull(AdTargets.heuristic("com.download.manager"))
    }

    @Test
    fun `every curated package is a valid android package name`() {
        (AdTargets.table(RomKind.COLOROS) + AdTargets.table(RomKind.MIUI)).forEach { target ->
            assertTrue(
                target.pkg.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$", RegexOption.IGNORE_CASE)),
                "${target.pkg} is not a package name"
            )
            assertTrue(target.name.isNotBlank())
            assertTrue(target.channel.isNotBlank())
        }
    }
}
