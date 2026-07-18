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
import lt.skautai.database.tables.EventPurchases
import lt.skautai.database.tables.PastovykleInventory
import lt.skautai.database.tables.Pastovykles
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreatePastovykleRequest
import lt.skautai.models.requests.CreateEventInventoryAllocationRequest
import lt.skautai.models.requests.CreateEventInventoryBucketRequest
import lt.skautai.models.requests.CreateEventInventoryItemRequest
import lt.skautai.models.requests.CreateEventInventoryItemsBulkRequest
import lt.skautai.models.requests.CreateEventInventoryMovementRequest
import lt.skautai.models.requests.ReconcileEventPurchaseLineRequest
import lt.skautai.models.requests.ReconcileEventPurchasesRequest
import lt.skautai.models.requests.ReconcileEventReturnLineRequest
import lt.skautai.models.requests.ReconcileEventReturnsRequest
import lt.skautai.models.requests.UpdateEventRequest
import lt.skautai.services.EventService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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

    private suspend fun HttpClient.createEvent(token: String, tuntasId: String, type: String = "STOVYKLA"): String {
        val response = post("/api/events") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """
                {
                    "name": "Direct test event",
                    "type": "$type",
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

    private suspend fun HttpClient.createLocation(
        token: String,
        tuntasId: String,
        name: String,
        visibility: String = "PUBLIC"
    ): String {
        val response = post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "visibility": "$visibility" }""")
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
    fun `event inventory item exposes pickup source snapshot`() = testApplication {
        configureFullApp()
        val email = "pickup-source@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = email)
        val tuntasId = UUID.fromString(tuntasIdText)
        val eventId = UUID.fromString(client.createEvent(token, tuntasIdText))
        val userId = userIdForEmail(email)
        val locationId = client.createLocation(token, tuntasIdText, "Garazas")
        client.activateEvent(token, tuntasIdText, eventId.toString())

        val itemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody(
                """
                {
                    "name": "Puodas",
                    "type": "COLLECTIVE",
                    "category": "TOOLS",
                    "quantity": 2,
                    "locationId": "$locationId",
                    "responsibleUserId": "$userId",
                    "temporaryStorageLabel": "Virsutine lentyna"
                }
                """.trimIndent()
            )
        }
        val itemId = Json.parseToJsonElement(itemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val created = service.createInventoryItem(
            eventId = eventId,
            tuntasId = tuntasId,
            createdByUserId = userId,
            request = CreateEventInventoryItemRequest(
                itemId = itemId,
                name = "Puodas",
                plannedQuantity = 2
            )
        ).getOrThrow()

        assertEquals("Garazas", created.sourceLocationPath)
        assertEquals("Virsutine lentyna", created.sourceTemporaryStorageLabel)
        assertTrue(created.sourceResponsibleUserName?.contains("Test Tuntininkas") == true)
        assertTrue(created.sourcePickupSummary?.contains("Garazas") == true)
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
            setBody("""{ "status": "ACTIVE" }""")
        }

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
        assertTrue(reconciliation.sessionId != null)
        assertEquals(0, reconciliation.openReturns.size)
        assertTrue(reconciliation.returnedToEventStorage.isNotEmpty())
    }

    @Test
    fun `event reconciliation summary includes return destination and condition`() = testApplication {
        configureFullApp()
        val email = "reconciliation-summary@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = email)
        val tuntasId = UUID.fromString(tuntasIdText)
        val eventId = UUID.fromString(client.createEvent(token, tuntasIdText))
        val userId = userIdForEmail(email)
        val locationId = client.createLocation(token, tuntasIdText, "Garazas")
        client.activateEvent(token, tuntasIdText, eventId.toString())

        val itemResponse = client.post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody(
                """
                {
                    "name": "Kirvis",
                    "type": "COLLECTIVE",
                    "category": "TOOLS",
                    "quantity": 1,
                    "locationId": "$locationId",
                    "temporaryStorageLabel": "Stovas A"
                }
                """.trimIndent()
            )
        }
        val itemId = Json.parseToJsonElement(itemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val eventItem = service.createInventoryItem(
            eventId = eventId,
            tuntasId = tuntasId,
            createdByUserId = userId,
            request = CreateEventInventoryItemRequest(
                itemId = itemId,
                name = "Kirvis",
                plannedQuantity = 1
            )
        ).getOrThrow()
        val pastovykle = service.createPastovykle(eventId, tuntasId, CreatePastovykleRequest(name = "Aitvarai")).getOrThrow()

        val movement = service.createInventoryMovement(
            eventId = eventId,
            tuntasId = tuntasId,
            performedByUserId = userId,
            request = CreateEventInventoryMovementRequest(
                eventInventoryItemId = eventItem.id,
                movementType = "ASSIGN_TO_PASTOVYKLE",
                quantity = 1,
                pastovykleId = pastovykle.id
            ),
            canManageInventory = true
        ).getOrThrow()

        service.updateEvent(eventId, tuntasId, UpdateEventRequest(status = "WRAP_UP")).getOrThrow()

        val reconciliation = service.reconcileReturns(
            eventId = eventId,
            tuntasId = tuntasId,
            userId = userId,
            request = ReconcileEventReturnsRequest(
                returns = listOf(
                    ReconcileEventReturnLineRequest(
                        custodyId = movement.custodyId!!,
                        decision = "DAMAGED",
                        quantity = 1,
                        notes = "Suluzo"
                    )
                )
            )
        ).getOrThrow()

        val row = reconciliation.returnedToEventStorage.first()
        assertTrue(row.isReturned)
        assertEquals("DAMAGED", row.returnDecision)
        assertEquals("DAMAGED", row.returnCondition)
        assertTrue(row.returnedToSummary?.contains("Garazas") == true)
        assertTrue(row.sourcePickupSummary?.contains("Stovas A") == true)
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
            setBody("""{ "status": "ACTIVE" }""")
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

    @Test
    fun `event service validates invoice file names and purchase completion states`() = testApplication {
        configureFullApp()
        val email = "invoice-direct@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = email)
        val tuntasId = UUID.fromString(tuntasIdText)
        val eventIdText = client.createEvent(token, tuntasIdText)
        val eventId = UUID.fromString(eventIdText)
        val sourceItemId = client.createItem(token, tuntasIdText, quantity = 1)

        val eventItemResponse = client.post("/api/events/$eventIdText/inventory-items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "itemId": "$sourceItemId", "name": "Puodas", "plannedQuantity": 2 }""")
        }
        val eventItemId = Json.parseToJsonElement(eventItemResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val purchaseBody = client.post("/api/events/$eventIdText/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "items": [{ "eventInventoryItemId": "$eventItemId", "purchasedQuantity": 1, "unitPrice": 7.0 }] }""")
        }.bodyAsText()
        val purchaseId = Json.parseToJsonElement(purchaseBody).jsonObject["id"]!!.jsonPrimitive.content
        val purchaseUUID = UUID.fromString(purchaseId)

        val missingInvoice = service.getPurchaseInvoiceFileName(eventId, purchaseUUID, tuntasId)
        assertEquals("Invoice not attached", missingInvoice.exceptionOrNull()?.message)

        transaction {
            EventPurchases.update({ EventPurchases.id eq purchaseUUID }) {
                it[invoiceFileUrl] = "https://example.com/invoice.pdf"
            }
        }
        val nondownloadable = service.getPurchaseInvoiceFileName(eventId, purchaseUUID, tuntasId)
        assertEquals("Invoice file URL is not downloadable", nondownloadable.exceptionOrNull()?.message)

        transaction {
            EventPurchases.update({ EventPurchases.id eq purchaseUUID }) {
                it[invoiceFileUrl] = "/uploads/documents/folder/invoice.pdf"
            }
        }
        val invalidFileName = service.getPurchaseInvoiceFileName(eventId, purchaseUUID, tuntasId)
        assertEquals("Invalid invoice file name", invalidFileName.exceptionOrNull()?.message)

        transaction {
            EventPurchases.update({ EventPurchases.id eq purchaseUUID }) {
                it[invoiceFileUrl] = "/uploads/documents/invoice-1.pdf"
            }
        }
        assertEquals("invoice-1.pdf", service.getPurchaseInvoiceFileName(eventId, purchaseUUID, tuntasId).getOrThrow())

        transaction {
            EventPurchases.update({ EventPurchases.id eq purchaseUUID }) {
                it[status] = "CANCELLED"
            }
        }
        val cancelledComplete = service.completePurchase(eventId, purchaseUUID, tuntasId)
        assertEquals("Cancelled purchase cannot be completed", cancelledComplete.exceptionOrNull()?.message)

        val secondPurchaseBody = client.post("/api/events/$eventIdText/purchases") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasIdText)
            setBody("""{ "items": [{ "eventInventoryItemId": "$eventItemId", "purchasedQuantity": 2, "unitPrice": 8.5 }] }""")
        }.bodyAsText()
        val secondPurchaseId = Json.parseToJsonElement(secondPurchaseBody).jsonObject["id"]!!.jsonPrimitive.content
        val secondPurchaseUUID = UUID.fromString(secondPurchaseId)

        val completed = service.completePurchase(eventId, secondPurchaseUUID, tuntasId)
        assertTrue(completed.isSuccess)
        assertEquals("PURCHASED", completed.getOrThrow().status)

        val completedAgain = service.completePurchase(eventId, secondPurchaseUUID, tuntasId)
        assertTrue(completedAgain.isSuccess)
        assertEquals("PURCHASED", completedAgain.getOrThrow().status)
    }

    @Test
    fun `event service gets and deletes pastovykles and inventory items directly`() = testApplication {
        configureFullApp()
        val email = "delete-direct@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = email)
        val tuntasId = UUID.fromString(tuntasIdText)
        val wrongTypeEventId = UUID.fromString(client.createEvent(token, tuntasIdText, type = "SUEIGA"))
        val eventIdText = client.createEvent(token, tuntasIdText)
        val eventId = UUID.fromString(eventIdText)
        val itemId = client.createItem(token, tuntasIdText, quantity = 2)

        val wrongTypePastovykle = service.getPastovykle(wrongTypeEventId, UUID.randomUUID(), tuntasId)
        assertEquals("Event not found or not of type STOVYKLA", wrongTypePastovykle.exceptionOrNull()?.message)

        val createdPastovykle = service.createPastovykle(
            eventId = eventId,
            tuntasId = tuntasId,
            request = CreatePastovykleRequest(name = "Pirma")
        ).getOrThrow()
        val pastovykleId = UUID.fromString(createdPastovykle.id)

        val fetchedPastovykle = service.getPastovykle(eventId, pastovykleId, tuntasId)
        assertTrue(fetchedPastovykle.isSuccess)
        assertEquals("Pirma", fetchedPastovykle.getOrThrow().name)

        transaction {
            PastovykleInventory.insert {
                it[this.pastovykleId] = pastovykleId
                it[this.itemId] = UUID.fromString(itemId)
                it[quantityAssigned] = 1
                it[quantityReturned] = 0
                it[distributedByUserId] = null
                it[recipientUserId] = null
                it[recipientType] = null
                it[notes] = null
            }
        }
        val blockedDeletePastovykle = service.deletePastovykle(eventId, pastovykleId, tuntasId)
        assertEquals("Cannot delete pastovykl\u0117 with assigned inventory", blockedDeletePastovykle.exceptionOrNull()?.message)

        transaction {
            PastovykleInventory.deleteWhere { PastovykleInventory.pastovykleId eq pastovykleId }
        }
        assertTrue(service.deletePastovykle(eventId, pastovykleId, tuntasId).isSuccess)
        assertEquals("Pastovykl\u0117 not found", service.getPastovykle(eventId, pastovykleId, tuntasId).exceptionOrNull()?.message)

        val createdInventoryItem = service.createInventoryItem(
            eventId = eventId,
            tuntasId = tuntasId,
            createdByUserId = userIdForEmail(email),
            request = CreateEventInventoryItemRequest(itemId = itemId, name = "Kirvis", plannedQuantity = 2)
        ).getOrThrow()
        val inventoryItemId = UUID.fromString(createdInventoryItem.id)
        assertTrue(service.deleteInventoryItem(eventId, inventoryItemId, tuntasId).isSuccess)
        assertEquals("Inventory item not found", service.deleteInventoryItem(eventId, inventoryItemId, tuntasId).exceptionOrNull()?.message)
    }
}

