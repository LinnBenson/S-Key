package io.secure.keyboard.engine

import java.util.Locale

/** Read-only system entries. Exact codes only; independent of user content and its lifecycle. */
object BuiltinExactDictionary {
    fun lookup(code: String): String? = when (code.lowercase(Locale.ROOT)) {
        "useskey" -> "由 Linn Benson 开发的输入法"
        else -> null
    }
}
