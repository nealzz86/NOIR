/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.cli

import app.noir.core.ActionType
import app.noir.core.AdTarget
import app.noir.core.AdTargets
import app.noir.core.Advisor
import app.noir.core.AppEntry
import app.noir.core.Cache
import app.noir.core.Category
import app.noir.core.CheckResult
import app.noir.core.Classifier
import app.noir.core.Domains
import app.noir.core.Device
import app.noir.core.Recommendation
import app.noir.core.Report
import app.noir.core.ReportMeta
import app.noir.core.Rom
import app.noir.core.RomInfo
import app.noir.core.RomKind
import app.noir.core.Rules
import app.noir.core.Tui
import app.noir.core.Verify
import app.noir.core.Verdict
import app.noir.core.adb.Adb
import app.noir.core.adb.DeviceScan
import app.noir.core.adb.Dumpsys
import app.noir.core.adb.PackagePath
import app.noir.core.apk.ApkScan
import app.noir.core.apk.ManifestAnalyzer
import app.noir.core.apk.ManifestFacts
import app.noir.core.apk.Trackers
import app.noir.core.jadx.Jadx
import app.noir.core.platform.Platform
import app.noir.core.platform.Setup
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

private val VERSION: String =
    Guidance::class.java.`package`?.implementationVersion ?: "0.1.0-dev"

private val BOOLEAN_FLAGS = setOf(
    "apply", "yes", "verbose", "help", "ascii", "color", "no-color", "quiet", "no-clear",
    "all", "purge", "revert"
)

private var QUIET = false

private fun say(message: String) {
    if (!QUIET) println(message)
}

private class NoirException(message: String, val hint: String? = null) : Exception(message)

fun main(args: Array<String>) {
    QUIET = args.contains("--quiet")
    try {
        runMain(args)
    } catch (e: NoirException) {
        System.err.println()
        System.err.println(Guidance.error(e.message ?: "出错了", e.hint))
        exitProcess(1)
    } catch (e: Exception) {
        System.err.println()
        System.err.println(
            Guidance.error(
                e.message ?: e::class.simpleName ?: "未知错误",
                "加 --verbose 可以看完整堆栈；也可以先运行 noir doctor 检查环境。"
            )
        )
        if (args.contains("--verbose")) e.printStackTrace()
        exitProcess(1)
    }
}

private fun runMain(args: Array<String>) {
    if (args.isEmpty()) {
        if (isInteractive()) guided() else usage()
        return
    }
    when (val command = args[0]) {
        "devices" -> cmdDevices(args.drop(1))
        "scan" -> cmdScan(args.drop(1))
        "auto" -> cmdAuto(args.drop(1))
        "ui" -> cmdUi(args.drop(1))
        "deep" -> cmdDeep(args.drop(1))
        "deepscan" -> cmdDeepScan(args.drop(1))
        "block" -> cmdBlock(args.drop(1))
        "adblock" -> cmdAdblock(args.drop(1))
        "setup" -> cmdSetup(args.drop(1))
        "doctor" -> cmdDoctor(args.drop(1))
        "-h", "--help", "help" -> usage()
        "-v", "--version", "version" -> println("NOIR $VERSION")
        "--about", "about" -> println(Guidance.about(VERSION))
        else -> {
            System.err.println("不认识的命令：$command")
            usage()
            exitProcess(2)
        }
    }
}

private fun usage() {
    println(
        """
        NOIR — audit what your phone can leak, from the desktop.
        runs on Windows 10/11, macOS and Debian/Ubuntu.

        第一次用？直接运行 `noir`（不带参数），会进入向导。
        想先了解它是什么：noir --about

        usage:
          noir                                guided setup + scan (interactive)
          noir --about                        what this is, what it writes, license
          noir --version                      print the version
          noir doctor                         check adb / jadx / device
          noir setup                          download missing tools into ~/.noir/tools
          noir devices                        list connected devices
          noir scan [options]                 inventory apps, permissions and endpoints
          noir auto [options]                 scan + classify + plan
          noir ui [options]                   same, rendered as a dashboard
          noir deep <package> [options]       pull one APK, run jadx, grep for red flags
          noir deepscan [options]             decompile the riskiest apps, then offer cleanup
          noir block <package...> [options]   print (or apply) neutralisation commands
          noir adblock [options]              find and disable system ad components

        options:
          --serial <id>      target a specific device
          --out <dir>        output directory (default: noir-out)
          --min <score>      only keep apps at or above this score
          --limit <n>        cap reported apps (default 500)
          --only <regex>     restrict scanning to matching package names
          --adb <path>       override adb binary
          --jadx <path>      override jadx binary

        auto / deepscan:
          --apply            execute the safe, reversible app-ops actions
          --yes              skip confirmation prompts (automation)

        deepscan only:
          --top <n>          how many apps to decompile (default 15)
          --all              decompile every flagged app (slow, lots of disk)
          --jobs <n>         parallel jadx processes (default: cpu/2, max 6)
          --purge            also offer to uninstall risky user apps

        adblock only:
          --apply            run `pm disable-user` on the components you confirm
          --revert           re-enable everything adblock --apply disabled

        display:
          --ascii            plain ASCII borders (legacy Windows console)
          --color            force ANSI colour (auto-detected on a TTY)
          --no-color         disable ANSI colour
          --quiet            suppress scan progress
          --no-clear         do not clear the screen before the dashboard
        """.trimIndent()
    )
}

// ── guided first run ───────────────────────────────────────────────────────

private fun bannerSmall() {
    println(
        """
        |  ███╗   ██╗ ██████╗ ██╗██████╗
        |  ████╗  ██║██╔═══██╗██║██╔══██╗
        |  ██╔██╗ ██║██║   ██║██║██████╔╝
        |  ██║╚██╗██║██║   ██║██║██╔══██╗
        |  ██║ ╚████║╚██████╔╝██║██║  ██║
        |  ╚═╝  ╚═══╝ ╚═════╝ ╚═╝╚═╝  ╚═╝
        """.trimMargin()
    )
}

private fun guided() {
    bannerSmall()
    println("  首次运行向导 · Ctrl+C 随时退出")
    println()

    val adb = ensureAdbInteractive()
    val device = awaitDevice(adb, null, interactive = true)

    println()
    println("  设备：${device.model.ifBlank { device.serial }}  (${device.serial})")
    print("  开始扫描并生成报告? [Y/n] ")
    val proceed = readLine()?.trim()?.lowercase()
    if (proceed == "n" || proceed == "no") {
        println("  已取消。")
        return
    }

    val args = Args(emptyList())
    val result = performScan(args)
    val recommendations = Advisor.advise(result.apps)
    writeReports(result, recommendations)

    println()
    print(renderDashboard(args, result, recommendations))

    val planned = plannedActions(recommendations)
    println()
    println("  可以安全执行 ${planned.size} 条（appops，可回滚）")
    if (planned.isEmpty()) {
        println("  没有需要自动处理的项。")
        return
    }
    print("  现在执行? [y/N] ")
    val apply = readLine()?.trim()?.lowercase()
    if (apply == "y" || apply == "yes") {
        applyCommands(adb, result, planned)
    } else {
        println("  已跳过。之后可以运行：noir auto --apply")
    }
}

private fun ensureAdbInteractive(): Adb {
    Platform.findAdb()?.let { return Adb(it) }

    println("  没有找到 adb。")
    println(Guidance.adbMissing())
    print("  要现在下载 Android platform-tools（约 15 MB）吗? [Y/n] ")
    val answer = readLine()?.trim()?.lowercase()
    if (answer == "n" || answer == "no") {
        throw NoirException("需要 adb 才能继续。", Guidance.adbMissing())
    }

    print("  下载中 ")
    val path = Setup.installAdb { read, total -> renderDownload(read, total) }
    println()
    if (path == null) throw NoirException("adb 下载失败。", "检查网络后重试，或手动安装 adb。")
    println("  已安装：$path")
    return Adb(path)
}

private fun renderDownload(read: Long, total: Long) {
    if (QUIET) return
    if (total > 0) {
        print("\r  ${read * 100 / total}%  ${read / 1_048_576}/${total / 1_048_576} MB   ")
    } else {
        print("\r  ${read / 1_048_576} MB   ")
    }
    System.out.flush()
}

// ── commands ───────────────────────────────────────────────────────────────

