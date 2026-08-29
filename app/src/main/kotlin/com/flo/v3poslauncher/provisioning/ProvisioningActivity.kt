package com.flo.v3poslauncher.provisioning

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flo.v3poslauncher.App
import com.flo.v3poslauncher.config.AdminExtras
import com.flo.v3poslauncher.config.AppConfig

/**
 * The true-black, full-screen provisioning status screen (v1 visual language). Shows each step
 * with a check / cross / spinner-ish state so the technician can watch progress and trust the
 * device isn't hung. On failure it shows the exact reason plus Retry / Skip buttons — never a
 * silent hang.
 *
 * Reached as:
 *  - ACTION_ADMIN_POLICY_COMPLIANCE (API 30+): compliance mode; on success we setResult(OK) and
 *    finish so the setup wizard exits to HOME.
 *  - ACTION_PROVISIONING_SUCCESSFUL (API 26–29): standalone mode; on success we jump to HOME.
 *  - ACTION_RERUN (admin panel): standalone mode, may run a subset of steps.
 */
class ProvisioningActivity : Activity(), ProvisioningRunner.Listener {

    private lateinit var runner: ProvisioningRunner
    private val rows = HashMap<StepId, StepRow>()
    private lateinit var header: TextView
    private lateinit var footer: TextView
    private lateinit var actionBar: LinearLayout
    private lateinit var retryBtn: Button
    private lateinit var skipBtn: Button
    private lateinit var continueBtn: Button

    private var complianceMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keepScreenOnAndImmersive()

        val app = application as App
        runner = app.provisioningRunner

        complianceMode = intent.action == "android.app.action.ADMIN_POLICY_COMPLIANCE"

        // Capture admin extras from whichever intent launched us.
        AdminExtras.applyFromIntent(this, intent, "ProvisioningActivity:${intent.action}")

        setContentView(buildUi())
        renderAll()

