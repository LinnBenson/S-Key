package io.secure.keyboard

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import io.secure.keyboard.settings.SettingsInputs
import io.secure.keyboard.settings.SettingsScreen
import io.secure.keyboard.settings.applySettingsActivityTheme

/** Inset-aware launcher; trial input remains transient and local. */
class MainActivity : Activity() {
    private lateinit var ui: SettingsScreen
    private lateinit var testInput: EditText
    private lateinit var enableButton: Button
    private lateinit var chooseButton: Button
    private val ink = 0xFF2B2B2B.toInt()
    private val muted = 0xFF73736F.toInt()
    private val paper = 0xFFF6F6F3.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private fun shape(color: Int, radius: Int = 24, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }
    private fun text(value: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card(parent: LinearLayout, color: Int = Color.WHITE) = column().apply {
        background = shape(color)
        setPadding(dp(20), dp(20), dp(20), dp(20))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun gap(parent: LinearLayout, height: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun button(parent: LinearLayout, title: String, tagName: String, dark: Boolean = false, action: () -> Unit): Button =
        Button(this).apply {
            text = title; tag = tagName; isAllCaps = false; textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            minHeight = dp(54); minimumHeight = dp(54)
            setTextColor(if (dark) Color.WHITE else ink)
            backgroundTintList = null
            background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x152B2B2B),
                shape(if (dark) ink else paper, 18), null)
            stateListAnimator = null
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { action() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySettingsActivityTheme()
        super.onCreate(savedInstanceState)
        ui = SettingsScreen(this)
        val body = ui.root()
        window.decorView.findViewWithTag<View>("settings_shell").setBackgroundColor(paper)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        body.setPadding(dp(22), dp(12), dp(22), dp(24))
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        brand.addView(text("S", 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = shape(ink, 14)
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val heading = column().apply { setPadding(dp(12), 0, 0, 0) }
        heading.addView(text("S Key", 23f, bold = true).apply { tag = "settings_title" })
        heading.addView(text("简单输入，自在表达", 11f, muted).apply { setPadding(0, dp(4), 0, 0) })
        brand.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        brand.addView(text("本机 · 离线", 11f, muted).apply {
            setPadding(dp(12), dp(9), dp(12), dp(9)); background = shape(Color.WHITE, 24)
        })
        body.addView(brand)
        gap(body, 24)

        card(body, ink).also { hero ->
            hero.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF41413E.toInt(), ink, 0xFF242424.toInt())).apply { cornerRadius = dp(26).toFloat() }
            hero.addView(text("YOUR WORDS. YOUR SPACE.", 10f, 0xFFCCCCBF.toInt()).apply { letterSpacing = .14f })
            gap(hero, 16)
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val copy = column()
            copy.addView(text("把想法，\n留在字里。", 28f, Color.WHITE, true))
            gap(copy, 12)
            copy.addView(text("安心输入，只在本机", 12f, 0xFFD7D7CF.toInt()))
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(KeyArtwork(), LinearLayout.LayoutParams(dp(112), dp(118)))
            hero.addView(row)
            gap(hero, 18)
            hero.addView(text("五笔 86     /     拼音     /     英文", 12f, 0xFFDDDDD5.toInt()))
        }

        card(body).also { group ->
            group.addView(text("两步，开始使用", 18f, bold = true))
            gap(group, 5)
            group.addView(text("连接你的键盘，让输入更顺手。", 12f, muted))
            gap(group, 18)
            enableButton = button(group, "01    启用 S Key     ↗", "launcher_enable_keyboard", true) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            gap(group, 10)
            chooseButton = button(group, "02    切换输入法     →", "launcher_choose_keyboard") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }
            gap(group, 12)
            group.addView(text("系统会提示第三方键盘风险，请仅使用你信任的版本。", 11f, muted))
        }

        card(body).also { group ->
            val label = text("试试手感", 18f, bold = true)
            group.addView(label)
            gap(group, 12)
            testInput = SettingsInputs.field(this, "写点什么，让灵感开始…").apply {
                id = R.id.settings_launcher_test_input; tag = "launcher_test_input"
                minLines = 2; gravity = Gravity.TOP or Gravity.START; isSaveEnabled = false
                setTextColor(ink); setHintTextColor(muted); textSize = 16f
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), shape(paper, 16, ink))
                    addState(intArrayOf(), shape(paper, 16))
                }
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            label.labelFor = testInput.id
            group.addView(testInput, LinearLayout.LayoutParams(-1, -2))
        }
        val links = LinearLayout(this).apply { isBaselineAligned = false }
        fun tile(title: String, subtitle: String, glyph: String, tagName: String, action: () -> Unit) {
            val tile = column().apply {
                tag = tagName; isClickable = true; isFocusable = true
                contentDescription = "$title，$subtitle"
                setPadding(dp(16), dp(18), dp(16), dp(18))
                background = RippleDrawable(ColorStateList.valueOf(0x152B2B2B), shape(Color.WHITE), null)
                setOnClickListener { action() }
            }
            tile.addView(io.secure.keyboard.ui.ToolIcon(this, glyph, ink, artworkSizeDp = 23f).apply {
                background = shape(paper, 16)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            gap(tile, 18)
            tile.addView(text(title, 16f, bold = true))
            gap(tile, 6)
            tile.addView(text(subtitle, 11f, muted))
            links.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply {
                if (links.childCount > 0) leftMargin = dp(12)
            })
        }
        tile("偏好设置  ↗", "外观、按键与内容管理", "settings", "launcher_settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        tile("离线语音  ↗", "安装模型，用声音输入", "voice", "launcher_voice") {
            startActivity(Intent(this, VoiceSetupActivity::class.java))
        }
        body.addView(links, LinearLayout.LayoutParams(-1, -2))
        gap(body, 22)
        body.addView(text("无联网权限  ·  不记录输入历史", 11f, muted).apply { gravity = Gravity.CENTER })
        ui.restoreScroll(savedInstanceState?.getInt("launcher_scroll") ?: 0)
    }

