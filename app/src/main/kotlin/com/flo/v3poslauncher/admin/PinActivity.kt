package com.flo.v3poslauncher.admin

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.config.Constants

/**
 * 4-digit PIN gate for the admin panel. This is purely the launcher's own settings gate — it is
 * NOT an Android keyguard/lock-screen, and no keyguard or password DEVICE POLICY is involved.
 *
 * Brute-force protection: after Constants.PIN_MAX_ATTEMPTS wrong entries, a timed lockout. The
 * PIN is verified against a salted hash (or the compiled-in default if none was ever set); it is
 * never displayed or logged.
 */
class PinActivity : Activity() {

    private lateinit var cfg: AppConfig
    private lateinit var input: EditText
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = AppConfig.get(this)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val p = dp(32); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        val title = TextView(this).apply {
            text = "Enter admin PIN"; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
        }
        input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "••••"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            layoutParams = LinearLayout.LayoutParams(dp(200), WRAP_CONTENT).apply { topMargin = dp(24) }
        }
        error = TextView(this).apply {
            setTextColor(Color.parseColor("#FF3B30")); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val unlock = Button(this).apply {
            text = "Unlock"
            setOnClickListener { attempt() }
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        root.addView(title); root.addView(input); root.addView(error); root.addView(unlock); root.addView(cancel)
        return root
    }

    private fun attempt() {
        val now = SystemClock.elapsedRealtime()
        if (now < cfg.pinLockoutUntil) {
            val secs = ((cfg.pinLockoutUntil - now) / 1000) + 1
            error.text = "Too many attempts. Wait ${secs}s."
            return
        }
        val pin = input.text.toString()
        if (pin.length != 4) { error.text = "Enter 4 digits."; return }

        if (cfg.verifyPin(pin)) {
            cfg.pinFailedAttempts = 0
            cfg.pinLockoutUntil = 0
            startActivity(Intent(this, AdminPanelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        } else {
            cfg.pinFailedAttempts += 1
            input.text.clear()
            if (cfg.pinFailedAttempts >= Constants.PIN_MAX_ATTEMPTS) {
                cfg.pinLockoutUntil = now + Constants.PIN_LOCKOUT_MS
                cfg.pinFailedAttempts = 0
                error.text = "Too many attempts. Locked for ${Constants.PIN_LOCKOUT_MS / 1000}s."
            } else {
                val left = Constants.PIN_MAX_ATTEMPTS - cfg.pinFailedAttempts
                error.text = "Incorrect PIN. $left attempt${if (left == 1) "" else "s"} left."
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