        runner.addListener(this)
        maybeStart()
    }

    override fun onDestroy() {
        runner.removeListener(this)
        super.onDestroy()
    }

    /** Block Back during provisioning so a stray tap can't abandon it. */
    override fun onBackPressed() { /* swallow */ }

    private fun maybeStart() {
        when (intent.action) {
            "com.flo.v3poslauncher.ACTION_RERUN" -> {
                val ids = intent.getStringArrayExtra("steps")?.mapNotNull { runCatching { StepId.valueOf(it) }.getOrNull() }
                    ?: StepId.active()
                runner.start(ids, compliance = false)
            }
            else -> runner.startAll(compliance = complianceMode)
        }
    }

    // ---- Listener ------------------------------------------------------------------------

    override fun onStepChanged(state: ProvisioningRunner.StepState) {
        runOnUiThread { rows[state.id]?.bind(state) }
    }

    override fun onRunStateChanged(state: ProvisioningRunner.RunState) {
        runOnUiThread {
            when (state) {
                ProvisioningRunner.RunState.RUNNING -> {
                    actionBar.visibility = View.GONE
                    footer.text = "Provisioning… do not power off."
                }
                ProvisioningRunner.RunState.PAUSED_ON_FAILURE -> {
                    val failed = runner.steps.firstOrNull { it.status == ProvisioningRunner.Status.FAIL }
                    footer.text = "A step failed: ${failed?.message ?: ""}"
                    footer.setTextColor(COLOR_FAIL)
                    retryBtn.visibility = View.VISIBLE
                    skipBtn.visibility = View.VISIBLE
                    continueBtn.visibility = View.GONE
                    actionBar.visibility = View.VISIBLE
                }
                ProvisioningRunner.RunState.DONE -> onDone()
                ProvisioningRunner.RunState.IDLE -> {}
            }
        }
    }

    private fun onDone() {
        val hadFailure = runner.steps.any { it.status == ProvisioningRunner.Status.FAIL }
        val hadWarn = runner.steps.any {
            it.status == ProvisioningRunner.Status.WARN || it.status == ProvisioningRunner.Status.SKIPPED
        }
        retryBtn.visibility = View.GONE
        skipBtn.visibility = View.GONE
        when {
            hadFailure -> {
                footer.text = "Finished with errors. Review above and Retry, or contact support."
                footer.setTextColor(COLOR_FAIL)
                retryBtn.visibility = View.VISIBLE
                actionBar.visibility = View.VISIBLE
            }
            else -> {
                footer.text = if (hadWarn) "Provisioning complete (with warnings)." else "Provisioning complete."
                footer.setTextColor(if (hadWarn) COLOR_WARN else COLOR_OK)
                if (complianceMode) {
                    // Hand control back to the setup wizard, which exits to HOME (us).
                    setResult(Activity.RESULT_OK)
                    footer.postDelayed({ finish() }, 900)
                } else {
                    continueBtn.visibility = View.VISIBLE
                    actionBar.visibility = View.VISIBLE
                }
            }
        }
    }

    // ---- UI construction (programmatic, no XML) -----------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val pad = dp(24)
            setPadding(pad, dp(40), pad, dp(24))
        }

        header = TextView(this).apply {
            text = "Setting up terminal"
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        root.addView(header)

        val sub = TextView(this).apply {
            text = "FLO POS Launcher • ${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}"
            setTextColor(COLOR_DIM)
            textSize = 12f
            setPadding(0, dp(4), 0, dp(20))
        }
        root.addView(sub)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        StepId.active().forEach { id ->
            val row = StepRow(this)
            rows[id] = row
            list.addView(row.view)
        }
        scroll.addView(list)
        root.addView(scroll)

        footer = TextView(this).apply {
            text = ""
            setTextColor(COLOR_DIM)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(footer)

        actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        retryBtn = actionButton("Retry") {
            footer.setTextColor(COLOR_DIM)
            if (runner.runState == ProvisioningRunner.RunState.DONE) runner.startAll(complianceMode)
            else runner.retryFailed()
        }
        skipBtn = actionButton("Skip step") { runner.skipFailed() }
        continueBtn = actionButton("Continue") { goHome() }
        actionBar.addView(retryBtn)
        actionBar.addView(skipBtn)
        actionBar.addView(continueBtn)
        root.addView(actionBar)

        return root
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun renderAll() = runner.steps.forEach { rows[it.id]?.bind(it) }

    private fun goHome() {
        startActivity(
            android.content.Intent(this, com.flo.v3poslauncher.home.HomeActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun keepScreenOnAndImmersive() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** One step row: [icon] title  +  message / progress. */
    inner class StepRow(activity: Activity) {
        val view: LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(10), 0, dp(10))
        }
        private val icon = TextView(activity).apply {
            textSize = 18f
            width = dp(34)
            gravity = Gravity.CENTER
        }
        private val title = TextView(activity).apply {
            setTextColor(Color.WHITE); textSize = 16f; setTypeface(Typeface.DEFAULT_BOLD)
        }
        private val msg = TextView(activity).apply { setTextColor(COLOR_DIM); textSize = 13f }

        init {
            val text = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            text.addView(title); text.addView(msg)
            view.addView(icon); view.addView(text)
        }

        fun bind(state: ProvisioningRunner.StepState) {
            title.text = state.id.title
            val (glyph, color) = when (state.status) {
                ProvisioningRunner.Status.PENDING -> "○" to COLOR_DIM
                ProvisioningRunner.Status.RUNNING -> "▸" to COLOR_ACCENT
                ProvisioningRunner.Status.OK -> "✓" to COLOR_OK
                ProvisioningRunner.Status.WARN -> "!" to COLOR_WARN
                ProvisioningRunner.Status.FAIL -> "✕" to COLOR_FAIL
                ProvisioningRunner.Status.SKIPPED -> "»" to COLOR_WARN
            }
            icon.text = glyph
            icon.setTextColor(color)
            val detail = when {
                state.status == ProvisioningRunner.Status.RUNNING && state.progress.isNotEmpty() -> state.progress
                state.message.isNotEmpty() -> state.message
                state.status == ProvisioningRunner.Status.RUNNING -> "working…"
                state.status == ProvisioningRunner.Status.PENDING -> "waiting"
                else -> ""
            }
            msg.text = detail
            msg.setTextColor(if (state.status == ProvisioningRunner.Status.FAIL) COLOR_FAIL else COLOR_DIM)
            msg.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    companion object {
        private val COLOR_OK = Color.parseColor("#4CD964")
        private val COLOR_WARN = Color.parseColor("#FFCC00")
        private val COLOR_FAIL = Color.parseColor("#FF3B30")
        private val COLOR_ACCENT = Color.parseColor("#4DA3FF")
        private val COLOR_DIM = Color.parseColor("#9A9A9A")
    }
}
