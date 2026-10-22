package io.secure.keyboard

import io.secure.keyboard.ime.KeyboardSession
import io.secure.keyboard.ime.InputState

/** Test-only adapter for assertions that previously inspected private service implementation. */
object SessionTestAccess {
    private fun session(owner: Any): KeyboardSession = when(owner) {
        is OfflineKeyboardService -> owner.session
        is KeyboardSession -> owner
        else -> error("Expected keyboard service/session")
    }
    fun field(name: String) = Field(name)
    fun method(name: String,vararg types: Class<*>?) = Method(name,types)
    class Field(private val name: String) {
        var isAccessible: Boolean=true
        private val input=InputState::class.java.declaredFields.any { it.name==name }
        private val field=(if(input) InputState::class.java else KeyboardSession::class.java).getDeclaredField(name).apply { isAccessible=true }
        private fun target(owner: Any): Any = session(owner).let { if(input) it.inputState else it }
        fun get(owner: Any): Any? = field.get(target(owner))
        fun set(owner: Any,value: Any?) = field.set(target(owner),value)
        fun setBoolean(owner: Any,value: Boolean) = field.setBoolean(target(owner),value)
    }
    class Method(name: String,types: Array<out Class<*>?>) {
        var isAccessible: Boolean=true
        private val method=KeyboardSession::class.java.getDeclaredMethod(name,*types).apply { isAccessible=true }
        fun invoke(owner: Any,vararg args: Any?): Any? = method.invoke(session(owner),*args)
    }
}
