package lt.skautai.services

import kotlinx.datetime.LocalDate
import lt.skautai.database.tables.DraugoveRequisitionItems
import lt.skautai.database.tables.DraugoveRequisitions
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import lt.skautai.models.requests.CreateRequisitionRequest
import lt.skautai.models.requests.RequisitionTopLevelReviewRequest
import lt.skautai.models.requests.RequisitionUnitReviewRequest
import lt.skautai.models.responses.RequisitionItemResponse
import lt.skautai.models.responses.RequisitionListResponse
import lt.skautai.models.responses.RequisitionResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class RequisitionService {

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
        isTopLevelReviewer: Boolean,
        reviewableUnitIds: List<UUID>
    ): Result<RequisitionListResponse> {
        return transaction {
            val filter = when {
                isTopLevelReviewer -> DraugoveRequisitions.tuntasId eq tuntasId
                reviewableUnitIds.isNotEmpty() -> {
                    (DraugoveRequisitions.tuntasId eq tuntasId) and
                        (
                            (DraugoveRequisitions.createdByUserId eq userId) or
                                (DraugoveRequisitions.organizationalUnitId inList reviewableUnitIds)
                            )
                }
                else -> {
                    (DraugoveRequisitions.tuntasId eq tuntasId) and
                        (DraugoveRequisitions.createdByUserId eq userId)
                }
            }

            val query = DraugoveRequisitions.selectAll().where { filter }
            val requests = query.map { toResponse(it) }
            Result.success(RequisitionListResponse(requests = requests, total = requests.size))
        }
    }

    fun getRequest(
        requestId: UUID,
        tuntasId: UUID,
        userId: UUID,
        isTopLevelReviewer: Boolean,
        reviewableUnitIds: List<UUID>
    ): Result<RequisitionResponse> {
        return transaction {
            val requisition = DraugoveRequisitions.selectAll()
                .where {
                    (DraugoveRequisitions.id eq requestId) and
                        (DraugoveRequisitions.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            val canAccess = isTopLevelReviewer ||
                requisition[DraugoveRequisitions.createdByUserId] == userId ||
                requisition[DraugoveRequisitions.organizationalUnitId]?.let { it in reviewableUnitIds } == true

            if (!canAccess) {
                return@transaction Result.failure(Exception("Request is not accessible"))
            }

            Result.success(toResponse(requisition))
        }
    }

    fun createRequest(
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateRequisitionRequest
    ): Result<RequisitionResponse> {
        return transaction {
            if (request.items.isEmpty()) {
                return@transaction Result.failure(Exception("Pridek bent viena norima daikta"))
            }
            if (request.items.any { it.itemName.isBlank() }) {
                return@transaction Result.failure(Exception("Norimo daikto pavadinimas privalomas"))
            }
            if (request.items.any { it.quantity < 1 }) {
                return@transaction Result.failure(Exception("Kiekis turi buti bent 1"))
            }

            val requestingUnitId = request.requestingUnitId?.let {
                try {
                    UUID.fromString(it)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid requesting unit ID"))
                }
            }

            val creatorIsRequestingUnitLeader = requestingUnitId?.let { unitId ->
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Requesting unit not found"))

                val hasLeadershipInUnit = isUnitLeader(createdByUserId, tuntasId, unitId)
                val hasMembershipInUnit = UnitAssignments.selectAll()
                    .where {
                        (UnitAssignments.userId eq createdByUserId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.organizationalUnitId eq unitId) and
                            UnitAssignments.leftAt.isNull()
                    }
                    .any()

                if (!hasLeadershipInUnit && !hasMembershipInUnit) {
                    return@transaction Result.failure(Exception("You can only create a request for your own unit"))
                }

                hasLeadershipInUnit
            } ?: false

            if (requestingUnitId == null && !canCreateTopLevelRequest(createdByUserId, tuntasId)) {
                return@transaction Result.failure(Exception("Only active leaders can create a tuntas-level request"))
            }

            val neededByDate = request.neededByDate?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid neededByDate format, use YYYY-MM-DD"))
                }
            }

            val autoApproveInUnit = requestingUnitId != null && creatorIsRequestingUnitLeader
            val unitReviewStatus = when {
                autoApproveInUnit -> "APPROVED"
                requestingUnitId != null -> "PENDING"
                else -> "SKIPPED"
            }
            val topLevelReviewStatus = if (requestingUnitId == null) "PENDING" else "NOT_REQUIRED"
            val createdStatus = if (autoApproveInUnit) "APPROVED" else "SUBMITTED"
            val now = kotlinx.datetime.Clock.System.now()

            val requisitionId = DraugoveRequisitions.insert {
                it[this.tuntasId] = tuntasId
                it[organizationalUnitId] = requestingUnitId
                it[eventId] = null
                it[this.createdByUserId] = createdByUserId
                it[reviewedByUserId] = if (autoApproveInUnit) createdByUserId else null
                it[status] = createdStatus
                it[this.unitReviewStatus] = unitReviewStatus
                it[this.unitReviewedByUserId] = if (autoApproveInUnit) createdByUserId else null
                it[this.unitReviewedAt] = if (autoApproveInUnit) now else null
                it[this.topLevelReviewStatus] = topLevelReviewStatus
                it[this.topLevelReviewedByUserId] = null
                it[this.topLevelReviewedAt] = null
                it[notes] = mergeNotes(request.notes, neededByDate)
            } get DraugoveRequisitions.id

            request.items.forEach { item ->
                DraugoveRequisitionItems.insert {
                    it[this.requisitionId] = requisitionId
                    it[itemId] = null
                    it[itemName] = item.itemName
                    it[itemDescription] = item.itemDescription
                    it[quantityRequested] = item.quantity
                    it[quantityApproved] = null
                    it[rejectionReason] = null
                    it[notes] = item.notes
                }
            }

            if (autoApproveInUnit) {
                approveAllItems(requisitionId)
            }

            val saved = DraugoveRequisitions.selectAll()
                .where { DraugoveRequisitions.id eq requisitionId }
                .first()

            Result.success(toResponse(saved))
        }
    }

    fun unitReview(
        requestId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: RequisitionUnitReviewRequest
    ): Result<RequisitionResponse> {
        return transaction {
            if (request.action !in listOf("APPROVED", "FORWARDED", "REJECTED")) {
                return@transaction Result.failure(Exception("Invalid unit review action"))
            }

            val existing = DraugoveRequisitions.selectAll()
                .where {
                    (DraugoveRequisitions.id eq requestId) and
                        (DraugoveRequisitions.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            val unitId = existing[DraugoveRequisitions.organizationalUnitId]
                ?: return@transaction Result.failure(Exception("Only unit requests can be reviewed at unit level"))

            if (!isUnitLeader(reviewerUserId, tuntasId, unitId)) {
                return@transaction Result.failure(Exception("You are not a leader of this unit"))
            }

            if (existing[DraugoveRequisitions.unitReviewStatus] != "PENDING") {
                return@transaction Result.failure(Exception("Request is not waiting for unit review"))
            }

            when (request.action) {
                "APPROVED" -> {
                    DraugoveRequisitions.update({ DraugoveRequisitions.id eq requestId }) {
                        it[status] = "APPROVED"
                        it[reviewedByUserId] = reviewerUserId
                        it[unitReviewStatus] = "APPROVED"
                        it[unitReviewedByUserId] = reviewerUserId
                        it[unitReviewedAt] = kotlinx.datetime.Clock.System.now()
                        it[topLevelReviewStatus] = "NOT_REQUIRED"
                    }
                    DraugoveRequisitionItems.update({ DraugoveRequisitionItems.requisitionId eq requestId }) {
                        it[rejectionReason] = null
                    }
                    approveAllItems(requestId)
                }
                "FORWARDED" -> {
                    DraugoveRequisitions.update({ DraugoveRequisitions.id eq requestId }) {
                        it[status] = "PARTIALLY_APPROVED"
                        it[unitReviewStatus] = "FORWARDED"
                        it[unitReviewedByUserId] = reviewerUserId
                        it[unitReviewedAt] = kotlinx.datetime.Clock.System.now()
                        it[topLevelReviewStatus] = "PENDING"
                    }
                }
                "REJECTED" -> {
                    DraugoveRequisitions.update({ DraugoveRequisitions.id eq requestId }) {
                        it[status] = "REJECTED"
                        it[reviewedByUserId] = reviewerUserId
                        it[unitReviewStatus] = "REJECTED"
                        it[unitReviewedByUserId] = reviewerUserId
                        it[unitReviewedAt] = kotlinx.datetime.Clock.System.now()
                        it[topLevelReviewStatus] = "NOT_REQUIRED"
                    }
                    DraugoveRequisitionItems.update({ DraugoveRequisitionItems.requisitionId eq requestId }) {
                        it[rejectionReason] = request.rejectionReason
                    }
                }
            }

            val updated = DraugoveRequisitions.selectAll()
                .where { DraugoveRequisitions.id eq requestId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    fun cancelRequest(
        requestId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<Unit> {
        return transaction {
            val existing = DraugoveRequisitions.selectAll()
                .where {
                    (DraugoveRequisitions.id eq requestId) and
                        (DraugoveRequisitions.tuntasId eq tuntasId)
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[DraugoveRequisitions.createdByUserId] != requestingUserId) {
                return@transaction Result.failure(Exception("You can only cancel your own requests"))
            }

            if (existing[DraugoveRequisitions.status] in listOf("APPROVED", "REJECTED", "CANCELLED")) {
                return@transaction Result.failure(Exception("Request cannot be cancelled in its current state"))
            }

            val unitStatus = existing[DraugoveRequisitions.unitReviewStatus]
            val topLevelStatus = existing[DraugoveRequisitions.topLevelReviewStatus]
            val now = kotlinx.datetime.Clock.System.now()

            DraugoveRequisitions.update({
                (DraugoveRequisitions.id eq requestId) and
                    (DraugoveRequisitions.tuntasId eq tuntasId)
            }) {
                it[status] = "CANCELLED"
                it[reviewedByUserId] = requestingUserId
                if (unitStatus == "PENDING") {
                    it[unitReviewStatus] = "CANCELLED"
                    it[unitReviewedByUserId] = requestingUserId
                    it[unitReviewedAt] = now
                }
                if (topLevelStatus == "PENDING") {
                    it[topLevelReviewStatus] = "CANCELLED"
                    it[topLevelReviewedByUserId] = requestingUserId
                    it[topLevelReviewedAt] = now
                }
                it[updatedAt] = now
            }
            DraugoveRequisitionItems.update({ DraugoveRequisitionItems.requisitionId eq requestId }) {
                it[rejectionReason] = "Cancelled by requester"
            }

            Result.success(Unit)
        }
    }

    fun topLevelReview(
        requestId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: RequisitionTopLevelReviewRequest
    ): Result<RequisitionResponse> {
        return transaction {
            if (request.action !in listOf("APPROVED", "REJECTED")) {
                return@transaction Result.failure(Exception("Invalid top level review action"))
            }

            val existing = DraugoveRequisitions.selectAll()
                .where {
                    (DraugoveRequisitions.id eq requestId) and
                        (DraugoveRequisitions.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[DraugoveRequisitions.topLevelReviewStatus] != "PENDING") {
                return@transaction Result.failure(Exception("Request is not waiting for top level review"))
            }

            when (request.action) {
                "APPROVED" -> {
                    DraugoveRequisitions.update({ DraugoveRequisitions.id eq requestId }) {
                        it[status] = "APPROVED"
                        it[reviewedByUserId] = reviewerUserId
                        it[topLevelReviewStatus] = "APPROVED"
                        it[topLevelReviewedByUserId] = reviewerUserId
                        it[topLevelReviewedAt] = kotlinx.datetime.Clock.System.now()
                    }
                    DraugoveRequisitionItems.update({ DraugoveRequisitionItems.requisitionId eq requestId }) {
                        it[rejectionReason] = null
                    }
                    approveAllItems(requestId)
                }
                "REJECTED" -> {
                    DraugoveRequisitions.update({ DraugoveRequisitions.id eq requestId }) {
                        it[status] = "REJECTED"
                        it[reviewedByUserId] = reviewerUserId
                        it[topLevelReviewStatus] = "REJECTED"
                        it[topLevelReviewedByUserId] = reviewerUserId
                        it[topLevelReviewedAt] = kotlinx.datetime.Clock.System.now()
                    }
                    DraugoveRequisitionItems.update({ DraugoveRequisitionItems.requisitionId eq requestId }) {
                        it[rejectionReason] = request.rejectionReason
                    }
                }
            }

            val updated = DraugoveRequisitions.selectAll()
                .where { DraugoveRequisitions.id eq requestId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    private fun isUnitLeader(userId: UUID, tuntasId: UUID, unitId: UUID): Boolean {
        return UserLeadershipRoles
            .innerJoin(Roles)
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.organizationalUnitId eq unitId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    (Roles.name inList unitLeaderRoles)
            }
            .any()
    }

    private fun canCreateTopLevelRequest(userId: UUID, tuntasId: UUID): Boolean {
        val topLevelLeaderRoles = listOf(
            "Tuntininkas",
            "Tuntininko pavaduotojas",
            "Inventorininkas"
        )

        return UserLeadershipRoles
            .innerJoin(Roles)
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    UserLeadershipRoles.organizationalUnitId.isNull() and
                    (Roles.name inList topLevelLeaderRoles)
            }
            .any()
    }

    private fun loadItems(requisitionId: UUID): List<RequisitionItemResponse> {
        return DraugoveRequisitionItems.selectAll()
            .where { DraugoveRequisitionItems.requisitionId eq requisitionId }
            .map { row ->
                RequisitionItemResponse(
                    id = row[DraugoveRequisitionItems.id].toString(),
                    itemId = row[DraugoveRequisitionItems.itemId]?.toString(),
                    itemName = row[DraugoveRequisitionItems.itemName]
                        ?: row[DraugoveRequisitionItems.itemId]?.toString()
                        ?: "Neivardytas daiktas",
                    itemDescription = row[DraugoveRequisitionItems.itemDescription],
                    quantityRequested = row[DraugoveRequisitionItems.quantityRequested],
                    quantityApproved = row[DraugoveRequisitionItems.quantityApproved],
                    rejectionReason = row[DraugoveRequisitionItems.rejectionReason],
                    notes = row[DraugoveRequisitionItems.notes]
                )
            }
    }

    private fun approveAllItems(requisitionId: UUID) {
        DraugoveRequisitionItems.selectAll()
            .where { DraugoveRequisitionItems.requisitionId eq requisitionId }
            .forEach { row ->
                DraugoveRequisitionItems.update({ DraugoveRequisitionItems.id eq row[DraugoveRequisitionItems.id] }) {
                    it[quantityApproved] = row[DraugoveRequisitionItems.quantityRequested]
                }
            }
    }

    private fun toResponse(row: ResultRow): RequisitionResponse {
        val requestingUnitId = row[DraugoveRequisitions.organizationalUnitId]
        val requestingUnitName = requestingUnitId?.let {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq it }
                .firstOrNull()
                ?.get(OrganizationalUnits.name)
        }
        val items = loadItems(row[DraugoveRequisitions.id])
        val neededByDate = row[DraugoveRequisitions.notes]
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("neededByDate=") }
            ?.substringAfter("=")

        val reviewLevel = when (row[DraugoveRequisitions.topLevelReviewStatus]) {
            "PENDING", "APPROVED", "REJECTED" -> "TOP_LEVEL"
            else -> if (requestingUnitId != null) "UNIT" else "TOP_LEVEL"
        }
        val lastAction = when {
            row[DraugoveRequisitions.status] == "CANCELLED" -> "CANCELLED"
            row[DraugoveRequisitions.status] == "APPROVED" && row[DraugoveRequisitions.topLevelReviewStatus] == "APPROVED" -> "TOP_LEVEL_APPROVED"
            row[DraugoveRequisitions.status] == "APPROVED" && row[DraugoveRequisitions.unitReviewStatus] == "APPROVED" -> "UNIT_APPROVED"
            row[DraugoveRequisitions.unitReviewStatus] == "FORWARDED" -> "FORWARDED"
            row[DraugoveRequisitions.unitReviewStatus] == "REJECTED" -> "UNIT_REJECTED"
            row[DraugoveRequisitions.topLevelReviewStatus] == "REJECTED" -> "TOP_LEVEL_REJECTED"
            else -> "SUBMITTED"
        }

        return RequisitionResponse(
            id = row[DraugoveRequisitions.id].toString(),
            tuntasId = row[DraugoveRequisitions.tuntasId].toString(),
            createdByUserId = row[DraugoveRequisitions.createdByUserId].toString(),
            requestingUnitId = requestingUnitId?.toString(),
            requestingUnitName = requestingUnitName,
            status = row[DraugoveRequisitions.status],
            unitReviewStatus = row[DraugoveRequisitions.unitReviewStatus],
            unitReviewedByUserId = row[DraugoveRequisitions.unitReviewedByUserId]?.toString(),
            unitReviewedAt = row[DraugoveRequisitions.unitReviewedAt]?.toString(),
            topLevelReviewStatus = row[DraugoveRequisitions.topLevelReviewStatus],
            topLevelReviewedByUserId = row[DraugoveRequisitions.topLevelReviewedByUserId]?.toString(),
            topLevelReviewedAt = row[DraugoveRequisitions.topLevelReviewedAt]?.toString(),
            reviewLevel = reviewLevel,
            lastAction = lastAction,
            neededByDate = neededByDate,
            notes = stripNeededByDate(row[DraugoveRequisitions.notes]),
            items = items,
            createdAt = row[DraugoveRequisitions.createdAt].toString(),
            updatedAt = row[DraugoveRequisitions.updatedAt].toString()
        )
    }

    private fun mergeNotes(notes: String?, neededByDate: LocalDate?): String? {
        val rawNotes = notes?.trim().orEmpty()
        val lines = buildList {
            neededByDate?.let { add("neededByDate=$it") }
            if (rawNotes.isNotBlank()) add(rawNotes)
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun stripNeededByDate(notes: String?): String? {
        val cleaned = notes
            ?.lineSequence()
            ?.filterNot { it.startsWith("neededByDate=") }
            ?.joinToString("\n")
            ?.trim()
        return cleaned?.takeIf { it.isNotBlank() }
    }
}
