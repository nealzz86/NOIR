/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.cli

import app.noir.core.Rom
import app.noir.core.RomKind
import app.noir.core.Tui
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuidanceTest {

    /** Every row of a box has to be exactly as wide as the border, CJK included. */
    private fun assertClosedBox(text: String, context: String) {
        val lines = text.lines().filter { it.isNotBlank() }
        val widths = lines.map { Tui.visibleWidth(it) }.distinct()
        assertEquals(1, widths.size, "$context has ragged rows: $widths\n$text")
        assertTrue(
            widths.single() <= Tui.terminalColumns(),
            "$context is ${widths.single()} columns wide on a ${Tui.terminalColumns()} column terminal"
        )
    }

    @Test
    fun `the device guide box closes on every line`() {
        assertClosedBox(Guidance.deviceGuide(), "deviceGuide")
    }

    @Test
    fun `the about box closes on every line`() {
        assertClosedBox(Guidance.about("0.1.0-test"), "about")
    }

    @Test
    fun `error boxes close with and without a hint`() {
        assertClosedBox(Guidance.error("没有可用设备。"), "error title only")
        assertClosedBox(Guidance.error("没有可用设备。", Guidance.deviceGuide()), "error with guide hint")
    }

    @Test
    fun `the silent rejection note names the coloros switch`() {
        val coloros = Rom.detect(mapOf("ro.product.brand" to "OPPO", "ro.build.version.opporom" to "V17.0.1.2"))
        assertEquals(RomKind.COLOROS, coloros.kind)

        val note = Guidance.silentRejectNote(coloros)
        assertTrue(note.contains("ColorOS"), note)
        assertTrue(note.contains("权限监控"), note)
        assertTrue(note.contains("verify.txt"), note)

        val aosp = Guidance.silentRejectNote(Rom.detect(mapOf("ro.product.brand" to "google")))
        assertTrue(aosp.contains("状态没有变化"), aosp)
    }

    @Test
    fun `the adblock note is honest about what has not been verified`() {
        val note = Guidance.adblockNote(Rom.detect(mapOf("ro.product.brand" to "OPPO")))
        assertTrue(note.contains("真机"), note)
        assertTrue(note.contains("pm enable"), note)
        assertTrue(note.contains("别碰"), note)
    }
}
