package com.flo.v3poslauncher.admin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningLog

/**
 * Removes a CURATED, EXPLICITLY NAMED set of pre-installed apps from the terminal.
 *
 * Why this exists when app hiding was retired in v3.6.0. The thing that bricked a MicroTouch was a
 * COMPUTED SWEEP -- "hide everything not on the home list" -- which inevitably caught packages
 * nobody had vetted: Settings (whose FallbackHome activity is where early boot lands) and Quickstep
 * (which is also SystemUI's recents provider). Same API, but this is a hand-checked list of named
 * packages, every one of which was confirmed present with a launcher icon on the target hardware.
 * A sweep cannot be reviewed; a list can.
 *
 * The alternative -- `adb shell pm uninstall --user 0` -- is safer still but needs a cable on every
 * unit, which is not a deployment model for a fleet. This runs from the QR, over Wi-Fi.
 *
 * Guards, all of which refuse regardless of what a QR asks for:
 *  - never a protected package (launcher / HOME / Settings / SystemUI / GMS / installer / IME),
 *  - never anything that resolves HOME,
 *  - only packages that actually have a LAUNCHER activity -- i.e. things a person could tap --
 *    with one deliberate exception for the app-prediction service, which has no icon and is the
 *    source of the taskbar's suggested apps.
 *
 * Bookkeeping is kept in [AppConfig.removedPackages], deliberately SEPARATE from AppLockdown's
 * records: its self-heal un-hides everything it knows about whenever the retired switches are off,
 * which would silently undo these on the next launch.
 */
object AppRemover {

    /**
     * Confirmed present with a launcher icon on a MicroTouch M1-156IC (Android 13). Baked into the
     * APK rather than carried in the QR so the QR stays small enough to scan reliably; a QR may
     * still override it wholesale with the `removeApps` extra.
     *
     * Deliberately ABSENT, each for a reason:
     *  - com.google.android.deskclock      the screen saver's dream lives here
     *  - com.google.android.documentsui    the system file picker, not the "Files" app
     *  - com.android.vending               Play Store; wanted on these terminals
     *  - com.mediatek.camera               camera; wanted on these terminals
     *  - com.google.android.googlequicksearchbox  feeds launcher search surfaces; add per-fleet
     *    via the `removeApps` extra once proven on a unit with adb attached
     */
    val DEFAULT_REMOVED_APPS: List<String> = listOf(
        "com.google.android.apps.docs",           // Drive
        "com.google.android.apps.maps",           // Maps
        "com.google.android.apps.messaging",      // Messages
        "com.google.android.apps.nbu.files",      // Files by Google
        "com.google.android.apps.photos",         // Photos
        "com.google.android.apps.safetyhub",      // Safety
        "com.google.android.apps.tachyon",        // Meet
        "com.google.android.apps.youtube.music",  // YT Music
        "com.google.android.calculator",          // Calculator
        "com.google.android.calendar",            // Calendar
        "com.google.android.contacts",            // Contacts
        "com.google.android.gm",                  // Gmail
        "com.google.android.keep",                // Keep Notes
        "com.google.android.videos",              // Google TV
        "com.google.android.youtube",             // YouTube
        "com.android.dialer",                     // Phone
    )

    data class Outcome(val removed: List<String>, val restored: List<String>, val refused: List<String>, val failed: List<String>) {
        val summary: String
            get() = "removed=${removed.size} restored=${restored.size}" +
                (if (refused.isEmpty()) "" else " refused=${refused.joinToString()}") +
                (if (failed.isEmpty()) "" else " FAILED=${failed.joinToString()}")
    }

    fun requestedList(context: Context): List<String> {
        val cfg = AppConfig.get(context)
        val override = cfg.removeAppsOverride
        return if (override.isNotEmpty()) override else DEFAULT_REMOVED_APPS
    }

    /** Bring the device to the configured state. Idempotent; safe to call at every boot. */
    fun apply(context: Context, log: (String) -> Unit = { ProvisioningLog.i(context, it) }): Outcome {
        val dp = DevicePolicy(context)
        dp.requireDeviceOwner()
        val cfg = AppConfig.get(context)

        val wanted = LinkedHashSet<String>()
        if (cfg.removeBloat) wanted.addAll(requestedList(context))
        if (cfg.disablePredictions) {
            AppLockdown.predictionServicePackage()?.let { wanted.add(it) }
                ?: log("AppRemover: no app-prediction service declared by this build")
        }

        val launchable = runCatching { AppLockdown.launchablePackages(context) }.getOrElse { emptySet() }
        val predictionPkg = AppLockdown.predictionServicePackage()
        val installed = { pkg: String ->
            runCatching { context.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES) }.isSuccess
        }

        val removed = mutableListOf<String>()
        val restored = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val nowRemoved = cfg.removedPackages.toMutableSet()

        for (pkg in wanted) {
            when {
                pkg == context.packageName -> refused.add("$pkg(self)")
                AppLockdown.isProtected(context, pkg) -> refused.add("$pkg(protected)")
                !installed(pkg) -> refused.add("$pkg(not installed)")
                pkg != predictionPkg && !launchable.contains(pkg) ->
                    refused.add("$pkg(no launcher icon)")
                else -> {
                    val already = runCatching { dp.dpm.isApplicationHidden(dp.admin, pkg) }.getOrDefault(false)
                    if (already) { nowRemoved.add(pkg); continue }
                    val ok = runCatching { dp.dpm.setApplicationHidden(dp.admin, pkg, true) }.getOrDefault(false)
                    if (ok) { removed.add(pkg); nowRemoved.add(pkg) } else failed.add(pkg)
                }
            }
        }

        // Anything we removed before that is no longer wanted comes back.
        for (pkg in cfg.removedPackages) {
            if (pkg in wanted) continue
            val ok = runCatching { dp.dpm.setApplicationHidden(dp.admin, pkg, false) }.getOrDefault(false)
            if (ok) { restored.add(pkg); nowRemoved.remove(pkg) } else failed.add(pkg)
        }

        cfg.removedPackages = nowRemoved
        val outcome = Outcome(removed, restored, refused, failed)
        log("AppRemover: ${outcome.summary} total=${nowRemoved.size}")
        return outcome
    }

    /** Put every removed app back. Used by revert and by the admin screen. */
    fun restoreAll(context: Context): Pair<Boolean, String> {
        val dp = DevicePolicy(context)
        if (!dp.isDeviceOwner) return false to "Not Device Owner; cannot restore apps."
        val cfg = AppConfig.get(context)
        val restored = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (pkg in cfg.removedPackages) {
            val ok = runCatching { dp.dpm.setApplicationHidden(dp.admin, pkg, false) }.getOrDefault(false)
            if (ok) restored.add(pkg) else failed.add(pkg)
        }
        cfg.removedPackages = failed.toSet()
        cfg.removeBloat = false
        cfg.disablePredictions = false
        return if (failed.isEmpty()) true to "Restored ${restored.size} app(s)."
        else false to "Restored ${restored.size}; FAILED: ${failed.joinToString()}"
    }
}
