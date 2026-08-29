package com.flo.v3poslauncher.admin

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningLog
import com.flo.v3poslauncher.provisioning.steps.DisplayPolicyStep
import kotlin.concurrent.thread

/**
 * The stock Android screen saver (Settings > Display > Screen saver).
 *
 * WHY THIS IS NOT PURE ZERO-TOUCH. The screen saver lives in four Settings.Secure keys, and the
 * Device Owner secure-setting allowlist does not include them — DevicePolicyManager.setSecureSetting
 * throws for anything but a short list (default input method, first-use hints). A Device Owner also
 * cannot grant itself WRITE_SECURE_SETTINGS: setPermissionGrantState only covers runtime
 * (dangerous) permissions, and this one is signature|privileged|development. So the QR gets the
 * device to the point where one command finishes the job:
 *
 *     adb shell pm grant com.flo.v3poslauncher android.permission.WRITE_SECURE_SETTINGS
 *
 * That grant survives reboots (not reinstalls), and it costs nothing extra in the field because
 * the staged rollout already has the terminal on USB to authorize adb before the first reboot.
 * [reapplyAsync] runs on every launcher start and at boot, so the moment the grant exists the
 * screen saver configures itself with no further visit.
 */
object Screensaver {

    private const val ENABLED = "screensaver_enabled"
    private const val COMPONENTS = "screensaver_components"
    private const val ON_SLEEP = "screensaver_activate_on_sleep"
    private const val ON_DOCK = "screensaver_activate_on_dock"

    const val GRANT_COMMAND =
        "adb shell pm grant com.flo.v3poslauncher android.permission.WRITE_SECURE_SETTINGS"

    data class Result(val applied: Boolean, val message: String)

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Installed dream services, clock preferred — the one Tyler selects by hand today. */
    fun preferredDream(context: Context): ComponentName? {
        val intent = Intent("android.service.dreams.DreamService")
        val found = runCatching {
            context.packageManager.queryIntentServices(intent, 0)
        }.getOrElse { emptyList() }
        val comps = found.mapNotNull { ri ->
            ri.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        }
        return comps.firstOrNull {
            it.className.contains("clock", ignoreCase = true) ||
                it.packageName.contains("deskclock", ignoreCase = true)
        } ?: comps.firstOrNull()
    }

    /**
     * Bring the screen saver to the configured state. Never throws; the caller decides whether a
     * failure is worth surfacing. Sets [AppConfig.screensaverActive] ONLY when the write was read
     * back successfully — the display policy keys off that, so a terminal without the grant keeps
     * the always-on screen instead of going black with no dream to show.
     */
    fun apply(context: Context): Result {
        val cfg = AppConfig.get(context)
        val cr = context.contentResolver

        if (!cfg.screensaverEnabled) {
            val wasActive = cfg.screensaverActive
            cfg.screensaverActive = false
            if (wasActive && canWrite(context)) runCatching { Settings.Secure.putInt(cr, ENABLED, 0) }
            return Result(false, "Screen saver is switched off in configuration.")
        }

        if (!canWrite(context)) {
            cfg.screensaverActive = false
            return Result(
                false,
                "Screen saver settings are not writable by this app yet. Device Owner cannot grant " +
                    "this permission to itself. Run once with the terminal on USB: $GRANT_COMMAND",
            )
        }

        return try {
            // Respect a dream the technician already chose; only pick one if nothing is set.
            val existing = Settings.Secure.getString(cr, COMPONENTS)?.takeIf { it.isNotBlank() }
            val chosen = existing ?: preferredDream(context)?.flattenToString()
            if (chosen != null) Settings.Secure.putString(cr, COMPONENTS, chosen)
            Settings.Secure.putInt(cr, ENABLED, 1)
            Settings.Secure.putInt(cr, ON_SLEEP, 1)   // "When to start: While charging"
            Settings.Secure.putInt(cr, ON_DOCK, 0)
            val verified = Settings.Secure.getInt(cr, ENABLED, 0) == 1
            cfg.screensaverActive = verified
            if (verified) {
                Result(true, "Screen saver ON (${chosen ?: "system default"}), after ${cfg.screensaverIdleMinutes} min idle.")
            } else {
                Result(false, "Wrote the screen saver settings but the system did not keep them.")
            }
        } catch (t: Throwable) {
            cfg.screensaverActive = false
            Result(false, "Could not write screen saver settings (${t.javaClass.simpleName}: ${t.message}). $GRANT_COMMAND")
        }
    }

    /**
     * Fire-and-forget re-apply from the launcher's own start-up and from BOOT_COMPLETED. Cheap and
     * idempotent; when it flips state it also re-applies the display policy, because the stay-on
     * setting and the screen saver are two halves of one decision.
     */
    fun reapplyAsync(context: Context) {
        val app = context.applicationContext
        thread(name = "screensaver-apply") {
            runCatching {
                val cfg = AppConfig.get(app)
                val before = cfg.screensaverActive
                val wanted = cfg.screensaverEnabled
                // Steady state on both sides -- nothing to write. Checked first so calling this on
                // every resume costs a SharedPreferences read and a permission check.
                if (wanted == before && (!wanted || canWrite(app))) return@runCatching
                val result = apply(app)
                if (result.applied != before) {
                    ProvisioningLog.i(app, "Screensaver: ${result.message}")
                    val dp = DevicePolicy(app)
                    if (dp.isDeviceOwner && cfg.displayPolicyApplied) {
                        ProvisioningLog.i(app, "Screensaver: ${DisplayPolicyStep.apply(app, dp, cfg)}")
                    }
                }
            }.onFailure { ProvisioningLog.w(app, "Screensaver.reapplyAsync: ${it.message}") }
        }
    }
}
