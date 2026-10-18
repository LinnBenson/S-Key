package io.secure.keyboard.ai

import org.junit.Assert.*
import org.junit.Test

class AiConversationTest {
    @Test fun expiryBoundaryAndClockRollback() {
        val c=AiConversation("8e6c_test","scope",1000,"polish")
        assertTrue(c.valid(1000)); assertTrue(c.valid(1000+AiConversation.TTL-1))
        assertFalse(c.valid(1000+AiConversation.TTL)); assertFalse(c.valid(999))
        assertFalse(c.valid(1001,"other"))
        assertTrue(c.copy(updatedAt=2000).valid(1000+AiConversation.TTL))
    }
    @Test fun typePromptAndEndpointIdentityChangeScope() {
        val scope=AiConversation.scope("endpoint","custom:a","prompt")
        assertNotEquals(scope,AiConversation.scope("endpoint","custom:b","prompt"))
        assertNotEquals(scope,AiConversation.scope("endpoint","custom:a","edited"))
        assertNotEquals(scope,AiConversation.scope("other","custom:a","prompt"))
        assertEquals(scope,AiConversation.scope("endpoint","custom:a","prompt"))
    }
}