private fun cmdDevices(raw: List<String>) {
    val args = Args(raw)
    val adb = connectAdb(args)
    val filter = args.value("serial")
    val devices = adb.devices().let { all -> if (filter == null) all else all.filter { it.serial == filter } }
    if (devices.isEmpty()) {
        println(Guidance.deviceGuide())
        return
    }
    println("%-22s %-14s %-22s %s".format("SERIAL", "STATE", "MODEL", "PRODUCT"))
    devices.forEach {
        println("%-22s %-14s %-22s %s".format(it.serial, it.state, it.model, it.product))
    }
    if (devices.any { it.state == "unauthorized" }) {
        println()
        println(Guidance.unauthorizedGuide())
    }
}

internal data class ScanResult(
    val device: Device,
    val apps: List<AppEntry>,
    val outDir: File,
    val android: String,
    val installed: Int,
    val paths: Map<String, String>,
    val meta: ReportMeta,
    val rom: RomInfo = RomInfo()
)

private fun cmdScan(raw: List<String>) {
    val args = Args(raw)
    val result = performScan(args)
    writeReports(result, emptyList())

    println()
    println("%5s  %-12s %-10s %-44s %s".format("SCORE", "CATEGORY", "VENDOR", "PACKAGE", "PERM/UPLOAD/CMD"))
    result.apps.take(40).forEach { a ->
        println(
            "%5d  %-12s %-10s %-44s %d/%d/%d".format(
                a.score, a.category.label, a.vendor ?: "—", a.pkg,
                a.permissions.size, a.uploadEndpoints.size, a.commandEndpoints.size
            )
        )
    }
    println()
    println(Tui.clip("[+] ${result.outDir.absolutePath}"))
}

private fun cmdAuto(raw: List<String>) {
    val args = Args(raw)
    val result = performScan(args)
    val recommendations = Advisor.advise(result.apps)
    writeReports(result, recommendations)

    println()
    print(renderDashboard(args, result, recommendations))
    println()
    println("== 建议动作 (前 15) ==")
    recommendations.take(15).forEach { r ->
        println("  %-5s %-16s %-46s %s".format(r.risk, r.action.label, r.pkg, r.reason))
    }

    val planned = plannedActions(recommendations)
    println()
    println("== 将执行（${planned.size} 条，全部可回滚）==")
    planned.forEach { println("  ${it.setCommand(result.meta.user)}") }
    println()
    println(Tui.clip("[+] ${result.outDir.absolutePath}"))

    if (!args.flag("apply")) {
        println()
        println("[*] dry-run — 加 --apply 执行这 ${planned.size} 条")
        return
    }

    if (!args.flag("yes")) {
        if (!isInteractive()) {
            println("[!] --apply 在非交互环境下需要同时加 --yes")
            return
        }
        print("执行 ${planned.size} 条 appops 动作? [y/N] ")
        val answer = readLine()?.trim()?.lowercase()
        if (answer != "y" && answer != "yes") {
            println("aborted")
            return
        }
    }
    applyCommands(connectAdb(args), result, planned)
}

private fun cmdUi(raw: List<String>) {
    val args = Args(raw)
    val result = performScan(args)
    val recommendations = Advisor.advise(result.apps)
    writeReports(result, recommendations)

    if (!args.flag("no-clear") && System.console() != null) {
        print("\u001b[2J\u001b[H")
    }
    println()
    print(renderDashboard(args, result, recommendations))
    println()
    println(Tui.clip("  report  ${result.outDir.absolutePath}"))
    println()
}

private fun cmdDeep(raw: List<String>) {
    val args = Args(raw)
    val pkg = args.positional.firstOrNull()
        ?: throw NoirException("缺少包名。", "用法：noir deep <package> [--serial S] [--out DIR]")

    val adb = connectAdb(args)
    val device = awaitDevice(adb, args.value("serial"), interactive = isInteractive())
    val outDir = File(args.value("out") ?: "noir-out/deep-${pkg.replace('.', '_')}").apply { mkdirs() }

    val remote = adb.shell(device.serial, "pm path $pkg").output
        .lineSequence()
        .firstOrNull { it.startsWith("package:") }
        ?.removePrefix("package:")
        ?.trim()
        ?: throw NoirException("这台设备上没有安装 $pkg。", "先运行 noir scan 看看实际包名。")

    val apk = File(outDir, "$pkg.apk")
    println("[*] pulling $remote")
    adb.pull(device.serial, remote, apk)
    println("[*] apk: ${apk.length() / 1024} KiB")

    val urls = ApkScan.urls(apk)
    println("[*] endpoints: ${urls.size}")
    urls.filter { Rules.isUpload(it) || Rules.isCommand(it) }.forEach { println("    ! $it") }

    val jadxBin = Platform.findJadx(args.value("jadx"))
        ?: throw NoirException("没有找到 jadx。", Guidance.jadxMissing())

    println("[*] decompiling with $jadxBin ...")
    val src = File(outDir, "src")
    val code = Jadx(jadxBin).decompile(apk, src)
    println("[*] jadx exit=$code")

    val hits = Jadx(jadxBin).grep(
        src,
        listOf(
            "Runtime\\.exec", "ProcessBuilder", "setApplicationEnabledSetting",
            "enabled_accessibility_services", "DexClassLoader", "loadLibrary",
            "https?://", "verify\\(", "Cipher", "getInstalledPackages",
            "getRunningTasks", "MediaProjection"
        )
    )
    println("[*] hits: ${hits.size}")
    hits.take(40).forEach { println("    ${it.file}:${it.line}  ${it.text}") }
    println("[+] sources: ${src.absolutePath}")
}

private data class BlockAction(val command: String, val kind: String, val pkg: String, val op: String = "")

private fun cmdBlock(raw: List<String>) {
    val args = Args(raw)
    val pkgs = args.positional
    if (pkgs.isEmpty()) throw NoirException("缺少包名。", "用法：noir block <package...> [--apply]")

    val adb = connectAdb(args)
    val device = awaitDevice(adb, args.value("serial"), interactive = isInteractive())

    val plan = pkgs.flatMap { pkg ->
        listOf(
            BlockAction("pm uninstall --user 0 $pkg", KIND_UNINSTALL, pkg),
            BlockAction("cmd appops set $pkg PROJECT_MEDIA ignore", KIND_APPOPS, pkg, "PROJECT_MEDIA"),
            BlockAction("cmd appops set $pkg GET_USAGE_STATS ignore", KIND_APPOPS, pkg, "GET_USAGE_STATS")
        )
    }

    println("# rollback")
    pkgs.forEach { println("#   adb -s ${device.serial} shell pm install-existing --user 0 $it") }
    println()
    plan.forEach { println("adb -s ${device.serial} shell ${it.command}") }

    if (!args.flag("apply")) {
        println()
        println("# dry-run — 加 --apply 执行")
        return
    }

    println()
    val changes = ArrayList<ChangeRecord>()
    plan.forEach { action ->
        print("> ${action.command} ... ")
        val result = adb.shell(device.serial, action.command)
        println(if (result.ok) "ok" else "failed (${result.code}) ${result.output.trim().take(120)}")
        changes += ChangeRecord(
            pkg = action.pkg,
            kind = action.kind,
            op = action.op,
            command = action.command,
            rollback = "pm install-existing --user 0 ${action.pkg}"
        )
    }

    val props = readProps(adb, device)
    val rom = Rom.detect(props)
    val meta = readReportMeta(adb, device, props, rom)
    val checks = verifyChanges(adb, device.serial, "0", changes)
    if (checks.isNotEmpty()) {
        println()
        println("[*] 复扫复核: ${Verify.summary(checks)}")
        checks.filter { it.verdict != Verdict.EFFECTIVE }.forEach {
            println("  ${it.verdict.label}  ${it.subject}  ${it.detail}")
        }
        val outDir = File(args.value("out") ?: "noir-out").apply { mkdirs() }
        writeVerifyReport(outDir, meta, device, rom, checks, "block-verify.txt")
        if (checks.any { it.verdict == Verdict.NO_EFFECT }) println(Guidance.silentRejectNote(rom))
    }
}

// ── adblock ────────────────────────────────────────────────────────────────

