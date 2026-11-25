package com.fold5.superhdr.tester

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.view.Display
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import kotlin.math.pow

class XposedInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "Fold5SuperHDR"
        private const val KILL_SWITCH_PATH = "/data/local/tmp/disable_fold5_hdr"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android" && File(KILL_SWITCH_PATH).exists()) {
            XposedBridge.log("$TAG: Safe kill-switch $KILL_SWITCH_PATH exists, skipping system_server hooks")
            return
        }

        if (lpparam.packageName == "android" ||
            lpparam.packageName == "com.sec.android.gallery3d" ||
            lpparam.packageName == "com.android.settings" ||
            lpparam.packageName == "com.google.android.apps.photos"
        ) {
            XposedBridge.log("$TAG: Loaded in ${lpparam.packageName} (process=${lpparam.processName})")
        }

        // 1. 解锁三星 One UI SemFloatingFeature 中的 PhotoHDR / HW_HDR / Super HDR 机型锁
        hookSamsungFloatingFeature(lpparam)

        // 2. 修复 Fold 5 虚标的 400/450 nits 静态峰值上限 -> 1500 nits (与硬件 sdrToHdrSpline 对齐)
        hookHdrCapabilities()

        if (lpparam.packageName == "android") {
            // 3. 核心突破：针对 Fold 5 (Android 17 / One UI 9) 实机 services.jar 逆向定位的真实拦截链路
            hookSystemServerHdrGatekeepers(lpparam)
        } else if (lpparam.packageName != "com.android.settings" &&
            lpparam.packageName != "com.fold5.superhdr.tester"
        ) {
            // 4. 在被勾选的第三方相册/社交应用中自动启用 HDR 窗口并为普通 SDR 大图实时注入 Gainmap
            hookClientAppAutoGainmap()
        }
    }

    private fun hookSamsungFloatingFeature(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val cls = XposedHelpers.findClassIfExists(
                "com.samsung.android.feature.SemFloatingFeature",
                lpparam.classLoader
            ) ?: return

            XposedBridge.hookAllMethods(cls, "getBoolean", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.firstOrNull() as? String ?: return
                    if (key.contains("PHOTOHDR") ||
                        key.contains("HW_HDR") ||
                        key.contains("SUPER_HDR") ||
                        key.contains("HDR_EFFECT") ||
                        key.contains("ULTRA_HDR")
                    ) {
                        param.result = true
                    }
                }
            })

            XposedBridge.hookAllMethods(cls, "getInt", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args.firstOrNull() as? String ?: return
                    if (key == "SEC_FLOATING_FEATURE_LCD_CONFIG_HDR_EFFECT") {
                        param.result = 2
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$TAG: hookSamsungFloatingFeature failed in ${lpparam.packageName}: $it")
        }
    }

    private fun hookHdrCapabilities() {
        runCatching {
            val capCls = Display.HdrCapabilities::class.java
            XposedBridge.hookAllMethods(capCls, "getDesiredMaxLuminance", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val orig = (param.result as? Float) ?: return
                    if (orig < 1500f) param.result = 1500f
                }
            })
            XposedBridge.hookAllMethods(capCls, "getDesiredMaxAverageLuminance", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val orig = (param.result as? Float) ?: return
                    if (orig < 1000f) param.result = 1000f
                }
            })
        }
        // 注：不再在客户端强制伪造 Display.isHdrSdrRatioAvailable() = true，避免遮蔽 system_server 真实状态；
        // Fold 5 原生 LocalDisplayAdapter 已开启 mUseSurfaceControlBrightness，在解锁比率曲线后会真实上报 1.0~3.0x。
    }

    /**
     * 基于 Fold 5 (F946BXXU7HZIJ / Android 17 / One UI 9) services.jar 实机反编译的精准拦截点：
     * 1) DisplayDeviceConfig.initFromGlobalXml(): R8 内联构造 mHbmData (config.HighBrightnessModeData) 与
     *    mHdrBrightnessData (config.HdrBrightnessData)，直接跳过构造函数；需在 initFromGlobalXml() 返回后修补字段并从
     *    mSdrNits/mHdrNits 动态生成 sdrToHdrRatioSpline。
     * 2) HdrBrightnessModifier (Android 15+ 主控模块): 硬编码 mThresRatio=0.9f、且 mLowPowerMode=true 结合
     *    allowInLowPowerMode=false 会拦截激发；图层事件入口为内部类 HdrBrightnessModifier$1.onHdrInfoChanged。
     * 3) HighBrightnessModeController & $HdrListener: 同步解除 mThresRatio=0.9f 与 $HdrListener.onHdrInfoChanged。
     */
    private fun hookSystemServerHdrGatekeepers(lpparam: XC_LoadPackage.LoadPackageParam) {
        val fallbackRatioSpline = createFold5HdrRatioSpline(lpparam.classLoader)

        // A1. 核心：Hook DisplayDeviceConfig.initFromGlobalXml() (Fold 5 无 display_id_*.xml 时走此路径且内联构造)
        runCatching {
            val ddcCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.DisplayDeviceConfig",
                lpparam.classLoader
            )
            if (ddcCls != null) {
                XposedBridge.hookAllMethods(ddcCls, "initFromGlobalXml", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ddc = param.thisObject ?: return
                        val hbm = getObjectSafe(ddc, "mHbmData")
                        val hdr = getObjectSafe(ddc, "mHdrBrightnessData")
                        val spline = buildRatioSplineFromDdc(ddc, lpparam.classLoader) ?: fallbackRatioSpline
                        patchHdrDataObjects(hbm, hdr, spline)
                        XposedBridge.log("$TAG: Patched DisplayDeviceConfig.initFromGlobalXml (hbm=${hbm != null}, hdr=${hdr != null}, spline=${spline != null})")
                    }
                })
                XposedBridge.hookAllMethods(ddcCls, "loadConfigFromDisplayConfigFile", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ddc = param.thisObject ?: return
                        val hbm = getObjectSafe(ddc, "mHbmData")
                        val hdr = getObjectSafe(ddc, "mHdrBrightnessData")
                        val spline = buildRatioSplineFromDdc(ddc, lpparam.classLoader) ?: fallbackRatioSpline
                        patchHdrDataObjects(hbm, hdr, spline)
                    }
                })
            }
        }.onFailure { XposedBridge.log("$TAG: DisplayDeviceConfig hook failed: $it") }

        // A2. 兜底 Hook HdrBrightnessData / HighBrightnessModeData (静态工厂 + 构造函数，包名 config.*)
        runCatching {
            val hdrDataCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.config.HdrBrightnessData",
                lpparam.classLoader
            )
            if (hdrDataCls != null) {
                XposedBridge.hookAllConstructors(hdrDataCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        patchHdrDataObjects(null, param.thisObject, fallbackRatioSpline)
                    }
                })
                XposedBridge.hookAllMethods(hdrDataCls, "loadConfig", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        patchHdrDataObjects(null, param.result, fallbackRatioSpline)
                    }
                })
            }
        }

        for (hbmClsName in listOf(
            "com.android.server.display.config.HighBrightnessModeData",
            "com.android.server.display.DisplayDeviceConfig\$HighBrightnessModeData"
        )) {
            runCatching {
                val hbmDataCls = XposedHelpers.findClassIfExists(hbmClsName, lpparam.classLoader) ?: return@runCatching
                XposedBridge.hookAllConstructors(hbmDataCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        patchHdrDataObjects(param.thisObject, null, fallbackRatioSpline)
                    }
                })
                XposedBridge.hookAllMethods(hbmDataCls, "loadHighBrightnessModeData", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        patchHdrDataObjects(param.result, null, fallbackRatioSpline)
                    }
                })
            }
        }

        // B. 核心：Hook HdrBrightnessModifier 及其内部 SurfaceControlHdrLayerInfoListener (HdrBrightnessModifier$1)
        runCatching {
            val modCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.brightness.clamper.HdrBrightnessModifier",
                lpparam.classLoader
            )
            if (modCls != null) {
                val patchModifier = { mod: Any ->
                    setFloatSafe(mod, "mThresRatio", 0.0f)
                    setBooleanSafe(mod, "mSupportHdrSolution", true)
                    setBooleanSafe(mod, "mSupportPhotoHdr", true)
                    setBooleanSafe(mod, "mLowPowerMode", false)
                    val ddc = getObjectSafe(mod, "mDisplayDeviceConfig")
                    val hdrData = getObjectSafe(mod, "mHdrBrightnessData")
                    val spline = (ddc?.let { buildRatioSplineFromDdc(it, lpparam.classLoader) }) ?: fallbackRatioSpline
                    patchHdrDataObjects(getObjectSafe(ddc, "mHbmData"), hdrData, spline)
                }
                XposedBridge.hookAllConstructors(modCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mod = param.thisObject ?: return
                        patchModifier(mod)
                        XposedBridge.log("$TAG: Patched HdrBrightnessModifier.<init> (mThresRatio=0, lowPowerBypass=true)")
                    }
                })
                XposedBridge.hookAllMethods(modCls, "onDisplayChanged", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mod = param.thisObject ?: return
                        patchModifier(mod)
                    }
                })
                XposedBridge.hookAllMethods(modCls, "recalculateMode", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val mod = param.thisObject ?: return
                        setFloatSafe(mod, "mThresRatio", 0.0f)
                        setBooleanSafe(mod, "mLowPowerMode", false)
                        setBooleanSafe(mod, "mHdrBrightnessEnabled", true)
                        val hdrArg = param.args.firstOrNull()
                        if (hdrArg != null) {
                            patchHdrDataObjects(null, hdrArg, fallbackRatioSpline)
                        }
                    }
                })
            }
        }.onFailure { XposedBridge.log("$TAG: HdrBrightnessModifier hook failed: $it") }

        // C. Hook HdrBrightnessModifier$1 与 HighBrightnessModeController$HdrListener 的 onHdrInfoChanged
        for (listenerClsName in listOf(
            "com.android.server.display.brightness.clamper.HdrBrightnessModifier\$1",
            "com.android.server.display.HighBrightnessModeController\$HdrListener"
        )) {
            runCatching {
                val listenerCls = XposedHelpers.findClassIfExists(listenerClsName, lpparam.classLoader) ?: return@runCatching
                XposedBridge.hookAllMethods(listenerCls, "onHdrInfoChanged", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // 签名: (IBinder displayToken, int numberOfHdrLayers, int maxW, int maxH, int flags, float maxDesiredHdrSdrRatio)
                        if (param.args.size >= 4) {
                            val numLayers = param.args[1] as? Int ?: 0
                            if (numLayers > 0) {
                                param.args[2] = 2176
                                param.args[3] = 2316
                            }
                        }
                    }
                })
                XposedBridge.log("$TAG: Hooked $listenerClsName.onHdrInfoChanged")
            }
        }

        // D. 同步修补 HighBrightnessModeController (mThresRatio=0.0f / mSupportHdrSolution / mSupportPhotoHdr)
        runCatching {
            val hbmCtrlCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.HighBrightnessModeController",
                lpparam.classLoader
            )
            if (hbmCtrlCls != null) {
                val patchHbmCtrl = { ctrl: Any ->
                    setFloatSafe(ctrl, "mThresRatio", 0.0f)
                    setBooleanSafe(ctrl, "mSupportHdrSolution", true)
                    setBooleanSafe(ctrl, "mSupportPhotoHdr", true)
                    setBooleanSafe(ctrl, "mIsBlockedByLowPowerMode", false)
                    patchHdrDataObjects(getObjectSafe(ctrl, "mHbmData"), null, fallbackRatioSpline)
                }
                XposedBridge.hookAllConstructors(hbmCtrlCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctrl = param.thisObject ?: return
                        patchHbmCtrl(ctrl)
                    }
                })
                XposedBridge.hookAllMethods(hbmCtrlCls, "resetHbmData", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctrl = param.thisObject ?: return
                        patchHbmCtrl(ctrl)
                    }
                })
            }
        }.onFailure { XposedBridge.log("$TAG: HighBrightnessModeController hook failed: $it") }
    }

    private fun patchHdrDataObjects(hbmData: Any?, hdrData: Any?, ratioSpline: Any?) {
        if (hdrData != null) {
            setFloatSafe(hdrData, "minimumHdrPercentOfScreenForNbm", 0.0f)
            setFloatSafe(hdrData, "minimumHdrPercentOfScreenForHbm", 0.0f)
            setFloatSafe(hdrData, "mMinimumHdrPercentOfScreenForNbm", 0.0f)
            setFloatSafe(hdrData, "mMinimumHdrPercentOfScreenForHbm", 0.0f)
            setBooleanSafe(hdrData, "allowInLowPowerMode", true)
            val curHighest = (getObjectSafe(hdrData, "highestHdrSdrRatio") as? Float) ?: 1.0f
            if (curHighest < 3.0f) {
                setFloatSafe(hdrData, "highestHdrSdrRatio", 3.0f)
            }
            if (ratioSpline != null) {
                setObjectIfNullSafe(hdrData, "sdrToHdrRatioSpline", ratioSpline)
                setObjectIfNullSafe(hdrData, "mSdrToHdrRatioSpline", ratioSpline)
            }
        }
        if (hbmData != null) {
            setFloatSafe(hbmData, "minimumHdrPercentOfScreen", 0.0f)
            setBooleanSafe(hbmData, "allowInLowPowerMode", true)
            if (ratioSpline != null) {
                setObjectIfNullSafe(hbmData, "sdrToHdrRatioSpline", ratioSpline)
                setObjectIfNullSafe(hbmData, "mSdrToHdrRatioSpline", ratioSpline)
            }
        }
    }

    private fun buildRatioSplineFromDdc(ddc: Any, classLoader: ClassLoader): Any? {
        return runCatching {
            val sdrNits = getObjectSafe(ddc, "mSdrNits") as? FloatArray ?: return null
            val hdrNits = getObjectSafe(ddc, "mHdrNits") as? FloatArray ?: return null
            if (sdrNits.isEmpty() || sdrNits.size != hdrNits.size) return null
            val ratios = FloatArray(sdrNits.size) { idx ->
                val s = sdrNits[idx].coerceAtLeast(0.1f)
                (hdrNits[idx] / s).coerceAtLeast(1.0f)
            }
            val splineCls = XposedHelpers.findClass("android.util.Spline", classLoader)
            XposedHelpers.callStaticMethod(splineCls, "createLinearSpline", sdrNits, ratios)
        }.getOrNull()
    }

    /**
     * 构造与 Fold 5 原厂硬件 sdrToHdrSpline [(1->3), (500->1500), (1500->1500), (2500->2500)] 匹配的兜底 Ratio Spline:
     * SDR 1~500 nits 区间输出 3.0x 倍率 (最高 1500 nits)，户外 1500 nits HBM 下平滑收敛至 1.0x
     */
    private fun createFold5HdrRatioSpline(classLoader: ClassLoader): Any? {
        return runCatching {
            val splineCls = XposedHelpers.findClass("android.util.Spline", classLoader)
            val xNits = floatArrayOf(1.0f, 500.0f, 1000.0f, 1500.0f, 2500.0f)
            val yRatios = floatArrayOf(3.0f, 3.0f, 1.5f, 1.0f, 1.0f)
            XposedHelpers.callStaticMethod(splineCls, "createLinearSpline", xNits, yRatios)
        }.getOrNull()
    }

    private fun hookClientAppAutoGainmap() {
        runCatching {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val act = param.thisObject as? Activity ?: return
                        act.window?.colorMode = ActivityInfo.COLOR_MODE_HDR
                    }
                }
            )

            XposedBridge.hookAllMethods(BitmapFactory::class.java, "decodeStream", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bmp = param.result as? Bitmap ?: return
                    attachAutoGainmapIfNeeded(bmp)
                }
            })

            XposedBridge.hookAllMethods(ImageDecoder::class.java, "decodeBitmapImpl", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val bmp = param.result as? Bitmap ?: return
                    attachAutoGainmapIfNeeded(bmp)
                }
            })
        }
    }

    private fun attachAutoGainmapIfNeeded(source: Bitmap) {
        runCatching {
            if (source.width < 512 || source.height < 512 || source.hasGainmap()) return

            val scale = 8
            val gmW = (source.width / scale).coerceAtLeast(64)
            val gmH = (source.height / scale).coerceAtLeast(64)

            val readable = if (source.config == Bitmap.Config.HARDWARE) {
                source.copy(Bitmap.Config.ARGB_8888, false) ?: return
            } else source

            val scaled = Bitmap.createScaledBitmap(readable, gmW, gmH, true)
            if (readable !== source) readable.recycle()

            val pixels = IntArray(gmW * gmH)
            scaled.getPixels(pixels, 0, gmW, 0, 0, gmW, gmH)
            scaled.recycle()

            val gmPixels = IntArray(pixels.size)
            val threshold = 0.72f
            var hiCount = 0

            for (i in pixels.indices) {
                val c = pixels[i]
                val luma = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f
                if (luma > threshold) {
                    val norm = ((luma - threshold) / (1f - threshold)).coerceIn(0f, 1f)
                    val curved = norm.pow(1.6f)
                    val v = (curved * 255f).toInt().coerceIn(0, 255)
                    gmPixels[i] = Color.rgb(v, v, v)
                    if (curved > 0.25f) hiCount++
                } else {
                    gmPixels[i] = Color.BLACK
                }
            }

            // 跳过纯白截图/文档 (高光占比超过 45% 不激发)
            val ratio = hiCount.toFloat() / pixels.size
            if (ratio < 0.002f || ratio > 0.45f) return

            val gmBmp = Bitmap.createBitmap(gmW, gmH, Bitmap.Config.ARGB_8888)
            gmBmp.setPixels(gmPixels, 0, gmW, 0, 0, gmW, gmH)

            source.gainmap = Gainmap(gmBmp).apply {
                setRatioMin(1.0f, 1.0f, 1.0f)
                setRatioMax(2.8f, 2.8f, 2.8f)
                setGamma(1.0f, 1.0f, 1.0f)
                setEpsilonSdr(0.01f, 0.01f, 0.01f)
                setEpsilonHdr(0.01f, 0.01f, 0.01f)
                minDisplayRatioForHdrTransition = 1.02f
            }
        }
    }

    private fun getObjectSafe(target: Any?, fieldName: String): Any? {
        if (target == null) return null
        return runCatching { XposedHelpers.getObjectField(target, fieldName) }.getOrNull()
    }

    private fun setFloatSafe(target: Any, fieldName: String, value: Float) {
        runCatching { XposedHelpers.setFloatField(target, fieldName, value) }
    }

    private fun setBooleanSafe(target: Any, fieldName: String, value: Boolean) {
        runCatching { XposedHelpers.setBooleanField(target, fieldName, value) }
    }

    private fun setObjectIfNullSafe(target: Any, fieldName: String, value: Any) {
        runCatching {
            val existing = XposedHelpers.getObjectField(target, fieldName)
            if (existing == null) {
                XposedHelpers.setObjectField(target, fieldName, value)
            }
        }
    }
}
