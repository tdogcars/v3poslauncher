package com.flo.v3poslauncher.home

import android.Manifest
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flo.v3poslauncher.admin.AppLockdown
import com.flo.v3poslauncher.admin.DevicePolicy
import com.flo.v3poslauncher.admin.LockTaskManager
import com.flo.v3poslauncher.admin.PinActivity
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningLog
import com.flo.v3poslauncher.ui.DefaultHomePrompt
import com.flo.v3poslauncher.ui.HomeScreen
import com.flo.v3poslauncher.ui.LauncherPrefs
import com.flo.v3poslauncher.ui.SettingsScreen
import com.flo.v3poslauncher.ui.isDefaultLauncher

/**
 * The home screen — the v1 POS Launcher UI (Compose), running inside the Device-Owner app.
 *
 * Provisioning makes this the persistent default HOME, so the v1 "make me default" prompt is
 * not needed. A 2-second hold on empty background goes through the PIN gate ([PinActivity]);
 * on success PinActivity re-launches us with [EXTRA_OPEN_SETTINGS] and the Launcher
 * configuration screen opens. A HOME press always returns to the icon screen.
 */
class HomeActivity : ComponentActivity() {

    // Bumped on every re-delivered intent (singleTask) so the UI reacts to HOME presses and
    // to the PIN gate's "open settings" result.
    private val intentTick = mutableIntStateOf(0)
    private val openSettingsRequested = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grantSelfLocationForSsid()
        selfHeal()
        // Home swallows Back. Registered first so Compose BackHandlers (added later, higher
        // priority) still work — e.g. Back on the configuration screen acts as Done.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* no-op */ }
        })
        openSettingsRequested.value = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        setContent { LauncherApp(intentTick.intValue, openSettingsRequested.value) }
    }

    /**
     * Repair on every launch. A freshly installed app can miss BOOT_COMPLETED (it starts in the
     * stopped state), so boot-time repair alone is not enough: any package hidden by a switch
     * that is now off gets un-hidden here, which is what clears a stock-launcher crash loop.
     * Cheap and idempotent — [AppLockdown.sync] does nothing when there is nothing to change.
     */
    private fun selfHeal() {
        AppLockdown.syncAsync(this)
    }

    override fun onResume() {
        super.onResume()
        enterLockTaskIfConfigured()
    }

    /**
     * Dedicated-terminal mode. Entering lock task from the home screen is what makes Android
     * suppress the large-screen taskbar and its suggested apps; the allowlist (launcher + home
     * apps) and the HOME/OVERVIEW features are set by [LockTaskManager], so staff can still open
     * the allowed apps and use Home / Recents to leave them. Nothing is hidden, so the stock
     * launcher cannot be crashed by this.
     */
    private fun enterLockTaskIfConfigured() {
        val cfg = AppConfig.get(this)
        if (!cfg.lockTaskEnabled) return
        val am = getSystemService(ActivityManager::class.java) ?: return
        if (am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
        if (!LockTaskManager.isAllowed(this)) return
        runCatching { startLockTask() }
            .onFailure { ProvisioningLog.w(this, "HomeActivity: startLockTask failed: ${it.message}") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openSettingsRequested.value = intent.getBooleanExtra(EXTRA_OPEN_SETTINGS, false)
        intentTick.intValue++
    }

    /** Reading the connected SSID on Android 10+ needs fine location; a Device Owner may grant it to itself. */
    private fun grantSelfLocationForSsid() {
        runCatching {
            val dp = DevicePolicy(this)
            if (dp.isDeviceOwner) {
                dp.dpm.setPermissionGrantState(
                    dp.admin, packageName, Manifest.permission.ACCESS_FINE_LOCATION,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_SETTINGS = "com.flo.v3poslauncher.OPEN_SETTINGS"
    }
}

private val LauncherColorScheme = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
)

@Composable
private fun LauncherApp(intentTick: Int, openSettingsRequested: Boolean) {
    val context = LocalContext.current
    val config by remember(context) { LauncherPrefs.configFlow(context) }
        .collectAsStateWithLifecycle(initialValue = null)
    var showSettings by remember { mutableStateOf(false) }

    // Re-checked on every resume so the "make me default" prompt disappears the moment this
    // app becomes the home app (and reappears if that is revoked). In the QR flow
    // provisioning already made us the persistent HOME, so the prompt never shows; it matters
    // for bench installs (adb install) where the technician sets the default by hand.
    var defaultCheckTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        defaultCheckTick++
        onPauseOrDispose { }
    }
    val isDefaultHome = remember(defaultCheckTick) { isDefaultLauncher(context) }
    var promptDismissed by remember { mutableStateOf(false) }
    var autoRequested by remember { mutableStateOf(false) }

    // Every re-delivered intent either opens settings (PIN passed) or lands on the icon
    // screen (HOME press) — even if the device was left sitting in configuration.
    LaunchedEffect(intentTick, openSettingsRequested) { showSettings = openSettingsRequested }

    MaterialTheme(colorScheme = LauncherColorScheme) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val loaded = config ?: return@Box
            when {
                showSettings -> SettingsScreen(
                    config = loaded,
                    onDone = { showSettings = false },
                )

                !isDefaultHome && !promptDismissed -> DefaultHomePrompt(
                    autoRequest = !autoRequested,
                    onAutoRequested = { autoRequested = true },
                    onRecheck = { defaultCheckTick++ },
                    onContinue = { promptDismissed = true },
                )

                else -> HomeScreen(
                    config = loaded,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(context, PinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
        }
    }
}
