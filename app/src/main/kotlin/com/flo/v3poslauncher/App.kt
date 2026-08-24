package com.flo.v3poslauncher

import android.app.Application
import com.flo.v3poslauncher.provisioning.ProvisioningRunner

/**
 * Process-scoped singletons. The provisioning runner must survive the status activity being
 * recreated. There is no POS watchdog in this build — the launcher is a plain app-grid home,
 * not a single-app kiosk, so there is nothing to keep in the foreground.
 */
class App : Application() {
    lateinit var provisioningRunner: ProvisioningRunner
        private set

    override fun onCreate() {
        super.onCreate()
        provisioningRunner = ProvisioningRunner(this)
    }
}
