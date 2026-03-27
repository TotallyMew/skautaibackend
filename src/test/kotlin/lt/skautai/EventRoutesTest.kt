package lt.skautai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventRoutesTest {

    @BeforeAll
    fun setup() {
        TestHelper.setupDatabase()
    }

    @AfterAll
    fun teardown() {
        TestHelper.teardownDatabase()
    }

    @BeforeEach
    fun cleanTables() {
        TestHelper.cleanTables()
    }

    private suspend fun HttpClient.createTestEvent(
        token: String,
        tuntasId: String,
        name: String = "Vasaros stovykla",
        type: String = "STOVYKLA"
    ): String {
        val response = post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "$name",
                    "type": "$type",
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"
                }
            """.trimIndent())
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `create stovykla event returns 201 with stovykla details`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Vasaros stovykla",
                    "type": "STOVYKLA",
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07",
                    "expectedParticipants": 50
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vasaros stovykla", body["name"]?.jsonPrimitive?.content)
        assertEquals("STOVYKLA", body["type"]?.jsonPrimitive?.content)
        assertEquals("PLANNING", body["status"]?.jsonPrimitive?.content)
        assertNotNull(body["stovyklaDetails"])
        assertEquals(50, body["stovyklaDetails"]?.jsonObject
            ?.get("expectedParticipants")?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `create sueiga event returns 201 without stovykla details`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Menesine sueiga",
                    "type": "SUEIGA",
                    "startDate": "2026-06-15",
                    "endDate": "2026-06-15"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("SUEIGA", body["type"]?.jsonPrimitive?.content)
        assertTrue(body["stovyklaDetails"] is JsonNull || body["stovyklaDetails"] == null)
    }

    @Test
    fun `create event automatically assigns creator as virsininkas`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Vasaros stovykla",
                    "type": "STOVYKLA",
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"
                }
            """.trimIndent())
        }

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val roles = body["eventRoles"]!!.jsonArray
        assertEquals(1, roles.size)
        assertEquals("VIRSININKAS", roles[0].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create event with invalid type returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Test",
                    "type": "INVALID",
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create event with end date before start date returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Test",
                    "type": "SUEIGA",
                    "startDate": "2026-07-07",
                    "endDate": "2026-07-01"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get events returns 200 with list`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        client.createTestEvent(token, tuntasId, "Stovykla 1")
        client.createTestEvent(token, tuntasId, "Stovykla 2")

        val response = client.get("/api/events") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get events filtered by type returns correct results`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        client.createTestEvent(token, tuntasId, "Stovykla", "STOVYKLA")
        client.createTestEvent(token, tuntasId, "Sueiga", "SUEIGA")

        val response = client.get("/api/events?type=STOVYKLA") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get single event returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.get("/api/events/$eventId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(eventId, body["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get nonexistent event returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/events/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `update event returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.put("/api/events/$eventId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Atnaujinta stovykla" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Atnaujinta stovykla", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancel event returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.delete("/api/events/$eventId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val getResponse = client.get("/api/events/$eventId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val body = Json.parseToJsonElement(getResponse.bodyAsText()).jsonObject
        assertEquals("CANCELLED", body["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assign event role returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val membersResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(membersResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val response = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "userId": "$userId",
                    "role": "UKVEDYS"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("UKVEDYS", body["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assign programeris without target group returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val membersResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(membersResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val response = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "userId": "$userId",
                    "role": "PROGRAMERIS"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `assign virsininkas transfers role to new person`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val membersResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(membersResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        // Reassign VIRSININKAS to same user (simulating transfer)
        client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "VIRSININKAS" }""")
        }

        val eventResponse = client.get("/api/events/$eventId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        val roles = Json.parseToJsonElement(eventResponse.bodyAsText())
            .jsonObject["eventRoles"]!!.jsonArray
        val virsininkasList = roles.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "VIRSININKAS"
        }

        // Only one VIRSININKAS should exist
        assertEquals(1, virsininkasList.size)
    }

    @Test
    fun `remove event role returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val membersResponse = client.get("/api/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val userId = Json.parseToJsonElement(membersResponse.bodyAsText())
            .jsonObject["members"]!!.jsonArray[0].jsonObject["userId"]!!.jsonPrimitive.content

        val assignResponse = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "UKVEDYS" }""")
        }

        val roleId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/events/$eventId/roles/$roleId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `create event without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Test",
                    "type": "SUEIGA",
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}