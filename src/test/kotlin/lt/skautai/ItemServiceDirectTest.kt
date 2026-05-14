package lt.skautai

import io.ktor.server.testing.testApplication
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.getRoleId
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.ItemCustomFieldRequest
import lt.skautai.models.requests.RestockItemRequest
import lt.skautai.models.requests.ReviewItemAdditionRequest
import lt.skautai.plugins.ResolvedPermission
import lt.skautai.services.ItemService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemServiceDirectTest {

    private val service = ItemService()

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

    @Test
    fun `item service validates custom fields duplicate target and responsible user`() = testApplication {
        configureFullApp()
        val ownerEmail = "item-owner@test.com"
        val outsideEmail = "other-tuntas@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val (_, otherTuntasIdText) = client.registerAndActivateTuntininkas(email = outsideEmail, tuntasName = "Kitas Tuntas")
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val outsideUserId = userIdByEmail(outsideEmail)

        val duplicateTargetMissing = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Kirvis",
                type = "COLLECTIVE",
                category = "TOOLS",
                quantity = 2,
                duplicateHandling = "ADD_TO_EXISTING",
                duplicateTargetItemId = UUID.randomUUID().toString()
            )
        )
        assertEquals("Duplicate target item not found", duplicateTargetMissing.exceptionOrNull()?.message)

        val duplicateCustomField = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Puodas",
                type = "COLLECTIVE",
                category = "COOKING",
                quantity = 1,
                customFields = listOf(
                    ItemCustomFieldRequest(fieldName = "Spalva", fieldValue = "Raudona"),
                    ItemCustomFieldRequest(fieldName = " spalva ", fieldValue = "Melyna")
                )
            )
        )
        assertEquals("Duplicate custom field name", duplicateCustomField.exceptionOrNull()?.message)

        val invalidPurchaseDate = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Kibirai",
                type = "COLLECTIVE",
                category = "CAMPING",
                quantity = 1,
                purchaseDate = "2026/01/01"
            )
        )
        assertEquals("Invalid purchase date format, use YYYY-MM-DD", invalidPurchaseDate.exceptionOrNull()?.message)

        val responsibleOutsideTuntas = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Virve",
                type = "COLLECTIVE",
                category = "TOOLS",
                quantity = 1,
                responsibleUserId = outsideUserId.toString()
            )
        )
        assertEquals("Responsible user must belong to this tuntas", responsibleOutsideTuntas.exceptionOrNull()?.message)

        assertNotNull(otherTuntasIdText)
        assertNotNull(token)
    }

    @Test
    fun `item service restock updates quantity and validates input`() = testApplication {
        configureFullApp()
        val ownerEmail = "restock-owner@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)

        val created = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Zibintas",
                type = "COLLECTIVE",
                category = "LIGHTING",
                quantity = 2
            )
        ).getOrThrow()
        val itemId = UUID.fromString(created.id)

        val invalidQuantity = service.restockItem(
            itemId = itemId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = RestockItemRequest(quantity = 0)
        )
        assertEquals("Quantity must be at least 1", invalidQuantity.exceptionOrNull()?.message)

        val invalidDate = service.restockItem(
            itemId = itemId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = RestockItemRequest(quantity = 1, purchaseDate = "2026/01/40")
        )
        assertEquals("Invalid purchase date format, use YYYY-MM-DD", invalidDate.exceptionOrNull()?.message)

        val success = service.restockItem(
            itemId = itemId,
            tuntasId = tuntasId,
            userId = ownerId,
            request = RestockItemRequest(
                quantity = 3,
                purchaseDate = "2026-05-01",
                purchasePrice = 12.5,
                notes = "Papildytas kiekis"
            )
        )
        assertTrue(success.isSuccess)
        val updated = success.getOrThrow()
        assertEquals(5, updated.quantity)
        assertEquals("2026-05-01", updated.purchaseDate)
        assertEquals(12.5, updated.purchasePrice)
        assertEquals("Papildytas kiekis", updated.notes)

        val missingItem = service.restockItem(
            itemId = UUID.randomUUID(),
            tuntasId = tuntasId,
            userId = ownerId,
            request = RestockItemRequest(quantity = 1)
        )
        assertEquals("Item not found", missingItem.exceptionOrNull()?.message)

        assertNotNull(token)
    }

    @Test
    fun `item service delete blocks reserved items and inactive items`() = testApplication {
        configureFullApp()
        val ownerEmail = "delete-owner@test.com"
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)

        val item = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Palapine",
                type = "COLLECTIVE",
                category = "CAMPING",
                quantity = 1
            )
        ).getOrThrow()
        val itemId = UUID.fromString(item.id)

        transaction {
            Reservations.insert {
                it[groupId] = UUID.randomUUID()
                it[title] = "Aktyvi rezervacija"
                it[this.itemId] = itemId
                it[this.tuntasId] = tuntasId
                it[reservedByUserId] = ownerId
                it[quantity] = 1
                it[startDate] = LocalDate.parse("2026-06-01")
                it[endDate] = LocalDate.parse("2026-06-03")
                it[status] = "APPROVED"
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
            }
        }

        val blockedByReservation = service.deleteItem(itemId, tuntasId, ownerId)
        assertEquals(
            "Item cannot be deactivated while it has active reservations",
            blockedByReservation.exceptionOrNull()?.message
        )

        transaction {
            Reservations.deleteWhere { Reservations.itemId eq itemId }
        }

        val deleted = service.deleteItem(itemId, tuntasId, ownerId)
        assertTrue(deleted.isSuccess)

        val alreadyInactive = service.deleteItem(itemId, tuntasId, ownerId)
        assertEquals("Item is already inactive", alreadyInactive.exceptionOrNull()?.message)

        assertNotNull(token)
    }

    @Test
    fun `item service review handles permission checks and approval flows`() = testApplication {
        configureFullApp()
        val ownerEmail = "review-owner@test.com"
        val reviewerEmail = randomEmail("reviewer")
        val (token, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        val unitId = createUnit(token, tuntasIdText, "Skautai 1")
        val (_, reviewerIdText) = client.registerInvitedUser(token, tuntasIdText, "Draugininkas", reviewerEmail, unitId)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdByEmail(ownerEmail)
        val reviewerId = UUID.fromString(reviewerIdText)

        val sharedPending = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Bendras puodas",
                type = "COLLECTIVE",
                category = "COOKING",
                quantity = 1
            ),
            isPendingApproval = true
        ).getOrThrow()
        val sharedPendingId = UUID.fromString(sharedPending.id)

        val unitPending = service.createItem(
            tuntasId = tuntasId,
            createdByUserId = ownerId,
            request = CreateItemRequest(
                name = "Vieneto kirvis",
                type = "COLLECTIVE",
                category = "TOOLS",
                custodianId = unitId,
                quantity = 1
            ),
            isPendingApproval = true
        ).getOrThrow()
        val unitPendingId = UUID.fromString(unitPending.id)

        val invalidDecision = service.reviewItemAddition(
            itemId = sharedPendingId,
            tuntasId = tuntasId,
            reviewerUserId = reviewerId,
            request = ReviewItemAdditionRequest(decision = "MAYBE"),
            reviewerPermissions = emptyList()
        )
        assertEquals("Decision must be APPROVED or REJECTED", invalidDecision.exceptionOrNull()?.message)

        val rejectWithoutReason = service.reviewItemAddition(
            itemId = sharedPendingId,
            tuntasId = tuntasId,
            reviewerUserId = reviewerId,
            request = ReviewItemAdditionRequest(decision = "REJECTED"),
            reviewerPermissions = emptyList()
        )
        assertEquals("Rejection reason is required", rejectWithoutReason.exceptionOrNull()?.message)

        val insufficientShared = service.reviewItemAddition(
            itemId = sharedPendingId,
            tuntasId = tuntasId,
            reviewerUserId = reviewerId,
            request = ReviewItemAdditionRequest(decision = "APPROVED"),
            reviewerPermissions = listOf(
                ResolvedPermission("items.review", "OWN_UNIT", setOf(UUID.fromString(unitId)))
            )
        )
        assertEquals("Insufficient permissions to review this item", insufficientShared.exceptionOrNull()?.message)

        val approvedUnit = service.reviewItemAddition(
            itemId = unitPendingId,
            tuntasId = tuntasId,
            reviewerUserId = reviewerId,
            request = ReviewItemAdditionRequest(decision = "APPROVED"),
            reviewerPermissions = listOf(
                ResolvedPermission("items.review", "OWN_UNIT", setOf(UUID.fromString(unitId)))
            )
        )
        assertTrue(approvedUnit.isSuccess)
        assertEquals("ACTIVE", approvedUnit.getOrThrow().status)

        val approvedShared = service.reviewItemAddition(
            itemId = sharedPendingId,
            tuntasId = tuntasId,
            reviewerUserId = reviewerId,
            request = ReviewItemAdditionRequest(decision = "APPROVED"),
            reviewerPermissions = listOf(
                ResolvedPermission("items.review", "ALL", emptySet())
            )
        )
        assertTrue(approvedShared.isSuccess)
        assertEquals("ACTIVE", approvedShared.getOrThrow().status)

        val notPending = service.reviewItemAddition(
            itemId = sharedPendingId,
            tuntasId = tuntasId,
            reviewerUserId = reviewerId,
            request = ReviewItemAdditionRequest(decision = "APPROVED"),
            reviewerPermissions = listOf(
                ResolvedPermission("items.review", "ALL", emptySet())
            )
        )
        assertEquals("Item is not pending approval", notPending.exceptionOrNull()?.message)

        val roleId = getRoleId(tuntasIdText, "Draugininkas")
        assertNotNull(roleId)
    }
}
