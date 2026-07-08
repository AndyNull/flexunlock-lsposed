# FlexUnlock LSPosed Module

> 配合 FlexUnlock 主 App 使用的 LSPosed 模块
> 通过 hook Samsung SubHomeActivity，让外屏直接显示完整内屏内容
> v0.1.0-alpha

## 这是什么

这是 FlexUnlock 的 LSPosed 模块部分。FlexUnlock 主 App（v0.2.0+）已经实现了封面屏桌面，但合盖时 Samsung 的 `SubHomeActivity` 会抢回前台。本模块通过 Xposed hook 在 SystemUI 层抑制 SubHomeActivity，让 FlexUnlock 桌面（或任意 App）稳定驻留外屏。

## 工作原理

1. **目标**：`com.android.systemui.subscreen.SubHomeActivity`（One UI 各版本类名可能不同）
2. **Hook 时机**：`onCreate` / `onResume` / `onStart` / `onNewIntent`
3. **行为**：hook 命中后立即 `finish()` 掉 SubHomeActivity，并启动 FlexUnlock CoverHomeActivity 到 display 1

## 安装前置

1. KernelSU（已安装）
2. ZygiskNext 模块（已启用）
3. LSPosed (Zygisk) 模块（已安装，可在 LSPosed Manager 看到模块列表）
4. FlexUnlock v0.2.0+ 主 App（已安装）

## 安装步骤

1. 编译本模块 APK（或从 release 下载）
2. 在 LSPosed Manager 中安装模块
3. 在模块设置中启用，作用域勾选：
   - `com.android.systemui`
   - `com.samsung.android.app.coverlauncher`
   - `com.samsung.android.app.subscreen`
4. 重启 SystemUI（`adb shell su -c "killall com.android.systemui"`）或重启手机
5. 在 FlexUnlock 主 App 中开启"折叠监控"

## 验证

合盖后，外屏应显示 FlexUnlock 桌面（而非 Samsung 小组件）。

如失败，查看 logcat：
```bash
adb logcat -s LSPosed-Bridge:V XposedBridge:V FlexUnlock-Hook:V
```

## 已知限制

- One UI 8.5 类名可能与候选列表不一致——需要根据 logcat 反馈调整
- hook onCreate 后 finish() 可能导致 SystemUI ANR——如发生请禁用模块
- 部分 One UI 版本 SubHomeActivity 在独立进程，需要额外 hook

## 开发

```bash
# 本地编译
./gradlew assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk
```

模块入口类：`com.flexunlock.lsposed.HookEntry`
配置文件：`app/src/main/assets/xposed_init`
