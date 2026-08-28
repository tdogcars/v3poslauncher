package com.flo.v3poslauncher.provisioning.steps

import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Step 2 — OPTIONAL: hide the stock launcher (Launcher3/Quickstep/OEM) so its app drawer is gone.
 *
 * OFF BY DEFAULT since v3.0.2. Being the persistent default HOME (step 1) is already enough to
 * remove the taskbar and app drawer on Android 12L+ (the taskbar only exists while Quickstep is
 * the default launcher). Hiding packages is a nice-to-have with real downsides, so it must be
 * explicitly enabled via the QR admin extra `hideStockLauncher=true`.
 *
 * Why this step was rewritten (v3.0.1 hung devices on the boot logo after a reboot):
 *  - `com.android.settings` resolves HOME too (its FallbackHome activity is what the system
 *    shows during early boot). Hiding it left boot with nowhere to land.
 *  - On Android 10+ the Quickstep launcher is also SystemUI's recents/overview provider
 *    (`config_recentsComponentName`). Hiding it can keep SystemUI from starting.
 *
 * Rules now:
 *  1. Never touch a protected package: ourselves, android, Settings, SystemUI, the setup
 *     wizard, permission controller, or the framework's recents component package.
 *  2. Only consider packages that actually resolve HOME (no fuzzy name scans of every app).
 *  3. Skip any package whose HOME activity is a fallback/setup activity rather than a launcher.
 *  4. Record exactly what was hidden so revert can unhide precisely those.
 */
class HideStockLauncherStep : ProvisioningStep {
    override val id = StepId.HIDE_STOCK

    private val protectedPackages = setOf(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.android.provision",
        "com.google.android.setupwizard",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Hide stock launcher") {
        ctx.dp.requireDeviceOwner()
        if (!ctx.config.hideStockLauncher) {
            return@guarded StepResult.Ok("Not hiding the stock launcher (default). We are the persistent HOME, which is enough to remove the taskbar/app drawer.")
        }

        val self = ctx.dp.selfPackage
        val recentsPkg = recentsProviderPackage()
        ctx.log("HideStock: recents provider package = ${recentsPkg ?: "(unknown)"}")

        val candidates = LinkedHashSet<String>()
        val skipped = mutableListOf<String>()
        ctx.dp.resolveHomeActivities().forEach { ri ->
            val ai = ri.activityInfo ?: return@forEach
            val pkg = ai.packageName
            val cls = ai.name.orEmpty()
            when {
                pkg == self -> Unit
                pkg in protectedPackages -> skipped += "$pkg (protected)"
                pkg == recentsPkg -> skipped += "$pkg (SystemUI recents provider)"
                pkg.startsWith("com.android.settings") -> skipped += "$pkg (settings)"
                cls.contains("FallbackHome", ignoreCase = true) ||
                    cls.contains("SetupWizard", ignoreCase = true) ||
                    cls.contains("Provision", ignoreCase = true) -> skipped += "$pkg ($cls is not a launcher)"
                else -> candidates.add(pkg)
            }
        }
        if (skipped.isNotEmpty()) ctx.log("HideStock: skipped: ${skipped.joinToString()}")
        ctx.log("HideStock: candidates: ${candidates.joinToString().ifEmpty { "(none)" }}")

        if (candidates.isEmpty()) {
            return@guarded StepResult.Warn("No hideable launcher package found; nothing hidden.")
        }

        val hidden = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (pkg in candidates) {
            val info: ApplicationInfo? = runCatching { ctx.dp.pm.getApplicationInfo(pkg, 0) }.getOrNull()
            ctx.progress("Hiding $pkg…")
            val ok = try {
                ctx.dp.dpm.setApplicationHidden(ctx.dp.admin, pkg, true)
            } catch (t: Throwable) {
                ctx.err("HideStock: exception hiding $pkg", t); false
            }
            if (ok) {
                hidden.add(pkg)
                ctx.log("HideStock: hid $pkg (system=${info?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }})")
            } else {
                failed.add(pkg)
            }
        }

        ctx.config.hiddenPackages = ctx.config.hiddenPackages + hidden

        when {
            hidden.isNotEmpty() && failed.isEmpty() -> StepResult.Ok("Hid: ${hidden.joinToString()}")
            hidden.isNotEmpty() -> StepResult.Warn("Hid: ${hidden.joinToString()}; could NOT hide: ${failed.joinToString()}")
            else -> StepResult.Warn("Could not hide: ${failed.joinToString()} (OEM-protected). We remain the default HOME regardless.")
        }
    }

    /** Package of the framework's recents/overview component (Quickstep on most builds). */
    private fun recentsProviderPackage(): String? = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_recentsComponentName", "string", "android")
        if (id == 0) null else ComponentName.unflattenFromString(res.getString(id))?.packageName
    }.getOrNull()
}
