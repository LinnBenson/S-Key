package io.secure.keyboard

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import io.secure.keyboard.diagnostics.checkedMain

@Suppress("DEPRECATION")
class SettingsContentPagingTest : InstrumentationTestCase() {
    fun testManagementPagesBoundViewsAndKeepAllRecords() {
        SettingsUiFixture(instrumentation).use { fixture ->
            fixture.config.clipboardLimit = 1000
            repeat(101) { fixture.content.addClipboard("copy-$it"); fixture.content.addPhrase("phrase-$it") }
            for (route in listOf("clipboard", "phrases")) {
                val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
                    .putExtra("page", route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
                instrumentation.waitForIdleSync()
                instrumentation.checkedMain {
                    fun all(view: View): List<View> = listOf(view) +
                        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
                    fun entries() = all(activity.window.decorView).filter {
                        it.tag?.toString()?.startsWith(if (route == "clipboard") "anchor:clipboard:" else "anchor:phrase:") == true
                    }
                    assertEquals(100, entries().size)
                    all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "下一页" }.performClick()
                    assertEquals(1, entries().size)
                    all(activity.window.decorView).filterIsInstance<Button>().single { it.text == "上一页" }.performClick()
                    assertEquals(100, entries().size)
                    activity.finish()
                }
                instrumentation.waitForIdleSync()
            }
            assertEquals(101, fixture.content.clipboard().size)
            assertEquals(101, fixture.content.phrases().size)
            fixture.assertActualDataUnchanged()
        }
    }
}
