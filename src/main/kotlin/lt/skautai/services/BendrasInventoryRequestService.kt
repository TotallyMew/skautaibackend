package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.CreateBendrasInventoryRequestRequest
import lt.skautai.models.requests.DraugininkasReviewRequest
import lt.skautai.models.requests.TopLevelReviewRequest
import lt.skautai.models.responses.BendrasInventoryRequestListResponse
import lt.skautai.models.responses.BendrasInventoryRequestResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class BendrasInventoryRequestService {

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

            val draugoveUUID = request.draugoveId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid draugove ID"))
                }
            }

            // Validate draugove belongs to tuntas if provided
            if (draugoveUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq draugoveUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId) and
                                (OrganizationalUnits.type eq "DRAUGOVE")
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Draugove not found in this tuntas"))
            }

            // Determine needs_draugininkas_approval
            // Check if requester has Draugininkas or Draugininko pavaduotojas role
            val isDraugininkas = UserLeadershipRoles
                .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq requestedByUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (Roles.name inList listOf("Draugininkas", "Draugininko pavaduotojas"))
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
                // Skautas and Patyres skautas always need draugininkas approval
                requesterRank in listOf("Skautas", "Patyres skautas") -> true
                // Draugininkas level never needs approval
                isDraugininkas -> false
                // Above Patyres skautas with no draugove — goes straight to top level
                draugoveUUID == null -> false
                // Above Patyres skautas with draugove — use their choice
                else -> request.needsDraugininkasApproval ?: false
            }

            val requestId = BendrasInventoryRequests.insert {
                it[this.tuntasId] = tuntasId
                it[this.requestedByUserId] = requestedByUserId
                it[this.itemId] = itemUUID
                it[quantity] = request.quantity
                it[this.eventId] = eventUUID
                it[this.draugoveId] = draugoveUUID
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
                return@transaction Result.failure(Exception("This request does not require draugininkas approval"))
            }

            if (existing[BendrasInventoryRequests.draugininkasStatus] != "PENDING") {
                return@transaction Result.failure(Exception("Request is not pending draugininkas review"))
            }

            // Verify reviewer is Draugininkas or Draugininko pavaduotojas
            // of the requester's draugove
            val draugoveId = existing[BendrasInventoryRequests.draugoveId]
                ?: return@transaction Result.failure(Exception("Request has no draugove assigned"))

            val isReviewerDraugininkas = UserLeadershipRoles
                .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq reviewerUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.organizationalUnitId eq draugoveId) and
                            (Roles.name inList listOf("Draugininkas", "Draugininko pavaduotojas"))
                }
                .any()

            if (!isReviewerDraugininkas) {
                return@transaction Result.failure(Exception("You are not the Draugininkas of this request's draugove"))
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
                    it[topLevelRejectionReason] = "Rejected by Draugininkas"
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

            // If needs draugininkas approval, draugininkas must have forwarded first
            if (existing[BendrasInventoryRequests.needsDraugininkasApproval] &&
                existing[BendrasInventoryRequests.draugininkasStatus] != "FORWARDED"
            ) {
                return@transaction Result.failure(Exception("Request must be forwarded by Draugininkas first"))
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
                val requesterUserId = existing[BendrasInventoryRequests.requestedByUserId]

                // Conflict detection
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

        val draugoveId = row[BendrasInventoryRequests.draugoveId]
        val draugoveName = draugoveId?.let {
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
            draugoveId = draugoveId?.toString(),
            draugoveName = draugoveName,
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