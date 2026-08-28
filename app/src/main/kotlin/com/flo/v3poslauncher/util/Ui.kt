package com.flo.v3poslauncher.util

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/** Shared colors + tiny view helpers for the framework-only UI (no AndroidX). */
object Ui {
    val BLACK = Color.BLACK
    val PANEL_BG = Color.parseColor("#141414")
    val CARD = Color.parseColor("#262626")
    val CARD_ALT = Color.parseColor("#1D1D1D")
    val TEXT = Color.WHITE
    val TEXT_DIM = Color.parseColor("#B3B3B3")
    val TEXT_FAINT = Color.parseColor("#7A7A7A")
    val ACCENT = Color.parseColor("#A8C7FA")
    val OK = Color.parseColor("#4CD964")
    val WARN = Color.parseColor("#FFCC00")
    val ERR = Color.parseColor("#FF5A52")

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun rounded(fill: Int, radiusDp: Int, context: Context, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radiusDp).toFloat()
            setColor(fill)
            if (strokeColor != null) setStroke(dp(context, strokeDp), strokeColor)
        }

    fun circle(color: Int, context: Context, sizeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setSize(dp(context, sizeDp), dp(context, sizeDp))
        }

    /** Pill-shaped outlined text button, as in the v1 launcher ("Run speed test"). */
    fun outlinedButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(ACCENT)
            textSize = 13f
            isAllCaps = false
            val h = dp(context, 18); val v = dp(context, 9)
            setPadding(h, v, h, v)
            val shape = rounded(Color.TRANSPARENT, 24, context, strokeColor = ACCENT)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33A8C7FA")), shape, shape)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    /** Flat accent text button ("Done"). */
    fun textButton(context: Context, label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(ACCENT)
            textSize = 16f
            val h = dp(context, 16); val v = dp(context, 10)
            setPadding(h, v, h, v)
            val shape = rounded(Color.TRANSPARENT, 8, context)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33A8C7FA")), null, shape)
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }
}
