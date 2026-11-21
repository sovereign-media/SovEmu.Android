package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientEventsTest {
    @Test
    fun eachDistinctEventIsSentOnceInBatchesTheServerTakes() {
        val q = ClientEventQueue { 1.0 }
        assertTrue(q.add("CONNECTION_FAILED", "ICE failed", "abc"))
        assertFalse("the same fault again", q.add("CONNECTION_FAILED", "ICE failed", "abc"))
        assertEquals(listOf(listOf(ClientEvent("CONNECTION_FAILED", "ICE failed", "abc", 1.0))), q.take())
        repeat(25) { q.add("OTHER", "event $it") }
        assertEquals(listOf(20, 5), q.take().map { it.size })
        assertEquals(emptyList<List<ClientEvent>>(), q.take())
    }

    @Test
    fun longMessagesAreCut() {
        val q = ClientEventQueue { 0.0 }
        q.add("OTHER", "x".repeat(1000))
        assertEquals(ClientEventQueue.MAX_MESSAGE, q.take().single().single().message.length)
    }
}
