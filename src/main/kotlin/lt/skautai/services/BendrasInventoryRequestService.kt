package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.CreateBendrasInventoryRequestRequest
import lt.skautai.models.requests.DraugininkasReviewRequest
import lt.skautai.models.requests.TopLevelReviewRequest
import lt.skautai.models.responses.BendrasInventoryRequestListResponse
import lt.skautai.models.responses.BendrasInventoryRequestResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class BendrasInventoryRequestService {

    // All leadership role names that can act as unit-level leaders
    // for draugininkas-review purposes
    private val unitLeaderRoles = listOf(
        "Draugininkas",
        "Draugininko pavaduotojas",
        "Gildijos pirmininkas",
        "Gildijos pirmininko pavaduotojas",
        "Vyr. skautu draugoves draugininkas",
        "Vyr. skautu draugoves draugininko pavaduotojas",
        "Vyr. skautu burelio pirmininkas",
        "Vyr. skautu burelio pirmininko pavaduotojas",
        "Vyr. skauciu draugoves draugininkas",
        "Vyr. skauciu draugoves draugininko pavaduotojas",
        "Vyr. skauciu burelio pirmininkas",
        "Vyr. skauciu burelio pirmininko pavaduotojas"
    )

    fun getAllRequests(tuntasId: UUID): Result<BendrasInventoryRequestListResponse> {
        return transaction {
            val requests = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.tuntasId eq tuntasId }
                .map { toResponse(it) }
            Result.success(BendrasInventoryRequestListResponse(requests = requests, total = requests.size))
        }
    }

    fun getRequest(requestId: UUID, tuntasId: UUID): Result<BendrasInventoryRequestResponse> {
        return transaction {
            val request = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                            (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))
            Result.success(toResponse(request))
        }
    }

    fun createRequest(
        tuntasId: UUID,
        requestedByUserId: UUID,
        request: CreateBendrasInventoryRequestRequest
    ): Result<BendrasInventoryRequestResponse> {
        return transaction {
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val itemUUID = try { UUID.fromString(request.itemId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid item ID"))
            }

            val startDate = try {
                kotlinx.datetime.LocalDate.parse(request.startDate)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid start date format, use YYYY-MM-DD"))
            }

            val endDate = try {
                kotlinx.datetime.LocalDate.parse(request.endDate)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid end date format, use YYYY-MM-DD"))
            }

            if (endDate < startDate) {
                return@transaction Result.failure(Exception("End date cannot be before start date"))
            }

            val item = Items.selectAll()
                .where {
                    (Items.id eq itemUUID) and
                            (Items.tuntasId eq tuntasId) and
                            (Items.status eq "ACTIVE")
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found or not active"))

            if (request.quantity > item[Items.quantity]) {
                return@transaction Result.failure(Exception("Requested quantity exceeds available quantity"))
            }

            val eventUUID = request.eventId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid event ID"))
                }
            }

            val requestingUnitUUID = request.requestingUnitId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid requesting unit ID"))
                }
            }

            // Validate requesting unit belongs to tuntas if provided
            if (requestingUnitUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq requestingUnitUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Requesting unit not found in this tuntas"))
            }

            // Determine needs_draugininkas_approval
            // Check if requester holds any unit-level leadership role
            val isUnitLeader = UserLeadershipRoles
                .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq requestedByUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.leftAt.isNull()) and
                            (Roles.name inList unitLeaderRoles)
                }
                .any()

            // Check requester's rank
            val requesterRank = UserRanks
                .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserRanks.userId eq requestedByUserId) and
                            (UserRanks.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?.get(Roles.name)

            val needsApproval = when {
                // Vilkas, Skautas, Patyres skautas always need unit leader approval
                requesterRank in listOf("Vilkas", "Skautas", "Patyres skautas") -> true
                // Unit leaders never need approval
                isUnitLeader -> false
                // Vadovas/Vyr. skautas with no requesting unit — goes straight to top level
                requestingUnitUUID == null -> false
                // Otherwise use their choice
                else -> request.needsDraugininkasApproval ?: false
            }

            val requestId = BendrasInventoryRequests.insert {
                it[this.tuntasId] = tuntasId
                it[this.requestedByUserId] = requestedByUserId
                it[this.itemId] = itemUUID
                it[quantity] = request.quantity
                it[this.eventId] = eventUUID
                it[this.requestingUnitId] = requestingUnitUUID
                it[needsDraugininkasApproval] = needsApproval
                it[draugininkasStatus] = if (needsApproval) "PENDING" else null
                it[topLevelStatus] = "PENDING"
                it[this.startDate] = startDate
                it[this.endDate] = endDate
                it[notes] = request.notes
            } get BendrasInventoryRequests.id

            val inserted = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.id eq requestId }
                .first()

            Result.success(toResponse(inserted))
        }
    }

    fun cancelRequest(
        requestId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<Unit> {
        return transaction {
            val existing = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                            (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[BendrasInventoryRequests.requestedByUserId] != requestingUserId) {
                return@transaction Result.failure(Exception("You can only cancel your own requests"))
            }

            val topStatus = existing[BendrasInventoryRequests.topLevelStatus]
            val draugininkasStatus = existing[BendrasInventoryRequests.draugininkasStatus]

            val cancellable = topStatus == "PENDING" &&
                    (draugininkasStatus == null || draugininkasStatus == "PENDING")

            if (!cancellable) {
                return@transaction Result.failure(Exception("Request cannot be cancelled in its current state"))
            }

            BendrasInventoryRequests.update({
                (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
            }) {
                it[BendrasInventoryRequests.topLevelStatus] = "REJECTED"
                it[topLevelRejectionReason] = "Cancelled by requester"
            }

            Result.success(Unit)
        }
    }

    fun draugininkasReview(
        requestId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: DraugininkasReviewRequest
    ): Result<BendrasInventoryRequestResponse> {
        return transaction {
            if (request.action !in listOf("FORWARDED", "REJECTED")) {
                return@transaction Result.failure(Exception("Action must be FORWARDED or REJECTED"))
            }

            val existing = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                            (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (!existing[BendrasInventoryRequests.needsDraugininkasApproval]) {
                return@transaction Result.failure(Exception("This request does not require unit leader approval"))
            }

            if (existing[BendrasInventoryRequests.draugininkasStatus] != "PENDING") {
                return@transaction Result.failure(Exception("Request is not pending unit leader review"))
            }

            val requestingUnitId = existing[BendrasInventoryRequests.requestingUnitId]
                ?: return@transaction Result.failure(Exception("Request has no unit assigned"))

            // Verify reviewer is a unit leader of the requesting unit
            val isReviewerUnitLeader = UserLeadershipRoles
                .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq reviewerUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.leftAt.isNull()) and
                            (UserLeadershipRoles.organizationalUnitId eq requestingUnitId) and
                            (Roles.name inList unitLeaderRoles)
                }
                .any()

            if (!isReviewerUnitLeader) {
                return@transaction Result.failure(Exception("You are not a unit leader of this request's unit"))
            }

            BendrasInventoryRequests.update({
                (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
            }) {
                it[draugininkasStatus] = request.action
                it[draugininkasReviewedByUserId] = reviewerUserId
                it[draugininkasRejectionReason] = request.rejectionReason
                if (request.action == "REJECTED") {
                    it[topLevelStatus] = "REJECTED"
                    it[topLevelRejectionReason] = "Rejected by unit leader"
                }
            }

            val updated = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.id eq requestId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    fun topLevelReview(
        requestId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: TopLevelReviewRequest
    ): Result<BendrasInventoryRequestResponse> {
        return transaction {
            if (request.action !in listOf("APPROVED", "REJECTED")) {
                return@transaction Result.failure(Exception("Action must be APPROVED or REJECTED"))
            }

            val existing = BendrasInventoryRequests.selectAll()
                .where {
                    (BendrasInventoryRequests.id eq requestId) and
                            (BendrasInventoryRequests.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[BendrasInventoryRequests.topLevelStatus] != "PENDING") {
                return@transaction Result.failure(Exception("Request is not pending top level review"))
            }

            if (existing[BendrasInventoryRequests.needsDraugininkasApproval] &&
                existing[BendrasInventoryRequests.draugininkasStatus] != "FORWARDED"
            ) {
                return@transaction Result.failure(Exception("Request must be forwarded by unit leader first"))
            }

            BendrasInventoryRequests.update({
                (BendrasInventoryRequests.id eq requestId) and
                        (BendrasInventoryRequests.tuntasId eq tuntasId)
            }) {
                it[topLevelStatus] = request.action
                it[topLevelReviewedByUserId] = reviewerUserId
                it[topLevelRejectionReason] = request.rejectionReason
            }

            // Auto-create reservation on approval
            if (request.action == "APPROVED") {
                val itemId = existing[BendrasInventoryRequests.itemId]
                val startDate = existing[BendrasInventoryRequests.startDate]
                val endDate = existing[BendrasInventoryRequests.endDate]
                val quantity = existing[BendrasInventoryRequests.quantity]
                val eventId = existing[BendrasInventoryRequests.eventId]
                val requestingUnitId = existing[BendrasInventoryRequests.requestingUnitId]
                val requesterUserId = existing[BendrasInventoryRequests.requestedByUserId]

                val item = Items.selectAll()
                    .where { Items.id eq itemId }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Item not found"))

                val conflictingQuantity = Reservations
                    .select(Reservations.quantity)
                    .where {
                        (Reservations.itemId eq itemId) and
                                (Reservations.status inList listOf("APPROVED", "ACTIVE")) and
                                (Reservations.startDate lessEq endDate) and
                                (Reservations.endDate greaterEq startDate)
                    }
                    .sumOf { it[Reservations.quantity] }

                val availableQuantity = item[Items.quantity] - conflictingQuantity
                if (quantity > availableQuantity) {
                    return@transaction Result.failure(
                        Exception("Insufficient available quantity. Available: $availableQuantity, requested: $quantity")
                    )
                }

                Reservations.insert {
                    it[Reservations.itemId] = itemId
                    it[Reservations.tuntasId] = tuntasId
                    it[reservedByUserId] = requesterUserId
                    it[approvedByUserId] = reviewerUserId
                    it[Reservations.eventId] = eventId
                    it[Reservations.requestingUnitId] = requestingUnitId
                    it[Reservations.quantity] = quantity
                    it[Reservations.startDate] = startDate
                    it[Reservations.endDate] = endDate
                    it[status] = "APPROVED"
                    it[notes] = "Auto-created from bendras inventory request $requestId"
                }
            }

            val updated = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.id eq requestId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    private fun toResponse(row: ResultRow): BendrasInventoryRequestResponse {
        val itemId = row[BendrasInventoryRequests.itemId]
        val itemName = Items.selectAll()
            .where { Items.id eq itemId }
            .firstOrNull()?.get(Items.name) ?: "Unknown"

        val requestingUnitId = row[BendrasInventoryRequests.requestingUnitId]
        val requestingUnitName = requestingUnitId?.let {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq it }
                .firstOrNull()?.get(OrganizationalUnits.name)
        }

        return BendrasInventoryRequestResponse(
            id = row[BendrasInventoryRequests.id].toString(),
            tuntasId = row[BendrasInventoryRequests.tuntasId].toString(),
            requestedByUserId = row[BendrasInventoryRequests.requestedByUserId].toString(),
            itemId = itemId.toString(),
            itemName = itemName,
            quantity = row[BendrasInventoryRequests.quantity],
            eventId = row[BendrasInventoryRequests.eventId]?.toString(),
            requestingUnitId = requestingUnitId?.toString(),
            requestingUnitName = requestingUnitName,
            needsDraugininkasApproval = row[BendrasInventoryRequests.needsDraugininkasApproval],
            draugininkasStatus = row[BendrasInventoryRequests.draugininkasStatus],
            draugininkasReviewedByUserId = row[BendrasInventoryRequests.draugininkasReviewedByUserId]?.toString(),
            draugininkasRejectionReason = row[BendrasInventoryRequests.draugininkasRejectionReason],
            topLevelStatus = row[BendrasInventoryRequests.topLevelStatus],
            topLevelReviewedByUserId = row[BendrasInventoryRequests.topLevelReviewedByUserId]?.toString(),
            topLevelRejectionReason = row[BendrasInventoryRequests.topLevelRejectionReason],
            startDate = row[BendrasInventoryRequests.startDate].toString(),
            endDate = row[BendrasInventoryRequests.endDate].toString(),
            notes = row[BendrasInventoryRequests.notes],
            createdAt = row[BendrasInventoryRequests.createdAt].toString(),
            updatedAt = row[BendrasInventoryRequests.updatedAt].toString()
        )
    }
}