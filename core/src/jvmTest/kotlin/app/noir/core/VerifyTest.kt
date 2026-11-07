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

class VerifyTest {

    @Test
    fun `reads the mode out of an appops get`() {
        assertEquals("ignore", Verify.appOpMode("PROJECT_MEDIA: ignore", "PROJECT_MEDIA"))
        assertEquals(
            "allow",
            Verify.appOpMode("GET_USAGE_STATS: allow: reject, time=\"2026-11-06\"", "GET_USAGE_STATS")
        )
        assertNull(Verify.appOpMode("No operations.", "PROJECT_MEDIA"))
        assertNull(Verify.appOpMode("", "PROJECT_MEDIA"))
    }

    @Test
    fun `a successful exit code with an unchanged mode is no effect`() {
        // The ColorOS case: `cmd appops set` prints nothing and exits 0, mode stays default.
        assertEquals(
            Verdict.NO_EFFECT,
            Verify.appOp(expectedMode = "ignore", afterMode = "default", exitOk = true)
        )
    }

    @Test
    fun `the expected mode makes it effective`() {
        assertEquals(
            Verdict.EFFECTIVE,
            Verify.appOp(expectedMode = "ignore", afterMode = "ignore", exitOk = true)
        )
    }

    @Test
    fun `a rejected command is reported as rejected even when the mode matches`() {
        assertEquals(
            Verdict.REJECTED,
            Verify.appOp(expectedMode = "ignore", afterMode = "ignore", exitOk = false)
        )
        assertEquals(
            Verdict.REJECTED,
            Verify.appOp(expectedMode = "ignore", afterMode = null, exitOk = false)
        )
    }

    @Test
    fun `an unreadable state cannot be confirmed`() {
        assertEquals(
            Verdict.UNVERIFIED,
            Verify.appOp(expectedMode = "ignore", afterMode = null, exitOk = true)
        )
    }

    @Test
    fun `a mode that was already the target did not need changing`() {
        assertTrue(Verify.alreadyWas("ignore", "ignore"))
        assertFalse(Verify.alreadyWas("ignore", "default"))
        assertFalse(Verify.alreadyWas("ignore", null))
    }

    @Test
    fun `the disabled list is authoritative`() {
        val list = "package:com.coloros.analytics\npackage:com.heytap.market\n"
        assertEquals(Verdict.EFFECTIVE, Verify.disabled(list, "com.coloros.analytics", true))
        assertEquals(Verdict.NO_EFFECT, Verify.disabled(list, "com.oppo.ota", true))
        assertEquals(Verdict.REJECTED, Verify.disabled("", "com.coloros.analytics", false))
    }

    @Test
    fun `a prefix must not match another package`() {
        val list = "package:com.heytap.market\n"
        assertEquals(Verdict.NO_EFFECT, Verify.disabled(list, "com.heytap.marke", true))
    }

    @Test
    fun `disable-user reports its own result inline`() {
        assertEquals(Verdict.EFFECTIVE, Verify.disabledByMessage("Package com.x new state: disabled-user"))
        assertEquals(Verdict.NO_EFFECT, Verify.disabledByMessage("Package com.x new state: enabled"))
        assertEquals(Verdict.REJECTED, Verify.disabledByMessage("Package com.x not found"))
        assertEquals(Verdict.UNVERIFIED, Verify.disabledByMessage(""))
    }

    @Test
    fun `enabled setting is decoded for reports`() {
        val dump = """
            |Packages:
            |  Package [com.heytap.market] (abc):
            |    mEnabledSetting=3
            |    enabledComponents=
        """.trimMargin()
        assertEquals("3", Verify.enabledSetting(dump))
        assertEquals("disabled-user", Verify.enabledStateLabel("3"))
        assertEquals("disabled", Verify.enabledStateLabel("2"))
        assertEquals("enabled", Verify.enabledStateLabel("1"))
        assertEquals("default", Verify.enabledStateLabel("0"))
        assertEquals("未知", Verify.enabledStateLabel(null))
        assertTrue(Verify.isDisabledState("3"))
        assertFalse(Verify.isDisabledState("1"))
    }

    @Test
    fun `a revert asks for the enabled half of the same field`() {
        assertTrue(Verify.enabled("0"))
        assertTrue(Verify.enabled("1"))
        assertFalse(Verify.enabled("2"))
        assertFalse(Verify.enabled("3"))
        assertFalse(Verify.enabled(null))
    }

    @Test
    fun `summary counts every verdict and always shows what worked`() {
        val checks = listOf(
            CheckResult("a", "cmd a", Verdict.EFFECTIVE),
            CheckResult("b", "cmd b", Verdict.NO_EFFECT, "当前模式 default"),
            CheckResult("c", "cmd c", Verdict.NO_EFFECT),
            CheckResult("d", "cmd d", Verdict.REJECTED)
        )
        assertEquals(
            "已生效 1 · 未生效 2 · 被拒绝 1",
            Verify.summary(checks)
        )
        assertEquals("没有需要复核的动作", Verify.summary(emptyList()))
    }

    @Test
    fun `text puts the detail next to the verdict`() {
        assertEquals("未生效  当前模式 default", Verify.text(CheckResult("a", "c", Verdict.NO_EFFECT, "当前模式 default")))
        assertEquals("已生效", Verify.text(CheckResult("a", "c", Verdict.EFFECTIVE)))
    }
}
