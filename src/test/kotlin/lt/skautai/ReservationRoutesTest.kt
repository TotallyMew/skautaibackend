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
                    "type": "COLLECTIVE",
                    "category": "CAMPING",
                    "quantity": $quantity
                }
            """.trimIndent())
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.registerSecondUser(
        token: String,
        tuntasId: String,
        email: String = "second@test.com"
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, "Skautas")
        val inviteResponse = post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""{ "name": "Second", "surname": "User", "email": "$email", "password": "testas123", "inviteCode": "$inviteCode" }""")
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createUnit(token: String, tuntasId: String, name: String): String {
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "SKAUTU_DRAUGOVE" }""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.registerScoutInUnit(
        token: String,
        tuntasId: String,
        unitId: String,
        email: String
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, "Skautas")
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId", "organizationalUnitId": "$unitId" }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""{ "name": "Scout", "surname": "User", "email": "$email", "password": "testas123", "inviteCode": "$inviteCode" }""")
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
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
        assertEquals("APPROVED", body["status"]?.jsonPrimitive?.content)
        assertEquals(2, body["totalQuantity"]?.jsonPrimitive?.content?.toInt())
        val item = body["items"]!!.jsonArray.first().jsonObject
        assertEquals(2, item["quantity"]?.jsonPrimitive?.content?.toInt())
        assertEquals(itemId, item["itemId"]?.jsonPrimitive?.content)
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
    fun `regular member cannot reserve shared inventory for another unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Savas vienetas")
        val otherUnitId = createUnit(token, tuntasId, "Svetimas vienetas")
        val itemId = client.createTestItem(token, tuntasId, quantity = 3)
        val (memberToken, _) = registerScoutInUnit(token, tuntasId, ownUnitId, "reservation-scope@test.com")

        val response = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2026-06-01",
                    "endDate": "2026-06-07",
                    "requestingUnitId": "$otherUnitId"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `availability hides foreign unit items from regular member`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val ownUnitId = createUnit(token, tuntasId, "Savas vienetas")
        val otherUnitId = createUnit(token, tuntasId, "Svetimas vienetas")
        val ownItemId = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Sava palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$ownUnitId" }""")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content }
        val otherItemId = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Svetima palapine", "type": "COLLECTIVE", "category": "CAMPING", "quantity": 1, "custodianId": "$otherUnitId" }""")
        }.bodyAsText().let { Json.parseToJsonElement(it).jsonObject["id"]!!.jsonPrimitive.content }
        val (memberToken, _) = registerScoutInUnit(token, tuntasId, ownUnitId, "availability-scope@test.com")

        val response = client.get("/api/reservations/availability?startDate=2026-06-01&endDate=2026-06-07") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val ids = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["items"]!!.jsonArray
            .map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            .toSet()
        assertEquals(true, ownItemId in ids)
        assertEquals(false, otherItemId in ids)
    }

    @Test
    fun `conflict detection blocks overlapping reservation`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 2)

        // Create first reservation. Tuntas leadership reservations are auto-approved.
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
    fun `legacy reservation status endpoint returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)
        val (secondToken, _) = client.registerSecondUser(token, tuntasId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $secondToken")
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

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invalid status transition returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)
        val (secondToken, _) = client.registerSecondUser(token, tuntasId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $secondToken")
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
        val (secondToken, _) = client.registerSecondUser(token, tuntasId)

        // Create two reservations
        client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $secondToken")
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

        client.post("/api/reservations") {
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

        val response = client.get("/api/reservations?status=PENDING") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `approved reservation serializes remaining movement quantities`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 5)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "title": "Isdavimui",
                    "items": [
                        { "itemId": "$itemId", "quantity": 2 }
                    ],
                    "startDate": "2026-08-01",
                    "endDate": "2026-08-07"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, createResponse.status)
        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/reservations/$reservationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val item = Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["items"]!!.jsonArray.first().jsonObject

        assertEquals(2, item["quantity"]!!.jsonPrimitive.int)
        assertEquals(0, item["issuedQuantity"]!!.jsonPrimitive.int)
        assertEquals(0, item["returnedQuantity"]!!.jsonPrimitive.int)
        assertEquals(0, item["markedReturnedQuantity"]!!.jsonPrimitive.int)
        assertEquals(2, item["remainingToIssue"]!!.jsonPrimitive.int)
        assertEquals(0, item["remainingToReturn"]!!.jsonPrimitive.int)
        assertEquals(0, item["remainingToMarkReturned"]!!.jsonPrimitive.int)
        assertEquals(0, item["remainingToReceive"]!!.jsonPrimitive.int)
    }

    @Test
    fun `issuing reservation updates remaining movement quantities`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 5)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "title": "Dalinis isdavimas",
                    "items": [
                        { "itemId": "$itemId", "quantity": 2 }
                    ],
                    "startDate": "2026-09-01",
                    "endDate": "2026-09-07"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, createResponse.status)
        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val issueResponse = client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "items": [
                        { "itemId": "$itemId", "quantity": 1 }
                    ]
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, issueResponse.status)
        val body = Json.parseToJsonElement(issueResponse.bodyAsText()).jsonObject
        val item = body["items"]!!.jsonArray.first().jsonObject

        assertEquals("ACTIVE", body["status"]!!.jsonPrimitive.content)
        assertEquals(2, item["quantity"]!!.jsonPrimitive.int)
        assertEquals(1, item["issuedQuantity"]!!.jsonPrimitive.int)
        assertEquals(0, item["returnedQuantity"]!!.jsonPrimitive.int)
        assertEquals(0, item["markedReturnedQuantity"]!!.jsonPrimitive.int)
        assertEquals(1, item["remainingToIssue"]!!.jsonPrimitive.int)
        assertEquals(1, item["remainingToReturn"]!!.jsonPrimitive.int)
        assertEquals(1, item["remainingToMarkReturned"]!!.jsonPrimitive.int)
        assertEquals(0, item["remainingToReceive"]!!.jsonPrimitive.int)
    }
}
