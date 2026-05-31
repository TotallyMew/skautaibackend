package lt.skautai

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryTemplateRoutesTest {

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

    private fun userIdByEmail(email: String): UUID = transaction {
        Users.selectAll()
            .where { Users.email eq email }
            .first()[Users.id]
    }

    private suspend fun ApplicationTestBuilder.createEvent(token: String, tuntasId: String, name: String = "Template Event"): String {
        val response = client.post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "$name",
                    "type": "STOVYKLA",
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.createSharedItem(
        token: String,
        tuntasId: String,
        name: String,
        quantity: Int
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
                    "quantity": $quantity
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `inventory template CRUD flow works`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val createResponse = client.post("/api/inventory-templates") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Stovyklos virtuve",
                    "eventType": "STOVYKLA",
                    "items": [
                        { "itemName": "Puodas", "quantity": 2, "category": "COOKING" },
                        { "itemName": "Kirvis", "quantity": 1, "category": "TOOLS", "notes": "Atsargai" }
                    ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)
        val createdBody = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val templateId = createdBody["id"]!!.jsonPrimitive.content
        assertEquals(2, createdBody["items"]!!.jsonArray.size)

        val listResponse = client.get("/api/inventory-templates?eventType=STOVYKLA") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listBody = Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject
        assertEquals(1, listBody["total"]!!.jsonPrimitive.content.toInt())

        val updateResponse = client.put("/api/inventory-templates/$templateId") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Atnaujinta virtuve",
                    "items": [
                        { "itemName": "Puodas", "quantity": 3, "category": "COOKING" }
                    ]
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, updateResponse.status)
        val updatedBody = Json.parseToJsonElement(updateResponse.bodyAsText()).jsonObject
        assertEquals("Atnaujinta virtuve", updatedBody["name"]!!.jsonPrimitive.content)
        assertEquals(1, updatedBody["items"]!!.jsonArray.size)

        val deleteResponse = client.delete("/api/inventory-templates/$templateId") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
        }
        assertEquals(HttpStatusCode.OK, deleteResponse.status)
    }

    @Test
    fun `inventory template validates bad payloads`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()

        val response = client.post("/api/inventory-templates") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": " ",
                    "items": [
                        { "itemName": " ", "quantity": 0 }
                    ]
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `apply template to event creates inventory plan items`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = createEvent(token, tuntasId)

        val templateResponse = client.post("/api/inventory-templates") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Zygio rinkinys",
                    "items": [
                        { "itemName": "Palapine", "quantity": 2, "category": "CAMPING" },
                        { "itemName": "Puodas", "quantity": 1, "category": "COOKING" }
                    ]
                }
                """.trimIndent()
            )
        }
        val templateId = Json.parseToJsonElement(templateResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val applyResponse = client.post("/api/events/$eventId/inventory-plan/from-template") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "templateId": "$templateId" }""")
        }

        assertEquals(HttpStatusCode.Created, applyResponse.status)
        val body = Json.parseToJsonElement(applyResponse.bodyAsText()).jsonObject
        assertEquals(2, body["total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `apply template with reservation reserves what is available and creates purchase shortage`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = createEvent(token, tuntasId, "Reservation Template Event")
        val itemId = createSharedItem(token, tuntasId, "Kirvis", 2)

        val templateResponse = client.post("/api/inventory-templates") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Kirviu rinkinys",
                    "items": [
                        { "itemId": "$itemId", "itemName": "Kirvis", "quantity": 3, "category": "TOOLS" }
                    ]
                }
                """.trimIndent()
            )
        }
        val templateId = Json.parseToJsonElement(templateResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val applyResponse = client.post("/api/events/$eventId/apply-template-with-reservation") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "templateId": "$templateId" }""")
        }

        assertEquals(HttpStatusCode.Created, applyResponse.status)
        val body = Json.parseToJsonElement(applyResponse.bodyAsText()).jsonObject
        assertEquals(2, body["reservedTotal"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, body["toPurchaseTotal"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["reserved"]!!.jsonArray.isNotEmpty())
        assertTrue(body["toPurchase"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `apply template with reservation creates purchase only when no source item matches`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = createEvent(token, tuntasId, "Purchase Only Event")

        val templateResponse = client.post("/api/inventory-templates") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Nerasto daikto rinkinys",
                    "items": [
                        { "itemName": "Neegzistuojantis daiktas", "quantity": 2, "category": "TOOLS" }
                    ]
                }
                """.trimIndent()
            )
        }
        val templateId = Json.parseToJsonElement(templateResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val applyResponse = client.post("/api/events/$eventId/apply-template-with-reservation") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "templateId": "$templateId" }""")
        }

        assertEquals(HttpStatusCode.Created, applyResponse.status)
        val body = Json.parseToJsonElement(applyResponse.bodyAsText()).jsonObject
        assertEquals(0, body["reservedTotal"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, body["toPurchaseTotal"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["reserved"]!!.jsonArray.isEmpty())
        assertTrue(body["toPurchase"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `apply template with reservation matches by name and subtracts overlapping reservations`() = testApplication {
        configureFullApp()
        val ownerEmail = "template-overlap@test.com"
        val (token, tuntasId) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val eventId = createEvent(token, tuntasId, "Overlap Event")
        val itemId = createSharedItem(token, tuntasId, "Didelis Kirvis", 5)
        val ownerId = userIdByEmail(ownerEmail)

        transaction {
            Reservations.insert {
                it[groupId] = UUID.randomUUID()
                it[title] = "Ankstesne rezervacija"
                it[this.itemId] = UUID.fromString(itemId)
                it[this.tuntasId] = UUID.fromString(tuntasId)
                it[reservedByUserId] = ownerId
                it[requestingUnitId] = null
                it[this.eventId] = null
                it[quantity] = 4
                it[startDate] = LocalDate.parse("2026-07-03")
                it[endDate] = LocalDate.parse("2026-07-04")
                it[unitReviewStatus] = "NOT_REQUIRED"
                it[topLevelReviewStatus] = "APPROVED"
                it[topLevelReviewedByUserId] = ownerId
                it[topLevelReviewedAt] = Clock.System.now()
                it[status] = "APPROVED"
                it[notes] = "Persidengianti rezervacija"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        val templateResponse = client.post("/api/inventory-templates") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Kirviu paieska pagal pavadinima",
                    "items": [
                        { "itemName": "Kirvis", "quantity": 3, "category": "TOOLS" }
                    ]
                }
                """.trimIndent()
            )
        }
        val templateId = Json.parseToJsonElement(templateResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val applyResponse = client.post("/api/events/$eventId/apply-template-with-reservation") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "templateId": "$templateId" }""")
        }

        assertEquals(HttpStatusCode.Created, applyResponse.status)
        val body = Json.parseToJsonElement(applyResponse.bodyAsText()).jsonObject
        assertEquals(1, body["reservedTotal"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, body["toPurchaseTotal"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["reserved"]!!.jsonArray.isNotEmpty())
        assertTrue(body["toPurchase"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `apply template with reservation splits one need across multiple source items`() = testApplication {
        configureFullApp()
        val (token, tuntasId) = client.registerAndActivateTuntininkas()
        val eventId = createEvent(token, tuntasId, "Split Source Event")
        createSharedItem(token, tuntasId, "Palapine A", 6)
        createSharedItem(token, tuntasId, "Palapine B", 4)

        val templateResponse = client.post("/api/inventory-templates") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Palapiniu rinkinys",
                    "items": [
                        { "itemName": "Palapine", "quantity": 10, "category": "CAMPING" }
                    ]
                }
                """.trimIndent()
            )
        }
        val templateId = Json.parseToJsonElement(templateResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val applyResponse = client.post("/api/events/$eventId/apply-template-with-reservation") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "templateId": "$templateId" }""")
        }

        assertEquals(HttpStatusCode.Created, applyResponse.status)
        val body = Json.parseToJsonElement(applyResponse.bodyAsText()).jsonObject
        val sourceQuantities = body["sources"]!!.jsonArray
            .map { it.jsonObject["reservedQuantity"]!!.jsonPrimitive.content.toInt() }
            .sortedDescending()
        assertEquals(10, body["reservedTotal"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, body["toPurchaseTotal"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf(6, 4), sourceQuantities)
    }
}
