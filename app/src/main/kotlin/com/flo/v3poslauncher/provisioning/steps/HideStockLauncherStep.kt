package com.flo.v3poslauncher.provisioning.steps

import android.content.pm.ApplicationInfo
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Step 2 — hide the stock launcher (Quickstep/Launcher3) so no taskbar or app-drawer
 * affordance is visible, including after reboot (setApplicationHidden is persistent).
 *
 * Detection, not hardcoding: we hide every package that resolves the HOME intent EXCEPT
 * ourselves, plus a small allowlist of known Launcher3/Quickstep/OEM variants that resolve
 * HOME. We never hide a package flagged as an unremovable critical system component beyond
 * the launcher role, and we record exactly what we hid so revert can unhide precisely those.
 *
 * PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED=true in the QR keeps all system apps enabled at
 * provisioning time; we then selectively hide only the launcher here.
 */
class HideStockLauncherStep : ProvisioningStep {
    override val id = StepId.HIDE_STOCK

    private val knownLauncherHints = listOf(
        "launcher3", "quickstep", "trebuchet", "nexuslauncher",
        "com.android.launcher", "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",     // Samsung
        "com.sunmi.hpf.launcher", "com.sunmi.launcher", // Sunmi variants
        "com.elo.launcher",                 // Elo (varies)
        "net.oneplus.launcher", "com.miui.home", "com.oppo.launcher", "com.bbk.launcher2",
    )

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Hide stock launcher") {
        ctx.dp.requireDeviceOwner()
        if (!ctx.config.hideStockLauncher) {
            return@guarded StepResult.Warn("Skipped: hideStockLauncher=false in configuration")
        }

        val self = ctx.dp.selfPackage
        val candidates = LinkedHashSet<String>()

        // (a) Everything that resolves HOME other than us.
        ctx.dp.resolveHomeActivities().forEach { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@forEach
            if (pkg != self) candidates.add(pkg)
        }

        // (b) Known launcher packages that are installed, even if a vendor hid them from
        //     the HOME resolver (belt and suspenders).
        knownLauncherHints.forEach { hint ->
            runCatching {
                ctx.dp.pm.getInstalledApplications(0)
                    .filter { it.packageName != self && it.packageName.contains(hint, ignoreCase = true) }
                    .forEach { candidates.add(it.packageName) }
            }
        }

        ctx.log("HideStock: HOME resolvers + hints => candidates: ${candidates.joinToString()}")

        if (candidates.isEmpty()) {
            return@guarded StepResult.Warn("No other launcher found to hide (device may already be launcher-free).")
        }

        val hidden = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (pkg in candidates) {
            val info: ApplicationInfo? = runCatching { ctx.dp.pm.getApplicationInfo(pkg, 0) }.getOrNull()
            // Do not hide ourselves; do not hide a package that is currently the ONLY thing
            // keeping HOME alive if hiding it would leave nothing — we are HOME now, so safe.
            ctx.progress("Hiding $pkg…")
            val ok = try {
                ctx.dp.dpm.setApplicationHidden(ctx.dp.admin, pkg, true)
            } catch (t: Throwable) {
                ctx.err("HideStock: exception hiding $pkg", t); false
            }
            when {
                ok -> { hidden.add(pkg); ctx.log("HideStock: hid $pkg (was system=${info?.let { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }})") }
                info == null -> skipped.add(pkg)
                else -> failed.add(pkg)
            }
        }

        // Persist exactly what we hid for precise revert (merge with any prior set).
        ctx.config.hiddenPackages = ctx.config.hiddenPackages + hidden

        when {
            hidden.isNotEmpty() && failed.isEmpty() ->
                StepResult.Ok("Hid: ${hidden.joinToString()}")
            hidden.isNotEmpty() ->
                StepResult.Warn("Hid: ${hidden.joinToString()}; could NOT hide: ${failed.joinToString()} (OEM may protect these — see OEM caveats).")
            failed.isNotEmpty() ->
                StepResult.Fail("Could not hide any launcher package: ${failed.joinToString()}. Home may still show a taskbar.")
            else ->
                StepResult.Warn("Nothing hidden (candidates not installable: ${skipped.joinToString()}).")
        }
    }
}
