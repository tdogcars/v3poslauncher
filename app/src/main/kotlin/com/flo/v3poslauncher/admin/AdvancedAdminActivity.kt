package com.flo.v3poslauncher.admin

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.provisioning.ProvisioningLog
import com.flo.v3poslauncher.provisioning.RevertManager
import com.flo.v3poslauncher.provisioning.StepId
import com.flo.v3poslauncher.provisioning.steps.DisplayPolicyStep
import com.flo.v3poslauncher.util.Ui
import kotlin.concurrent.thread

/**
 * "Advanced device management" — reached from the Launcher configuration screen. Sections:
 *   1. Provisioning — status summary, log viewer, re-run any step.
 *   2. Wi-Fi — the install-site network the launcher keeps saved.
 *   3. Revert — each rollback step individually, and "Undo everything".
 *   4. Security — change PIN.
 *
 * Not exported; only reachable after the PIN gate.
 */
class AdvancedAdminActivity : Activity() {

    private lateinit var cfg: AppConfig
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout
    private var showDiagnostics = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = AppConfig.get(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.PANEL_BG) }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24); setPadding(p, dp(20), p, dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)
        build()
    }

    private fun build() {
        container.removeAllViews()
        title("Advanced device management")
        caption(DevicePolicy(this).describe())

        // ---- Provisioning ----------------------------------------------------------------
        section("Provisioning")
        caption(stepSummary())
        button("View provisioning log") { startActivity(Intent(this, LogViewerActivity::class.java)) }
        button("Re-run ALL provisioning steps") { rerun(StepId.active()) }

        // ---- Dedicated terminal mode -----------------------------------------------------
        section("Dedicated terminal mode (taskbar)")
        caption("Android's supported kiosk mechanism, and the reason no taskbar or suggested apps " +
            "appear. The launcher and the home apps are allowlisted; Home and Recents stay " +
            "enabled so staff can leave any app. Nothing is hidden or disabled, so the stock " +
            "launcher cannot be destabilised. " +
            "Currently: ${if (cfg.lockTaskEnabled) "ON" else "OFF"}. " +
            "Allowlist: ${LockTaskManager.allowlist(this).joinToString()}")
        val lockTaskStatus = caption("")
        button(if (cfg.lockTaskEnabled) "Dedicated terminal mode is ON — turn off" else "Dedicated terminal mode is OFF — turn on") {
            cfg.lockTaskEnabled = !cfg.lockTaskEnabled
            ProvisioningLog.i(this, "Admin: lockTaskEnabled=${cfg.lockTaskEnabled}")
            applyLockTask(lockTaskStatus)
        }

        // ---- Screen saver ----------------------------------------------------------------
        section("Screen saver")
        val onDevice = Screensaver.isOnDevice(this)
        val state = when {
            onDevice -> "ON, starts after ${cfg.screensaverIdleMinutes} min idle"
            cfg.screensaverEnabled -> "wanted, but switched OFF on this terminal"
            else -> "off (not wanted)"
        }
        caption("Stops an always-powered panel from showing the same pixels all day. " +
            "While it is on, the display is allowed to sleep; while it is not, the terminal keeps " +
            "the stay-on-forever policy — which is why a dream never starts on a plugged-in " +
            "terminal until this is on.\nCurrently: $state.\n" + Screensaver.describeDevice(this) + "\n" +
            (if (Screensaver.canWrite(this)) "The launcher can set this itself on this device."
            else "The launcher cannot switch it on by itself — use the button below, or grant it " +
                "once over USB to make it automatic:\n${Screensaver.GRANT_COMMAND}"))
        button("Open Android screen saver settings") { Screensaver.openSettings(this) }
        val screensaverStatus = caption("")
        button(if (cfg.screensaverEnabled) "Screen saver is ON — turn off" else "Screen saver is OFF — turn on") {
            cfg.screensaverEnabled = !cfg.screensaverEnabled
            ProvisioningLog.i(this, "Admin: screensaverEnabled=${cfg.screensaverEnabled}")
            applyScreensaver(screensaverStatus)
        }
        val idleField = field("Idle minutes before it starts", cfg.screensaverIdleMinutes.toString(), numeric = true)
        button("Save idle delay and re-apply") {
            val minutes = idleField.text.toString().trim().toIntOrNull()
            if (minutes == null || minutes < 1 || minutes > 120) { toast("Enter 1–120 minutes"); return@button }
            cfg.screensaverIdleMinutes = minutes
            ProvisioningLog.i(this, "Admin: screensaverIdleMinutes=$minutes")
            applyScreensaver(screensaverStatus)
        }

        // ---- Wi-Fi -----------------------------------------------------------------------
        section("Install-site Wi-Fi")
        caption("The network the launcher keeps saved and re-asserts at boot.")
        val ssidField = field("SSID", cfg.wifiSsid)
        val pskField = field("Password", "", password = true, hint = "leave blank to keep current")
        button("Save Wi-Fi") {
            val s = ssidField.text.toString().trim()
            if (s.isEmpty()) { toast("SSID required"); return@button }
            cfg.wifiSsid = s
            val p = pskField.text.toString()
            if (p.isNotEmpty()) cfg.wifiPassword = p
            pskField.text.clear()
            ProvisioningLog.i(this, "Admin: Wi-Fi SSID set to '$s'${if (p.isNotEmpty()) " (password updated)" else ""}")
            toast("Saved — re-run the Wi-Fi step to apply")
        }

        // ---- Security --------------------------------------------------------------------
        section("Security")
        val newPin = field("New 4-digit PIN", "", numeric = true, password = true)
        button("Change admin PIN") {
            val p = newPin.text.toString()
            if (p.length == 4 && p.all { it.isDigit() }) {
                cfg.setPin(p); newPin.text.clear(); toast("PIN changed")
                ProvisioningLog.i(this, "Admin: PIN changed")
            } else toast("PIN must be exactly 4 digits")
        }

        // ---- Revert ----------------------------------------------------------------------
        section("Revert")
        caption("Runs every rollback step in order and finally releases Device Owner, after which " +
            "this app can be uninstalled normally — no factory reset. Individual steps live under " +
            "Diagnostics.")
        val revertStatus = caption("")
        dangerButton("⚠ Undo everything → stock device") {
            confirm("Undo everything?",
                "Restore hidden apps → clear home lock → restore display policy → release Device " +
                    "Owner. The device returns to stock behavior and this app becomes uninstallable.") {
                undoEverything(revertStatus)
            }
        }

        // ---- Diagnostics -----------------------------------------------------------------
        section("")
        button(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics") {
            showDiagnostics = !showDiagnostics
            build()
        }
        if (showDiagnostics) diagnostics(lockTaskStatus, revertStatus)

        section("")
        button("Back to Launcher configuration") { finish() }
    }

    /**
     * Field-repair tools, hidden by default. On a live terminal these are noise — most only matter
     * when one specific step has failed, and the per-piece revert ladder is a way to leave a device
     * half-reverted if it is tapped without understanding. Nothing here is lost, just folded away.
     */
    private fun diagnostics(lockTaskStatus: TextView, revertStatus: TextView) {
        section("Diagnostics — re-run a single step")
        StepId.active().forEach { id -> button("• ${id.title}") { rerun(listOf(id)) } }

        section("Diagnostics — dedicated terminal")
        button("Re-apply lock task allowlist now") { applyLockTask(lockTaskStatus) }

        section("Diagnostics — revert one piece at a time")
        button("Leave dedicated terminal mode") { runRevert(revertStatus) { RevertManager(this).clearLockTask() } }
        button("Restore stock launcher (if an old build hid it)") { runRevert(revertStatus) { RevertManager(this).unhideStockLauncher() } }
        button("Restore hidden apps and suggestions") { runRevert(revertStatus) { RevertManager(this).unhideOtherApps() } }
        button("Clear default-home lock") { runRevert(revertStatus) { RevertManager(this).clearPersistentHome() } }
        button("Restore display policy / screen timeout") { runRevert(revertStatus) { RevertManager(this).restoreDisplayTimeout() } }
        button("Forget install-site Wi-Fi") { runRevert(revertStatus) { RevertManager(this).forgetWifi() } }
        button("Release Device Owner") {
            confirm("Release Device Owner?",
                "Do the other revert steps first, or use \"Undo everything\".") {
                runRevert(revertStatus) { RevertManager(this).clearDeviceOwner() }
            }
        }
    }

    // ---- actions -------------------------------------------------------------------------

    private fun applyLockTask(status: TextView) {
        status.setTextColor(Ui.ACCENT); status.text = "Applying…"
        thread(name = "admin-locktask") {
            val r = runCatching {
                if (cfg.lockTaskEnabled) LockTaskManager.apply(this).joinToString()
                else LockTaskManager.clear(this).second
            }
            main.post {
                r.onSuccess { detail ->
                    status.setTextColor(Ui.OK)
                    status.text = if (cfg.lockTaskEnabled) "Allowlist applied: $detail. Press Home on the terminal to enter." else detail
                }.onFailure { status.setTextColor(Ui.ERR); status.text = "Failed: ${it.message}" }
                build()
            }
        }
    }

    private fun applyScreensaver(status: TextView) {
        status.setTextColor(Ui.ACCENT); status.text = "Applying…"
        thread(name = "admin-screensaver") {
            val outcome = runCatching {
                val r = Screensaver.apply(this)
                // The stay-on policy is the other half of this decision — re-apply it together,
                // or the screen saver is configured but can never actually start.
                val dp = DevicePolicy(this)
                if (dp.isDeviceOwner && cfg.displayPolicyApplied) DisplayPolicyStep.apply(this, dp, cfg)
                r
            }
            main.post {
                // build() clears the status view, so the outcome goes to a toast.
                outcome.onSuccess { toast(it.message) }.onFailure { toast("Failed: ${it.message}") }
                build()
            }
        }
    }

    private fun rerun(ids: List<StepId>) {
        startActivity(
            Intent(this, com.flo.v3poslauncher.provisioning.ProvisioningActivity::class.java)
                .setAction("com.flo.v3poslauncher.ACTION_RERUN")
                .putExtra("steps", ids.map { it.name }.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun runRevert(status: TextView, action: () -> RevertManager.StepOutcome) {
        status.setTextColor(Ui.ACCENT); status.text = "Working…"
        thread(name = "admin-revert") {
            val o = action()
            main.post {
                status.text = "${o.name}: ${if (o.ok) "OK" else "FAILED"} — ${o.detail}"
                status.setTextColor(if (o.ok) Ui.OK else Ui.ERR)
            }
        }
    }

    private fun undoEverything(status: TextView) {
        status.setTextColor(Ui.ACCENT); status.text = "Reverting…"
        thread(name = "admin-undo-all") {
            val sb = StringBuilder()
            RevertManager(this).undoEverything { o ->
                sb.append(if (o.ok) "✓ " else "✕ ").append(o.name).append(": ").append(o.detail).append('\n')
                main.post { status.text = sb.toString() }
            }
        }
    }

    private fun stepSummary(): String =
        StepId.active().joinToString("\n") { "• ${it.title}: ${cfg.getStepStatus(it.name)}" }

    // ---- tiny view DSL -------------------------------------------------------------------

    private fun title(t: String) = container.addView(TextView(this).apply {
        text = t; setTextColor(Ui.TEXT); textSize = 22f; setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, 0, 0, dp(8))
    })

    private fun section(t: String) = container.addView(TextView(this).apply {
        text = t; setTextColor(Ui.ACCENT); textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(22), 0, dp(6))
    })

    private fun caption(t: String): TextView = TextView(this).apply {
        text = t; setTextColor(Ui.TEXT_DIM); textSize = 12f
        setPadding(0, dp(2), 0, dp(6))
    }.also { container.addView(it) }

    private fun field(label: String, value: String, numeric: Boolean = false, password: Boolean = false, hint: String = ""): EditText {
        container.addView(TextView(this).apply {
            text = label; setTextColor(Color.parseColor("#C8C8C8")); textSize = 12f; setPadding(0, dp(8), 0, dp(2))
        })
        val e = EditText(this).apply {
            setText(value); setTextColor(Ui.TEXT); textSize = 14f
            if (hint.isNotEmpty()) this.hint = hint
            setHintTextColor(Ui.TEXT_FAINT)
            background = Ui.rounded(Ui.CARD_ALT, 8, this@AdvancedAdminActivity)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = when {
                password && numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        container.addView(e)
        return e
    }

    private fun button(label: String, onClick: () -> Unit) = container.addView(Button(this).apply {
        text = label; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
    })

    private fun dangerButton(label: String, onClick: () -> Unit) = container.addView(Button(this).apply {
        text = label; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#5A1A17"))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
    })

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = Ui.dp(this, v)
}
