package io.secure.keyboard

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import io.secure.keyboard.ime.KeyboardSession

/** Android lifecycle adapter. Mutable input and feature behavior live in the session/controllers. */
class OfflineKeyboardService : InputMethodService() {
    internal lateinit var session: KeyboardSession
        private set
    override fun onCreate() { super.onCreate(); session=KeyboardSession(this); session.onCreate() }
    override fun onEvaluateFullscreenMode() = false
    override fun onCreateInputView() = session.onCreateInputView()
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute,restarting); session.onStartInput(attribute,restarting)
    }
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info,restarting); session.onStartInputView(info,restarting)
    }
    override fun onFinishInputView(finishingInput: Boolean) { session.onFinishInputView(finishingInput); super.onFinishInputView(finishingInput) }
    override fun onFinishInput() { session.onFinishInput(); super.onFinishInput() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); session.onConfigurationChanged(newConfig) }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (::session.isInitialized) session.onTrimMemory(level) }
    override fun onDestroy() { session.onDestroy(); super.onDestroy() }
    override fun onUpdateSelection(oldSelStart: Int,oldSelEnd: Int,newSelStart: Int,newSelEnd: Int,candidatesStart: Int,candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
        session.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd)
    }
    override fun onComputeInsets(outInsets: Insets) { super.onComputeInsets(outInsets); session.onComputeInsets(outInsets) }
}
