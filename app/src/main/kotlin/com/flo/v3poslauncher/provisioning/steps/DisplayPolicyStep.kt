package com.flo.v3poslauncher.provisioning.steps

import android.content.Context
import android.provider.Settings
import com.flo.v3poslauncher.admin.DevicePolicy
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Display policy for a countertop terminal. Two mutually exclusive shapes, chosen by whether the
 * screen saver actually got configured on THIS device:
 *
 *  - Screen saver active: STAY_ON_WHILE_PLUGGED_IN = 0 and SCREEN_OFF_TIMEOUT = the idle delay.
 *    This matters more than it looks. A dream only starts when the device would otherwise nap, and
 *    STAY_ON_WHILE_PLUGGED_IN stops it napping. An always-plugged-in POS with stay-on set therefore
 *    NEVER shows a screen saver, however the toggle in Settings reads — which is why turning it on
 *    by hand on a provisioned unit appeared to do nothing.
 *
 *  - Otherwise (the historical behavior): stay on while powered, maximum screen timeout.
 *
 * Keying off [AppConfig.screensaverActive] rather than [AppConfig.screensaverEnabled] is deliberate
 * and is the fail-safe: without the WRITE_SECURE_SETTINGS grant there is no dream to show, so
 * releasing stay-on would just give staff a black panel that looks like dead hardware.
 *
 * Both settings are written through DevicePolicyManager (setGlobalSetting / setSystemSetting), the
 * only app-legal route; both keys are on the Device-Owner-permitted allowlist. Pre-change values are
 * captured once so revert restores them exactly.
 */
class DisplayPolicyStep : ProvisioningStep {
    override val id = StepId.DISPLAY

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Apply display policy") {
        ctx.dp.requireDeviceOwner()
        val message = apply(ctx.context, ctx.dp, ctx.config)
        ctx.config.displayPolicyApplied = true
        StepResult.Ok(message)
    }

    companion object {
        private const val STAY_ON_ALL = 7 // BATTERY_PLUGGED_AC(1) | USB(2) | WIRELESS(4)
        private const val MAX_TIMEOUT = Int.MAX_VALUE

        /** Shared with [com.flo.v3poslauncher.admin.Screensaver] so a late grant re-applies both halves. */
        fun apply(context: Context, dp: DevicePolicy, cfg: AppConfig): String {
            val cr = context.contentResolver

            // Capture originals once (don't overwrite on re-run).
            if (cfg.originalStayOnWhilePluggedIn == -1) {
                cfg.originalStayOnWhilePluggedIn =
                    Settings.Global.getInt(cr, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)
            }
            if (cfg.originalScreenOffTimeout == -1) {
                cfg.originalScreenOffTimeout =
                    Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, 60_000)
            }

            return if (cfg.screensaverActive) {
                val idleMs = cfg.screensaverIdleMinutes * 60_000
                dp.dpm.setGlobalSetting(dp.admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")
                dp.dpm.setSystemSetting(dp.admin, Settings.System.SCREEN_OFF_TIMEOUT, idleMs.toString())
                "Screen saver after ${cfg.screensaverIdleMinutes} min idle (stay-on released so the dream can start)."
            } else {
                dp.dpm.setGlobalSetting(dp.admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_ALL.toString())
                dp.dpm.setSystemSetting(dp.admin, Settings.System.SCREEN_OFF_TIMEOUT, MAX_TIMEOUT.toString())
                "Stay-on while powered + no screen timeout applied."
            }
        }
    }
}
