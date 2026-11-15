package media.sovereign.sovemu.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Request bodies and response decoding for `POST /graphql` (`SovEmu.Server/schema.graphql`). */
class GraphQLTest {
    @Test
    fun encodesQueryAndVariables() {
        val body = GraphQL.body(Queries.SESSION_OFFER, buildJsonObject { put("id", "abc"); put("sdp", "v=0") })
        val json = ApiJson.parseToJsonElement(body).jsonObject
        assertEquals(JsonPrimitive(Queries.SESSION_OFFER), json["query"])
        assertEquals(buildJsonObject { put("id", "abc"); put("sdp", "v=0") }, json["variables"])
        // No variables still sends an (empty) object.
        val plain = ApiJson.parseToJsonElement(GraphQL.body(Queries.VIEWER)).jsonObject
        assertEquals(JsonObject(emptyMap()), plain["variables"])
    }

    @Test
    fun numbersStayNumbers() {
        val body = GraphQL.body(Queries.START_SESSION, buildJsonObject { put("gameId", 42L) })
        assertTrue(body.contains("\"gameId\":42"))
    }

    @Test
    fun queriesAskForTheFieldsTheModelsRead() {
        assertTrue(Queries.SYSTEM_GAMES.contains("system(id: \$id) { games { id system title tags size lastPlayedAt playCount } }"))
        assertTrue(Queries.START_SESSION.contains("startSession(gameId: \$gameId)"))
        assertTrue(Queries.LOGIN.startsWith("mutation(\$username: String!, \$password: String!)"))
    }

    @Test
    fun decodesTheViewer() {
        val signedOut = GraphQL.decode<Viewer>("""{"data":{"viewer":{"setupRequired":true,"user":null}}}""", "viewer")
        assertTrue(signedOut.setupRequired)
        assertNull(signedOut.user)
        val signedIn = GraphQL.decode<Viewer>(
            """{"data":{"viewer":{"setupRequired":false,"user":{"id":1,"username":"bruce","isAdmin":true}}}}""",
            "viewer",
        )
        assertEquals(User(1, "bruce", true), signedIn.user)
    }

    @Test
    fun decodesGamesWithFloatSizesAndTagLists() {
        val games = GraphQL.decode<SystemGames?>(
            """{"data":{"system":{"games":[{"id":7,"system":"nes","title":"Alter Ego","tags":["USA","Rev 1"],
               "size":40976.0,"lastPlayedAt":null,"playCount":0}]}}}""",
            "system",
        )!!.games
        assertEquals(listOf("USA", "Rev 1"), games.single().tags)
        assertEquals(40976.0, games.single().size, 0.0)
        // An unknown system is null, not an error.
        assertNull(GraphQL.decode<SystemGames?>("""{"data":{"system":null}}""", "system"))
    }

    @Test
    fun decodesSessionsAnswersAndConfig() {
        val session = GraphQL.decode<SessionInfo>(
            """{"data":{"startSession":{"id":"s1","gameId":7,"system":"nes","title":"Alter Ego","core":"FCEUmm",
               "width":256,"height":240,"fps":60.0988,"connected":false}}}""",
            "startSession",
        )
        assertEquals(7L, session.gameId)
        assertFalse(session.connected)
        assertEquals("v=0\r\n", GraphQL.decode<String>("""{"data":{"sessionOffer":"v=0\r\n"}}""", "sessionOffer"))
        assertTrue(GraphQL.decode<Boolean>("""{"data":{"saveState":true}}""", "saveState"))
        val config = GraphQL.decode<ClientConfig>(
            """{"data":{"clientConfig":{"iceServers":[{"urls":"stun:stun.example:3478"},
               {"urls":["turn:t.example"],"username":"u","credential":"c"}]}}}""",
            "clientConfig",
        )
        assertEquals(2, config.iceServers.size)
        assertEquals("u", config.iceServers[1].username)
    }

    @Test
    fun decodesSaves() {
        val saves = GraphQL.decode<GameSaves>(
            """{"data":{"game":{"saves":[{"slot":0,"savedAt":1763100000.0,"screenshotUrl":"/media/saves/7/0.png?v=1"},
               {"slot":1,"savedAt":1763100001.0,"screenshotUrl":null}]}}}""",
            "game",
        ).saves
        assertEquals("/media/saves/7/0.png?v=1", saves[0].screenshotUrl)
        assertNull(saves[1].screenshotUrl)
    }

    @Test
    fun errorsCarryTheirCodeAndMessage() {
        val e = assertThrows(ApiException::class.java) {
            GraphQL.decode<List<SystemSummary>>(
                """{"data":null,"errors":[{"message":"Sign in first","locations":[{"line":1,"column":9}],
                   "path":["systems"],"extensions":{"code":"UNAUTHENTICATED"}}]}""",
                "systems",
            )
        }
        assertEquals("UNAUTHENTICATED", e.code)
        assertEquals("Sign in first", e.message)
        assertTrue(e.unauthenticated)
    }

    @Test
    fun errorsWinOverPartialData() {
        val e = assertThrows(ApiException::class.java) {
            GraphQL.decode<User>(
                """{"data":{"login":null},"errors":[{"message":"Wrong username or password","extensions":{"code":"FORBIDDEN"}}]}""",
                "login",
            )
        }
        assertEquals("FORBIDDEN", e.code)
        assertFalse(e.unauthenticated)
    }

    @Test
    fun anErrorWithoutACodeIsInternal() {
        val e = assertThrows(ApiException::class.java) {
            GraphQL.decode<Boolean>("""{"errors":[{"message":"Unknown field \"nope\""}]}""", "logout", 400)
        }
        assertEquals("INTERNAL", e.code)
        assertEquals("Unknown field \"nope\"", e.message)
    }

