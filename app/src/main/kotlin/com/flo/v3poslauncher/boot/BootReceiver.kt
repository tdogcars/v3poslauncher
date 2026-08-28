package com.flo.v3poslauncher.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.flo.v3poslauncher.admin.AppLockdown
import com.flo.v3poslauncher.admin.DevicePolicy
import com.flo.v3poslauncher.admin.LockTaskManager
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningLog
import com.flo.v3poslauncher.wifi.WifiProvisioner
import kotlin.concurrent.thread

/**
 * On every boot, once provisioning has completed, re-assert the FLO Secure network (self-heal if
 * it was forgotten) and the display policy (some OEMs reset system settings on update). The
 * device then comes up on our Home (the app grid) on its own — nothing is auto-launched.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = AppConfig.get(context)

        // Self-heal FIRST, before the provisioningCompleted gate: if an earlier build (or a
        // lab-only switch) hid packages and the switches are now off, un-hiding them is what
        // stops the stock launcher crash-looping. This must never be gated behind a flag that
        // only a full provisioning run sets.
        runCatching {
            if (DevicePolicy(context).isDeviceOwner) {
                AppLockdown.sync(context)
                // The allowlist must be re-applied here too, not only after a full provisioning
                // run: without it startLockTask() is refused and dedicated terminal mode is off.
                if (cfg.lockTaskEnabled) LockTaskManager.apply(context)
            }
        }.onFailure { ProvisioningLog.w(context, "BootReceiver: self-heal failed: ${it.message}") }

        if (!cfg.provisioningCompleted) {
            ProvisioningLog.i(context, "BootReceiver: provisioning not complete; nothing to re-assert")
            return
        }
        ProvisioningLog.i(context, "BootReceiver: boot completed; re-asserting policy")

        val pending = goAsync()
        thread(name = "boot-reassert") {
            try {
                val dp = DevicePolicy(context)
                if (dp.isDeviceOwner) {
                    val w = WifiProvisioner(context).ensureNetwork(cfg.wifiSsid, cfg.wifiPassword)
                    ProvisioningLog.i(context, "BootReceiver: wifi -> ${w.message}")
                    if (cfg.lockTaskEnabled) {
                        runCatching { LockTaskManager.apply(context) }
                            .onFailure { ProvisioningLog.w(context, "BootReceiver: lock task re-apply failed: ${it.message}") }
                    }
                    runCatching { AppLockdown.sync(context) }
                        .onFailure { ProvisioningLog.w(context, "BootReceiver: app lockdown re-sync failed: ${it.message}") }
                    if (cfg.displayPolicyApplied) {
                        runCatching {
                            dp.dpm.setGlobalSetting(dp.admin, android.provider.Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7")
                            dp.dpm.setSystemSetting(dp.admin, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, Int.MAX_VALUE.toString())
                        }.onFailure { ProvisioningLog.w(context, "BootReceiver: display re-assert failed: ${it.message}") }
                    }
                } else {
                    ProvisioningLog.w(context, "BootReceiver: not Device Owner at boot")
                }
            } catch (t: Throwable) {
                ProvisioningLog.e(context, "BootReceiver: re-assert failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
