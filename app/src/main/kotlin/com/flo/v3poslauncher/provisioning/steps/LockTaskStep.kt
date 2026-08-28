package com.flo.v3poslauncher.provisioning.steps

import com.flo.v3poslauncher.admin.LockTaskManager
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Step 6 — dedicated-terminal mode. Allowlists the launcher plus the configured home apps for
 * lock task and enables the Home / Recents / notifications features, which is what makes Android
 * suppress the large-screen taskbar and its suggested apps. The launcher enters lock task on the
 * home screen; nothing is hidden or disabled, so nothing can crash the stock launcher.
 */
class LockTaskStep : ProvisioningStep {
    override val id = StepId.LOCK_TASK

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Dedicated terminal mode") {
        ctx.dp.requireDeviceOwner()
        if (!ctx.config.lockTaskEnabled) {
            return@guarded StepResult.Ok("Skipped: dedicated terminal mode is off (taskbar behaves as stock).")
        }
        ctx.progress("Allowlisting apps for dedicated terminal mode…")
        val list = LockTaskManager.apply(ctx.context) { ctx.log(it) }
        StepResult.Ok("Allowed: ${list.joinToString()}. Home + Recents stay available; taskbar suppressed.")
    }
}
