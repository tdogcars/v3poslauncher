package com.flo.v3poslauncher.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.flo.v3poslauncher.config.AdminExtras

/**
 * Android 11+ (API 30+) ONLY. The setup wizard launches this before setting Device Owner, asking
 * which provisioning mode the DPC wants. We answer PROVISIONING_MODE_FULLY_MANAGED_DEVICE and
 * pass the admin-extras bundle straight back so it survives into onProfileProvisioningComplete /
 * the compliance activity. We also skip the DPC "education" screens for a hands-off flow.
 *
 * This activity never shows UI: it reads the incoming intent, sets a result, and finishes.
 */
class GetProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Capture the extras bundle as early as possible.
        AdminExtras.applyFromIntent(this, intent, "GET_PROVISIONING_MODE")

        val result = Intent().apply {
            if (Build.VERSION.SDK_INT >= 30) {
                putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                )
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_EDUCATION_SCREENS, true)
                // Re-attach the admin extras so downstream callbacks receive them.
                intent.getParcelableExtra<android.os.PersistableBundle>(
                    DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                )?.let { putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, it) }
            }
        }
        ProvisioningLog.i(this, "GetProvisioningModeActivity: answering FULLY_MANAGED_DEVICE (api=${Build.VERSION.SDK_INT})")
        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
