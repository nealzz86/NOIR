/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RomTest {

    private fun props(vararg pairs: Pair<String, String>) = mapOf(*pairs)

    @Test
    fun `reads a getprop dump`() {
        val dump = """
            |[ro.build.version.release]: [17]
            |[ro.build.version.opporom]: [V17.0.1.2]
            |[ro.product.brand]: [OPPO]
            |[invalid line]
        """.trimMargin()

        val parsed = Rom.parseGetprop(dump)
        assertEquals("17", parsed["ro.build.version.release"])
        assertEquals("V17.0.1.2", parsed["ro.build.version.opporom"])
        assertEquals("OPPO", parsed["ro.product.brand"])
        assertEquals(3, parsed.size)
    }

    @Test
    fun `property keys are lowercased so lookups are stable`() {
        val parsed = Rom.parseGetprop("[RO.Product.Brand]: [OPPO]")
        assertEquals("OPPO", parsed["ro.product.brand"])
    }

    @Test
    fun `coloros 17 on an oppo build`() {
        val rom = Rom.detect(
            props(
                "ro.build.version.release" to "17",
                "ro.build.version.opporom" to "V17.0.1.2",
                "ro.product.brand" to "OPPO"
            )
        )
        assertEquals(RomKind.COLOROS, rom.kind)
        assertEquals("17", rom.version)
        assertEquals("ColorOS 17", rom.display)
        assertTrue(rom.isColorOsFamily)
    }

    @Test
    fun `oneplus uses the oplus property instead of the oppo one`() {
        val rom = Rom.detect(
            props(
                "ro.build.version.oplusrom" to "V17.0.0.30",
                "ro.product.brand" to "OnePlus"
            )
        )
        assertEquals(RomKind.COLOROS, rom.kind)
        assertEquals("ColorOS 17", rom.display)
    }

    @Test
    fun `realme is treated as the coloros family`() {
        val rom = Rom.detect(
            props(
                "ro.build.version.realmeui" to "realme UI 7.0",
                "ro.product.brand" to "realme"
            )
        )
        assertEquals(RomKind.COLOROS, rom.kind)
        assertEquals("7", rom.version)
    }

    @Test
    fun `a coloros brand without a version property is still coloros`() {
        val rom = Rom.detect(props("ro.product.brand" to "OPPO"))
        assertEquals(RomKind.COLOROS, rom.kind)
        assertEquals("ColorOS", rom.display)
    }

    @Test
    fun `miui version code V140 is 14`() {
        val rom = Rom.detect(
            props(
                "ro.miui.ui.version.name" to "V140",
                "ro.product.brand" to "Xiaomi"
            )
        )
        assertEquals(RomKind.MIUI, rom.kind)
        assertEquals("MIUI 14", rom.display)
        assertTrue(rom.isXiaomiFamily)
    }

    @Test
    fun `miui half versions keep the fraction`() {
        val rom = Rom.detect(
            props(
                "ro.miui.ui.version.name" to "V125",
                "ro.product.brand" to "Redmi"
            )
        )
        assertEquals(RomKind.MIUI, rom.kind)
        assertEquals("12.5", rom.version)
    }

    @Test
    fun `hyperos wins over miui when both properties are present`() {
        val rom = Rom.detect(
            props(
                "ro.miui.ui.version.name" to "V800",
                "ro.build.version.full" to "HyperOS 2.0.10",
                "ro.product.brand" to "Xiaomi"
            )
        )
        assertEquals(RomKind.HYPEROS, rom.kind)
        assertEquals("HyperOS 2", rom.display)
        assertTrue(rom.isXiaomiFamily)
    }

    @Test
    fun `originos version comes from the vivo property`() {
        val rom = Rom.detect(
            props(
                "ro.vivo.os.version" to "15.0.0.0",
                "ro.product.brand" to "vivo"
            )
        )
        assertEquals(RomKind.ORIGINOS, rom.kind)
        assertEquals("OriginOS 15", rom.display)
    }

    @Test
    fun `iqoo keeps its own capitalisation`() {
        val rom = Rom.detect(
            props("ro.vivo.os.version" to "15.0.0.0", "ro.product.brand" to "iQOO")
        )
        assertEquals("iQOO", rom.brand)
    }

    @Test
    fun `one ui five digit code splits major and minor`() {
        val seven = Rom.detect(
            props("ro.build.version.oneui" to "70000", "ro.product.brand" to "samsung")
        )
        val sevenOne = Rom.detect(
            props("ro.build.version.oneui" to "70100", "ro.product.brand" to "samsung")
        )
        assertEquals(RomKind.ONEUI, seven.kind)
        assertEquals("One UI 7", seven.display)
        assertEquals("7.1", sevenOne.version)
    }

    @Test
    fun `emui and harmony are told apart by the property they carry`() {
        assertEquals(
            RomKind.EMUI,
            Rom.detect(props("ro.build.version.emui" to "EmotionUI_13.0", "ro.product.brand" to "HUAWEI")).kind
        )
        assertEquals(
            RomKind.HARMONY,
            Rom.detect(
                props(
                    "ro.build.version.emui" to "HarmonyOS_4.0.0.22",
                    "ro.product.brand" to "HUAWEI"
                )
            ).kind
        )
    }

    @Test
    fun `honor is magicos even without a version property`() {
        val rom = Rom.detect(props("ro.product.brand" to "HONOR"))
        assertEquals(RomKind.MAGICOS, rom.kind)
    }

    @Test
    fun `pixel reports aosp and hides the android label behind the version`() {
        val rom = Rom.detect(props("ro.product.brand" to "google"))
        assertEquals(RomKind.AOSP, rom.kind)
        assertEquals("Android", rom.display)
    }

    @Test
    fun `an unknown brand stays unknown but is still named`() {
        val rom = Rom.detect(props("ro.product.brand" to "nothing"))
        assertEquals(RomKind.UNKNOWN, rom.kind)
        assertEquals("Nothing （未识别 ROM）", rom.display)
    }

    @Test
    fun `the brand falls back to the fingerprint when the product props are empty`() {
        val rom = Rom.detect(
            props("ro.build.fingerprint" to "PLK110/PLK110:16/UKQ1.240411.002")
        )
        assertEquals("PLK110", rom.brand)
        assertEquals(RomKind.UNKNOWN, rom.kind)
        assertEquals("PLK110 （未识别 ROM）", rom.display)
    }

    @Test
    fun `nothing at all detected is a bare unknown label`() {
        val rom = Rom.detect(emptyMap())
        assertEquals(RomKind.UNKNOWN, rom.kind)
        assertEquals("未知 ROM", rom.display)
    }
}
