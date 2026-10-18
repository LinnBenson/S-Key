package io.secure.keyboard.ai

import android.test.InstrumentationTestCase
import io.secure.keyboard.data.AiConversationStore
import java.io.File

@Suppress("DEPRECATION")
class AiConversationStoreTest: InstrumentationTestCase() {
    fun testEncryptedRoundTripAndCorruptionFailClosed() {
        val context=instrumentation.targetContext
        val store=AiConversationStore(context)
        val prior=store.load()
        try {
            val value=AiConversation("8e6c_sensitive_test","scope-test",System.currentTimeMillis(),"custom:private-type")
            store.save(value)
            val file=File(context.noBackupFilesDir,"ai_conversation.enc")
            assertTrue(file.exists())
            val raw=String(file.readBytes(),Charsets.ISO_8859_1)
            assertFalse(raw.contains(value.id));assertFalse(raw.contains(value.type));assertFalse(raw.contains(value.scope))
            assertEquals(value,AiConversationStore(context).load())
            val snapshot=store.snapshot();assertEquals(value,snapshot.first)
            val revision=snapshot.second;store.clear();assertTrue(store.revision()>revision);assertNull(store.load())
            try { store.save(value,revision);fail("A reset must revoke an outstanding save") } catch(_:IllegalStateException) {}
            assertNull(store.load())
            store.save(value)
            val bytes=file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();file.writeBytes(bytes)
            assertNull(store.load());assertFalse(file.exists())
        } finally { store.clear();if(prior!=null)store.save(prior) }
    }
}
