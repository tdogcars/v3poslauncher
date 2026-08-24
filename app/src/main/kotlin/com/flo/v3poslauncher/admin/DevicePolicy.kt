package com.flo.v3poslauncher.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings

/**
 * Thin wrapper over [DevicePolicyManager] with the admin [ComponentName] pre-bound.
 * Every call that can fail throws; callers (provisioning steps, revert) catch and report
 * loudly on screen. Nothing here swallows a failure.
 */
class DevicePolicy(context: Context) {
    val appContext: Context = context.applicationContext
    val dpm: DevicePolicyManager =
        appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val admin: ComponentName = PosDeviceAdminReceiver.component(appContext)
    val pm: PackageManager = appContext.packageManager
    val selfPackage: String = appContext.packageName

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(selfPackage)

    fun requireDeviceOwner() {
        if (!isDeviceOwner) throw IllegalStateException(
            "This app is not Device Owner. Provisioning must start from a factory-fresh device via QR."
        )
    }

    // ---- HOME ------------------------------------------------------------------------

    fun homeComponent(): ComponentName =
        ComponentName(selfPackage, "com.flo.v3poslauncher.home.HomeActivity")

    /** Every activity that resolves the HOME intent (any package), including ours. */
    fun resolveHomeActivities(): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
    }

    /** Package the system currently picks for HOME, or null. */
    fun currentDefaultHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }

    // ---- Navigation mode (for the Quickstep caveat) ------------------------------------

    /** 0 = 3-button, 1 = 2-button, 2 = gestural, -1 = unknown. */
    fun navigationMode(): Int = try {
        Settings.Secure.getInt(appContext.contentResolver, "navigation_mode", -1)
    } catch (_: Throwable) { -1 }

    fun navigationModeName(): String = when (navigationMode()) {
        0 -> "3-button"
        1 -> "2-button"
        2 -> "gesture"
        else -> "unknown"
    }

    // ---- Misc ------------------------------------------------------------------------

    fun describe(): String = buildString {
        append("deviceOwner=").append(isDeviceOwner)
        append(" api=").append(Build.VERSION.SDK_INT)
        append(" oem=").append(Build.MANUFACTURER).append('/').append(Build.MODEL)
        append(" nav=").append(navigationModeName())
        append(" defaultHome=").append(currentDefaultHomePackage())
    }
}
