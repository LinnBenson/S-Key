package io.secure.keyboard.settings

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import io.secure.keyboard.ui.styleSystemBars
import kotlin.math.max

/** A bounded, inset-aware LIGHT shell used exclusively by companion application screens. */
internal open class SettingsScreen(private val activity: Activity) {
    val background = SettingsPalette.background
    val surface = SettingsPalette.surface
    val ink = SettingsPalette.ink
    val muted = SettingsPalette.secondaryText
    val tint = SettingsPalette.functional
    val blue = SettingsPalette.accent // Compatibility name; never a keyboard color.
    val dark = false
    lateinit var scroll: ScrollView
        private set
    private var shell: View? = null
    private var restoreListener: ViewTreeObserver.OnPreDrawListener? = null

    fun dp(value: Int) = SettingsPalette.dp(activity, value)
    fun shape(color: Int, radius: Int = 24) = SettingsPalette.shape(activity, color, radius)
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    @Suppress("DEPRECATION")
    fun root(
        title: String? = null,
        backLabel: String? = null,
        onBack: (() -> Unit)? = null,
    ): LinearLayout {
        dispose()
        activity.window.statusBarColor = background
        styleSystemBars(activity.window, dark = false, background = background)
        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) activity.window.setDecorFitsSystemWindows(false)
        else activity.window.decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        val inset = if (activity.resources.configuration.screenWidthDp >= 600) 24 else 22
        val root = column().apply {
            tag = "settings_shell"
            setBackgroundColor(this@SettingsScreen.background)
            if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        }
        if (title != null || backLabel != null) {
            val header = column().apply { setPadding(dp(inset), dp(10), dp(inset), dp(12)) }
            backLabel?.let { label ->
                button(header, "‹  返回") { onBack?.invoke() }.apply {
                    tag = "settings_back"
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    background = SettingsPalette.buttonBackground(activity)
                    setPadding(dp(14), 0, dp(14), 0)
                    minHeight = dp(44); minimumHeight = dp(44)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(-2, -2)
                    contentDescription = label
                }
            }
            title?.let { value ->
                text(header, value, 24f, true).apply {
                    tag = "settings_title"
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                }
            }
            root.addView(MaxWidthLayout(activity, dp(720)).apply { addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)) })
        }
        val body = column().apply {
            tag = "settings_body"
            setPadding(dp(inset), dp(if (title == null) 16 else 4), dp(inset), dp(24))
        }
        scroll = ScrollView(activity).apply {
            tag = "settings_scroll"
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(MaxWidthLayout(activity, dp(720)).apply {
                addView(body, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            }, ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            } else {
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        shell = root
        activity.setContentView(root)
        root.requestApplyInsets()
        return body
    }

    fun scrollY(): Int = if (::scroll.isInitialized) scroll.scrollY else 0

    fun restoreScroll(offset: Int) {
        if (!::scroll.isInitialized) return
        restoreListener?.let { scroll.viewTreeObserver.removeOnPreDrawListener(it) }
        val target = scroll
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                target.viewTreeObserver.removeOnPreDrawListener(this)
                restoreListener = null
                target.scrollTo(0, offset.coerceAtLeast(0))
                return true
            }
        }
        restoreListener = listener
        target.viewTreeObserver.addOnPreDrawListener(listener)
        target.invalidate()
    }

    fun dispose() {
        if (::scroll.isInitialized && scroll.viewTreeObserver.isAlive) {
            restoreListener?.let { scroll.viewTreeObserver.removeOnPreDrawListener(it) }
        }
        restoreListener = null
        shell?.setOnApplyWindowInsetsListener(null)
        shell = null
    }

    fun text(parent: LinearLayout, value: String, size: Float = 14f, strong: Boolean = false): TextView =
        TextView(activity).apply {
            text = value
            textSize = size
            includeFontPadding = false
            setTextColor(if (strong) ink else muted)
            if (strong) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(4), 0, dp(6))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun card(parent: LinearLayout, color: Int = surface): LinearLayout = column().apply {
        background = shape(color)
        setPadding(dp(18), dp(16), dp(18), dp(16))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
    }

    fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit): Button =
        Button(activity).apply {
            text = label
            gravity = Gravity.CENTER
            SettingsPalette.styleButton(this, primary)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(5), 0, dp(5)) })
            setOnClickListener { action() }
        }

    fun section(parent: LinearLayout, title: String, subtitle: String? = null): LinearLayout =
        card(parent).also { group ->
            text(group, title, 17f, true).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
            subtitle?.let { text(group, it) }
        }

    fun infoCard(parent: LinearLayout, title: String, message: String): LinearLayout =
        card(parent, tint).also { text(it, title, 17f, true); text(it, message) }

    fun emptyState(parent: LinearLayout, title: String, message: String): LinearLayout =
        infoCard(parent, title, message)

    fun actionRow(parent: LinearLayout, title: String, summary: String? = null, action: () -> Unit): Button =
        button(parent, if (summary.isNullOrBlank()) title else "$title\n$summary", action = action).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }

    fun valueRow(parent: LinearLayout, title: String, value: String, action: () -> Unit): Button =
        actionRow(parent, "$title: $value", action = action)

    fun dangerRow(parent: LinearLayout, label: String, action: () -> Unit): Button =
        button(parent, label, action = action).apply { SettingsPalette.styleButton(this, destructive = true) }

    fun actionGroup(parent: LinearLayout): LinearLayout = column().also { parent.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    fun toggleRow(parent: LinearLayout, label: String, checked: Boolean, change: (Boolean) -> Unit): Switch =
        Switch(activity).apply {
            text = label
            isChecked = checked
            SettingsPalette.styleCompound(this)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnCheckedChangeListener { _, value -> change(value) }
        }

    fun sliderRow(parent: LinearLayout, title: String, value: Int, min: Int, max: Int, unit: String = "", change: (Int) -> Unit): SeekBar {
        val label = text(parent, "$title: $value$unit", 15f, true)
        return SeekBar(activity).apply {
            this.max = (max - min).coerceAtLeast(0)
            progress = (value - min).coerceIn(0, this.max)
            contentDescription = title
            SettingsPalette.styleSlider(this)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$title: ${progress + min}$unit"
                    if (fromUser) change(progress + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
    }

    private class MaxWidthLayout(context: Context, private val maxContentWidth: Int) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec)
            val childWidth = available.coerceAtMost(maxContentWidth)
            val childWidthSpec = MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY)
            super.onMeasure(childWidthSpec, heightMeasureSpec)
            setMeasuredDimension(resolveSize(available, widthMeasureSpec), measuredHeight)
        }
    }
}
