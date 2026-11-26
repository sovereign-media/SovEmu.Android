package media.sovereign.sovemu

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncTest {
    @Test
    fun failuresBecomeResults() {
        assertEquals(42, attempt { 42 }.getOrNull())
        val failed = attempt<Int> { throw IllegalStateException("no") }
        assertTrue(failed.isFailure)
        assertEquals("no", failed.exceptionOrNull()?.message)
    }

    /** The bug it exists for: runCatching swallowed cancellation and the work carried on. */
    @Test
    fun cancellationIsRethrownSoWorkStops() = runBlocking {
        var carriedOn = false
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            attempt { delay(10_000) }
            carriedOn = true
        }
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertFalse(carriedOn)
    }
}
