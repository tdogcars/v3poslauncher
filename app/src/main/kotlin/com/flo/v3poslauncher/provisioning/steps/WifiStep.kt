package com.flo.v3poslauncher.provisioning.steps

import com.flo.v3poslauncher.provisioning.ProvisioningStep
import com.flo.v3poslauncher.provisioning.StepContext
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.StepResult
import com.flo.v3poslauncher.provisioning.guarded
import com.flo.v3poslauncher.wifi.WifiProvisioner

/**
 * Step 4 — ensure the FLO Secure network is saved, regardless of which network the device was
 * provisioned on. Re-run on every boot (BootReceiver) so a "forgotten" network self-heals.
 *
 * The password is never surfaced: only the SSID appears in any message or log.
 */
class WifiStep : ProvisioningStep {
    override val id = StepId.WIFI

    override fun run(ctx: StepContext): StepResult = guarded(ctx, "Save Wi-Fi") {
        ctx.dp.requireDeviceOwner()
        val ssid = ctx.config.wifiSsid
        if (ctx.config.wifiPassword.isEmpty()) {
            return@guarded StepResult.Fail(
                "No Wi-Fi password available. It must come from the QR (wifiPassword) or the " +
                    "WIFI_PASSWORD build secret. Set it in the admin panel, or re-provision with a QR " +
                    "that includes it.",
            )
        }
        ctx.progress("Saving network '$ssid'…")
        when (val r = WifiProvisioner(ctx.context).ensureNetwork(ssid, ctx.config.wifiPassword)) {
            is WifiProvisioner.Result.Added -> StepResult.Ok(r.message)
            is WifiProvisioner.Result.AlreadyPresent -> StepResult.Ok(r.message)
            is WifiProvisioner.Result.Failure -> StepResult.Fail(r.message)
        }
    }
}
