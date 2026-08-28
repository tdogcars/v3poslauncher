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
 *   "hideOtherApps": "true",                                 // optional, default true (v3.4+)
 *   "disableAppSuggestions": "true",                         // optional, default true (v3.4+)
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
    const val KEY_HIDE_OTHER_APPS = "hideOtherApps"
    const val KEY_DISABLE_SUGGESTIONS = "disableAppSuggestions"
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
        bundle.getString(KEY_HIDE_OTHER_APPS)?.trim()?.let {
            cfg.lockdownApps = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_HIDE_OTHER_APPS
        }
        bundle.getString(KEY_DISABLE_SUGGESTIONS)?.trim()?.let {
            cfg.disableAppSuggestions = it.equals("true", ignoreCase = true) || it == "1"
            applied += KEY_DISABLE_SUGGESTIONS
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
