package com.flo.v3poslauncher.provisioning.steps

import com.flo.v3poslauncher.admin.AppRemover
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Removes the curated set of pre-installed apps, and optionally the app-prediction service that
 * feeds the taskbar's suggested apps. See [AppRemover] for why this is safe where the retired
 * sweep was not, and for the guards that refuse a bad list.
 *
 * WARN rather than FAIL on a partial result: a terminal that kept one unwanted icon is a cosmetic
 * problem, and must never block provisioning.
 */
class RemoveAppsStep : ProvisioningStep {
    override val id = StepId.REMOVE_APPS

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Remove pre-installed apps") {
        ctx.dp.requireDeviceOwner()
        if (!ctx.config.removeBloat && !ctx.config.disablePredictions) {
            return@guarded StepResult.Ok("Nothing to remove (removeBloat and disablePredictions are both off).")
        }
        ctx.progress("Removing pre-installed apps…")
        val outcome = AppRemover.apply(ctx.context) { ctx.log(it) }
        when {
            outcome.failed.isNotEmpty() ->
                StepResult.Warn("Some apps could not be removed (OEM-protected): ${outcome.failed.joinToString()}. ${outcome.summary}")
            else -> StepResult.Ok(outcome.summary)
        }
    }
}
