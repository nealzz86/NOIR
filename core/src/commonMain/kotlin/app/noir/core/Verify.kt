/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

enum class Verdict(val label: String) {
    EFFECTIVE("已生效"),
    NO_EFFECT("未生效"),
    REJECTED("被拒绝"),
    UNVERIFIED("无法确认")
}

data class CheckResult(
    val subject: String,
    val command: String,
    val verdict: Verdict,
    val detail: String = ""
)

/**
 * Reads the device back after a change and says whether it actually took.
 *
 * A zero exit code is not proof: OEM skins answer `cmd appops set` with success
 * while leaving the mode untouched, so every check here compares the state the
 * device reports now against the state the command was supposed to produce.
 */
object Verify {

    private val ENABLED_SETTING = Regex("mEnabledSetting=(\\d+)")

    /** `PROJECT_MEDIA: ignore` -> `ignore`; anything unparseable -> null. */
    fun appOpMode(output: String, op: String): String? =
        Regex("(?m)^\\s*${Regex.escape(op)}:\\s*([a-zA-Z_]+)").find(output)?.groupValues?.get(1)

    fun appOp(expectedMode: String, afterMode: String?, exitOk: Boolean): Verdict = when {
        !exitOk -> Verdict.REJECTED
        afterMode == null -> Verdict.UNVERIFIED
        afterMode != expectedMode -> Verdict.NO_EFFECT
        else -> Verdict.EFFECTIVE
    }

    fun alreadyWas(expectedMode: String, beforeMode: String?): Boolean = beforeMode == expectedMode

    /** Cross-checks `pm list packages -d`, the authoritative disabled list. */
    fun disabled(listOutput: String, pkg: String, exitOk: Boolean): Verdict {
        if (!exitOk) return Verdict.REJECTED
        val found = listOutput.lineSequence().any { it.trim() == "package:$pkg" }
        return if (found) Verdict.EFFECTIVE else Verdict.NO_EFFECT
    }

    /** `pm disable-user` reports the new state inline; used when the list is unavailable. */
    fun disabledByMessage(output: String): Verdict = when {
        output.contains("new state: disabled", ignoreCase = true) -> Verdict.EFFECTIVE
        output.contains("new state: enabled", ignoreCase = true) -> Verdict.NO_EFFECT
        output.contains("not found", ignoreCase = true) -> Verdict.REJECTED
        else -> Verdict.UNVERIFIED
    }

    fun enabledSetting(dumpsysPackageOutput: String): String? =
        ENABLED_SETTING.find(dumpsysPackageOutput)?.groupValues?.get(1)

    /** Android encodes component state as a number; reports need the word. */
    fun enabledStateLabel(setting: String?): String = when (setting) {
        "0" -> "default"
        "1" -> "enabled"
        "2" -> "disabled"
        "3" -> "disabled-user"
        null -> "未知"
        else -> "setting=$setting"
    }

    fun isDisabledState(setting: String?): Boolean = setting == "2" || setting == "3"

    /** The state a revert asks for: `0` defers to the manifest, `1` is explicitly on. */
    fun enabled(setting: String?): Boolean = setting == "0" || setting == "1"

    fun counts(results: List<CheckResult>): Map<Verdict, Int> =
        results.groupingBy { it.verdict }.eachCount()

    fun summary(results: List<CheckResult>): String {
        if (results.isEmpty()) return "没有需要复核的动作"
        val counts = counts(results)
        return Verdict.entries
            .filter { (counts[it] ?: 0) > 0 || it == Verdict.EFFECTIVE }
            .joinToString(" · ") { "${it.label} ${counts[it] ?: 0}" }
    }

    fun text(result: CheckResult): String =
        if (result.detail.isBlank()) result.verdict.label else "${result.verdict.label}  ${result.detail}"
}
