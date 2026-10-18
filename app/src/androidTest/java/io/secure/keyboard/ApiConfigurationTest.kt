package io.secure.keyboard

import android.test.InstrumentationTestCase
import io.secure.keyboard.data.ApiConfiguration
import io.secure.keyboard.data.ApiConfigurationStore

@Suppress("DEPRECATION")
class ApiConfigurationTest : InstrumentationTestCase() {
    fun testEncryptedRoundTripAndClear() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("api_configuration", 0)
        val original = prefs.getString("encrypted", null)
        try {
            val config = ApiConfiguration(googleKey = "test-google-secret", grokKey = "test-grok-secret", grokModel = "test-model")
            val store = ApiConfigurationStore(context)
            store.save(config)
            assertEquals(config, ApiConfigurationStore(context).load())
            val raw = prefs.getString("encrypted", "")!!
            assertFalse(raw.contains("test-google-secret"))
            assertFalse(raw.contains("test-grok-secret"))
            store.clear()
            assertEquals(ApiConfiguration(), store.load())
        } finally {
            if (original == null) prefs.edit().clear().commit()
            else prefs.edit().putString("encrypted", original).commit()
        }
    }
    fun testEndpointValidationRejectsUnsafeUrls() {
        for (url in listOf("http://api.x.ai/v1", "https://user:secret@api.x.ai/v1", "https://api.x.ai/v1?key=secret", "not-a-url")) {
            try { ApiConfiguration(grokUrl = url).validated(); fail("URL accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("https://api.x.ai/v1", ApiConfiguration(grokUrl = " https://api.x.ai/v1/ ").validated().grokUrl)
    }
}
