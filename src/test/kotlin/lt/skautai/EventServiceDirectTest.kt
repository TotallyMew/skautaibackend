package lt.skautai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateEventInventoryAllocationRequest
import lt.skautai.models.requests.CreateEventInventoryBucketRequest
import lt.skautai.models.requests.CreateEventInventoryItemRequest
import lt.skautai.models.requests.CreateEventInventoryItemsBulkRequest
import lt.skautai.models.requests.ReconcileEventPurchaseLineRequest
import lt.skautai.models.requests.ReconcileEventPurchasesRequest
import lt.skautai.models.requests.ReconcileEventReturnLineRequest
import lt.skautai.models.requests.ReconcileEventReturnsRequest
import lt.skautai.services.EventService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventServiceDirectTest {

    private val service = EventService()

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

    private suspend fun HttpClient.createEvent(token: String, tuntasId: String, plannedQuantity: Int = 3): String {
        val response = post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Direct test event",
                    "type": "STOVYKLA",
                    "startDate": "2026-07-01",
                    "endDate": "2026-07-07"
                }
                """.trimIndent()
            )
        }
        check(response.status == HttpStatusCode.Created)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.activateEvent(token: String, tuntasId: String, eventId: String) {
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
        check(response.status == HttpStatusCode.OK)
    }

    private suspend fun HttpClient.createItem(token: String, tuntasId: String, quantity: Int): String {
        val response = post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "Direct item", "type": "COLLECTIVE", "category": "TOOLS", "quantity": $quantity }""")
        }
        check(response.status == HttpStatusCode.Created)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun userIdForEmail(email: String): UUID = transaction {
        Users.selectAll()
            .where { Users.email eq email }
            .first()[Users.id]
    }

    @Test
    fun `event service validates bucket item bulk and allocation inputs directly`() = testApplication {
        configureFullApp()
        val email = "event-direct@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = email)
        val tuntasId = UUID.fromString(tuntasIdText)
        val eventId = UUID.fromString(client.createEvent(token, tuntasIdText))
        val userId = userIdForEmail(email)

        val blankBucket = service.createInventoryBucket(
            eventId,
            tuntasId,
            CreateEventInventoryBucketRequest(name = " ", type = "OTHER")
        )
        assertEquals("Name cannot be blank", blankBucket.exceptionOrNull()?.message)

        val invalidBucketType = service.createInventoryBucket(
            eventId,
            tuntasId,
            CreateEventInventoryBucketRequest(name = "Zona", type = "BAD")
        )
        assertEquals("Invalid bucket type", invalidBucketType.exceptionOrNull()?.message)

        val missingPastovykle = service.createInventoryBucket(
            eventId,
            tuntasId,
            CreateEventInventoryBucketRequest(name = "Stovykle", type = "PASTOVYKLE")
        )
        assertEquals("PASTOVYKLE bucket requires pastovykleId", missingPastovykle.exceptionOrNull()?.message)

        val invalidLocation = service.createInventoryBucket(
            eventId,
            tuntasId,
            CreateEventInventoryBucketRequest(name = "Zona", type = "OTHER", locationId = "bad-uuid")
        )
        assertEquals("Invalid location ID", invalidLocation.exceptionOrNull()?.message)

        val invalidItemQuantity = service.createInventoryItem(
            eventId,
            tuntasId,
            userId,
            CreateEventInventoryItemRequest(name = "Puodas", plannedQuantity = 0)
        )
        assertEquals("Planned quantity must be at least 1", invalidItemQuantity.exceptionOrNull()?.message)

        val invalidItemId = service.createInventoryItem(
            eventId,
            tuntasId,
            userId,
            CreateEventInventoryItemRequest(itemId = "bad-uuid", name = "Puodas", plannedQuantity = 1)
        )
        assertEquals("Invalid item ID", invalidItemId.exceptionOrNull()?.message)

        val missingName = service.createInventoryItem(
            eventId,
            tuntasId,
            userId,
            CreateEventInventoryItemRequest(name = " ", plannedQuantity = 1)
        )
        assertEquals("Name cannot be blank", missingName.exceptionOrNull()?.message)

        val invalidResponsible = service.createInventoryItem(
            eventId,
            tuntasId,
            userId,
            CreateEventInventoryItemRequest(name = "Puodas", plannedQuantity = 1, responsibleUserId = "bad-uuid")
        )
        assertEquals("Invalid responsible user ID", invalidResponsible.exceptionOrNull()?.message)

        val bulkTooLarge = service.createInventoryItemsBulk(
            eventId,
            tuntasId,
            userId,
            CreateEventInventoryItemsBulkRequest(
                items = List(201) { index ->
                    CreateEventInventoryItemRequest(name = "Item $index", plannedQuantity = 1)
                }
            )
        )
        assertEquals("Cannot add more than 200 items at once", bulkTooLarge.exceptionOrNull()?.message)

        val createdBucket = service.createInventoryBucket(
            eventId,
            tuntasId,
            CreateEventInventoryBucketRequest(name = "Virtuve", type = "KITCHEN")
        ).getOrThrow()
        val createdItem = service.createInventoryItem(
            eventId,
            tuntasId,
            userId,
            CreateEventInventoryItemRequest(name = "Puodas", plannedQuantity = 2, bucketId = createdBucket.id)
        ).getOrThrow()

        val invalidAllocationQuantity = service.createInventoryAllocation(
            eventId,
            tuntasId,
            CreateEventInventoryAllocationRequest(
                eventInventoryItemId = createdItem.id,
                bucketId = createdBucket.id,
                quantity = 0
            )
        )
        assertEquals("Quantity must be at least 1", invalidAllocationQuantity.exceptionOrNull()?.message)

        val invalidAllocationItem = service.createInventoryAllocation(
            eventId,
            tuntasId,
            CreateEventInventoryAllocationRequest(
                eventInventoryItemId = "bad-uuid",
                bucketId = createdBucket.id,
                quantity = 1
            )
        )
        assertEquals("Invalid event inventory item ID", invalidAllocationItem.exceptionOrNull()?.message)

        val createdAllocation = service.createInventoryAllocation(
            eventId,
            tuntasId,
            CreateEventInventoryAllocationRequest(
                eventInventoryItemId = createdItem.id,
                bucketId = createdBucket.id,
                quantity = 1
            )
        ).getOrThrow()

        val blockedDelete = service.deleteInventoryBucket(eventId, UUID.fromString(createdBucket.id), tuntasId)
        assertEquals("Cannot delete bucket with inventory allocations", blockedDelete.exceptionOrNull()?.message)

        val deletedAllocation = service.deleteInventoryAllocation(eventId, UUID.fromString(createdAllocation.id), tuntasId)
        assertTrue(deletedAllocation.isSuccess)
        assertTrue(service.deleteInventoryBucket(eventId, UUID.fromString(createdBucket.id), tuntasId).isSuccess)
    }

    @Test
    fun `event service validates and applies return reconciliation directly`() = testApplication {
        configureFullApp()
        val email = "returns-direct@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = email)
        val tuntasId = UUID.fromString(tuntasIdText)
        val eventIdText = client.createEvent(token, tuntasIdText)
        val eventId = UUID.fromString(eventIdText)
        val userId = userIdForEmail(email)
        client.activateEvent(token, tuntasIdText, eventIdText)
        val itemId = client.createItem(token, tuntasIdText, quantity = 2)
        val notWrapUp = service.reconcileReturns(
            eventId,
            tuntasId,
            userId,
            ReconcileEventReturnsRequest(
                returns = listOf(
                    ReconcileEventReturnLineRequest(
                        custodyId = UUID.randomUUID().toString(),
                        decision = "RETURNED",
                        quantity = 1,
                        notes = "Sugrizo"
                    )
                )
            )
        )
        assertEquals("Returns can be reconciled only during wrap-up", notWrapUp.exceptionOrNull()?.message)

        val eventItemResponse = client.post("/api/events/$eventIdText/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "itemId": "$itemId", "name": "Kirvis", "plannedQuantity": 2 }""")
        }
        val eventItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val checkoutResponse = client.post("/api/events/$eventIdText/inventory-movements") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "eventInventoryItemId": "$eventItemId", "movementType": "CHECKOUT_TO_PERSON", "quantity": 2 }""")
        }
        assertEquals(HttpStatusCode.Created, checkoutResponse.status)
        val custodyId = Json.parseToJsonElement(checkoutResponse.bodyAsText()).jsonObject["custodyId"]!!.jsonPrimitive.content

        client.put("/api/events/$eventIdText") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "status": "WRAP_UP" }""")
        }

        val invalidDecision = service.reconcileReturns(
            eventId,
            tuntasId,
            userId,
            ReconcileEventReturnsRequest(
                returns = listOf(
                    ReconcileEventReturnLineRequest(
                        custodyId = UUID.randomUUID().toString(),
                        decision = "BROKEN",
                        quantity = 1,
                        notes = null
                    )
                )
            )
        )
        assertEquals("Invalid return decision", invalidDecision.exceptionOrNull()?.message)

        val result = service.reconcileReturns(
            eventId,
            tuntasId,
            userId,
            ReconcileEventReturnsRequest(
                returns = listOf(
                    ReconcileEventReturnLineRequest(
                        custodyId = custodyId,
                        decision = "RETURNED",
                        quantity = 2,
                        notes = "Sugrizo"
                    )
                )
            )
        )
        assertTrue(result.isSuccess)
        val reconciliation = result.getOrThrow()
        assertEquals(0, reconciliation.openReturns.size)
        assertTrue(reconciliation.returnedToEventStorage.isNotEmpty())
    }

    @Test
    fun `event service reconciles purchases and completes wrap up directly`() = testApplication {
        configureFullApp()
        val email = "purchases-direct@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = email)
        val tuntasId = UUID.fromString(tuntasIdText)
        val eventIdText = client.createEvent(token, tuntasIdText)
        val eventId = UUID.fromString(eventIdText)
        val userId = userIdForEmail(email)
        val sourceItemId = client.createItem(token, tuntasIdText, quantity = 1)

        val noWrapUpCompletion = service.completeEvent(eventId, tuntasId)
        assertEquals("Event can be completed only during wrap-up", noWrapUpCompletion.exceptionOrNull()?.message)

        val eventItemResponse = client.post("/api/events/$eventIdText/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "itemId": "$sourceItemId", "name": "Puodas", "plannedQuantity": 3 }""")
        }
        val eventItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val purchaseBody = client.post("/api/events/$eventIdText/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "items": [{ "eventInventoryItemId": "$eventItemId", "purchasedQuantity": 1, "unitPrice": 5.5 }] }""")
        }.bodyAsText()
        val purchase = Json.parseToJsonElement(purchaseBody).jsonObject
        val purchaseId = purchase["id"]!!.jsonPrimitive.content
        val purchaseItemId = purchase["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        client.post("/api/events/$eventIdText/purchases/$purchaseId/complete") {
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
        }

        client.put("/api/events/$eventIdText") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "status": "WRAP_UP" }""")
        }

        val reconciled = service.reconcilePurchases(
            eventId,
            tuntasId,
            userId,
            ReconcileEventPurchasesRequest(
                purchases = listOf(
                    ReconcileEventPurchaseLineRequest(
                        purchaseItemId = purchaseItemId,
                        decision = "CONSUMED",
                        quantity = 1,
                        name = null,
                        existingItemId = null,
                        notes = "Sunaudota"
                    )
                )
            )
        )
        assertTrue(reconciled.isSuccess)
        assertEquals(0, reconciled.getOrThrow().unresolvedPurchases.size)

        val completed = service.completeEvent(eventId, tuntasId)
        assertTrue(completed.isSuccess)
        assertEquals("COMPLETED", completed.getOrThrow().status)
    }
}
