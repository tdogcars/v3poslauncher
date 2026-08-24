package com.flo.v3poslauncher.provisioning.steps

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded

/**
 * Step 1 — become the persistent default HOME so no chooser dialog ever appears.
 *
 * addPersistentPreferredActivity() installs a system-level preferred-activity mapping that
 * only a Device Owner / Profile Owner can set, and that the "always/just once" chooser
 * cannot override. This is the correct API; setting it does not require also disabling the
 * stock launcher (that is step 2, and is about hiding its taskbar/drawer).
 */
class HomeStep : ProvisioningStep {
    override val id = StepId.HOME

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Set default HOME") {
        ctx.dp.requireDeviceOwner()
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val home: ComponentName = ctx.dp.homeComponent()
        ctx.dp.dpm.addPersistentPreferredActivity(ctx.dp.admin, filter, home)
        ctx.config.persistentHomeApplied = true

        // Verify the system now resolves HOME to us.
        val resolved = ctx.dp.currentDefaultHomePackage()
        if (resolved == ctx.dp.selfPackage) {
            StepResult.Ok("Default home is now ${home.className.substringAfterLast('.')}")
        } else {
            // Mapping is set but resolution may lag until next HOME press; treat as WARN not FAIL.
            StepResult.Warn("Persistent HOME set; system currently resolves HOME to '$resolved' (will switch to us on next Home).")
        }
    }
}
