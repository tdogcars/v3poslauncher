package com.flo.v3poslauncher.provisioning.steps

import android.provider.Settings
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Step 5 — keep the screen on for a countertop terminal.
 *
 *  - Global STAY_ON_WHILE_PLUGGED_IN = AC|USB|WIRELESS (7): screen never sleeps while powered,
 *    which an all-in-one POS always is.
 *  - System SCREEN_OFF_TIMEOUT set to the maximum so it never times out even on battery.
 *
 * Both are set through DevicePolicyManager (setGlobalSetting / setSystemSetting), the only
 * app-legal way to write these; both are on the Device-Owner-permitted settings allowlist.
 * The pre-change values are captured so revert can restore them exactly.
 *
 * We intentionally do NOT hold a FLAG_KEEP_SCREEN_ON wake lock in the launcher: the POS app is
 * in the foreground almost all the time, and these system settings keep the display on across
 * both the launcher and the POS without a background wake lock.
 */
class DisplayPolicyStep : ProvisioningStep {
    override val id = StepId.DISPLAY

    private val STAY_ON_ALL = 7 // BATTERY_PLUGGED_AC(1) | USB(2) | WIRELESS(4)
    private val MAX_TIMEOUT = Int.MAX_VALUE

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Apply display policy") {
        ctx.dp.requireDeviceOwner()
        val cr = ctx.context.contentResolver

        // Capture originals once (don't overwrite on re-run).
        if (ctx.config.originalStayOnWhilePluggedIn == -1) {
            ctx.config.originalStayOnWhilePluggedIn =
                Settings.Global.getInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
        }
        if (ctx.config.originalScreenOffTimeout == -1) {
            ctx.config.originalScreenOffTimeout =
                Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
        }

        ctx.dp.dpm.setGlobalSetting(ctx.dp.admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_ALL.toString())
        ctx.dp.dpm.setSystemSetting(ctx.dp.admin, Settings.System.SCREEN_OFF_TIMEOUT, MAX_TIMEOUT.toString())
        ctx.config.displayPolicyApplied = true

        StepResult.Ok("Stay-on while powered + no screen timeout applied.")
    }
}