private fun cmdAdblock(raw: List<String>) {
    val args = Args(raw)
    if (args.flag("revert")) {
        revertAdblock(args)
        return
    }

    val result = performScan(args)
    val adb = connectAdb(args)
    val user = result.meta.user.ifBlank { "0" }
    val targets = AdTargets.onDevice(result.paths.keys, result.rom.kind)

    println()
    if (targets.isEmpty()) {
        println("[*] 这台设备上没匹配到广告组件。")
        if (AdTargets.table(result.rom.kind).isEmpty()) {
            println("    ${result.rom.display} 没有内置清单，只做了包名推断；清单目前覆盖 ColorOS / HyperOS / MIUI。")
        }
        return
    }

    val alreadyDisabled = disabledPackages(adb, result.device.serial)
    val actionable = targets.filter { it.actionable && it.pkg !in alreadyDisabled }

    println("== 广告相关组件 (${targets.size} 个，可停用 ${actionable.size} 个) ==")
    targets.forEach { target ->
        val state = if (target.pkg in alreadyDisabled) " · 已停用" else ""
        println("  ${target.tier.label}  ${target.pkg}  [${target.source}]$state")
        println("        ${target.name} · ${target.channel}")
    }
    println()
    println(Guidance.adblockNote(result.rom))

    writeAdblockFiles(result, targets, actionable.mapTo(HashSet()) { it.pkg })

    if (!args.flag("apply")) {
        println()
        println("[*] dry-run — 加 --apply 逐条确认这 ${actionable.size} 条 pm disable-user")
        println("[*] 恢复：noir adblock --revert --out ${args.value("out") ?: "noir-out"}")
        return
    }
    if (actionable.isEmpty()) {
        println("[*] 可停用的都已经是停用状态；「别碰」一档 NOIR 不执行。")
        return
    }

    val selected = if (args.flag("yes")) actionable else pickAdblockTargets(actionable)
    if (selected.isNullOrEmpty()) {
        if (selected != null) println("aborted")
        return
    }

    applyAdblock(adb, result, selected, user)
}

/**
 * One prompt per component, because the curated list is not device-verified.
 * `a` takes the rest so a known-good ROM does not cost 15 keystrokes.
 */
private fun pickAdblockTargets(actionable: List<AdTarget>): List<AdTarget>? {
    if (!isInteractive()) {
        println("[!] --apply 在非交互环境下需要同时加 --yes")
        return null
    }
    val chosen = ArrayList<AdTarget>()
    var takeRest = false
    actionable.forEach { target ->
        if (takeRest) {
            chosen += target
            return@forEach
        }
        print("停用 ${target.pkg}（${target.name}）? [y/n/a=其余全部] ")
        when (readLine()?.trim()?.lowercase()) {
            "y", "yes" -> chosen += target
            "a" -> {
                chosen += target
                takeRest = true
            }

            "n", "no" -> println("      跳过 ${target.pkg}")
            else -> {
                println("aborted")
                return null
            }
        }
    }
    return chosen
}

private fun applyAdblock(adb: Adb, result: ScanResult, targets: List<AdTarget>, user: String) {
    val serial = result.device.serial
    val applied = ArrayList<String>()
    val rollback = ArrayList<String>()
    val changes = ArrayList<ChangeRecord>()

    applied.add("# NOIR adblock")
    applied.add("# generated: ${result.meta.generatedAt}")
    applied.add("# tool: ${result.meta.tool}")
    applied.add("# host: ${result.meta.host}")
    applied.add("# device: ${result.device.model} (${result.device.serial}) · ${result.device.product}")
    applied.add("# rom: ${result.meta.rom.ifBlank { "unrecognised" }}")
    applied.add("# user: $user")
    applied.add("#")

    rollback.add("# NOIR adblock rollback")
    rollback.add("# generated: ${result.meta.generatedAt}")
    rollback.add("# every component a disable command was sent for; re-enabling an")
    rollback.add("# already-enabled app is a no-op, so the list is deliberately generous")
    rollback.add("#")

    println()
    targets.forEach { target ->
        val command = "pm disable-user --user $user ${target.pkg}"
        val res = adb.shell(serial, command, timeoutSec = 30)
        val status = when {
            res.timedOut -> "timeout (${res.code})"
            res.ok -> "ok (0)"
            else -> "failed (${res.code})"
        }
        val inline = Verify.disabledByMessage(res.output)

        applied.add("$command\t$status")
        applied.add("  output: ${flatOutput(res.output)}")
        applied.add("  即时回执: ${inline.label}")

        println("  $command  $status")
        if (!res.ok) res.output.trim().lineSequence().filter { it.isNotBlank() }.take(3)
            .forEach { println("      $it") }
        if (inline == Verdict.NO_EFFECT) println("      即时回执：${inline.label}（命令说没改成功）")

        rollback.add("adb -s $serial shell pm enable --user $user ${target.pkg}")
        changes += ChangeRecord(
            pkg = target.pkg,
            kind = KIND_DISABLE,
            command = command,
            rollback = "pm enable --user $user ${target.pkg}"
        )
    }

    File(result.outDir, "adblock.txt").writeText(applied.joinToString("\n", postfix = "\n"))
    File(result.outDir, "adblock-rollback.txt").writeText(rollback.joinToString("\n", postfix = "\n"))
    println()
    println("[*] applied:  ${File(result.outDir, "adblock.txt").absolutePath}")
    println("[*] rollback: ${File(result.outDir, "adblock-rollback.txt").absolutePath}")

    val checks = verifyChanges(adb, serial, user, changes)
    if (checks.isEmpty()) return
    println("[*] 复扫复核: ${Verify.summary(checks)}")
    checks.filter { it.verdict != Verdict.EFFECTIVE }.take(10).forEach {
        println("  ${"%-10s".format(it.verdict.label)} ${it.subject}  ${it.detail}")
    }
    writeVerifyReport(result.outDir, result.meta, result.device, result.rom, checks, "adblock-verify.txt")
    if (checks.any { it.verdict == Verdict.NO_EFFECT }) println(Guidance.silentRejectNote(result.rom))
}

private val ROLLBACK_COMMAND = Regex("""^adb -s \S+ shell (pm enable .+)$""")

private fun revertAdblock(args: Args) {
    val outDir = File(args.value("out") ?: "noir-out")
    val file = File(outDir, "adblock-rollback.txt")
    if (!file.isFile) {
        throw NoirException(
            "没有找到 ${file.absolutePath}",
            "只有执行过 noir adblock --apply 才会生成回滚文件；也可以手动 adb shell pm enable <包名>。"
        )
    }
    val commands = file.readLines().mapNotNull { ROLLBACK_COMMAND.find(it.trim())?.groupValues?.get(1) }
    if (commands.isEmpty()) throw NoirException("回滚文件里没有恢复命令。", "检查 ${file.absolutePath}")

    val adb = connectAdb(args)
    val device = awaitDevice(adb, args.value("serial"), interactive = isInteractive())

    println("== 恢复 ${commands.size} 个组件 ==")
    commands.forEach { println("  $it") }
    if (!args.flag("yes") && !confirm("执行以上恢复命令? [Y/n] ")) {
        println("aborted")
        return
    }

    val checks = ArrayList<CheckResult>()
    println()
    commands.forEach { command ->
        val pkg = command.substringAfterLast(' ')
        val res = adb.shell(device.serial, command, timeoutSec = 30)
        val status = when {
            res.timedOut -> "timeout (${res.code})"
            res.ok -> "ok (0)"
            else -> "failed (${res.code})"
        }
        println("  $command  $status")

        val dump = adb.shell(device.serial, "dumpsys package $pkg", timeoutSec = 60)
        val setting = Verify.enabledSetting(dump.output)
        checks += CheckResult(
            subject = pkg,
            command = command,
            verdict = when {
                !res.ok -> Verdict.REJECTED
                setting == null -> Verdict.UNVERIFIED
                Verify.enabled(setting) -> Verdict.EFFECTIVE
                else -> Verdict.NO_EFFECT
            },
            detail = "mEnabledSetting=${Verify.enabledStateLabel(setting)}"
        )
    }

    if (checks.isNotEmpty()) {
        println()
        println("[*] 复扫复核: ${Verify.summary(checks)}")
        val props = readProps(adb, device)
        val rom = Rom.detect(props)
        writeVerifyReport(
            outDir,
            readReportMeta(adb, device, props, rom),
            device,
            rom,
            checks,
            "adblock-revert-verify.txt"
        )
    }
}

