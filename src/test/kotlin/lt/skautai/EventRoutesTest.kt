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
import lt.skautai.services.DeviceService
import lt.skautai.services.EventInventoryReminderService
import lt.skautai.services.FirebaseNotificationService
import lt.skautai.services.NotificationService
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
        type: String = "STOVYKLA",
        organizationalUnitId: String? = null,
        customTypeLabel: String? = null
    ): String {
        val orgUnitField = organizationalUnitId?.let { """,
                    "organizationalUnitId": "$it"""" }.orEmpty()
        val customTypeField = customTypeLabel?.let { """,
                    "customTypeLabel": "$it"""" }.orEmpty()
        val response = post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "$name",
                    "type": "$type"$customTypeField,
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"$orgUnitField
                }
            """.trimIndent())
        }
        val responseBody = response.bodyAsText()
        assertEquals(HttpStatusCode.Created, response.status, responseBody)
        return Json.parseToJsonElement(responseBody)
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

    private suspend fun HttpClient.createUnit(
        token: String,
        tuntasId: String,
        name: String,
        type: String = "SKAUTU_DRAUGOVE",
        subType: String? = null
    ): String {
        val subTypeField = subType?.let { """, "subType": "$it"""" }.orEmpty()
        val response = post("/api/organizational-units") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "type": "$type"$subTypeField }""")
        }
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.createEventResponse(
        token: String,
        tuntasId: String,
        name: String,
        organizationalUnitId: String? = null
    ): HttpResponse {
        val orgUnitField = organizationalUnitId?.let { """, "organizationalUnitId": "$it"""" }.orEmpty()
        return post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "name": "$name",
                    "type": "RENGINYS",
                    "startDate": "2026-08-01",
                    "endDate": "2026-08-01"$orgUnitField
                }
            """.trimIndent())
        }
    }

    private suspend fun HttpClient.visibleEventIds(token: String, tuntasId: String): List<String> {
        val response = get("/api/events") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["events"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    private suspend fun HttpClient.createEventInventoryItem(
        token: String,
        tuntasId: String,
        eventId: String,
        name: String = "Puodai",
        plannedQuantity: Int = 3
    ): String {
        val response = post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "plannedQuantity": $plannedQuantity }""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.createEventPurchase(
        token: String,
        tuntasId: String,
        eventId: String,
        eventInventoryItemId: String,
        purchasedQuantity: Int = 1,
        unitPrice: Double = 5.0
    ): JsonObject {
        val response = post("/api/events/$eventId/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "items": [
                        {
                            "eventInventoryItemId": "$eventInventoryItemId",
                            "purchasedQuantity": $purchasedQuantity,
                            "unitPrice": $unitPrice
                        }
                    ]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
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
    fun `event creation and visibility follows rank target audience`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val gildijaId = client.createUnit(token, tuntasId, "Vadovu gildija", type = "GILDIJA")
        val vyrSkautuId = client.createUnit(
            token,
            tuntasId,
            "Vyr skautu vienetas",
            type = "VYR_SKAUTU_VIENETAS",
            subType = "DRAUGOVE"
        )
        val vyrSkauciuId = client.createUnit(
            token,
            tuntasId,
            "Vyr skauciu vienetas",
            type = "VYR_SKAUCIU_VIENETAS",
            subType = "DRAUGOVE"
        )

        val vadovasToken = registerUserWithRole(token, tuntasId, "Vadovas", "vadovas-events@test.com").first
        val vyrSkautasToken = registerUserWithRole(
            token,
            tuntasId,
            "Vyr. skautas",
            "vyr-skautas-events@test.com",
            vyrSkautuId
        ).first
        val vyrSkauteToken = registerUserWithRole(
            token,
            tuntasId,
            "Vyr. skautas kandidatas",
            "vyr-skaute-events@test.com",
            vyrSkauciuId
        ).first
        val patyresToken = registerUserWithRole(token, tuntasId, "Patyres skautas", "patyres-events@test.com").first

        val bendrasResponse = client.createEventResponse(vadovasToken, tuntasId, "Bendras renginys")
        assertEquals(HttpStatusCode.Created, bendrasResponse.status)
        val bendrasId = Json.parseToJsonElement(bendrasResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val gildijaResponse = client.createEventResponse(vadovasToken, tuntasId, "Gildijos renginys", gildijaId)
        assertEquals(HttpStatusCode.Created, gildijaResponse.status)
        val gildijaEventId = Json.parseToJsonElement(gildijaResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val vadovasVyrSkautuResponse = client.createEventResponse(vadovasToken, tuntasId, "Ne savo vyr skautu", vyrSkautuId)
        assertEquals(HttpStatusCode.Forbidden, vadovasVyrSkautuResponse.status)

        val vyrSkautuResponse = client.createEventResponse(vyrSkautasToken, tuntasId, "Vyr skautu renginys", vyrSkautuId)
        assertEquals(HttpStatusCode.Created, vyrSkautuResponse.status)
        val vyrSkautuEventId = Json.parseToJsonElement(vyrSkautuResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val vyrSkauteWrongTargetResponse = client.createEventResponse(
            vyrSkauteToken,
            tuntasId,
            "Ne savo vyr skautu",
            vyrSkautuId
        )
        assertEquals(HttpStatusCode.Forbidden, vyrSkauteWrongTargetResponse.status)

        val vyrSkauciuResponse = client.createEventResponse(vyrSkauteToken, tuntasId, "Vyr skauciu renginys", vyrSkauciuId)
        assertEquals(HttpStatusCode.Created, vyrSkauciuResponse.status)
        val vyrSkauciuEventId = Json.parseToJsonElement(vyrSkauciuResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val patyresResponse = client.createEventResponse(patyresToken, tuntasId, "Patyres negali")
        assertEquals(HttpStatusCode.Forbidden, patyresResponse.status)

        assertEquals(setOf(bendrasId, gildijaEventId), client.visibleEventIds(vadovasToken, tuntasId).toSet())
        assertEquals(setOf(vyrSkautuEventId), client.visibleEventIds(vyrSkautasToken, tuntasId).toSet())
        assertEquals(setOf(vyrSkauciuEventId), client.visibleEventIds(vyrSkauteToken, tuntasId).toSet())
        assertEquals(emptyList<String>(), client.visibleEventIds(patyresToken, tuntasId))
        assertFalse(vyrSkautuEventId in client.visibleEventIds(token, tuntasId))
        assertFalse(vyrSkauciuEventId in client.visibleEventIds(token, tuntasId))

        val hiddenDetailResponse = client.get("/api/events/$vyrSkautuEventId") {
            header("Authorization", "Bearer $vadovasToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, hiddenDetailResponse.status)

        val hiddenFromTuntininkas = client.get("/api/events/$vyrSkautuEventId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, hiddenFromTuntininkas.status)
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
    fun `get events supports limit and offset pagination`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        repeat(3) { index -> client.createTestEvent(token, tuntasId, "Event $index") }

        val response = client.get("/api/events?limit=2&offset=1") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(3, body["total"]!!.jsonPrimitive.int)
        assertEquals(2, body["limit"]!!.jsonPrimitive.int)
        assertEquals(1, body["offset"]!!.jsonPrimitive.int)
        assertFalse(body["hasMore"]?.jsonPrimitive?.boolean ?: false)
        assertEquals(2, body["events"]!!.jsonArray.size)
    }

    @Test
    fun `get events rejects invalid pagination`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.get("/api/events?limit=0&offset=-1") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `event inventory mutation routes validate uuid parameters`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val badBucketUpdate = client.put("/api/events/$eventId/inventory-buckets/not-a-uuid") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Bad", "type": "OTHER" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, badBucketUpdate.status)

        val badItemUpdate = client.put("/api/events/$eventId/inventory-items/not-a-uuid") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Bad item" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, badItemUpdate.status)

        val badAllocationDelete = client.delete("/api/events/$eventId/inventory-allocations/not-a-uuid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badAllocationDelete.status)
    }

    @Test
    fun `event purchase and reconciliation routes validate uuid parameters and gone branch`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val badPurchaseUpdate = client.put("/api/events/$eventId/purchases/not-a-uuid") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "notes": "bad" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, badPurchaseUpdate.status)

        val badInvoiceAttach = client.post("/api/events/$eventId/purchases/not-a-uuid/invoice") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "invoiceFileUrl": "/uploads/documents/test.pdf" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, badInvoiceAttach.status)

        val badInvoiceDownload = client.get("/api/events/$eventId/purchases/not-a-uuid/invoice/download") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badInvoiceDownload.status)

        val badPurchaseComplete = client.post("/api/events/$eventId/purchases/not-a-uuid/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badPurchaseComplete.status)

        val goneAddToInventory = client.post("/api/events/$eventId/purchases/anything/add-to-inventory") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Gone, goneAddToInventory.status)

        val badCandidateId = client.get("/api/events/$eventId/reconciliation/purchases/not-a-uuid/candidates") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badCandidateId.status)
    }

    @Test
    fun `event inventory and purchase routes enforce permission guards`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "event-route-member@test.com")
        val (otherToken, _) = client.registerAndActivateTuntininkas(
            email = "event-other-tuntas@test.com",
            tuntasName = "Other Event Tuntas"
        )

        val forbiddenPurchasesList = client.get("/api/events/$eventId/purchases") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenPurchasesList.status)

        val forbiddenPurchaseCreate = client.post("/api/events/$eventId/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [] }""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenPurchaseCreate.status)

        val forbiddenEventComplete = client.post("/api/events/$eventId/complete") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenEventComplete.status)

        val foreignMovement = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $otherToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "movementType": "CHECKOUT_TO_PERSON", "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.Forbidden, foreignMovement.status)
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
    fun `create event accepts custom type label while preserving base type`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Ziemos zygis",
                    "type": "RENGINYS",
                    "customTypeLabel": "Ziemos zygis",
                    "startDate": "2026-12-12",
                    "endDate": "2026-12-13"
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("RENGINYS", body["type"]?.jsonPrimitive?.content)
        assertEquals("Ziemos zygis", body["customTypeLabel"]?.jsonPrimitive?.content)
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
    fun `pastovykle nested routes validate uuid guards`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val badPastovykleGet = client.get("/api/events/$eventId/pastovykles/not-a-uuid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badPastovykleGet.status)

        val badLeaderAssign = client.post("/api/events/$eventId/pastovykles/not-a-uuid/leaders") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "00000000-0000-0000-0000-000000000000", "role": "BENDRAVADOVIS" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, badLeaderAssign.status)

        val badMemberDelete = client.delete("/api/events/$eventId/pastovykles/not-a-uuid/members/not-a-uuid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badMemberDelete.status)

        val badInventoryUpdate = client.put("/api/events/$eventId/pastovykles/not-a-uuid/inventory/not-a-uuid") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantityReturned": 1 }""")
        }
        assertEquals(HttpStatusCode.BadRequest, badInventoryUpdate.status)

        val badRequestApprove = client.post("/api/events/$eventId/pastovykles/not-a-uuid/requests/not-a-uuid/approve") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badRequestApprove.status)
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
        val (_, userId) = registerUserWithRole(token, tuntasId, "Vadovas", "ukvedys-role@test.com")

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
    fun `event staff member can hold multiple roles`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (_, userId) = registerUserWithRole(token, tuntasId, "Vadovas", "single-staff-role@test.com")

        val firstResponse = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "MAISTININKAS" }""")
        }
        assertEquals(HttpStatusCode.Created, firstResponse.status)

        val secondResponse = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "KOMENDANTAS" }""")
        }
        assertEquals(HttpStatusCode.Created, secondResponse.status)
        assertEquals("KOMENDANTAS", Json.parseToJsonElement(secondResponse.bodyAsText()).jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assign programeris without target group returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (_, userId) = registerUserWithRole(token, tuntasId, "Vadovas", "remove-role-ukvedys@test.com")

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
    fun `tuntininkas without explicit event role can read but cannot update another persons event`() = testApplication {
        configureFullApp()
        val (tuntToken, tuntasId) = client.registerAndActivateTuntininkas()
        val (vadovasToken, _) = registerUserWithRole(tuntToken, tuntasId, "Vadovas", "event-owner@test.com")
        val eventId = client.createTestEvent(vadovasToken, tuntasId, name = "Delegated event")

        val readResponse = client.get("/api/events/$eventId") {
            header("Authorization", "Bearer $tuntToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, readResponse.status)

        val updateResponse = client.put("/api/events/$eventId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $tuntToken")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Changed by tuntininkas"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Forbidden, updateResponse.status)
    }

    @Test
    fun `candidate members include vadovas across units and exclude scouts`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val firstUnitId = client.createUnit(token, tuntasId, "Skautai A")
        val secondUnitId = client.createUnit(token, tuntasId, "Skautai B")
        val (_, vadovasUserId) = registerUserWithRole(token, tuntasId, "Vadovas", "vadovas-a@test.com", firstUnitId)
        val (_, inventorininkasUserId) = registerUserWithRole(token, tuntasId, "Inventorininkas", "inventorininkas@test.com")
        val (_, skautasUserId) = registerUserWithRole(token, tuntasId, "Skautas", "skautas-b@test.com", secondUnitId)

        val response = client.get("/api/events/$eventId/candidate-members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val members = Json.parseToJsonElement(response.bodyAsText()).jsonObject["members"]!!.jsonArray
        val candidateIds = members.map { it.jsonObject["userId"]!!.jsonPrimitive.content }.toSet()
        assertTrue(vadovasUserId in candidateIds)
        assertTrue(inventorininkasUserId in candidateIds)
        assertFalse(skautasUserId in candidateIds)
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
    fun `remove event role returns 204`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (_, userId) = registerUserWithRole(token, tuntasId, "Vadovas", "remove-role-ukvedys@test.com")

        val assignResponse = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "UKVEDYS" }""")
        }

        val assignBody = assignResponse.bodyAsText()
        assertEquals(HttpStatusCode.Created, assignResponse.status, assignBody)
        val roleId = Json.parseToJsonElement(assignBody)
            .jsonObject["id"]!!.jsonPrimitive.content

        val response = client.delete("/api/events/$eventId/roles/$roleId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
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


    private suspend fun HttpClient.createTestPastovykle(
        token: String,
        tuntasId: String,
        eventId: String,
        name: String = "Vilkai",
        responsibleUserId: String? = null
    ): String {
        val responsibleField = responsibleUserId?.let { """, "responsibleUserId": "$it"""" }.orEmpty()
        val response = post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name"$responsibleField }""")
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


    @Test
    fun `create pastovykle returns 201`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vyr. skautės", "ageGroup": "VYR_SKAUTES" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Vyr. skautės", body["name"]?.jsonPrimitive?.content)
        assertEquals("VYR_SKAUTES", body["ageGroup"]?.jsonPrimitive?.content)
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
    fun `event routes validate top level bad input and forbidden branches`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (memberToken, _) = registerUserWithRole(token, tuntasId, "Skautas", "event-route-guard@test.com")

        val badListHeader = client.get("/api/events") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", "not-a-uuid")
        }
        assertEquals(HttpStatusCode.BadRequest, badListHeader.status)

        val badCreateUnit = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Bad unit event",
                    "type": "RENGINYS",
                    "startDate": "2026-08-01",
                    "endDate": "2026-08-01",
                    "organizationalUnitId": "not-a-uuid"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.BadRequest, badCreateUnit.status)

        val badEventId = client.get("/api/events/not-a-uuid") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, badEventId.status)

        val forbiddenCandidates = client.get("/api/events/$eventId/candidate-members") {
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenCandidates.status)

        val forbiddenInventoryRequest = client.post("/api/events/$eventId/inventory-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $memberToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Reikia puodu", "quantity": 2 }""")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenInventoryRequest.status)
    }

    @Test
    fun `create pastovykle with non member responsible user returns 400`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val response = client.post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vilkai", "responsibleUserId": "00000000-0000-0000-0000-000000000001" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `pastovykle responsible user can also hold event role`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (_, userId) = registerUserWithRole(token, tuntasId, "Vadovas", "pastovykle-conflict-role@test.com")

        val assignResponse = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "KOMENDANTAS" }""")
        }
        assertEquals(HttpStatusCode.Created, assignResponse.status)

        val response = client.post("/api/events/$eventId/pastovykles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Skautai", "responsibleUserId": "$userId" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `event role user can also be pastovykle responsible`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (_, userId) = registerUserWithRole(token, tuntasId, "Vadovas", "role-conflict-pastovykle@test.com")
        client.createTestPastovykle(token, tuntasId, eventId, "Skautai", userId)

        val response = client.post("/api/events/$eventId/roles") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$userId", "role": "MAISTININKAS" }""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
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
    fun `update pastovykle can clear responsible user`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (_, userId) = registerUserWithRole(token, tuntasId, "Skautas", "clear-pastovykle-leader@test.com")
        val pid = client.createTestPastovykle(token, tuntasId, eventId, responsibleUserId = userId)

        val response = client.put("/api/events/$eventId/pastovykles/$pid") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "clearResponsibleUser": true }""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNull(body["responsibleUserId"])
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
    fun `pastovykle leaders and members support full workflow`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val (_, leaderUserId) = registerUserWithRole(token, tuntasId, "Skautas", "pastovykle-leader-flow@test.com")
        val (_, memberUserId) = registerUserWithRole(token, tuntasId, "Skautas", "pastovykle-member-flow@test.com")

        val assignLeaderResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/leaders") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$leaderUserId" }""")
        }
        assertEquals(HttpStatusCode.Created, assignLeaderResponse.status)
        val leaderRoleId = Json.parseToJsonElement(assignLeaderResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val addMemberResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/members") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "userId": "$memberUserId" }""")
        }
        assertEquals(HttpStatusCode.Created, addMemberResponse.status)
        val memberAssignmentId = Json.parseToJsonElement(addMemberResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val membersResponse = client.get("/api/events/$eventId/pastovykles/$pastovykleId/members") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, membersResponse.status)
        val members = Json.parseToJsonElement(membersResponse.bodyAsText()).jsonObject["members"]!!.jsonArray
        assertTrue(members.any { it.jsonObject["userId"]!!.jsonPrimitive.content == memberUserId })

        val removeMemberResponse = client.delete("/api/events/$eventId/pastovykles/$pastovykleId/members/$memberAssignmentId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, removeMemberResponse.status)

        val removeLeaderResponse = client.delete("/api/events/$eventId/pastovykles/$pastovykleId/leaders/$leaderRoleId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, removeLeaderResponse.status)
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
        assertEquals(HttpStatusCode.Gone, addResponse.status)
        val error = Json.parseToJsonElement(addResponse.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("suvedim"))
    }

    @Test
    fun `event purchase rejects duplicate active inventory item`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 4)

        client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 1)

        val duplicateResponse = client.post("/api/events/$eventId/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "items": [
                        {
                            "eventInventoryItemId": "$eventInventoryItemId",
                            "purchasedQuantity": 1,
                            "unitPrice": 5.0
                        }
                    ]
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, duplicateResponse.status)
        val error = Json.parseToJsonElement(duplicateResponse.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        assertTrue(error.contains("aktyvu pirkima") || error.contains("aktyvų pirkimą"))
    }

    @Test
    fun `event purchase allows same inventory item after cancelled purchase`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 4)
        val purchase = client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 1)
        val purchaseId = purchase["id"]!!.jsonPrimitive.content

        val cancelResponse = client.put("/api/events/$eventId/purchases/$purchaseId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "CANCELLED" }""")
        }
        assertEquals(HttpStatusCode.OK, cancelResponse.status)

        val newPurchase = client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 1)

        assertEquals("DRAFT", newPurchase["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `event purchase rejects same inventory item while purchased purchase awaits reconciliation`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 4)
        val purchase = client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 1)
        val purchaseId = purchase["id"]!!.jsonPrimitive.content

        val completeResponse = client.post("/api/events/$eventId/purchases/$purchaseId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, completeResponse.status)

        val duplicateResponse = client.post("/api/events/$eventId/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "items": [
                        {
                            "eventInventoryItemId": "$eventInventoryItemId",
                            "purchasedQuantity": 1,
                            "unitPrice": 5.0
                        }
                    ]
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.BadRequest, duplicateResponse.status)
    }

    @Test
    fun `completing purchase twice does not increase available quantity twice`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 4)
        val purchase = client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 2)
        val purchaseId = purchase["id"]!!.jsonPrimitive.content

        repeat(2) {
            val completeResponse = client.post("/api/events/$eventId/purchases/$purchaseId/complete") {
                header("Authorization", "Bearer $token")
                header("X-Tuntas-Id", tuntasId)
            }
            assertEquals(HttpStatusCode.OK, completeResponse.status)
        }

        val planResponse = client.get("/api/events/$eventId/inventory-plan") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val item = Json.parseToJsonElement(planResponse.bodyAsText())
            .jsonObject["items"]!!.jsonArray
            .single { it.jsonObject["id"]!!.jsonPrimitive.content == eventInventoryItemId }
            .jsonObject
        assertEquals(2, item["availableQuantity"]!!.jsonPrimitive.int)
        assertEquals(2, item["shortageQuantity"]!!.jsonPrimitive.int)
    }

    @Test
    fun `purchase cannot be marked purchased through generic update`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 4)
        val purchase = client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 2)
        val purchaseId = purchase["id"]!!.jsonPrimitive.content

        val updateResponse = client.put("/api/events/$eventId/purchases/$purchaseId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "PURCHASED" }""")
        }

        assertEquals(HttpStatusCode.BadRequest, updateResponse.status)
    }

    @Test
    fun `failed purchase creation does not leave empty purchase`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 4)

        val invalidPurchaseResponse = client.post("/api/events/$eventId/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""
                {
                    "items": [
                        {
                            "eventInventoryItemId": "$eventInventoryItemId",
                            "purchasedQuantity": 0,
                            "unitPrice": 5.0
                        }
                    ]
                }
            """.trimIndent())
        }
        assertEquals(HttpStatusCode.BadRequest, invalidPurchaseResponse.status)

        val purchasesResponse = client.get("/api/events/$eventId/purchases") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val purchases = Json.parseToJsonElement(purchasesResponse.bodyAsText()).jsonObject["purchases"]!!.jsonArray
        assertEquals(0, purchases.size)
    }

    @Test
    fun `get purchases supports limit and offset pagination`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        repeat(3) { index ->
            val inventoryItemId = client.createEventInventoryItem(
                token,
                tuntasId,
                eventId,
                name = "Purchase item $index"
            )
            client.createEventPurchase(token, tuntasId, eventId, inventoryItemId)
        }

        val response = client.get("/api/events/$eventId/purchases?limit=2&offset=1") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(3, body["total"]!!.jsonPrimitive.int)
        assertEquals(2, body["purchases"]!!.jsonArray.size)
        assertFalse(body["hasMore"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `holder approves inventory transfer request and movement is tracked`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val (holderToken, holderUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Skautas",
            "transfer-holder@test.com"
        )
        val (requesterToken, requesterUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Skautas",
            "transfer-requester@test.com"
        )
        listOf(holderUserId, requesterUserId).forEach { eventUserId ->
            val roleResponse = client.post("/api/events/$eventId/roles") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-Tuntas-Id", tuntasId)
                setBody("""{ "userId": "$eventUserId", "role": "PROGRAMERIS", "targetGroup": "PROGRAMA" }""")
            }
            assertEquals(HttpStatusCode.Created, roleResponse.status, roleResponse.bodyAsText())
        }
        val sourceItemId = client.createTestItem(token, tuntasId, quantity = 2)
        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$sourceItemId", "name": "Kirvis", "plannedQuantity": 2 }""")
        }
        assertEquals(HttpStatusCode.Created, eventItemResponse.status, eventItemResponse.bodyAsText())
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content

        val checkoutResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "movementType": "CHECKOUT_TO_PERSON",
                    "quantity": 2,
                    "toUserId": "$holderUserId"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, checkoutResponse.status, checkoutResponse.bodyAsText())
        val custodyId = Json.parseToJsonElement(checkoutResponse.bodyAsText())
            .jsonObject["custodyId"]!!.jsonPrimitive.content

        val requestResponse = client.post("/api/events/$eventId/inventory-transfer-requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $requesterToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "sourceCustodyId": "$custodyId", "quantity": 1, "notes": "Reikia darbui" }""")
        }
        assertEquals(HttpStatusCode.Created, requestResponse.status, requestResponse.bodyAsText())
        val transferRequest = Json.parseToJsonElement(requestResponse.bodyAsText()).jsonObject
        val transferRequestId = transferRequest["id"]!!.jsonPrimitive.content
        assertEquals(holderUserId, transferRequest["requestedFromUserId"]!!.jsonPrimitive.content)
        assertEquals(requesterUserId, transferRequest["requestedByUserId"]!!.jsonPrimitive.content)

        val approveResponse = client.post(
            "/api/events/$eventId/inventory-transfer-requests/$transferRequestId/respond"
        ) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $holderToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "approve": true }""")
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status, approveResponse.bodyAsText())
        val approved = Json.parseToJsonElement(approveResponse.bodyAsText()).jsonObject
        assertEquals("APPROVED", approved["status"]!!.jsonPrimitive.content)
        assertNotNull(approved["movementId"])

        val custodyResponse = client.get("/api/events/$eventId/inventory-custody") {
            header("Authorization", "Bearer $requesterToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, custodyResponse.status, custodyResponse.bodyAsText())
        val requesterCustody = Json.parseToJsonElement(custodyResponse.bodyAsText())
            .jsonObject["custody"]!!.jsonArray
            .map(JsonElement::jsonObject)
            .firstOrNull { it["holderUserId"]?.jsonPrimitive?.content == requesterUserId }
        assertNotNull(requesterCustody)
        assertEquals(1, requesterCustody!!["remainingQuantity"]!!.jsonPrimitive.int)

        val movementsResponse = client.get("/api/events/$eventId/inventory-movements") {
            header("Authorization", "Bearer $requesterToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, movementsResponse.status, movementsResponse.bodyAsText())
        val movements = Json.parseToJsonElement(movementsResponse.bodyAsText())
            .jsonObject["movements"]!!.jsonArray
            .map(JsonElement::jsonObject)
        assertTrue(
            movements.any {
                it["movementType"]?.jsonPrimitive?.content == "TRANSFER" &&
                    it["fromUserId"]?.jsonPrimitive?.content == holderUserId &&
                    it["toUserId"]?.jsonPrimitive?.content == requesterUserId
            }
        )
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

    @Test
    fun `inventory request planning fields history and readiness are exposed`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (_, responsibleUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Skautas",
            "request-planning@test.com"
        )
        val eventId = client.createTestEvent(token, tuntasId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 3)

        val createResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "eventInventoryItemId": "$eventInventoryItemId",
                    "quantity": 3,
                    "provider": "UNIT",
                    "dueAt": "2026-07-01T23:59:59Z",
                    "responsibleUserId": "$responsibleUserId",
                    "notes": "Vienetas surenka"
                }
                """.trimIndent()
            )
        }
        val createBodyText = createResponse.bodyAsText()
        assertEquals(HttpStatusCode.Created, createResponse.status, createBodyText)
        val created = Json.parseToJsonElement(createBodyText).jsonObject
        val requestId = created["id"]!!.jsonPrimitive.content
        assertEquals("2026-07-01T23:59:59Z", created["dueAt"]!!.jsonPrimitive.content)
        assertEquals(responsibleUserId, created["responsibleUserId"]!!.jsonPrimitive.content)

        val reminderService = EventInventoryReminderService(
            FirebaseNotificationService(DeviceService(), NotificationService())
        )
        assertEquals(1, reminderService.dispatchDueReminders(kotlinx.datetime.Instant.parse("2026-07-01T12:00:00Z")))
        assertEquals(0, reminderService.dispatchDueReminders(kotlinx.datetime.Instant.parse("2026-07-01T13:00:00Z")))

        val updateResponse = client.put("/api/events/$eventId/pastovykles/$pastovykleId/requests/$requestId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "provider": "UKVEDYS", "notes": "Perduota ukvedziui" }""")
        }
        val updateBodyText = updateResponse.bodyAsText()
        assertEquals(HttpStatusCode.OK, updateResponse.status, updateBodyText)
        val updated = Json.parseToJsonElement(updateBodyText).jsonObject
        val history = updated["providerHistory"]!!.jsonArray
        assertEquals(1, history.size)
        assertEquals("UNIT", history.single().jsonObject["fromProvider"]!!.jsonPrimitive.content)
        assertEquals("UKVEDYS", history.single().jsonObject["toProvider"]!!.jsonPrimitive.content)

        val readinessBefore = client.get("/api/events/$eventId/inventory-readiness") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, readinessBefore.status)
        val beforeBody = Json.parseToJsonElement(readinessBefore.bodyAsText()).jsonObject
        assertEquals(0, beforeBody["readinessPercent"]!!.jsonPrimitive.int)
        assertEquals(3, beforeBody["openQuantity"]!!.jsonPrimitive.int)

        val selfProvidedResponse = client.post(
            "/api/events/$eventId/pastovykles/$pastovykleId/requests/$requestId/self-provided"
        ) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "notes": "Pasirupinta" }""")
        }
        assertEquals(HttpStatusCode.OK, selfProvidedResponse.status)

        val readinessAfter = client.get("/api/events/$eventId/inventory-readiness") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        val afterBody = Json.parseToJsonElement(readinessAfter.bodyAsText()).jsonObject
        assertEquals(100, afterBody["readinessPercent"]!!.jsonPrimitive.int)
        assertEquals(0, afterBody["openQuantity"]!!.jsonPrimitive.int)
    }

    @Test
    fun `pastovykle responsible user is scoped to own pastovykle workflow`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val (responsibleToken, responsibleUserId) = registerUserWithRole(token, tuntasId, "Skautas", "pastovykle-leader@test.com")
        val eventId = client.createTestEvent(token, tuntasId)
        val otherEventId = client.createTestEvent(token, tuntasId, name = "Kita stovykla")
        client.activateEventForMovement(token, tuntasId, eventId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId, "Vilkai", responsibleUserId)
        val otherPastovykleId = client.createTestPastovykle(token, tuntasId, eventId, "Skautai")
        client.createTestPastovykle(token, tuntasId, otherEventId, "Kiti")
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 5)

        val eventsResponse = client.get("/api/events") {
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, eventsResponse.status)
        val events = Json.parseToJsonElement(eventsResponse.bodyAsText()).jsonObject["events"]!!.jsonArray
        assertEquals(listOf(eventId), events.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        val pastovyklesResponse = client.get("/api/events/$eventId/pastovykles") {
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, pastovyklesResponse.status)
        val pastovykles = Json.parseToJsonElement(pastovyklesResponse.bodyAsText()).jsonObject["pastovykles"]!!.jsonArray
        assertEquals(1, pastovykles.size)
        assertEquals(pastovykleId, pastovykles.single().jsonObject["id"]!!.jsonPrimitive.content)

        val ownInventoryResponse = client.get("/api/events/$eventId/pastovykles/$pastovykleId/inventory") {
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, ownInventoryResponse.status)

        val otherInventoryResponse = client.get("/api/events/$eventId/pastovykles/$otherPastovykleId/inventory") {
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, otherInventoryResponse.status)

        val requestResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$eventInventoryItemId", "quantity": 2, "notes": "Reikia pastovyklei" }""")
        }
        assertEquals(HttpStatusCode.Created, requestResponse.status)
        val requestId = Json.parseToJsonElement(requestResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val selfProvidedResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests/$requestId/self-provided") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "notes": "Atsivesime patys" }""")
        }
        assertEquals(HttpStatusCode.OK, selfProvidedResponse.status)

        val approveResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests/$requestId/approve") {
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.Forbidden, approveResponse.status)
    }

    @Test
    fun `komendantas and ukvedys manage inventory but not event administration`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val (komendantasToken, komendantasUserId) = registerUserWithRole(token, tuntasId, "Vadovas", "komendantas@test.com")
        val (ukvedysToken, ukvedysUserId) = registerUserWithRole(token, tuntasId, "Vadovas", "ukvedys@test.com")

        listOf(komendantasUserId to "KOMENDANTAS", ukvedysUserId to "UKVEDYS").forEach { (userId, role) ->
            val assignResponse = client.post("/api/events/$eventId/roles") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                header("X-Tuntas-Id", tuntasId)
                setBody("""{ "userId": "$userId", "role": "$role" }""")
            }
            assertEquals(HttpStatusCode.Created, assignResponse.status)
        }

        listOf(komendantasToken to "Puodai", ukvedysToken to "Kirviai").forEach { (staffToken, itemName) ->
            val inventoryResponse = client.post("/api/events/$eventId/inventory-items") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $staffToken")
                header("X-Tuntas-Id", tuntasId)
                setBody("""{ "name": "$itemName", "plannedQuantity": 2 }""")
            }
            assertEquals(HttpStatusCode.Created, inventoryResponse.status)

            val eventAdminResponse = client.put("/api/events/$eventId") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $staffToken")
                header("X-Tuntas-Id", tuntasId)
                setBody("""{ "name": "$itemName eventas" }""")
            }
            assertEquals(HttpStatusCode.Forbidden, eventAdminResponse.status)
        }
    }

    @Test
    fun `cancelled event blocks pastovykle mutating workflow`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 3)

        val cancelResponse = client.delete("/api/events/$eventId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, cancelResponse.status)

        val requestResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$eventInventoryItemId", "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.BadRequest, requestResponse.status)

        val assignFromUnitResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/assign-from-unit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "00000000-0000-0000-0000-000000000001", "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.BadRequest, assignFromUnitResponse.status)
    }

    @Test
    fun `inventory buckets items and allocations support mutation workflow`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId)
        val (_, responsibleUserId) = registerUserWithRole(token, tuntasId, "Skautas", "inventory-owner@test.com")

        val createBucketResponse = client.post("/api/events/$eventId/inventory-buckets") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "  Vilku sandelis  ",
                    "type": "PASTOVYKLE",
                    "pastovykleId": "$pastovykleId",
                    "notes": "Pradine zona"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, createBucketResponse.status)
        val bucketBody = Json.parseToJsonElement(createBucketResponse.bodyAsText()).jsonObject
        val bucketId = bucketBody["id"]!!.jsonPrimitive.content
        assertEquals("Vilku sandelis", bucketBody["name"]!!.jsonPrimitive.content)
        assertEquals("PASTOVYKLE", bucketBody["type"]!!.jsonPrimitive.content)

        val updateBucketResponse = client.put("/api/events/$eventId/inventory-buckets/$bucketId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "  Centrinis sandelis  ", "notes": "Atnaujinta" }""")
        }
        assertEquals(HttpStatusCode.OK, updateBucketResponse.status)
        val updatedBucket = Json.parseToJsonElement(updateBucketResponse.bodyAsText()).jsonObject
        assertEquals("Centrinis sandelis", updatedBucket["name"]!!.jsonPrimitive.content)
        assertEquals("Atnaujinta", updatedBucket["notes"]!!.jsonPrimitive.content)

        val createItemsResponse = client.post("/api/events/$eventId/inventory-items/bulk") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "items": [
                        {
                            "name": "  Puodai  ",
                            "plannedQuantity": 4,
                            "bucketId": "$bucketId",
                            "responsibleUserId": "$responsibleUserId",
                            "notes": "Virtuvei"
                        },
                        {
                            "name": "Kirviai",
                            "plannedQuantity": 2
                        }
                    ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, createItemsResponse.status)
        val createdItems = Json.parseToJsonElement(createItemsResponse.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(2, createdItems.size)
        val firstItem = createdItems.first().jsonObject
        val eventInventoryItemId = firstItem["id"]!!.jsonPrimitive.content
        assertEquals("Puodai", firstItem["name"]!!.jsonPrimitive.content)
        assertEquals(bucketId, firstItem["bucketId"]!!.jsonPrimitive.content)
        assertEquals(responsibleUserId, firstItem["responsibleUserId"]!!.jsonPrimitive.content)

        val updateItemResponse = client.put("/api/events/$eventId/inventory-items/$eventInventoryItemId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "  Dideli puodai  ", "plannedQuantity": 5, "notes": "Atnaujinti" }""")
        }
        assertEquals(HttpStatusCode.OK, updateItemResponse.status)
        val updatedItem = Json.parseToJsonElement(updateItemResponse.bodyAsText()).jsonObject
        assertEquals("Dideli puodai", updatedItem["name"]!!.jsonPrimitive.content)
        assertEquals(5, updatedItem["plannedQuantity"]!!.jsonPrimitive.int)
        assertEquals("Atnaujinti", updatedItem["notes"]!!.jsonPrimitive.content)

        val createAllocationResponse = client.post("/api/events/$eventId/inventory-allocations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$eventInventoryItemId", "bucketId": "$bucketId", "quantity": 3, "notes": "Paskirstyta" }""")
        }
        assertEquals(HttpStatusCode.Created, createAllocationResponse.status)
        val allocationBody = Json.parseToJsonElement(createAllocationResponse.bodyAsText()).jsonObject
        val allocationId = allocationBody["id"]!!.jsonPrimitive.content
        assertEquals(3, allocationBody["quantity"]!!.jsonPrimitive.int)

        val deleteBucketWhileAllocatedResponse = client.delete("/api/events/$eventId/inventory-buckets/$bucketId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, deleteBucketWhileAllocatedResponse.status)

        val updateAllocationResponse = client.put("/api/events/$eventId/inventory-allocations/$allocationId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 1, "notes": "Sumazinta" }""")
        }
        assertEquals(HttpStatusCode.OK, updateAllocationResponse.status)
        val updatedAllocation = Json.parseToJsonElement(updateAllocationResponse.bodyAsText()).jsonObject
        assertEquals(1, updatedAllocation["quantity"]!!.jsonPrimitive.int)
        assertEquals("Sumazinta", updatedAllocation["notes"]!!.jsonPrimitive.content)

        val deleteAllocationResponse = client.delete("/api/events/$eventId/inventory-allocations/$allocationId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, deleteAllocationResponse.status)

        val deleteItemResponse = client.delete("/api/events/$eventId/inventory-items/$eventInventoryItemId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, deleteItemResponse.status)

        val deleteBucketResponse = client.delete("/api/events/$eventId/inventory-buckets/$bucketId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, deleteBucketResponse.status)

        val planResponse = client.get("/api/events/$eventId/inventory-plan") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, planResponse.status)
        val plan = Json.parseToJsonElement(planResponse.bodyAsText()).jsonObject
        assertEquals(0, plan["allocations"]!!.jsonArray.size)
        assertFalse(plan["buckets"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == bucketId })
        assertFalse(plan["items"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == eventInventoryItemId })
    }

    @Test
    fun `bulk inventory item creation validates size and nested payloads`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)

        val emptyBulkResponse = client.post("/api/events/$eventId/inventory-items/bulk") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, emptyBulkResponse.status)
        assertTrue(emptyBulkResponse.bodyAsText().contains("Įveskite bent vieną inventoriaus objektą"))

        val invalidNestedResponse = client.post("/api/events/$eventId/inventory-items/bulk") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "items": [{ "name": "", "plannedQuantity": 0 }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidNestedResponse.status)
        assertTrue(invalidNestedResponse.bodyAsText().contains("Planuojamas kiekis turi būti bent 1"))
    }

    @Test
    fun `purchase invoice download returns not found when file is missing`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 2)
        val purchase = client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 1)
        val purchaseId = purchase["id"]!!.jsonPrimitive.content

        val invoiceResponse = client.post("/api/events/$eventId/purchases/$purchaseId/invoice") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "invoiceFileUrl": "/uploads/documents/missing-invoice.pdf" }""")
        }
        assertEquals(HttpStatusCode.OK, invoiceResponse.status)

        val downloadResponse = client.get("/api/events/$eventId/purchases/$purchaseId/invoice/download") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.NotFound, downloadResponse.status)
        assertTrue(downloadResponse.bodyAsText().contains("Sąskaitos failas nerastas"))
    }

    @Test
    fun `event wrap up reconciliation resolves returns purchases and completion`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val sourceItemId = client.createTestItem(token, tuntasId, quantity = 2)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$sourceItemId", "name": "Puodas", "plannedQuantity": 4 }""")
        }
        assertEquals(HttpStatusCode.Created, eventItemResponse.status)
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val purchase = client.createEventPurchase(token, tuntasId, eventId, eventInventoryItemId, purchasedQuantity = 1, unitPrice = 6.5)
        val purchaseId = purchase["id"]!!.jsonPrimitive.content
        val purchaseItemId = purchase["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        val completePurchaseResponse = client.post("/api/events/$eventId/purchases/$purchaseId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, completePurchaseResponse.status)

        val wrapUpResponse = client.put("/api/events/$eventId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "WRAP_UP" }""")
        }
        assertEquals(HttpStatusCode.OK, wrapUpResponse.status)

        val reconciliationResponse = client.get("/api/events/$eventId/reconciliation") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, reconciliationResponse.status)
        val reconciliation = Json.parseToJsonElement(reconciliationResponse.bodyAsText()).jsonObject
        assertEquals(0, reconciliation["openReturns"]!!.jsonArray.size)
        assertEquals(1, reconciliation["unresolvedPurchases"]!!.jsonArray.size)
        assertFalse(reconciliation["canComplete"]!!.jsonPrimitive.boolean)

        val earlyCompleteResponse = client.post("/api/events/$eventId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, earlyCompleteResponse.status)

        val reconcilePurchasesResponse = client.post("/api/events/$eventId/reconciliation/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "purchases": [
                        {
                            "purchaseItemId": "$purchaseItemId",
                            "decision": "CONSUMED",
                            "quantity": 1,
                            "notes": "Prideta i sandeli"
                        }
                    ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, reconcilePurchasesResponse.status)
        val reconciled = Json.parseToJsonElement(reconcilePurchasesResponse.bodyAsText()).jsonObject
        assertEquals(0, reconciled["openReturns"]!!.jsonArray.size)
        assertEquals(0, reconciled["unresolvedPurchases"]!!.jsonArray.size)
        assertTrue(reconciled["canComplete"]!!.jsonPrimitive.boolean)

        val completeEventResponse = client.post("/api/events/$eventId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, completeEventResponse.status)
        assertEquals("COMPLETED", Json.parseToJsonElement(completeEventResponse.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content)

        val completedMutationResponse = client.post("/api/events/$eventId/inventory-buckets") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Nebegalima", "type": "OTHER" }""")
        }
        assertEquals(HttpStatusCode.BadRequest, completedMutationResponse.status)
    }

    @Test
    fun `reconciliation endpoints validate wrap up state and bad payloads`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val itemId = client.createTestItem(token, tuntasId, quantity = 2)
        val eventInventoryItemId = client.createEventInventoryItem(token, tuntasId, eventId, plannedQuantity = 2, name = "Kirvis")

        val checkoutResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$eventInventoryItemId", "movementType": "CHECKOUT_TO_PERSON", "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.BadRequest, checkoutResponse.status)

        val linkedEventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$itemId", "name": "Kirvis", "plannedQuantity": 2 }""")
        }
        val linkedEventItemId = Json.parseToJsonElement(linkedEventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val linkedCheckoutResponse = client.post("/api/events/$eventId/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$linkedEventItemId", "movementType": "CHECKOUT_TO_PERSON", "quantity": 1 }""")
        }
        assertEquals(HttpStatusCode.Created, linkedCheckoutResponse.status)
        val custodyId = Json.parseToJsonElement(linkedCheckoutResponse.bodyAsText()).jsonObject["custodyId"]!!.jsonPrimitive.content

        val notWrapUpResponse = client.post("/api/events/$eventId/reconciliation/returns") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "returns": [{ "custodyId": "$custodyId", "decision": "RETURNED", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, notWrapUpResponse.status)
        assertTrue(notWrapUpResponse.bodyAsText().contains("renginio užbaigimo", ignoreCase = true))

        val wrapUpResponse = client.put("/api/events/$eventId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "status": "WRAP_UP" }""")
        }
        assertEquals(HttpStatusCode.OK, wrapUpResponse.status)

        val invalidDecisionResponse = client.post("/api/events/$eventId/reconciliation/returns") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "returns": [{ "custodyId": "$custodyId", "decision": "BROKEN", "quantity": 1 }] }""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalidDecisionResponse.status)
        assertTrue(invalidDecisionResponse.bodyAsText().contains("Neteisingas grąžinimo sprendimas"))

        val validReturnResponse = client.post("/api/events/$eventId/reconciliation/returns") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "returns": [{ "custodyId": "$custodyId", "decision": "RETURNED", "quantity": 1, "notes": "Uzdaryta" }] }""")
        }
        assertEquals(HttpStatusCode.OK, validReturnResponse.status)
        val reconciliation = Json.parseToJsonElement(validReturnResponse.bodyAsText()).jsonObject
        assertEquals(0, reconciliation["openReturns"]!!.jsonArray.size)
        assertTrue(reconciliation["returnedToEventStorage"]!!.jsonArray.isNotEmpty())

        val movementsResponse = client.get("/api/events/$eventId/inventory-movements") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, movementsResponse.status)
        val movements = Json.parseToJsonElement(movementsResponse.bodyAsText()).jsonObject["movements"]!!.jsonArray
        assertTrue(movements.any { it.jsonObject["movementType"]!!.jsonPrimitive.content == "RECONCILE_RETURNED" })
    }

    @Test
    fun `pastovykle request routes cover listing approval fulfillment rejection self provided and assign from unit`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val unitId = client.createUnit(token, tuntasId, "Zygio draugove")
        val (responsibleToken, responsibleUserId) = registerUserWithRole(
            token,
            tuntasId,
            "Draugininkas",
            "pastovykle-route-owner@test.com",
            unitId
        )
        val eventId = client.createTestEvent(token, tuntasId)
        client.activateEventForMovement(token, tuntasId, eventId)
        val pastovykleId = client.createTestPastovykle(token, tuntasId, eventId, "Lapes", responsibleUserId)
        val sharedItemId = client.createTestItem(token, tuntasId, quantity = 8)

        val eventItemResponse = client.post("/api/events/$eventId/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$sharedItemId", "name": "Puodai", "plannedQuantity": 6 }""")
        }
        assertEquals(HttpStatusCode.Created, eventItemResponse.status)
        val eventInventoryItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val createRequestResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$eventInventoryItemId", "quantity": 2, "notes": "Reikia vakarienei" }""")
        }
        assertEquals(HttpStatusCode.Created, createRequestResponse.status)
        val requestId = Json.parseToJsonElement(createRequestResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val listRequestsResponse = client.get("/api/events/$eventId/pastovykles/$pastovykleId/requests") {
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, listRequestsResponse.status)
        val listedRequests = Json.parseToJsonElement(listRequestsResponse.bodyAsText()).jsonObject["requests"]!!.jsonArray
        assertTrue(listedRequests.any { it.jsonObject["id"]!!.jsonPrimitive.content == requestId })

        val approveResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests/$requestId/approve") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status)
        val approvedBody = Json.parseToJsonElement(approveResponse.bodyAsText()).jsonObject
        assertEquals("APPROVED", approvedBody["status"]!!.jsonPrimitive.content)

        val fulfillResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests/$requestId/fulfill") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "quantity": 2, "notes": "Isduota is renginio sandelio" }""")
        }
        assertEquals(HttpStatusCode.OK, fulfillResponse.status)
        val fulfilledBody = Json.parseToJsonElement(fulfillResponse.bodyAsText()).jsonObject
        assertEquals("FULFILLED", fulfilledBody["status"]!!.jsonPrimitive.content)
        assertEquals("Isduota is renginio sandelio", fulfilledBody["notes"]!!.jsonPrimitive.content)

        val selfProvidedCreate = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{ "eventInventoryItemId": "$eventInventoryItemId", "quantity": 1, "notes": "Atsivesime patys", "provider": "UNIT" }"""
            )
        }
        assertEquals(HttpStatusCode.Created, selfProvidedCreate.status)
        val selfProvidedCreateBody = Json.parseToJsonElement(selfProvidedCreate.bodyAsText()).jsonObject
        val selfProvidedId = selfProvidedCreateBody["id"]!!.jsonPrimitive.content
        assertEquals("UNIT", selfProvidedCreateBody["provider"]!!.jsonPrimitive.content)

        val unitNeedApprove = client.post(
            "/api/events/$eventId/pastovykles/$pastovykleId/requests/$selfProvidedId/approve"
        ) {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.BadRequest, unitNeedApprove.status)

        val selfProvidedResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests/$selfProvidedId/self-provided") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "notes": "Susiorganizuosime savo" }""")
        }
        assertEquals(HttpStatusCode.OK, selfProvidedResponse.status)
        val selfProvidedBody = Json.parseToJsonElement(selfProvidedResponse.bodyAsText()).jsonObject
        assertEquals("SELF_PROVIDED", selfProvidedBody["status"]!!.jsonPrimitive.content)

        val rejectCreate = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "eventInventoryItemId": "$eventInventoryItemId", "quantity": 1, "notes": "Papildomas puodas" }""")
        }
        assertEquals(HttpStatusCode.Created, rejectCreate.status)
        val rejectRequestId = Json.parseToJsonElement(rejectCreate.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val rejectResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/requests/$rejectRequestId/reject") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, rejectResponse.status)
        val rejectedBody = Json.parseToJsonElement(rejectResponse.bodyAsText()).jsonObject
        assertEquals("REJECTED", rejectedBody["status"]!!.jsonPrimitive.content)

        val unitItemCreate = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Vieneto kirvis", "type": "COLLECTIVE", "category": "TOOLS", "quantity": 3, "custodianId": "$unitId" }""")
        }
        assertEquals(HttpStatusCode.Created, unitItemCreate.status)
        val unitItemId = Json.parseToJsonElement(unitItemCreate.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val assignFromUnitResponse = client.post("/api/events/$eventId/pastovykles/$pastovykleId/assign-from-unit") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "itemId": "$unitItemId", "quantity": 2, "notes": "Atsivezta is vieneto" }""")
        }
        assertEquals(HttpStatusCode.Created, assignFromUnitResponse.status)
        val assignedBody = Json.parseToJsonElement(assignFromUnitResponse.bodyAsText()).jsonObject
        assertEquals(2, assignedBody["quantityAssigned"]!!.jsonPrimitive.int)
        assertEquals("Atsivezta is vieneto", assignedBody["notes"]!!.jsonPrimitive.content)

        val inventoryResponse = client.get("/api/events/$eventId/pastovykles/$pastovykleId/inventory") {
            header("Authorization", "Bearer $responsibleToken")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, inventoryResponse.status)
        val inventoryItems = Json.parseToJsonElement(inventoryResponse.bodyAsText()).jsonObject["inventory"]!!.jsonArray
        assertTrue(inventoryItems.size >= 2)
    }
}
