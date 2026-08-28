package com.flo.v3poslauncher.provisioning

import android.content.Context
import android.provider.Settings
import com.flo.v3poslauncher.admin.AppLockdown
import com.flo.v3poslauncher.admin.DevicePolicy
import com.flo.v3poslauncher.admin.LockTaskManager
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.wifi.WifiProvisioner

/**
 * The rollback contract. Every policy this app applies is reversible here, and "Undo everything"
 * runs the steps in the correct order, ending with clearDeviceOwnerApp() — after which the device
 * behaves stock and the app can be uninstalled normally, with no factory reset.
 *
 * ORDER MATTERS: clearDeviceOwnerApp() must be LAST, because once DO is cleared we can no longer
 * call setApplicationHidden / setGlobalSetting / clearPackagePersistentPreferredActivities. Each
 * earlier step is individually invocable from the admin panel and each reports its own outcome.
 */
class RevertManager(context: Context) {
    private val appContext = context.applicationContext
    private val dp = DevicePolicy(appContext)
    private val cfg = AppConfig.get(appContext)

    data class StepOutcome(val name: String, val ok: Boolean, val detail: String)

    /** 1. Un-hide every launcher package we hid. */
    fun unhideStockLauncher(): StepOutcome = wrap("Unhide stock launcher") {
        if (!dp.isDeviceOwner) return@wrap false to "Not Device Owner; cannot unhide."
        val pkgs = cfg.hiddenPackages
        if (pkgs.isEmpty()) return@wrap true to "Nothing was hidden."
        val restored = mutableListOf<String>(); val failed = mutableListOf<String>()
        for (p in pkgs) {
            val ok = runCatching { dp.dpm.setApplicationHidden(dp.admin, p, false) }.getOrDefault(false)
            if (ok) restored.add(p) else failed.add(p)
        }
        cfg.hiddenPackages = failed.toSet() // keep only the ones still hidden
        if (failed.isEmpty()) true to "Unhid: ${restored.joinToString()}"
        else false to "Unhid ${restored.joinToString()}; FAILED: ${failed.joinToString()}"
    }

    /** 1a. Leave dedicated-terminal mode and clear the lock task allowlist. */
    fun clearLockTask(): StepOutcome = wrap("Clear dedicated terminal mode") {
        LockTaskManager.clear(appContext)
    }

    /** 1b. Un-hide the non-allowed apps and the app-prediction service (AppLockdown). */
    fun unhideOtherApps(): StepOutcome = wrap("Unhide non-allowed apps & suggestions") {
        AppLockdown.unhideAll(appContext)
    }

    /** 2. Clear our persistent HOME preference so the stock launcher is default again. */
    fun clearPersistentHome(): StepOutcome = wrap("Clear persistent HOME") {
        if (!dp.isDeviceOwner) return@wrap false to "Not Device Owner; cannot clear HOME preference."
        dp.dpm.clearPackagePersistentPreferredActivities(dp.admin, dp.selfPackage)
        cfg.persistentHomeApplied = false
        true to "Persistent HOME preference cleared."
    }

    /** 3. Restore the original display settings. */
    fun restoreDisplayTimeout(): StepOutcome = wrap("Restore screen timeout") {
        if (!dp.isDeviceOwner) return@wrap false to "Not Device Owner; cannot restore settings."
        val stayOn = if (cfg.originalStayOnWhilePluggedIn >= 0) cfg.originalStayOnWhilePluggedIn else 0
        val timeout = if (cfg.originalScreenOffTimeout >= 0) cfg.originalScreenOffTimeout else 60_000
        dp.dpm.setGlobalSetting(dp.admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, stayOn.toString())
        dp.dpm.setSystemSetting(dp.admin, Settings.System.SCREEN_OFF_TIMEOUT, timeout.toString())
        cfg.displayPolicyApplied = false
        true to "Restored stay-on=$stayOn, screen-off timeout=${timeout}ms."
    }

    /** Optional: forget the FLO Secure network. Not part of "Undo everything" by default because
     *  removing the network could strand a device mid-revert; exposed as its own admin action. */
    fun forgetWifi(): StepOutcome = wrap("Forget FLO Secure Wi-Fi") {
        val removed = WifiProvisioner(appContext).removeNetwork(cfg.wifiSsid)
        removed to if (removed) "Removed '${cfg.wifiSsid}'." else "Network '${cfg.wifiSsid}' was not saved."
    }

    /** 4. FINAL: relinquish Device Owner. After this the app is a normal, uninstallable app. */
    fun clearDeviceOwner(): StepOutcome = wrap("Clear Device Owner") {
        if (!dp.isDeviceOwner) return@wrap true to "Already not Device Owner."
        dp.dpm.clearDeviceOwnerApp(dp.selfPackage)
        cfg.provisioningCompleted = false
        true to "Device Owner cleared. The app can now be uninstalled; device is stock."
    }

    /** Runs 1 → 2 → 3 → 4 in order. Stops before clearing DO if an earlier DO-requiring step
     *  hard-failed, so the operator can see what went wrong while still Device Owner. */
    fun undoEverything(progress: (StepOutcome) -> Unit): List<StepOutcome> {
        val results = mutableListOf<StepOutcome>()
        fun step(o: StepOutcome) { results.add(o); progress(o); ProvisioningLog.i(appContext, "Revert: ${o.name}: ${if (o.ok) "OK" else "FAIL"} — ${o.detail}") }

        step(clearLockTask())
        step(unhideStockLauncher())
        step(unhideOtherApps())
        step(clearPersistentHome())
        step(restoreDisplayTimeout())

        val hardFailed = results.any { !it.ok }
        if (hardFailed) {
            val note = StepOutcome("Clear Device Owner", false,
                "SKIPPED because an earlier step failed. Fix it and retry, or clear DO manually. " +
                    "DO intentionally kept so the earlier steps can be retried.")
            step(note)
            return results
        }
        step(clearDeviceOwner())
        return results
    }

    private inline fun wrap(name: String, block: () -> Pair<Boolean, String>): StepOutcome =
        try { val (ok, detail) = block(); StepOutcome(name, ok, detail) }
        catch (t: Throwable) {
            ProvisioningLog.e(appContext, "Revert step '$name' threw", t)
            StepOutcome(name, false, "${t.javaClass.simpleName}: ${t.message}")
        }
}
