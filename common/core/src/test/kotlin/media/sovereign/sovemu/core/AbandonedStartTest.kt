package media.sovereign.sovemu.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import media.sovereign.sovemu.core.graphql.type.StartPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A start the app gave up on is stopped once the server has it ready, and only then. */
@OptIn(ExperimentalCoroutinesApi::class)
class AbandonedStartTest {
    private class FakeStarts(
        private val phases: List<StartPhase?>,
        private val current: SessionInfo? = session(7),
    ) : StartService {
        var asked = 0
        val stopped = mutableListOf<String>()
        override suspend fun startProgress(startId: String): StartStatus {
            val phase = phases[minOf(asked++, phases.lastIndex)] ?: error("not found: start")
            return StartStatus(phase, null, null, 0.0, 0.0, null)
        }
        override suspend fun currentSession() = current
        override suspend fun stopSession(sessionId: String) {
            stopped += sessionId
        }
    }

    @Test
    fun `a start that finishes after the app gave up is stopped`() = runTest {
        val api = FakeStarts(listOf(StartPhase.CORE, StartPhase.CONTENT, StartPhase.READY))
        assertTrue(stopAbandonedStart(api, gameId = 7, startId = "s"))
        assertEquals(listOf("s7"), api.stopped)
        assertEquals("a look a second", 2_000L, currentTime)
    }

    @Test
    fun `a start that fails stops nothing`() = runTest {
        val api = FakeStarts(listOf(StartPhase.LAUNCHING, StartPhase.FAILED))
        assertFalse(stopAbandonedStart(api, 7, "s"))
        assertEquals(emptyList<String>(), api.stopped)
    }

    @Test
    fun `another game being played now is left alone`() = runTest {
        // Started since, from another device: the session is no longer this start's.
        val api = FakeStarts(listOf(StartPhase.READY), current = session(8))
        assertFalse(stopAbandonedStart(api, 7, "s"))
        assertEquals(emptyList<String>(), api.stopped)
    }

    @Test
    fun `a start the server forgot, or cannot say, is given up on`() = runTest {
        val api = FakeStarts(listOf(null))
        assertFalse(stopAbandonedStart(api, 7, "s"))
        assertEquals(AbandonedStartDefaults.MISSES, api.asked)
        // A miss now and then is not the end of it.
        val flaky = FakeStarts(listOf(null, StartPhase.CONTENT, null, StartPhase.READY))
        assertTrue(stopAbandonedStart(flaky, 7, "s"))
    }

    @Test
    fun `a start that never ends is followed no longer than the app would have waited`() = runTest {
        val api = FakeStarts(listOf(StartPhase.CONTENT))
        assertFalse(stopAbandonedStart(api, 7, "s"))
        assertTrue(currentTime <= AbandonedStartDefaults.MAX_WAIT_MS + 1_000)
    }

    private companion object {
        fun session(gameId: Int) =
            SessionInfo("s$gameId", gameId, "nes", "Nova", "fceumm", 256, 240, 60.0, false, 1, 1)
    }
}
