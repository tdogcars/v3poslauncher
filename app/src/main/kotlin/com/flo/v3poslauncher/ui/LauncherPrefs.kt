package com.flo.v3poslauncher.ui

import android.content.Context
import android.content.SharedPreferences
import com.flo.v3poslauncher.config.AppConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** What the launcher UI needs: the ordered app list and the icon size. */
data class LauncherConfig(
    val posPackages: List<String>,
    val iconSizeDp: Int,
)

/**
 * The v1 UI's preference facade, re-backed by v3's [AppConfig] so there is ONE store: the app
 * list the QR's admin-extras bundle writes at provisioning time is the same list the
 * Launcher-configuration screen edits.
 */
object LauncherPrefs {
    const val DEFAULT_ICON_SIZE_DP = 125
    val ICON_SIZE_RANGE = 96..320

    private fun snapshot(cfg: AppConfig) = LauncherConfig(
        posPackages = cfg.homeApps,
        iconSizeDp = cfg.iconSizeDp.coerceIn(ICON_SIZE_RANGE),
    )

    fun configFlow(context: Context): Flow<LauncherConfig> {
        val cfg = AppConfig.get(context)
        return callbackFlow {
            trySend(snapshot(cfg))
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(snapshot(cfg)) }
            cfg.addChangeListener(listener)
            awaitClose { cfg.removeChangeListener(listener) }
        }.conflate()
    }

    fun setPosPackages(context: Context, packages: List<String>) {
        AppConfig.get(context).homeApps = packages
    }

    fun setIconSize(context: Context, sizeDp: Int) {
        AppConfig.get(context).iconSizeDp = sizeDp.coerceIn(ICON_SIZE_RANGE)
    }
}
