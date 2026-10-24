package io.secure.keyboard

@Suppress("DEPRECATION")
class CompanionScreensTest : ComposeSettingsTest() {
    fun testThemeEditorsAndCurrentDictionaryNavigation() {
        open("behavior"); click("自定义白色主题"); await("主题预览")
        recreate(); await("主题预览")
        open("behavior"); click("自定义黑色主题"); await("主题预览")
        open("home"); click("中文自定义词库"); await("添加词条")
        open("home"); click("英文自定义词库"); await("添加词条")
    }
}