    override fun onResume() {
        super.onResume()
        if (!::enableButton.isInitialized) return
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == packageName
        enableButton.text = if (enabled) "01    已启用 S Key     ✓" else "01    启用 S Key     ↗"
        chooseButton.text = if (selected) "02    正在使用 S Key     ✓" else "02    切换输入法     →"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("launcher_scroll", ui.scrollY())
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { if (::ui.isInitialized) ui.dispose(); super.onDestroy() }

    private inner class KeyArtwork : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lettering = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val keyGradients = listOf(Triple(8f, 9f, true), Triple(60f, 24f, false), Triple(20f, 61f, false)).map { (x, y, light) ->
            LinearGradient(x, y, x + 45, y + 40,
                if (light) 0xFFF3F1E7.toInt() else 0xFF696962.toInt(),
                if (light) 0xFFD3D1C8.toInt() else 0xFF454541.toInt(), Shader.TileMode.CLAMP)
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(width / 112f, height / 118f)
            canvas.rotate(-12f, 56f, 59f)
            fun key(x: Float, y: Float, glyph: String, light: Boolean, shader: Shader) {
                paint.color = 0xFF181818.toInt()
                canvas.drawRoundRect(x, y + 5, x + 45, y + 45, 12f, 12f, paint)
                paint.shader = shader
                canvas.drawRoundRect(x, y, x + 45, y + 40, 12f, 12f, paint)
                paint.shader = null
                paint.color = if (light) ink else Color.WHITE
                paint.textSize = 20f; paint.textAlign = Paint.Align.CENTER
                paint.typeface = lettering
                canvas.drawText(glyph, x + 22.5f, y + 27, paint)
            }
            key(8f, 9f, "字", true, keyGradients[0])
            key(60f, 24f, "A", false, keyGradients[1])
            key(20f, 61f, "↵", false, keyGradients[2])
            canvas.restore()
        }
    }
}
