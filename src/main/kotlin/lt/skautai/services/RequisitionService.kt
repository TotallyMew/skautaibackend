package lt.skautai.services

import kotlinx.datetime.LocalDate
import lt.skautai.database.tables.DraugoveRequisitionItems
import lt.skautai.database.tables.DraugoveRequisitions
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import lt.skautai.models.requests.CreateRequisitionRequest
import lt.skautai.models.requests.AddRequisitionToInventoryRequest
import lt.skautai.models.requests.RequisitionTopLevelReviewRequest
import lt.skautai.models.requests.RequisitionUnitReviewRequest
import lt.skautai.models.requests.RequisitionMarkPurchasedRequest
import lt.skautai.models.responses.RequisitionItemResponse
import lt.skautai.models.responses.RequisitionCapabilitiesResponse
import lt.skautai.models.responses.RequisitionListResponse
import lt.skautai.models.responses.RequisitionResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
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
        reviewableUnitIds: List<UUID>,
        updatedAfter: kotlinx.datetime.Instant? = null
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

            var query = DraugoveRequisitions.selectAll().where { filter }
            updatedAfter?.let { since ->
                query = query.andWhere { DraugoveRequisitions.updatedAt greater since }
            }
            val rows = query.toList()
            val hydration = buildListHydration(rows)
            val requests = rows.map {
                withCapabilities(
                    response = toResponse(it, hydration),
                    userId = userId,
                    isTopLevelReviewer = isTopLevelReviewer,
                    reviewableUnitIds = reviewableUnitIds,
                    canCreateItems = false
                )
            }
            Result.success(RequisitionListResponse(requests = requests, total = requests.size))
        }
    }

    fun getRequest(
        requestId: UUID,
        tuntasId: UUID,
        userId: UUID,
        isTopLevelReviewer: Boolean,
        reviewableUnitIds: List<UUID>,
        canCreateItems: Boolean = false
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

            val response = toResponse(requisition)
            Result.success(withCapabilities(
                response = response,
                userId = userId,
                isTopLevelReviewer = isTopLevelReviewer,
                reviewableUnitIds = reviewableUnitIds,
                canCreateItems = canCreateItems
            ))
        }
    }

    private fun withCapabilities(
        response: RequisitionResponse,
        userId: UUID,
        isTopLevelReviewer: Boolean,
        reviewableUnitIds: List<UUID>,
        canCreateItems: Boolean
    ): RequisitionResponse {
            val isOwner = response.createdByUserId == userId.toString()
            val canReviewUnit = response.requestingUnitId?.let { unitId ->
                isTopLevelReviewer || runCatching { UUID.fromString(unitId) }.getOrNull() in reviewableUnitIds
            } == true
            return response.copy(capabilities = RequisitionCapabilitiesResponse(
                canReviewUnit = !isOwner && response.unitReviewStatus == "PENDING" && canReviewUnit,
                canReviewTopLevel = !isOwner && response.topLevelReviewStatus == "PENDING" && isTopLevelReviewer,
                canCancel = isOwner && response.status !in listOf("APPROVED", "REJECTED", "CANCELLED"),
                canMarkPurchased = response.status == "APPROVED" && isTopLevelReviewer,
                canAddToInventory = response.status == "PURCHASED" && canCreateItems
            ))
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
            if (request.items.any { it.requestType !in listOf("NEW_ITEM", "RESTOCK_EXISTING") }) {
                return@transaction Result.failure(Exception("Neteisingas prašymo tipas"))
            }

            val requestingUnitId = request.requestingUnitId?.let {
                try {
                    UUID.fromString(it)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid requesting unit ID"))
                }
            }

            requestingUnitId?.let { unitId ->
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

            }

            if (requestingUnitId == null && !canCreateTopLevelRequest(createdByUserId, tuntasId)) {
                return@transaction Result.failure(Exception("Tik aktyvus draugininkas arba tuntinio lygio vadovas gali kurti prašymą tuntui"))
            }

            val neededByDate = request.neededByDate?.let {
                try {
                    LocalDate.parse(it)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid neededByDate format, use YYYY-MM-DD"))
                }
            }

            val unitReviewStatus = when {
                requestingUnitId != null -> "PENDING"
                else -> "SKIPPED"
            }
            val topLevelReviewStatus = if (requestingUnitId == null) "PENDING" else "NOT_REQUIRED"

            val requisitionId = DraugoveRequisitions.insert {
                it[this.tuntasId] = tuntasId
                it[organizationalUnitId] = requestingUnitId
                it[eventId] = null
                it[this.createdByUserId] = createdByUserId
                it[reviewedByUserId] = null
                it[status] = "SUBMITTED"
                it[this.unitReviewStatus] = unitReviewStatus
                it[this.unitReviewedByUserId] = null
                it[this.unitReviewedAt] = null
                it[this.topLevelReviewStatus] = topLevelReviewStatus
                it[this.topLevelReviewedByUserId] = null
                it[this.topLevelReviewedAt] = null
                it[notes] = mergeNotes(request.notes, neededByDate)
            } get DraugoveRequisitions.id

            request.items.forEach { item ->
                val existingItemId = item.existingItemId?.let {
                    try {
                        UUID.fromString(it)
                    } catch (_: Exception) {
                        return@transaction Result.failure(Exception("Neteisingas existing item ID"))
                    }
                }
                if (item.requestType == "RESTOCK_EXISTING" && existingItemId == null) {
                    return@transaction Result.failure(Exception("RESTOCK_EXISTING tipui privalomas existingItemId"))
                }
                if (item.requestType == "NEW_ITEM" && existingItemId != null) {
                    return@transaction Result.failure(Exception("NEW_ITEM tipui existingItemId neturi būti nurodytas"))
                }
                val existingItemRow = existingItemId?.let { requestedItemId ->
                    Items.selectAll()
                        .where {
                            (Items.id eq requestedItemId) and
                                (Items.tuntasId eq tuntasId) and
                                (Items.status eq "ACTIVE")
                        }
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("Pasirinktas papildomas daiktas nerastas"))
                }
                val normalizedName = when {
                    item.requestType == "RESTOCK_EXISTING" -> existingItemRow?.get(Items.name) ?: item.itemName
                    else -> item.itemName
                }
                DraugoveRequisitionItems.insert {
                    it[this.requisitionId] = requisitionId
                    it[itemId] = null
                    it[requestType] = item.requestType
                    it[this.existingItemId] = existingItemId
                    it[itemName] = normalizedName
                    it[itemDescription] = item.itemDescription
                    it[quantityRequested] = item.quantity
                    it[quantityApproved] = null
                    it[rejectionReason] = null
                    it[notes] = item.notes
                }
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

            if (existing[DraugoveRequisitions.createdByUserId] == reviewerUserId) {
                return@transaction Result.failure(Exception("You cannot review your own requisition"))
            }

            if (
                !isUnitLeader(reviewerUserId, tuntasId, unitId) &&
                !isTopLevelRequisitionReviewer(reviewerUserId, tuntasId)
            ) {
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

            if (existing[DraugoveRequisitions.createdByUserId] == reviewerUserId) {
                return@transaction Result.failure(Exception("You cannot review your own requisition"))
            }

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

    fun markPurchased(
        requestId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: RequisitionMarkPurchasedRequest
    ): Result<RequisitionResponse> {
        return transaction {
            val existing = DraugoveRequisitions.selectAll()
                .where {
                    (DraugoveRequisitions.id eq requestId) and
                        (DraugoveRequisitions.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[DraugoveRequisitions.status] != "APPROVED") {
                return@transaction Result.failure(Exception("Only approved requests can be marked as purchased"))
            }

            val now = kotlinx.datetime.Clock.System.now()
            DraugoveRequisitions.update({ DraugoveRequisitions.id eq requestId }) {
                it[status] = "PURCHASED"
                it[purchasedByUserId] = userId
                it[purchasedAt] = now
                if (!request.notes.isNullOrBlank()) {
                    it[notes] = mergePlainNotes(existing[DraugoveRequisitions.notes], request.notes)
                }
            }

            val updated = DraugoveRequisitions.selectAll()
                .where { DraugoveRequisitions.id eq requestId }
                .first()
            Result.success(toResponse(updated))
        }
    }

    fun addPurchasedItemsToInventory(
        requestId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: AddRequisitionToInventoryRequest
    ): Result<RequisitionResponse> {
        return transaction {
            val existing = DraugoveRequisitions.selectAll()
                .where {
                    (DraugoveRequisitions.id eq requestId) and
                        (DraugoveRequisitions.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[DraugoveRequisitions.status] != "PURCHASED") {
                return@transaction Result.failure(Exception("Only purchased requests can be added to inventory"))
            }
            if (request.items.isEmpty()) {
                return@transaction Result.failure(Exception("At least one inventory action is required"))
            }

            val linesById = DraugoveRequisitionItems.selectAll()
                .where { DraugoveRequisitionItems.requisitionId eq requestId }
                .associateBy { it[DraugoveRequisitionItems.id] }

            val now = kotlinx.datetime.Clock.System.now()
            request.items.forEach { action ->
                val lineId = try {
                    UUID.fromString(action.requisitionItemId)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid requisition item ID"))
                }
                val line = linesById[lineId]
                    ?: return@transaction Result.failure(Exception("Requisition item not found"))
                if (line[DraugoveRequisitionItems.itemId] != null) {
                    return@transaction Result.failure(Exception("Requisition item is already added to inventory"))
                }

                val quantity = line[DraugoveRequisitionItems.quantityApproved]
                    ?: line[DraugoveRequisitionItems.quantityRequested]
                if (quantity < 1) {
                    return@transaction Result.failure(Exception("Approved quantity must be at least 1"))
                }

                val purchaseDate = action.purchaseDate?.let {
                    try { LocalDate.parse(it) } catch (_: Exception) {
                        return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                    }
                }

                when (action.action) {
                    "NEW_ITEM" -> {
                        val custodianUUID = action.custodianId?.let {
                            try { UUID.fromString(it) } catch (_: Exception) {
                                return@transaction Result.failure(Exception("Invalid custodian ID"))
                            }
                        } ?: existing[DraugoveRequisitions.organizationalUnitId]

                        custodianUUID?.let { unitId ->
                            OrganizationalUnits.selectAll()
                                .where {
                                    (OrganizationalUnits.id eq unitId) and
                                        (OrganizationalUnits.tuntasId eq tuntasId)
                                }
                                .firstOrNull()
                                ?: return@transaction Result.failure(Exception("Custodian unit not found in this tuntas"))
                        }

                        if (action.type !in listOf("COLLECTIVE", "ASSIGNED", "INDIVIDUAL")) {
                            return@transaction Result.failure(Exception("Invalid inventory type"))
                        }
                        if (action.condition !in listOf("GOOD", "DAMAGED", "LOST", "REPAIR_NEEDED", "UNKNOWN")) {
                            return@transaction Result.failure(Exception("Invalid item condition"))
                        }

                        val createdItemId = Items.insert {
                            it[this.tuntasId] = tuntasId
                            it[custodianId] = custodianUUID
                            it[origin] = "UNIT_ACQUIRED"
                            it[name] = line[DraugoveRequisitionItems.itemName] ?: "Nupirktas daiktas"
                            it[description] = line[DraugoveRequisitionItems.itemDescription]
                            it[type] = action.type
                            it[category] = action.category
                            it[condition] = action.condition
                            it[Items.quantity] = quantity
                            it[locationId] = null
                            it[temporaryStorageLabel] = null
                            it[sourceSharedItemId] = null
                            it[responsibleUserId] = null
                            it[createdByUserId] = userId
                            it[qrToken] = UUID.randomUUID().toString()
                            it[photoUrl] = null
                            it[this.purchaseDate] = purchaseDate
                            it[purchasePrice] = action.purchasePrice?.toBigDecimal()
                            it[notes] = action.notes ?: line[DraugoveRequisitionItems.notes]
                            it[status] = "ACTIVE"
                            it[createdAt] = now
                            it[updatedAt] = now
                        } get Items.id

                        DraugoveRequisitionItems.update({ DraugoveRequisitionItems.id eq lineId }) {
                            it[itemId] = createdItemId
                        }
                        ItemService.recordItemHistory(
                            itemId = createdItemId,
                            eventType = "PURCHASED_NEW",
                            quantityChange = quantity,
                            performedByUserId = userId,
                            requisitionId = requestId,
                            notes = action.notes ?: "Nupirkta pagal pirkimo prašymą",
                            createdAt = now
                        )
                    }
                    "RESTOCK_EXISTING" -> {
                        val existingItemId = try {
                            UUID.fromString(
                                action.existingItemId
                                    ?: line[DraugoveRequisitionItems.existingItemId]?.toString()
                                    ?: return@transaction Result.failure(Exception("existingItemId is required"))
                            )
                        } catch (_: Exception) {
                            return@transaction Result.failure(Exception("Invalid existing item ID"))
                        }
                        val item = Items.selectAll()
                            .where {
                                (Items.id eq existingItemId) and
                                    (Items.tuntasId eq tuntasId) and
                                    (Items.status eq "ACTIVE")
                            }
                            .forUpdate()
                            .firstOrNull()
                            ?: return@transaction Result.failure(Exception("Existing item not found"))

                        Items.update({ Items.id eq existingItemId }) {
                            it[Items.quantity] = item[Items.quantity] + quantity
                            action.purchaseDate?.let { _ -> it[Items.purchaseDate] = purchaseDate }
                            action.purchasePrice?.let { value -> it[purchasePrice] = value.toBigDecimal() }
                            if (!action.notes.isNullOrBlank()) it[notes] = action.notes
                            it[updatedAt] = now
                        }
                        DraugoveRequisitionItems.update({ DraugoveRequisitionItems.id eq lineId }) {
                            it[itemId] = existingItemId
                        }
                        ItemService.recordItemHistory(
                            itemId = existingItemId,
                            eventType = "RESTOCKED",
                            quantityChange = quantity,
                            performedByUserId = userId,
                            requisitionId = requestId,
                            notes = action.notes ?: "Papildyta pagal pirkimo prašymą",
                            createdAt = now
                        )
                    }
                    else -> return@transaction Result.failure(Exception("Invalid inventory action"))
                }
            }

            val remaining = DraugoveRequisitionItems.selectAll()
                .where { DraugoveRequisitionItems.requisitionId eq requestId }
                .any { it[DraugoveRequisitionItems.itemId] == null }

            DraugoveRequisitions.update({ DraugoveRequisitions.id eq requestId }) {
                if (!remaining) {
                    it[status] = "INVENTORY_ADDED"
                    it[addedToInventoryAt] = now
                    it[addedToInventoryByUserId] = userId
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

    private fun isTopLevelRequisitionReviewer(userId: UUID, tuntasId: UUID): Boolean {
        return UserLeadershipRoles
            .innerJoin(Roles)
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    UserLeadershipRoles.organizationalUnitId.isNull() and
                    (Roles.name inList listOf("Tuntininkas", "Tuntininko pavaduotojas", "Inventorininkas"))
            }
            .any()
    }

    private fun canCreateTopLevelRequest(userId: UUID, tuntasId: UUID): Boolean {
        val topLevelLeaderRoles = listOf(
            "Tuntininkas",
            "Tuntininko pavaduotojas",
            "Inventorininkas"
        )
        val hasTopLevelRole = UserLeadershipRoles
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
        if (hasTopLevelRole) return true

        return UserLeadershipRoles
            .innerJoin(Roles)
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    UserLeadershipRoles.organizationalUnitId.isNotNull()
            }
            .any { row ->
                val roleName = row[Roles.name]
                roleName.contains("drauginink", ignoreCase = true) &&
                    !roleName.contains("pavaduotoj", ignoreCase = true)
            }
    }

    private data class RequisitionListHydration(
        val unitNames: Map<UUID, String>,
        val itemsByRequisitionId: Map<UUID, List<RequisitionItemResponse>>
    )

    private fun buildListHydration(rows: List<ResultRow>): RequisitionListHydration {
        if (rows.isEmpty()) {
            return RequisitionListHydration(emptyMap(), emptyMap())
        }
        val unitIds = rows.mapNotNull { it[DraugoveRequisitions.organizationalUnitId] }.distinct()
        val unitNames = if (unitIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id inList unitIds }
                .associate { it[OrganizationalUnits.id] to it[OrganizationalUnits.name] }
        }
        val requisitionIds = rows.map { it[DraugoveRequisitions.id] }
        val itemsByRequisitionId = DraugoveRequisitionItems.selectAll()
            .where { DraugoveRequisitionItems.requisitionId inList requisitionIds }
            .map { row ->
                row[DraugoveRequisitionItems.requisitionId] to toItemResponse(row)
            }
            .groupBy({ it.first }, { it.second })
        return RequisitionListHydration(unitNames, itemsByRequisitionId)
    }

    private fun loadItems(requisitionId: UUID): List<RequisitionItemResponse> {
        return DraugoveRequisitionItems.selectAll()
            .where { DraugoveRequisitionItems.requisitionId eq requisitionId }
            .map(::toItemResponse)
    }

    private fun toItemResponse(row: ResultRow): RequisitionItemResponse =
        RequisitionItemResponse(
            id = row[DraugoveRequisitionItems.id].toString(),
            itemId = row[DraugoveRequisitionItems.itemId]?.toString(),
            requestType = row[DraugoveRequisitionItems.requestType],
            existingItemId = row[DraugoveRequisitionItems.existingItemId]?.toString(),
            itemName = row[DraugoveRequisitionItems.itemName]
                ?: row[DraugoveRequisitionItems.itemId]?.toString()
                ?: "Neivardytas daiktas",
            itemDescription = row[DraugoveRequisitionItems.itemDescription],
            quantityRequested = row[DraugoveRequisitionItems.quantityRequested],
            quantityApproved = row[DraugoveRequisitionItems.quantityApproved],
            rejectionReason = row[DraugoveRequisitionItems.rejectionReason],
            notes = row[DraugoveRequisitionItems.notes]
        )

    private fun approveAllItems(requisitionId: UUID) {
        DraugoveRequisitionItems.selectAll()
            .where { DraugoveRequisitionItems.requisitionId eq requisitionId }
            .forEach { row ->
                DraugoveRequisitionItems.update({ DraugoveRequisitionItems.id eq row[DraugoveRequisitionItems.id] }) {
                    it[quantityApproved] = row[DraugoveRequisitionItems.quantityRequested]
                }
            }
    }

    private fun toResponse(row: ResultRow, hydration: RequisitionListHydration? = null): RequisitionResponse {
        val requestingUnitId = row[DraugoveRequisitions.organizationalUnitId]
        val requestingUnitName = requestingUnitId?.let { hydration?.unitNames?.get(it) } ?: requestingUnitId?.let {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq it }
                .firstOrNull()
                ?.get(OrganizationalUnits.name)
        }
        val items = hydration?.itemsByRequisitionId?.get(row[DraugoveRequisitions.id])
            ?: loadItems(row[DraugoveRequisitions.id])
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
            row[DraugoveRequisitions.status] == "INVENTORY_ADDED" -> "INVENTORY_ADDED"
            row[DraugoveRequisitions.status] == "PURCHASED" -> "PURCHASED"
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
            purchasedAt = row[DraugoveRequisitions.purchasedAt]?.toString(),
            addedToInventoryAt = row[DraugoveRequisitions.addedToInventoryAt]?.toString(),
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

    private fun mergePlainNotes(existing: String?, addition: String?): String? {
        val next = addition?.trim().orEmpty()
        if (next.isBlank()) return existing
        return listOfNotNull(existing?.takeIf { it.isNotBlank() }, next).joinToString("\n")
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
