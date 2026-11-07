/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.core

enum class RomKind(val label: String) {
    COLOROS("ColorOS"),
    HYPEROS("HyperOS"),
    MIUI("MIUI"),
    ORIGINOS("OriginOS"),
    HARMONY("HarmonyOS"),
    EMUI("EMUI"),
    MAGICOS("MagicOS"),
    ONEUI("One UI"),
    AOSP("Android"),
    UNKNOWN("未知 ROM")
}

data class RomInfo(
    val kind: RomKind = RomKind.UNKNOWN,
    val version: String = "",
    val rawVersion: String = "",
    val brand: String = ""
) {
    val display: String
        get() = when {
            version.isNotBlank() -> "${kind.label} $version"
            kind == RomKind.UNKNOWN && brand.isNotBlank() -> "$brand （未识别 ROM）"
            else -> kind.label
        }

    val isColorOsFamily: Boolean get() = kind == RomKind.COLOROS
    val isXiaomiFamily: Boolean get() = kind == RomKind.HYPEROS || kind == RomKind.MIUI
}

/**
 * Recognises the vendor skin from system properties.
 *
 * The rule tables in Advisor / Classifier were written against MIUI, so every
 * OEM-specific decision downstream needs this answer first. Property names that
 * vary by brand are tried in order rather than assumed.
 */
object Rom {

    private val COLOROS_KEYS = listOf(
        "ro.build.version.opporom",
        "ro.build.version.oplusrom",
        "ro.build.version.realmeui"
    )
    private val COLOROS_BRANDS = setOf("oppo", "oneplus", "realme")
    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
    private val VIVO_BRANDS = setOf("vivo", "iqoo")
    private val HUAWEI_BRANDS = setOf("huawei")
    private val HONOR_BRANDS = setOf("honor", "hihonor")
    private val SAMSUNG_BRANDS = setOf("samsung", "sm-")
    private val GOOGLE_BRANDS = setOf("google", "pixel")

    private val PROP_LINE = Regex("^\\[(?i)([^]]+)]:\\s*\\[(.*)]\\s*$")

