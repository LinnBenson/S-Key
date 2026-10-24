package io.secure.keyboard

import android.content.Context

/** Older synchronous interaction fixtures opt out explicitly; default/spacing suites test opt-in. */
internal class ExplicitInputPreferences(context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("settings", 0)
    private val values = listOf("editorPreview", "autoChineseSpacing", "chineseAssociation", "englishAssociation")
        .associateWith { if (prefs.contains(it)) prefs.getBoolean(it, true) else null }
    init { prefs.edit().putBoolean("editorPreview", false).putBoolean("autoChineseSpacing", false)
        .putBoolean("chineseAssociation", true).putBoolean("englishAssociation", true).commit() }
    override fun close() { prefs.edit().apply { values.forEach { (key, value) ->
        if (value == null) remove(key) else putBoolean(key, value)
    } }.commit() }
}
