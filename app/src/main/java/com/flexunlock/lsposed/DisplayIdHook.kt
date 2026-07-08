package com.flexunlock.lsposed

import android.content.Context
import android.os.FileUtils
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File

/**
 * FlexUnlock LSPosed 模块 — Hook Display ID
 *
 * 核心原理：
 *  当 App 在外屏 (display 1) 运行时，Hook Display.getDisplayId() 返回 0
 *  让 App 以为自己在内屏，绕过"请展开手机使用"限制
 *
 * 安全设计：
 *  1. 只篡改 displayId=1 → 0，不影响 displayId=0（内屏正常）
 *  2. 通过文件标志判断是否处于"外屏模式"（由主 App 写入）
 *  3. 不 hook 系统关键 App（SystemUI 等）
 *  4. 异常时返回原始值（fail-safe）
 */
class DisplayIdHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FlexUnlock-Hook"
        private const val COVER_MODE_FILE = "/data/local/tmp/flexunlock_cover_mode"

        // v0.3.0: 只 hook 这些 App（精确控制，不 hook 所有 App）
        // 安全文件夹 + 其他可能检测 displayId 的 App
        // v0.3.2: 安全文件夹运行在 Knox 容器 (user 150)，LSPosed 无法 hook
        // 从白名单移除避免内屏闪退
        private val TARGET_APPS = setOf(
            "com.android.settings",
            "com.samsung.android.app.contacts",
            "com.sec.android.app.myfiles",
            "com.samsung.android.messaging",
            "com.samsung.android.dialer",
            "com.sec.android.app.camera",
            "com.sec.android.app.samsungapps",
            "com.sec.android.gallery3d",
            "com.sec.android.app.clockpackage",
            "com.sec.android.app.popupcalculator",
            "com.samsung.android.calendar",
            "com.samsung.android.app.notes",
        )
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        // v0.3.0: 白名单模式——只 hook 指定的 App
        if (lpparam.packageName !in TARGET_APPS) return

        XposedBridge.log("[$TAG] Loading hook for: ${lpparam.packageName}")

        hookDisplayGetDisplayId(lpparam)
        hookContextGetDisplayId(lpparam)
        hookWindowConfiguration(lpparam)
    }

    /**
     * Hook android.view.Display.getDisplayId()
     * 这是最核心的 hook——App 调用这个方法检测自己在哪个屏幕
     */
    private fun hookDisplayGetDisplayId(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.Display",
                lpparam.classLoader,
                "getDisplayId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val original = param.result as? Int ?: return
                            // 只篡改 displayId=1 → 0
                            if (original == 1 && isCoverMode()) {
                                XposedBridge.log("[$TAG] ${lpparam.packageName}: getDisplayId() 1→0 (cover mode)")
                                param.result = 0
                            }
                        } catch (e: Exception) {
                            XposedBridge.log("[$TAG] Error in getDisplayId hook: ${e.message}")
                            // fail-safe: 不修改返回值
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] Hooked Display.getDisplayId() for ${lpparam.packageName}")
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] Failed to hook Display.getDisplayId(): ${e.message}")
        }
    }

    /**
     * Hook android.content.Context.getDisplayId()
     * 部分 App 通过 Context 获取 displayId
     */
    private fun hookContextGetDisplayId(lpparam: LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.content.Context",
                lpparam.classLoader,
                "getDisplayId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val original = param.result as? Int ?: return
                            if (original == 1 && isCoverMode()) {
                                XposedBridge.log("[$TAG] ${lpparam.packageName}: Context.getDisplayId() 1→0")
                                param.result = 0
                            }
                        } catch (e: Exception) {
                            // fail-safe
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // Context.getDisplayId 可能在某些版本不存在，忽略
        }
    }

    /**
     * Hook android.app.WindowConfiguration 中的 displayId
     * 部分 App 通过 WindowConfiguration 检测
     */
    private fun hookWindowConfiguration(lpparam: LoadPackageParam) {
        try {
            val windowConfigClass = XposedHelpers.findClass(
                "android.app.WindowConfiguration",
                lpparam.classLoader
            )

            // Hook getDisplayId() 方法
            XposedHelpers.findAndHookMethod(
                windowConfigClass,
                "getDisplayId",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val original = param.result as? Int ?: return
                            if (original == 1 && isCoverMode()) {
                                XposedBridge.log("[$TAG] ${lpparam.packageName}: WindowConfiguration.getDisplayId() 1→0")
                                param.result = 0
                            }
                        } catch (e: Exception) {
                            // fail-safe
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // WindowConfiguration 可能在某些版本不同，忽略
        }
    }

    /**
     * 检查当前是否处于"外屏模式"
     *
     * 通过文件标志判断：主 App 在合盖时创建文件，展开时删除
     * 如果文件不存在，返回 false（不影响内屏使用）
     */
    private fun isCoverMode(): Boolean {
        return try {
            File(COVER_MODE_FILE).exists()
        } catch (e: Exception) {
            // 读取失败时返回 false（安全默认值——不篡改）
            false
        }
    }
}