private fun disabledPackages(adb: Adb, serial: String): Set<String> {
    val res = adb.shell(serial, "pm list packages -d", timeoutSec = 60)
    if (!res.ok) return emptySet()
    return res.output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .mapTo(HashSet()) { it.removePrefix("package:") }
}

private fun writeAdblockFiles(result: ScanResult, targets: List<AdTarget>, actionablePkgs: Set<String>) {
    val lines = ArrayList<String>()
    lines += "# NOIR adblock plan"
    lines += "# generated: ${result.meta.generatedAt}"
    lines += "# tool: ${result.meta.tool}"
    lines += "# device: ${result.device.model} (${result.device.serial}) · ${result.rom.display}"
    lines += "# 清单行来自公开精简笔记，未在真机逐项验证；推断行只按包名形态匹配。"
    lines += ""
    targets.forEach { target ->
        val run = if (target.actionable) "adb -s ${result.device.serial} shell pm disable-user --user ${result.meta.user.ifBlank { "0" }} ${target.pkg}"
        else "# 不执行（${target.tier.label}）"
        lines += "${target.tier.label}\t${target.source}\t${target.pkg}"
        lines += "    ${target.name} · ${target.channel}"
        lines += "    $run"
    }
    File(result.outDir, "adblock-plan.txt").writeText(lines.joinToString("\n", postfix = "\n"))

    val hosts = Domains.export(result.apps.filter { it.pkg in actionablePkgs }).trackers
    val domains = buildString {
        appendLine("# NOIR adblock domains")
        appendLine("# hosts the listed components report to · ${hosts.size} entries")
        appendLine("# 只导出，不执行：NOIR 不改 DNS，把这些行交给 Pi-hole / AdGuard 自己决定。")
        hosts.forEach { appendLine(it) }
    }
    File(result.outDir, "adblock-domains.txt").writeText(domains)

    println()
    println("[*] adblock plan: ${File(result.outDir, "adblock-plan.txt").absolutePath}")
    println("[*] adblock hosts: ${File(result.outDir, "adblock-domains.txt").absolutePath} (${hosts.size})")
}

private fun cmdSetup(raw: List<String>) {
    val args = Args(raw)
    val autoYes = args.flag("yes")

    println("NOIR setup")
    println("  platform  ${Platform.describe()}")

    val adb = Platform.findAdb(args.value("adb"))
    if (adb != null) {
        println("  adb       $adb")
    } else {
        println("  adb       未找到")
        if (!autoYes && !confirm("  下载 Android platform-tools（约 15 MB）? [Y/n] ")) {
            println(Guidance.adbMissing())
        } else {
            print("  下载中 ")
            val path = Setup.installAdb { read, total -> renderDownload(read, total) }
            println()
            if (path == null) throw NoirException("adb 下载失败。", "检查网络后重试。")
            println("  adb       $path")
        }
    }

    val jadx = Platform.findJadx(args.value("jadx"))
    if (jadx != null) {
        println("  jadx      $jadx")
    } else {
        println("  jadx      未找到（只有 deep 命令需要）")
        if (!autoYes && !confirm("  下载 jadx（约 73 MB）? [Y/n] ")) {
            println(Guidance.jadxMissing())
        } else {
            print("  下载中 ")
            val path = Setup.installJadx { read, total -> renderDownload(read, total) }
            println()
            if (path == null) throw NoirException("jadx 下载失败。", "检查网络后重试。")
            println("  jadx      $path")
        }
    }

    println()
    println("完成。下一步：noir doctor")
}

private fun cmdDoctor(raw: List<String>) {
    val args = Args(raw)
    println("NOIR doctor")
    println("  platform  ${Platform.describe()}")
    println("  java      ${System.getProperty("java.version")}")

    val adbPath = Platform.findAdb(args.value("adb"))
    if (adbPath == null) {
        println("  adb       未找到")
        println(Guidance.adbMissing())
        return
    }
    val adb = Adb(adbPath)
    println("  adb       $adbPath")
    adb.exec(listOf("version"), timeoutSec = 15).output.lineSequence()
        .firstOrNull { it.startsWith("Version") }
        ?.let { println("            $it") }

    val devices = adb.devices()
    if (devices.isEmpty()) {
        println("  device    未连接")
        println()
        println(Guidance.deviceGuide())
    } else {
        devices.forEach { println("  device    ${it.serial}  ${it.state}  ${it.model}") }
        if (devices.any { it.state == "unauthorized" }) {
            println()
            println(Guidance.unauthorizedGuide())
        }
    }

    val jadx = Platform.findJadx(args.value("jadx"))
    if (jadx == null) println("  jadx      未找到（只有 deep 需要）") else println("  jadx      $jadx")
}

private fun confirm(prompt: String): Boolean {
    print(prompt)
    val answer = readLine()?.trim()?.lowercase()
    return answer != "n" && answer != "no"
}

// ── scan pipeline ──────────────────────────────────────────────────────────

private fun performScan(args: Args): ScanResult {
    val adb = connectAdb(args)
    val device = awaitDevice(adb, args.value("serial"), interactive = isInteractive())
    val outDir = File(args.value("out") ?: "noir-out").apply { mkdirs() }
    val minScore = args.value("min")?.toIntOrNull() ?: 0
    val limit = args.value("limit")?.toIntOrNull() ?: 500

    say("[*] host: ${Platform.describe()}")
    say("[*] device: ${device.serial} (${device.model})")

    val paths = DeviceScan.packagePaths(adb, device.serial)
    say("[*] packages: ${paths.size}")

    if (!QUIET) print("[*] reading dumpsys package ... ")
    val dump = adb.shell(device.serial, "dumpsys package", timeoutSec = 600)
    say("${dump.output.length / 1024} KiB")

    val meta = Dumpsys.parse(dump.output)
    say("[*] parsed: ${meta.size}")

    val only = args.value("only")?.let { runCatching { Regex(it) }.getOrNull() }
    val candidates = meta.entries.filter { (pkg, m) ->
        m.perms.contains("INTERNET") &&
            m.perms.count { it in Rules.SENSITIVE } > 0 &&
            paths.containsKey(pkg) &&
            (only == null || only.containsMatchIn(pkg))
    }
    say("[*] candidates (internet + sensitive): ${candidates.size}")

    val props = readProps(adb, device)
    val android = props["ro.build.version.release"].orEmpty()
    val rom = Rom.detect(props)
    val reportMeta = readReportMeta(adb, device, props, rom)
    say("[*] rom: ${rom.display}")

    val targets = candidates.map { PackagePath(it.key, paths.getValue(it.key)) }
    val endpoints = HashMap<String, List<String>>()
    val missing = ArrayList<PackagePath>()

    targets.forEach { target ->
        val hit = Cache.get(target.pkg, target.path)
        if (hit != null) endpoints[target.pkg] = hit else missing += target
    }

    if (missing.isNotEmpty()) {
        say("[*] analysing ${missing.size} new/changed APKs (cache hits: ${targets.size - missing.size})")
        val started = System.currentTimeMillis()
        val fresh = DeviceScan.urls(adb, device.serial, missing, File(outDir, "work")) { done, total ->
            if (!QUIET) {
                print(progressLine(started, done, total))
                System.out.flush()
            }
        }
        if (!QUIET) println()
        missing.forEach { target -> Cache.put(target.pkg, target.path, fresh[target.pkg].orEmpty()) }
        Cache.flush()
        endpoints.putAll(fresh)
    } else {
        say("[*] all endpoints served from cache")
    }

    val apps = candidates.map { (pkg, m) ->
        val perms = m.perms.filter { it in Rules.SENSITIVE }
        val urls = endpoints[pkg].orEmpty()
        AppEntry(
            pkg = pkg,
            uid = m.appId,
            isSystem = m.isSystem,
            debuggable = m.debuggable,
            installer = m.installer,
            permissions = perms,
            endpoints = urls,
            score = Rules.score(perms, urls, m.isSystem, m.debuggable),
            category = Classifier.category(pkg, urls, perms, m.isSystem),
            vendor = Classifier.vendor(pkg, urls),
            sharedUid = m.sharedUser
        )
    }
        .filter { it.score >= minScore }
        .sortedByDescending { it.score }
        .take(limit)

    return ScanResult(device, apps, outDir, android, paths.size, paths, reportMeta, rom)
}

