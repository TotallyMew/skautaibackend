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
        quantity: Int = 5,
        custodianId: String? = null,
        type: String = "COLLECTIVE"
    ): String {
        val custodianField = custodianId?.let { """, "custodianId": "$it"""" }.orEmpty()
        val response = post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "$name",
                    "type": "$type",
                    "category": "CAMPING",
                    "quantity": $quantity
                    $custodianField
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

    private suspend fun ApplicationTestBuilder.registerUserWithRole(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String,
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = TestHelper.getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { """, "organizationalUnitId": "$it"""" }.orEmpty()
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content
        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""{ "name": "Role", "surname": "User", "email": "$email", "password": "testas123", "inviteCode": "$inviteCode" }""")
        }
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.assignMember(
        token: String,
        tuntasId: String,
        unitId: String,
        userId: String
    ) {
        client.post("/api/organizational-units/$unitId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "assignmentType": "MEMBER" }""")
        }
    }

    private suspend fun ApplicationTestBuilder.createLocation(
        token: String,
        tuntasId: String,
        name: String,
        visibility: String = "PUBLIC",
        ownerUnitId: String? = null
    ): String {
        val ownerUnitField = ownerUnitId?.let { """, "ownerUnitId": "$it"""" }.orEmpty()
        val response = client.post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "visibility": "$visibility"$ownerUnitField }""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
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
    fun `get reservations supports limit and offset pagination`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId)

        listOf("2026-06-01", "2026-06-08", "2026-06-15").forEach { startDate ->
            client.post("/api/reservations") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-Tuntas-Id", tuntasId)
                setBody(
                    """{
                        "itemId": "$itemId",
                        "quantity": 1,
                        "startDate": "$startDate",
                        "endDate": "$startDate"
                    }""".trimIndent()
                )
            }
        }

        val response = client.get("/api/reservations?limit=2&offset=0") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["reservations"]!!.jsonArray.size)
        assertEquals(3, body["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, body["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals("true", body["hasMore"]!!.jsonPrimitive.content)
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

    @Test
    fun `reservation list is scoped for unit approver and item filter works`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitA = createUnit(token, tuntasId, "Unit A")
        val unitB = createUnit(token, tuntasId, "Unit B")
        val itemA = client.createTestItem(token, tuntasId, name = "A item", custodianId = unitA)
        val itemB = client.createTestItem(token, tuntasId, name = "B item", custodianId = unitB)
        val itemShared = client.createTestItem(token, tuntasId, name = "Shared item")
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "leader-a-res@test.com", unitA)
        val (memberAToken, memberAId) = registerUserWithRole(token, tuntasId, "Skautas", "member-a-res@test.com")
        val (memberBToken, memberBId) = registerUserWithRole(token, tuntasId, "Skautas", "member-b-res@test.com")
        assignMember(token, tuntasId, unitA, memberAId)
        assignMember(token, tuntasId, unitB, memberBId)

        client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberAToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemA", "quantity": 1, "startDate": "2026-10-01", "endDate": "2026-10-03", "requestingUnitId": "$unitA" }""")
        }
        client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberBToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemB", "quantity": 1, "startDate": "2026-10-04", "endDate": "2026-10-05", "requestingUnitId": "$unitB" }""")
        }
        client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemShared", "quantity": 1, "startDate": "2026-10-06", "endDate": "2026-10-07" }""")
        }

        val response = client.get("/api/reservations?itemId=$itemA") {
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]!!.jsonPrimitive.int)
        val reservation = body["reservations"]!!.jsonArray.first().jsonObject
        assertEquals(unitA, reservation["requestingUnitId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `foreign member cannot access another reservation details`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 5)
        val (ownerToken, _) = client.registerSecondUser(token, tuntasId, "owner-res@test.com")
        val (otherToken, _) = client.registerSecondUser(token, tuntasId, "other-res@test.com")

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $ownerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 1, "startDate": "2026-10-08", "endDate": "2026-10-09" }""")
        }
        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/reservations/$reservationId") {
            header("Authorization", "Bearer $otherToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `create reservation validates blank fields bad ids and mixed unit inventory`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitA = createUnit(token, tuntasId, "Mixed A")
        val unitB = createUnit(token, tuntasId, "Mixed B")
        val itemA = client.createTestItem(token, tuntasId, name = "Item A", custodianId = unitA)
        val itemB = client.createTestItem(token, tuntasId, name = "Item B", custodianId = unitB)
        val unitLocation = createLocation(token, tuntasId, "Unit spot", visibility = "UNIT", ownerUnitId = unitA)

        suspend fun create(body: String) = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(body)
        }

        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": " ", "itemId": "$itemA", "quantity": 1, "startDate": "2026-10-10", "endDate": "2026-10-11" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "No items", "startDate": "2026-10-10", "endDate": "2026-10-11" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Blank start", "itemId": "$itemA", "quantity": 1, "startDate": "", "endDate": "2026-10-11" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Blank end", "itemId": "$itemA", "quantity": 1, "startDate": "2026-10-10", "endDate": "" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Bad item", "itemId": "bad-id", "quantity": 1, "startDate": "2026-10-10", "endDate": "2026-10-11" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Bad start", "itemId": "$itemA", "quantity": 1, "startDate": "2026/10/10", "endDate": "2026-10-11" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Bad end", "itemId": "$itemA", "quantity": 1, "startDate": "2026-10-10", "endDate": "2026/10/11" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Missing unit", "itemId": "$itemA", "quantity": 1, "startDate": "2026-10-10", "endDate": "2026-10-11", "requestingUnitId": "00000000-0000-0000-0000-000000000000" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Mixed units", "items": [{ "itemId": "$itemA", "quantity": 1 }, { "itemId": "$itemB", "quantity": 1 }], "startDate": "2026-10-10", "endDate": "2026-10-11" }""").status)
        assertEquals(HttpStatusCode.BadRequest, create("""{ "title": "Wrong location", "itemId": "$itemB", "quantity": 1, "startDate": "2026-10-10", "endDate": "2026-10-11", "pickupLocationId": "$unitLocation" }""").status)
    }

    @Test
    fun `unit inventory reservation for member is pending and leader can review it`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Review unit")
        val itemId = client.createTestItem(token, tuntasId, custodianId = unitId)
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "unit-res-leader@test.com", unitId)
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "unit-res-member@test.com")
        assignMember(token, tuntasId, unitId, memberId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 1, "startDate": "2026-10-12", "endDate": "2026-10-13", "requestingUnitId": "$unitId" }""")
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createdBody = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        assertEquals("PENDING", createdBody["status"]!!.jsonPrimitive.content)
        assertEquals("PENDING", createdBody["unitReviewStatus"]!!.jsonPrimitive.content)
        assertEquals("NOT_REQUIRED", createdBody["topLevelReviewStatus"]?.jsonPrimitive?.content ?: "NOT_REQUIRED")
        val reservationId = createdBody["id"]!!.jsonPrimitive.content

        val reviewResponse = client.post("/api/reservations/$reservationId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED", "notes": "Tinka" }""")
        }
        assertEquals(HttpStatusCode.OK, reviewResponse.status)
        val reviewedBody = Json.parseToJsonElement(reviewResponse.bodyAsText()).jsonObject
        assertEquals("APPROVED", reviewedBody["status"]!!.jsonPrimitive.content)
        assertEquals("APPROVED", reviewedBody["unitReviewStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun `shared reservation top level review and movement lifecycle work`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Shared review unit")
        val itemId = client.createTestItem(token, tuntasId, quantity = 4)
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "shared-flow@test.com", unitId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "title": "Shared flow", "itemId": "$itemId", "quantity": 2, "startDate": "2026-10-14", "endDate": "2026-10-15" }""")
        }
        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val approveResponse = client.post("/api/reservations/$reservationId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED", "notes": "Gerai" }""")
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)

        val issueResponse = client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$itemId", "quantity": 2 }], "notes": "Isduota" }""")
        }
        assertEquals(HttpStatusCode.OK, issueResponse.status)
        val issueBody = Json.parseToJsonElement(issueResponse.bodyAsText()).jsonObject
        assertEquals("ACTIVE", issueBody["status"]!!.jsonPrimitive.content)

        val markReturned = client.post("/api/reservations/$reservationId/mark-returned") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$itemId", "quantity": 2 }] }""")
        }
        assertEquals(HttpStatusCode.OK, markReturned.status)

        val returnResponse = client.post("/api/reservations/$reservationId/return") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$itemId", "quantity": 2 }] }""")
        }
        assertEquals(HttpStatusCode.OK, returnResponse.status)
        val returnedBody = Json.parseToJsonElement(returnResponse.bodyAsText()).jsonObject
        assertEquals("RETURNED", returnedBody["status"]!!.jsonPrimitive.content)

        val movementsResponse = client.get("/api/reservations/$reservationId/movements") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, movementsResponse.status)
        assertEquals(
            3,
            Json.parseToJsonElement(movementsResponse.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.int
        )
    }

    @Test
    fun `mixed reservation movements are limited to matching inventory responsibility`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Mixed movement unit")
        val unitItemId = client.createTestItem(token, tuntasId, name = "Unit rope", quantity = 4, custodianId = unitId)
        val sharedItemId = client.createTestItem(token, tuntasId, name = "Shared stove", quantity = 4)
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "mixed-move-leader@test.com", unitId)
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "mixed-move-member@test.com")
        assignMember(token, tuntasId, unitId, memberId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "title": "Mixed movement", "items": [{ "itemId": "$unitItemId", "quantity": 1 }, { "itemId": "$sharedItemId", "quantity": 1 }], "startDate": "2026-10-20", "endDate": "2026-10-21", "requestingUnitId": "$unitId" }""")
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val unitReview = client.post("/api/reservations/$reservationId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, unitReview.status)
        val afterUnitReview = Json.parseToJsonElement(unitReview.bodyAsText()).jsonObject
        assertEquals("PENDING", afterUnitReview["status"]!!.jsonPrimitive.content)
        assertEquals("APPROVED", afterUnitReview["unitReviewStatus"]!!.jsonPrimitive.content)
        assertEquals("PENDING", afterUnitReview["topLevelReviewStatus"]!!.jsonPrimitive.content)

        val topLevelReview = client.post("/api/reservations/$reservationId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, topLevelReview.status)

        val topLevelIssuesUnitItem = client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$unitItemId", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, topLevelIssuesUnitItem.status)

        val leaderIssuesSharedItem = client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$sharedItemId", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, leaderIssuesSharedItem.status)

        val leaderIssuesUnitItem = client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$unitItemId", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.OK, leaderIssuesUnitItem.status)

        val topLevelIssuesSharedItem = client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$sharedItemId", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.OK, topLevelIssuesSharedItem.status)
    }

    @Test
    fun `reservation movement and review endpoints validate permissions and states`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Permission unit")
        val itemId = client.createTestItem(token, tuntasId, quantity = 4, custodianId = unitId)
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "perm-leader@test.com", unitId)
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "perm-member@test.com")
        val (outsiderToken, _) = client.registerSecondUser(token, tuntasId, "perm-outsider@test.com")
        assignMember(token, tuntasId, unitId, memberId)

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 2, "startDate": "2026-10-16", "endDate": "2026-10-17", "requestingUnitId": "$unitId" }""")
        }
        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val outsiderReview = client.post("/api/reservations/$reservationId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $outsiderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.Forbidden, outsiderReview.status)

        val leaderReview = client.post("/api/reservations/$reservationId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, leaderReview.status)

        val outsiderIssue = client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $outsiderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$itemId", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.Forbidden, outsiderIssue.status)

        val badMarkReturned = client.post("/api/reservations/$reservationId/mark-returned") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $outsiderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$itemId", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.Forbidden, badMarkReturned.status)
    }

    @Test
    fun `pickup and return time proposals validate responses and ownership`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = client.createTestItem(token, tuntasId, quantity = 3)
        val publicLocationId = createLocation(token, tuntasId, "Public spot")
        val (managerToken, _) = registerUserWithRole(token, tuntasId, "Tuntininko pavaduotojas", "pickup-manager@test.com")

        val createResponse = client.post("/api/reservations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "quantity": 1, "startDate": "2026-10-18", "endDate": "2026-10-19" }""")
        }
        val reservationId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val invalidPickupResponse = client.put("/api/reservations/$reservationId/pickup-time") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "pickupAt": "2026/10/18T10:00:00Z" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidPickupResponse.status)

        val proposePickup = client.put("/api/reservations/$reservationId/pickup-time") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "pickupAt": "2026-10-18T10:00:00Z", "pickupLocationId": "$publicLocationId" }""")
        }
        assertEquals(HttpStatusCode.OK, proposePickup.status)

        val selfAccept = client.put("/api/reservations/$reservationId/pickup-time") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "response": "ACCEPT" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, selfAccept.status)

        val managerAccept = client.put("/api/reservations/$reservationId/pickup-time") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $managerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "response": "ACCEPT" }""")
        }
        assertEquals(HttpStatusCode.OK, managerAccept.status)

        client.post("/api/reservations/$reservationId/issue") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "itemId": "$itemId", "quantity": 1 }] }""")
        }

        val proposeReturn = client.put("/api/reservations/$reservationId/return-time") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "returnAt": "2026-10-19T12:00:00Z", "returnLocationId": "$publicLocationId" }""")
        }
        assertEquals(HttpStatusCode.OK, proposeReturn.status)

        val invalidReturnResponse = client.put("/api/reservations/$reservationId/return-time") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $managerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "response": "DECLINE" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidReturnResponse.status)
    }
}
