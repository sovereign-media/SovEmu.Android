package media.sovereign.sovemu.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Cheats page's and the Collections drawer's state, against fakes of the API. */
class GameExtrasTest {
    /** Runs each launched block at once, on the test's thread. */
    private val now = CoroutineScope(Dispatchers.Unconfined)

    private class FakeCheats : CheatService {
        val list = mutableListOf(Cheat(1, "Lives", "SXIOPO", false))
        val calls = mutableListOf<String>()
        var fail: String? = null

        private fun check() = fail?.let { throw ApiException("BAD_REQUEST", it) }
        override suspend fun cheats(gameId: Int) = list.toList().also { calls += "cheats $gameId" }
        override suspend fun addCheat(gameId: Int, code: String, description: String): List<Cheat> {
            check()
            calls += "add $code '$description'"
            list += Cheat(list.size + 1, description, code, false)
            return list.toList()
        }
        override suspend fun setCheatEnabled(
            gameId: Int,
            cheatId: Int,
            enabled: Boolean,
        ): List<Cheat> {
            check()
            calls += "set $cheatId $enabled"
            list.replaceAll { if (it.id == cheatId) it.copy(enabled = enabled) else it }
            return list.toList()
        }
        override suspend fun deleteCheat(gameId: Int, cheatId: Int) {
            check()
            calls += "delete $cheatId"
            list.removeAll { it.id == cheatId }
        }
    }

    @Test
    fun cheatsLoadToggleAddAndDelete() {
        val api = FakeCheats()
        val model = CheatsModel(api, 7, now)
        assertNull("not loaded yet", model.cheats.value)
        model.load()
        assertEquals(listOf("Lives"), model.cheats.value!!.map { it.description })
        model.toggle(model.cheats.value!![0])
        assertEquals(true, model.cheats.value!![0].enabled)
        // A code is upper case and trimmed; an empty one is not sent.
        model.add("  apzlgg ", " Moon jump ")
        model.add("   ", "nothing")
        assertEquals(listOf("cheats 7", "set 1 true", "add APZLGG 'Moon jump'"), api.calls)
        model.delete(model.cheats.value!![0])
        assertEquals(listOf("Moon jump"), model.cheats.value!!.map { it.description })
    }

    @Test
    fun aRefusedChangeSaysWhyAndKeepsTheList() {
        val api = FakeCheats()
        val model = CheatsModel(api, 7, now)
        model.load()
        api.fail = "that is not a cheat code"
        model.add("zz", "")
        assertEquals("that is not a cheat code", model.message.value)
        assertEquals(1, model.cheats.value!!.size)
        api.fail = null
        model.toggle(model.cheats.value!![0])
        assertNull("a change that works clears it", model.message.value)
    }

    private class FakeCollections : CollectionService {
        val members = mutableMapOf(1 to false, 2 to true)
        val names = mutableMapOf(1 to "Arcade", 2 to "couch")
        var fail = false

        override suspend fun collectionChoices(gameId: Int) = names.map { (id, name) ->
            CollectionChoice(
                id,
                name,
                members[id] == true,
            )
        }
        override suspend fun createCollection(name: String): Int {
            if (fail) throw ApiException("CONFLICT", "a collection with that name exists")
            val id = names.size + 1
            names[id] = name
            return id
        }
        override suspend fun setInCollection(collectionId: Int, gameId: Int, member: Boolean) {
            if (fail) throw ApiException("INTERNAL", "boom")
            members[collectionId] = member
        }
    }

    @Test
    fun aToggleShowsAtOnceAndIsUndoneWhenRefused() = runBlocking {
        val api = FakeCollections()
        var changed = 0
        val model = CollectionsModel(api, 7, now) { changed++ }
        model.load()
        model.toggle(model.choices.value!![0])
        assertEquals(true, api.members[1])
        assertEquals(1, changed)
        api.fail = true
        model.toggle(model.choices.value!![0])
        yield()
        assertEquals("undone", true, model.choices.value!![0].member)
        assertEquals("Could not change Arcade: boom", model.error.value)
        assertEquals("only what worked tells Home", 1, changed)
    }

    @Test
    fun aNewCollectionHasTheGameInItAndANameThatFailedComesBack() = runBlocking {
        val api = FakeCollections()
        val model = CollectionsModel(api, 7, now)
        model.load()
        model.create("  Beat 'em ups ")
        assertEquals(
            listOf("Arcade", "Beat 'em ups", "couch"),
            model.choices.value!!.map {
                it.name
            },
        )
        assertEquals(true, api.members[3])
        api.fail = true
        var back: String? = null
        model.create("Arcade") { back = it }
        assertEquals("Arcade", back)
        assertEquals("a collection with that name exists", model.error.value)
    }
}
