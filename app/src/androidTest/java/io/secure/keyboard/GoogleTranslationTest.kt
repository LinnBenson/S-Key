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
    fun testConfiguredGoogleTranslation() {
        val config = ApiConfigurationStore(instrumentation.targetContext).load()
        assertTrue("请先保存 Google 密钥", config.googleKey.isNotBlank())
        val result = GoogleTranslation.translate(config, "你好", "zh-CN", "en")
        assertTrue("应返回非空译文", result.isNotBlank())
        assertFalse("译文应不同于原文", result == "你好")
    }
}