/** One `getprop` dump beats a round trip per key; some shells refuse the dump. */
private fun readProps(adb: Adb, device: Device): Map<String, String> {
    val dump = adb.shell(device.serial, "getprop", timeoutSec = 60)
    val props = Rom.parseGetprop(dump.output)
    if (props.isNotEmpty()) return props

    val keys = listOf(
        "ro.build.version.release", "ro.build.version.sdk", "ro.build.version.full",
        "ro.build.display.id", "ro.build.id", "ro.build.fingerprint",
        "ro.product.brand", "ro.product.manufacturer",
        "ro.build.version.opporom", "ro.build.version.oplusrom", "ro.build.version.realmeui",
        "ro.miui.ui.version.name", "ro.vivo.os.version", "ro.vivo.os.build.display.id",
        "ro.build.version.emui", "ro.build.version.magic", "ro.build.version.oneui"
    )
    return keys
        .map { it to adb.shell(device.serial, "getprop $it", timeoutSec = 15).output.trim() }
        .filter { it.second.isNotBlank() }
        .toMap()
}

private fun readReportMeta(adb: Adb, device: Device, props: Map<String, String>, rom: RomInfo): ReportMeta {
    val userOut = adb.shell(device.serial, "am get-current-user", timeoutSec = 15).output
    val user = userOut.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.toIntOrNull() != null }
        ?: "0"

    return ReportMeta(
        tool = "NOIR $VERSION",
        host = Platform.describe(),
        android = props["ro.build.version.release"].orEmpty(),
        rom = if (rom.kind == RomKind.UNKNOWN) "" else rom.display,
        sdk = props["ro.build.version.sdk"].orEmpty(),
        build = props["ro.build.display.id"].orEmpty().ifBlank { props["ro.build.id"].orEmpty() },
        fingerprint = props["ro.build.fingerprint"].orEmpty(),
        user = user,
        generatedAt = OffsetDateTime.now().toString()
    )
}

private fun writeReports(result: ScanResult, recommendations: List<Recommendation>) {
    File(result.outDir, "report.json").writeText(Report.json(result.device, result.apps, recommendations, result.meta))
    File(result.outDir, "report.md").writeText(Report.markdown(result.device, result.apps, recommendations, result.meta))
    writeDomainFiles(result.outDir, result.apps)
    File(result.outDir, "plan.txt").writeText(
        plannedCommands(recommendations, result.meta.user).joinToString("\n", postfix = "\n")
    )
    File(result.outDir, "rollback.txt").writeText(
        rollbackLines(recommendations, result.meta.user).joinToString("\n", postfix = "\n")
    )
}

private fun writeDomainFiles(outDir: File, apps: List<AppEntry>) {
    val export = Domains.export(apps)
    val date = java.time.LocalDate.now().toString()

    fun render(title: String, note: String, hosts: List<String>): String =
        buildString {
            append("# NOIR — ").append(title).append('\n')
            append("# generated ").append(date).append(" · ").append(hosts.size).append(" hosts\n")
            append("# ").append(note).append('\n')
            append("#\n")
            hosts.forEach { append(it).append('\n') }
        }

    val trackers = render(
        "tracker domains",
        "analytics / ads / crash reporting. Safe to block: these do not carry user-facing features.",
        export.trackers
    )
    val review = render(
        "unclassified domains",
        "review before blocking: these may be app APIs, maps, security updates or third-party SDKs.",
        export.review
    )
    val functional = render(
        "functional domains",
        "DO NOT BLOCK: push, login, updates, payments, app stores, cloud sync and connectivity checks.",
        export.functional
    )

    File(outDir, "domains-trackers.txt").writeText(trackers)
    File(outDir, "domains-other.txt").writeText(review)
    File(outDir, "domains-functional.txt").writeText(functional)

    // Backwards-compatible name, now containing only the safe tier.
    File(outDir, "domains.txt").writeText(trackers)
}

internal data class AppOpAction(val pkg: String, val op: String) {
    fun setCommand(user: String?): String =
        "cmd appops set${userArg(user)} $pkg $op ignore"

    fun getCommand(user: String?): String =
        "cmd appops get${userArg(user)} $pkg $op"

    fun rollbackCommand(user: String?, mode: String = "default"): String =
        "adb shell cmd appops set${userArg(user)} $pkg $op ${mode.ifBlank { "default" }}"

    private fun userArg(user: String?): String =
        if (user.isNullOrBlank()) "" else " --user $user"
}

/** One change NOIR asked the device to make, so the effect can be read back later. */
internal data class ChangeRecord(
    val pkg: String,
    val kind: String,
    val op: String = "",
    val command: String,
    val rollback: String
)

private const val EXPECTED_APP_OP_MODE = "ignore"
private const val KIND_APPOPS = "appop"
private const val KIND_DISABLE = "disable"
private const val KIND_UNINSTALL = "uninstall"

/**
 * Reads every changed setting back after the whole batch, one command each.
 *
 * Doing this after the batch rather than only inline matters: a later command
 * can undo an earlier one, and OEM skins report success without touching state.
 */
internal fun verifyChanges(adb: Adb, serial: String, user: String?, changes: List<ChangeRecord>): List<CheckResult> {
    if (changes.isEmpty()) return emptyList()
    val out = ArrayList<CheckResult>()

    val disabledList = if (changes.any { it.kind == KIND_DISABLE }) {
        adb.shell(serial, "pm list packages -d", timeoutSec = 60)
    } else {
        null
    }
    val visibleList = if (changes.any { it.kind == KIND_UNINSTALL }) {
        adb.shell(serial, "pm list packages --user ${user ?: "0"}", timeoutSec = 120)
    } else {
        null
    }

    changes.forEach { change ->
        when (change.kind) {
            KIND_APPOPS -> {
                val read = adb.shell(serial, AppOpAction(change.pkg, change.op).getCommand(user), timeoutSec = 30)
                val mode = Verify.appOpMode(read.output, change.op)
                out += CheckResult(
                    subject = change.pkg,
                    command = "cmd appops get ${change.op}",
                    verdict = Verify.appOp(EXPECTED_APP_OP_MODE, mode, read.ok),
                    detail = "当前模式 ${mode ?: (if (read.ok) "读不到" else "读取失败")}"
                )
            }

            KIND_DISABLE -> {
                val dump = adb.shell(serial, "dumpsys package ${change.pkg}", timeoutSec = 60)
                val setting = Verify.enabledSetting(dump.output)
                val verdict = when {
                    Verify.isDisabledState(setting) -> Verdict.EFFECTIVE
                    disabledList != null && !disabledList.ok -> Verdict.UNVERIFIED
                    disabledList != null -> Verify.disabled(disabledList.output, change.pkg, true)
                    else -> Verdict.UNVERIFIED
                }
                out += CheckResult(
                    subject = change.pkg,
                    command = "pm disable-user ${change.pkg}",
                    verdict = verdict,
                    detail = "mEnabledSetting=${Verify.enabledStateLabel(setting)}"
                )
            }

            else -> {
                val stillVisible = visibleList?.output
                    ?.lineSequence()
                    ?.any { it.trim() == "package:${change.pkg}" }
                val verdict = when {
                    visibleList == null || !visibleList.ok -> Verdict.UNVERIFIED
                    stillVisible == true -> Verdict.NO_EFFECT
                    else -> Verdict.EFFECTIVE
                }
                out += CheckResult(
                    subject = change.pkg,
                    command = "pm uninstall --user 0 ${change.pkg}",
                    verdict = verdict,
                    detail = if (stillVisible == true) "当前用户仍可见" else "当前用户已不可见"
                )
            }
        }
    }

    return out
}

private fun writeVerifyReport(
    outDir: File,
    meta: ReportMeta,
    device: Device,
    rom: RomInfo,
    checks: List<CheckResult>,
    name: String = "verify.txt"
) {
    if (checks.isEmpty()) return
    val lines = ArrayList<String>()
    lines += "# NOIR 生效复核"
    lines += "# generated: ${meta.generatedAt}"
    lines += "# tool: ${meta.tool}"
    lines += "# device: ${device.model} (${device.serial}) · ${rom.display}"
    lines += "# user: ${meta.user.ifBlank { "unknown (default user)" }}"
    lines += "# 复核读的是设备当前状态，不是命令退出码"
    lines += ""
    lines += Verify.summary(checks)
    lines += ""
    checks.forEach { check ->
        lines += "${check.verdict.label}\t${check.subject}\t${check.command}\t${check.detail}"
    }
    File(outDir, name).writeText(lines.joinToString("\n", postfix = "\n"))
    println("[*] 复核报告: ${File(outDir, name).absolutePath}")
}

