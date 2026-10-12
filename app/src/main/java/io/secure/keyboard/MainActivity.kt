package io.secure.keyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import io.secure.keyboard.settings.SettingsInputs
import io.secure.keyboard.settings.SettingsScreen
import io.secure.keyboard.settings.applySettingsActivityTheme

/** Launcher/setup belongs to the same fixed-light companion shell as every settings page. */
class MainActivity : Activity() {
    private lateinit var ui: SettingsScreen
    private lateinit var testInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        applySettingsActivityTheme()
        super.onCreate(savedInstanceState)
        ui = SettingsScreen(this)
        val body = ui.root("S Key")
        ui.text(body, "五笔 86 · 拼音 · 英文", 15f)
        ui.infoCard(body, "安心输入，只在本机", "没有联网权限，不记录输入历史。")
        ui.section(body, "开始使用").also { group ->
            ui.button(group, "1 · 启用S Key", true) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }.tag = "launcher_enable_keyboard"
            ui.button(group, "2 · 切换输入法") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }.tag = "launcher_choose_keyboard"
            ui.text(group, "系统会显示第三方键盘通用风险提醒，请仅安装你信任的版本。", 14f)
        }
        ui.card(body).also { group ->
            testInput = SettingsInputs.field(this, "在这里试打：中文、英文、表情").apply {
                id = R.id.settings_launcher_test_input
                tag = "launcher_test_input"
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
                isSaveEnabled = false
            }
            SettingsInputs.labeled(group, "试试你的键盘", testInput)
        }
        ui.section(body, "按你的习惯设置").also { group ->
            ui.button(group, "设置与内容管理") { startActivity(Intent(this, SettingsActivity::class.java)) }
                .tag = "launcher_settings"
            ui.button(group, "安装离线语音模型") { startActivity(Intent(this, VoiceSetupActivity::class.java)) }
                .tag = "launcher_voice"
        }
        ui.restoreScroll(savedInstanceState?.getInt("launcher_scroll") ?: 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("launcher_scroll", ui.scrollY())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::ui.isInitialized) ui.dispose()
        super.onDestroy()
    }
}
