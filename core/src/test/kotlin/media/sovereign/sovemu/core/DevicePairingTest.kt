package media.sovereign.sovemu.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Waiting for a code's approval, on a clock the test moves (Sovereign's poll controller's cases).
 */
class DevicePairingTest {
    private var clock = 0L
    private val waits = mutableListOf<Long>()
    private val code =
        DeviceCode(
            "dc",
            "BCDF-GHJK",
            "http://tv.local/device",
            null,
            expiresInSeconds = 60,
            intervalSeconds = 5,
        )

    private fun pairing(
        vararg answers: DevicePoll,
        reach: MutableList<Boolean> = mutableListOf(),
    ): PairingResult {
        val queue = ArrayDeque(answers.toList())
        return runBlocking {
            awaitApproval(
                code,
                poll = { queue.removeFirstOrNull() ?: DevicePoll.Pending },
                now = { clock },
                sleep = {
                    waits += it
                    clock += it
                },
                reachable = { reach += it },
            )
        }
    }

    @Test
    fun waitingIsNotAFailureAndTheServersIntervalIsKept() {
        val tokens = Tokens("a", 3600, "r")
        val result = pairing(DevicePoll.Pending, DevicePoll.Pending, DevicePoll.Approved(tokens))
        assertEquals(PairingResult.Approved(tokens), result)
        // The wait comes first, so the request that got the code is not doubled at once.
        assertEquals(listOf(5_000L, 5_000L, 5_000L), waits)
    }

    @Test
    fun slowDownWidensTheIntervalForGood() {
        pairing(
            DevicePoll.SlowDown,
            DevicePoll.Pending,
            DevicePoll.SlowDown,
            DevicePoll.Refused(PairingFailure.Denied),
        )
        assertEquals(listOf(5_000L, 10_000L, 10_000L, 15_000L), waits)
    }

    @Test
    fun aPollWithNoAnswerIsTriedAgainAndSaidSo() {
        val reach = mutableListOf<Boolean>()
        val tokens = Tokens("a", 3600, "r")
        val result =
            pairing(
                DevicePoll.NoAnswer,
                DevicePoll.NoAnswer,
                DevicePoll.Pending,
                DevicePoll.Approved(tokens),
                reach = reach,
            )
        assertEquals(PairingResult.Approved(tokens), result)
        assertEquals(listOf(false, false, true), reach)
    }

    @Test
    fun theServersNoEndsIt() {
        assertEquals(
            PairingResult.Failed(PairingFailure.Denied),
            pairing(DevicePoll.Refused(PairingFailure.Denied)),
        )
        assertEquals(1, waits.size)
    }

    @Test
    fun aCodeNobodyApprovesExpiresWithItsLifetime() {
        // Pending for ever: the 60 s lifetime ends it, without a poll past it.
        assertEquals(PairingResult.Failed(PairingFailure.Expired), pairing())
        assertEquals(60_000L, waits.sum())
        assertEquals(12, waits.size)
    }

    @Test
    fun theCodeIsShownGroupedAndCountsDown() {
        assertEquals("BCDF-GHJK", code.copy(userCode = "BCDFGHJK").grouped)
        assertEquals("BCDF-GHJK", code.grouped)
        assertEquals("Expires in 9:05", PairingText.expiresIn(545))
        assertEquals("Expires in 0:00", PairingText.expiresIn(-3))
    }

    @Test
    fun theQrCodeHoldsThePage() {
        val qr = QrCode.encode("http://192.168.1.10:8080/device?user_code=BCDF-GHJK")
        assertEquals(0, (qr.size - 21) % 4) // a QR code is 21 + 4n modules a side
        // Its three finder patterns: dark corners.
        assert(qr.isDark(0, 0) && qr.isDark(qr.size - 1, 0) && qr.isDark(0, qr.size - 1))
    }
}
