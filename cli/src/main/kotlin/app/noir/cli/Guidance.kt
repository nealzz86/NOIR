/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.cli

import app.noir.core.RomInfo
import app.noir.core.RomKind
import app.noir.core.Tui

/** Human-friendly text blocks: every failure should end with an action. */
object Guidance {


    fun about(version: String): String {
        val total = (Tui.terminalColumns() - 2).coerceIn(50, 100)
        val inner = total - 4

        fun row(text: String) = "│ " + pad(text, inner) + " │"

        val sb = StringBuilder()
        sb.append("╭").append("─".repeat(total - 2)).append("╮").append('\n')

        val logo = listOf(
            "  ███╗   ██╗ ██████╗ ██╗██████╗ ",
            "  ████╗  ██║██╔═══██╗██║██╔══██╗",
            "  ██╔██╗ ██║██║   ██║██║██████╔╝",
            "  ██║╚██╗██║██║   ██║██║██╔══██╗",
            "  ██║ ╚████║╚██████╔╝██║██║  ██║",
            "  ╚═╝  ╚═══╝ ╚═════╝ ╚═╝╚═╝  ╚═╝"
        )
        if (inner >= 40) {
            logo.forEach { sb.append(row(it)).append('\n') }
        } else {
            sb.append(row("  NOIR")).append('\n')
        }

        sb.append(row("")).append('\n')

        fun field(label: String, value: String) {
            val prefix = "  " + label.padEnd(10)
            val lines = wrap(value, inner - prefix.length)
            if (lines.isEmpty()) {
                sb.append(row(prefix.trimEnd())).append('\n')
                return
            }
            sb.append(row(prefix + lines.first())).append('\n')
            lines.drop(1).forEach { sb.append(row(" ".repeat(prefix.length) + it)).append('\n') }
        }

        field("version", "$version · android leak audit")
        field("what", "inventories what installed apps can collect, and where they send it")
        field("not", "a root tool · not a malware scanner · not a firewall")
        field("writes", "nothing by default; --apply only sets reversible app-ops")
        field("license", "MIT")
        field("author", "Verlintas")
        field("repo", "github.com/Verlintas/NOIR")
        field("runs on", "Windows 10/11 · macOS · Debian/Ubuntu")

        sb.append(row("")).append('\n')
        sb.append(row("  commands")).append('\n')
        listOf(
            "noir" to "guided first run",
            "noir doctor" to "environment check",
            "noir scan" to "inventory + report",
            "noir ui" to "dashboard",
            "noir auto" to "plan (+ --apply)",
            "noir deep <pkg>" to "jadx dive",
            "noir setup" to "download adb / jadx"
        ).forEach { (cmd, desc) ->
            sb.append(row("    " + cmd.padEnd(18) + desc)).append('\n')
        }

        sb.append('╰').append("─".repeat(total - 2)).append('╯')
        return sb.toString()
    }

    /**
     * Built with the same width-aware helpers as [about] rather than a literal box:
     * CJK steps are wider than they look, and a hand-padded border breaks the
     * moment a line is reworded or the console is 50 columns.
     */
    fun deviceGuide(): String {
        val total = (Tui.terminalColumns() - 2).coerceIn(50, 100)
        val inner = total - 4

        fun row(text: String) = "│ " + pad(text, inner) + " │"

        val sb = StringBuilder()
        // "╭─ 没有检测到设备 " is 18 columns, so the dashes have to give the rest.
        sb.append("╭─ 没有检测到设备 ").append("─".repeat(total - 19)).append("╮").append('\n')

        fun step(title: String, vararg lines: String) {
            wrap(title, inner).forEach { sb.append(row(it)).append('\n') }
            lines.forEach { text ->
                // Reserve the indent from the wrap width, otherwise a wrapped
                // continuation pushes the right border off the screen.
                wrap(text, inner - 6).forEachIndexed { index, part ->
                    sb.append(row((if (index == 0) "    " else "      ") + part)).append('\n')
                }
            }
            sb.append(row("")).append('\n')
        }

        step(
            "  1. 打开开发者模式",
            "小米 / HyperOS：设置 → 我的设备 → 全部参数 → 连点「MIUI 版本」7 次",
            "OPPO / 一加 / 真我：设置 → 关于本机 → 版本信息 → 连点「版本号」7 次",
            "原生 Android：设置 → 关于手机 → 连点「版本号」7 次"
        )
        step(
            "  2. 开启 USB 调试",
            "设置 → 系统 → 开发者选项 → USB 调试",
            "ColorOS：设置 → 系统设置 → 开发者选项（要先登录 OPPO 账号）",
            "Xiaomi 额外开启：USB 调试（安全设置）"
        )
        step(
            "  3. 插线后手机会弹「允许 USB 调试吗？」",
            "勾选「一律允许使用这台计算机进行调试」→ 允许"
        )
        wrap("  4. 还不行就换一根数据线（很多线只能充电）", inner).forEach { sb.append(row(it)).append('\n') }

        sb.append('╰').append("─".repeat(total - 2)).append('╯')
        return sb.toString()
    }

