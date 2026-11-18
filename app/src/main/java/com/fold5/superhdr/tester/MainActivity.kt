package com.fold5.superhdr.tester

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
        HARDWARE_PQ_SURFACE, // 模式 A：10-bit BT.2020 PQ 硬件直通（无需改系统即可在 Fold5 物理激发 1000+ 尼特高光）
        NATIVE_GAINMAP       // 模式 B：Android 14 原生 Gainmap + COLOR_MODE_HDR（检验系统 hdrSdrRatio / 模块是否生效）
    }

    private var currentMode = EngineMode.HARDWARE_PQ_SURFACE
    private var hdrEnabled = true
    private var peakBoost = 5.0f      // 5.0x -> ~1015 nits in PQ mode, 5.0x ratioMax in Gainmap mode
    private var threshold = 0.68f     // 68% 亮度以上触发局部激发
    private var splitRatio = 0.50f    // 左 SDR / 右 Super HDR 分屏线

    private lateinit var sourceBitmap: Bitmap
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
                        // 限制最大边长 2560 保证实时拖动分屏丝滑
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

        // 仅在系统开放了 hdrSdrRatio 时注册监听（未解锁固件直接注册会抛出 IllegalStateException）
        try {
            if (display?.isHdrSdrRatioAvailable == true) {
                display?.registerHdrSdrRatioChangedListener({ it.run() }, {
                    runOnUiThread { refreshDisplayTelemetry() }
                })
            }
        } catch (_: Throwable) {
        }
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

        // 顶部状态栏（实时显示屏幕 hdrSdrRatio 与纯白 203nit SDR 对照块）
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(Color.rgb(20, 23, 30))
        }

        statusBanner = TextView(this).apply {
            setTextColor(Color.rgb(220, 230, 245))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // 右上角纯白 #FFFFFF SDR 窗口基准对照块（用于肉眼对比画面高光是否显著亮于系统纯白 UI！）
        val sdrRefBox = TextView(this).apply {
            text = "SDR 纯白基准
#FFFFFF"
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

        // 中部 HDR 图像预览视口（占屏幕高度约 44%）
        val viewportContainer = FrameLayout(this).apply {
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

        val splitOverlayHint = TextView(this).apply {
            text = "◀ 左侧：标准 SDR          |          右侧：Super HDR 局部激发 ▶"
            setTextColor(Color.argb(210, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(6) }
        }

        viewportContainer.addView(pqSurfaceView)
        viewportContainer.addView(gainmapImageView)
        viewportContainer.addView(splitOverlayHint)
        root.addView(viewportContainer)

        // 下半部分控制面板与 Root 控制台
        val scroll = ScrollView(this).apply {
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

        // 引擎模式切换双按钮
        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnModePq = createStyledButton("引擎 A：硬件 PQ 直通激发 (免改系统)", true) {
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

        // 图片选择与总开关行
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
        val btnResetCard = createStyledButton("内置高光测试卡", false) {
            sourceBitmap = GainmapGenerator.createBuiltInHdrTestCard()
            refreshPreview()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        val btnToggleHdr = createStyledButton("HDR 开关: 开", true) { btn ->
            hdrEnabled = !hdrEnabled
            btn.text = if (hdrEnabled) "HDR 开关: 开" else "HDR 开关: 关"
            refreshPreview()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        actionRow.addView(btnPickPhoto)
        actionRow.addView(btnResetCard)
        actionRow.addView(btnToggleHdr)
        controls.addView(actionRow)

        // 滑块 1：局部峰值激发倍数
        boostLabel = makeSliderLabel()
        controls.addView(boostLabel)
        controls.addView(makeSeekBar(max = 75, progress = 40) { p ->
            // 1.0x ~ 8.5x (203 nits ~ 1725 nits)
            peakBoost = 1.0f + (p / 10f)
            refreshSliderLabels()
            refreshPreview()
        })

        // 滑块 2：高光提取阈值
        thresholdLabel = makeSliderLabel()
        controls.addView(thresholdLabel)
        controls.addView(makeSeekBar(max = 90, progress = 68) { p ->
            threshold = (p.coerceAtLeast(20)) / 100f
            refreshSliderLabels()
            refreshPreview()
        })

        // 滑块 3：左右对比分屏线
        splitLabel = makeSliderLabel()
        controls.addView(splitLabel)
        controls.addView(makeSeekBar(max = 100, progress = 50) { p ->
            splitRatio = p / 100f
            refreshSliderLabels()
            refreshPreview()
        })
        refreshSliderLabels()

        // Root 权限工具栏
        val rootTitle = TextView(this).apply {
            text = "⚡ Root 权限诊断与系统解锁工具箱 (su)"
            setTextColor(Color.rgb(130, 200, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(6))
        }
        controls.addView(rootTitle)

        val rootBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnRootDiag = createStyledButton("1. Root 诊断屏幕参数", false) {
            appendLog("正在请求 Root 读取 Fold 5 显示管线状态...")
            lifecycleScope.launch {
                val report = RootShellHelper.runFullFold5Diagnostics()
                logOutputView.text = report
                refreshDisplayTelemetry()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val btnRootEnable = createStyledButton("2. Root 开启系统 HDR 键值", false) {
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

        val btnBuildMagisk = createStyledButton(
            "3. 提取本机内外屏配置自动生成 Magisk 原生 SuperHDR 补丁包 (可选)",
            false
        ) {
            appendLog("正在通过 Root 提取 /vendor/etc/displayconfig 并注入 sdrHdrRatioMap...")
            lifecycleScope.launch {
                val msg = RootShellHelper.buildMagiskDisplayConfigOverlay(this@MainActivity)
                appendLog(msg)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        controls.addView(btnBuildMagisk)

        logOutputView = TextView(this).apply {
            text = "点击「1. Root 诊断屏幕参数」可申请 su 权限并读取本机内外屏 hdrSdrRatio、FloatingFeature 与 DisplayConfig 状态。
提示：观察画面高光与右上角『#FFFFFF 纯白基准块』的亮度差——若画面高光明显比纯白块更亮、更有发光感，说明窗口局部 Super HDR 硬件激发已生效！"
            setTextColor(Color.rgb(190, 205, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
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

        scroll.addView(controls)
        root.addView(scroll)
        return root
    }

    private fun refreshSliderLabels() {
        val estNits = (203f * peakBoost).toInt()
        boostLabel.text = String.format(
            Locale.US,
            "峰值激发倍率: %.1fx  (PQ 映射峰值 ≈ %d nits / Gainmap RatioMax = %.1f)",
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

    private fun refreshDisplayTelemetry() {
        val d = display ?: return
        val ratioAvail = d.isHdrSdrRatioAvailable
        val ratio = d.hdrSdrRatio
        val hdrTypes = d.mode?.supportedHdrTypes?.joinToString(",") ?: "none"
        val modeTitle = if (currentMode == EngineMode.HARDWARE_PQ_SURFACE) "引擎A: 硬件 PQ 直通" else "引擎B: 原生 Gainmap"
        statusBanner.text = String.format(
            Locale.US,
            "%s\nhdrSdrRatioAvailable: %s | 系统当前倍率: %.2fx\n面板支持 HDR 类型 ID: [%s]",
            modeTitle, ratioAvail, ratio, hdrTypes
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
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
        logOutputView.text = msg + "

" + logOutputView.text
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
