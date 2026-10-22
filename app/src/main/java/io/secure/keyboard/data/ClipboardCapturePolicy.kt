package io.secure.keyboard.data

import android.content.ClipData

/** Inspect metadata and length before allocating or queueing a full text copy. */
internal object ClipboardCapturePolicy {
    fun matchesDeleted(clip: ClipData, removed: Set<String>): Boolean =
        clip.itemCount == 1 && clip.getItemAt(0).let { item ->
            item.uri == null && item.intent == null && item.text?.toString() in removed
        }

    fun textForHistory(clip: ClipData): String? {
        if (clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true)
            return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).text ?: return null
        if (text.isEmpty() || text.length > ContentStore.MAX_CLIPBOARD_ITEM_CHARACTERS) return null
        return text.toString()
    }
}
