package com.flo.v3poslauncher.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.flo.v3poslauncher.config.AdminExtras
import com.flo.v3poslauncher.provisioning.ProvisioningLog

/**
 * Device-admin receiver. The component name of this class is what the QR's
 * PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME must point at:
 *
 *     com.flo.v3poslauncher/com.flo.v3poslauncher.admin.PosDeviceAdminReceiver
 *
 * Which callback actually kicks off provisioning, by API level:
 *
 *  API 30+ (Android 11+): the setup wizard first launches ACTION_GET_PROVISIONING_MODE
 *      (GetProvisioningModeActivity), sets us as Device Owner, sends
 *      PROFILE_PROVISIONING_COMPLETE (here), then launches ACTION_ADMIN_POLICY_COMPLIANCE
 *      (ProvisioningActivity) and waits for it to finish. The compliance activity is where
 *      the visible provisioning sequence runs.
 *
 *  API 26–29 (Android 8.0–10): PROFILE_PROVISIONING_COMPLETE (here), then the system launches
 *      ACTION_PROVISIONING_SUCCESSFUL (also ProvisioningActivity) once the wizard is done.
 *
 *  Below API 26 the DPC had to start its own UI from this broadcast; we don't support that
 *  (minSdk 29).
 *
 * This receiver therefore only persists the admin extras bundle and logs. It never starts an
 * activity itself: starting activities from a broadcast is restricted on Android 10+, and on
 * every supported level the system launches our activity for us anyway.
 */
class PosDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        ProvisioningLog.i(context, "DeviceAdminReceiver.onEnabled (api=${Build.VERSION.SDK_INT})")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        ProvisioningLog.i(context, "DeviceAdminReceiver.onProfileProvisioningComplete (api=${Build.VERSION.SDK_INT})")
        AdminExtras.applyFromIntent(context, intent, "PROFILE_PROVISIONING_COMPLETE")
        val dp = DevicePolicy(context)
        ProvisioningLog.i(context, "State after DO grant: ${dp.describe()}")
        if (!dp.isDeviceOwner) {
            ProvisioningLog.e(context, "Provisioning completed but we are NOT device owner — was this a work-profile flow?")
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        ProvisioningLog.w(context, "DeviceAdminReceiver.onDisabled — admin removed (expected only after 'Undo everything')")
    }

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context.applicationContext, PosDeviceAdminReceiver::class.java)
    }
}
