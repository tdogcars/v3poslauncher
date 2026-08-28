package com.flo.v3poslauncher.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

private fun Drawable.toIconBitmap(sizePx: Int): ImageBitmap {
    val size = sizePx.coerceAtLeast(1)
    return toBitmap(size, size).asImageBitmap()
}

/**
 * Resolves the configured POS package to its launcher entry, or null when the
 * package is missing or has no launchable activity. Runs off the main thread;
 * the icon is rasterized here at the exact display size so the UI thread never
 * decodes drawables.
 */
suspend fun resolvePosApp(context: Context, packageName: String, iconSizePx: Int): InstalledApp? =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return@withContext null
        try {
            @Suppress("DEPRECATION")
            val resolved = pm.resolveActivity(launchIntent, 0)
                ?: return@withContext null
            InstalledApp(
                packageName = packageName,
                label = resolved.loadLabel(pm).toString(),
                icon = resolved.loadIcon(pm).toIconBitmap(iconSizePx),
            )
        } catch (e: Exception) {
            // Package removed between the lookup and the load: treat as missing.
            null
        }
    }

/** All launchable apps on the device except this launcher, for the technician picker. */
suspend fun queryLaunchableApps(context: Context, iconSizePx: Int): List<InstalledApp> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(launcherIntent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it != null && it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .mapNotNull { activityInfo ->
                try {
                    InstalledApp(
                        packageName = activityInfo.packageName,
                        label = activityInfo.loadLabel(pm).toString(),
                        icon = activityInfo.loadIcon(pm).toIconBitmap(iconSizePx),
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

/** Starts the POS app; returns false when it cannot be launched. */
fun launchApp(context: Context, packageName: String): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    return try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        // ActivityNotFoundException or SecurityException if the app vanished or
        // its launcher activity is not exported/enabled anymore.
        false
    }
}