    /** Parses a whole `getprop` dump (`[key]: [value]` per line). */
    fun parseGetprop(dump: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        dump.lineSequence().forEach { line ->
            val match = PROP_LINE.find(line) ?: return@forEach
            out[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
        }
        return out
    }

    fun detect(props: Map<String, String>): RomInfo {
        val brand = brandOf(props)
        val lower = brand.lowercase()
        fun first(keys: List<String>): String =
            keys.map { props[it].orBlank() }.firstOrNull { it.isNotBlank() }.orEmpty()

        val colorRaw = first(COLOROS_KEYS)
        if (colorRaw.isNotBlank() || COLOROS_BRANDS.any { lower.startsWith(it) }) {
            return RomInfo(RomKind.COLOROS, majorVersion(colorRaw), colorRaw, brand)
        }

        val miuiRaw = props["ro.miui.ui.version.name"].orBlank()
        if (miuiRaw.isNotBlank() || XIAOMI_BRANDS.any { lower.startsWith(it) }) {
            if (isHyperOs(miuiRaw, props)) {
                val raw = first(listOf("ro.build.version.full", "ro.build.display.id"))
                return RomInfo(RomKind.HYPEROS, majorVersion(raw), raw, brand)
            }
            return RomInfo(RomKind.MIUI, miuiVersion(miuiRaw), miuiRaw, brand)
        }

        val vivoRaw = first(listOf("ro.vivo.os.version", "ro.vivo.os.build.display.id"))
        if (vivoRaw.isNotBlank() || VIVO_BRANDS.any { lower.startsWith(it) }) {
            return RomInfo(RomKind.ORIGINOS, majorVersion(vivoRaw), vivoRaw, brand)
        }

        val harmonyRaw = first(listOf("ro.harmonyos.version", "ro.build.version.harmony"))
        if (harmonyRaw.isNotBlank()) {
            return RomInfo(RomKind.HARMONY, majorVersion(harmonyRaw), harmonyRaw, brand)
        }

        val emuiRaw = props["ro.build.version.emui"].orBlank()
        if (emuiRaw.isNotBlank() || HUAWEI_BRANDS.any { lower.startsWith(it) }) {
            if (emuiRaw.contains("harmony", ignoreCase = true)) {
                return RomInfo(RomKind.HARMONY, majorVersion(emuiRaw), emuiRaw, brand)
            }
            return RomInfo(RomKind.EMUI, majorVersion(emuiRaw), emuiRaw, brand)
        }

        val magicRaw = props["ro.build.version.magic"].orBlank()
        if (magicRaw.isNotBlank() || HONOR_BRANDS.any { lower.startsWith(it) }) {
            return RomInfo(RomKind.MAGICOS, majorVersion(magicRaw), magicRaw, brand)
        }

        val oneuiRaw = props["ro.build.version.oneui"].orBlank()
        if (oneuiRaw.isNotBlank() || SAMSUNG_BRANDS.any { lower.startsWith(it) }) {
            return RomInfo(RomKind.ONEUI, oneuiVersion(oneuiRaw), oneuiRaw, brand)
        }

        if (GOOGLE_BRANDS.any { lower.startsWith(it) }) {
            return RomInfo(RomKind.AOSP, brand = brand)
        }

        // Nothing matched: keep whatever the fingerprint told us, so the report is honest.
        return RomInfo(RomKind.UNKNOWN, brand = brand)
    }

    private fun isHyperOs(miuiRaw: String, props: Map<String, String>): Boolean =
        miuiRaw.startsWith("V8") ||
            props["ro.build.display.id"].orBlank().contains("hyperos", ignoreCase = true)

    private fun brandOf(props: Map<String, String>): String {
        val direct = props["ro.product.brand"].orBlank()
        if (direct.isNotBlank()) return titleCase(direct)
        val manufacturer = props["ro.product.manufacturer"].orBlank()
        if (manufacturer.isNotBlank()) return titleCase(manufacturer)
        return fromFingerprint(props["ro.build.fingerprint"].orBlank())
    }

    private fun fromFingerprint(fingerprint: String): String {
        val head = fingerprint.substringBefore('/').trim()
        return if (head.isBlank() || head == fingerprint) "" else titleCase(head)
    }

    private fun titleCase(value: String): String = when {
        value.lowercase() == "iqoo" -> "iQOO"
        value.lowercase() == "oppo" -> "OPPO"
        value.lowercase() == "oneplus" -> "OnePlus"
        value.lowercase() == "realme" -> "realme"
        value.isBlank() -> value
        else -> value.replaceFirstChar { it.uppercaseChar() }
    }

    /** `V17.0.1.2` -> `17`, `realme UI 7.0` -> `7`, `EmotionUI_13.0` -> `13`. */
    private fun majorVersion(raw: String): String {
        val match = Regex("(\\d+)(?:\\.(\\d+))?").find(raw) ?: return ""
        val major = match.groupValues[1].trimStart('0').ifBlank { "0" }
        val minor = match.groupValues[2]
        return if (minor.length == 1 && minor != "0") "$major.$minor" else major
    }

    /** MIUI encodes the version as `V140` (14) or `V125` (12.5). */
    private fun miuiVersion(raw: String): String {
        val digits = raw.trim().removePrefix("V").removePrefix("v")
        if (digits.length < 2 || digits.any { !it.isDigit() }) return ""
        val major = digits.dropLast(1).trimStart('0').ifBlank { "0" }
        val minor = digits.last().toString()
        return if (minor == "0") major else "$major.$minor"
    }

    /** One UI ships as a five digit code: the minor is two digits, so `70100` is 7.1. */
    private fun oneuiVersion(raw: String): String {
        val digits = raw.trim()
        if (digits.length < 3 || digits.any { !it.isDigit() }) return majorVersion(raw)
        val major = digits.take(1)
        val minor = digits.substring(1, 3).toIntOrNull() ?: return major
        return if (minor == 0) major else "$major.$minor"
    }

    private fun String?.orBlank(): String = this ?: ""
}
