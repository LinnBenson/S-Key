package io.secure.keyboard

/** LazyColumn replaces the retired numbered-page controls. */
@Suppress("DEPRECATION")
class SettingsContentPagingTest : ComposeSettingsTest() {
    fun testManagementListsVirtualizeAndKeepAllPermittedRecords() {
        fixture.config.clipboardLimit = 100
        repeat(101) { fixture.content.addClipboard("copy-$it"); fixture.content.addPhrase("phrase-$it") }
        open("phrases"); reveal("phrase-0")
        assertTrue("Only visible semantics are materialized", nodes().count { it.text?.toString()?.startsWith("phrase-") == true } < 40)
        reveal("phrase-100")
        assertEquals(101, fixture.content.phrases().size)
        assertEquals(100, fixture.content.clipboard().size)
        open("clipboard"); reveal("copy-100")
        assertEquals("Unpinned oldest entry is evicted", "copy-1", fixture.content.clipboard().first().text)
    }
}
