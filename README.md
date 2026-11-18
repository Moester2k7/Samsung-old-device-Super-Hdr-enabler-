# Fold5 SuperHDR Lab v3 (LSPosed 模块 + 双引擎测试台)

针对三星 **Galaxy Z Fold 5** 实机 `dumpsys display` 逆向分析定制的 **v3 一体化版本**。

## 实机锁定根因与 v3 破解方案
1. **50% 屏幕面积锁 (`minimumHdrPercentOfScreen = 0.5`)**：
   - 实机 `mHbmData` 与 `mHdrBrightnessData` 内置了完整的 `sdrToHdrSpline: [(1->3), (500->1500)]`（最高 **1500 nits / 3.0x** 硬件激发），但三星强行限制 HDR 图层必须占全屏面积 **50% 以上**才放行激发。
   - **v3 突破**：`XposedInit` 在 `system_server` 中将 `minimumHdrPercentOfScreen` / `minimumHdrPercentOfScreenForNbm` / `ForHbm` 全部归零，并拦截 `onHdrInfoChanged` 让任意小尺寸窗口或相册缩略图都能触发硬件激发。
2. **原生 Gainmap 倍率曲线空缺 (`sdrToHdrRatioSpline = null`)**：
   - 三星保留了视频用的 `sdrToHdrSpline`，但把静态照片 Ultra HDR 专用的 `sdrToHdrRatioSpline` 留空为 `null`，导致 `hdrSdrRatio` 恒为 `1.0`。
   - **v3 突破**：动态构造 `Spline.createLinearSpline`（`1~500 nits -> 3.0x`）注入到 `HdrBrightnessData` 与 `HighBrightnessModeData` 中，并解除 `400/450 nits` 虚标上限至 `1500 nits`。
3. **自动导出与剪贴板复制**：
   - 点击「1. 诊断并自动导出/复制日志」会自动将完整报告保存至 `/sdcard/Download/fold5_hdr_diag_v3.txt` 并复制到系统剪贴板，日志框同时支持自由长按选词。
