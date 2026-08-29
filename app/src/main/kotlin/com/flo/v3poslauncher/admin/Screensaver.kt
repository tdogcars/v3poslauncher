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
    private const val DEFAULT_COMPONENT = "screensaver_default_component"

    const val GRANT_COMMAND =
        "adb shell pm grant com.flo.v3poslauncher android.permission.WRITE_SECURE_SETTINGS"

    data class Result(val applied: Boolean, val message: String)

    /**
     * The device's real answer, straight from Settings.Secure. Reading that namespace needs no
     * permission at all — only writing does — so this is always available and is the truth even
     * when the launcher itself could not set it. This is what the home-screen notice keys off, so
     * a screen saver switched on by hand in Android settings clears the notice by itself.
     */
    fun isOnDevice(context: Context): Boolean = isToggleOn(context) && resolvedDream(context) != null

    /** The toggle in Settings > Display > Screen saver, on its own. */
    fun isToggleOn(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * The dream that would ACTUALLY run, or null if none would.
     *
     * Observed on a MicroTouch M1-156IC: screensaver_components was unset, so the framework fell
     * back to screensaver_default_component, which names `com.android.deskclock` — the AOSP clock.
     * This is a GMS build, where Google Clock (`com.google.android.deskclock`) replaces it, so the
     * default resolved to nothing. The toggle read on, activate_on_sleep was 1, the timeout was
     * right, and the panel would still have gone black at the idle mark with no dream to show.
     *
     * Hence: never trust the toggle alone. A configured component is only honoured if it resolves.
     */
    fun resolvedDream(context: Context): ComponentName? {
        val cr = context.contentResolver
        val configured = runCatching { Settings.Secure.getString(cr, COMPONENTS) }.getOrNull()
        val fallback = runCatching { Settings.Secure.getString(cr, DEFAULT_COMPONENT) }.getOrNull()
        return sequenceOf(configured, fallback)
            .filterNotNull()
            .flatMap { it.split(',', ':').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .firstOrNull { dreamExists(context, it) }
    }

    private fun dreamExists(context: Context, component: ComponentName): Boolean = runCatching {
        val intent = Intent("android.service.dreams.DreamService").setPackage(component.packageName)
        context.packageManager.queryIntentServices(intent, 0).any {
            it.serviceInfo?.packageName == component.packageName &&
                it.serviceInfo?.name == component.className
        }
    }.getOrDefault(false)

    /** Android's own screen saver page, with fallbacks for skins that do not expose it directly. */
    fun openSettings(context: Context) {
        val actions = listOf(
            Settings.ACTION_DREAM_SETTINGS,
            Settings.ACTION_DISPLAY_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        for (action in actions) {
            val opened = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
        ProvisioningLog.w(context, "Screensaver: no screen saver settings screen could be opened")
    }

    /**
     * The raw device state. Worth showing on the admin screen: if the toggle reads on but the panel
     * never dreams, the answer is almost always in here — no component chosen, or both trigger keys
     * zero so there is no condition under which it would ever start.
     */
    fun describeDevice(context: Context): String {
        val cr = context.contentResolver
        fun key(name: String) = runCatching { Settings.Secure.getInt(cr, name, -1) }.getOrDefault(-1)
        val component = runCatching { Settings.Secure.getString(cr, COMPONENTS) }.getOrNull()
        val resolved = resolvedDream(context)
        return "device: enabled=${key(ENABLED)} whileCharging=${key(ON_SLEEP)} " +
            "whileDocked=${key(ON_DOCK)}\nchosen=${component ?: "(none)"}\n" +
            "will actually run=${resolved?.flattenToString() ?: "NOTHING — the screen would just go black"}"
    }

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
            // We cannot switch it on ourselves, but a person can, in two taps. Trust the device.
            val onDevice = isOnDevice(context)
            cfg.screensaverActive = onDevice
            return when {
                onDevice -> Result(true, "Screen saver is on and will run ${resolvedDream(context)?.flattenToString()}.")
                isToggleOn(context) -> Result(
                    false,
                    "Screen saver is switched on but NO screen saver is chosen that this build " +
                        "actually has, so nothing would appear. Open Settings > Display > Screen " +
                        "saver and pick one (Clock). ${describeDevice(context)}",
                )
                else -> Result(
                    false,
                    "Screen saver is off. Turn it on in Settings > Display > Screen saver — the " +
                        "home screen has a shortcut. To have the launcher set it with no visit at " +
                        "all, grant it once over USB: $GRANT_COMMAND",
                )
            }
        }

        return try {
            // Respect a dream the technician already chose -- but only if it actually resolves.
            // A stale default that names a package this build does not ship is worse than no
            // setting at all, because everything else reads as correctly configured.
            val existing = resolvedDream(context)?.flattenToString()
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
