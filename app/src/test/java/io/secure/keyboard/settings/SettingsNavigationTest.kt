package io.secure.keyboard.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun deepLinksHaveLogicalParentsAndRootFinishes() {
        SettingsNavigation.routes.forEach { route ->
            val navigation = SettingsNavigation()
            navigation.reset(route)
            if (route == "home") assertNull(navigation.back())
            else assertEquals(SettingsNavigation.parent(route), navigation.back())
            repeat(3) { navigation.back() }
            assertEquals("home", navigation.current)
            assertNull(navigation.back())
        }
    }

    @Test fun refreshDoesNotDuplicateHistoryAndReturningTruncatesIt() {
        val navigation = SettingsNavigation()
        navigation.navigate("layout")
        navigation.navigate(SettingsNavigation.profile(false, true))
        repeat(10) { navigation.navigate(SettingsNavigation.profile(false, true)) }
        assertEquals(listOf("home", "layout"), navigation.history)
        navigation.navigate("layout")
        assertEquals(listOf("home"), navigation.history)
        navigation.navigate("home")
        assertTrue(navigation.history.isEmpty())
    }

    @Test fun restorationUsesOnlyKnownRoutes() {
        val navigation = SettingsNavigation()
        navigation.restore("theme_dark", listOf("home", "unknown", "behavior", "behavior"))
        assertEquals("theme_dark", navigation.current)
        assertEquals("behavior", navigation.back())
        assertEquals("home", navigation.back())
        assertNull(navigation.back())
        navigation.restore("bad", listOf("layout"))
        assertEquals("home", navigation.current)
        assertTrue(navigation.history.isEmpty())
    }

    @Test fun newRequestDropsOldOffsetsAndExpandedHelp() {
        val navigation = SettingsNavigation()
        navigation.positions["phrases"] = SettingsNavigation.Position(250, "phrase_123", -4)
        navigation.expandedHelp.add("dictionary")
        navigation.reset("english_dictionary")
        assertEquals("english_dictionary", navigation.current)
        assertTrue(navigation.positions.isEmpty())
        assertTrue(navigation.expandedHelp.isEmpty())
        assertEquals(listOf("home"), navigation.history)
    }

    @Test fun recoveryOriginIsPreservedDuringInAppNavigation() {
        val navigation = SettingsNavigation()
        navigation.navigate("recovery")
        navigation.navigate("behavior")
        assertEquals("recovery", navigation.back())
    }
}
