package com.flo.v3poslauncher.provisioning.steps

import com.flo.v3poslauncher.admin.Screensaver
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Step — turn on the stock Android screen saver so an always-powered countertop panel is not
 * displaying the same pixels all day.
 *
 * This step can only WARN, never FAIL: the settings need WRITE_SECURE_SETTINGS (see [Screensaver]),
 * which the QR flow cannot provide, and a missing screen saver must never stop a terminal from
 * being provisioned. When it warns, the message carries the exact adb command that finishes it,
 * and the launcher re-applies itself on every start once that grant exists.
 */
class ScreensaverStep : ProvisioningStep {
    override val id = StepId.SCREENSAVER

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Screen saver") {
        val result = Screensaver.apply(ctx.context)
        ctx.log("Screensaver: ${result.message}")
        if (result.applied || !ctx.config.screensaverEnabled) StepResult.Ok(result.message)
        else StepResult.Warn(result.message)
    }
}
