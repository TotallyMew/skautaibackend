package lt.skautai

import io.ktor.client.HttpClient
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
class ReservationRoutesTest {

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

    @Test
    fun `create reservation returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        val response = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 2,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("PENDING", body["status"]?.jsonPrimitive?.content)
        assertEquals(2, body["quantity"]?.jsonPrimitive?.content?.toInt())
        assertEquals(itemId, body["itemId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create reservation without token returns 401`() = testApplication {
        configureFullApp()
        val (_, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "00000000-0000-0000-0000-000000000000",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `create reservation with end date before start date returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        val response = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-07",
                    "endDate": "2026-06-01"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create reservation exceeding available quantity returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 2)

        val response = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 10,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `conflict detection blocks overlapping reservation`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 2)

        // Create first reservation and approve it
        val firstResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 2,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        val firstId = Json.parseToJsonElement(firstResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        // Approve it
        client.put("/api/reservations/$firstId/status") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED" }""")
        }

        // Try to reserve same item overlapping dates - quantity now 0
        val conflictResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-05",
                    "endDate": "2026-06-10"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, conflictResponse.status)
    }

    @Test
    fun `get reservations returns 200 with list`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        val response = client.get("/api/reservations") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get single reservation returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/reservations/$reservationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(reservationId, body["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `get nonexistent reservation returns 404`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/reservations/00000000-0000-0000-0000-000000000000") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `approve reservation returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.put("/api/reservations/$reservationId/status") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("APPROVED", body["status"]?.jsonPrimitive?.content)
        assertNotNull(body["approvedByUserId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid status transition returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        // Try to go directly from PENDING to RETURNED - invalid
        val response = client.put("/api/reservations/$reservationId/status") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "RETURNED" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `cancel own reservation returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/reservations/$reservationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `filter reservations by status returns correct results`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 10)

        // Create two reservations
        client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07"
                }
            """.trimIndent())
        }

        val secondResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"
                }
            """.trimIndent())
        }

        val secondId = Json.parseToJsonElement(secondResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        // Approve the second one
        client.put("/api/reservations/$secondId/status") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED" }""")
        }

        val response = client.get("/api/reservations?status=PENDING") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }
}