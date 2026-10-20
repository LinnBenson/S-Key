package io.secure.keyboard.data

import org.junit.Assert.*
import org.junit.Test

class ClipboardWebLinkTest {
    @Test fun acceptsWebAddressesInsideText() {
        assertEquals("https://example.com/a?q=1#part", ClipboardWebLink.parse("看看https://example.com/a?q=1#part 这个页面"))
        assertEquals("http://example.com", ClipboardWebLink.parse("http://example.com"))
        assertEquals("https://www.example.com", ClipboardWebLink.parse("地址：www.example.com。"))
    }
    @Test fun selectsFirstLinkIncludingMarkdownLabels() {
        assertEquals("https://first.example", ClipboardWebLink.parse("[https://first.example](https://second.example)"))
        assertEquals("https://first.example", ClipboardWebLink.parse("https://first.example\nhttps://second.example"))
        assertEquals("https://example.com/path_(one)", ClipboardWebLink.parse("[label](https://example.com/path_(one))"))
    }
    @Test fun ignoresSurroundingPunctuation() {
        assertEquals("https://example.com", ClipboardWebLink.parse("请看（https://example.com），谢谢"))
        assertEquals("https://example.com", ClipboardWebLink.parse("See (https://example.com)."))
    }
    @Test fun normalizesSchemeAndStopsAtUnicodeWhitespace() {
        assertEquals("https://example.com", ClipboardWebLink.parse("HTTPS://example.com\u3000后面的文字"))
        assertEquals("http://example.com", ClipboardWebLink.parse("HTTP://example.com"))
    }
    @Test fun rejectsNonWebOrInvalidAddresses() {
        for (text in listOf("plain text", "javascript:alert(1)", "file:///sdcard/test", "intent://example", "https://", "https://example.com:99999")) {
            assertNull(text, ClipboardWebLink.parse(text))
        }
    }
}
