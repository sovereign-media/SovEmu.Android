package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    private fun account(
        id: String,
        server: String = "http://tv.local:8080",
        name: String? = "ada",
    ) = StoredAccount(id, "sub-$id", server, "sovemu-native", name, 0L)

    @Test
    fun theCodecRoundTripsEveryField() {
        val accounts = listOf(
            StoredAccount("a", "7", "http://x:8080", "phone-app", "ada\tlovelace\n%", 1700L),
            StoredAccount("b", null, "https://y", null, null, 0L),
            StoredAccount("c", "", "http://z", "", "", 5L),
        )
        assertEquals(accounts, StoredAccountCodec.decode(StoredAccountCodec.encode(accounts)))
    }

    @Test
    fun aDamagedBlobGivesTheAccountsThatDecode() {
        val good = StoredAccountCodec.encode(listOf(account("a"), account("b")))
        val lines = good.split('\n')
        val damaged = listOf(
            lines[0],
            lines[1],
            "+x\t-\tbroken",
            lines[2],
            lines[1],
        ).joinToString("\n")
        // The broken record is skipped and the duplicate id dropped.
        assertEquals(listOf("a", "b"), StoredAccountCodec.decode(damaged).map { it.id })
        assertTrue(StoredAccountCodec.decode("v9\n" + lines[1]).isEmpty())
        assertTrue(StoredAccountCodec.decode(null).isEmpty())
        assertTrue(StoredAccountCodec.decode(lines[0] + "\n+a\t-\t+s\t-\t-\t+%zz").isEmpty())
    }

    @Test
    fun oneServerWhicheverSchemeItAnsweredOn() {
        assertTrue(StoredAccount.sameServer("http://TV.local:8080/", "https://tv.local:8080"))
        assertFalse(StoredAccount.sameServer("http://tv.local:8080", "http://tv.local:8081"))
        assertFalse(StoredAccount.sameServer(null, "http://tv.local"))
        assertFalse(StoredAccount.sameServer("", ""))
    }

    @Test
    fun fiveUsersAServerAndThreeServersADevice() {
        val five = (1..5).map { account("$it") }
        assertNull(StoredAccount.refusalFor(five.take(4), "http://tv.local:8080"))
        assertEquals(
            AccountRefusal.ServerFull,
            StoredAccount.refusalFor(five, "http://tv.local:8080"),
        )
        val three =
            listOf(account("a", "http://a"), account("b", "http://b"), account("c", "http://c"))
        assertEquals(AccountRefusal.DeviceFull, StoredAccount.refusalFor(three, "http://d"))
        // Another user on a server the device already holds still fits.
        assertNull(StoredAccount.refusalFor(three, "https://b"))
    }

    @Test
    fun aLaunchDecidesByTheCount() {
        assertEquals(LaunchAccounts.Login, LaunchAccounts.decide(0))
        assertEquals(LaunchAccounts.Restore, LaunchAccounts.decide(1))
        assertEquals(LaunchAccounts.Picker, LaunchAccounts.decide(2))
    }

    @Test
    fun chooseAServerListsEachServerOnceInOrder() {
        val accounts = listOf(
            account("a", "http://one", "ada"),
            account("b", "http://two", "ben"),
            account("c", "https://ONE/", "cy"),
        )
        val list = KnownServers.list(accounts, "https://one")
        assertEquals(listOf("https://one", "http://two"), list.map { it.address })
        assertEquals("2 users  ·  in use", list[0].countLine)
        assertEquals("1 user", list[1].countLine)
        assertEquals(
            "Everyone signed in to it on this device will be signed out: ada, cy.",
            list[0].removeMessage,
        )
        assertEquals(list[1], KnownServers.successor(list, list[0]))
        assertEquals(list[0], KnownServers.successor(list, list[1]))
        assertNull(KnownServers.successor(list.take(1), list[0]))
        assertFalse(KnownServers.canAddServer(3))
    }

    @Test
    fun anIdTokenSaysWhoSignedIn() {
        assertEquals(IdToken("7", "ada"), IdToken.read(idToken("7", "ada")))
        assertEquals(IdToken("7", null), IdToken.read(idToken("7")))
        assertEquals(IdToken.EMPTY, IdToken.read("x.y.z"))
        assertEquals(IdToken.EMPTY, IdToken.read(null))
        assertEquals(IdToken.EMPTY, IdToken.read("nodots"))
        val numeric = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":42}""".toByteArray())
        assertEquals("42", IdToken.read("h.$numeric.s").subject)
    }

    @Test
    fun anAccountIsLabelledByItsUsername() {
        assertEquals("AD", account("a").initials)
        assertEquals("?", account("a", name = null).label)
    }
}
