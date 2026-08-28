package com.flo.v3poslauncher.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningLog

/**
 * Lock task ("kiosk") mode — the SUPPORTED way to suppress the large-screen taskbar.
 *
 * Why this and not app hiding: `setApplicationHidden` on apps the stock launcher still has
 * pinned makes Quickstep throw when it loads its hotseat ("Pixel Launcher keeps stopping"),
 * which is fatal in a store. Lock task changes nothing about which apps exist — it tells the
 * system this device is a dedicated terminal, and Android itself hides the taskbar (and its
 * suggested apps) while lock task is active.
 *
 * This is NOT the old "trapped in one app" kiosk:
 *  - The allowlist holds the launcher plus every configured home app, so a user can move
 *    between the POS app, Chrome, Settings… freely.
 *  - LOCK_TASK_FEATURE_HOME keeps the Home button working (it returns to our launcher) and
 *    LOCK_TASK_FEATURE_OVERVIEW keeps Recents working — the two buttons staff actually need
 *    to leave an app.
 *  - Notifications, system info and global actions are enabled too, so the status bar and the
 *    power button behave normally.
 *
 * Setting the allowlist alone does nothing visible; the terminal enters lock task when our
 * HomeActivity calls startLockTask(). Leaving is stopLockTask(), which the admin panel and the
 * revert path both do.
 */
object LockTaskManager {

    /** Everything a dedicated terminal should still be able to do. */
    private const val FEATURES =
        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD

    /** Launcher + configured home apps: everything the terminal is allowed to run. */
    fun allowlist(context: Context): List<String> {
        val cfg = AppConfig.get(context)
        return (listOf(context.packageName) + cfg.homeApps).distinct()
    }

    /**
     * Push the allowlist and feature set to the system. Safe to call repeatedly; does not by
     * itself put the device into lock task.
     */
    fun apply(context: Context, log: (String) -> Unit = { ProvisioningLog.i(context, it) }): List<String> {
        val dp = DevicePolicy(context)
        dp.requireDeviceOwner()
        val list = allowlist(context)
        dp.dpm.setLockTaskPackages(dp.admin, list.toTypedArray())
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { dp.dpm.setLockTaskFeatures(dp.admin, FEATURES) }
                .onFailure { log("LockTask: setLockTaskFeatures failed: ${it.message}") }
        }
        log("LockTask: allowlist=${list.joinToString()} features=HOME|OVERVIEW|GLOBAL_ACTIONS|NOTIFICATIONS|SYSTEM_INFO|KEYGUARD")
        return list
    }

    /** Clear the allowlist entirely (revert). Any running lock task must be stopped first. */
    fun clear(context: Context): Pair<Boolean, String> {
        val dp = DevicePolicy(context)
        if (!dp.isDeviceOwner) return false to "Not Device Owner; nothing to clear."
        return try {
            if (Build.VERSION.SDK_INT >= 28) runCatching { dp.dpm.setLockTaskFeatures(dp.admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE) }
            dp.dpm.setLockTaskPackages(dp.admin, emptyArray())
            true to "Lock task allowlist cleared; the taskbar returns to stock behaviour."
        } catch (t: Throwable) {
            false to "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    fun isAllowed(context: Context): Boolean = runCatching {
        val dp = DevicePolicy(context)
        dp.isDeviceOwner && dp.dpm.isLockTaskPermitted(context.packageName)
    }.getOrDefault(false)
}
