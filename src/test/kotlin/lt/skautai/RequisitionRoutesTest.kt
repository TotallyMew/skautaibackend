package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.database.tables.DraugoveRequisitionItems
import lt.skautai.database.tables.Items
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequisitionRoutesTest {

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

    private suspend fun ApplicationTestBuilder.createUnit(
        token: String,
        tuntasId: String,
        name: String = "Vilkai"
    ): String {
        val response = client.post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "SKAUTU_DRAUGOVE" }""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.registerUserWithRole(
        token: String,
        tuntasId: String,
        roleName: String,
        email: String,
        organizationalUnitId: String? = null
    ): Pair<String, String> {
        val roleId = getRoleId(tuntasId, roleName)
        val unitField = organizationalUnitId?.let { """, "organizationalUnitId": "$it"""" }.orEmpty()
        val inviteResponse = client.post("/api/invitations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "roleId": "$roleId"$unitField, "expiresInHours": 48 }""")
        }
        val inviteCode = Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content

        val registerResponse = client.post("/api/auth/register/invite") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                    "name": "Test",
                    "surname": "Narys",
                    "email": "$email",
                    "password": "testas123",
                    "inviteCode": "$inviteCode"
                }
                """.trimIndent()
            )
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

    private suspend fun ApplicationTestBuilder.createRequisition(
        token: String,
        tuntasId: String,
        requestingUnitId: String?
    ) = client.post("/api/requisitions") {
        contentType(ContentType.Application.Json)
        header("Authorization", "Bearer $token")
        header("X-Tuntas-Id", tuntasId)
        val unitField = requestingUnitId?.let { """"requestingUnitId": "$it",""" }.orEmpty()
        setBody(
            """
            {
                $unitField
                "items": [
                    { "itemName": "Nauja palapine", "quantity": 2 }
                ]
            }
            """.trimIndent()
        )
    }

    private suspend fun ApplicationTestBuilder.createItem(
        token: String,
        tuntasId: String,
        name: String,
        quantity: Int = 1
    ): String {
        val response = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "$name",
                    "type": "COLLECTIVE",
                    "category": "TOOLS",
                    "quantity": $quantity,
                    "condition": "GOOD",
                    "origin": "UNIT_ACQUIRED"
                }
                """.trimIndent()
            )
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `unit leader cannot auto approve own unit requisition`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (leaderToken, _) = registerUserWithRole(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = "leader@test.com",
            organizationalUnitId = unitId
        )

        val response = createRequisition(leaderToken, tuntasId, unitId)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("SUBMITTED", body["status"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["unitReviewStatus"]?.jsonPrimitive?.content)
        assertEquals("NOT_REQUIRED", body["topLevelReviewStatus"]?.jsonPrimitive?.content)
        val item = body["items"]!!.jsonArray.first().jsonObject
        assertEquals(null, item["quantityApproved"])
    }

    @Test
    fun `regular member creates own unit requisition as pending`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId)
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "member@test.com")
        assignMember(token, tuntasId, unitId, memberId)

        val response = createRequisition(memberToken, tuntasId, unitId)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("SUBMITTED", body["status"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["unitReviewStatus"]?.jsonPrimitive?.content)
        assertEquals("NOT_REQUIRED", body["topLevelReviewStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tuntas level requisition can be created without unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = createRequisition(token, tuntasId, requestingUnitId = null)

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(null, body["requestingUnitId"])
        assertEquals("SKIPPED", body["unitReviewStatus"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["topLevelReviewStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `regular member cannot create tuntas level requisition`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "member@test.com")

        val response = createRequisition(memberToken, tuntasId, requestingUnitId = null)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `vadovas rank cannot create tuntas level requisition`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (vadovasToken, _) = registerUserWithRole(token, tuntasId, "Vadovas", "vadovas-rank@test.com")

        val response = createRequisition(vadovasToken, tuntasId, requestingUnitId = null)

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `draugininkas can create tuntas level requisition`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Draugove")
        val (leaderToken, _) = registerUserWithRole(
            token = token,
            tuntasId = tuntasId,
            roleName = "Draugininkas",
            email = "draugininkas-top@test.com",
            organizationalUnitId = unitId
        )

        val response = createRequisition(leaderToken, tuntasId, requestingUnitId = null)
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("SKIPPED", body["unitReviewStatus"]?.jsonPrimitive?.content)
        assertEquals("PENDING", body["topLevelReviewStatus"]?.jsonPrimitive?.content)
    }

    @Test
    fun `requisition supports restock type with existing item id`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createdItemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Test palapine",
                    "type": "COLLECTIVE",
                    "category": "CAMPING",
                    "quantity": 2,
                    "condition": "GOOD",
                    "origin": "UNIT_ACQUIRED"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, createdItemResponse.status)
        val itemId = Json.parseToJsonElement(createdItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val response = client.post("/api/requisitions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "items": [
                        {
                            "itemName": "palapiniu papildymas",
                            "requestType": "RESTOCK_EXISTING",
                            "existingItemId": "$itemId",
                            "quantity": 3
                        }
                    ]
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val createdItem = body["items"]!!.jsonArray.first().jsonObject
        assertEquals("RESTOCK_EXISTING", createdItem["requestType"]!!.jsonPrimitive.content)
        assertEquals(itemId, createdItem["existingItemId"]!!.jsonPrimitive.content)
        assertEquals("Test palapine", createdItem["itemName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `requisition listing is scoped to creator reviewable unit and top level reviewer`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitA = createUnit(token, tuntasId, "Unit A")
        val unitB = createUnit(token, tuntasId, "Unit B")
        val (memberAToken, memberAId) = registerUserWithRole(token, tuntasId, "Skautas", "member-a@test.com")
        val (memberBToken, memberBId) = registerUserWithRole(token, tuntasId, "Skautas", "member-b@test.com")
        val (leaderAToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "leader-a@test.com", unitA)
        val (inventorToken, _) = registerUserWithRole(token, tuntasId, "Tuntininko pavaduotojas", "top-reviewer@test.com")
        assignMember(token, tuntasId, unitA, memberAId)
        assignMember(token, tuntasId, unitB, memberBId)

        val requestAId = Json.parseToJsonElement(createRequisition(memberAToken, tuntasId, unitA).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        createRequisition(memberBToken, tuntasId, unitB)

        val memberList = client.get("/api/requisitions") {
            header("Authorization", "Bearer $memberAToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, memberList.status)
        assertEquals(
            1,
            Json.parseToJsonElement(memberList.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt()
        )

        val leaderList = client.get("/api/requisitions") {
            header("Authorization", "Bearer $leaderAToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, leaderList.status)
        val leaderItems = Json.parseToJsonElement(leaderList.bodyAsText()).jsonObject["requests"]!!.jsonArray
        assertEquals(1, leaderItems.size)
        assertEquals(requestAId, leaderItems.first().jsonObject["id"]!!.jsonPrimitive.content)

        val topLevelList = client.get("/api/requisitions") {
            header("Authorization", "Bearer $inventorToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, topLevelList.status)
        assertEquals(
            2,
            Json.parseToJsonElement(topLevelList.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt()
        )
    }

    @Test
    fun `get requisition denies inaccessible request`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitA = createUnit(token, tuntasId, "Scopers A")
        val unitB = createUnit(token, tuntasId, "Scopers B")
        val (memberAToken, memberAId) = registerUserWithRole(token, tuntasId, "Skautas", "scoped-a@test.com")
        val (memberBToken, memberBId) = registerUserWithRole(token, tuntasId, "Skautas", "scoped-b@test.com")
        assignMember(token, tuntasId, unitA, memberAId)
        assignMember(token, tuntasId, unitB, memberBId)

        val requestId = Json.parseToJsonElement(createRequisition(memberAToken, tuntasId, unitA).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.get("/api/requisitions/$requestId") {
            header("Authorization", "Bearer $memberBToken")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("Prašymas nepasiekiamas"))
    }

    @Test
    fun `create requisition validates payload and unit ownership`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitA = createUnit(token, tuntasId, "Owned Unit")
        val unitB = createUnit(token, tuntasId, "Foreign Unit")
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "validator@test.com")
        assignMember(token, tuntasId, unitA, memberId)

        suspend fun postRaw(body: String) = client.post("/api/requisitions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody(body)
        }

        assertEquals(
            HttpStatusCode.BadRequest,
            postRaw("""{ "requestingUnitId": "$unitA", "items": [] }""").status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postRaw("""{ "requestingUnitId": "$unitA", "items": [{ "itemName": " ", "quantity": 1 }] }""").status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postRaw("""{ "requestingUnitId": "$unitA", "items": [{ "itemName": "Kirvis", "quantity": 0 }] }""").status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postRaw("""{ "requestingUnitId": "not-a-uuid", "items": [{ "itemName": "Kirvis", "quantity": 1 }] }""").status
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postRaw("""{ "requestingUnitId": "$unitA", "neededByDate": "2026/08/10", "items": [{ "itemName": "Kirvis", "quantity": 1 }] }""").status
        )

        val foreignUnit = postRaw("""{ "requestingUnitId": "$unitB", "items": [{ "itemName": "Kirvis", "quantity": 1 }] }""")
        assertEquals(HttpStatusCode.BadRequest, foreignUnit.status)
        assertTrue(foreignUnit.bodyAsText().contains("savo vienetui"))
    }

    @Test
    fun `unit review approve reject and forward update requisition state`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Review Unit")
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "review-member@test.com")
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "review-leader@test.com", unitId)
        assignMember(token, tuntasId, unitId, memberId)

        val approveRequestId = Json.parseToJsonElement(createRequisition(memberToken, tuntasId, unitId).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val approveResponse = client.post("/api/requisitions/$approveRequestId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)
        val approveBody = Json.parseToJsonElement(approveResponse.bodyAsText()).jsonObject
        assertEquals("APPROVED", approveBody["status"]!!.jsonPrimitive.content)
        assertEquals("APPROVED", approveBody["unitReviewStatus"]!!.jsonPrimitive.content)
        assertEquals("2", approveBody["items"]!!.jsonArray.first().jsonObject["quantityApproved"]!!.jsonPrimitive.content)

        val rejectRequestId = Json.parseToJsonElement(createRequisition(memberToken, tuntasId, unitId).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val rejectResponse = client.post("/api/requisitions/$rejectRequestId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "REJECTED", "rejectionReason": "Neaktualu" }""")
        }
        assertEquals(HttpStatusCode.OK, rejectResponse.status)
        val rejectBody = Json.parseToJsonElement(rejectResponse.bodyAsText()).jsonObject
        assertEquals("REJECTED", rejectBody["status"]!!.jsonPrimitive.content)
        assertEquals("UNIT_REJECTED", rejectBody["lastAction"]!!.jsonPrimitive.content)
        assertEquals("Neaktualu", rejectBody["items"]!!.jsonArray.first().jsonObject["rejectionReason"]!!.jsonPrimitive.content)

        val forwardRequestId = Json.parseToJsonElement(createRequisition(memberToken, tuntasId, unitId).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val forwardResponse = client.post("/api/requisitions/$forwardRequestId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "FORWARDED" }""")
        }
        assertEquals(HttpStatusCode.OK, forwardResponse.status)
        val forwardBody = Json.parseToJsonElement(forwardResponse.bodyAsText()).jsonObject
        assertEquals("PARTIALLY_APPROVED", forwardBody["status"]!!.jsonPrimitive.content)
        assertEquals("FORWARDED", forwardBody["unitReviewStatus"]!!.jsonPrimitive.content)
        assertEquals("PENDING", forwardBody["topLevelReviewStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun `top level review validates state and can approve or reject`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Top Level Review Unit")
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "top-member@test.com")
        val (leaderToken, _) = registerUserWithRole(token, tuntasId, "Draugininkas", "top-leader@test.com", unitId)
        val (reviewerToken, _) = registerUserWithRole(token, tuntasId, "Tuntininko pavaduotojas", "top-reviewer-2@test.com")
        assignMember(token, tuntasId, unitId, memberId)

        val pendingTopLevelId = Json.parseToJsonElement(createRequisition(token, tuntasId, null).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val approveResponse = client.post("/api/requisitions/$pendingTopLevelId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $reviewerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)
        val approvedBody = Json.parseToJsonElement(approveResponse.bodyAsText()).jsonObject
        assertEquals("APPROVED", approvedBody["topLevelReviewStatus"]!!.jsonPrimitive.content)
        assertEquals("TOP_LEVEL_APPROVED", approvedBody["lastAction"]!!.jsonPrimitive.content)

        val forwardedId = Json.parseToJsonElement(createRequisition(memberToken, tuntasId, unitId).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        client.post("/api/requisitions/$forwardedId/unit-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $leaderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "FORWARDED" }""")
        }

        val rejectResponse = client.post("/api/requisitions/$forwardedId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $reviewerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "REJECTED", "rejectionReason": "Per brangu" }""")
        }
        assertEquals(HttpStatusCode.OK, rejectResponse.status)
        val rejectedBody = Json.parseToJsonElement(rejectResponse.bodyAsText()).jsonObject
        assertEquals("REJECTED", rejectedBody["topLevelReviewStatus"]!!.jsonPrimitive.content)
        assertEquals("FORWARDED", rejectedBody["lastAction"]!!.jsonPrimitive.content)

        val invalidAction = client.post("/api/requisitions/$forwardedId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $reviewerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "MAYBE" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidAction.status)
    }

    @Test
    fun `cancel requisition works for creator and blocks invalid states`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = createUnit(token, tuntasId, "Cancel Unit")
        val (memberToken, memberId) = registerUserWithRole(token, tuntasId, "Skautas", "cancel-member@test.com")
        assignMember(token, tuntasId, unitId, memberId)

        val pendingRequestId = Json.parseToJsonElement(createRequisition(memberToken, tuntasId, unitId).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val cancelPending = client.delete("/api/requisitions/$pendingRequestId") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, cancelPending.status)

        val cancelledRequest = client.get("/api/requisitions/$pendingRequestId") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        val cancelledBody = Json.parseToJsonElement(cancelledRequest.bodyAsText()).jsonObject
        assertEquals("CANCELLED", cancelledBody["status"]!!.jsonPrimitive.content)
        assertEquals("CANCELLED", cancelledBody["unitReviewStatus"]!!.jsonPrimitive.content)

        val approvedRequestId = Json.parseToJsonElement(createRequisition(token, tuntasId, null).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        val (reviewerToken, _) = registerUserWithRole(token, tuntasId, "Inventorininkas", "cancel-reviewer@test.com")
        client.post("/api/requisitions/$approvedRequestId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $reviewerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "APPROVED" }""")
        }
        val cancelApproved = client.delete("/api/requisitions/$approvedRequestId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, cancelApproved.status)
        assertTrue(cancelApproved.bodyAsText().contains("atšaukti negalima", ignoreCase = true))
    }

    @Test
    fun `mark purchased and add to inventory handle new item and restock flows`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (reviewerToken, _) = registerUserWithRole(token, tuntasId, "Inventorininkas", "purchase-reviewer@test.com")
        val existingItemId = createItem(token, tuntasId, "Esamas kirvis", quantity = 2)

        val createResponse = client.post("/api/requisitions") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "items": [
                        { "itemName": "Naujas puodas", "quantity": 2, "requestType": "NEW_ITEM", "notes": "Puodams" },
                        { "itemName": "Kirvio papildymas", "quantity": 3, "requestType": "RESTOCK_EXISTING", "existingItemId": "$existingItemId", "notes": "Kirviams" }
                    ]
                }
                """.trimIndent()
            )
        }
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val approveResponse = client.post("/api/requisitions/$requestId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $reviewerToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "APPROVED" }""")
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)

        val markPurchased = client.post("/api/requisitions/$requestId/mark-purchased") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "notes": "Nupirkta viena partija" }""")
        }
        assertEquals(HttpStatusCode.OK, markPurchased.status)
        val purchasedBody = Json.parseToJsonElement(markPurchased.bodyAsText()).jsonObject
        assertEquals("PURCHASED", purchasedBody["status"]!!.jsonPrimitive.content)

        val lineIds = transaction {
            DraugoveRequisitionItems.selectAll()
                .where { DraugoveRequisitionItems.requisitionId eq UUID.fromString(requestId) }
                .map { it[DraugoveRequisitionItems.id].toString() to it[DraugoveRequisitionItems.requestType] }
                .associate { it }
        }
        val newItemLineId = lineIds.entries.first { it.value == "NEW_ITEM" }.key
        val restockLineId = lineIds.entries.first { it.value == "RESTOCK_EXISTING" }.key

        val addToInventory = client.post("/api/requisitions/$requestId/add-to-inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "items": [
                        {
                            "requisitionItemId": "$newItemLineId",
                            "action": "NEW_ITEM",
                            "type": "COLLECTIVE",
                            "category": "COOKING",
                            "condition": "GOOD",
                            "purchaseDate": "2026-08-10",
                            "purchasePrice": 24.5,
                            "notes": "Sukurta is prasymo"
                        },
                        {
                            "requisitionItemId": "$restockLineId",
                            "action": "RESTOCK_EXISTING",
                            "existingItemId": "$existingItemId",
                            "purchaseDate": "2026-08-11",
                            "purchasePrice": 15.0,
                            "notes": "Papildytas esamas"
                        }
                    ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, addToInventory.status)
        val inventoryBody = Json.parseToJsonElement(addToInventory.bodyAsText()).jsonObject
        assertEquals("INVENTORY_ADDED", inventoryBody["status"]!!.jsonPrimitive.content)

        val itemNamesAndQuantities = transaction {
            Items.selectAll()
                .where { Items.tuntasId eq UUID.fromString(tuntasId) }
                .map { it[Items.name] to it[Items.quantity] }
                .toMap()
        }
        assertEquals(5, itemNamesAndQuantities["Esamas kirvis"])
        assertEquals(2, itemNamesAndQuantities["Naujas puodas"])
    }

    @Test
    fun `inventory addition endpoints validate payload and state`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = createRequisition(token, tuntasId, null)
        val requestId = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val markBeforeApprove = client.post("/api/requisitions/$requestId/mark-purchased") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, markBeforeApprove.status)

        client.post("/api/requisitions/$requestId/top-level-review") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "action": "APPROVED" }""")
        }
        client.post("/api/requisitions/$requestId/mark-purchased") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{}""")
        }

        val emptyActions = client.post("/api/requisitions/$requestId/add-to-inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, emptyActions.status)

        val invalidLineId = client.post("/api/requisitions/$requestId/add-to-inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "requisitionItemId": "not-a-uuid", "action": "NEW_ITEM" }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidLineId.status)

        val lineId = transaction {
            DraugoveRequisitionItems.selectAll()
                .where { DraugoveRequisitionItems.requisitionId eq UUID.fromString(requestId) }
                .first()[DraugoveRequisitionItems.id].toString()
        }

        val invalidAction = client.post("/api/requisitions/$requestId/add-to-inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "requisitionItemId": "$lineId", "action": "SOMETHING_ELSE" }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidAction.status)

        val invalidType = client.post("/api/requisitions/$requestId/add-to-inventory") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "requisitionItemId": "$lineId", "action": "NEW_ITEM", "type": "BAD", "category": "TOOLS", "condition": "GOOD" }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidType.status)
    }
}
