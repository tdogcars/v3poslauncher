package com.flo.v3poslauncher.admin

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.flo.v3poslauncher.config.AppConfig
import com.flo.v3poslauncher.home.AppLauncher
import com.flo.v3poslauncher.provisioning.ProvisioningLog
import com.flo.v3poslauncher.util.Ui

/**
 * "Launcher configuration" — the PIN-gated settings screen, styled after the v1 launcher:
 *
 *   Launcher configuration                                  Done
 *   N selected
 *   Icon size   [========o------]  125 dp
 *   Shown on home screen (in order)
 *     [icon] Label / package            ^  v  [x]
 *   Available apps
 *     [icon] Label / package                  [ ]
 *   Advanced device management…
 *
 * Every change is saved immediately. Provisioning / revert / PIN tools live in
 * [AdvancedAdminActivity]. Not exported; assumes the caller already passed the PIN.
 */
class AdminPanelActivity : Activity() {

    private lateinit var cfg: AppConfig
    private lateinit var container: LinearLayout
    private lateinit var selectedCount: TextView
    private lateinit var shownList: LinearLayout
    private lateinit var availableList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = AppConfig.get(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.PANEL_BG)
            isFillViewport = true
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24); setPadding(p, dp(16), p, dp(40))
        }
        scroll.addView(container)
        setContentView(scroll)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshLists()
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun build() {
        container.removeAllViews()

        // Header: title + "N selected" on the left, Done on the right.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(this).apply {
            text = "Launcher configuration"; setTextColor(Ui.TEXT); textSize = 22f; setTypeface(Typeface.DEFAULT_BOLD)
        })
        selectedCount = TextView(this).apply { setTextColor(Ui.TEXT_DIM); textSize = 13f }
        titles.addView(selectedCount)
        header.addView(titles)
        header.addView(Ui.textButton(this, "Done") { done() })
        container.addView(header)

        // Icon size slider.
        sectionLabel("Icon size", topDp = 26)
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        val valueLabel = TextView(this).apply {
            setTextColor(Ui.TEXT); textSize = 15f; setPadding(dp(16), 0, 0, 0)
            text = "${cfg.iconSizeDp} dp"
        }
        val slider = SeekBar(this).apply {
            max = ICON_MAX - ICON_MIN
            progress = (cfg.iconSizeDp - ICON_MIN).coerceIn(0, max)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            progressTintList = ColorStateList.valueOf(Ui.ACCENT)
            thumbTintList = ColorStateList.valueOf(Ui.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3A3A"))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    valueLabel.text = "${p + ICON_MIN} dp"
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    cfg.iconSizeDp = sb.progress + ICON_MIN
                    ProvisioningLog.i(this@AdminPanelActivity, "Admin: icon size ${cfg.iconSizeDp}dp")
                }
            })
        }
        sliderRow.addView(slider); sliderRow.addView(valueLabel)
        container.addView(sliderRow)
        divider()

        // Lists (filled by refreshLists()).
        sectionLabel("Shown on home screen (in order)", topDp = 18)
        shownList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        container.addView(shownList)

        sectionLabel("Available apps", topDp = 22)
        availableList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        container.addView(availableList)

        divider(topDp = 24)
        val advanced = Ui.outlinedButton(this, "Advanced device management…") {
            startActivity(Intent(this, AdvancedAdminActivity::class.java))
        }
        (advanced.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        container.addView(advanced)
        container.addView(TextView(this).apply {
            text = "Provisioning status, re-run steps, Wi-Fi, revert / undo everything, change PIN."
            setTextColor(Ui.TEXT_FAINT); textSize = 12f; setPadding(dp(4), dp(8), 0, 0)
        })

        refreshLists()
    }

    private fun refreshLists() {
        if (!::shownList.isInitialized) return
        val shown = cfg.homeApps
        selectedCount.text = "${shown.size} selected"

        shownList.removeAllViews()
        if (shown.isEmpty()) {
            shownList.addView(TextView(this).apply {
                text = "Nothing selected — tick apps below to show them on the home screen."
                setTextColor(Ui.TEXT_FAINT); textSize = 13f; setPadding(dp(4), dp(6), 0, dp(6))
            })
        }
        shown.forEachIndexed { i, pkg ->
            val entry = AppLauncher.resolve(this, pkg)
            shownList.addView(
                appRow(
                    label = if (entry.installed) entry.label else "${entry.label}  (not installed)",
                    pkg = pkg, icon = entry.icon, checked = true,
                    canUp = i > 0, canDown = i < shown.size - 1,
                    onUp = { cfg.moveHomeApp(pkg, -1); refreshLists() },
                    onDown = { cfg.moveHomeApp(pkg, +1); refreshLists() },
                    onToggle = { cfg.removeHomeApp(pkg); ProvisioningLog.i(this, "Admin: removed home app $pkg"); refreshLists() },
                ),
            )
        }

        availableList.removeAllViews()
        val shownLower = shown.map { it.lowercase() }.toSet()
        val available = AppLauncher.installedLaunchableApps(this)
            .filter { e ->
                val p = e.resolvedPackage
                p != null && p != packageName && p.lowercase() !in shownLower
            }
        if (available.isEmpty()) {
            availableList.addView(TextView(this).apply {
                text = "No other launchable apps on this device."
                setTextColor(Ui.TEXT_FAINT); textSize = 13f; setPadding(dp(4), dp(6), 0, dp(6))
            })
        }
        available.forEach { e ->
            val p = e.resolvedPackage!!
            availableList.addView(
                appRow(
                    label = e.label, pkg = p, icon = e.icon, checked = false,
                    canUp = false, canDown = false, onUp = {}, onDown = {},
                    onToggle = { cfg.addHomeApp(p); ProvisioningLog.i(this, "Admin: added home app $p"); refreshLists() },
                ),
            )
        }
    }

    // ---- rows -----------------------------------------------------------------------------

    private fun appRow(
        label: String, pkg: String, icon: android.graphics.drawable.Drawable?, checked: Boolean,
        canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit, onToggle: () -> Unit,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(if (checked) Ui.CARD else Color.TRANSPARENT, 10, this@AdminPanelActivity)
            val h = dp(12); val v = dp(10)
            setPadding(h, v, h, v)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        row.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) }
            if (icon != null) setImageDrawable(icon) else setImageResource(com.flo.v3poslauncher.R.drawable.ic_launcher_foreground)
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply { text = label; setTextColor(Ui.TEXT); textSize = 16f })
        texts.addView(TextView(this).apply {
            text = pkg; setTextColor(Ui.TEXT_DIM); textSize = 12f; typeface = Typeface.MONOSPACE
        })
        row.addView(texts)

        if (checked) {
            row.addView(arrowButton("⌃", canUp, onUp))   // ⌃
            row.addView(arrowButton("⌄", canDown, onDown)) // ⌄
        }
        row.addView(CheckBox(this).apply {
            isChecked = checked
            buttonTintList = ColorStateList.valueOf(if (checked) Ui.ACCENT else Ui.TEXT_DIM)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) }
            setOnClickListener { onToggle() }
        })
        return row
    }

    private fun arrowButton(glyph: String, enabled: Boolean, onClick: () -> Unit): View =
        TextView(this).apply {
            text = glyph; textSize = 22f; gravity = Gravity.CENTER
            setTextColor(if (enabled) Ui.TEXT else Color.parseColor("#4A4A4A"))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            isEnabled = enabled
            if (enabled) setOnClickListener { onClick() }
        }

    // ---- helpers --------------------------------------------------------------------------

    private fun sectionLabel(t: String, topDp: Int) = container.addView(TextView(this).apply {
        text = t; setTextColor(Ui.TEXT); textSize = 15f; setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, dp(topDp), 0, dp(4))
    })

    private fun divider(topDp: Int = 16) = container.addView(View(this).apply {
        setBackgroundColor(Color.parseColor("#2E2E2E"))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { topMargin = dp(topDp) }
    })

    private fun done() {
        startActivity(
            Intent(this, com.flo.v3poslauncher.home.HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    private fun dp(v: Int): Int = Ui.dp(this, v)

    private companion object {
        const val ICON_MIN = 48
        const val ICON_MAX = 256
    }
}
