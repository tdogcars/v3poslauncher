package com.flo.v3poslauncher.admin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningLog
import kotlin.concurrent.thread

/**
 * "Only the allowed apps exist" — the zero-touch answer to the large-screen taskbar.
 *
 * Quickstep stays (so Back / Home / Recents keep working), but as Device Owner we:
 *  1. HIDE every launchable app that is not on the home-app list (and not protected). Hidden
 *     apps cannot be launched, do not appear in the taskbar, its all-apps sheet, or Recents.
 *  2. HIDE the system's app-prediction service (Android System Intelligence on GMS builds),
 *     which is where the taskbar's "suggested apps" come from. With it gone the taskbar shows no
 *     suggestions at all.
 *
 * Both are recorded so revert can restore exactly what was hidden. [sync] is idempotent and is
 * re-run whenever the allowed list changes (Launcher configuration) and at boot.
 */
object AppLockdown {

    data class Outcome(val hidden: List<String>, val unhidden: List<String>, val failed: List<String>, val predictionPkg: String?)

    /**
     * Launcher / taskbar / recents packages, matched by name. The framework resource lookup in
     * [recentsPackage] returns null on some builds (observed on an Android 15 Pixel Tablet image),
     * and when it does, nothing stopped Quickstep from being hidden — which removes the taskbar
     * AND the navigation buttons, and crash-loops the stock launcher. Never rely on the resource
     * alone.
     */
    private val launcherNameHints = listOf(
        "launcher", "quickstep", "trebuchet", "nexuslauncher", "com.android.systemui",
    )

    /** Never hidden, whatever the allowed list says. */
    private val protectedPrefixes = listOf(
        "com.android.settings", "com.android.systemui", "com.android.provision",
        "com.google.android.setupwizard", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.google.android.gms", "com.google.android.gsf",
        "com.android.vending", "com.android.inputmethod", "com.google.android.inputmethod",
        "com.android.managedprovisioning", "com.android.shell", "com.android.phone",
        "com.android.server.telecom", "com.android.emergency", "com.android.cellbroadcastreceiver",
    )