    @Test
    fun aResponseThatIsNotGraphQLKeepsTheHttpStatus() {
        val e = assertThrows(ApiException::class.java) { GraphQL.decode<Viewer>("Not Found", "viewer", 404) }
        assertEquals("HTTP_404", e.code)
        assertFalse(e.unauthenticated)
    }

    @Test
    fun decodesControllerMappings() {
        val mappings = GraphQL.decode<List<ControllerMapping>>(
            """{"data":{"controllerMappings":[{"controller":"xbox","name":"Xbox Wireless Controller",
               "bindings":[{"input":"south","button":"A"},{"input":"east","button":"B"}],"source":"ADMIN"},
               {"controller":"keyboard","name":"","bindings":[{"input":"KeyZ","button":"SELECT"}],"source":"USER"}]}}""",
            "controllerMappings",
        )
        assertEquals(listOf(Binding("south", Button.A), Binding("east", Button.B)), mappings[0].bindings)
        assertEquals(Button.SELECT, mappings[1].bindings.single().button)
        assertEquals(MappingSource.ADMIN, mappings[0].source)
        assertEquals(MappingSource.USER, mappings[1].source)
        assertTrue(GraphQL.decode<Boolean>("""{"data":{"resetControllerMapping":false}}""", "resetControllerMapping").not())
    }

    @Test
    fun encodesPerSystemSaveAndResetArguments() {
        val vars = Queries.saveMappingVariables("pad:2dc8:6101", "megadrive", "x".repeat(200), listOf(Binding("l2", Button.L3)))
        assertEquals(
            ApiJson.parseToJsonElement(
                """{"controller":"pad:2dc8:6101","system":"megadrive","name":"${"x".repeat(128)}","bindings":[{"input":"l2","button":"L3"}]}""",
            ),
            vars,
        )
        // Every system: an explicit null.
        assertEquals(JsonNull, Queries.saveMappingVariables("xbox", null, "", emptyList())["system"])
        assertEquals(ApiJson.parseToJsonElement("""{"controller":"xbox","system":"snes"}"""), Queries.resetMappingVariables("xbox", "snes"))
        assertEquals(ApiJson.parseToJsonElement("""{"controller":"xbox","system":null}"""), Queries.resetMappingVariables("xbox", null))
        assertEquals(ApiJson.parseToJsonElement("""{"system":"snes"}"""), Queries.mappingsVariables("snes"))
        assertTrue(Queries.SAVE_CONTROLLER_MAPPING.contains("saveControllerMapping(controller: \$controller, system: \$system,"))
        assertTrue(Queries.RESET_CONTROLLER_MAPPING.contains("resetControllerMapping(controller: \$controller, system: \$system)"))
        assertTrue(Queries.CONTROLLER_MAPPINGS.contains("controllerMappings(system: \$system) { controller system name source bindings { input button } }"))
    }

    @Test
    fun decodesASystemsControlsAndTheControllerLayouts() {
        // Trimmed from what SovEmu.Server (controls.rs) answers for the Mega Drive.
        val text = """{"data":{"system":{"controls":{"buttons":[{"button":"UP","label":"↑"},{"button":"Y","label":"A"},
            {"button":"B","label":"B"},{"button":"A","label":"C"},{"button":"SELECT","label":"Mode"}],
            "layout":{"id":"megadrive","name":"Mega Drive","width":160.0,"height":100.0,
            "body":[{"x":12.0,"y":30.0,"w":136.0,"h":42.0,"r":20.0,"tone":0},{"x":60.0,"y":40.0,"w":10.0,"h":10.0,"r":5.0,"tone":1}],
            "keys":[{"key":"Y","label":"A","x":100.0,"y":50.0,"w":10.0,"h":10.0,"r":5.0}]}}},
            "controllerLayouts":[{"id":"xbox","name":"Xbox","width":160.0,"height":100.0,"body":[],
            "keys":[{"key":"l2","label":"LT","x":20.0,"y":14.0,"w":30.0,"h":6.0,"r":3.0}]}]}}"""
        val controls = GraphQL.decode<SystemWithControls?>(text, "system")!!.controls
        assertEquals(listOf("↑", "A", "B", "C", "Mode"), controls.buttons.map { it.label })
        assertEquals(Button.Y, controls.buttons[1].button)
        assertEquals(1, controls.layout.body[1].tone)
        assertEquals(PadKey("Y", "A", 100.0, 50.0, 10.0, 10.0, 5.0), controls.layout.keys.single())
        val layouts = GraphQL.decode<List<PadLayout>>(text, "controllerLayouts")
        assertEquals("xbox", layouts.single().id)
        assertEquals("l2", layouts.single().keys.single().key)
        assertTrue(Queries.CONTROLS.contains("controllerLayouts { id name width height body { x y w h r tone } keys { key label x y w h r } }"))
    }

    @Test
    fun mappingsSayWhichSystemTheyAreFor() {
        val list = GraphQL.decode<List<ControllerMapping>>(
            """{"data":{"controllerMappings":[{"controller":"xbox","system":"snes","name":"","source":"USER","bindings":[]},
               {"controller":"keyboard","system":null,"name":"","source":"ADMIN","bindings":[]}]}}""",
            "controllerMappings",
        )
        assertEquals("snes", list[0].system)
        assertNull(list[1].system)
    }

    @Test
    fun aBadMappingIsABadRequest() {
        val e = assertThrows(ApiException::class.java) {
            GraphQL.decode<ControllerMapping>(
                """{"data":null,"errors":[{"message":"'Tab' is not an input a keyboard can bind","extensions":{"code":"BAD_REQUEST"}}]}""",
                "saveControllerMapping",
            )
        }
        assertEquals("BAD_REQUEST", e.code)
    }
}
