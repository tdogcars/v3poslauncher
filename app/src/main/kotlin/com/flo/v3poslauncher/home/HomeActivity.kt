package com.flo.v3poslauncher.home

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.flo.v3poslauncher.admin.PinActivity
import com.flo.v3poslauncher.config.AppConfig

/**
 * The home screen: true-black background showing a small centered grid of the configured apps
 * (by default Chrome and Settings). Tap a tile to open the app; long-press anywhere (or on a
 * tile) to open the PIN-gated admin panel.
 *
 * No app is auto-launched — boot lands here on the grid (per requirement). No stock taskbar or
 * app drawer is shown because provisioning made us the persistent Home and hid the stock
 * launcher. Missing apps (e.g. Chrome absent on an AOSP image) render as a disabled tile.
 */
class HomeActivity : Activity() {

    private lateinit var cfg: AppConfig
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = AppConfig.get(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        setContentView(root)
        root.setOnLongClickListener { openAdmin(); true }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        renderGrid()
    }

    /** Home swallows Back. */
    override fun onBackPressed() { /* no-op */ }

    private fun renderGrid() {
        root.removeAllViews()
        val apps = cfg.homeApps
        val iconPx = (cfg.iconSizeDp * resources.displayMetrics.density).toInt()

        // A single centered row of tiles; wraps to the width for 1–4 apps which is the norm.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (apps.isEmpty()) {
            row.addView(TextView(this).apply {
                text = "No apps configured.\nLong-press for settings."
                setTextColor(Color.parseColor("#9A9A9A")); gravity = Gravity.CENTER
            })
        } else {
            apps.forEach { pkg -> row.addView(tileFor(pkg, iconPx)) }
        }
        root.addView(row)

        root.addView(TextView(this).apply {
            text = "Long-press anywhere for settings"
            setTextColor(Color.parseColor("#5A5A5A")); textSize = 11f
            gravity = Gravity.CENTER; setPadding(0, dp(28), 0, 0)
        })
    }

    private fun tileFor(pkg: String, iconPx: Int): View {
        val entry = AppLauncher.resolve(this, pkg)
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val m = dp(20)
            setPadding(m, m, m, m)
            isEnabled = entry.installed
        }
        val icon = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconPx, iconPx)
            if (entry.icon != null) setImageDrawable(entry.icon)
            else setImageResource(com.flo.v3poslauncher.R.drawable.ic_launcher_foreground)
            alpha = if (entry.installed) 1f else 0.35f
        }
        val label = TextView(this).apply {
            text = if (entry.installed) entry.label else "${entry.label}\n(not installed)"
            setTextColor(if (entry.installed) Color.WHITE else Color.parseColor("#8A8A8A"))
            textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        tile.addView(icon); tile.addView(label)
        tile.setOnClickListener {
            if (entry.installed && entry.resolvedPackage != null) {
                if (!AppLauncher.launch(this, entry.resolvedPackage)) toast("Could not open ${entry.label}")
            } else {
                toast("${entry.label} is not installed on this device")
            }
        }
        tile.setOnLongClickListener { openAdmin(); true }
        return tile
    }

    private fun openAdmin() {
        startActivity(Intent(this, PinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun applyImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
