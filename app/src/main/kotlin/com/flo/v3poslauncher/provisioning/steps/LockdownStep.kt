package com.flo.v3poslauncher.provisioning.steps

import com.flo.v3poslauncher.admin.AppLockdown
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Step 6 — hide every launchable app that is not on the home-app list, and hide the system
 * app-prediction service so the taskbar has no "suggested apps". Quickstep stays, so Back /
 * Home / Recents keep working. See [AppLockdown].
 */
class LockdownStep : ProvisioningStep {
    override val id = StepId.LOCKDOWN

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Hide non-allowed apps") {
        ctx.dp.requireDeviceOwner()
        if (!ctx.config.lockdownApps && !ctx.config.disableAppSuggestions) {
            return@guarded StepResult.Ok("Skipped: hideOtherApps=false and disableAppSuggestions=false.")
        }
        ctx.progress("Hiding apps not on the allowed list…")
        val o = AppLockdown.sync(ctx.context) { ctx.log(it) }
        val total = ctx.config.hiddenOtherApps.size
        val sugg = when {
            !ctx.config.disableAppSuggestions -> "suggestions left on"
            o.predictionPkg == null -> "no prediction service on this build"
            ctx.config.hiddenPredictionPackage.isNotEmpty() -> "suggestions off (${o.predictionPkg})"
            else -> "could not hide prediction service ${o.predictionPkg}"
        }
        when {
            o.failed.isEmpty() -> StepResult.Ok("$total app(s) hidden; ${o.hidden.size} new. ${sugg.replaceFirstChar { it.uppercase() }}.")
            else -> StepResult.Warn("$total app(s) hidden; could not hide: ${o.failed.joinToString()}. ${sugg.replaceFirstChar { it.uppercase() }}.")
        }
    }
}
