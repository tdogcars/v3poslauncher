package com.flo.v3poslauncher.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.flo.v3poslauncher.config.Constants
import com.flo.v3poslauncher.provisioning.ProvisioningLog

/** Resolving, labelling and starting the already-installed apps shown on the home grid. */
object AppLauncher {

    data class AppEntry(
        val requestedPackage: String,
        val resolvedPackage: String?,
        val label: String,
        val icon: Drawable?,
    ) {
        val installed: Boolean get() = resolvedPackage != null
    }

    /** Resolve a configured package to something launchable, applying Chrome fallbacks. */
    fun resolve(context: Context, pkg: String): AppEntry {
        val pm = context.packageManager
        val candidates = if (pkg.equals("com.android.chrome", ignoreCase = true))
            (listOf(pkg) + Constants.CHROME_FALLBACKS).distinct() else listOf(pkg)

        for (candidate in candidates) {
            val launch = pm.getLaunchIntentForPackage(candidate) ?: continue
            val label = try {
                val ai = pm.getApplicationInfo(candidate, 0)
                pm.getApplicationLabel(ai).toString()
            } catch (_: Throwable) { candidate }
            val icon = try { pm.getApplicationIcon(candidate) } catch (_: Throwable) { null }
            return AppEntry(pkg, candidate, label, icon)
        }
        return AppEntry(pkg, null, prettyFallbackName(pkg), null)
    }

    fun launchIntentFor(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

    fun launch(context: Context, pkg: String): Boolean {
        val intent = launchIntentFor(context, pkg)
        if (intent == null) {
            ProvisioningLog.w(context, "AppLauncher: no launch intent for '$pkg'")
            return false
        }
        return try {
            context.startActivity(intent); true
        } catch (t: Throwable) {
            ProvisioningLog.e(context, "AppLauncher: startActivity failed for '$pkg'", t)
            false
        }
    }

    /** All apps the device offers a launcher entry for — used by the admin "add app" picker. */
    fun installedLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
            .mapNotNull { ri ->
                val p = ri.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(p, p, ri.loadLabel(pm).toString(), runCatching { ri.loadIcon(pm) }.getOrNull())
            }
            .distinctBy { it.resolvedPackage }
            .sortedBy { it.label.lowercase() }
    }

    private fun prettyFallbackName(pkg: String): String = when {
        pkg.contains("chrome", true) -> "Chrome"
        pkg.contains("settings", true) -> "Settings"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
}
