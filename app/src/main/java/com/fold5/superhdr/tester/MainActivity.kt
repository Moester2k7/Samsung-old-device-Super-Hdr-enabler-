package com.fold5.superhdr.tester

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class EngineMode {
        HARDWARE_PQ_SURFACE,
        NATIVE_GAINMAP
    }

    private var currentMode = EngineMode.HARDWARE_PQ_SURFACE
    private var hdrEnabled = true
    private var expandedViewport = false // 开启后预览框占屏 >68%，免模块直接突破三星 50% 屏幕面积锁！
    private var peakBoost = 3.5f
    private var threshold = 0.68f
    private var splitRatio = 0.50f

    private lateinit var sourceBitmap: Bitmap
    private lateinit var viewportContainer: FrameLayout
    private lateinit var controlScroll: ScrollView
    private lateinit var pqSurfaceView: PqHdrSurfaceView
    private lateinit var gainmapImageView: ImageView
    private lateinit var statusBanner: TextView
    private lateinit var logOutputView: TextView
    private lateinit var boostLabel: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var splitLabel: TextView
    private lateinit var btnModePq: Button
    private lateinit var btnModeGainmap: Button

    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val decoded = BitmapFactory.decodeStream(stream)
                    if (decoded != null) {
                        val maxDim = 2560
                        val ratio = minOf(maxDim.toFloat() / decoded.width, maxDim.toFloat() / decoded.height, 1f)
                        val scaled = if (ratio < 1f) {
                            Bitmap.createScaledBitmap(
                                decoded,
                                (decoded.width * ratio).toInt(),
                                (decoded.height * ratio).toInt(),
                                true
                            )
                        } else decoded
                        withContext(Dispatchers.Main) {
                            sourceBitmap = scaled
                            refreshPreview()
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.colorMode = ActivityInfo.COLOR_MODE_HDR

        sourceBitmap = GainmapGenerator.createBuiltInHdrTestCard()
        setContentView(buildRootLayout())
        refreshDisplayTelemetry()
        refreshPreview()

        try {
            if (display?.isHdrSdrRatioAvailable == true) {
                display?.registerHdrSdrRatioChangedListener({ it.run() }, {
                    runOnUiThread { refreshDisplayTelemetry() }
                })
            }
        } catch (_: Throwable) {
        }
    }

    override fun onResume() {
        super.onResume()
        if (::pqSurfaceView.isInitialized) {
            pqSurfaceView.onResume()
        }
        refreshDisplayTelemetry()
    }

    override fun onPause() {
        if (::pqSurfaceView.isInitialized) {
            pqSurfaceView.onPause()
        }
        super.onPause()
    }

    private fun buildRootLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 14, 18))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(20, 23, 30))
        }

        statusBanner = TextView(this).apply {
            setTextColor(Color.rgb(220, 230, 245))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sdrRefBox = TextView(this).apply {
            text = "SDR 纯白基准\n#FFFFFF"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(6).toFloat()
            }
        }

        headerRow.addView(statusBanner)
        headerRow.addView(sdrRefBox)
        root.addView(headerRow)

        viewportContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.05f
            )
        }

        pqSurfaceView = PqHdrSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        gainmapImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 右下角一键放大超过 50% 屏幕面积按钮（免 LSPosed 验证三星原厂 500->1500nits 曲线！）
        val btnExpandArea = createStyledButton("扩大视口 (>65% 面积突破三星 0.5 锁)", true) { btn ->
            expandedViewport = !expandedViewport
            val vLp = viewportContainer.layoutParams as LinearLayout.LayoutParams
            val sLp = controlScroll.layoutParams as LinearLayout.LayoutParams
            if (expandedViewport) {
                vLp.weight = 2.4f
                sLp.weight = 0.8f
                btn.text = "恢复常规视口比例"
            } else {
                vLp.weight = 1.05f
                sLp.weight = 1.0f
                btn.text = "扩大视口 (>65% 面积突破三星 0.5 锁)"
            }
            viewportContainer.layoutParams = vLp
            controlScroll.layoutParams = sLp
            viewportContainer.postDelayed({ refreshDisplayTelemetry() }, 350)
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            }
        }

        val splitOverlayHint = TextView(this).apply {
            text = "◀ 左侧：标准 SDR  |  右侧：Super HDR 局部激发 ▶"
            setTextColor(Color.argb(215, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Color.argb(130, 0, 0, 0))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(6) }
        }

        viewportContainer.addView(pqSurfaceView)
        viewportContainer.addView(gainmapImageView)
        viewportContainer.addView(btnExpandArea)
        viewportContainer.addView(splitOverlayHint)
        root.addView(viewportContainer)

        controlScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnModePq = createStyledButton("引擎 A：硬件 PQ 直通激发", true) {
            currentMode = EngineMode.HARDWARE_PQ_SURFACE
            updateModeButtons()
            refreshPreview()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        btnModeGainmap = createStyledButton("引擎 B：原生 Gainmap 窗口测试", false) {
            currentMode = EngineMode.NATIVE_GAINMAP
            updateModeButtons()
            refreshPreview()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        modeRow.addView(btnModePq)
        modeRow.addView(btnModeGainmap)
        controls.addView(modeRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(6))
        }
        val btnPickPhoto = createStyledButton("相册选图测试", false) {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        val btnResetCard = createStyledButton("内置测试卡", false) {
            sourceBitmap = GainmapGenerator.createBuiltInHdrTestCard()
            refreshPreview()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        val btnToggleHdr = createStyledButton("HDR: 开", true) { btn ->
            hdrEnabled = !hdrEnabled
            btn.text = if (hdrEnabled) "HDR: 开" else "HDR: 关"
            refreshPreview()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionRow.addView(btnPickPhoto)
        actionRow.addView(btnResetCard)
        actionRow.addView(btnToggleHdr)
        controls.addView(actionRow)

        boostLabel = makeSliderLabel()
        controls.addView(boostLabel)
        controls.addView(makeSeekBar(max = 65, progress = 25) { p ->
            peakBoost = 1.0f + (p / 10f)
            refreshSliderLabels()
            refreshPreview()
        })

        thresholdLabel = makeSliderLabel()
        controls.addView(thresholdLabel)
        controls.addView(makeSeekBar(max = 90, progress = 68) { p ->
            threshold = (p.coerceAtLeast(20)) / 100f
            refreshSliderLabels()
            refreshPreview()
        })

        splitLabel = makeSliderLabel()
        controls.addView(splitLabel)
        controls.addView(makeSeekBar(max = 100, progress = 50) { p ->
            splitRatio = p / 100f
            refreshSliderLabels()
            refreshPreview()
        })
        refreshSliderLabels()

        val rootTitle = TextView(this).apply {
            text = "LSPosed 状态验证与 Root 诊断导出"
            setTextColor(Color.rgb(130, 200, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(6))
        }
        controls.addView(rootTitle)

        val rootBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnRootDiag = createStyledButton("1. 诊断并自动导出/复制日志", false) {
            logOutputView.text = "正在请求 Root 读取并导出诊断报告..."
            lifecycleScope.launch {
                val report = RootShellHelper.runFullFold5Diagnostics()
                logOutputView.text = report
                copyToClipboard(report)
                val exportMsg = RootShellHelper.exportDiagnosticsToDownload(report)
                Toast.makeText(this@MainActivity, exportMsg, Toast.LENGTH_LONG).show()
                refreshDisplayTelemetry()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val btnRootEnable = createStyledButton("2. 写入系统 SuperHDR 键值", false) {
            lifecycleScope.launch {
                val res = RootShellHelper.applyRuntimeRootHdrTweaks()
                appendLog(res)
                refreshDisplayTelemetry()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        rootBtnRow.addView(btnRootDiag)
        rootBtnRow.addView(btnRootEnable)
        controls.addView(rootBtnRow)

        logOutputView = TextView(this).apply {
            text = "v4 已适配 Fold 5 (Android 17 / One UI 9) 实机管线！\n1. 免重启快速测试：点击预览图右上角『扩大视口 (>65% 面积)』可直接跨过 50% 面积锁触发原厂 500->1500nits 激发。\n2. 全局解锁：在 LSPosed 启用本模块（勾选 android 与三星相册/设置）重启，即会 Hook DisplayDeviceConfig.initFromGlobalXml() 与 HdrBrightnessModifier (mThresRatio=0 / 省电模式绕过 / 1500nits 曲线)。\n3. 防卡开机救砖：ADB 执行 touch /data/local/tmp/disable_fold5_hdr 即可跳过 system_server Hook。"
            setTextColor(Color.rgb(190, 205, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 26, 34))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        controls.addView(logOutputView)

        controlScroll.addView(controls)
        root.addView(controlScroll)
        return root
    }

    private fun copyToClipboard(content: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Fold5_HDR_Diag_v4", content))
    }

    private fun refreshSliderLabels() {
        val estNits = (203f * peakBoost).toInt()
        boostLabel.text = String.format(
            Locale.US,
            "峰值激发倍率: %.1fx  (PQ 目标 ≈ %d nits / Gainmap RatioMax = %.1f)",
            peakBoost, estNits, peakBoost
        )
        thresholdLabel.text = String.format(
            Locale.US,
            "高光切入阈值: %d%% 亮度以上触发激发",
            (threshold * 100).toInt()
        )
        splitLabel.text = String.format(
            Locale.US,
            "左右分屏对比线: 左侧 %d%% 标准 SDR  |  右侧 %d%% Super HDR",
            (splitRatio * 100).toInt(), 100 - (splitRatio * 100).toInt()
        )
    }

    private fun refreshDisplayTelemetry() {
        val d = display ?: return
        val ratioAvail = d.isHdrSdrRatioAvailable
        val ratio = d.hdrSdrRatio
        val maxLum = d.hdrCapabilities?.desiredMaxLuminance ?: 0f
        val modeTitle = if (currentMode == EngineMode.HARDWARE_PQ_SURFACE) "引擎A: 硬件 PQ 直通" else "引擎B: 原生 Gainmap"
        statusBanner.text = String.format(
            Locale.US,
            "%s\nhdrSdrRatioAvailable: %s | 当前倍率: %.2fx | 峰值上限: %.0f nits",
            modeTitle, ratioAvail, ratio, maxLum
        )
    }

    private fun refreshPreview() {
        refreshDisplayTelemetry()
        if (currentMode == EngineMode.HARDWARE_PQ_SURFACE) {
            gainmapImageView.visibility = View.GONE
            pqSurfaceView.visibility = View.VISIBLE
            pqSurfaceView.setImageBitmap(sourceBitmap)
            pqSurfaceView.updateHdrParams(hdrEnabled, peakBoost, threshold, splitRatio)
        } else {
            pqSurfaceView.visibility = View.GONE
            gainmapImageView.visibility = View.VISIBLE
            if (!hdrEnabled) {
                gainmapImageView.setImageBitmap(sourceBitmap)
            } else {
                lifecycleScope.launch(Dispatchers.Default) {
                    val gmBmp = GainmapGenerator.buildBitmapWithGainmap(
                        sourceBitmap,
                        maxRatio = peakBoost,
                        threshold = threshold,
                        splitRatio = splitRatio
                    )
                    withContext(Dispatchers.Main) {
                        gainmapImageView.setImageBitmap(gmBmp)
                    }
                }
            }
        }
    }

    private fun updateModeButtons() {
        styleButtonState(btnModePq, currentMode == EngineMode.HARDWARE_PQ_SURFACE)
        styleButtonState(btnModeGainmap, currentMode == EngineMode.NATIVE_GAINMAP)
    }

    private fun createStyledButton(
        label: String,
        active: Boolean,
        onClick: (Button) -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            styleButtonState(this, active)
            setOnClickListener { onClick(this) }
        }
    }

    private fun styleButtonState(button: Button, active: Boolean) {
        button.setTextColor(if (active) Color.WHITE else Color.rgb(200, 210, 225))
        button.background = GradientDrawable().apply {
            setColor(if (active) Color.rgb(37, 99, 235) else Color.rgb(38, 44, 58))
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun makeSliderLabel(): TextView = TextView(this).apply {
        setTextColor(Color.rgb(215, 225, 240))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun makeSeekBar(max: Int, progress: Int, onChange: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            this.max = max
            this.progress = progress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) onChange(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun appendLog(msg: String) {
        logOutputView.text = msg + "\n\n" + logOutputView.text
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
