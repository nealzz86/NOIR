/*
 * NOIR — Android leak audit
 * Copyright (c) 2026 Verlintas
 * SPDX-License-Identifier: MIT
 */
package app.noir.cli

import app.noir.core.Device
import app.noir.core.ReportMeta
import app.noir.core.Verdict
import app.noir.core.adb.Adb
import app.noir.core.platform.Platform
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyTest {

    private fun fixture(scriptBody: String): Pair<File, ScanResult> {
        val dir = Files.createTempDirectory("noir-apply").toFile()
        val state = File(dir, "state")
        val script = File(dir, "fake-adb")
        script.writeText(
            """
            #!/bin/sh
            STATE="$state"
            cmd="${'$'}4"
            $scriptBody
            """.trimIndent() + "\n"
        )
        script.setExecutable(true)
        return script to scanResult(dir)
    }

    private fun scanResult(dir: File) = ScanResult(
        device = Device("serial", "device", "PLK110", "p1"),
        apps = emptyList(),
        outDir = dir,
        android = "16",
        installed = 0,
        paths = emptyMap(),
        meta = ReportMeta(
            tool = "NOIR test",
            host = "test host",
            android = "16",
            sdk = "36",
            build = "PLK110_16.0.0",
            fingerprint = "test/fingerprint",
            user = "0",
            generatedAt = "2026-11-04T00:00:00Z"
        )
    )

    @Test
    fun `failed appops keeps stdout and stderr in applied txt`() {
        if (Platform.isWindows) return
        val (script, result) = fixture(
            """
            case "${'$'}cmd" in
              *"cmd appops get"*) echo "PROJECT_MEDIA: default"; exit 0 ;;
              *"cmd appops set"*) echo "java.lang.SecurityException: uid 2000 does not have MANAGE_APP_OPS_MODES" >&2; exit 255 ;;
            esac
            exit 1
            """.trimIndent()
        )

        applyCommands(Adb(script.absolutePath), result, listOf(AppOpAction("com.example", "PROJECT_MEDIA")))

        val applied = File(result.outDir, "applied.txt").readText()
        val rollback = File(result.outDir, "rollback.txt").readText()
        assertTrue(applied.contains("failed (255) [system permission]"))
        assertTrue(applied.contains("SecurityException"))
        assertTrue(applied.contains("before: PROJECT_MEDIA: default"))
        assertTrue(applied.contains("after:  PROJECT_MEDIA: default"))
        assertTrue(applied.contains("user: 0"))
        assertTrue(rollback.contains("no appops changes were applied"))
        assertFalse(rollback.contains("adb shell cmd appops set"))
    }

    @Test
    fun `successful appops records the precise previous mode`() {
        if (Platform.isWindows) return
        val (script, result) = fixture(
            """
            n=${'$'}(cat "${'$'}STATE" 2>/dev/null || echo 0)
            n=${'$'}((n + 1))
            echo "${'$'}n" > "${'$'}STATE"
            case "${'$'}cmd" in
              *"cmd appops get"*)
                if [ "${'$'}n" -eq 1 ]; then echo "PROJECT_MEDIA: default"; else echo "PROJECT_MEDIA: ignore"; fi
                exit 0 ;;
              *"cmd appops set"*) exit 0 ;;
            esac
            exit 1
            """.trimIndent()
        )

        applyCommands(Adb(script.absolutePath), result, listOf(AppOpAction("com.example", "PROJECT_MEDIA")))

        val applied = File(result.outDir, "applied.txt").readText()
        val rollback = File(result.outDir, "rollback.txt").readText()
        assertTrue(applied.contains("cmd appops set --user 0 com.example PROJECT_MEDIA ignore\tok (0)"))
        assertTrue(applied.contains("after:  PROJECT_MEDIA: ignore"))
        assertTrue(rollback.contains("adb shell cmd appops set --user 0 com.example PROJECT_MEDIA default"))
    }

    /**
     * The ColorOS case: every command exits 0 and nothing changes. A rollback file
     * full of commands for state that was never touched would be worse than none.
     *
     * Written for both shells because this is the behaviour the feature exists for,
     * and CI on macOS alone would leave it unproven on the platform most users run.
     */
    @Test
    fun `a device that reports success without changing the mode writes no rollback`() {
        val dir = Files.createTempDirectory("noir-apply").toFile()
        val script = if (Platform.isWindows) {
            File(dir, "fake-adb.cmd").apply {
                writeText(
                    """
                    @echo off
                    set "OP=%~4"
                    echo %OP% | findstr /C:"appops get" >nul && (echo PROJECT_MEDIA: default& exit /b 0)
                    echo %OP% | findstr /C:"appops set" >nul && exit /b 0
                    exit /b 1
                    """.trimIndent()
                )
            }
        } else {
            File(dir, "fake-adb").apply {
                writeText(
                    """
                    #!/bin/sh
                    cmd="${'$'}4"
                    case "${'$'}cmd" in
                      *"cmd appops get"*) echo "PROJECT_MEDIA: default"; exit 0 ;;
                      *"cmd appops set"*) exit 0 ;;
                    esac
                    exit 1
                    """.trimIndent() + "\n"
                )
                setExecutable(true)
            }
        }
        val result = scanResult(dir)

        applyCommands(Adb(script.absolutePath), result, listOf(AppOpAction("com.example", "PROJECT_MEDIA")))

        val applied = File(result.outDir, "applied.txt").readText()
        val rollback = File(result.outDir, "rollback.txt").readText()
        val verify = File(result.outDir, "verify.txt").readText()

        assertTrue(applied.contains("ok (0)"), applied)
        assertTrue(applied.contains("复核: ${Verdict.NO_EFFECT.label}"), applied)
        assertTrue(rollback.contains("no appops changes were applied"), rollback)
        assertTrue(verify.contains(Verdict.NO_EFFECT.label), verify)
        assertTrue(verify.contains("复核读的是设备当前状态，不是命令退出码"), verify)
    }
}
