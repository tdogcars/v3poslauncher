package com.flo.v3poslauncher.config

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import com.flo.v3poslauncher.provisioning.ProvisioningLog

/**
 * Keys understood inside the QR's `android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE`.
 * All values are strings (that is what the QR JSON → PersistableBundle path preserves).
 *
 * This build installs no APKs, so there is no POS URL/checksum/package. The only things worth
 * overriding per-fleet are the admin PIN, the home app list, whether to hide the stock launcher,
 * and (rarely) the Wi-Fi. All are OPTIONAL — with an empty bundle the app uses its constants.
 *
 * Example bundle (see ci/make_qr.py):
 * ```
 * "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
 *   "homeApps": "com.android.chrome,com.android.settings",  // optional, comma-separated, ordered
 *   "adminPin": "4821",                                      // optional 4-digit override
 *   "hideStockLauncher": "false",                            // optional, default false (v3.0.2+)
 *   "hideTaskbar": "false",                                  // optional, default false (v3.3+)
 *   "dedicatedTerminal": "true",                             // optional, default true (v3.5+)
 *   "hideOtherApps": "false",                                // optional, default FALSE — unsafe
 *   "disableAppSuggestions": "false",                        // optional, default FALSE — unsafe
 *   "screensaver": "true",                                   // optional, default true (v3.7+)
 *   "screensaverIdleMinutes": "10",                           // optional, default 10
 *   "wifiSsid": "FLO Secure",                                // optional
 *   "wifiPassword": "…"                                      // optional
 * }
 * ```
 */
object AdminExtras {
    const val KEY_HOME_APPS = "homeApps"
    const val KEY_ADMIN_PIN = "adminPin"
    const val KEY_HIDE_STOCK_LAUNCHER = "hideStockLauncher"
    const val KEY_HIDE_TASKBAR = "hideTaskbar"
    const val KEY_LOCK_TASK = "dedicatedTerminal"
    const val KEY_HIDE_OTHER_APPS = "hideOtherApps"
    const val KEY_DISABLE_SUGGESTIONS = "disableAppSuggestions"
    const val KEY_REMOVE_BLOAT = "removeBloat"
    const val KEY_REMOVE_APPS = "removeApps"
    const val KEY_DISABLE_PREDICTIONS = "disablePredictions"
    const val KEY_SCREENSAVER = "screensaver"
    const val KEY_SCREENSAVER_IDLE = "screensaverIdleMinutes"
    const val KEY_WIFI_SSID = "wifiSsid"
    const val KEY_WIFI_PASSWORD = "wifiPassword"

    fun applyFromIntent(context: Context, intent: Intent?, source: String): Boolean {
        val bundle: PersistableBundle? = try {
            intent?.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        } catch (t: Throwable) {
            ProvisioningLog.w(context, "AdminExtras: could not read bundle from $source: $t")
            null
        }
        if (bundle == null) {
            ProvisioningLog.i(context, "AdminExtras: no admin extras bundle in $source")
            return false
        }
        apply(context, bundle, source)
        return true
    }

    fun apply(context: Context, bundle: PersistableBundle, source: String) {
        val cfg = AppConfig.get(context)
        val applied = mutableListOf<String>()

        bundle.getString(KEY_HOME_APPS)?.trim()?.takeIf { it.isNotEmpty() }?.let { csv ->
            val apps = csv.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (apps.isNotEmpty()) { cfg.homeApps = apps; applied += KEY_HOME_APPS }
        }
        bundle.getString(KEY_HIDE_STOCK_LAUNCHER)?.trim()?.let {
            cfg.hideStockLauncher = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_HIDE_STOCK_LAUNCHER
        }
        bundle.getString(KEY_HIDE_TASKBAR)?.trim()?.let {
            cfg.hideTaskbar = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_HIDE_TASKBAR
        }
        bundle.getString(KEY_LOCK_TASK)?.trim()?.let {
            cfg.lockTaskEnabled = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_LOCK_TASK
        }
        bundle.getString(KEY_HIDE_OTHER_APPS)?.trim()?.let {
            cfg.lockdownApps = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_HIDE_OTHER_APPS
        }
        bundle.getString(KEY_DISABLE_SUGGESTIONS)?.trim()?.let {
            cfg.disableAppSuggestions = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_DISABLE_SUGGESTIONS
        }
        bundle.getString(KEY_REMOVE_BLOAT)?.trim()?.let {
            cfg.removeBloat = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_REMOVE_BLOAT
        }
        bundle.getString(KEY_DISABLE_PREDICTIONS)?.trim()?.let {
            cfg.disablePredictions = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_DISABLE_PREDICTIONS
        }
        bundle.getString(KEY_REMOVE_APPS)?.trim()?.takeIf { it.isNotEmpty() }?.let { csv ->
            val apps = csv.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (apps.isNotEmpty()) { cfg.removeAppsOverride = apps; applied += KEY_REMOVE_APPS }
        }
        bundle.getString(KEY_SCREENSAVER)?.trim()?.let {
            cfg.screensaverEnabled = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_SCREENSAVER
        }
        bundle.getString(KEY_SCREENSAVER_IDLE)?.trim()?.toIntOrNull()?.let {
            cfg.screensaverIdleMinutes = it; applied += KEY_SCREENSAVER_IDLE
        }
        bundle.getString(KEY_WIFI_SSID)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            cfg.wifiSsid = it; applied += KEY_WIFI_SSID
        }
        bundle.getString(KEY_WIFI_PASSWORD)?.takeIf { it.isNotEmpty() }?.let {
            cfg.wifiPassword = it; applied += "$KEY_WIFI_PASSWORD(redacted)"
        }
        bundle.getString(KEY_ADMIN_PIN)?.trim()?.let { pin ->
            if (pin.length == 4 && pin.all { it.isDigit() }) {
                cfg.setPin(pin); applied += "$KEY_ADMIN_PIN(redacted)"
            } else {
                ProvisioningLog.w(context, "AdminExtras: adminPin override ignored (must be 4 digits)")
            }
        }
        cfg.extrasSource = source
        ProvisioningLog.i(context, "AdminExtras: applied from $source: ${applied.joinToString(", ").ifEmpty { "(nothing)" }}")
    }
}
