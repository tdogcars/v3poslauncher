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
import com.flo.v3poslauncher.home.AppLauncher
import com.flo.v3poslauncher.provisioning.ProvisioningLog
import com.flo.v3poslauncher.provisioning.RevertManager
import com.flo.v3poslauncher.provisioning.StepId
import kotlin.concurrent.thread

/**
 * PIN-gated admin panel. Reachable only through [PinActivity]. Sections:
 *   1. Home apps — add/remove the apps shown on the launcher, set icon size.
 *   2. Provisioning — status summary, log viewer, re-run any step.
 *   3. Revert — each rollback step individually, and "Undo everything".
 *   4. Security — change PIN.
 *
 * Not exported; assumes the caller already passed the PIN.
 */
class AdminPanelActivity : Activity() {

    private lateinit var cfg: AppConfig
    private val main = Handler(Looper.getMainLooper())
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = AppConfig.get(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(20); setPadding(p, dp(28), p, dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)
        build()
    }

    private fun build() {
        container.removeAllViews()
        title("FLO POS Launcher — Admin")
        caption(DevicePolicy(this).describe())

        // ---- 1. Home apps ----------------------------------------------------------------
        section("Home apps")
        caption("Apps shown on the launcher, in order. No apps are installed by the launcher — " +
            "these must already be on the device.")
        cfg.homeApps.forEach { pkg ->
            val entry = AppLauncher.resolve(this, pkg)
            appRow(pkg, entry.label, entry.installed)
        }
        val addField = field("Add app by package name", "", hint = "e.g. com.android.chrome")
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addRow.addView(Button(this).apply {
            text = "Add"
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener {
                val p = addField.text.toString().trim()
                if (p.isEmpty()) { toast("Enter a package name"); return@setOnClickListener }
                cfg.addHomeApp(p); ProvisioningLog.i(this@AdminPanelActivity, "Admin: added home app $p"); build()
            }
        })
        addRow.addView(Button(this).apply {
            text = "Pick installed…"
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { showAppPicker() }
        })
        container.addView(addRow)

        val iconField = field("Home icon size (dp)", cfg.iconSizeDp.toString(), numeric = true)
        button("Save icon size") {
            iconField.text.toString().toIntOrNull()?.let { cfg.iconSizeDp = it; toast("Saved (${cfg.iconSizeDp}dp)") }
                ?: toast("Enter a number")
        }

        // ---- 2. Provisioning -------------------------------------------------------------
        section("Provisioning")
        caption(stepSummary())
        button("View provisioning log") { startActivity(Intent(this, LogViewerActivity::class.java)) }
        button("Re-run ALL provisioning steps") { rerun(StepId.values().toList()) }
        caption("Re-run a single step:")
        StepId.values().forEach { id -> button("• ${id.title}") { rerun(listOf(id)) } }

        // ---- 3. Revert -------------------------------------------------------------------
        section("Revert (rollback)")
        caption("Each action is reversible. \"Undo everything\" runs them in order and finally " +
            "releases Device Owner, after which this app can be uninstalled normally — no factory reset.")
        val revertStatus = caption("")
        button("1. Unhide stock launcher") { runRevert(revertStatus) { RevertManager(this).unhideStockLauncher() } }
        button("2. Clear default-home lock") { runRevert(revertStatus) { RevertManager(this).clearPersistentHome() } }
        button("3. Restore screen timeout") { runRevert(revertStatus) { RevertManager(this).restoreDisplayTimeout() } }
        button("Forget FLO Secure Wi-Fi (optional)") { runRevert(revertStatus) { RevertManager(this).forgetWifi() } }
        button("4. Release Device Owner") {
            confirm("Release Device Owner?",
                "Do the unhide/clear/restore steps first, or use \"Undo everything\".") {
                runRevert(revertStatus) { RevertManager(this).clearDeviceOwner() }
            }
        }
        dangerButton("⚠ Undo everything → stock device") {
            confirm("Undo everything?",
                "Unhide launcher → clear home lock → restore timeout → release Device Owner. " +
                    "The device returns to stock behavior and this app becomes uninstallable.") {
                undoEverything(revertStatus)
            }
        }

        // ---- 4. Security -----------------------------------------------------------------
        section("Security")
        val newPin = field("New 4-digit PIN", "", numeric = true, password = true)
        button("Change admin PIN") {
            val p = newPin.text.toString()
            if (p.length == 4 && p.all { it.isDigit() }) {
                cfg.setPin(p); newPin.text.clear(); toast("PIN changed")
                ProvisioningLog.i(this, "Admin: PIN changed")
            } else toast("PIN must be exactly 4 digits")
        }

        section("")
        button("Close") { goHome() }
    }

