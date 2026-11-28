package media.sovereign.sovemu.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The controller settings, one system at a time, from the in-game menu or the account menu. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSettingsTest {
    /** Mappings kept per scope (a system's id, or null for every system). */
    private class FakeMappings : ControllerMappingService {
        val stored = mutableMapOf<String?, MutableList<ControllerMapping>>()
        val asked = mutableListOf<String?>()
        var controlsGate: CompletableDeferred<Unit>? = null

        override suspend fun controllerMappings(system: String?): List<ControllerMapping> {
            asked += system
            return stored[system].orEmpty().toList()
        }
        override suspend fun saveControllerMapping(
            controller: String,
            system: String?,
            name: String,
            bindings: List<Binding>,
        ): ControllerMapping {
            val m = ControllerMapping(controller, name, bindings, MappingSource.USER, system)
            stored.getOrPut(system) { mutableListOf() }.apply {
                removeAll { it.controller == controller }
                add(m)
            }
            return m
        }
        override suspend fun resetControllerMapping(controller: String, system: String?) =
            stored[system]?.removeAll { it.controller == controller } == true
        override suspend fun controls(system: String): Pair<SystemControls?, List<PadLayout>> {
            controlsGate?.await()
            return null to emptyList()
        }
    }

    private val swap = listOf(Binding("south", Button.A), Binding("east", Button.B))

    @Test
    fun `a system's mappings load for it and for every system`() = runTest {
        val api = FakeMappings()
        api.stored["snes"] = mutableListOf(ControllerMapping("xbox", "Pad", swap, system = "snes"))
        val model = ControllerSettingsModel(api, this)
        model.select("snes")
        assertTrue(model.state.value.loading)
        advanceUntilIdle()
        val state = model.state.value
        assertEquals(null, state.error)
        assertEquals("snes", state.system)
        assertEquals(listOf("snes", null), api.asked)
        assertTrue(state.book!!.canReset("xbox", MappingScope.SYSTEM))
    }

    @Test
    fun `choosing another system while one loads shows the one chosen last`() = runTest {
        val api = FakeMappings()
        val gate = CompletableDeferred<Unit>()
        api.controlsGate = gate
        val model = ControllerSettingsModel(api, this)
        model.select("nes")
        advanceUntilIdle()
        api.controlsGate = null
        model.select("gba")
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals("gba", model.state.value.system)
        assertEquals("gba", model.state.value.book?.system)
    }

    @Test
    fun `saving and resetting go to the system shown and tell the game`() = runTest {
        val api = FakeMappings()
        var changed = 0
        val model = ControllerSettingsModel(api, this) { changed++ }
        model.select("md")
        advanceUntilIdle()
        model.save("xbox", MappingScope.SYSTEM, "Pad", swap)
        assertEquals("md", api.stored["md"]!!.single().system)
        assertTrue(model.state.value.book!!.canReset("xbox", MappingScope.SYSTEM))
        model.save("xbox", MappingScope.ALL, "Pad", swap)
        assertEquals(1, api.stored[null]!!.size)
        model.reset("xbox", MappingScope.SYSTEM)
        assertEquals(false, model.state.value.book!!.canReset("xbox", MappingScope.SYSTEM))
        assertEquals(3, changed)
    }

    @Test
    fun `nothing is saved before a system has loaded`() = runTest {
        val api = FakeMappings()
        val model = ControllerSettingsModel(api, this)
        model.save("xbox", MappingScope.SYSTEM, "Pad", swap)
        assertTrue(api.stored.isEmpty())
        assertNull(model.state.value.system)
    }
}
