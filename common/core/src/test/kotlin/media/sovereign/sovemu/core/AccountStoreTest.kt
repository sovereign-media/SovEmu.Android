package media.sovereign.sovemu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStoreTest {
    private val prefs = MemoryKeyValue()
    private val sealer = FakeSealer()
    private var server: String? = "http://tv.local:8080"
    private var ids = 0

    private fun store() = AccountStore(prefs, sealer, { server }, newId = { "id${++ids}" })

    @Test
    fun aSingleSignInBecomesTheActiveAccountWithItsTokenUntouched() {
        val sealed = sealer.seal("r0")
        prefs.put(AccountStore.LEGACY_SEALED, sealed)
        prefs.put(AccountStore.LEGACY_CLIENT, "phone-app")
        prefs.put(AccountStore.LEGACY_DEVICE, "Living room TV")
        val store = store()
        val account = store.all().single()
        assertEquals(
            StoredAccount("id1", null, "http://tv.local:8080", "phone-app", null, 0L),
            account,
        )
        assertEquals("id1", store.activeId())
        assertEquals("r0", store.refreshToken("id1"))
        // Moved, not decrypted and sealed again.
        assertEquals(sealed, prefs.get(AccountStore.tokenKey("id1")))
        assertEquals("Living room TV", store.namedDevice("id1"))
        for (key in listOf("refresh_token_sealed", "token_client", "named_device")) {
            assertNull(key, prefs.get(key))
        }
        // Once: another launch finds the account, and no second one.
        assertEquals(listOf(account), store().all())
    }

    @Test
    fun aPlainTokenFromBeforeSealingIsSealedAsItMoves() {
        prefs.put(AccountStore.LEGACY_PLAIN, "r0")
        val store = store()
        assertEquals("r0", store.refreshToken(store.activeId()!!))
        assertFalse(prefs.map.values.any { it == "r0" })
    }

    @Test
    fun aTokenWithNoServerIsDropped() {
        server = ""
        prefs.put(AccountStore.LEGACY_SEALED, sealer.seal("r0"))
        assertTrue(store().all().isEmpty())
        assertNull(prefs.get(AccountStore.LEGACY_SEALED))
    }

    @Test
    fun theSameUserOnTheSameServerIsOneAccount() {
        val store = store()
        val first = (
            store.upsert(
                "7",
                "http://tv.local:8080",
                "c",
                "ada",
                1,
            ) as AccountUpsert.Stored
            ).account
        store.setNamedDevice(first.id, "TV")
        val again = (
            store.upsert(
                "7",
                "https://TV.local:8080",
                "c2",
                null,
                2,
            ) as AccountUpsert.Stored
            ).account
        assertEquals(first.id, again.id)
        assertEquals("c2", again.clientId)
        assertEquals("ada", again.userName)
        assertNull("a new sign-in has no device name yet", store.namedDevice(first.id))
        // Another user, or the same user on another server, is another account.
        store.upsert("8", "http://tv.local:8080", "c", "ben", 3)
        store.upsert("7", "http://other:8080", "c", "ada", 4)
        assertEquals(3, store.all().size)
    }

    @Test
    fun aSixthUserIsRefusedAndNothingChanges() {
        val store = store()
        repeat(5) { store.upsert("$it", "http://tv.local:8080", "c", null, 0) }
        val before = store.all()
        assertEquals(
            AccountUpsert.Refused(AccountRefusal.ServerFull),
            store.upsert("9", "http://tv.local:8080", "c", null, 0),
        )
        assertEquals(before, store.all())
        // The same user signing in again still fits.
        assertTrue(store.upsert("3", "http://tv.local:8080", "c", null, 0) is AccountUpsert.Stored)
    }

    @Test
    fun removingAnAccountTakesItsTokenAndTheActiveId() {
        val store = store()
        val a = (
            store.upsert(
                "7",
                "http://tv.local:8080",
                "c",
                null,
                0,
            ) as AccountUpsert.Stored
            ).account
        store.setRefreshToken(a.id, "r1")
        store.setActive(a.id, 5)
        assertEquals(5L, store.account(a.id)!!.lastUsedAt)
        store.remove(a.id)
        assertNull(store.activeId())
        assertNull(store.refreshToken(a.id))
        assertNull(prefs.get(AccountStore.tokenKey(a.id)))
        // A late write for it (a refresh that outlived the sign-out) is dropped.
        store.setRefreshToken(a.id, "r2")
        assertNull(prefs.get(AccountStore.tokenKey(a.id)))
    }

    @Test
    fun aTokenThatCannotBeUnsealedReadsAsNone() {
        val store = store()
        val a = (
            store.upsert(
                "7",
                "http://tv.local:8080",
                "c",
                null,
                0,
            ) as AccountUpsert.Stored
            ).account
        prefs.put(AccountStore.tokenKey(a.id), "damaged")
        assertNull(store.refreshToken(a.id))
    }

    @Test
    fun aRefreshTeachesAMigratedAccountWhoItIs() {
        prefs.put(AccountStore.LEGACY_SEALED, sealer.seal("r0"))
        val store = store()
        store.recordRefresh("id1", IdToken("7", "ada"), "sovemu-native")
        assertEquals(
            StoredAccount("id1", "7", "http://tv.local:8080", "sovemu-native", "ada", 0L),
            store.account("id1"),
        )
        // Its sub never changes afterwards.
        store.recordRefresh("id1", IdToken("8", null), "sovemu-native")
        assertEquals("7", store.account("id1")!!.subject)
    }
}