    // ---- home-app rows & picker ----------------------------------------------------------

    private fun appRow(pkg: String, label: String, installed: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = if (installed) "$label\n$pkg" else "$label\n$pkg  — NOT INSTALLED"
            setTextColor(if (installed) Color.WHITE else Color.parseColor("#C8A24D"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        row.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                cfg.removeHomeApp(pkg); ProvisioningLog.i(this@AdminPanelActivity, "Admin: removed home app $pkg"); build()
            }
        })
        container.addView(row)
    }

    private fun showAppPicker() {
        val apps = AppLauncher.installedLaunchableApps(this)
        if (apps.isEmpty()) { toast("No launchable apps found"); return }
        val labels = apps.map { "${it.label}  (${it.resolvedPackage})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Add an installed app")
            .setItems(labels) { _, which ->
                apps[which].resolvedPackage?.let {
                    cfg.addHomeApp(it); ProvisioningLog.i(this, "Admin: added home app $it (picker)"); build()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- actions -------------------------------------------------------------------------

    private fun rerun(ids: List<StepId>) {
        startActivity(
            Intent(this, com.flo.v3poslauncher.provisioning.ProvisioningActivity::class.java)
                .setAction("com.flo.v3poslauncher.ACTION_RERUN")
                .putExtra("steps", ids.map { it.name }.toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun runRevert(status: TextView, action: () -> RevertManager.StepOutcome) {
        status.setTextColor(Color.parseColor("#4DA3FF")); status.text = "Working…"
        thread(name = "admin-revert") {
            val o = action()
            main.post {
                status.text = "${o.name}: ${if (o.ok) "OK" else "FAILED"} — ${o.detail}"
                status.setTextColor(if (o.ok) Color.parseColor("#4CD964") else Color.parseColor("#FF3B30"))
            }
        }
    }

    private fun undoEverything(status: TextView) {
        status.setTextColor(Color.parseColor("#4DA3FF")); status.text = "Reverting…"
        thread(name = "admin-undo-all") {
            val sb = StringBuilder()
            RevertManager(this).undoEverything { o ->
                sb.append(if (o.ok) "✓ " else "✕ ").append(o.name).append(": ").append(o.detail).append('\n')
                main.post { status.text = sb.toString() }
            }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(this, com.flo.v3poslauncher.home.HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun stepSummary(): String =
        StepId.values().joinToString("\n") { "• ${it.title}: ${cfg.getStepStatus(it.name)}" }

    // ---- tiny view DSL -------------------------------------------------------------------

    private fun title(t: String) = container.addView(TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 22f; setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, 0, 0, dp(8))
    })

    private fun section(t: String) = container.addView(TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#4DA3FF")); textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(22), 0, dp(6))
    })

    private fun caption(t: String): TextView = TextView(this).apply {
        text = t; setTextColor(Color.parseColor("#9A9A9A")); textSize = 12f
        setPadding(0, dp(2), 0, dp(6))
    }.also { container.addView(it) }

    private fun field(label: String, value: String, numeric: Boolean = false, password: Boolean = false, hint: String = ""): EditText {
        container.addView(TextView(this).apply {
            text = label; setTextColor(Color.parseColor("#C8C8C8")); textSize = 12f; setPadding(0, dp(8), 0, dp(2))
        })
        val e = EditText(this).apply {
            setText(value); setTextColor(Color.WHITE); textSize = 14f
            if (hint.isNotEmpty()) this.hint = hint
            setHintTextColor(Color.parseColor("#6E6E6E"))
            setBackgroundColor(Color.parseColor("#161616"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            inputType = when {
                password -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
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
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
