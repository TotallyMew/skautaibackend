package lt.skautai

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import lt.skautai.TestHelper.configureFullApp
import lt.skautai.TestHelper.createUnit
import lt.skautai.TestHelper.randomEmail
import lt.skautai.TestHelper.registerAndActivateTuntininkas
import lt.skautai.TestHelper.registerInvitedUser
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateReservationItemRequest
import lt.skautai.models.requests.CreateReservationRequest
import lt.skautai.models.requests.ReservationMovementItemRequest
import lt.skautai.models.requests.ReservationMovementRequest
import lt.skautai.models.requests.ReviewReservationRequest
import lt.skautai.models.requests.UpdateReservationPickupRequest
import lt.skautai.models.requests.UpdateReservationReturnTimeRequest
import lt.skautai.models.requests.UpdateReservationStatusRequest
import lt.skautai.services.ReservationService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
class ReservationServiceDirectTest {

    private val service = ReservationService()

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

    private fun userIdForEmail(email: String): UUID = transaction {
        Users.selectAll()
            .where { Users.email eq email }
            .first()[Users.id]
    }

    private suspend fun HttpClient.createItem(
        token: String,
        tuntasId: String,
        name: String,
        quantity: Int = 2,
        custodianId: String? = null
    ): String {
        val custodianField = custodianId?.let { """, "custodianId": "$it"""" }.orEmpty()
        val response = post("/api/items") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody(
                """{ "name": "$name", "type": "COLLECTIVE", "category": "CAMPING", "quantity": $quantity$custodianField }"""
            )
        }
        check(response.status == HttpStatusCode.Created) {
            "Failed to create item: ${response.status} ${response.bodyAsText()}"
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.createLocation(
        token: String,
        tuntasId: String,
        name: String,
        visibility: String = "PUBLIC",
        ownerUnitId: String? = null
    ): String {
        val ownerUnitField = ownerUnitId?.let { """, "ownerUnitId": "$it"""" }.orEmpty()
        val response = post("/api/locations") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $token")
            header("X-Tuntas-Id", tuntasId)
            setBody("""{ "name": "$name", "visibility": "$visibility"$ownerUnitField }""")
        }
        check(response.status == HttpStatusCode.Created) {
            "Failed to create location: ${response.status} ${response.bodyAsText()}"
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    }

    @Test
    fun `reservation service validates unit scope and reservation locations directly`() = testApplication {
        configureFullApp()
        val leaderEmail = randomEmail("reservation-leader")
        val memberEmail = randomEmail("reservation-member")
        val otherEmail = randomEmail("reservation-other")
        val (leaderToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = leaderEmail)
        val unitA = createUnit(leaderToken, tuntasIdText, "Unit A")
        val unitB = createUnit(leaderToken, tuntasIdText, "Unit B")
        val (_, memberIdText) = client.registerInvitedUser(leaderToken, tuntasIdText, "Skautas", memberEmail, unitA)
        client.registerInvitedUser(leaderToken, tuntasIdText, "Skautas", otherEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val memberId = UUID.fromString(memberIdText)

        val itemA = client.createItem(leaderToken, tuntasIdText, "Tent A", custodianId = unitA)
        val itemB = client.createItem(leaderToken, tuntasIdText, "Tent B", custodianId = unitB)
        val unitBLocation = client.createLocation(leaderToken, tuntasIdText, "Unit B Shelf", "UNIT", unitB)
        val otherPrivateLocation = client.createLocation(leaderToken, tuntasIdText, "Other Locker", "PRIVATE")

        val mixedUnits = service.createReservation(
            tuntasId = tuntasId,
            reservedByUserId = memberId,
            request = CreateReservationRequest(
                title = "Mixed",
                startDate = "2026-07-01",
                endDate = "2026-07-03",
                items = listOf(
                    CreateReservationItemRequest(itemA, 1),
                    CreateReservationItemRequest(itemB, 1)
                )
            ),
            userUnitIds = setOf(UUID.fromString(unitA))
        )
        assertEquals("Reservation can include items from only one unit inventory", mixedUnits.exceptionOrNull()?.message)

        val wrongRequestingUnit = service.createReservation(
            tuntasId = tuntasId,
            reservedByUserId = memberId,
            request = CreateReservationRequest(
                title = "Wrong unit",
                startDate = "2026-07-01",
                endDate = "2026-07-03",
                items = listOf(CreateReservationItemRequest(itemA, 1)),
                requestingUnitId = unitB
            ),
            canApproveTopLevel = true,
            userUnitIds = setOf(UUID.fromString(unitA))
        )
        assertEquals("Requesting unit must match selected unit inventory", wrongRequestingUnit.exceptionOrNull()?.message)

        val wrongUnitLocation = service.createReservation(
            tuntasId = tuntasId,
            reservedByUserId = memberId,
            request = CreateReservationRequest(
                title = "Wrong pickup",
                startDate = "2026-07-01",
                endDate = "2026-07-03",
                items = listOf(CreateReservationItemRequest(itemA, 1)),
                pickupLocationId = unitBLocation
            ),
            userUnitIds = setOf(UUID.fromString(unitA))
        )
        assertEquals("Selected location does not match reserved unit inventory", wrongUnitLocation.exceptionOrNull()?.message)

        val foreignPrivate = service.createReservation(
            tuntasId = tuntasId,
            reservedByUserId = memberId,
            request = CreateReservationRequest(
                title = "Wrong return",
                startDate = "2026-07-01",
                endDate = "2026-07-03",
                items = listOf(CreateReservationItemRequest(itemA, 1)),
                returnLocationId = otherPrivateLocation
            ),
            userUnitIds = setOf(UUID.fromString(unitA))
        )
        assertEquals(
            "Private reservation locations can only belong to reservation owner",
            foreignPrivate.exceptionOrNull()?.message
        )
    }

    @Test
    fun `reservation service reviews and status transitions validate directly`() = testApplication {
        configureFullApp()
        val leaderEmail = randomEmail("reservation-review-leader")
        val memberEmail = randomEmail("reservation-review-member")
        val (leaderToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = leaderEmail)
        val (_, memberIdText) = client.registerInvitedUser(leaderToken, tuntasIdText, "Skautas", memberEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val leaderId = userIdForEmail(leaderEmail)
        val memberId = UUID.fromString(memberIdText)
        val sharedItemId = client.createItem(leaderToken, tuntasIdText, "Shared Stove")

        val created = service.createReservation(
            tuntasId = tuntasId,
            reservedByUserId = memberId,
            request = CreateReservationRequest(
                title = "Shared request",
                startDate = "2026-08-01",
                endDate = "2026-08-02",
                items = listOf(CreateReservationItemRequest(sharedItemId, 1))
            ),
            canApproveTopLevel = false
        ).getOrThrow()
        val groupId = UUID.fromString(created.id)
        assertEquals("PENDING", created.status)
        assertEquals("NOT_REQUIRED", created.unitReviewStatus)
        assertEquals("PENDING", created.topLevelReviewStatus)

        val noUnitReview = service.reviewReservation(
            groupId = groupId,
            tuntasId = tuntasId,
            reviewerUserId = leaderId,
            level = "unit",
            request = ReviewReservationRequest(status = "APPROVED"),
            canApproveTopLevel = true,
            approvableUnitIds = emptySet()
        )
        assertEquals("Unit review is not pending", noUnitReview.exceptionOrNull()?.message)

        val selfReview = service.reviewReservation(
            groupId = groupId,
            tuntasId = tuntasId,
            reviewerUserId = memberId,
            level = "top-level",
            request = ReviewReservationRequest(status = "APPROVED"),
            canApproveTopLevel = false,
            approvableUnitIds = emptySet()
        )
        assertEquals("You cannot review your own reservation", selfReview.exceptionOrNull()?.message)

        val approved = service.reviewReservation(
            groupId = groupId,
            tuntasId = tuntasId,
            reviewerUserId = leaderId,
            level = "top-level",
            request = ReviewReservationRequest(status = "APPROVED", notes = "Looks good"),
            canApproveTopLevel = true,
            approvableUnitIds = emptySet()
        )
        assertTrue(approved.isSuccess)
        assertEquals("APPROVED", approved.getOrThrow().status)

        val invalidStatus = service.updateReservationStatus(
            groupId = groupId,
            tuntasId = tuntasId,
            approvedByUserId = leaderId,
            request = UpdateReservationStatusRequest(status = "BROKEN")
        )
        assertEquals(
            "Invalid status. Must be one of: PENDING, APPROVED, ACTIVE, RETURNED, CANCELLED, REJECTED",
            invalidStatus.exceptionOrNull()?.message
        )

        val invalidTransition = service.updateReservationStatus(
            groupId = groupId,
            tuntasId = tuntasId,
            approvedByUserId = leaderId,
            request = UpdateReservationStatusRequest(status = "RETURNED")
        )
        assertEquals("Cannot transition from APPROVED to RETURNED", invalidTransition.exceptionOrNull()?.message)
    }

    @Test
    fun `reservation service movement and proposal flows validate directly`() = testApplication {
        configureFullApp()
        val ownerEmail = randomEmail("reservation-owner")
        val managerEmail = randomEmail("reservation-manager")
        val memberEmail = randomEmail("reservation-other-member")
        val (ownerToken, tuntasIdText) = client.registerAndActivateTuntininkas(email = ownerEmail)
        client.registerInvitedUser(ownerToken, tuntasIdText, "Inventorininkas", managerEmail)
        client.registerInvitedUser(ownerToken, tuntasIdText, "Skautas", memberEmail)
        val tuntasId = UUID.fromString(tuntasIdText)
        val ownerId = userIdForEmail(ownerEmail)
        val managerId = userIdForEmail(managerEmail)
        val otherMemberId = userIdForEmail(memberEmail)
        val itemId = client.createItem(ownerToken, tuntasIdText, "Axe", quantity = 1)
        val publicLocationId = client.createLocation(ownerToken, tuntasIdText, "Front Desk")

        val created = service.createReservation(
            tuntasId = tuntasId,
            reservedByUserId = ownerId,
            request = CreateReservationRequest(
                title = "Approved reservation",
                startDate = "2026-09-01",
                endDate = "2026-09-02",
                items = listOf(CreateReservationItemRequest(itemId, 1))
            ),
            canApproveTopLevel = true
        ).getOrThrow()
        val groupId = UUID.fromString(created.id)
        assertEquals("PENDING", created.status)
        val approved = service.reviewReservation(
            groupId = groupId,
            tuntasId = tuntasId,
            reviewerUserId = managerId,
            level = "top-level",
            request = ReviewReservationRequest(status = "APPROVED"),
            canApproveTopLevel = true,
            approvableUnitIds = emptySet()
        ).getOrThrow()
        assertEquals("APPROVED", approved.status)

        val noPendingPickup = service.updatePickupTime(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = ownerId,
            canManageTopLevel = true,
            approvableUnitIds = emptySet(),
            request = UpdateReservationPickupRequest(response = "ACCEPT")
        )
        assertEquals("No pending pickup proposal", noPendingPickup.exceptionOrNull()?.message)

        val proposedPickup = service.updatePickupTime(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = ownerId,
            canManageTopLevel = true,
            approvableUnitIds = emptySet(),
            request = UpdateReservationPickupRequest(
                pickupAt = "2026-09-01T10:00:00Z",
                pickupLocationId = publicLocationId
            )
        )
        assertTrue(proposedPickup.isSuccess)
        assertEquals("PENDING", proposedPickup.getOrThrow().pickupProposalStatus)

        val acceptOwnPickup = service.updatePickupTime(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = ownerId,
            canManageTopLevel = true,
            approvableUnitIds = emptySet(),
            request = UpdateReservationPickupRequest(response = "ACCEPT")
        )
        assertEquals("You cannot accept your own pickup proposal", acceptOwnPickup.exceptionOrNull()?.message)

        val acceptedPickup = service.updatePickupTime(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = managerId,
            canManageTopLevel = true,
            approvableUnitIds = emptySet(),
            request = UpdateReservationPickupRequest(response = "ACCEPT")
        )
        assertTrue(acceptedPickup.isSuccess)
        assertEquals("ACCEPTED", acceptedPickup.getOrThrow().pickupProposalStatus)

        val invalidReturnBeforeIssue = service.recordMovement(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = managerId,
            type = "RETURN",
            request = ReservationMovementRequest(
                items = listOf(ReservationMovementItemRequest(itemId, 1))
            ),
            canApproveTopLevel = true,
            approvableUnitIds = emptySet()
        )
        assertEquals("Only active reservations can be returned", invalidReturnBeforeIssue.exceptionOrNull()?.message)

        val issued = service.recordMovement(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = managerId,
            type = "ISSUE",
            request = ReservationMovementRequest(
                items = listOf(ReservationMovementItemRequest(itemId, 1)),
                locationId = publicLocationId
            ),
            canApproveTopLevel = true,
            approvableUnitIds = emptySet()
        )
        assertTrue(issued.isSuccess)
        assertEquals("ACTIVE", issued.getOrThrow().status)

        val otherCannotMarkReturned = service.recordMovement(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = otherMemberId,
            type = "RETURN_MARKED",
            request = ReservationMovementRequest(
                items = listOf(ReservationMovementItemRequest(itemId, 1))
            ),
            canApproveTopLevel = false,
            approvableUnitIds = emptySet()
        )
        assertEquals("Only reservation owner can mark items as returned", otherCannotMarkReturned.exceptionOrNull()?.message)

        val invalidReturnResponse = service.updateReturnTime(
            groupId = groupId,
            tuntasId = tuntasId,
            userId = ownerId,
            canManageTopLevel = true,
            approvableUnitIds = emptySet(),
            request = UpdateReservationReturnTimeRequest(response = "MAYBE")
        )
        assertEquals("Invalid return response", invalidReturnResponse.exceptionOrNull()?.message)

        val cancelActive = service.cancelReservation(groupId, tuntasId, ownerId)
        assertEquals("Only PENDING or APPROVED reservations can be cancelled", cancelActive.exceptionOrNull()?.message)
    }
}
