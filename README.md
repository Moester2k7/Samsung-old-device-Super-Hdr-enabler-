# Fold5 SuperHDR Lab v4 (LSPosed 模块 + 双引擎测试台)

针对三星 **Galaxy Z Fold 5 (`SM-F946B` / `F946BXXU7HZIJ`, Android 17 / One UI 9)** 实机 `services.jar`、`SecGallery`、`SecSettings` 反编译逆向分析定制的 **v4 版本**。

## 实机锁定根因与 v4 破解方案
1. **R8 内联构造导致 v3 构造 Hook 失效 (`DisplayDeviceConfig.initFromGlobalXml`)**：
   - Fold 5 无 `/vendor/etc/displayconfig/display_id_*.xml`，`DisplayDeviceConfig` 走 `initFromGlobalXml()`，其中 R8 将 `config.HighBrightnessModeData`（非旧包名 `DisplayDeviceConfig$HighBrightnessModeData`）与 `config.HdrBrightnessData` 内联为 `Object.<init>()` 后直接 `iput` 字段。
   - **v4 突破**：直接在 `DisplayDeviceConfig.initFromGlobalXml()` 返回后修补 `mHbmData` / `mHdrBrightnessData`，并从实机 `mSdrNits` / `mHdrNits` 动态构造 `sdrToHdrRatioSpline`（兜底 `1~500 nits -> 3.0x`）。
2. **Android 15+ 真实主控门槛 (`HdrBrightnessModifier` `mThresRatio = 0.9` 与省电模式拦截)**：
   - 实机 `HighBrightnessModeController` 处于 `mHdrBoostDisabled=true`，真正驱动局部激发的是 `HdrBrightnessModifier`。因 `SurfaceFlinger` `1135` 返回 `0` (`mSDRDimming=0`)，其硬编码 `mThresRatio = 0.9f`，且 `onHdrInfoChanged` 位于内部类 `HdrBrightnessModifier$1` 与 `HighBrightnessModeController$HdrListener`；同时 `low_power=1` + `allowInLowPowerMode=false` 会直接拦截为 `NO_HDR`。
   - **v4 突破**：归零 `HdrBrightnessModifier` 与 `HighBrightnessModeController` 的 `mThresRatio`，开启 `allowInLowPowerMode=true` 与 `mSupportHdrSolution/mSupportPhotoHdr`，并拦截 `$1` 与 `$HdrListener` 的 `onHdrInfoChanged` 放行任意尺寸图层。
3. **安全救砖与实机诊断**：
   - 若因系统更新导致 `system_server` 异常，可在开机/Recovery ADB 中执行 `touch /data/local/tmp/disable_fold5_hdr` 立即跳过 `system_server` Hook；点击「1. 诊断并自动导出/复制日志」会保存至 `/sdcard/Download/fold5_hdr_diag_v4.txt` 并复制到剪贴板。
