package media.sovereign.sovemu.core

import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.parseResponse
import com.apollographql.apollo.exception.ApolloHttpException
import com.benasher44.uuid.uuid4
import media.sovereign.sovemu.core.graphql.ControlsQuery
import media.sovereign.sovemu.core.graphql.HomeQuery
import media.sovereign.sovemu.core.graphql.LogoutMutation
import media.sovereign.sovemu.core.graphql.SystemGamesQuery
import media.sovereign.sovemu.core.graphql.fragment.MappingFields
import media.sovereign.sovemu.core.graphql.type.MappingSource as SchemaMappingSource
import media.sovereign.sovemu.core.graphql.type.PadButton
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The generated operations against responses shaped as SovEmu.Server sends them, and the conversions over them. */
class ApiTest {
    private fun json(s: String) = Buffer().writeUtf8(s).jsonReader()

    @Test
    fun parsesTheHomeFeedWithAutoSaves() {
        val text = """{"data":{"home":{
            "featured":[{"__typename":"Game","id":7,"system":"snes","title":"Chrono Trigger","fileTitle":null,"tags":["USA"],
              "path":"snes/ct.sfc","size":4194304.0,"lastPlayedAt":null,"playCount":0,"image":null,"thumb":"/media/games/7/thumb",
              "video":null,"description":null,"releaseDate":"1995-03-11","developer":"Square","publisher":null,"genre":"RPG",
              "players":"1","rating":null,"discs":1,"favourite":true,"autoSave":null}],
            "recent":[{"__typename":"Game","id":8,"system":"nes","title":"Metroid","fileTitle":null,"tags":[],"path":"nes/m.nes",
              "size":131088.0,"lastPlayedAt":"2026-11-19 16:05:56","playCount":2,"image":null,"thumb":null,"video":null,
              "description":null,"releaseDate":null,"developer":null,"publisher":null,"genre":null,"players":null,"rating":null,"discs":2,"favourite":false,
              "autoSave":{"slot":0,"savedAt":1.7e9,"screenshotUrl":"/media/games/8/saves/0?t=1"}}],
            "added":[],"favourites":[]},
          "collections":[{"__typename":"Collection","id":3,"name":"Co-op","games":[]}]}}"""
        val data = HomeQuery().parseResponse(json(text)).orThrow()
        val home = data.home
        val featured = home.featured[0].gameFields
        assertEquals("/media/games/7/thumb", featured.thumb)
        assertEquals("1995 · Square · RPG", featured.byline)
        assertEquals("/media/games/8/saves/0?t=1", home.recent[0].gameFields.autoSave?.screenshotUrl)
        assertTrue(home.added.isEmpty())
        assertTrue(featured.favourite)
        assertEquals("Co-op", data.collections.single().name)
    }

    @Test
    fun anUnknownSystemIsNull() {
        assertNull(SystemGamesQuery("nope").parseResponse(json("""{"data":{"system":null}}""")).orThrow().system)
    }

    @Test
    fun errorsBecomeApiExceptionsWithTheirCode() {
        val signedOut = LogoutMutation().parseResponse(
            json("""{"data":null,"errors":[{"message":"Not signed in","extensions":{"code":"UNAUTHENTICATED"}}]}"""),
        )
        val e = assertThrows(ApiException::class.java) { signedOut.orThrow() }
        assertTrue(e.unauthenticated)
        assertEquals("Not signed in", e.message)

        val plain = ApolloResponse.Builder(LogoutMutation(), uuid4()).errors(listOf(Error.Builder("Bad").build())).build()
        assertEquals("INTERNAL", assertThrows(ApiException::class.java) { plain.orThrow() }.code)

        val http = ApolloResponse.Builder(LogoutMutation(), uuid4()).exception(ApolloHttpException(404, emptyList(), null, "Not Found")).build()
        assertEquals("HTTP_404", assertThrows(ApiException::class.java) { http.orThrow() }.code)
    }

    @Test
    fun padButtonsAreTheProtocolsButtonsNameForName() {
        for (b in Button.entries) assertEquals(b, b.toPadButton().toButton())
        assertEquals(Button.entries.size, PadButton.knownEntries.size)
    }

    @Test
    fun convertsMappingsBothWays() {
        val fields = MappingFields(
            controller = "xbox", system = "snes", name = "Mine", source = SchemaMappingSource.USER,
            bindings = listOf(MappingFields.Binding("south", PadButton.B), MappingFields.Binding("l2", PadButton.L3)),
        )
        val m = fields.toMapping()
        assertEquals(ControllerMapping("xbox", "Mine", listOf(Binding("south", Button.B), Binding("l2", Button.L3)), MappingSource.USER, "snes"), m)
        assertEquals(PadButton.L3, m.bindings[1].toInput().button)
    }

    @Test
    fun parsesControls() {
        val layout = """{"__typename":"PadLayout","id":"snes","name":"Super Nintendo","width":100.0,"height":50.0,
            "body":[{"x":0.0,"y":0.0,"w":100.0,"h":50.0,"r":10.0,"tone":0}],
            "keys":[{"key":"A","label":"A","x":80.0,"y":20.0,"w":8.0,"h":8.0,"r":4.0}]}"""
        val text = """{"data":{"system":{"controls":{"buttons":[{"button":"A","label":"A"},{"button":"START","label":"Start"}],
            "layout":$layout}},"controllerLayouts":[${layout.replace("\"snes\"", "\"xbox\"")}]}}"""
        val (system, pads) = ControlsQuery("snes").parseResponse(json(text)).orThrow().toControls()
        assertEquals(listOf(SystemButton(Button.A, "A"), SystemButton(Button.START, "Start")), system?.buttons)
        assertEquals(PadKey("A", "A", 80.0, 20.0, 8.0, 8.0, 4.0), system?.layout?.keys?.single())
        assertEquals("xbox", pads.single().id)
    }

    @Test
    fun readsIceServersFromTheJsonScalar() {
        val servers = iceServers(
            listOf(
                mapOf("urls" to "stun:stun.example.org"),
                mapOf("urls" to listOf("turn:a", "turn:b"), "username" to "u", "credential" to "c"),
                mapOf("username" to "no urls"),
                "junk",
            ),
        )
        assertEquals(listOf(IceServer(listOf("stun:stun.example.org")), IceServer(listOf("turn:a", "turn:b"), "u", "c")), servers)
        assertTrue(iceServers(null).isEmpty())
    }

    @Test
    fun systemBadgesAndColours() {
        assertEquals("software rendering", system(needsGpu = true, rendering = media.sovereign.sovemu.core.graphql.type.Rendering.SOFTWARE).badge())
        assertEquals("no core", system(playable = false).badge())
        assertEquals("no BIOS", system(biosMissing = true).badge())
        assertNull(system().badge())
        assertEquals(0xffc0392bL, parseColor(system().color))
    }
}
