package io.secure.keyboard.data

import android.test.InstrumentationTestCase

/** Android uses ICU regex, unlike the JVM unit-test runtime. */
@Suppress("DEPRECATION")
class ClipboardWebLinkAndroidTest : InstrumentationTestCase() {
    fun testAndroidRegexAndUriCompatibility() {
        assertEquals("https://example.com", ClipboardWebLink.parse("HTTPS://example.com\u3000后面的文字"))
        assertEquals("https://first.example", ClipboardWebLink.parse("[https://first.example](https://second.example)"))
        assertEquals("https://www.example.com", ClipboardWebLink.parse("地址：www.example.com。"))
        assertNull(ClipboardWebLink.parse("ordinary text"))
    }
}
