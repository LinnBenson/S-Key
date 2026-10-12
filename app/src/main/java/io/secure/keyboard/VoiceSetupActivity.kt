package io.secure.keyboard

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import io.secure.keyboard.settings.SettingsDialogs
import io.secure.keyboard.settings.SettingsScreen
import io.secure.keyboard.settings.applySettingsActivityTheme
import io.secure.keyboard.voice.ModelImportCoordinator
import io.secure.keyboard.voice.ModelImportTransfer
import io.secure.keyboard.voice.ModelRepository

/**
 * Explicit local-model setup; opening the external download page never grants this app network
 * access.
 */
class VoiceSetupActivity : Activity() {
    private lateinit var ui: SettingsScreen
    private var licenseDialog: AlertDialog? = null
    private var licenseScroll: ScrollView? = null
    private var licenseRestoreListener: ViewTreeObserver.OnPreDrawListener? = null
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var permissionButton: Button
    private val importObserver: (ModelImportCoordinator.State) -> Unit = { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySettingsActivityTheme()
        super.onCreate(savedInstanceState)
        title = "离线普通话语音"
        ui = SettingsScreen(this)
        val layout = ui.root("离线普通话语音", "‹ 返回") { finish() }
        ui.text(layout, "一次安装，随时离线使用", 15f)
        ui.card(layout, ui.tint).also {
            ui.text(it, "你的声音，留在设备内", 17f, true)
            ui.text(it, "不保存录音、不联网、不记录转写内容。麦克风数据仅在内存中短暂处理。")
            ui.text(it, "点击语音即开始，每次最多录音 60 秒。停顿后自动输入；取消会停止录音，已输入文字会保留。", 13f)
        }
        val setup = ui.card(layout)
        ui.text(setup, "1 · 准备普通话模型", 17f, true)
        ui.text(setup, "用浏览器下载约 25 MB 的指定压缩包，解压后选择同时包含 model.int8.onnx 和 tokens.txt 的文件夹。")
        ui.text(setup, "应用会核对两份文件的 SHA-256 校验值，并复制到应用私有空间。", 14f)
        var target = setup
        fun button(label: String, action: () -> Unit) =
            ui.button(target, label, label == "选择已解压的模型文件夹", action)
        button("用浏览器下载指定模型") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ModelRepository.DOWNLOAD_URL)))
            } catch (_: ActivityNotFoundException) {
                status.text = "没有可用浏览器，请在其他设备下载模型后传到本机。"
            }
        }
        importButton =
            button("选择已解压的模型文件夹") {
                if (ModelImportTransfer.state === ModelImportCoordinator.State.Running) return@button
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                        IMPORT_REQUEST,
                    )
                } catch (_: ActivityNotFoundException) {
                    status.text = "设备没有可用文件选择器。"
                }
            }
        importButton.tag = "voice_import_model"
        ui.text(setup, "指定版本：${ModelRepository.MODEL_ID}", 13f)
        target = ui.card(layout)
        ui.text(target, "2 · 开启麦克风", 17f, true)
        ui.text(target, "仅在你主动使用语音输入时录音。识别可能有误，请检查输入框中的文字。")
        permissionButton =
            button("允许麦克风权限") {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            }
        permissionButton.tag = "voice_microphone_permission"
        val progressCard = ui.card(layout, ui.tint)
        ui.text(progressCard, "设置状态", 17f, true)
        status = ui.text(progressCard, "")
        status.tag = "voice_status"
        status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        target = layout
        button("开源许可与声明") { showLicenses() }.tag = "voice_licenses"
        button("完成，返回") { finish() }
        refreshStatus()
        ui.restoreScroll(savedInstanceState?.getInt("voice_scroll") ?: 0)
        if (savedInstanceState?.getBoolean("voice_license_open") == true) {
            showLicenses(savedInstanceState.getInt("voice_license_scroll"))
        }
    }

    private fun showLicenses(scrollY: Int = 0) {
        if (licenseDialog?.isShowing == true) return
        val notices =
            listOf("SHERPA_ONNX_LICENSE", "ONNXRUNTIME_LICENSE", "ONNXRUNTIME_THIRD_PARTY_NOTICES")
                .joinToString("\n\n") { name ->
                    assets.open("voice-licenses/$name").bufferedReader().use { it.readText() }
                }
        val group = ui.column().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        ui.text(group, notices, 14f).setTextIsSelectable(true)
        val scroll = SettingsDialogs.scrollable(this, group)
        licenseScroll = scroll
        val dialog = SettingsDialogs.builder(this)
            .setTitle("离线语音开源声明")
            .setView(scroll)
            .setPositiveButton("关闭", null)
            .create()
        licenseDialog = dialog
        dialog.setOnDismissListener {
            if (scroll.viewTreeObserver.isAlive) {
                licenseRestoreListener?.let { scroll.viewTreeObserver.removeOnPreDrawListener(it) }
            }
            licenseRestoreListener = null
            licenseDialog = null
            licenseScroll = null
        }
        SettingsDialogs.show(dialog)
        val restore = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                licenseRestoreListener = null
                scroll.scrollTo(0, scrollY.coerceAtLeast(0))
                return true
            }
        }
        licenseRestoreListener = restore
        scroll.viewTreeObserver.addOnPreDrawListener(restore)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("voice_scroll", ui.scrollY())
        outState.putBoolean("voice_license_open", licenseDialog?.isShowing == true)
        outState.putInt("voice_license_scroll", licenseScroll?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        ModelImportTransfer.attach(importObserver)
    }

    override fun onStop() {
        ModelImportTransfer.detach(importObserver)
        super.onStop()
    }

    private fun refreshStatus() {
        val transfer = ModelImportTransfer.state
        importButton.isEnabled = transfer !== ModelImportCoordinator.State.Running
        val model = ModelRepository(applicationContext).isInstalled()
        val microphone =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        status.text = when (transfer) {
            ModelImportCoordinator.State.Running -> "正在复制和校验模型…"
            is ModelImportCoordinator.State.Failure -> transfer.message
            else ->
                "模型：${if (model) "已导入" else "未导入"}\n麦克风：${if (microphone) "已允许" else "未允许"}\n" +
                    if (model && microphone) "准备就绪。回到键盘点击语音即可开始。" else "请完成以上设置后返回键盘。"
        }
        permissionButton.isEnabled = !microphone
    }

    @Deprecated("Android activity callback retained for dependency-free minSdk 26 implementation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        // The process owner rejects duplicate picker results, including results from a reopened UI.
        // A result can precede onStart; that lifecycle callback will attach and render current state.
        ModelImportTransfer.start(applicationContext, uri)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MIC_REQUEST) refreshStatus()
    }

    override fun onDestroy() {
        ModelImportTransfer.detach(importObserver)
        licenseDialog?.dismiss()
        licenseDialog = null
        licenseScroll = null
        if (::ui.isInitialized) ui.dispose()
        super.onDestroy()
    }

    companion object {
        private const val IMPORT_REQUEST = 101
        private const val MIC_REQUEST = 102
    }
}