    /** Package of the framework's app-prediction service (taskbar suggestions), if any. */
    fun predictionServicePackage(): String? = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_defaultAppPredictionService", "string", "android")
        if (id == 0) null else ComponentName.unflattenFromString(res.getString(id))?.packageName
    }.getOrNull()

    private fun recentsPackage(): String? = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_recentsComponentName", "string", "android")
        if (id == 0) null else ComponentName.unflattenFromString(res.getString(id))?.packageName
    }.getOrNull()

    /** Every package with a LAUNCHER activity, INCLUDING ones we have hidden (Device Owner can see them). */
    fun launchablePackages(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val flags = PackageManager.MATCH_ALL or PackageManager.MATCH_UNINSTALLED_PACKAGES
        return pm.queryIntentActivities(intent, flags)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    fun isProtected(context: Context, pkg: String, recents: String? = recentsPackage()): Boolean =
        pkg == context.packageName ||
            pkg == recents ||
            protectedPrefixes.any { pkg.startsWith(it) } ||
            launcherNameHints.any { pkg.contains(it, ignoreCase = true) }

    /**
     * Bring the device to the desired state: hide non-allowed launchable apps (if enabled) and
     * the prediction service (if enabled); unhide anything we previously hid that is now allowed
     * or no longer supposed to be hidden. Safe to call repeatedly.
     */
    fun sync(context: Context, log: (String) -> Unit = { ProvisioningLog.i(context, it) }): Outcome {
        val dp = DevicePolicy(context)
        dp.requireDeviceOwner()
        val cfg = AppConfig.get(context)
        val allowed = cfg.homeApps.map { it.lowercase() }.toSet()
        val recents = recentsPackage()
        val previouslyHidden = cfg.hiddenOtherApps

        val hidden = mutableListOf<String>(); val unhidden = mutableListOf<String>(); val failed = mutableListOf<String>()
        val nowHidden = previouslyHidden.toMutableSet()

        fun setHidden(pkg: String, hide: Boolean): Boolean = try {
            val ok = dp.dpm.setApplicationHidden(dp.admin, pkg, hide)
            if (!ok) failed.add(pkg)
            ok
        } catch (t: Throwable) {
            log("AppLockdown: ${if (hide) "hide" else "unhide"} $pkg threw ${t.javaClass.simpleName}: ${t.message}")
            failed.add(pkg); false
        }

        if (cfg.lockdownApps) {
            val candidates = launchablePackages(context)
                .filter { !allowed.contains(it.lowercase()) && !isProtected(context, it, recents) }
                .toSortedSet()
            for (pkg in candidates) {
                val alreadyHidden = runCatching { dp.dpm.isApplicationHidden(dp.admin, pkg) }.getOrDefault(false)
                if (alreadyHidden && pkg !in previouslyHidden) continue // someone else's hide (e.g. taskbar step); leave it
                if (!alreadyHidden && setHidden(pkg, true)) hidden.add(pkg)
                if (alreadyHidden || pkg in hidden) nowHidden.add(pkg)
            }
        }
        // Unhide what we hid before but should no longer be hidden (allowed now, or lockdown off).
        for (pkg in previouslyHidden) {
            val shouldStayHidden = cfg.lockdownApps && !allowed.contains(pkg.lowercase()) && !isProtected(context, pkg, recents)
            if (!shouldStayHidden) {
                if (setHidden(pkg, false)) { unhidden.add(pkg); nowHidden.remove(pkg) }
            }
        }
        cfg.hiddenOtherApps = nowHidden

        // Anything the stock-launcher / taskbar step hid is restored here too when those
        // switches are off. Without this, a device whose Quickstep was hidden by an earlier
        // build has no taskbar and no navigation buttons, and no way to repair itself.
        if (!cfg.hideStockLauncher && !cfg.hideTaskbar && cfg.hiddenPackages.isNotEmpty()) {
            val stillHidden = mutableSetOf<String>()
            for (pkg in cfg.hiddenPackages) {
                if (setHidden(pkg, false)) { unhidden.add(pkg); log("AppLockdown: restored launcher package $pkg") }
                else stillHidden.add(pkg)
            }
            cfg.hiddenPackages = stillHidden
        }

        // Suggestions: hide / unhide the prediction service package.
        val predPkg = predictionServicePackage()
        if (predPkg != null && predPkg != context.packageName) {
            val isHidden = runCatching { dp.dpm.isApplicationHidden(dp.admin, predPkg) }.getOrDefault(false)
            if (cfg.disableAppSuggestions && !isHidden) {
                if (setHidden(predPkg, true)) { cfg.hiddenPredictionPackage = predPkg; log("AppLockdown: hid prediction service $predPkg (taskbar suggestions off)") }
            } else if (!cfg.disableAppSuggestions && isHidden && cfg.hiddenPredictionPackage == predPkg) {
                if (setHidden(predPkg, false)) { cfg.hiddenPredictionPackage = ""; log("AppLockdown: unhid prediction service $predPkg") }
            }
        }

        log("AppLockdown: allowed=${allowed.joinToString()} hidden(+${hidden.size})=${hidden.joinToString()} unhidden=${unhidden.joinToString()} failed=${failed.joinToString()} totalHidden=${nowHidden.size} prediction=${predPkg ?: "(none)"}")
        return Outcome(hidden, unhidden, failed, predPkg)
    }

    /** Fire-and-forget sync from UI code (the allowed list just changed). */
    fun syncAsync(context: Context) {
        val app = context.applicationContext
        thread(name = "app-lockdown-sync") {
            runCatching { if (DevicePolicy(app).isDeviceOwner) sync(app) }
                .onFailure { ProvisioningLog.w(app, "AppLockdown.syncAsync: ${it.message}") }
        }
    }

    /** Revert: unhide everything this object hid (apps + prediction service). */
    fun unhideAll(context: Context): Pair<Boolean, String> {
        val dp = DevicePolicy(context)
        if (!dp.isDeviceOwner) return false to "Not Device Owner; cannot unhide."
        val cfg = AppConfig.get(context)
        val restored = mutableListOf<String>(); val failed = mutableListOf<String>()
        for (pkg in cfg.hiddenOtherApps) {
            val ok = runCatching { dp.dpm.setApplicationHidden(dp.admin, pkg, false) }.getOrDefault(false)
            if (ok) restored.add(pkg) else failed.add(pkg)
        }
        cfg.hiddenOtherApps = failed.toSet()
        val pred = cfg.hiddenPredictionPackage
        if (pred.isNotEmpty()) {
            val ok = runCatching { dp.dpm.setApplicationHidden(dp.admin, pred, false) }.getOrDefault(false)
            if (ok) { restored.add(pred); cfg.hiddenPredictionPackage = "" } else failed.add(pred)
        }
        return if (failed.isEmpty()) true to "Unhid ${restored.size} package(s)."
        else false to "Unhid ${restored.size}; FAILED: ${failed.joinToString()}"
    }
}
