package media.sovereign.sovemu.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTest {
    @Test
    fun thePickerReturnsOnlyAfterAnHourAwayWithSomeoneToChoose() {
        val hour = 60 * 60_000L
        assertTrue(IdlePicker.shouldShow(0, hour, accounts = 2, playing = false))
        assertFalse(IdlePicker.shouldShow(0, hour - 1, accounts = 2, playing = false))
        assertFalse(IdlePicker.shouldShow(0, hour, accounts = 1, playing = false))
        assertFalse(IdlePicker.shouldShow(0, hour, accounts = 2, playing = true))
        assertFalse(IdlePicker.shouldShow(null, hour, accounts = 2, playing = false))
        assertFalse(IdlePicker.shouldShow(0, hour, 2, false, thresholdMinutes = 0))
    }

    @Test
    fun upAndDownGoToTheNearestTileOfACentredRow() {
        // Five accounts and Add user: two full rows of three.
        val full = pickerFocusMoves(6, 3)
        assertEquals(PickerMove(left = null, right = 1, up = null, down = 3), full[0])
        assertEquals(PickerMove(left = 4, right = null, up = 2, down = null), full[5])
        // Four tiles: a row of three over one centred tile, which sits under the middle one.
        val four = pickerFocusMoves(4, 3)
        assertEquals(3, four[0].down)
        assertEquals(3, four[2].down)
        assertEquals(1, four[3].up)
        assertEquals(PickerMove(null, null, 1, null), four[3])
        // Five: two under three, between its columns; a tie goes left.
        val five = pickerFocusMoves(5, 3)
        assertEquals(3, five[1].down)
        assertEquals(0, five[3].up)
        assertEquals(1, five[4].up)
        assertTrue(pickerFocusMoves(0, 3).isEmpty())
    }

    /** The app's server, as the account screens move it; [down] answers nothing. */
    private class Servers(var current: String, val down: Set<String> = emptySet()) :
        ServerService {
        val asked = mutableListOf<String>()
        override val server get() = current
        override fun setServer(address: String) {
            current = address
        }
        override suspend fun bootstrapAt(address: String): SignInConfig {
            asked += address
            current = address
            if (address in down) throw java.io.IOException("unreachable")
            return SignInConfig(address, true, "c", "/t", "/r", "/p")
        }
    }

    private val prefs = MemoryKeyValue()
    private var ids = 0

    private fun session(servers: Servers): Session {
        val store = AccountStore(prefs, FakeSealer(), newId = { "id${++ids}" })
        listOf("http://one" to "7", "http://one" to "8", "http://two" to "9", "http://three" to "7")
            .forEach { (server, sub) -> store.upsert(sub, server, "c", "u$sub", 0) }
        // Revocations go nowhere: these tests are about where the app lands.
        val client = TokenClient(OkHttpClient(), { servers.current }, { null })
        return Session(client, store, { servers.current })
    }

    @Test
    fun choosingAServerLandsByTheLaunchRule() = runBlocking {
        val servers = Servers("http://one")
        val session = session(servers)
        val chooser = ServerChooser(servers, session)
        val list = KnownServers.list(session.allAccounts.value, servers.current)
        assertEquals(ServerMove.Stayed, chooser.choose(list[0]))
        assertEquals(ServerMove.Moved(LaunchAccounts.Restore), chooser.choose(list[1]))
        assertEquals("http://two", servers.current)
        assertEquals("u9", session.active!!.userName)
        val back = KnownServers.list(session.allAccounts.value, servers.current)
        assertEquals(ServerMove.Moved(LaunchAccounts.Picker), chooser.choose(back[0]))
        assertEquals(AuthState.Choosing, session.state.value)
    }

    @Test
    fun aServerThatDoesNotAnswerLeavesTheAppWhereItWas() = runBlocking {
        val servers = Servers("http://one", down = setOf("http://two"))
        val session = session(servers)
        session.launch()
        val list = KnownServers.list(session.allAccounts.value, servers.current)
        val move = ServerChooser(servers, session).choose(list[1])
        assertTrue(move is ServerMove.Failed)
        assertEquals("http://one", servers.current)
        assertEquals(AuthState.Choosing, session.state.value)
    }

    @Test
    fun removingTheServerInUseMovesToTheOneThatTookItsPlace() = runBlocking {
        val servers = Servers("http://two")
        val session = session(servers)
        session.launch()
        val chooser = ServerChooser(servers, session)
        var list = KnownServers.list(session.allAccounts.value, servers.current)
        // Not in use: only its accounts go.
        assertEquals(ServerMove.Stayed, chooser.remove(list[0], list))
        assertEquals(listOf("u9", "u7"), session.allAccounts.value.map { it.userName })
        list = KnownServers.list(session.allAccounts.value, servers.current)
        assertEquals(ServerMove.Moved(LaunchAccounts.Restore), chooser.remove(list[0], list))
        assertEquals("http://three", servers.current)
        list = KnownServers.list(session.allAccounts.value, servers.current)
        assertEquals(ServerMove.NoServer, chooser.remove(list[0], list))
        assertEquals("", servers.current)
        assertEquals(AuthState.SignedOut, session.state.value)
    }

    @Test
    fun removingTheServerInUseLandsEvenWhenTheScreenThatAskedGoes() = runBlocking {
        // #131: removing the server in use signs its account out, and the app's signed-in
        // screens (the account screens opened over it among them) go at once. The move to the
        // next server must still finish, or the app is left signed out on a server whose
        // stored account nobody restores.
        val answer = CompletableDeferred<Unit>()
        val servers = object : ServerService {
            var current = "http://two"
            override val server get() = current
            override fun setServer(address: String) {
                current = address
            }
            override suspend fun bootstrapAt(address: String): SignInConfig {
                current = address
                answer.await()
                return SignInConfig(address, true, "c", "/t", "/r", "/p")
            }
        }
        val store = AccountStore(prefs, FakeSealer(), newId = { "id${++ids}" })
        store.upsert("9", "http://two", "c", "u9", 0)
        store.upsert("7", "http://three", "c", "u7", 0)
        val client = TokenClient(OkHttpClient(), { servers.current }, { null })
        val session = Session(client, store, { servers.current })
        session.launch()
        val chooser = ServerChooser(servers, session)
        val list = KnownServers.list(session.allAccounts.value, servers.current)
        val removing = launch(Dispatchers.Default) { chooser.remove(list[0], list) }
        // The next server is being asked how to sign in: the screen goes, cancelling its call.
        withTimeout(5_000) { while (servers.current != "http://three") yield() }
        removing.cancel()
        answer.complete(Unit)
        removing.join()
        withTimeout(5_000) { session.state.first { it is AuthState.Authenticated } }
        assertEquals("u7", session.active!!.userName)
    }
}
