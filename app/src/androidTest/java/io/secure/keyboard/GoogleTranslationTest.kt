package io.secure.keyboard
import android.test.InstrumentationTestCase
import io.secure.keyboard.data.ApiConfigurationStore
import io.secure.keyboard.translation.GoogleTranslation
@Suppress("DEPRECATION")
class GoogleTranslationTest : InstrumentationTestCase() {
    fun testRequestUsesPlainTextAndAutoDetection() {
        val request = GoogleTranslation.requestBody("你好", "auto", "en", "nmt")
        assertFalse(request.has("source")); assertEquals("text", request.getString("format"))
        assertEquals("你好", request.getString("q"))
        assertEquals("zh-CN", GoogleTranslation.requestBody("你好", "zh-CN", "en", "nmt").getString("source"))
    }
    fun testMissingCredentialFailsBeforeNetwork() {
        try {
            GoogleTranslation.translate(io.secure.keyboard.data.ApiConfiguration(), "你好", "zh-CN", "en")
            fail("Missing credentials must fail closed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("密钥"))
        }
    }
    fun testConfiguredGoogleTranslation() {
        val config = ApiConfigurationStore(instrumentation.targetContext).load()
        if (config.googleKey.isBlank()) {
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("environment_skip", "Google live integration: no configured credential; offline request/policy tests still run")
            })
            return
        }
        val result = GoogleTranslation.translate(config, "你好", "zh-CN", "en")
        assertTrue("应返回非空译文", result.isNotBlank())
        assertFalse("译文应不同于原文", result == "你好")
    }
}
