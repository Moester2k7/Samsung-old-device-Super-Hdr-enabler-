# Fold5 SuperHDR Lab (Root 窗口 Super HDR 测试与解锁工具)

专为三星 **Galaxy Z Fold 5**（One UI 6.1 / 7 / 8，Android 14+）编写的独立测试软件，内置双渲染引擎与 Root 诊断工具箱：

## 核心功能
1. **引擎 A：10-bit BT.2020 PQ 硬件直通激发 (`PqHdrSurfaceView`)**
   - **无需刷入任何系统模块或重启**，直接通过 OpenGL ES 3.0 `RGBA_1010102` + `DataSpace.DATASPACE_BT2020_PQ` 硬件图层将画面普通区域锚定在 203 尼特，将高光区域按滑块实时推高至 **1000 ~ 1725 尼特**。
   - 窗口右上角自带 **`#FFFFFF` 纯白 SDR 基准块**，支持左右拖动分屏线实时对比，一眼验证 Fold 5 内外屏局部 Super HDR 物理发光效果。
2. **引擎 B：Android 14+ 原生 `Gainmap` (Ultra HDR `ImageView` + `COLOR_MODE_HDR`)**
   - 实时为内置测试卡或相册任意普通照片计算逆色调映射并挂载 `android.graphics.Gainmap`。
   - 用于直接检验本机 `Display.getHdrSdrRatio()` 或 LSPosed / Magisk 补丁是否已成功激活系统级原生 Gainmap 管线。
3. **Root (`su`) 权限工具箱**
   - 一键读取 `dumpsys display`（内外屏 `hdrSdrRatio`、`sdrHdrRatioMap`）、`floating_feature.xml` 与 `/vendor/etc/displayconfig/` 状态。
   - 一键写入 `hdr_effect=1`、`super_hdr=1` 并解除系统 HDR 格式禁用列表。
   - **一键提取本机内外屏配置生成 Magisk 模块**：自动读取本机 `/vendor/etc/displayconfig/*.xml`，无损注入 `<sdrHdrRatioMap>` 曲线并打包到 `/sdcard/Download/Fold5-SuperHDR-Overlay-Magisk.zip`。

## 两种极速获取 APK 的方式

### 方式一：免装环境，GitHub Actions 云端一键编译（约 2 分钟）
1. 在 GitHub 新建一个仓库（可设为 Private 私有仓库），将解压后的全部文件上传/推送上去。
2. 点击仓库顶部的 **Actions** 标签页 -> 选择 **Build Fold5 SuperHDR Tester APK** -> 点击 **Run workflow**。
3. 编译完成后在页面底部 **Artifacts** 下载 `Fold5-SuperHDR-Tester-debug-apk` 即可安装到手机。

### 方式二：本地 Android Studio 或命令行编译
1. 使用 Android Studio (Iguana / Jellyfish / Ladybug 或更新版本) 直接 `Open` 解压后的目录，等待 Gradle Sync 完成后点击 **Run** 直接装入 Fold 5。
2. 若在 Linux (如 Arch Linux) 命令行下已安装 `android-sdk` 与 `gradle`：
   ```bash
   export ANDROID_HOME=/opt/android-sdk   # 修改为你的 SDK 路径
   gradle assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
