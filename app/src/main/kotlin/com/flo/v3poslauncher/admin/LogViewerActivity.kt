package com.flo.v3poslauncher.admin

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flo.v3poslauncher.provisioning.ProvisioningLog

/** Read-only viewer for the on-device provisioning log, with a clear button. */
class LogViewerActivity : Activity() {
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            val p = dp(16); setPadding(p, dp(28), p, p)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        root.addView(TextView(this).apply {
            text = "Provisioning log"; setTextColor(Color.WHITE); textSize = 20f; setTypeface(Typeface.DEFAULT_BOLD)
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        bar.addView(Button(this).apply { text = "Refresh"; setOnClickListener { reload() } })
        bar.addView(Button(this).apply { text = "Clear"; setOnClickListener { ProvisioningLog.clear(this@LogViewerActivity); reload() } })
        bar.addView(Button(this).apply { text = "Close"; setOnClickListener { finish() } })
        root.addView(bar)

        val scroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) }
        body = TextView(this).apply {
            setTextColor(Color.parseColor("#C8C8C8")); textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        scroll.addView(body)
        root.addView(scroll)
        setContentView(root)
        reload()
    }

    private fun reload() { body.text = ProvisioningLog.read(this) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
