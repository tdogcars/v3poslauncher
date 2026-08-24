package com.flo.v3poslauncher.provisioning.steps

import com.flo.v3poslauncher.home.AppLauncher
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Final step — verify the configured home apps (Chrome, Settings) are actually present on this
 * device, since nothing is installed by the launcher. A missing app is a WARN, not a FAIL: the
 * terminal still boots to the home grid, the missing tile is shown as unavailable, and the app
 * list is editable from the admin panel. (Common on AOSP-based POS images that ship without
 * Chrome — the log names exactly what is missing.)
 */
class AppsStep : ProvisioningStep {
    override val id = StepId.APPS

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Verify home apps") {
        val apps = ctx.config.homeApps
        if (apps.isEmpty()) return@guarded StepResult.Warn("No home apps configured.")

        val present = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (pkg in apps) {
            val entry = AppLauncher.resolve(ctx.context, pkg)
            if (entry.installed) present.add("${entry.label} (${entry.resolvedPackage})")
            else missing.add(pkg)
        }
        ctx.log("AppsStep: present=${present.joinToString()} missing=${missing.joinToString()}")

        when {
            missing.isEmpty() -> StepResult.Ok("Home apps ready: ${present.joinToString()}")
            present.isEmpty() -> StepResult.Warn("None of the configured apps are installed: ${missing.joinToString()}. Edit the list in the admin panel.")
            else -> StepResult.Warn("Ready: ${present.joinToString()}. NOT installed: ${missing.joinToString()} (shown as unavailable; edit in admin panel).")
        }
    }
}
