package io.secure.keyboard.data

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase

@Suppress("DEPRECATION")
class LetterSymbolStoreTest : AndroidTestCase() {
    fun testLanguageIsolationEmptyOverrideUnicodeAndRestore() {
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("letter-symbol-test-$name", mode)
        }
        val prefs = isolated.getSharedPreferences("settings", 0)
        prefs.edit().clear().commit()
        try {
            val store = LetterSymbolStore(isolated)
            assertEquals("1", store.symbols(true, 'q'))
            assertEquals("“”", store.symbols(true, 'k'))
            assertEquals(")", store.symbols(false, 'k'))
            store.save(true, 'k', "🙂《》")
            store.save(false, 'k', "")
            assertEquals("🙂《》", LetterSymbolStore(isolated).symbols(true, 'k'))
            assertEquals("", store.symbols(false, 'k'))
            for (invalid in listOf("\n", "a b", "123456789", "\uD800")) {
                try { store.save(true, 'k', invalid); fail("Invalid alternative accepted") }
                catch (_: IllegalArgumentException) {}
                assertEquals("🙂《》", store.symbols(true, 'k'))
            }
            store.restore(true)
            assertEquals("“”", store.symbols(true, 'k'))
            assertEquals("", store.symbols(false, 'k'))
        } finally { prefs.edit().clear().commit() }
    }
}
