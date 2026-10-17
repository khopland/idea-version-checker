package io.github.khopland.versionchecker

import org.junit.Assert.*
import org.junit.Test

class UpdateSessionTest {
    @Test fun `late outcomes belong to the originating session and expired tokens are ignored`() {
        val store = UpdateSessionStore(2)
        val first = store.start(10, "First declaration", setOf("maven"))
        val second = store.start(11, "Second declaration", setOf("maven", "npm"))
        store.record(first, "maven", "Reload cancelled after request")
        assertEquals("Reload cancelled after request", store.get(first)!!.followUps["maven"])
        assertTrue(store.get(second)!!.followUps.isEmpty())
        store.start(null, "Third declaration", setOf("gradle"))
        store.record(first, "maven", "Late completion")
        assertNull(store.get(first))
        assertTrue(store.get(second)!!.followUps.isEmpty())
    }

    @Test fun `completed followup leaves verification unknown and declaration review available`() {
        val store = UpdateSessionStore()
        val token = store.start(42, "Declarations applied", setOf("npm"))
        store.record(token, "npm", "Command completed; lockfile changed")
        val result = store.get(token)!!
        assertEquals(42L, result.diffToken)
        assertTrue(result.summary().contains("Verification: Unknown"))
        assertTrue(result.summary().contains("Declarations applied"))
        assertTrue(result.summary().contains("Cancellation does not undo"))
        store.record(token, "npm", "Expected version confirmed", verification = true)
        assertTrue(store.get(token)!!.summary().contains("Verification: Expected version confirmed"))
    }

    @Test fun `retention limits text and providers and clear removes all sessions`() {
        val store = UpdateSessionStore()
        val token = store.start(null, "a".repeat(5000), (1..100).map { "provider$it" }.toSet())
        assertEquals(16, store.get(token)!!.providers.size)
        assertEquals(4096, store.get(token)!!.declarationNotice.length)
        store.record(token, "provider1", "b".repeat(10000))
        store.record(token, "unrelated", "Ignored")
        assertEquals(8192, store.get(token)!!.followUps.getValue("provider1").length)
        assertEquals(1, store.get(token)!!.followUps.size)
        store.clear()
        assertNull(store.latest())
        assertNull(store.get(token))
    }
}
