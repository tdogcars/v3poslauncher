package com.flo.v3poslauncher.home

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.flo.v3poslauncher.admin.DevicePolicy
import com.flo.v3poslauncher.admin.PinActivity
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.util.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The home screen (v1 visual language): true-black background, a centered row of the configured
 * apps, a Wi-Fi status + "Run speed test" control top-left, and the clock/date top-right.
 * Tap a tile to open the app; long-press anywhere for the PIN-gated Launcher configuration.
 *
 * No app is auto-launched — boot lands here on the grid.
 */
class HomeActivity : Activity() {

    private lateinit var cfg: AppConfig
    private lateinit var root: FrameLayout
    private lateinit var grid: LinearLayout
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var netDot: View
    private lateinit var netLabel: TextView
    private lateinit var speedResult: TextView

    private var network: NetworkStatus? = null
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US)
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = AppConfig.get(this)
        grantSelfLocationForSsid()

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        root.addView(grid)
        root.addView(buildTopLeft())
        root.addView(buildTopRight())
        setContentView(root)
        root.setOnLongClickListener { openAdmin(); true }
        grid.setOnLongClickListener { openAdmin(); true }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive()
        renderGrid()
        updateClock()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(tick, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(tick, filter)
        network = NetworkStatus(this) { s -> renderNetwork(s) }.also { it.start() }
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(tick) }
        network?.stop(); network = null
    }

    /** Home swallows Back. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* no-op */ }

    // ---- top-left: network + speed test -------------------------------------------------

    private fun buildTopLeft(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(24); topMargin = dp(22)
            }
        }
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        netDot = View(this).apply {
            background = Ui.circle(Ui.ERR, this@HomeActivity, 9)
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(10) }
        }
        netLabel = TextView(this).apply {
            text = "Not connected"; setTextColor(Color.WHITE); textSize = 14f
        }
        status.addView(netDot); status.addView(netLabel)
        box.addView(status)

        val btn = Ui.outlinedButton(this, "Run speed test") { runSpeedTest() }
        (btn.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        box.addView(btn)

        speedResult = TextView(this).apply {
            text = ""; setTextColor(Ui.TEXT_DIM); textSize = 12f
            setPadding(dp(4), dp(8), 0, 0)
            visibility = View.GONE
        }
        box.addView(speedResult)
        return box
    }

    private fun renderNetwork(s: NetworkStatus.State) {
        val color = when {
            s.connected && s.internet -> Ui.OK
            s.connected -> Ui.WARN
            else -> Ui.ERR
        }
        netDot.background = Ui.circle(color, this, 9)
        netLabel.text = when {
            !s.connected -> "Not connected"
            s.ssid != null -> s.ssid
            else -> "Wi-Fi"
        }
    }

    private fun runSpeedTest() {
        if (SpeedTest.isRunning) return
        speedResult.visibility = View.VISIBLE
        speedResult.setTextColor(Ui.TEXT_DIM)
        speedResult.text = "Starting…"
        SpeedTest.run(
            onProgress = { speedResult.text = it },
            onDone = { r ->
                speedResult.text = r.summary()
                speedResult.setTextColor(if (r.ok) Ui.OK else Ui.ERR)
            },
        )
    }

    // ---- top-right: clock + date ---------------------------------------------------------

    private fun buildTopRight(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(24); topMargin = dp(18)
            }
        }
        clock = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 30f; setTypeface(Typeface.DEFAULT_BOLD); gravity = Gravity.END
        }
        date = TextView(this).apply {
            setTextColor(Ui.TEXT_DIM); textSize = 14f; gravity = Gravity.END
        }
        box.addView(clock); box.addView(date)
        return box
    }

    private fun updateClock() {
        val now = Date()
        clock.text = timeFmt.format(now)
        date.text = dateFmt.format(now)
    }

    // ---- center: app grid ----------------------------------------------------------------

    private fun renderGrid() {
        grid.removeAllViews()
        val apps = cfg.homeApps
        val iconPx = (cfg.iconSizeDp * resources.displayMetrics.density).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        if (apps.isEmpty()) {
            row.addView(TextView(this).apply {
                text = "No apps configured.\nLong-press for settings."
                setTextColor(Ui.TEXT_FAINT); gravity = Gravity.CENTER
            })
        } else {
            apps.forEach { pkg -> row.addView(tileFor(pkg, iconPx)) }
        }
        grid.addView(row)
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
            setTextColor(if (entry.installed) Color.WHITE else Ui.TEXT_FAINT)
            textSize = 16f; gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
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

    // ---- misc -----------------------------------------------------------------------------

    private fun openAdmin() {
        startActivity(Intent(this, PinActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int): Int = Ui.dp(this, v)
}
