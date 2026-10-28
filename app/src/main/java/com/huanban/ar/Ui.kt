package com.huanban.ar

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

object Ui {
    val bg = Color.rgb(16, 21, 30)
    val panel = Color.rgb(29, 38, 51)
    val mint = Color.rgb(166, 243, 208)
    val muted = Color.rgb(173, 186, 203)

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun round(color: Int, r: Float = 20f) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = r
        }

    fun column(c: Context) =
        LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 24))
        }

    fun text(c: Context, s: String, size: Float = 16f, color: Int = Color.WHITE) =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            setPadding(0, dp(c, 6), 0, dp(c, 6))
            setLineSpacing(3f, 1f)
        }

    fun title(c: Context, s: String) = text(c, s, 28f).apply { typeface = Typeface.DEFAULT_BOLD }

    fun button(c: Context, label: String, click: () -> Unit) =
        Button(c).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(bg)
            backgroundTintList = android.content.res.ColorStateList.valueOf(mint)
            minHeight = dp(c, 48)
            setOnClickListener { click() }
        }

    fun secondary(c: Context, label: String, click: () -> Unit) =
        button(c, label, click).apply {
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(panel)
        }

    fun card(c: Context, title: String, body: String, click: () -> Unit): View =
        column(c).apply {
            background = round(panel, 28f)
            addView(text(c, title, 22f, mint))
            addView(text(c, body, 14f, muted))
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 12) }
        }

    fun edit(c: Context, hint: String, value: String = "", secret: Boolean = false) =
        EditText(c).apply {
            this.hint = hint
            setText(value)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(muted)
            setSingleLine()
            if (secret) inputType = 129
        }

    fun row(c: Context, vararg children: View) =
        LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            children.forEach {
                addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(c, 4) })
            }
        }

    fun scroll(c: Context, child: View) =
        ScrollView(c).apply {
            isFillViewport = true
            addView(child)
        }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    fun insets(a: Activity, root: View) {
        root.setBackgroundColor(bg)
        root.setOnApplyWindowInsetsListener { v, i ->
            val bars = i.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            i
        }
        a.setContentView(root)
    }

    fun slider(c: Context, label: String, max: Int, value: Int, change: (Int) -> Unit): View =
        column(c).apply {
            setPadding(0, 0, 0, 0)
            val t = text(c, label, 14f, muted)
            addView(t)
            addView(
                SeekBar(c).apply {
                    this.max = max
                    progress = value
                    contentDescription = label
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(b: SeekBar, p: Int, user: Boolean) {
                                if (user) change(p)
                            }

                            override fun onStartTrackingTouch(b: SeekBar) {}

                            override fun onStopTrackingTouch(b: SeekBar) {}
                        }
                    )
                }
            )
        }
}
