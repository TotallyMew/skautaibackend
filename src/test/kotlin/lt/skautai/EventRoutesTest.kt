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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun HttpClient.createTestPastovykle(
        token: String,
        tuntasId: String,
        eventId: String,
        name: String = "Vilkai"
    ): String {
        val response = post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name" }""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.createTestItem(
        token: String,
        tuntasId: String,
        name: String = "Palapine",
        quantity: Int = 5
    ): String {
        val response = post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "$name",
                    "category": "COLLECTIVE",
                    "quantity": $quantity
                }
            """.trimIndent())
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    // ── StovyklaDetails tests ─────────────────────────────────────────────────

    @Test
    fun `update stovykla details returns 200 with updated fields`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.put("/api/events/$eventId/stovykla-details") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "expectedParticipants": 80,
                    "registrationDeadline": "2026-06-15"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(80, body["expectedParticipants"]?.jsonPrimitive?.content?.toInt())
        assertEquals("2026-06-15", body["registrationDeadline"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update stovykla details on non-stovykla event returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId, type = "SUEIGA")

        val response = client.put("/api/events/$eventId/stovykla-details") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "expectedParticipants": 10 }""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `update stovykla details on nonexistent event returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.put("/api/events/00000000-0000-0000-0000-000000000000/stovykla-details") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "expectedParticipants": 10 }""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `update stovykla details with invalid date format returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.put("/api/events/$eventId/stovykla-details") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "registrationDeadline": "15/06/2026" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ── Pastovyklės CRUD tests ────────────────────────────────────────────────

    @Test
    fun `create pastovykle returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "ageGroup": "VILKAI" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vilkai", body["name"]?.jsonPrimitive?.content)
        assertEquals("VILKAI", body["ageGroup"]?.jsonPrimitive?.content)
        assertEquals(eventId, body["eventId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create pastovykle on non-stovykla event returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId, type = "SUEIGA")

        val response = client.post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create pastovykle with blank name returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "   " }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create pastovykle with invalid age group returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Grupė", "ageGroup": "INVALID_GROUP" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get pastovykles returns 200 with list`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.createTestPastovykle(token, tuntasId, eventId, "Vilkai")
        client.createTestPastovykle(token, tuntasId, eventId, "Skautai")

        val response = client.get("/api/events/$eventId/pastovykles") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get pastovykles on non-stovykla event returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId, type = "RENGINYS")

        val response = client.get("/api/events/$eventId/pastovykles") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `get single pastovykle returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)

        val response = client.get("/api/events/$eventId/pastovykles/$pid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(pid, body["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get nonexistent pastovykle returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.get("/api/events/$eventId/pastovykles/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `update pastovykle name returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)

        val response = client.put("/api/events/$eventId/pastovykles/$pid") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Atnaujinta grupė" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Atnaujinta grupė", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update pastovykle with invalid age group returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)

        val response = client.put("/api/events/$eventId/pastovykles/$pid") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "ageGroup": "BAD_GROUP" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `delete pastovykle with no inventory returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)

        val response = client.delete("/api/events/$eventId/pastovykles/$pid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `delete pastovykle with assigned inventory returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 2 }""")
        }

        val response = client.delete("/api/events/$eventId/pastovykles/$pid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `delete nonexistent pastovykle returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.delete("/api/events/$eventId/pastovykles/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Pastovyklė Inventory tests ────────────────────────────────────────────

    @Test
    fun `assign inventory to pastovykle returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 3 }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(itemId, body["itemId"]?.jsonPrimitive?.content)
        assertEquals(3, body["quantityAssigned"]?.jsonPrimitive?.content?.toInt())
        assertEquals(0, body["quantityReturned"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `assign inventory with quantity zero returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 0 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `assign inventory with nonexistent item returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)

        val response = client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "00000000-0000-0000-0000-000000000000", "quantity": 1 }""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `assign inventory to nonexistent pastovykle returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val itemId = client.createTestItem(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles/00000000-0000-0000-0000-000000000000/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 1 }""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `get pastovykle inventory returns 200 with list`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 2 }""")
        }

        val response = client.get("/api/events/$eventId/pastovykles/$pid/inventory") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `update inventory assignment to mark return returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        val assignResponse = client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 4 }""")
        }
        val invId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/events/$eventId/pastovykles/$pid/inventory/$invId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantityReturned": 4 }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(4, body["quantityReturned"]?.jsonPrimitive?.content?.toInt())
        assertNotNull(body["returnedAt"]?.jsonPrimitive?.content)
    }

    @Test
    fun `update inventory returned quantity exceeding assigned returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        val assignResponse = client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 2 }""")
        }
        val invId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/events/$eventId/pastovykles/$pid/inventory/$invId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantityReturned": 99 }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `remove inventory assignment with no returns returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        val assignResponse = client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 2 }""")
        }
        val invId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/events/$eventId/pastovykles/$pid/inventory/$invId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `remove inventory assignment with partial return returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId)

        val assignResponse = client.post("/api/events/$eventId/pastovykles/$pid/inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 4 }""")
        }
        val invId = Json.parseToJsonElement(assignResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        client.put("/api/events/$eventId/pastovykles/$pid/inventory/$invId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantityReturned": 2 }""")
        }

        val response = client.delete("/api/events/$eventId/pastovykles/$pid/inventory/$invId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `remove nonexistent inventory assignment returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pid = client.createTestPastovykle(token, tuntasId, eventId)

        val response = client.delete("/api/events/$eventId/pastovykles/$pid/inventory/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ── Auth tests ────────────────────────────────────────────────────────────

    @Test
    fun `create pastovykle without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events/00000000-0000-0000-0000-000000000000/pastovykles") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai" }""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `get pastovykles without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/events/00000000-0000-0000-0000-000000000000/pastovykles") {
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `assign inventory without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events/00000000-0000-0000-0000-000000000000/pastovykles/00000000-0000-0000-0000-000000000000/inventory") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "00000000-0000-0000-0000-000000000000", "quantity": 1 }""")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}