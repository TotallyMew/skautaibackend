package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BendrasInventoryRequestRoutesTest {

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

    // Helper to create an active item
    private suspend fun ApplicationTestBuilder.createItem(
        token: String,
        tuntasId: String,
        name: String = "Palapine",
        quantity: Int = 5
    ): String {
        val response = client.post("/api/items") {
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
        val bodyText = response.bodyAsText()
        println("createItem response: ${response.status} â€” $bodyText")
        return Json.parseToJsonElement(bodyText)
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    // Helper to register second user via invite
    private suspend fun ApplicationTestBuilder.registerSecondUser(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String = "second@test.com",
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = getRoleId(tuntasId, roleName)
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            val unitField = organizationalUnitId?.let { """, "organizationalUnitId": "$it"""" } ?: ""
            setBody("""{ "roleId": "$roleId"$unitField, "expiresAt": "2099-01-01T00:00:00Z" }""")
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                    "name": "Second",
                    "surname": "User",
                    "email": "$email",
                    "password": "testas123",
                    "inviteCode": "$inviteCode"
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, registerResponse.status)
        val body = Json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
        return body["token"]!!.jsonPrimitive.content to body["userId"]!!.jsonPrimitive.content
    }

    // Helper to create a draugove
    private suspend fun ApplicationTestBuilder.createDraugove(
        token: String,
        tuntasId: String,
        name: String = "Vilkai"
    ): String {
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "VILKU_DRAUGOVE" }""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun firstMemberId(tuntasId: String): String = transaction {
        var id = ""
        exec("SELECT user_id FROM user_tuntas_memberships WHERE tuntas_id = '$tuntasId' LIMIT 1") { rs ->
            if (rs.next()) id = rs.getString("user_id")
        }
        id
    }

    private suspend fun ApplicationTestBuilder.assignToUnit(
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

    @Test
    fun `create bendras request as Skautas with draugove requires draugininkas approval`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)

        val (skautasToken, skautasId) = registerSecondUser(token, tuntasId, "Skautas")

        // Assign skautas to draugove
        client.post("/api/organizational-units/$draugoveId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$skautasId", "assignmentType": "MEMBER" }""")
        }

        val response = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $skautasToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["needsDraugininkasApproval"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("PENDING", body["draugininkasStatus"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["topLevelStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `create bendras request as Draugininkas skips draugininkas approval`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)

        val (draugininkasToken, _) = registerSecondUser(token, tuntasId, "Draugininkas", organizationalUnitId = draugoveId)
        val draugininkasId = transaction {
            var id = ""
            exec("SELECT id FROM users WHERE email = 'second@test.com' LIMIT 1") { rs -> if (rs.next()) id = rs.getString("id") }
            id
        }
        assignToUnit(token, tuntasId, draugoveId, draugininkasId)

        val response = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $draugininkasToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10"
                    ,"requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["needsDraugininkasApproval"]?.jsonPrimitive?.content?.toBoolean())
        assertNull(body["draugininkasStatus"]?.jsonPrimitive?.content?.let { if (it == "null") null else it })
    }

    @Test
    fun `get all requests returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)
        assignToUnit(token, tuntasId, draugoveId, firstMemberId(tuntasId))

        client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }

        val response = client.get("/api/inventory-requests") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `get single request returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)
        assignToUnit(token, tuntasId, draugoveId, firstMemberId(tuntasId))

        val createResponse = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/inventory-requests/$requestId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(requestId, body["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancel own pending request returns 200`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)
        assignToUnit(token, tuntasId, draugoveId, firstMemberId(tuntasId))

        val createResponse = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/inventory-requests/$requestId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `cancel another users request returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)
        assignToUnit(token, tuntasId, draugoveId, firstMemberId(tuntasId))

        val createResponse = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val (secondToken, _) = registerSecondUser(token, tuntasId, "Skautas")

        val response = client.delete("/api/inventory-requests/$requestId") {
            header("Authorization", "Bearer $secondToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `draugininkas review forwards request successfully`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)

        // Register Draugininkas with draugove already assigned via invite
        val draugininkasRoleId = getRoleId(tuntasId, "Draugininkas")
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
            {
                "roleId": "$draugininkasRoleId",
                "organizationalUnitId": "$draugoveId"
            }
        """.trimIndent())
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText())
            .jsonObject["code"]!!.jsonPrimitive.content

        val draugininkasRegisterResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody("""
            {
                "name": "Draugininkas",
                "surname": "User",
                "email": "draugininkas@test.com",
                "password": "testas123",
                "inviteCode": "$inviteCode"
            }
        """.trimIndent())
        }
        val draugininkasToken = Json.parseToJsonElement(draugininkasRegisterResponse.bodyAsText())
            .jsonObject["token"]!!.jsonPrimitive.content
        val draugininkasId = Json.parseToJsonElement(draugininkasRegisterResponse.bodyAsText())
            .jsonObject["userId"]!!.jsonPrimitive.content

        // Register Skautas and assign to draugove
        val (skautasToken, skautasId) = registerSecondUser(
            token, tuntasId, "Skautas", "skautas@test.com"
        )
        client.post("/api/organizational-units/$draugoveId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$skautasId", "assignmentType": "MEMBER" }""")
        }

        // Skautas creates request routed through draugove
        val createResponse = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $skautasToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
            {
                "itemId": "$itemId",
                "quantity": 1,
                "startDate": "2099-01-01",
                "endDate": "2099-01-10",
                "requestingUnitId": "$draugoveId"
            }
        """.trimIndent())
        }
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        // Draugininkas forwards
        val response = client.post("/api/inventory-requests/$requestId/draugininkas-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $draugininkasToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "FORWARDED" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("FORWARDED", body["draugininkasStatus"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["topLevelStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `top level approve transfers shared item to requesting unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)
        assignToUnit(token, tuntasId, draugoveId, firstMemberId(tuntasId))
        val (reviewerToken, _) = registerSecondUser(token, tuntasId, "Inventorininkas", "shared-approve-reviewer@test.com")

        // Tuntininkas creates the request; a different inventory manager reviews it.
        val createResponse = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.post("/api/inventory-requests/$requestId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $reviewerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "APPROVED" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("APPROVED", body["topLevelStatus"]?.jsonPrimitive?.content)

        // Verify shared inventory was transferred to the requesting unit.
        transaction {
            exec("SELECT COUNT(*) as cnt FROM item_transfers WHERE item_id = '$itemId' AND to_custodian_id = '$draugoveId'") { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("cnt"))
            }
            exec("SELECT quantity, origin FROM items WHERE source_shared_item_id = '$itemId' AND custodian_id = '$draugoveId'") { rs ->
                assertTrue(rs.next())
                assertEquals(1, rs.getInt("quantity"))
                assertEquals("TRANSFERRED_FROM_TUNTAS", rs.getString("origin"))
            }
        }
    }

    @Test
    fun `top level reject does not transfer shared item`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)
        assignToUnit(token, tuntasId, draugoveId, firstMemberId(tuntasId))
        val (reviewerToken, _) = registerSecondUser(token, tuntasId, "Inventorininkas", "shared-reject-reviewer@test.com")

        val createResponse = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.post("/api/inventory-requests/$requestId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $reviewerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "REJECTED", "rejectionReason": "Not needed" }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        transaction {
            exec("SELECT COUNT(*) as cnt FROM item_transfers WHERE item_id = '$itemId'") { rs ->
                assertTrue(rs.next())
                assertEquals(0, rs.getInt("cnt"))
            }
        }
    }

    @Test
    fun `create request with quantity exceeding available returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId, quantity = 2)
        val draugoveId = createDraugove(token, tuntasId)
        assignToUnit(token, tuntasId, draugoveId, firstMemberId(tuntasId))

        val response = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 10,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `create request without token returns 401`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val itemId = createItem(token, tuntasId)
        val draugoveId = createDraugove(token, tuntasId)

        val response = client.post("/api/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "itemId": "$itemId",
                    "quantity": 1,
                    "startDate": "2099-01-01",
                    "endDate": "2099-01-10",
                    "requestingUnitId": "$draugoveId"
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
