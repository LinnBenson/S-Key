package io.secure.keyboard.settings

/** UI-only navigation. Holds small values, never an Activity, view, content list or save callback. */
class SettingsNavigation {
    data class Position(val offset: Int = 0, val anchor: String? = null, val anchorOffset: Int = 0, val focus: String? = null)

    var current: String = HOME
        private set
    private val stack = mutableListOf<String>()
    val positions = mutableMapOf<String, Position>()
    val expandedHelp = mutableSetOf<String>()
    val history: List<String> get() = stack.toList()

    fun navigate(destination: String) {
        val next = valid(destination)
        if (next == current) return
        if (next == HOME) stack.clear()
        else if (next in stack) {
            while (stack.isNotEmpty() && stack.last() != next) stack.removeAt(stack.lastIndex)
            if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
        } else stack.add(current)
        current = next
    }

    fun back(): String? {
        if (stack.isNotEmpty()) current = stack.removeAt(stack.lastIndex)
        else current = parent(current) ?: return null
        return current
    }

    /** A new external request starts with logical parents and no stale page or dialog state. */
    fun reset(destination: String) {
        current = valid(destination)
        stack.clear()
        positions.clear()
        expandedHelp.clear()
        var ancestor = parent(current)
        while (ancestor != null) {
            stack.add(0, ancestor)
            ancestor = parent(ancestor)
        }
    }

    fun restore(route: String?, history: List<String>) {
        current = valid(route)
        stack.clear()
        history.filter { it in routes && it != current }.distinct().forEach(stack::add)
        if (current == HOME) stack.clear()
    }

    companion object {
        const val HOME = "home"
        const val RECOVERY = "recovery"
        val routes = setOf(HOME, "behavior", "layout", "toolbar", "clipboard", "phrases",
            "dictionary", "english_dictionary", "emoji", "theme_light", "theme_dark",
            "profile_outer_portrait", "profile_outer_landscape", "profile_inner_portrait",
            "profile_inner_landscape", RECOVERY)

        fun valid(route: String?): String = route?.takeIf { it in routes } ?: HOME
        fun profile(outer: Boolean, landscape: Boolean) =
            "profile_${if (outer) "outer" else "inner"}_${if (landscape) "landscape" else "portrait"}"
        fun parent(route: String): String? = when {
            route == HOME -> null
            route.startsWith("profile_") -> "layout"
            route == "theme_light" || route == "theme_dark" -> "behavior"
            else -> HOME
        }
    }
}
