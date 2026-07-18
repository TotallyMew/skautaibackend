package lt.skautai

import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.DraugoveRequisitionItems
import lt.skautai.database.tables.DraugoveRequisitions
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.AddRequisitionItemToInventoryRequest
import lt.skautai.models.requests.AddRequisitionToInventoryRequest
import lt.skautai.models.requests.CreateRequisitionItemRequest
import lt.skautai.models.requests.CreateRequisitionRequest
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.RequisitionMarkPurchasedRequest
import lt.skautai.models.requests.RequisitionTopLevelReviewRequest
import lt.skautai.services.ItemService
import lt.skautai.services.RequisitionService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequisitionServiceDirectTest {

    private val requisitionService = RequisitionService()
    private val itemService = ItemService()

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

    private fun assignUserToUnit(userId: UUID, unitId: UUID, tuntasId: UUID) {
        transaction {
            UnitAssignments.insert {
                it[this.userId] = userId
                it[organizationalUnitId] = unitId
                it[this.tuntasId] = tuntasId
                it[assignmentType] = "MEMBER"
                it[assignedByUserId] = null
                it[joinedAt] = Clock.System.now()
                it[leftAt] = null
            }
        }
    }

    private fun createPurchasedRequest(
        tuntasId: UUID,
        ownerId: UUID,
        reviewerId: UUID,
        items: List<CreateRequisitionItemRequest>
    ): UUID {
        val created = requisitionService.createRequest(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateRequisitionRequest(
                requestingUnitId = null,
                items = items
            )
        ).getOrThrow()
        val requestId = UUID.fromString(created.id)
        requisitionService.topLevelReview(
            requestId = requestId,
            tuntasId = tuntasId,
            reviewerUserId = reviewerId,
            request = RequisitionTopLevelReviewRequest(action = "APPROVED")
        ).getOrThrow()
        requisitionService.markPurchased(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = RequisitionMarkPurchasedRequest(notes = "Nupirkta")
        ).getOrThrow()
        return requestId
    }

    @Test
    fun `requisition service validates add to inventory new item actions`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("req-owner")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val reviewerEmail = randomEmail("req-reviewer")
        client.registerInvitedUser(token, tuntasIdText, "Inventorininkas", reviewerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val reviewerId = userIdByEmail(reviewerEmail)
        val requestId = createPurchasedRequest(
            tuntasId = tuntasId,
            ownerId = ownerId,
            reviewerId = reviewerId,
            items = listOf(CreateRequisitionItemRequest(itemName = "Puodas", quantity = 2))
        )
        val lineId = transaction {
            DraugoveRequisitionItems.selectAll()
                .where { DraugoveRequisitionItems.requisitionId eq requestId }
                .first()[DraugoveRequisitionItems.id]
        }

        val emptyActions = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(items = emptyList())
        )
        assertEquals("At least one inventory action is required", emptyActions.exceptionOrNull()?.message)

        val invalidLineId = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(AddRequisitionItemToInventoryRequest(requisitionItemId = "not-a-uuid", action = "NEW_ITEM"))
            )
        )
        assertEquals("Invalid requisition item ID", invalidLineId.exceptionOrNull()?.message)

        val missingLine = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = UUID.randomUUID().toString(),
                        action = "NEW_ITEM"
                    )
                )
            )
        )
        assertEquals("Requisition item not found", missingLine.exceptionOrNull()?.message)

        val invalidCustodianId = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "NEW_ITEM",
                        custodianId = "bad-uuid"
                    )
                )
            )
        )
        assertEquals("Invalid custodian ID", invalidCustodianId.exceptionOrNull()?.message)

        val missingCustodian = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "NEW_ITEM",
                        custodianId = UUID.randomUUID().toString()
                    )
                )
            )
        )
        assertEquals("Custodian unit not found in this tuntas", missingCustodian.exceptionOrNull()?.message)

        val invalidType = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "NEW_ITEM",
                        type = "BAD_TYPE"
                    )
                )
            )
        )
        assertEquals("Invalid inventory type", invalidType.exceptionOrNull()?.message)

        val invalidCondition = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "NEW_ITEM",
                        condition = "BROKEN_BAD"
                    )
                )
            )
        )
        assertEquals("Invalid item condition", invalidCondition.exceptionOrNull()?.message)

        val invalidPurchaseDate = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "NEW_ITEM",
                        purchaseDate = "2026/08/10"
                    )
                )
            )
        )
        assertEquals("Invalid purchase date format, use YYYY-MM-DD", invalidPurchaseDate.exceptionOrNull()?.message)

        assertNotNull(token)
    }

    @Test
    fun `requisition service validates and completes restock actions`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("restock-owner")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val reviewerEmail = randomEmail("restock-reviewer")
        client.registerInvitedUser(token, tuntasIdText, "Inventorininkas", reviewerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val reviewerId = userIdByEmail(reviewerEmail)

        val existingItem = itemService.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Kirvis",
                type = "COLLECTIVE",
                category = "TOOLS",
                quantity = 2
            )
        ).getOrThrow()
        val requestId = createPurchasedRequest(
            tuntasId = tuntasId,
            ownerId = ownerId,
            reviewerId = reviewerId,
            items = listOf(
                CreateRequisitionItemRequest(
                    itemName = "Kirvio papildymas",
                    quantity = 3,
                    requestType = "RESTOCK_EXISTING",
                    existingItemId = existingItem.id
                )
            )
        )
        val lineId = transaction {
            DraugoveRequisitionItems.selectAll()
                .where { DraugoveRequisitionItems.requisitionId eq requestId }
                .first()[DraugoveRequisitionItems.id]
        }

        val invalidExistingId = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "RESTOCK_EXISTING",
                        existingItemId = "bad-uuid"
                    )
                )
            )
        )
        assertEquals("Invalid existing item ID", invalidExistingId.exceptionOrNull()?.message)

        val missingExistingItem = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "RESTOCK_EXISTING",
                        existingItemId = UUID.randomUUID().toString()
                    )
                )
            )
        )
        assertEquals("Existing item not found", missingExistingItem.exceptionOrNull()?.message)

        transaction {
            DraugoveRequisitionItems.update({ DraugoveRequisitionItems.id eq lineId }) {
                it[quantityApproved] = 0
            }
        }
        val invalidApprovedQuantity = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "RESTOCK_EXISTING",
                        existingItemId = existingItem.id
                    )
                )
            )
        )
        assertEquals("Approved quantity must be at least 1", invalidApprovedQuantity.exceptionOrNull()?.message)

        transaction {
            DraugoveRequisitionItems.update({ DraugoveRequisitionItems.id eq lineId }) {
                it[quantityApproved] = 3
            }
        }
        val restocked = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "RESTOCK_EXISTING",
                        existingItemId = existingItem.id,
                        purchaseDate = "2026-08-10",
                        purchasePrice = 12.5,
                        notes = "Papildyta"
                    )
                )
            )
        )
        assertTrue(restocked.isSuccess)
        assertEquals("INVENTORY_ADDED", restocked.getOrThrow().status)

        val restockedQuantity = transaction {
            Items.selectAll()
                .where { Items.id eq UUID.fromString(existingItem.id) }
                .first()[Items.quantity]
        }
        assertEquals(5, restockedQuantity)

        val alreadyAdded = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "RESTOCK_EXISTING",
                        existingItemId = existingItem.id
                    )
                )
            )
        )
        assertEquals("Only purchased requests can be added to inventory", alreadyAdded.exceptionOrNull()?.message)

        assertNotNull(token)
    }

    @Test
    fun `requisition service creates new inventory items and preserves unit context`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("new-item-owner")
        val memberEmail = randomEmail("new-item-member")
        val leaderEmail = randomEmail("new-item-leader")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val unitIdText = createUnit(token, tuntasIdText, "Naujas Vienetas")
        val (_, memberIdText) = client.registerInvitedUser(token, tuntasIdText, "Skautas", memberEmail)
        val (_, leaderIdText) = client.registerInvitedUser(
            token,
            tuntasIdText,
            "Draugininkas",
            leaderEmail,
            organizationalUnitId = unitIdText
        )
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val memberId = UUID.fromString(memberIdText)
        val leaderId = UUID.fromString(leaderIdText)
        val unitId = UUID.fromString(unitIdText)
        assignUserToUnit(memberId, unitId, tuntasId)

        val created = requisitionService.createRequest(
            tuntasId = tuntasId,
            createdByUserId = memberId,
            request = CreateRequisitionRequest(
                requestingUnitId = unitIdText,
                items = listOf(CreateRequisitionItemRequest(itemName = "Naujas puodas", quantity = 2))
            )
        ).getOrThrow()
        val requestId = UUID.fromString(created.id)
        val approvedRequest = requisitionService.unitReview(
            requestId = requestId,
            tuntasId = tuntasId,
            reviewerUserId = leaderId,
            request = lt.skautai.models.requests.RequisitionUnitReviewRequest(action = "APPROVED")
        )
        assertTrue(approvedRequest.isSuccess)

        requisitionService.markPurchased(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = RequisitionMarkPurchasedRequest()
        ).getOrThrow()

        val lineId = transaction {
            DraugoveRequisitionItems.selectAll()
                .where { DraugoveRequisitionItems.requisitionId eq requestId }
                .first()[DraugoveRequisitionItems.id]
        }
        val added = requisitionService.addPurchasedItemsToInventory(
            requestId = requestId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = AddRequisitionToInventoryRequest(
                items = listOf(
                    AddRequisitionItemToInventoryRequest(
                        requisitionItemId = lineId.toString(),
                        action = "NEW_ITEM",
                        type = "COLLECTIVE",
                        category = "COOKING",
                        condition = "GOOD",
                        purchaseDate = "2026-08-09",
                        purchasePrice = 22.0,
                        notes = "Sukurta naujai"
                    )
                )
            )
        )
        assertTrue(added.isSuccess)
        assertEquals("INVENTORY_ADDED", added.getOrThrow().status)

        val createdItem = transaction {
            Items.selectAll()
                .where { Items.name eq "Naujas puodas" }
                .firstOrNull()
        }
        assertNotNull(createdItem)
        assertEquals(unitId, createdItem[Items.custodianId])

        val finalized = transaction {
            DraugoveRequisitions.selectAll()
                .where { DraugoveRequisitions.id eq requestId }
                .first()
        }
        assertEquals("INVENTORY_ADDED", finalized[DraugoveRequisitions.status])
        assertNotNull(finalized[DraugoveRequisitions.addedToInventoryAt])
        assertEquals(ownerId, finalized[DraugoveRequisitions.addedToInventoryByUserId])
    }
}