private fun plannedActions(recommendations: List<Recommendation>): List<AppOpAction> =
    recommendations.filter { it.autoSafe }.mapNotNull { r ->
        when (r.action) {
            ActionType.APPOP_PROJECT_MEDIA -> AppOpAction(r.pkg, "PROJECT_MEDIA")
            ActionType.APPOP_USAGE_STATS -> AppOpAction(r.pkg, "GET_USAGE_STATS")
            else -> null
        }
    }.distinct()

private fun plannedCommands(recommendations: List<Recommendation>, user: String? = null): List<String> =
    plannedActions(recommendations).map { it.setCommand(user) }

private fun rollbackLines(recommendations: List<Recommendation>, user: String? = null): List<String> =
    plannedActions(recommendations).map { it.rollbackCommand(user) }

private fun flatOutput(output: String): String =
    output.trim().replace("\n", " | ").ifBlank { "(no output)" }

private fun classifyAppOpFailure(output: String): String = when {
    output.contains("device offline", ignoreCase = true) ||
        output.contains("no devices", ignoreCase = true) ||
        output.contains("device not found", ignoreCase = true) -> "adb connection"
    output.contains("SecurityException", ignoreCase = true) ||
        output.contains("Permission Denial", ignoreCase = true) ||
        output.contains("not allowed", ignoreCase = true) -> "system permission"
    output.contains("Unknown operation", ignoreCase = true) ||
        output.contains("Unknown op", ignoreCase = true) -> "unknown AppOp"
    output.contains("No such package", ignoreCase = true) ||
        (output.contains("package", ignoreCase = true) && output.contains("not found", ignoreCase = true)) -> "package/user"
    else -> "rejected"
}

internal fun applyCommands(adb: Adb, result: ScanResult, planned: List<AppOpAction>) {
    if (planned.isEmpty()) return
    val user = result.meta.user.ifBlank { null }
    val applied = ArrayList<String>()
    val rollback = ArrayList<String>()
    val changes = ArrayList<ChangeRecord>()
    var failed = 0
    var permissionFailures = 0

    applied.add("# NOIR applied commands")
    applied.add("# generated: ${result.meta.generatedAt}")
    applied.add("# tool: ${result.meta.tool}")
    applied.add("# host: ${result.meta.host}")
    applied.add("# device: ${result.device.model} (${result.device.serial}) · ${result.device.product}")
    applied.add("# android: ${result.meta.android} (SDK ${result.meta.sdk})")
    applied.add("# rom: ${result.meta.rom.ifBlank { "unrecognised" }}")
    applied.add("# build: ${result.meta.build}")
    applied.add("# fingerprint: ${result.meta.fingerprint}")
    applied.add("# user: ${result.meta.user.ifBlank { "unknown (default user)" }}")
    applied.add("#")

    println()
    planned.forEach { action ->
        val before = adb.shell(result.device.serial, action.getCommand(user), timeoutSec = 30)
        val res = adb.shell(result.device.serial, action.setCommand(user), timeoutSec = 30)
        val after = adb.shell(result.device.serial, action.getCommand(user), timeoutSec = 30)
        val status = when {
            res.timedOut -> "timeout (${res.code})"
            res.ok -> "ok (0)"
            else -> "failed (${res.code})"
        }
        val kind = if (!res.ok) classifyAppOpFailure(res.output) else ""
        val afterMode = Verify.appOpMode(after.output, action.op)
        val verdict = Verify.appOp(EXPECTED_APP_OP_MODE, afterMode, res.ok)
        if (!res.ok) {
            failed++
            if (kind == "system permission") permissionFailures++
        }

        applied.add("${action.setCommand(user)}\t$status${if (kind.isNotBlank()) " [$kind]" else ""}\t复核: ${verdict.label}")
        applied.add("  before: ${flatOutput(before.output)}")
        applied.add("  after:  ${flatOutput(after.output)}")
        if (res.output.isBlank()) {
            applied.add("  output: (empty)")
        } else {
            applied.add("  output:")
            res.output.trimEnd().lineSequence().forEach { applied.add("    $it") }
        }

        println("  %-72s %s%s".format(action.setCommand(user), status, if (kind.isNotBlank()) " [$kind]" else ""))
        if (!res.ok) {
            val lines = res.output.trim().lineSequence().filter { it.isNotBlank() }.toList()
            lines.take(4).forEach { println("      $it") }
            if (lines.size > 4) println("      ... (${lines.size - 4} more lines in applied.txt)")
        }
        if (verdict != Verdict.EFFECTIVE) {
            println("      复核：${verdict.label}（退出码不代表生效，当前模式 ${afterMode ?: "读不到"}）")
        }

        if (verdict == Verdict.EFFECTIVE) {
            val prior = Verify.appOpMode(before.output, action.op) ?: "default"
            rollback.add(action.rollbackCommand(user, prior))
        }
        changes += ChangeRecord(
            pkg = action.pkg,
            kind = KIND_APPOPS,
            op = action.op,
            command = action.setCommand(user),
            rollback = action.rollbackCommand(user)
        )
    }

    File(result.outDir, "applied.txt").writeText(applied.joinToString("\n", postfix = "\n"))

    val rollbackText = buildString {
        appendLine("# NOIR rollback")
        appendLine("# generated: ${result.meta.generatedAt}")
        appendLine("# tool: ${result.meta.tool}")
        appendLine("# only commands that were verified as effective are listed")
        appendLine("# device: ${result.device.model} (${result.device.serial}) · user ${result.meta.user.ifBlank { "unknown" }}")
        if (rollback.isEmpty()) {
            appendLine("# no appops changes were applied")
        } else {
            rollback.forEach { appendLine(it) }
        }
    }
    File(result.outDir, "rollback.txt").writeText(rollbackText)
    File(result.outDir, "applied.txt").let { println("\n[*] applied: ${it.absolutePath}") }
    println("[*] rollback: ${File(result.outDir, "rollback.txt").absolutePath}")
    println("[*] ${planned.size - failed}/${planned.size} appops commands applied")

    val checks = verifyChanges(adb, result.device.serial, user, changes)
    if (checks.isNotEmpty()) {
        println("[*] 复扫复核: ${Verify.summary(checks)}")
        checks.filter { it.verdict != Verdict.EFFECTIVE }.take(10).forEach {
            println("  ${"%-10s".format(it.verdict.label)} ${it.subject}  ${it.detail}")
        }
        writeVerifyReport(result.outDir, result.meta, result.device, result.rom, checks)
        if (checks.any { it.verdict == Verdict.NO_EFFECT }) println(Guidance.silentRejectNote(result.rom))
    }

    if (failed > 0) {
        println("[!] $failed command(s) failed — full stdout/stderr is in applied.txt")
        if (permissionFailures > 0) {
            println("[i] The device rejected shell-level AppOps changes. On ColorOS/OPPO/OnePlus,")
            println("    check Developer options → Permission monitoring, or use Shizuku.")
        }
    }
}

private fun renderDashboard(args: Args, result: ScanResult, recommendations: List<Recommendation>): String {
    val color = (args.flag("color") || System.console() != null) &&
        !args.flag("no-color") &&
        !args.flag("ascii")
    return Tui.dashboard(
        device = result.device,
        apps = result.apps,
        recommendations = recommendations,
        android = result.android,
        rom = if (result.rom.kind == RomKind.UNKNOWN) "" else result.rom.display,
        host = Platform.describe(),
        installed = result.installed,
        ascii = args.flag("ascii"),
        color = color
    )
}

private fun progressLine(startedMs: Long, done: Int, total: Int): String {
    val elapsed = (System.currentTimeMillis() - startedMs).coerceAtLeast(1)
    val perItem = elapsed.toDouble() / done.coerceAtLeast(1)
    val eta = ((total - done) * perItem / 1000).toLong().coerceAtLeast(0)
    val width = 26
    val pct = done * 100 / total
    val filled = (done.toLong() * width / total).toInt().coerceIn(0, width)
    val bar = "█".repeat(filled) + "░".repeat(width - filled)
    val spin = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"[done % 10]
    return "\r  " + bar + " " + pct + "%  " + done + "/" + total +
        "  ·  ETA " + (eta / 60) + ":" + (eta % 60).toString().padStart(2, '0') + "  " + spin
}


