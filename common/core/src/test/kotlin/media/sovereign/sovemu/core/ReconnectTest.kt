package media.sovereign.sovemu.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnecting as the web does: the same constants, the same three reasons, then giving up. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectTest {
    private class FakeLink(private val failConnect: Boolean = false) : PeerLink {
        val mutable = MutableStateFlow(PeerState.NEW)
        override val state: StateFlow<PeerState> = mutable
        var closed = 0
        override suspend fun connect() {
            if (failConnect) error("offer refused")
            mutable.value = PeerState.CONNECTING
        }
        override fun close() {
            closed++
        }
    }

    private val links = mutableListOf<FakeLink>()
    private val attempts = mutableListOf<Int>()
    private var gaveUp = false

    private fun TestScope.start(make: (Int) -> FakeLink = { FakeLink() }) = launch {
        keepConnected({ attempt ->
            attempts += attempt
            make(attempt).also { links += it }
        })
        gaveUp = true
    }

    @Test
    fun `a connection that stays up is never replaced`() = runTest {
        val job = start()
        runCurrent()
        links[0].mutable.value = PeerState.CONNECTED
        advanceTimeBy(10 * 60_000)
        assertEquals(listOf(0), attempts)
        job.cancel()
        runCurrent()
        assertEquals("cancelled, its connection is closed", 1, links[0].closed)
    }

    @Test
    fun `a failed connection is made again at once`() = runTest {
        val job = start()
        runCurrent()
        links[0].mutable.value = PeerState.CONNECTED
        runCurrent()
        links[0].mutable.value = PeerState.FAILED
        runCurrent()
        assertEquals(listOf(0, 1), attempts)
        assertEquals(1, links[0].closed)
        job.cancel()
    }

    @Test
    fun `a disconnection is given five seconds to recover`() = runTest {
        val job = start()
        runCurrent()
        links[0].mutable.value = PeerState.CONNECTED
        runCurrent()
        links[0].mutable.value = PeerState.DISCONNECTED
        advanceTimeBy(Reconnect.DISCONNECTED_GRACE_MS - 1)
        links[0].mutable.value = PeerState.CONNECTED
        advanceTimeBy(60_000)
        assertEquals("it recovered by itself", listOf(0), attempts)

        links[0].mutable.value = PeerState.DISCONNECTED
        advanceTimeBy(Reconnect.DISCONNECTED_GRACE_MS + 1)
        assertEquals(listOf(0, 1), attempts)
        job.cancel()
    }

    @Test
    fun `a connection never made within fifteen seconds is made again`() = runTest {
        val job = start()
        advanceTimeBy(Reconnect.CONNECT_TIMEOUT_MS - 1)
        assertEquals(listOf(0), attempts)
        advanceTimeBy(2)
        assertEquals(listOf(0, 1), attempts)
        job.cancel()
    }

    @Test
    fun `after three reconnects it gives up, with every connection closed`() = runTest {
        start { FakeLink(failConnect = true) }
        advanceTimeBy(60_000)
        assertEquals(listOf(0, 1, 2, 3), attempts)
        assertTrue(gaveUp)
        assertTrue(links.all { it.closed == 1 })
    }

    @Test
    fun `reconnects count over the whole game, not per connection`() = runTest {
        start()
        repeat(4) { i ->
            runCurrent()
            links[i].mutable.value = PeerState.CONNECTED
            runCurrent()
            links[i].mutable.value = PeerState.FAILED
        }
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3), attempts)
        assertTrue(gaveUp)
    }

    @Test
    fun `a new connection carries on the game's packet numbers`() {
        // The worker remembers the last number it took across a reconnect, and drops anything
        // no newer: numbers started again would leave the game deaf to input.
        val sequence = InputSequence()
        val first = List(3) { sequence.nextInput() }
        val afterReconnect = sequence.nextInput()
        assertEquals(listOf(1, 2, 3), first)
        assertTrue(afterReconnect > first.last())
        assertEquals(1, sequence.nextAnalog())
    }
}
