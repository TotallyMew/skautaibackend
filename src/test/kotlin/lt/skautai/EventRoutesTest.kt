package lt.skautai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.database.tables.EventInventoryMovements
import lt.skautai.database.tables.EventInventoryRequests
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.time.LocalDate

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

    private suspend fun HttpClient.activateEventForMovement(token: String, tuntasId: String, eventId: String) {
        val today = LocalDate.now()
        val response = put("/api/events/$eventId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "status": "ACTIVE",
                    "startDate": "${today.minusDays(1)}",
                    "endDate": "${today.plusDays(1)}"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    private suspend fun ApplicationTestBuilder.registerUserWithRole(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String,
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { ", \"organizationalUnitId\": \"$it\"" }.orEmpty()
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField, "expiresInHours": 48 }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Event",
                    "surname": "User",
                    "email": "$email",
                    "password": "testas123",
                    "inviteCode": "$inviteCode"
                }
            """.trimIndent())
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.createUnit(token: String, tuntasId: String, name: String): String {
        val response = post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "SKAUTU_DRAUGOVE" }""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `create stovykla event returns 201 without stovykla details payload`() = testApplication {
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

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vasaros stovykla", body["name"]?.jsonPrimitive?.content)
        assertEquals("STOVYKLA", body["type"]?.jsonPrimitive?.content)
        assertEquals("PLANNING", body["status"]?.jsonPrimitive?.content)
        assertNull(body["stovyklaDetails"])
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
    fun `vadovas can create event but regular member cannot`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "event-member@test.com")
        val (vadovasToken, vadovasUserId) = registerUserWithRole(token, tuntasId, "Vadovas", "event-vadovas@test.com")

        val denied = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Member event",
                    "type": "SUEIGA",
                    "startDate": "2026-06-15",
                    "endDate": "2026-06-15"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Forbidden, denied.status)

        val created = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Vadovu sueiga",
                    "type": "SUEIGA",
                    "startDate": "2026-06-15",
                    "endDate": "2026-06-15"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, created.status)
        val roles = Json.parseToJsonElement(created.bodyAsText()).jsonObject["eventRoles"]!!.jsonArray
        assertEquals("VIRSININKAS", roles.first().jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(vadovasUserId, roles.first().jsonObject["userId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `event location and organizational unit must belong to same tuntas`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (otherToken, otherTuntasId) = client.registerAndActivateTuntininkas(
            email = "other-tuntininkas@test.com",
            tuntasName = "Other Tuntas"
        )

        val foreignLocationResponse = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $otherToken")
            header("X-Tuntas-Id", otherTuntasId)
            setBody("""{ "name": "Foreign storage" }""")
        }
        val foreignLocationId = Json.parseToJsonElement(foreignLocationResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val foreignUnitId = client.createUnit(otherToken, otherTuntasId, "Foreign unit")

        val createWithForeignLocation = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "Cross tenant",
                    "type": "SUEIGA",
                    "startDate": "2026-06-15",
                    "endDate": "2026-06-15",
                    "locationId": "$foreignLocationId"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.BadRequest, createWithForeignLocation.status)

        val eventId = client.createTestEvent(token, tuntasId)
        val updateWithForeignUnit = client.put("/api/events/$eventId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "organizationalUnitId": "$foreignUnitId" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, updateWithForeignUnit.status)
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
        val (_, userId) = registerUserWithRole(token, tuntasId, "Vadovas", "delegated-virsininkas@test.com")

        val assignResponse = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "VIRSININKAS" }""")
        }
        assertEquals(HttpStatusCode.Created, assignResponse.status)

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
        assertEquals(userId, virsininkasList.first().jsonObject["userId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `raw document upload URLs are not publicly served`() = testApplication {
        configureFullApp()
        val (token, _) = client.registerAndActivateTuntininkas()

        val response = client.get("/uploads/documents/test-invoice.pdf") {
            header("Authorization", "Bearer $token")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
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

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
                    "type": "COLLECTIVE", "category": "CAMPING",
                    "quantity": $quantity
                }
            """.trimIndent())
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    // â”€â”€ Removed stovyklaDetails API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    fun `stovykla details endpoint is removed`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.put("/api/events/$eventId/stovykla-details") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "expectedParticipants": 10 }""")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // â”€â”€ PastovyklÄ—s CRUD tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            setBody("""{ "name": "GrupÄ—", "ageGroup": "INVALID_GROUP" }""")
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
            setBody("""{ "name": "Atnaujinta grupÄ—" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Atnaujinta grupÄ—", body["name"]?.jsonPrimitive?.content)
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

    // â”€â”€ PastovyklÄ— Inventory tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    // â”€â”€ Auth tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    @Test
    fun `event inventory plan supports missing items buckets and allocations`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val bucketResponse = client.post("/api/events/$eventId/inventory-buckets") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Virtuve", "type": "KITCHEN" }""")
        }
        assertEquals(HttpStatusCode.Created, bucketResponse.status)
        val bucketId = Json.parseToJsonElement(bucketResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val itemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Puodai", "plannedQuantity": 4 }""")
        }
        assertEquals(HttpStatusCode.Created, itemResponse.status)
        val itemBody = Json.parseToJsonElement(itemResponse.bodyAsText()).jsonObject
        val eventInventoryItemId = itemBody["id"]!!.jsonPrimitive.content
        assertEquals(0, itemBody["availableQuantity"]?.jsonPrimitive?.content?.toInt())
        assertEquals(4, itemBody["shortageQuantity"]?.jsonPrimitive?.content?.toInt())
        assertTrue(itemBody["needsPurchase"]?.jsonPrimitive?.content?.toBoolean() == true)

        val allocationResponse = client.post("/api/events/$eventId/inventory-allocations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$eventInventoryItemId", "bucketId": "$bucketId", "quantity": 2 }""")
        }
        assertEquals(HttpStatusCode.Created, allocationResponse.status)

        val planResponse = client.get("/api/events/$eventId/inventory-plan") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, planResponse.status)
        val plan = Json.parseToJsonElement(planResponse.bodyAsText()).jsonObject
        assertTrue(plan["buckets"]!!.jsonArray.size >= 1)
        assertEquals(1, plan["items"]!!.jsonArray.size)
        assertEquals(1, plan["allocations"]!!.jsonArray.size)
        assertEquals(2, plan["items"]!!.jsonArray.first().jsonObject["allocatedQuantity"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `event purchase can attach invoice complete and add items to inventory`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val itemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Puodai", "plannedQuantity": 3 }""")
        }
        assertEquals(HttpStatusCode.Created, itemResponse.status)
        val eventInventoryItemId = Json.parseToJsonElement(itemResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val purchaseResponse = client.post("/api/events/$eventId/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "items": [
                        {
                            "eventInventoryItemId": "$eventInventoryItemId",
                            "purchasedQuantity": 3,
                            "unitPrice": 12.50
                        }
                    ]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, purchaseResponse.status)
        val purchaseBody = Json.parseToJsonElement(purchaseResponse.bodyAsText()).jsonObject
        val purchaseId = purchaseBody["id"]!!.jsonPrimitive.content
        assertEquals("DRAFT", purchaseBody["status"]?.jsonPrimitive?.content)
        assertEquals(37.5, purchaseBody["totalAmount"]?.jsonPrimitive?.double)

        val invoiceResponse = client.post("/api/events/$eventId/purchases/$purchaseId/invoice") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "invoiceFileUrl": "/uploads/documents/test-invoice.pdf" }""")
        }
        assertEquals(HttpStatusCode.OK, invoiceResponse.status)
        assertEquals(
            "/uploads/documents/test-invoice.pdf",
            Json.parseToJsonElement(invoiceResponse.bodyAsText()).jsonObject["invoiceFileUrl"]?.jsonPrimitive?.content
        )

        val completeResponse = client.post("/api/events/$eventId/purchases/$purchaseId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, completeResponse.status)
        assertEquals("PURCHASED", Json.parseToJsonElement(completeResponse.bodyAsText()).jsonObject["status"]?.jsonPrimitive?.content)

        val addResponse = client.post("/api/events/$eventId/purchases/$purchaseId/add-to-inventory") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, addResponse.status)
        val added = Json.parseToJsonElement(addResponse.bodyAsText()).jsonObject
        assertEquals("ADDED_TO_INVENTORY", added["status"]?.jsonPrimitive?.content)
        assertTrue(
            added["items"]!!.jsonArray.first().jsonObject["addedToInventory"]!!.jsonPrimitive.content.toBoolean()
        )
        assertNotNull(added["items"]!!.jsonArray.first().jsonObject["addedToInventoryItemId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ukvedys assigns planned inventory to pastovykle and custody is visible`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId, quantity = 5)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "name": "Palapine", "plannedQuantity": 5 }""")
        }
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val assignResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "ASSIGN_TO_PASTOVYKLE",
                    "quantity": 3,
                    "pastovykleId": "$pastovykleId"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, assignResponse.status)
        val movement = Json.parseToJsonElement(assignResponse.bodyAsText()).jsonObject
        assertEquals("ASSIGN_TO_PASTOVYKLE", movement["movementType"]?.jsonPrimitive?.content)
        assertEquals(3, movement["quantity"]?.jsonPrimitive?.int)

        val custodyResponse = client.get("/api/events/$eventId/inventory-custody") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, custodyResponse.status)
        val custody = Json.parseToJsonElement(custodyResponse.bodyAsText()).jsonObject["custody"]!!.jsonArray
        assertEquals(1, custody.size)
        assertEquals(pastovykleId, custody.first().jsonObject["pastovykleId"]?.jsonPrimitive?.content)
        assertEquals(3, custody.first().jsonObject["remainingQuantity"]?.jsonPrimitive?.int)
    }

    @Test
    fun `participant checkout cannot exceed assigned pastovykle quantity`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId, quantity = 2)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "name": "Kirvis", "plannedQuantity": 2 }""")
        }
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "ASSIGN_TO_PASTOVYKLE",
                    "quantity": 1,
                    "pastovykleId": "$pastovykleId"
                }
            """.trimIndent())
        }

        val checkoutResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "CHECKOUT_TO_PERSON",
                    "quantity": 2,
                    "pastovykleId": "$pastovykleId"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, checkoutResponse.status)
    }

    @Test
    fun `self checkout and return closes custody record`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId, quantity = 3)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "name": "Puodas", "plannedQuantity": 3 }""")
        }
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val checkoutResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "CHECKOUT_TO_PERSON",
                    "quantity": 2
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, checkoutResponse.status)
        val custodyId = Json.parseToJsonElement(checkoutResponse.bodyAsText()).jsonObject["custodyId"]!!.jsonPrimitive.content

        val returnResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "RETURN_TO_EVENT_STORAGE",
                    "quantity": 2,
                    "fromCustodyId": "$custodyId"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, returnResponse.status)

        val custodyResponse = client.get("/api/events/$eventId/inventory-custody") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val custody = Json.parseToJsonElement(custodyResponse.bodyAsText()).jsonObject["custody"]!!.jsonArray
        val returned = custody.first { it.jsonObject["id"]!!.jsonPrimitive.content == custodyId }.jsonObject
        assertEquals("RETURNED", returned["status"]?.jsonPrimitive?.content)
        assertEquals(0, returned["remainingQuantity"]?.jsonPrimitive?.int)
    }

    @Test
    fun `return to event storage from pastovykle checkout reduces pastovykle remaining quantity`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId, quantity = 5)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "name": "Puodas", "plannedQuantity": 5 }""")
        }
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "ASSIGN_TO_PASTOVYKLE",
                    "quantity": 5,
                    "pastovykleId": "$pastovykleId"
                }
                """.trimIndent()
            )
        }

        val checkoutResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "CHECKOUT_TO_PERSON",
                    "quantity": 3,
                    "pastovykleId": "$pastovykleId"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, checkoutResponse.status)
        val checkoutBody = Json.parseToJsonElement(checkoutResponse.bodyAsText()).jsonObject
        val custodyId = checkoutBody["custodyId"]!!.jsonPrimitive.content

        val returnResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "RETURN_TO_EVENT_STORAGE",
                    "quantity": 3,
                    "fromCustodyId": "$custodyId"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, returnResponse.status)

        val custodyResponse = client.get("/api/events/$eventId/inventory-custody") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, custodyResponse.status)
        val custody = Json.parseToJsonElement(custodyResponse.bodyAsText()).jsonObject["custody"]!!.jsonArray
        val pastovykleCustody = custody.first {
            val row = it.jsonObject
            row["pastovykleId"]?.jsonPrimitive?.content == pastovykleId &&
                row["remainingQuantity"]?.jsonPrimitive?.int == 2
        }.jsonObject
        assertEquals(2, pastovykleCustody["remainingQuantity"]?.jsonPrimitive?.int)
    }

    @Test
    fun `movement request id is idempotent`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId, quantity = 3)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "name": "Kirvis", "plannedQuantity": 3 }""")
        }
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val requestId = "same-request-1"

        val firstResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "CHECKOUT_TO_PERSON",
                    "quantity": 2,
                    "requestId": "$requestId"
                }
                """.trimIndent()
            )
        }
        val secondResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "CHECKOUT_TO_PERSON",
                    "quantity": 2,
                    "requestId": "$requestId"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, firstResponse.status)
        assertEquals(HttpStatusCode.Created, secondResponse.status)
        val firstId = Json.parseToJsonElement(firstResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val secondId = Json.parseToJsonElement(secondResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals(firstId, secondId)

        val movementCount = transaction {
            EventInventoryMovements.selectAll().count()
        }
        assertEquals(1, movementCount)
    }

    @Test
    fun `pastovykle request creates persistent inventory request`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId, quantity = 4)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "name": "Puodelis", "plannedQuantity": 4 }""")
        }
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val requestResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "PASTOVYKLE_REQUEST",
                    "quantity": 2,
                    "pastovykleId": "$pastovykleId",
                    "notes": "Reikia vakarui"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, requestResponse.status)
        val persistedRequest = transaction {
            EventInventoryRequests.selectAll().single()
        }
        assertEquals(2, persistedRequest[EventInventoryRequests.quantity])
        assertEquals("PENDING", persistedRequest[EventInventoryRequests.status])
        assertEquals("Reikia vakarui", persistedRequest[EventInventoryRequests.notes])
    }
}