// ── deep scan ──────────────────────────────────────────────────────────────

private data class DeepFinding(
    val pkg: String,
    val score: Int,
    val codeRisk: Int,
    val signals: Map<String, Int>,
    val trackers: Set<String>,
    val manifest: ManifestFacts?,
    val src: File?,
    val error: String?
)

private val DEEP_SIGNALS: Map<String, Pair<Regex, Int>> = linkedMapOf(
    "exec" to (Regex("Runtime\\.exec|ProcessBuilder") to 4),
    "dynamic-load" to (Regex("DexClassLoader|PathClassLoader|loadLibrary") to 3),
    "disable-app" to (Regex("setApplicationEnabledSetting") to 5),
    "a11y-hijack" to (Regex("enabled_accessibility_services") to 5),
    "screen-capture" to (Regex("MediaProjection|createScreenCaptureIntent") to 4),
    "reflection" to (Regex("Class\\.forName|getDeclaredMethod") to 1),
    "crypto" to (Regex("Cipher\\.getInstance|MessageDigest") to 1),
    "network" to (Regex("HttpURLConnection|OkHttpClient|openConnection") to 1),
    "set-prop" to (Regex("SystemProperties\\.set|Settings\\.(Global|Secure)\\.put") to 5),
    "sms" to (Regex("SmsManager|sendTextMessage|content://sms") to 4),
    "device-id" to (Regex("getDeviceId|getImei|getSubscriberId|getAndroidId|getSerial") to 3),
    "webview-bridge" to (Regex("addJavascriptInterface") to 3),
    "accessibility" to (Regex("AccessibilityService") to 4),
    "notification-listener" to (Regex("NotificationListenerService") to 3),
    "device-admin" to (Regex("DeviceAdminReceiver") to 4)
)

private val DEEP_SKIP = listOf(
    "androidx/", "kotlin/", "kotlinx/", "org/intellij/", "org/jetbrains/",
    "com/google/gson/", "okhttp3/", "retrofit2/", "com/google/protobuf/"
)

private fun cmdDeepScan(raw: List<String>) {
    val args = Args(raw)
    val adb = connectAdb(args)
    val scan = performScan(args)
    val outDir = File(scan.outDir, "deepscan").apply { mkdirs() }

    val top = args.value("top")?.toIntOrNull() ?: 15
    val jobs = (args.value("jobs")?.toIntOrNull()
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)).coerceIn(1, 6)
    val targets = if (args.flag("all")) scan.apps else scan.apps.take(top)

    if (targets.isEmpty()) {
        println("没有被标记的应用，不需要 DeepScan。")
        return
    }

    val jadxBin = Platform.findJadx(args.value("jadx"))
        ?: throw NoirException("没有找到 jadx。", Guidance.jadxMissing())

    println()
    println("== DeepScan ==")
    println("  目标    ${targets.size} 个应用（共 ${scan.apps.size} 个被标记）")
    println("  并行    $jobs 个 jadx 进程")
    println("  输出    ${outDir.absolutePath}")
    if (args.flag("all")) println("  注意    --all 会反编译所有被标记应用，可能耗时数小时、占用数 GB")
    println()

    val pool = Executors.newFixedThreadPool(jobs)
    val done = AtomicInteger()
    val started = System.currentTimeMillis()

    val findings = targets.map { app ->
        pool.submit<DeepFinding> {
            val result = try {
                analyzeOne(adb, scan, outDir, jadxBin, app)
            } catch (e: Exception) {
                DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), null, null, e.message ?: e::class.simpleName)
            }
            val n = done.incrementAndGet()
            val elapsed = (System.currentTimeMillis() - started) / 1000
            println("  [$n/${targets.size}] ${app.pkg}  code-risk=${result.codeRisk}  (${elapsed}s)")
            result
        }
    }.map { future ->
        try {
            future.get()
        } catch (e: Exception) {
            DeepFinding("unknown", 0, 0, emptyMap(), emptySet(), null, null, e.message)
        }
    }
    pool.shutdown()

    val ranked = findings.sortedByDescending { it.codeRisk }
    writeDeepReports(outDir, ranked)

    println()
    println("%5s %5s  %-44s %s".format("RISK", "SCAN", "PACKAGE", "SIGNALS"))
    ranked.take(20).forEach { f ->
        val signals = f.signals.entries.sortedByDescending { it.value }.take(3)
            .joinToString(", ") { "${it.key}x${it.value}" }
            .ifBlank { f.error ?: "-" }
        println("%5d %5d  %-44s %s".format(f.codeRisk, f.score, f.pkg, signals))
    }
    println()
    println("[+] ${File(outDir, "deepscan.md").absolutePath}")
    println("[+] ${File(outDir, "deepscan.tsv").absolutePath}")

    offerCleanup(args, adb, scan, ranked)
}

private fun analyzeOne(adb: Adb, scan: ScanResult, outDir: File, jadxBin: String, app: AppEntry): DeepFinding {
    val remote = scan.paths[app.pkg]
        ?: return DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), null, null, "no apk path")

    val apk = File(outDir, "apks/${app.pkg}.apk")
    if (!apk.exists()) {
        apk.parentFile?.mkdirs()
        adb.pull(scan.device.serial, remote, apk)
    }
    if (!apk.exists()) {
        return DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), null, null, "pull failed")
    }

    val src = File(outDir, "src/${app.pkg}")
    val marker = File(src, ".noir-ok")
    val cached = marker.exists() && marker.readText().trim() == remote
    if (!cached) {
        src.deleteRecursively()
        src.mkdirs()
    }

    val exit = if (cached) 0 else Jadx(jadxBin).decompile(apk, src)
    val sources = File(src, "sources")
    if (exit != 0 && !sources.isDirectory) {
        return DeepFinding(app.pkg, app.score, 0, emptyMap(), emptySet(), ManifestAnalyzer.analyze(apk), null, "jadx exit $exit")
    }
    runCatching { marker.writeText(remote) }

    val signals = countSignals(src)
    var risk = DEEP_SIGNALS.entries.sumOf { (key, def) ->
        (signals[key] ?: 0).coerceAtMost(3) * def.second
    }
    val manifest = ManifestAnalyzer.analyze(apk)
    manifest?.let { facts ->
        risk += facts.riskyCount.coerceAtMost(4)
        if (facts.cleartext == true && app.uploadEndpoints.isNotEmpty()) risk += 2
        if (facts.allowBackup == true) risk += 1
        if (facts.debuggable == true) risk += 3
    }

    return DeepFinding(
        app.pkg, app.score, risk.coerceAtMost(40), signals,
        Trackers.fromEndpoints(app.endpoints) + Trackers.fromSources(src),
        manifest, src, null
    )
}

private fun countSignals(dir: File): Map<String, Int> {
    val counts = HashMap<String, Int>()
    if (!dir.exists()) return counts

    // jadx writes decompiled classes under <out>/sources; skip bundled libraries by
    // their path relative to that root, not the output root.
    val root = File(dir, "sources").takeIf { it.isDirectory } ?: dir
    var budget = 40L * 1024 * 1024
    val files = root.walkTopDown()
        .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
        .filter { file ->
            val path = file.relativeTo(root).path
            DEEP_SKIP.none { path.startsWith(it) }
        }
        .take(8000)

    for (file in files) {
        if (budget <= 0) break
        val text = try {
            file.readText()
        } catch (e: Exception) {
            continue
        }
        budget -= text.length
        DEEP_SIGNALS.forEach { (key, def) ->
            val hits = def.first.findAll(text).count()
            if (hits > 0) counts[key] = (counts[key] ?: 0) + hits
        }
    }
    return counts
}

