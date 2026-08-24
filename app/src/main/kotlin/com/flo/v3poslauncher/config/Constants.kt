package com.flo.v3poslauncher.config

import com.flo.v3poslauncher.BuildConfig

/**
 * Compile-time defaults. The admin PIN and Wi-Fi credentials can be overridden at provisioning
 * time through the QR's PROVISIONING_ADMIN_EXTRAS_BUNDLE (see [AdminExtras]) or later from the
 * admin panel. The home app list is likewise editable from the admin panel.
 *
 * This build installs NO additional APKs. The launcher shows a small grid of already-installed
 * apps (by default Chrome and Settings) and nothing is downloaded.
 *
 * SECURITY NOTES
 *  - WIFI_PASSWORD and DEFAULT_ADMIN_PIN are secrets that live in code by design decision.
 *    They must never be written to the provisioning log, shown on screen, or put in a crash
 *    string. Grep for "WIFI_PASSWORD" / "DEFAULT_ADMIN_PIN" before adding any logging.
 *  - The QR card carries the Wi-Fi password in plaintext regardless; treat printed cards like
 *    Wi-Fi password cards.
 */
object Constants {
    /** Company-standard install-site network SSID (not secret). */
    const val WIFI_SSID = "FLO Secure"

    /**
     * Install-site Wi-Fi password. NOT hardcoded in source, so the repo can be public without
     * leaking it. It is injected at build time from the CI `WIFI_PASSWORD` secret (see
     * app/build.gradle.kts → BuildConfig.WIFI_PASSWORD_DEFAULT) and, more importantly, supplied
     * per-device by the QR's admin-extras bundle at provision time. If both are empty, the
     * Wi-Fi step fails loudly rather than saving a passwordless network.
     */
    val WIFI_PASSWORD: String = BuildConfig.WIFI_PASSWORD_DEFAULT

    /**
     * Default 4-digit admin-panel PIN used when the QR did not supply an override. This is a
     * documented default meant to be changed per-fleet (QR `adminPin` or the admin panel); it is
     * not a device-security secret and only gates the launcher's own settings screen.
     */
    const val DEFAULT_ADMIN_PIN = "5913"

    /** Default home-screen icon size, in dp. */
    const val DEFAULT_ICON_SIZE_DP = 125

    /**
     * Default apps shown on the home grid, in order. These are STANDARD system/preinstalled
     * package names; nothing is installed by the launcher. Chrome may be absent on some AOSP-
     * based POS images — the launcher detects that and the app list is editable from the panel.
     */
    val DEFAULT_APPS: List<String> = listOf(
        "com.android.chrome",     // Google Chrome
        "com.android.settings",   // Android Settings
    )

    /** Known alternative Chrome package names, tried if com.android.chrome is absent. */
    val CHROME_FALLBACKS: List<String> = listOf(
        "com.chrome.beta", "com.android.chrome", "org.chromium.chrome",
    )

    /** PIN gate: lockout after this many consecutive wrong entries. */
    const val PIN_MAX_ATTEMPTS = 5
    const val PIN_LOCKOUT_MS = 30_000L

    /** Provisioning log file (in app-private files dir) and its rotation cap. */
    const val LOG_FILE_NAME = "provisioning.log"
    const val LOG_MAX_BYTES = 256 * 1024L
}
