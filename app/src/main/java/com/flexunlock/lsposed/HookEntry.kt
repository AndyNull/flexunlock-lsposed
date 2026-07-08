package com.flexunlock.lsposed

import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * LSPosed module entry point. Hooks into SystemUI (or Samsung's subscreen package)
 * to suppress the cover screen's SubHomeActivity, allowing standard Launcher / any App
 * to stay in foreground on the cover display (display 1) when the phone is folded.
 *
 * Hook strategy:
 *  - For each known package that might host SubHomeActivity, attempt to find and hook
 *    its onCreate/onResume/onStart methods
 *  - When hooked method is called, immediately finish() the activity and start
 *    FlexUnlock's CoverHomeActivity (or standard Launcher) on display 1
 *  - Log all hook attempts for debugging
 *
 * Known package/class candidates (One UI 5.1.1 - 8.5):
 *  1. com.android.systemui.subscreen.SubHomeActivity
 *  2. com.samsung.android.app.subscreen.SubHomeActivity
 *  3. com.samsung.android.app.coverlauncher.SubHomeActivity
 *  4. com.android.systemui.fold.SubHomeActivity
 *
 * The actual class name varies by One UI version. We try multiple candidates
 * and log which one exists in the loaded package.
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "FlexUnlock-Hook"

        // Target packages this module hooks into (must match xposed_scope)
        private val TARGET_PACKAGES = setOf(
            "com.android.systemui",
            "com.samsung.android.app.coverlauncher",
            "com.samsung.android.app.subscreen",
        )

        // SubHomeActivity class name candidates (across One UI versions)
        private val SUBHOME_CLASS_CANDIDATES = listOf(
            "com.android.systemui.subscreen.SubHomeActivity",
            "com.samsung.android.app.subscreen.SubHomeActivity",
            "com.samsung.android.app.coverlauncher.SubHomeActivity",
            "com.android.systemui.fold.SubHomeActivity",
            "com.android.systemui.cover.SubHomeActivity",
            "com.samsung.android.systemui.subscreen.SubHomeActivity",
        )

        // Methods to hook on SubHomeActivity
        private val TARGET_METHODS = listOf("onCreate", "onResume", "onStart", "onNewIntent")
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return

        log("handleLoadPackage: ${lpparam.packageName}")

        // Try each candidate class name
        var hookedAny = false
        for (className in SUBHOME_CLASS_CANDIDATES) {
            try {
                val clazz = XposedHelpers.findClass(className, lpparam.classLoader)
                log("✓ Found SubHomeActivity class: $className")
                hookSubHomeActivity(lpparam, clazz)
                hookedAny = true
            } catch (e: ClassNotFoundException) {
                // Class doesn't exist in this package — try next
                log("✗ Class not found in ${lpparam.packageName}: $className")
            } catch (e: NoSuchMethodError) {
                log("⚠ Class exists but no methods to hook: $className")
            }
        }

        if (!hookedAny) {
            // Fallback: scan loaded classes for anything matching SubHome pattern
            log("No direct class match — attempting class name pattern scan")
            scanForSubHomeClasses(lpparam)
        }
    }

    /**
     * Hook SubHomeActivity lifecycle methods to prevent it from staying in foreground.
     *
     * When any hooked method is invoked:
     *  1. Log the call
     *  2. After method executes, call finish() on the activity
     *  3. (Optional) Start FlexUnlock CoverHomeActivity as replacement
     */
    private fun hookSubHomeActivity(lpparam: LoadPackageParam, subHomeClass: Class<*>) {
        for (methodName in TARGET_METHODS) {
            try {
                // Try different parameter signatures
                val hooked = tryHookWithParams(subHomeClass, methodName, Bundle::class.java)
                    ?: tryHookWithParams(subHomeClass, methodName)
                    ?: continue

                XposedBridge.hookMethod(hooked.first, hooked.second)
                log("✓ Hooked $methodName on ${subHomeClass.name}")
            } catch (e: Exception) {
                log("⚠ Failed to hook $methodName: ${e.message}")
            }
        }

        // Also hook onDestroy for logging
        try {
            XposedHelpers.findAndHookMethod(subHomeClass, "onDestroy", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    log("SubHomeActivity onDestroy")
                }
            })
        } catch (e: Exception) { /* ignore */ }
    }

    private fun tryHookWithParams(clazz: Class<*>, methodName: String, vararg paramTypes: Class<*>):
        Pair<java.lang.reflect.Method, XC_MethodHook>? {
        return try {
            val method = XposedHelpers.findMethodExactIfExists(clazz, methodName, *paramTypes)
            if (method == null) null
            else method to object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleSubHomeInvoked(param.thisObject, methodName)
                }
            }
        } catch (e: Exception) { null }
    }

    /**
     * Called when SubHomeActivity lifecycle method is invoked.
     * We finish() the activity to prevent it from staying in foreground.
     */
    private fun handleSubHomeInvoked(activityInstance: Any, methodName: String) {
        log("🔥 SubHomeActivity.$methodName called — finishing activity to prevent cover launcher takeover")

        try {
            // Call Activity.finish() via reflection
            val finishMethod = activityInstance.javaClass.getMethod("finish")
            finishMethod.invoke(activityInstance)
            log("✓ finish() invoked on SubHomeActivity")
        } catch (e: Exception) {
            log("⚠ Failed to call finish(): ${e.message}")
        }

        // Try to start FlexUnlock CoverHomeActivity on cover display
        tryStartFlexUnlockHome(activityInstance)
    }

    /**
     * Attempt to start FlexUnlock CoverHomeActivity on display 1.
     * This uses Android's internal ActivityOptions to set launch display.
     */
    private fun tryStartFlexUnlockHome(contextAny: Any) {
        try {
            val context = contextAny as? android.content.Context ?: return
            val pm = context.packageManager

            // Try FlexUnlock debug build first (we've been testing with this)
            val flexUnlockPackages = listOf(
                "com.flexunlock.debug",
                "com.flexunlock",
            )

            var launchIntent: android.content.Intent? = null
            var targetPkg: String? = null
            for (pkg in flexUnlockPackages) {
                launchIntent = pm.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    targetPkg = pkg
                    break
                }
            }

            if (launchIntent == null) {
                log("FlexUnlock not installed — skipping replacement launch")
                return
            }

            // Use ActivityOptions to set launch display to cover (displayId=1)
            val activityOptionsClass = XposedHelpers.findClass(
                "android.app.ActivityOptions", context.classLoader
            )
            val makeBasic = activityOptionsClass.getMethod("makeBasic")
            val options = makeBasic.invoke(null) as android.app.ActivityOptions
            XposedHelpers.callMethod(options, "setLaunchDisplayId", 1)

            launchIntent.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
            )

            context.startActivity(launchIntent, options.toBundle())
            log("✓ Started FlexUnlock on display 1 (pkg=$targetPkg)")
        } catch (e: Exception) {
            log("⚠ tryStartFlexUnlockHome failed: ${e.message}")
        }
    }

    /**
     * Fallback: enumerate loaded classes to find anything matching SubHome pattern.
     * Used when the candidate class names don't match.
     */
    private fun scanForSubHomeClasses(lpparam: LoadPackageParam) {
        // Note: This is a fallback — usually not needed if candidates list is up-to-date.
        // We can't enumerate all classes at runtime without knowing the path list,
        // but we can try common patterns.
        log("⚠ Pattern scan not implemented — please report your One UI version " +
            "and the actual SubHomeActivity class name from logcat")
    }

    private fun log(message: String) {
        XposedBridge.log("[$TAG] $message")
    }
}
