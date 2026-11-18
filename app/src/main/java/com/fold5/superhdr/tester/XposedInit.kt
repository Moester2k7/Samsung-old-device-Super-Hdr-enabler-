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
import kotlin.math.pow

class XposedInit : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. 解锁三星 One UI SemFloatingFeature 中的 PhotoHDR / Super HDR 机型锁
        hookSamsungFloatingFeature(lpparam)

        // 2. 修复 Fold 5 虚标的 400/450 nits 静态峰值上限 -> 1500 nits (与硬件 sdrToHdrSpline 对齐)
        hookHdrCapabilities()

        if (lpparam.packageName == "android") {
            // 3. 核心突破：在 system_server 解除 50% 屏幕面积门槛，并补全缺失的 sdrToHdrRatioSpline (1->3.0x, 500->3.0x)
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

        // 客户端保底：当系统上报 hdrSdrRatio <= 1.01f 时确保 isHdrSdrRatioAvailable 仍能返回 true 供调试对比
        runCatching {
            XposedBridge.hookAllMethods(Display::class.java, "isHdrSdrRatioAvailable", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = true
                }
            })
        }
    }

    /**
     * 针对 Fold 5 诊断报告精确定位的三大拦截点：
     * 1) HdrBrightnessData: minimumHdrPercentOfScreenForNbm = 0.5 -> 0.0f, minimumHdrPercentOfScreenForHbm = 0.5 -> 0.0f, sdrToHdrRatioSpline = null -> 3.0x Spline
     * 2) HighBrightnessModeData (HBM): minimumHdrPercentOfScreen = 0.5 -> 0.0f, mSdrToHdrRatioSpline = null -> 3.0x Spline
     * 3) HighBrightnessModeController / HdrBrightnessModifier: 解除面积校验并返回真实 3.0x 倍率
     */
    private fun hookSystemServerHdrGatekeepers(lpparam: XC_LoadPackage.LoadPackageParam) {
        val ratioSpline = createFold5HdrRatioSpline(lpparam.classLoader)

        // A. Hook One UI 7 / Android 15 新增的 HdrBrightnessData 配置对象
        runCatching {
            val hdrDataCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.config.HdrBrightnessData",
                lpparam.classLoader
            )
            if (hdrDataCls != null) {
                XposedBridge.hookAllConstructors(hdrDataCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject ?: return
                        setFloatSafe(obj, "minimumHdrPercentOfScreenForNbm", 0.0f)
                        setFloatSafe(obj, "minimumHdrPercentOfScreenForHbm", 0.0f)
                        setFloatSafe(obj, "mMinimumHdrPercentOfScreenForNbm", 0.0f)
                        setFloatSafe(obj, "mMinimumHdrPercentOfScreenForHbm", 0.0f)
                        setBooleanSafe(obj, "allowInLowPowerMode", true)
                        if (ratioSpline != null) {
                            setObjectIfNullSafe(obj, "sdrToHdrRatioSpline", ratioSpline)
                            setObjectIfNullSafe(obj, "mSdrToHdrRatioSpline", ratioSpline)
                        }
                        XposedBridge.log("Fold5SuperHDR: Patched HdrBrightnessData (0% area limit + 3.0x ratio spline)")
                    }
                })
            }
        }

        // B. Hook DisplayDeviceConfig$HighBrightnessModeData (dumpsys 中的 mHbmData)
        runCatching {
            val hbmDataCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.DisplayDeviceConfig\$HighBrightnessModeData",
                lpparam.classLoader
            )
            if (hbmDataCls != null) {
                XposedBridge.hookAllConstructors(hbmDataCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val obj = param.thisObject ?: return
                        setFloatSafe(obj, "minimumHdrPercentOfScreen", 0.0f)
                        setBooleanSafe(obj, "allowInLowPowerMode", true)
                        if (ratioSpline != null) {
                            setObjectIfNullSafe(obj, "mSdrToHdrRatioSpline", ratioSpline)
                            setObjectIfNullSafe(obj, "sdrToHdrRatioSpline", ratioSpline)
                        }
                        XposedBridge.log("Fold5SuperHDR: Patched HighBrightnessModeData (0% area + 3.0x spline)")
                    }
                })
            }
        }

        // C. Hook DisplayDeviceConfig 的获取方法，防止结构体字段在构造后被覆盖
        runCatching {
            val ddcCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.DisplayDeviceConfig",
                lpparam.classLoader
            )
            if (ddcCls != null) {
                XposedBridge.hookAllMethods(ddcCls, "getHighBrightnessModeData", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val hbm = param.result ?: return
                        setFloatSafe(hbm, "minimumHdrPercentOfScreen", 0.0f)
                        if (ratioSpline != null) {
                            setObjectIfNullSafe(hbm, "mSdrToHdrRatioSpline", ratioSpline)
                        }
                    }
                })
                XposedBridge.hookAllMethods(ddcCls, "getHdrBrightnessData", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val hdrData = param.result ?: return
                        setFloatSafe(hdrData, "minimumHdrPercentOfScreenForNbm", 0.0f)
                        setFloatSafe(hdrData, "minimumHdrPercentOfScreenForHbm", 0.0f)
                        if (ratioSpline != null) {
                            setObjectIfNullSafe(hdrData, "sdrToHdrRatioSpline", ratioSpline)
                        }
                    }
                })
                XposedBridge.hookAllMethods(ddcCls, "getSdrToHdrRatio", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val cur = (param.result as? Float) ?: return
                        if (cur.isNaN() || cur <= 1.01f) {
                            param.result = 3.0f
                        }
                    }
                })
            }
        }

        // D. Hook HighBrightnessModeController，解除窗口尺寸拦截并激活 getHdrSdrRatio()
        runCatching {
            val hbmCtrlCls = XposedHelpers.findClassIfExists(
                "com.android.server.display.HighBrightnessModeController",
                lpparam.classLoader
            )
            if (hbmCtrlCls != null) {
                XposedBridge.hookAllConstructors(hbmCtrlCls, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctrl = param.thisObject ?: return
                        setFloatSafe(ctrl, "mMinHdrPercentOfScreen", 0.0f)
                    }
                })
                XposedBridge.hookAllMethods(hbmCtrlCls, "getHdrSdrRatio", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val r = (param.result as? Float) ?: return
                        if (r.isNaN() || r <= 1.01f) {
                            param.result = 3.0f
                        }
                    }
                })
                // 拦截 onHdrInfoChanged(IBinder, int numberOfHdrLayers, int maxW, int maxH, int flags, float desiredMaxHdrRatio)
                // 当存在 HDR 图层 (numberOfHdrLayers > 0) 但小窗口面积不足 50% 时，把上报宽高放大至全屏尺寸以原生触发三星 1500nits 硬件曲线！
                XposedBridge.hookAllMethods(hbmCtrlCls, "onHdrInfoChanged", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size >= 4) {
                            val numLayers = param.args[1] as? Int ?: 0
                            if (numLayers > 0) {
                                param.args[2] = 2176
                                param.args[3] = 2316
                            }
                        }
                    }
                })
            }
        }

        // E. 同步拦截 Android 15 HdrClamper / HdrBrightnessModifier 的 HDR 图层面积事件
        for (clsName in listOf(
            "com.android.server.display.brightness.clamper.HdrClamper",
            "com.android.server.display.brightness.clamper.HdrBrightnessModifier"
        )) {
            runCatching {
                val cls = XposedHelpers.findClassIfExists(clsName, lpparam.classLoader) ?: return@runCatching
                XposedBridge.hookAllMethods(cls, "onHdrInfoChanged", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        for (i in param.args.indices) {
                            // 查找连续的 (numHdrLayers, maxW, maxH) 参数签名
                            if (i + 2 < param.args.size &&
                                param.args[i] is Int &&
                                param.args[i + 1] is Int &&
                                param.args[i + 2] is Int
                            ) {
                                val layers = param.args[i] as Int
                                if (layers > 0) {
                                    param.args[i + 1] = 2176
                                    param.args[i + 2] = 2316
                                }
                                break
                            }
                        }
                    }
                })
            }
        }
    }

    /**
     * 构造与 Fold 5 原厂硬件 sdrToHdrSpline [(1->3), (500->1500), (1500->1500)] 完全匹配的 Ratio Spline:
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
