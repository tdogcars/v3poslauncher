package com.flo.v3poslauncher.config

import android.content.Context
import android.content.SharedPreferences
import com.flo.v3poslauncher.util.PinHasher

/**
 * All persisted state, in one place, on app-private SharedPreferences.
 *
 * Nothing here is backed up (allowBackup=false) and nothing leaves the device. Secrets are stored
 * hashed (PIN) or as-is in private storage (Wi-Fi PSK override, only if the QR supplied one).
 */
class AppConfig private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("v3poslauncher", Context.MODE_PRIVATE)

    /** Observe changes (the Compose UI turns this into a Flow). Listener is held weakly by Android. */
    fun addChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun removeChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    // ---- Home app grid -------------------------------------------------------------------

    /**
     * Ordered list of package names shown on the home screen. Editable from the admin panel.
     * Defaults to Chrome + Settings the first time.
     */
    var homeApps: List<String>
        get() {
            val raw = prefs.getString(K_HOME_APPS, null) ?: return Constants.DEFAULT_APPS
            return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        set(v) = prefs.edit().putString(K_HOME_APPS, v.joinToString("\n") { it.trim() }).apply()

    fun addHomeApp(pkg: String) {
        val p = pkg.trim()
        if (p.isEmpty()) return
        if (homeApps.any { it.equals(p, ignoreCase = true) }) return
        homeApps = homeApps + p
    }

    fun removeHomeApp(pkg: String) {
        homeApps = homeApps.filterNot { it.equals(pkg.trim(), ignoreCase = true) }
    }

    /** Move an app up (delta = -1) or down (delta = +1) in the home order. No-op at the ends. */
    fun moveHomeApp(pkg: String, delta: Int) {
        val list = homeApps.toMutableList()
        val i = list.indexOfFirst { it.equals(pkg.trim(), ignoreCase = true) }
        if (i < 0) return
        val j = i + delta
        if (j < 0 || j >= list.size) return
        val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        homeApps = list
    }

    var iconSizeDp: Int
        get() = prefs.getInt(K_ICON_SIZE, Constants.DEFAULT_ICON_SIZE_DP)
        set(v) = prefs.edit().putInt(K_ICON_SIZE, v.coerceIn(48, 512)).apply()

    // ---- Wi-Fi ---------------------------------------------------------------------------

    var wifiSsid: String
        get() = prefs.getString(K_WIFI_SSID, null)?.takeIf { it.isNotBlank() } ?: Constants.WIFI_SSID
        set(v) = prefs.edit().putString(K_WIFI_SSID, v).apply()

    var wifiPassword: String
        get() = prefs.getString(K_WIFI_PSK, null)?.takeIf { it.isNotEmpty() } ?: Constants.WIFI_PASSWORD
        set(v) = prefs.edit().putString(K_WIFI_PSK, v).apply()

    // ---- Provisioning policy switches ----------------------------------------------------

    var hideStockLauncher: Boolean
        get() = prefs.getBoolean(K_HIDE_STOCK, false)
        set(v) = prefs.edit().putBoolean(K_HIDE_STOCK, v).apply()

    /**
     * Also hide the framework's recents/taskbar provider (Quickstep). Removes the large-screen
     * taskbar inside other apps on Android 12L+, at the cost of the Recents button. Off by
     * default; must be proven on a disposable unit first (see PROVISIONING.md §6).
     */
    var hideTaskbar: Boolean
        get() = prefs.getBoolean(K_HIDE_TASKBAR, false)
        set(v) = prefs.edit().putBoolean(K_HIDE_TASKBAR, v).apply()

    /**
     * Zero-touch taskbar answer: hide every launchable app that is not on the home-app list, and
     * hide the system app-prediction service so the taskbar shows no "suggested apps". Quickstep
     * itself stays, so Back / Home / Recents keep working. Both default ON.
     */
    var lockdownApps: Boolean
        get() = prefs.getBoolean(K_LOCKDOWN_APPS, true)
        set(v) = prefs.edit().putBoolean(K_LOCKDOWN_APPS, v).apply()

    var disableAppSuggestions: Boolean
        get() = prefs.getBoolean(K_DISABLE_SUGGESTIONS, true)
        set(v) = prefs.edit().putBoolean(K_DISABLE_SUGGESTIONS, v).apply()

    /** Non-allowed apps we hid (AppLockdown), so revert unhides exactly those. */
    var hiddenOtherApps: Set<String>
        get() = prefs.getStringSet(K_HIDDEN_OTHER, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_HIDDEN_OTHER, v).apply()

    var hiddenPredictionPackage: String
        get() = prefs.getString(K_HIDDEN_PREDICTION, "") ?: ""
        set(v) = prefs.edit().putString(K_HIDDEN_PREDICTION, v).apply()

    /** Packages we actually hid, so revert unhides exactly those. */
    var hiddenPackages: Set<String>
        get() = prefs.getStringSet(K_HIDDEN_PKGS, emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet(K_HIDDEN_PKGS, v).apply()

    var originalScreenOffTimeout: Int
        get() = prefs.getInt(K_ORIG_TIMEOUT, -1)
        set(v) = prefs.edit().putInt(K_ORIG_TIMEOUT, v).apply()

    var originalStayOnWhilePluggedIn: Int
        get() = prefs.getInt(K_ORIG_STAY_ON, -1)
        set(v) = prefs.edit().putInt(K_ORIG_STAY_ON, v).apply()

    var displayPolicyApplied: Boolean
        get() = prefs.getBoolean(K_DISPLAY_APPLIED, false)
        set(v) = prefs.edit().putBoolean(K_DISPLAY_APPLIED, v).apply()

    var persistentHomeApplied: Boolean
        get() = prefs.getBoolean(K_HOME_APPLIED, false)
        set(v) = prefs.edit().putBoolean(K_HOME_APPLIED, v).apply()

    // ---- Lifecycle flags -----------------------------------------------------------------

    var provisioningCompleted: Boolean
        get() = prefs.getBoolean(K_PROV_DONE, false)
        set(v) = prefs.edit().putBoolean(K_PROV_DONE, v).apply()

    var extrasSource: String
        get() = prefs.getString(K_EXTRAS_SOURCE, "") ?: ""
        set(v) = prefs.edit().putString(K_EXTRAS_SOURCE, v).apply()

    fun setStepStatus(stepId: String, status: String) =
        prefs.edit().putString(K_STEP_PREFIX + stepId, status).apply()

    fun getStepStatus(stepId: String): String = prefs.getString(K_STEP_PREFIX + stepId, "never run") ?: "never run"

    // ---- PIN -----------------------------------------------------------------------------

    fun setPin(pin: String) {
        val (salt, hash) = PinHasher.hashNew(pin)
        prefs.edit().putString(K_PIN_SALT, salt).putString(K_PIN_HASH, hash).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val salt = prefs.getString(K_PIN_SALT, null)
        val hash = prefs.getString(K_PIN_HASH, null)
        if (salt == null || hash == null) {
            return PinHasher.constantTimeEquals(pin, Constants.DEFAULT_ADMIN_PIN)
        }
        return PinHasher.verify(pin, salt, hash)
    }

    var pinFailedAttempts: Int
        get() = prefs.getInt(K_PIN_FAILS, 0)
        set(v) = prefs.edit().putInt(K_PIN_FAILS, v).apply()

    var pinLockoutUntil: Long
        get() = prefs.getLong(K_PIN_LOCK_UNTIL, 0L)
        set(v) = prefs.edit().putLong(K_PIN_LOCK_UNTIL, v).apply()

    companion object {
        @Volatile private var instance: AppConfig? = null
        fun get(context: Context): AppConfig =
            instance ?: synchronized(this) { instance ?: AppConfig(context).also { instance = it } }

        private const val K_HOME_APPS = "home_apps"
        private const val K_ICON_SIZE = "icon_size_dp"
        private const val K_WIFI_SSID = "wifi_ssid"
        private const val K_WIFI_PSK = "wifi_psk"
        private const val K_HIDE_STOCK = "hide_stock_launcher"
        private const val K_HIDE_TASKBAR = "hide_taskbar"
        private const val K_LOCKDOWN_APPS = "lockdown_apps"
        private const val K_DISABLE_SUGGESTIONS = "disable_app_suggestions"
        private const val K_HIDDEN_OTHER = "hidden_other_apps"
        private const val K_HIDDEN_PREDICTION = "hidden_prediction_pkg"
        private const val K_HIDDEN_PKGS = "hidden_packages"
        private const val K_ORIG_TIMEOUT = "orig_screen_off_timeout"
        private const val K_ORIG_STAY_ON = "orig_stay_on_plugged"
        private const val K_DISPLAY_APPLIED = "display_policy_applied"
        private const val K_HOME_APPLIED = "persistent_home_applied"
        private const val K_PROV_DONE = "provisioning_completed"
        private const val K_EXTRAS_SOURCE = "extras_source"
        private const val K_STEP_PREFIX = "step_status_"
        private const val K_PIN_SALT = "pin_salt"
        private const val K_PIN_HASH = "pin_hash"
        private const val K_PIN_FAILS = "pin_failed_attempts"
        private const val K_PIN_LOCK_UNTIL = "pin_lockout_until"
    }
}
