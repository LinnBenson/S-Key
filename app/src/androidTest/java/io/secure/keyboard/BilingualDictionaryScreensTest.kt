package io.secure.keyboard

import io.secure.keyboard.data.DictionaryKind

/** Current custom dictionary CRUD. SAF transfers are covered by DictionaryImportLifecycleTest. */
@Suppress("DEPRECATION")
class BilingualDictionaryScreensTest : ComposeSettingsTest() {
    fun testIndependentPagesAndRotationKeepTheirOwnValues() {
        fixture.content.addDictionary("中文测试", "shared")
        fixture.content.addDictionary("English One", "shared", DictionaryKind.ENGLISH)
        open("dictionary"); reveal("中文测试"); assertNull(find("English One"))
        recreate(); reveal("中文测试")
        open("english_dictionary"); reveal("English One"); assertNull(find("中文测试"))
        recreate(); reveal("English One")
    }
    fun testEnglishLiteralAddEditAndScopedClear() {
        fixture.content.addDictionary("保留中文", "keep")
        open("english_dictionary"); click("添加词条"); await("保存")
        fill(0, "eBay Support 😀"); fill(1, "HELP"); click("保存")
        waitFor("English entry saved") { fixture.content.dictionary(DictionaryKind.ENGLISH).size == 1 }
        reveal("eBay Support 😀"); click("编辑"); await("保存")
        assertEquals("eBay Support 😀", nodes().first { it.isEditable }.text.toString())
        fill(0, "eBay Help 😀"); click("保存")
        waitFor("Literal edit saved") { fixture.content.dictionary(DictionaryKind.ENGLISH).single().word == "eBay Help 😀" }
        open("english_dictionary"); click("清空本词库"); click("确认")
        waitFor("Only English cleared") { fixture.content.dictionary(DictionaryKind.ENGLISH).isEmpty() }
        assertEquals("保留中文", fixture.content.dictionary().single().word)
    }
    fun testInvalidEnglishCodeCannotSave() {
        open("english_dictionary"); click("添加词条"); await("保存")
        fill(0, "Invalid Code Fixture"); fill(1, "has space"); click("保存")
        await("英文编码须为 1–40 个英文字母（A–Z 或 a–z），不能包含空格或其他字符")
        assertTrue(fixture.content.dictionary(DictionaryKind.ENGLISH).isEmpty())
    }
    fun testCancelledAddAndDeleteNeverMutateOtherLanguage() {
        fixture.content.addDictionary("保留中文", "keep")
        fixture.content.addDictionary("Keep English", "keep", DictionaryKind.ENGLISH)
        open("dictionary"); click("添加词条"); await("保存")
        fill(0, "取消内容"); fill(1, "cancel"); click("取消")
        assertEquals(1, fixture.content.dictionary().size)
        click("删除"); click("取消"); assertEquals(1, fixture.content.dictionary().size)
        click("删除"); click("确认")
        waitFor("Chinese deleted") { fixture.content.dictionary().isEmpty() }
        assertEquals("Keep English", fixture.content.dictionary(DictionaryKind.ENGLISH).single().word)
    }
}
