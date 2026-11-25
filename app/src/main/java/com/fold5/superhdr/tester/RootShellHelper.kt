package com.fold5.superhdr.tester

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

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
            ShellResult(-1, "", e.message ?: "无法调用 su，请确认已在 Magisk/KernelSU/APatch 授权")
        }
    }

    suspend fun runFullFold5Diagnostics(): String {
        val checkSu = runSuCommand("id")
        if (!checkSu.isSuccess || !checkSu.stdout.contains("uid=0")) {
            return "Root 授权失败：" + checkSu.stderr.ifEmpty {
                "未检测到 uid=0，请在 Magisk / KernelSU / APatch 中授予本应用超级用户权限。"
            }
        }

        val script = listOf(
            "echo '=== [1] Display HBM & HdrBrightnessData & HdrBrightnessModifier ==='",
            "dumpsys display | grep -E 'mHbmData=|mHdrBrightnessData=|hdrSdrRatio|hdrCapabilities|mIsHdrLayerPresent|mMode=|mSupportPhotoHdr|mSupportHdrSolution|mLowPowerMode='",
            "echo ''",
            "echo '=== [2] SurfaceFlinger 实时 HDR 图层事件 ==='",
            "dumpsys SurfaceFlinger | grep -i -E 'HDR events|numHdrLayers|desiredRatio' | tail -n 15",
            "echo ''",
            "echo '=== [3] One UI FloatingFeature SuperHDR 键值 ==='",
            "grep -E 'PHOTOHDR|HDR_EFFECT|SUPER_HDR|HW_HDR' /system/etc/floating_feature.xml 2>/dev/null || echo '未找到对应键值'",
            "echo ''",
            "echo '=== [4] 当前系统 HDR 设置项 ==='",
            "echo \"hdr_effect = \$(settings get system hdr_effect)\"",
            "echo \"super_hdr = \$(settings get system super_hdr)\"",
            "echo \"sec_superhdr = \$(settings get system sec_superhdr)\"",
            "echo \"low_power = \$(settings get global low_power)\"",
            "echo ''",
            "echo '=== [5] LSPosed 模块加载日志 (Fold5SuperHDR) ==='",
            "grep -i 'Fold5SuperHDR' /data/adb/lspd/log/modules_*.log 2>/dev/null | tail -n 20 || echo '未检测到日志'"
        )

        val res = runSuCommand(*script.toTypedArray())
        return if (res.isSuccess) res.stdout else "诊断执行异常：\n" + res.stderr + "\n" + res.stdout
    }

    suspend fun exportDiagnosticsToDownload(report: String): String {
        val targetPath = "/sdcard/Download/fold5_hdr_diag_v4.txt"
        val escaped = report.replace("'", "'\\''")
        val res = runSuCommand("printf '%s\\n' '$escaped' > $targetPath")
        return if (res.isSuccess) {
            "已导出至 " + targetPath + " 并复制到系统剪贴板"
        } else {
            "已复制到剪贴板（写入 Download 失败: " + res.stderr + "）"
        }
    }

    suspend fun applyRuntimeRootHdrTweaks(): String {
        val cmds = arrayOf(
            "settings put system hdr_effect 1",
            "settings put system super_hdr 1",
            "settings put system sec_superhdr 1",
            "settings put system sec_hdr_effect 1",
            "service call SurfaceFlinger 1138 i32 1 >/dev/null 2>&1 || true",
            "cmd display set-user-disabled-hdr-types",
            "echo '已通过 Root 写入 hdr_effect=1 / sec_superhdr=1，下发 SurfaceFlinger 1138=1 并清空 HDR 格式禁用列表'"
        )
        val res = runSuCommand(*cmds)
        return if (res.isSuccess) res.stdout else "写入失败: " + res.stderr
    }
}
