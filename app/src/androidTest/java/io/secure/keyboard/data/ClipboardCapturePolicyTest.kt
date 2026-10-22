package io.secure.keyboard.data

import android.content.ClipData
import android.os.PersistableBundle
import android.test.AndroidTestCase

@Suppress("DEPRECATION")
class ClipboardCapturePolicyTest : AndroidTestCase() {
    fun testDeletedMatchIsExactAndPreservesOtherClipboardKinds() {
        val deleted = setOf("same\ntext")
        assertTrue(ClipboardCapturePolicy.matchesDeleted(ClipData.newPlainText("", "same\ntext"), deleted))
        assertFalse(ClipboardCapturePolicy.matchesDeleted(ClipData.newPlainText("", "same text"), deleted))
        assertFalse(ClipboardCapturePolicy.matchesDeleted(ClipData.newPlainText("", "new copy"), deleted))
        val multiple = ClipData.newPlainText("", "same\ntext")
        multiple.addItem(ClipData.Item("other"))
        assertFalse(ClipboardCapturePolicy.matchesDeleted(multiple, deleted))
        val uri = ClipData.newRawUri("", android.net.Uri.parse("content://test/item"))
        assertFalse(ClipboardCapturePolicy.matchesDeleted(uri, deleted))
    }

    fun testSensitiveCopyIsRejectedEvenWithoutPasswordEditor() {
        val clip = ClipData.newPlainText("fixture", "test-secret")
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        assertNull(ClipboardCapturePolicy.textForHistory(clip))
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", false)
        }
        assertEquals("test-secret", ClipboardCapturePolicy.textForHistory(clip))
    }
    fun testOrdinaryCopyIsPreservedAndOverlongCopyIsNotTruncated() {
        val text = "🙂\n原文".repeat(100)
        assertEquals(text, ClipboardCapturePolicy.textForHistory(ClipData.newPlainText("", text)))
        val boundary = "x".repeat(ContentStore.MAX_CLIPBOARD_ITEM_CHARACTERS)
        assertEquals(boundary, ClipboardCapturePolicy.textForHistory(ClipData.newPlainText("", boundary)))
        assertNull(ClipboardCapturePolicy.textForHistory(ClipData.newPlainText("", boundary + "x")))
    }
}
