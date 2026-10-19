package io.secure.keyboard.data

internal object AiReasoning {
    val options = linkedMapOf("low" to "低", "medium" to "中", "high" to "高", "xhigh" to "最高")
    fun valid(value: String) = value in options
}
