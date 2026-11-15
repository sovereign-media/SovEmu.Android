package media.sovereign.sovemu.core

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
               "bindings":[{"input":"south","button":"A"},{"input":"east","button":"B"}]},
               {"controller":"keyboard","name":"","bindings":[{"input":"KeyZ","button":"SELECT"}]}]}}""",
            "controllerMappings",
        )
        assertEquals(listOf(Binding("south", Button.A), Binding("east", Button.B)), mappings[0].bindings)
        assertEquals(Button.SELECT, mappings[1].bindings.single().button)
        assertTrue(GraphQL.decode<Boolean>("""{"data":{"resetControllerMapping":false}}""", "resetControllerMapping").not())
    }

    @Test
    fun encodesASavedMapping() {
        val vars = Queries.saveMappingVariables("pad:2dc8:6101", "x".repeat(200), listOf(Binding("l2", Button.L3)))
        assertEquals(
            ApiJson.parseToJsonElement("""{"controller":"pad:2dc8:6101","name":"${"x".repeat(128)}","bindings":[{"input":"l2","button":"L3"}]}"""),
            vars,
        )
        assertTrue(Queries.SAVE_CONTROLLER_MAPPING.contains("\$bindings: [BindingInput!]!"))
        assertTrue(Queries.CONTROLLER_MAPPINGS.contains("controllerMappings { controller name bindings { input button } }"))
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