private fun writeDeepReports(outDir: File, findings: List<DeepFinding>) {
    val tsv = StringBuilder("code_risk\tscan_score\tpackage\tsignals\ttrackers\texported_no_perm\terror\n")
    findings.forEach { f ->
        val signals = f.signals.entries.joinToString(";") { "${it.key}=${it.value}" }
        tsv.append(f.codeRisk).append('\t')
            .append(f.score).append('\t')
            .append(f.pkg).append('\t')
            .append(signals).append('\t')
            .append(f.trackers.joinToString(",")).append('\t')
            .append(f.manifest?.exportedWithoutPermission?.size ?: 0).append('\t')
            .append(f.error ?: "").append('\n')
    }
    File(outDir, "deepscan.tsv").writeText(tsv.toString())

    val md = StringBuilder()
    md.append("# DeepScan\n\n")
    md.append("| code risk | scan score | package | signals | trackers | error |\n")
    md.append("|---:|---:|---|---|---|---|\n")
    findings.forEach { f ->
        val signals = f.signals.entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ×${it.value}" }
        md.append("| ").append(f.codeRisk)
            .append(" | ").append(f.score)
            .append(" | `").append(f.pkg).append("`")
            .append(" | ").append(signals.ifBlank { "—" })
            .append(" | ").append(f.trackers.joinToString(", ").ifBlank { "—" })
            .append(" | ").append(f.error ?: "")
            .append(" |\n")
    }
    md.append("\n## details\n")
    findings.forEach { f ->
        md.append("\n### ").append(f.pkg).append("\n\n")
        md.append("- code risk: ").append(f.codeRisk).append("\n")
        md.append("- scan score: ").append(f.score).append("\n")
        if (f.signals.isNotEmpty()) {
            md.append("- signals: ").append(f.signals.entries.joinToString(", ") { "${it.key}=${it.value}" }).append("\n")
        }
        if (f.trackers.isNotEmpty()) {
            md.append("- trackers: ").append(f.trackers.joinToString(", ")).append("\n")
        }
        f.manifest?.let { m ->
            md.append("- targetSdk: ").append(m.targetSdk ?: "?").append("\n")
            md.append("- cleartext: ").append(m.cleartext ?: "unset")
                .append(" · allowBackup: ").append(m.allowBackup ?: "unset")
                .append(" · debuggable: ").append(m.debuggable ?: "false")
                .append(" · networkSecurityConfig: ").append(m.networkSecurityConfig).append("\n")
            if (m.exportedWithoutPermission.isNotEmpty()) {
                md.append("- exported without permission (").append(m.exportedWithoutPermission.size).append("):\n")
                m.exportedWithoutPermission.take(10).forEach { c ->
                    md.append("  - ").append(c.kind).append(" `").append(c.name).append("`")
                    c.authorities?.let { md.append(" (").append(it).append(")") }
                    md.append("\n")
                }
            }
        }
        f.src?.let { md.append("- sources: `").append(it.absolutePath).append("`\n") }
        if (f.error != null) md.append("- error: ").append(f.error).append("\n")
    }
    File(outDir, "deepscan.md").writeText(md.toString())
}

private fun offerCleanup(args: Args, adb: Adb, scan: ScanResult, findings: List<DeepFinding>) {
    val high = findings.filter { it.codeRisk >= 8 && it.error == null }
    if (high.isEmpty()) {
        println()
        println("没有代码级高风险项，不需要额外清理。")
        return
    }

    val highPkgs = high.map { it.pkg }.toSet()
    val appsByPkg = scan.apps.associateBy { it.pkg }
    val planned = plannedActions(Advisor.advise(scan.apps).filter { it.pkg in highPkgs })

    println()
    println("== 代码级高风险 ${high.size} 个 ==")
    high.forEach { f ->
        val system = appsByPkg[f.pkg]?.isSystem ?: true
        println("  %-44s risk %2d  %s".format(f.pkg, f.codeRisk, if (system) "system" else "user"))
    }
    println("  可执行的安全 appops: ${planned.size} 条")

    if (!args.flag("apply")) {
        println()
        println("[*] dry-run — 加 --apply 执行安全清理；加 --purge 可同时卸载高风险用户应用")
        return
    }

    if (!args.flag("yes")) {
        if (!isInteractive()) {
            println("[!] --apply 在非交互环境下需要同时加 --yes")
            return
        }
        print("执行这 ${planned.size} 条 appops? [y/N] ")
        val answer = readLine()?.trim()?.lowercase()
        if (answer != "y" && answer != "yes") {
            println("已跳过。")
            return
        }
    }
    applyCommands(adb, scan, planned)

    if (!args.flag("purge")) return

    val userHigh = high.filter { appsByPkg[it.pkg]?.isSystem == false }
    if (userHigh.isEmpty()) {
        println("高风险项里没有用户应用，无需卸载。")
        return
    }
    println()
    println("== 卸载高风险用户应用 ==")
    println("# 恢复命令：adb shell pm install-existing --user 0 <package>")
    userHigh.forEach { f ->
        if (!args.flag("yes") && isInteractive()) {
            print("  卸载 ${f.pkg}? [y/N] ")
            val answer = readLine()?.trim()?.lowercase()
            if (answer != "y" && answer != "yes") return@forEach
        }
        val result = adb.shell(scan.device.serial, "pm uninstall --user 0 ${f.pkg}")
        println("  %-44s %s".format(f.pkg, if (result.ok) "uninstalled" else "failed (${result.code})"))
    }
}

// ── plumbing ───────────────────────────────────────────────────────────────

private fun isInteractive(): Boolean =
    System.console() != null || System.getenv("NOIR_INTERACTIVE") == "1"

private fun connectAdb(args: Args): Adb {
    val explicit = args.value("adb")
    if (explicit != null && !File(explicit).exists()) {
        throw NoirException("指定的 adb 不存在：$explicit", "检查路径，或运行 noir setup 自动安装。")
    }
    val bin = Platform.findAdb(explicit)
        ?: throw NoirException("没有找到 adb。", Guidance.adbMissing())
    return Adb(bin)
}

private fun awaitDevice(adb: Adb, requested: String?, interactive: Boolean): Device {
    var waited = 0
    var printed = false

    while (true) {
        val devices = adb.devices()
        val online = devices.filter { it.state == "device" }

        if (requested != null) {
            online.firstOrNull { it.serial == requested }?.let { return it }
            if (online.isNotEmpty()) {
                throw NoirException(
                    "没有找到设备 $requested。",
                    "当前已连接：" + online.joinToString { "${it.serial} (${it.model})" }
                )
            }
        } else if (online.size == 1) {
            return online.first()
        } else if (online.size > 1) {
            // Prefer a physical device over an emulator when both are attached.
            val physical = online.filterNot { it.serial.startsWith("emulator-") }
            if (physical.size == 1) return physical.first()

            if (interactive) {
                println("检测到多台设备：")
                online.forEachIndexed { index, device ->
                    println("  ${index + 1}) ${device.serial}  ${device.model}")
                }
                print("选择设备 [1-${online.size}] ")
                val choice = readLine()?.trim()?.toIntOrNull()
                if (choice != null && choice in 1..online.size) return online[choice - 1]
                throw NoirException("没有选择设备。", "用 --serial <id> 指定要扫描的设备。")
            }

            val chosen = physical.firstOrNull() ?: online.first()
            println("提示：检测到 ${online.size} 台设备，使用 ${chosen.serial}；可用 --serial 指定。")
            return chosen
        }

        val unauthorized = devices.any { it.state == "unauthorized" }
        if (!printed) {
            println(if (unauthorized) Guidance.unauthorizedGuide() else Guidance.deviceGuide())
            printed = true
        }

        if (!interactive) {
            throw NoirException(
                "没有可用设备。",
                if (unauthorized) Guidance.unauthorizedGuide() else "插上手机后重新运行，或先运行 noir doctor。"
            )
        }
        if (waited >= 180) throw NoirException("等了 180 秒仍然没有设备。", Guidance.deviceGuide())

        print("\r  等待设备接入 ... ${180 - waited}s   ")
        System.out.flush()
        Thread.sleep(2000)
        waited += 2
    }
}

private class Args(raw: List<String>) {
    val positional = ArrayList<String>()
    val flags = HashSet<String>()
    val values = HashMap<String, String>()

    init {
        var i = 0
        while (i < raw.size) {
            val arg = raw[i]
            if (arg.startsWith("--")) {
                val name = arg.substring(2)
                val takesValue = name !in BOOLEAN_FLAGS && i + 1 < raw.size && !raw[i + 1].startsWith("--")
                if (takesValue) {
                    values[name] = raw[i + 1]
                    i++
                } else {
                    flags.add(name)
                }
            } else {
                positional.add(arg)
            }
            i++
        }
    }

    fun flag(name: String): Boolean = name in flags
    fun value(name: String): String? = values[name]
}
