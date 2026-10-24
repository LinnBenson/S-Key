package io.secure.keyboard

import io.secure.keyboard.diagnostics.checkedMain

/** Current Compose behavior, replacing pre-Compose View tags and fixed-light design assumptions. */
@Suppress("DEPRECATION")
class SettingsRedesignScreensTest : ComposeSettingsTest() {
    fun testInputSwitchesPersistAcrossRecreationAndRemainIndependent() {
        open("input")
        assertTrue(fixture.config.editorPreview); assertTrue(fixture.config.autoChineseSpacing)
        click("输入框预览"); assertFalse(fixture.config.editorPreview)
        assertTrue(fixture.config.autoChineseSpacing)
        click("自动联想"); assertFalse(fixture.config.chineseAssociation)
        recreate(); await("输入框预览")
        assertFalse(fixture.config.editorPreview)
        open("input_english"); click("自动联想")
        assertFalse(fixture.config.englishAssociation)
        assertTrue(fixture.config.englishCompletion)
    }
    fun testThemeChoicesPersistAndFollowSystem() {
        open("behavior"); click("黑色主题"); assertEquals("dark", fixture.config.theme)
        click("跟随系统"); assertEquals("system", fixture.config.theme)
        assertTrue(fixture.config.isDark(true)); assertFalse(fixture.config.isDark(false))
        click("白色主题"); recreate(); await("白色主题")
        assertEquals("light", fixture.config.theme)
    }
    fun testClipboardPoliciesKeepPinsAndDoNotChangeLimit() {
        fixture.content.addClipboard("pin"); fixture.content.setClipboardPinned(fixture.content.clipboard().single().id, true)
        fixture.content.addClipboard("temporary")
        open("clipboard"); await("定时清理")
        assertEquals(24, fixture.config.clipboardRetentionHours)
        assertEquals(10, fixture.config.clipboardLimit)
        click("不保存历史")
        waitFor("Unpinned history removed") { fixture.content.clipboard().size == 1 }
        assertEquals("pin", fixture.content.clipboard().single().text)
        click("长期保留"); waitFor("Policy saved") { fixture.config.clipboardRetention == "forever" }
        assertEquals(10, fixture.config.clipboardLimit)
    }
    fun testClipboardPolicyDispatchDuringLoadKeepsLastChoiceAndPurgesHistory() {
        fixture.content.addClipboard("private pending policy fixture")
        open("clipboard")
        lateinit var model: io.secure.keyboard.settings.SettingsModel
        instrumentation.checkedMain {
            model = androidx.lifecycle.ViewModelProvider(fixture.current as SettingsActivity)[io.secure.keyboard.settings.SettingsModel::class.java]
            // Deterministic same-frame dispatch: the first cleanup is still queued when
            // later choices arrive. No sleeps or accessibility recomposition assumptions.
            model.setClipboardRetention("forever")
            model.setClipboardRetention("timed")
            model.setClipboardRetention("none")
            assertEquals("none", fixture.config.clipboardRetention)
        }
        waitFor("All dispatched policy cleanups complete") {
            var idle = false
            instrumentation.checkedMain { idle = !model.busy }
            idle
        }
        // Read ciphertext through the authenticated file layer, not clipboard(), which
        // itself trims and could conceal a missing background cleanup.
        val file = java.io.File(fixture.context.noBackupFilesDir, "private-content/clipboard.enc")
        assertEquals(0, org.json.JSONArray(io.secure.keyboard.data.EncryptedContentFile(file).read()).length())
        assertEquals("none", fixture.config.clipboardRetention)
    }

    fun testFullPhraseEditorCancelSaveAndDelete() {
        val full = "长内容🙂\n".repeat(40)
        fixture.content.addPhrase(full)
        open("phrases"); click("编辑"); await("保存")
        assertEquals(full, nodes().first { it.isEditable }.text.toString())
        fill(0, "cancelled"); click("取消"); assertEquals(full, fixture.content.phrases().single().text)
        click("编辑"); await("保存"); fill(0, "已保存🙂"); click("保存")
        waitFor("Phrase updated") { fixture.content.phrases().single().text == "已保存🙂" }
        click("删除"); click("确认")
        waitFor("Phrase deleted") { fixture.content.phrases().isEmpty() }
    }
    fun testRepeatedDialogCancellationAndRecreationReleaseDrafts() {
        open("phrases")
        repeat(10) {
            click("添加常用语"); await("保存"); fill(0, "discard-$it"); click("取消")
            await("添加常用语")
            waitFor("Dialog window dismissed before recreation") { fixture.current?.hasWindowFocus() == true }
            recreate(); await("添加常用语")
            assertTrue(fixture.content.phrases().isEmpty())
        }
        assertTrue(fixture.destroyedCount >= 10)
    }
    fun testDeepLinkAndBackUseLogicalParent() {
        open("input_english"); await("英文单词补全")
        instrumentation.checkedMain { (fixture.current as SettingsActivity).onBackPressedDispatcher.onBackPressed() }
        await("输入方式")
    }
}
