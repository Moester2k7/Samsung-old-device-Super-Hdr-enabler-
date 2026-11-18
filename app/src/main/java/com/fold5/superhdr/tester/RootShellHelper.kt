package com.fold5.superhdr.tester

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RootShellHelper {

    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    suspend fun runSuCommand(vararg commands: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                for (cmd in commands) {
                    os.write(cmd.toByteArray(Charsets.UTF_8))
                    os.write("\n".toByteArray(Charsets.UTF_8))
                }
                os.write("exit\n".toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val out = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { it.readText() }
            val err = BufferedReader(InputStreamReader(process.errorStream, Charsets.UTF_8)).use { it.readText() }
            val code = process.waitFor()
            ShellResult(code, out.trim(), err.trim())
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "无法调用 su，请确认 Magisk/KernelSU/APatch 已授予超级用户权限")
        }
    }

    suspend fun runFullFold5Diagnostics(): String {
        val checkSu = runSuCommand("id")
        if (!checkSu.isSuccess || !checkSu.stdout.contains("uid=0")) {
            return "❌ Root 授权失败：" + checkSu.stderr.ifEmpty {
                "未检测到 uid=0，请在 Magisk / KernelSU / APatch 中授予本应用 Root 权限。"
            }
        }

        val script = listOf(
            "echo '=== [1] Display HDR/SDR Ratio 状态 ==='",
            "dumpsys display | grep -E 'mHdrSdrRatio|sdrHdrRatioMap|hdrCapabilities|mBrightnessCap' | head -n 24",
            "echo ''",
            "echo '=== [2] One UI FloatingFeature SuperHDR 键值 ==='",
            "grep -E 'PHOTOHDR|HDR_EFFECT|SUPER_HDR' /system/etc/floating_feature.xml 2>/dev/null || echo '未在 floating_feature.xml 中检索到对应键值'",
            "echo ''",
            "echo '=== [3] DisplayConfig 硬件映射文件检查 ==='",
            "for f in /vendor/etc/displayconfig/*.xml /product/etc/displayconfig/*.xml; do " +
                "if [ -f \"\$f\" ]; then " +
                "c=\$(grep -c 'sdrHdrRatioMap' \"\$f\"); " +
                "echo \"\$f -> sdrHdrRatioMap节点数: \$c\"; " +
                "fi; done",
            "echo ''",
            "echo '=== [4] 当前系统 HDR 设置项 ==='",
            "echo \"hdr_effect = \$(settings get system hdr_effect)\"",
            "echo \"super_hdr = \$(settings get system super_hdr)\""
        )

        val res = runSuCommand(*script.toTypedArray())
        return if (res.isSuccess) res.stdout else "⚠️ 诊断执行返回非零状态：\n${res.stderr}\n${res.stdout}"
    }

    suspend fun applyRuntimeRootHdrTweaks(): String {
        val cmds = arrayOf(
            "settings put system hdr_effect 1",
            "settings put system super_hdr 1",
            "settings put system sec_hdr_effect 1",
            "cmd display set-user-disabled-hdr-types",
            "echo '✅ 已通过 Root 写入 hdr_effect=1 / super_hdr=1 并清空 HDR 格式禁用列表'"
        )
        val res = runSuCommand(*cmds)
        return if (res.isSuccess) res.stdout else "❌ 写入失败: ${res.stderr}"
    }

    suspend fun buildMagiskDisplayConfigOverlay(context: Context): String = withContext(Dispatchers.IO) {
        val listRes = runSuCommand("ls /vendor/etc/displayconfig/*.xml 2>/dev/null")
        if (!listRes.isSuccess || listRes.stdout.isBlank()) {
            return@withContext "❌ 未找到 /vendor/etc/displayconfig/*.xml，请检查本机固件路径。"
        }

        val xmlPaths = listRes.stdout.lines().map { it.trim() }.filter { it.endsWith(".xml") }
        val outFile = File(context.getExternalFilesDir(null), "Fold5-SuperHDR-Overlay-Magisk.zip")

        val ratioSnippet = listOf(
            "        <sdrHdrRatioMap>",
            "            <point>",
            "                <sdrNits>2.0</sdrNits>",
            "                <hdrRatio>4.0</hdrRatio>",
            "            </point>",
            "            <point>",
            "                <sdrNits>300.0</sdrNits>",
            "                <hdrRatio>3.2</hdrRatio>",
            "            </point>",
            "            <point>",
            "                <sdrNits>600.0</sdrNits>",
            "                <hdrRatio>2.4</hdrRatio>",
            "            </point>",
            "            <point>",
            "                <sdrNits>1200.0</sdrNits>",
            "                <hdrRatio>1.45</hdrRatio>",
            "            </point>",
            "        <sdrHdrRatioMap>".replace("<sdrHdrRatioMap>", "</sdrHdrRatioMap>")
        ).joinToString("\n")

        val moduleProp = listOf(
            "id=fold5_superhdr_overlay",
            "name=Galaxy Z Fold5 Super HDR DisplayConfig Unlock",
            "version=v1.0",
            "versionCode=1",
            "author=SuperHDRLab",
            "description=Injects sdrHdrRatioMap for Fold5 inner & cover displays to enable Android 14+ Gainmap Ultra HDR."
        ).joinToString("\n")

        var patchedCount = 0
        ZipOutputStream(FileOutputStream(outFile)).use { zos ->
            zos.putNextEntry(ZipEntry("module.prop"))
            zos.write(moduleProp.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            for (path in xmlPaths) {
                val catRes = runSuCommand("cat \"$path\"")
                if (!catRes.isSuccess || catRes.stdout.isBlank()) continue
                var xml = catRes.stdout
                if (!xml.contains("<sdrHdrRatioMap>") && xml.contains("</highBrightnessMode>")) {
                    xml = xml.replace("</highBrightnessMode>", "$ratioSnippet\n    </highBrightnessMode>")
                    val entryName = "system/vendor/etc/displayconfig/" + File(path).name
                    zos.putNextEntry(ZipEntry(entryName))
                    zos.write(xml.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    patchedCount++
                }
            }
        }

        if (patchedCount == 0) {
            outFile.delete()
            "⚠️ 本机 displayconfig 中已包含 sdrHdrRatioMap 或未找到 <highBrightnessMode> 标签，无需重复生成补丁。"
        } else {
            val publicPath = "/sdcard/Download/Fold5-SuperHDR-Overlay-Magisk.zip"
            runSuCommand("cp \"${outFile.absolutePath}\" \"$publicPath\"")
            "✅ 已提取本机 $patchedCount 个屏幕配置文件并生成 Magisk/KernelSU 模块：\n$publicPath\n（注意：刷入前请确认已配置 Magisk 安全模式救砖保护；刷入重启后即可在『引擎 B：原生 Gainmap』中验证系统级原生局部高光激发）"
        }
    }
}
