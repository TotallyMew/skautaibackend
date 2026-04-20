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

    fun getAllRequests(
        tuntasId: UUID,
        userId: UUID,
        isAdmin: Boolean,
        unitIds: List<UUID>
    ): Result<BendrasInventoryRequestListResponse> {
        return transaction {
            var query = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.tuntasId eq tuntasId }

            when {
                isAdmin -> {}
                unitIds.isNotEmpty() -> query = query.andWhere {
                    (BendrasInventoryRequests.requestingUnitId inList unitIds) or
                            (BendrasInventoryRequests.requestedByUserId eq userId)
                }
                else -> query = query.andWhere {
                    BendrasInventoryRequests.requestedByUserId eq userId
                }
            }

            val requests = query.map { toResponse(it) }
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

            if (request.itemId == null && request.itemDescription.isNullOrBlank()) {
                return@transaction Result.failure(Exception("Either itemId or itemDescription is required"))
            }

            val itemUUID = request.itemId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid item ID"))
                }
            }

            val neededByDate = request.neededByDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid neededByDate format, use YYYY-MM-DD"))
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

                val hasLeadershipInUnit = UserLeadershipRoles.selectAll()
                    .where {
                        (UserLeadershipRoles.userId eq requestedByUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.leftAt.isNull()) and
                            (UserLeadershipRoles.organizationalUnitId eq requestingUnitUUID)
                    }
                    .any()

                val hasMembershipInUnit = UnitAssignments.selectAll()
                    .where {
                        (UnitAssignments.userId eq requestedByUserId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.organizationalUnitId eq requestingUnitUUID) and
                            (UnitAssignments.leftAt.isNull())
                    }
                    .any()

                if (!hasLeadershipInUnit && !hasMembershipInUnit) {
                    return@transaction Result.failure(Exception("You can only create a request for your own unit or for the tuntas"))
                }
            }

            // Determine needs_draugininkas_approval
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
                requesterRank in listOf("Vilkas", "Skautas", "Patyres skautas") -> true
                isUnitLeader -> false
                requestingUnitUUID == null -> false
                else -> request.needsDraugininkasApproval ?: false
            }

            val requestId = BendrasInventoryRequests.insert {
                it[this.tuntasId] = tuntasId
                it[this.requestedByUserId] = requestedByUserId
                it[this.itemId] = itemUUID
                it[itemDescription] = request.itemDescription
                it[quantity] = request.quantity
                it[this.eventId] = null
                it[this.requestingUnitId] = requestingUnitUUID
                it[needsDraugininkasApproval] = needsApproval
                it[draugininkasStatus] = if (needsApproval) "PENDING" else null
                it[topLevelStatus] = "PENDING"
                it[this.startDate] = neededByDate
                it[this.endDate] = neededByDate
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

            // Purchase requests (no itemId) have no reservation to create on approval


            val updated = BendrasInventoryRequests.selectAll()
                .where { BendrasInventoryRequests.id eq requestId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    private fun toResponse(row: ResultRow): BendrasInventoryRequestResponse {
        val itemId = row[BendrasInventoryRequests.itemId]
        val itemDescription = row[BendrasInventoryRequests.itemDescription]
        val itemName = if (itemId != null) {
            Items.selectAll().where { Items.id eq itemId }.firstOrNull()?.get(Items.name) ?: "Unknown"
        } else {
            itemDescription ?: "Unknown"
        }

        val requestingUnitId = row[BendrasInventoryRequests.requestingUnitId]
        val requestingUnitName = requestingUnitId?.let {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq it }
                .firstOrNull()?.get(OrganizationalUnits.name)
        }

        val neededByDate = row[BendrasInventoryRequests.startDate]?.toString()

        return BendrasInventoryRequestResponse(
            id = row[BendrasInventoryRequests.id].toString(),
            tuntasId = row[BendrasInventoryRequests.tuntasId].toString(),
            requestedByUserId = row[BendrasInventoryRequests.requestedByUserId].toString(),
            itemId = itemId?.toString(),
            itemName = itemName,
            itemDescription = itemDescription,
            quantity = row[BendrasInventoryRequests.quantity],
            neededByDate = neededByDate,
            requestingUnitId = requestingUnitId?.toString(),
            requestingUnitName = requestingUnitName,
            needsDraugininkasApproval = row[BendrasInventoryRequests.needsDraugininkasApproval],
            draugininkasStatus = row[BendrasInventoryRequests.draugininkasStatus],
            draugininkasReviewedByUserId = row[BendrasInventoryRequests.draugininkasReviewedByUserId]?.toString(),
            draugininkasRejectionReason = row[BendrasInventoryRequests.draugininkasRejectionReason],
            topLevelStatus = row[BendrasInventoryRequests.topLevelStatus],
            topLevelReviewedByUserId = row[BendrasInventoryRequests.topLevelReviewedByUserId]?.toString(),
            topLevelRejectionReason = row[BendrasInventoryRequests.topLevelRejectionReason],
            notes = row[BendrasInventoryRequests.notes],
            createdAt = row[BendrasInventoryRequests.createdAt].toString(),
            updatedAt = row[BendrasInventoryRequests.updatedAt].toString()
        )
    }
}
