package io.secure.keyboard.data

import org.json.JSONObject

/** Bound recursive platform JSON parsing before constructing any response tree. */
internal object NetworkJson {
    fun objectValue(text: String): JSONObject {
        validate(text)
        return JSONObject(text)
    }

    internal fun validate(text: String) {
        require(text.length <= 1024 * 1024) { "接口响应过大" }
        val stack = CharArray(64)
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
                continue
            }
            when (c) {
                '"' -> quoted = true
                '{', '[' -> {
                    require(depth < stack.size) { "接口响应嵌套过深" }
                    stack[depth++] = c
                }
                '}', ']' -> {
                    require(depth > 0 && stack[depth - 1] == if (c == '}') '{' else '[') { "接口响应格式错误" }
                    depth--
                }
                // JSONObject accepts nonstandard quotes/comments. Reject these before
                // parsing so they cannot hide nesting from the structural scan.
                '\'', '/', '#' -> throw IllegalArgumentException("接口响应格式错误")
            }
        }
        require(depth == 0 && !quoted) { "接口响应格式错误" }
    }
}