    fun unauthorizedGuide(): String = """
        |设备已连接，但没有授权。
        |
        |  • 看手机屏幕：应该有一个「允许 USB 调试吗？」的弹窗
        |  • 没弹窗：开发者选项 → 撤销 USB 调试授权 → 重新插拔
        |  • 仍不行：开发者选项 → 关闭再打开 USB 调试
    """.trimMargin()

    fun adbMissing(): String = """
        |  • macOS：brew install --cask android-platform-tools
        |  • Windows：scoop install adb  或  https://developer.android.com/tools/releases/platform-tools
        |  • Debian/Ubuntu：sudo apt install adb
        |  • 或者让 NOIR 自己下载：noir setup
    """.trimMargin()

    fun jadxMissing(): String = """
        |  • macOS：brew install jadx
        |  • Windows：scoop install jadx
        |  • Debian/Ubuntu：apt install jadx
        |  • 或者让 NOIR 自己下载：noir setup
    """.trimMargin()

    /** The device answered success and changed nothing — the usual OEM shell outcome. */
    fun silentRejectNote(rom: RomInfo): String = """
        |${silentRejectHead(rom)} 逐条结论见 verify.txt：
        |  • ColorOS：设置 → 系统设置（或其他设置）→ 开发者选项 → 「权限监控」选「允许修改」
        |  • MIUI/HyperOS：开发者选项 → USB 调试（安全设置），需要登录账号并插 SIM
        |  • 仍然无效：NOIR 只走 adb 通道，Shizuku / root 路径暂未支持
    """.trimMargin()

    private fun silentRejectHead(rom: RomInfo): String = when (rom.kind) {
        RomKind.COLOROS -> "ColorOS 让 appops 返回成功，但没有真的改模式（${rom.display}）。"
        RomKind.HYPEROS, RomKind.MIUI -> "HyperOS/MIUI 拒绝了 shell 侧的 AppOps 修改。"
        RomKind.ORIGINOS -> "OriginOS 拦掉了 shell 侧的 AppOps 修改。"
        else -> "设备报告成功，但状态没有变化。"
    }

    fun adblockNote(rom: RomInfo): String = """
        |清单按 ${rom.display} 给出。带「清单」的行来自公开的 ColorOS / MIUI 精简笔记，
        |NOIR 没有在真机逐项验证；带「推断」的行只按包名形态匹配，需要你判断。
        |停用一律可恢复：pm enable <包名>，恢复命令已写进 adblock-rollback.txt。
        |「别碰」一档 NOIR 不执行，只用来说明这些包同样在广告链路上。
    """.trimMargin()

    fun error(title: String, hint: String? = null): String {
        val total = (Tui.terminalColumns() - 2).coerceIn(50, 100)
        val inner = total - 4

        fun row(text: String) = "│ " + pad(text, inner) + " │"

        val sb = StringBuilder()
        sb.append("╭─ ERROR ").append("─".repeat(total - 10)).append("╮").append('\n')
        wrap(title, inner).forEach { sb.append(row(it)).append('\n') }
        if (hint != null) {
            sb.append('├').append("─".repeat(total - 2)).append('┤').append('\n')
            wrap(hint, inner).forEach { sb.append(row(it)).append('\n') }
        }
        sb.append('╰').append("─".repeat(total - 2)).append('╯')
        return sb.toString()
    }

    /**
     * Greedy wrap that prefers spaces (English) but still breaks anywhere when a
     * run of CJK text has no spaces to break on.
     */
    private fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        text.lines().forEach { line ->
            if (line.isEmpty()) {
                out.add("")
                return@forEach
            }
            var start = 0
            while (start < line.length) {
                var i = start
                var used = 0
                var lastSpace = -1
                while (i < line.length) {
                    val cw = charWidth(line[i])
                    if (used + cw > width) break
                    if (line[i] == ' ') lastSpace = i
                    used += cw
                    i++
                }
                if (i >= line.length) {
                    out.add(line.substring(start))
                    break
                }
                if (lastSpace > start) {
                    out.add(line.substring(start, lastSpace))
                    start = lastSpace + 1
                } else {
                    out.add(line.substring(start, i))
                    start = i
                }
            }
        }
        return out
    }

    private fun pad(s: String, width: Int) = s + " ".repeat(maxOf(0, width - width(s)))

    private fun width(s: String) = s.sumOf { charWidth(it) }

    private fun charWidth(c: Char): Int = when {
        c.code in 0x1100..0x115F -> 2
        c.code in 0x2E80..0xA4CF -> 2
        c.code in 0xAC00..0xD7A3 -> 2
        c.code in 0xF900..0xFAFF -> 2
        c.code in 0xFE30..0xFE4F -> 2
        c.code in 0xFF00..0xFF60 -> 2
        c.code in 0xFFE0..0xFFE6 -> 2
        else -> 1
    }
}
