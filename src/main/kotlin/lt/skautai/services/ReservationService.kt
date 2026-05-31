package lt.skautai.services

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.ReservationMovements
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateReservationItemRequest
import lt.skautai.models.requests.CreateReservationRequest
import lt.skautai.models.requests.ReservationMovementRequest
import lt.skautai.models.requests.ReviewReservationRequest
import lt.skautai.models.requests.UpdateReservationPickupRequest
import lt.skautai.models.requests.UpdateReservationReturnTimeRequest
import lt.skautai.models.requests.UpdateReservationStatusRequest
import lt.skautai.models.responses.ReservationAvailabilityItemResponse
import lt.skautai.models.responses.ReservationAvailabilityResponse
import lt.skautai.models.responses.ReservationItemResponse
import lt.skautai.models.responses.ReservationListResponse
import lt.skautai.models.responses.ReservationMovementListResponse
import lt.skautai.models.responses.ReservationMovementResponse
import lt.skautai.models.responses.ReservationResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ReservationService {

    private val validStatuses = listOf(
        "PENDING", "APPROVED", "ACTIVE", "RETURNED", "CANCELLED", "REJECTED"
    )

    fun getReservations(
        tuntasId: UUID,
        userId: UUID,
        canViewAll: Boolean,
        approvableUnitIds: List<UUID>,
        itemId: UUID? = null,
        status: String? = null,
        updatedAfter: Instant? = null
    ): Result<ReservationListResponse> {
        return transaction {
            var query = Reservations.selectAll()
                .where { Reservations.tuntasId eq tuntasId }

            when {
                canViewAll -> {}
                approvableUnitIds.isNotEmpty() -> query = query.andWhere {
                    (Reservations.requestingUnitId inList approvableUnitIds) or
                        (Reservations.reservedByUserId eq userId)
                }
                else -> query = query.andWhere { Reservations.reservedByUserId eq userId }
            }

            if (updatedAfter == null) {
                itemId?.let { query = query.andWhere { Reservations.itemId eq it } }
                status?.let { query = query.andWhere { Reservations.status eq it } }
            }
            updatedAfter?.let { since -> query = query.andWhere { Reservations.updatedAt greater since } }

            val reservationRows = query
                .orderBy(Reservations.createdAt, SortOrder.DESC)
                .toList()

            val reservations = reservationRows
                .groupBy { it[Reservations.groupId] }
                .values
                .map { toReservationResponse(it) }
                .sortedByDescending { it.createdAt }

            Result.success(ReservationListResponse(reservations = reservations, total = reservations.size))
        }
    }

    fun getReservation(
        groupId: UUID,
        tuntasId: UUID,
        userId: UUID,
        canViewAll: Boolean,
        approvableUnitIds: Set<UUID>
    ): Result<ReservationResponse> {
        return transaction {
            val rows = reservationRows(groupId, tuntasId)

            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }
            if (!canAccessReservation(rows, userId, canViewAll, approvableUnitIds)) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }

            Result.success(toReservationResponse(rows))
        }
    }

    fun createReservation(
        tuntasId: UUID,
        reservedByUserId: UUID,
        request: CreateReservationRequest,
        canApproveTopLevel: Boolean = false,
        approvableUnitIds: Set<UUID> = emptySet(),
        userUnitIds: Set<UUID> = emptySet()
    ): Result<ReservationResponse> {
        return transaction {
            if (request.title.isBlank()) {
                return@transaction Result.failure(Exception("Reservation title is required"))
            }
            val requestedItems = if (request.items.isNotEmpty()) {
                request.items
            } else {
                request.itemId?.let {
                    listOf(CreateReservationItemRequest(itemId = it, quantity = request.quantity))
                }.orEmpty()
            }

            if (requestedItems.isEmpty()) {
                return@transaction Result.failure(Exception("At least one item must be reserved"))
            }
            if (request.startDate.isBlank()) {
                return@transaction Result.failure(Exception("Start date is required"))
            }
            if (request.endDate.isBlank()) {
                return@transaction Result.failure(Exception("End date is required"))
            }

            val normalizedItems = requestedItems
                .groupBy { it.itemId }
                .mapValues { (_, items) -> items.sumOf { it.quantity } }

            if (normalizedItems.any { it.value < 1 }) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val itemIds = normalizedItems.keys.map { itemId ->
                try {
                    UUID.fromString(itemId)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid item ID"))
                }
            }

            val startDate = parseDate(request.startDate)
                ?: return@transaction Result.failure(Exception("Invalid start date format, use YYYY-MM-DD"))
            val endDate = parseDate(request.endDate)
                ?: return@transaction Result.failure(Exception("Invalid end date format, use YYYY-MM-DD"))

            if (endDate < startDate) {
                return@transaction Result.failure(Exception("End date cannot be before start date"))
            }

            val itemRows = itemIds
                .sortedBy(UUID::toString)
                .map { itemId ->
                    val row = Items.selectAll()
                        .where {
                            (Items.id eq itemId) and
                                (Items.tuntasId eq tuntasId) and
                                (Items.status eq "ACTIVE")
                        }
                        .forUpdate()
                        .firstOrNull()
                        ?: return@transaction Result.failure(Exception("One or more selected items were not found"))
                    itemId to row
                }
                .toMap()

            if (itemRows.size != itemIds.size) {
                return@transaction Result.failure(Exception("One or more selected items were not found"))
            }

            val requestingUnitUUID = request.requestingUnitId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid requesting unit ID"))
                }
            }
            if (requestingUnitUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq requestingUnitUUID) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Requesting unit not found in this tuntas"))
                if (!canApproveTopLevel && requestingUnitUUID !in userUnitIds) {
                    return@transaction Result.failure(Exception("You can create reservations only for your own unit"))
                }
            }

            val unitItemCustodianIds = itemRows.values
                .mapNotNull { it[Items.custodianId] }
                .distinct()
            val hasSharedItems = itemRows.values.any { it[Items.custodianId] == null }
            val hasUnitItems = unitItemCustodianIds.isNotEmpty()

            if (unitItemCustodianIds.size > 1) {
                return@transaction Result.failure(Exception("Reservation can include items from only one unit inventory"))
            }

            if (hasUnitItems && requestingUnitUUID != null && requestingUnitUUID != unitItemCustodianIds.single()) {
                return@transaction Result.failure(Exception("Requesting unit must match selected unit inventory"))
            }
            if (!canApproveTopLevel && unitItemCustodianIds.any { it !in userUnitIds }) {
                return@transaction Result.failure(Exception("You can reserve only your unit inventory"))
            }

            val inferredRequestingUnitUUID = requestingUnitUUID ?: unitItemCustodianIds.singleOrNull()
            val canApproveUnit = canApproveTopLevel || unitItemCustodianIds.all { it in approvableUnitIds }
            val unitReviewStatus = when {
                !hasUnitItems -> "NOT_REQUIRED"
                canApproveUnit -> "APPROVED"
                else -> "PENDING"
            }
            val topLevelReviewStatus = when {
                !hasSharedItems -> "NOT_REQUIRED"
                canApproveTopLevel -> "APPROVED"
                else -> "PENDING"
            }
            val initialStatus = computeOverallStatus(unitReviewStatus, topLevelReviewStatus)
            val now = Clock.System.now()

            val eventUUID = request.eventId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid event ID"))
                }
            }
            val pickupLocationUUID = request.pickupLocationId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid pickup location ID"))
                }
            }
            val returnLocationUUID = request.returnLocationId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid return location ID"))
                }
            }

            validateReservationLocation(
                tuntasId = tuntasId,
                locationId = pickupLocationUUID,
                itemRows = itemRows,
                reservedByUserId = reservedByUserId
            )?.let { return@transaction Result.failure(it) }
            validateReservationLocation(
                tuntasId = tuntasId,
                locationId = returnLocationUUID,
                itemRows = itemRows,
                reservedByUserId = reservedByUserId
            )?.let { return@transaction Result.failure(it) }

            for ((itemIdString, requestedQuantity) in normalizedItems) {
                val itemUUID = UUID.fromString(itemIdString)
                val item = itemRows[itemUUID]
                    ?: return@transaction Result.failure(Exception("Item not found or not active"))

                val conflictingQuantity = overlappingReservedQuantity(itemUUID, startDate, endDate)
                val availableQuantity = item[Items.quantity] - conflictingQuantity

                if (requestedQuantity > availableQuantity) {
                    return@transaction Result.failure(
                        Exception(
                            "Insufficient available quantity for ${item[Items.name]}. " +
                                "Available: $availableQuantity, requested: $requestedQuantity"
                        )
                    )
                }
            }

            val groupId = UUID.randomUUID()
            normalizedItems.forEach { (itemIdString, requestedQuantity) ->
                Reservations.insert {
                    it[this.groupId] = groupId
                    it[title] = request.title.trim()
                    it[itemId] = UUID.fromString(itemIdString)
                    it[this.tuntasId] = tuntasId
                    it[this.reservedByUserId] = reservedByUserId
                    it[requestingUnitId] = inferredRequestingUnitUUID
                    it[eventId] = eventUUID
                    it[quantity] = requestedQuantity
                    it[this.startDate] = startDate
                    it[this.endDate] = endDate
                    it[this.unitReviewStatus] = unitReviewStatus
                    if (unitReviewStatus == "APPROVED") {
                        it[unitReviewedByUserId] = reservedByUserId
                        it[unitReviewedAt] = now
                    }
                    it[this.topLevelReviewStatus] = topLevelReviewStatus
                    if (topLevelReviewStatus == "APPROVED") {
                        it[topLevelReviewedByUserId] = reservedByUserId
                        it[topLevelReviewedAt] = now
                    }
                    it[pickupLocationId] = pickupLocationUUID
                    it[returnLocationId] = returnLocationUUID
                    it[status] = initialStatus
                    it[notes] = request.notes
                }
            }

            val createdRows = Reservations.selectAll()
                .where { Reservations.groupId eq groupId }
                .orderBy(Reservations.createdAt, SortOrder.ASC)
                .toList()

            Result.success(toReservationResponse(createdRows))
        }
    }

    fun getAvailability(
        tuntasId: UUID,
        userId: UUID,
        startDate: String,
        endDate: String,
        canApproveTopLevel: Boolean,
        userUnitIds: Set<UUID>
    ): Result<ReservationAvailabilityResponse> {
        return transaction {
            val start = try {
                LocalDate.parse(startDate)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid start date format, use YYYY-MM-DD"))
            }

            val end = try {
                LocalDate.parse(endDate)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid end date format, use YYYY-MM-DD"))
            }

            if (end < start) {
                return@transaction Result.failure(Exception("End date cannot be before start date"))
            }

            var activeItems = Items.selectAll()
                .where {
                    (Items.tuntasId eq tuntasId) and
                        (Items.status eq "ACTIVE")
                }
            if (!canApproveTopLevel) {
                activeItems = if (userUnitIds.isEmpty()) {
                    activeItems.andWhere { Items.custodianId.isNull() }
                } else {
                    activeItems.andWhere {
                        Items.custodianId.isNull() or (Items.custodianId inList userUnitIds.toList())
                    }
                }
                activeItems = activeItems.andWhere {
                    (Items.type neq "INDIVIDUAL") or (Items.createdByUserId eq userId)
                }
            }

            val items = activeItems.map { item ->
                val reservedQuantity = overlappingReservedQuantity(item[Items.id], start, end)
                val totalQuantity = item[Items.quantity]
                ReservationAvailabilityItemResponse(
                    itemId = item[Items.id].toString(),
                    totalQuantity = totalQuantity,
                    reservedQuantity = reservedQuantity,
                    availableQuantity = (totalQuantity - reservedQuantity).coerceAtLeast(0)
                )
            }

            Result.success(
                ReservationAvailabilityResponse(
                    startDate = startDate,
                    endDate = endDate,
                    items = items
                )
            )
        }
    }

    fun updateReservationStatus(
        groupId: UUID,
        tuntasId: UUID,
        approvedByUserId: UUID,
        request: UpdateReservationStatusRequest
    ): Result<ReservationResponse> {
        return transaction {
            if (request.status !in validStatuses) {
                return@transaction Result.failure(Exception("Invalid status. Must be one of: ${validStatuses.joinToString()}"))
            }

            val rows = Reservations.selectAll()
                .where {
                    (Reservations.groupId eq groupId) and
                        (Reservations.tuntasId eq tuntasId)
                }
                .toList()

            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }

            val currentStatuses = rows.map { it[Reservations.status] }.distinct()
            if (currentStatuses.size != 1) {
                return@transaction Result.failure(Exception("Reservation group is in an inconsistent state"))
            }

            val currentStatus = currentStatuses.single()
            val validTransitions = mapOf(
                "PENDING" to listOf("APPROVED", "REJECTED", "CANCELLED"),
                "APPROVED" to listOf("ACTIVE", "CANCELLED", "REJECTED"),
                "ACTIVE" to listOf("RETURNED", "CANCELLED"),
                "RETURNED" to emptyList(),
                "CANCELLED" to emptyList(),
                "REJECTED" to emptyList()
            )

            if (request.status !in (validTransitions[currentStatus] ?: emptyList())) {
                return@transaction Result.failure(
                    Exception("Cannot transition from $currentStatus to ${request.status}")
                )
            }

            Reservations.update({
                (Reservations.groupId eq groupId) and
                    (Reservations.tuntasId eq tuntasId)
            }) {
                it[status] = request.status
                if (request.status in listOf("APPROVED", "REJECTED")) {
                    it[this.approvedByUserId] = approvedByUserId
                }
                request.notes?.let { value -> it[notes] = value }
            }

            val updatedRows = Reservations.selectAll()
                .where {
                    (Reservations.groupId eq groupId) and
                        (Reservations.tuntasId eq tuntasId)
                }
                .toList()

            Result.success(toReservationResponse(updatedRows))
        }
    }

    fun reviewReservation(
        groupId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        level: String,
        request: ReviewReservationRequest,
        canApproveTopLevel: Boolean,
        approvableUnitIds: Set<UUID>
    ): Result<ReservationResponse> {
        return transaction {
            val decision = request.status.uppercase()
            if (decision !in listOf("APPROVED", "REJECTED")) {
                return@transaction Result.failure(Exception("Review status must be APPROVED or REJECTED"))
            }

            val rows = reservationRows(groupId, tuntasId)
            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }
            if (rows.any { it[Reservations.status] !in listOf("PENDING", "APPROVED") }) {
                return@transaction Result.failure(Exception("Only pending reservations can be reviewed"))
            }

            val itemRows = Items.selectAll()
                .where { Items.id inList rows.map { it[Reservations.itemId] } }
                .associateBy { it[Items.id] }
            val unitIds = itemRows.values.mapNotNull { it[Items.custodianId] }.distinct()

            val now = Clock.System.now()
            val currentUnitStatus = rows.first()[Reservations.unitReviewStatus]
            val currentTopStatus = rows.first()[Reservations.topLevelReviewStatus]
            val nextUnitStatus: String
            val nextTopStatus: String

            when (level) {
                "unit" -> {
                    if (currentUnitStatus != "PENDING") {
                        return@transaction Result.failure(Exception("Unit review is not pending"))
                    }
                    if (!canApproveTopLevel && !unitIds.all { it in approvableUnitIds }) {
                        return@transaction Result.failure(Exception("Insufficient permissions for unit review"))
                    }
                    nextUnitStatus = decision
                    nextTopStatus = currentTopStatus
                    Reservations.update({
                        (Reservations.groupId eq groupId) and (Reservations.tuntasId eq tuntasId)
                    }) {
                        it[unitReviewStatus] = decision
                        it[unitReviewedByUserId] = reviewerUserId
                        it[unitReviewedAt] = now
                        it[status] = computeOverallStatus(nextUnitStatus, nextTopStatus)
                        request.notes?.let { value -> it[notes] = value }
                    }
                }
                "top-level" -> {
                    if (currentTopStatus != "PENDING") {
                        return@transaction Result.failure(Exception("Top-level review is not pending"))
                    }
                    if (!canApproveTopLevel) {
                        return@transaction Result.failure(Exception("Insufficient permissions for top-level review"))
                    }
                    nextUnitStatus = currentUnitStatus
                    nextTopStatus = decision
                    Reservations.update({
                        (Reservations.groupId eq groupId) and (Reservations.tuntasId eq tuntasId)
                    }) {
                        it[topLevelReviewStatus] = decision
                        it[topLevelReviewedByUserId] = reviewerUserId
                        it[topLevelReviewedAt] = now
                        it[approvedByUserId] = reviewerUserId
                        it[status] = computeOverallStatus(nextUnitStatus, nextTopStatus)
                        request.notes?.let { value -> it[notes] = value }
                    }
                }
                else -> return@transaction Result.failure(Exception("Unknown review level"))
            }

            Result.success(toReservationResponse(reservationRows(groupId, tuntasId)))
        }
    }

    fun getMovements(
        groupId: UUID,
        tuntasId: UUID,
        userId: UUID,
        canViewAll: Boolean,
        approvableUnitIds: Set<UUID>
    ): Result<ReservationMovementListResponse> {
        return transaction {
            val rows = reservationRows(groupId, tuntasId)
            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }
            if (!canAccessReservation(rows, userId, canViewAll, approvableUnitIds)) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }

            val movements = movementsForGroup(groupId)
            Result.success(ReservationMovementListResponse(movements, movements.size))
        }
    }

    fun recordMovement(
        groupId: UUID,
        tuntasId: UUID,
        userId: UUID,
        type: String,
        request: ReservationMovementRequest,
        canApproveTopLevel: Boolean,
        approvableUnitIds: Set<UUID>
    ): Result<ReservationResponse> {
        return transaction {
            val movementType = type.uppercase()
            if (movementType !in listOf("ISSUE", "RETURN_MARKED", "RETURN")) {
                return@transaction Result.failure(Exception("Movement type must be ISSUE, RETURN_MARKED or RETURN"))
            }
            if (request.items.isEmpty()) {
                return@transaction Result.failure(Exception("At least one item is required"))
            }

            val rows = reservationRows(groupId, tuntasId)
            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }

            val currentStatus = rows.first()[Reservations.status]
            if (movementType == "ISSUE" && currentStatus !in listOf("APPROVED", "ACTIVE")) {
                return@transaction Result.failure(Exception("Only approved reservations can be issued"))
            }
            if (movementType == "RETURN" && currentStatus != "ACTIVE") {
                return@transaction Result.failure(Exception("Only active reservations can be returned"))
            }
            if (movementType == "RETURN_MARKED" && currentStatus != "ACTIVE") {
                return@transaction Result.failure(Exception("Only active reservations can be marked as returned"))
            }

            val reservationItems = rows.associateBy { it[Reservations.itemId] }
            val itemIds = reservationItems.keys.toList()
            val itemRows = Items.selectAll()
                .where { Items.id inList itemIds }
                .associateBy { it[Items.id] }
            val currentMovements = movementTotals(groupId)

            request.items.forEach { movementItem ->
                val itemUUID = try {
                    UUID.fromString(movementItem.itemId)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid item ID"))
                }
                if (movementItem.quantity < 1) {
                    return@transaction Result.failure(Exception("Quantity must be at least 1"))
                }

                val reservationRow = reservationItems[itemUUID]
                    ?: return@transaction Result.failure(Exception("Item is not part of this reservation"))
                val item = itemRows[itemUUID]
                    ?: return@transaction Result.failure(Exception("Item not found"))
                if (movementType == "RETURN_MARKED") {
                    if (reservationRow[Reservations.reservedByUserId] != userId) {
                        return@transaction Result.failure(Exception("Only reservation owner can mark items as returned"))
                    }
                } else {
                    val custodianId = item[Items.custodianId]
                    val canManageItem = if (custodianId == null) {
                        canApproveTopLevel
                    } else {
                        custodianId in approvableUnitIds
                    }
                    if (!canManageItem) {
                        return@transaction Result.failure(Exception("Insufficient permissions for ${item[Items.name]}"))
                    }
                }

                val totals = currentMovements[itemUUID] ?: MovementTotals()
                val maxQuantity = when (movementType) {
                    "ISSUE" -> reservationRow[Reservations.quantity] - totals.issued
                    "RETURN_MARKED" -> totals.issued - totals.returnedMarked
                    else -> totals.returnedMarked - totals.returned
                }
                if (movementItem.quantity > maxQuantity) {
                    return@transaction Result.failure(
                        Exception("Quantity too high for ${item[Items.name]}. Available for $movementType: $maxQuantity")
                    )
                }

                val movementLocationId = request.locationId?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: Exception) {
                        return@transaction Result.failure(Exception("Invalid movement location ID"))
                    }
                }
                validateReservationLocation(
                    tuntasId = tuntasId,
                    locationId = movementLocationId,
                    itemRows = itemRows,
                    reservedByUserId = rows.first()[Reservations.reservedByUserId]
                )?.let { return@transaction Result.failure(it) }

                val now = Clock.System.now()
                ReservationMovements.insert {
                    it[reservationGroupId] = groupId
                    it[itemId] = itemUUID
                    it[locationId] = movementLocationId
                    it[this.type] = movementType
                    it[quantity] = movementItem.quantity
                    it[performedByUserId] = userId
                    it[notes] = request.notes
                    it[createdAt] = now
                }
                val historyType = when (movementType) {
                    "ISSUE" -> "RESERVATION_ISSUED"
                    "RETURN_MARKED" -> "RESERVATION_RETURN_MARKED"
                    else -> "RESERVATION_RETURNED"
                }
                val quantityChange = when (movementType) {
                    "ISSUE" -> -movementItem.quantity
                    "RETURN" -> movementItem.quantity
                    else -> 0
                }
                ItemService.recordItemHistory(
                    itemId = itemUUID,
                    eventType = historyType,
                    quantityChange = quantityChange,
                    performedByUserId = userId,
                    notes = request.notes ?: "Rezervacijos judejimas: $movementType",
                    createdAt = now
                )
            }

            val nextTotals = movementTotals(groupId)
            val allIssuedReturned = rows.all { row ->
                val totals = nextTotals[row[Reservations.itemId]] ?: MovementTotals()
                totals.issued > 0 && totals.issued == totals.returned
            }
            Reservations.update({
                (Reservations.groupId eq groupId) and (Reservations.tuntasId eq tuntasId)
            }) {
                it[status] = if (movementType == "RETURN" && allIssuedReturned) "RETURNED" else "ACTIVE"
            }

            Result.success(toReservationResponse(reservationRows(groupId, tuntasId)))
        }
    }

    fun updatePickupTime(
        groupId: UUID,
        tuntasId: UUID,
        userId: UUID,
        canManageTopLevel: Boolean,
        approvableUnitIds: Set<UUID>,
        request: UpdateReservationPickupRequest
    ): Result<ReservationResponse> {
        return transaction {
            val rows = reservationRows(groupId, tuntasId)
            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }
            if (rows.any { it[Reservations.status] !in listOf("APPROVED", "ACTIVE") }) {
                return@transaction Result.failure(Exception("Pickup time can be set only for approved reservations"))
            }
            val canEditPickup = canAccessReservation(rows, userId, canManageTopLevel, approvableUnitIds)
            if (!canEditPickup) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }

            updateTimeProposal(
                rows = rows,
                groupId = groupId,
                tuntasId = tuntasId,
                userId = userId,
                value = request.pickupAt,
                locationId = request.pickupLocationId,
                response = request.response,
                timeColumn = Reservations.pickupAt,
                locationColumn = Reservations.pickupLocationId,
                statusColumn = Reservations.pickupProposalStatus,
                proposedAtColumn = Reservations.pickupProposedAt,
                proposedByColumn = Reservations.pickupProposedByUserId,
                respondedAtColumn = Reservations.pickupRespondedAt,
                respondedByColumn = Reservations.pickupRespondedByUserId,
                label = "pickup"
            ).getOrElse { return@transaction Result.failure(it) }

            Result.success(toReservationResponse(reservationRows(groupId, tuntasId)))
        }
    }

    fun updateReturnTime(
        groupId: UUID,
        tuntasId: UUID,
        userId: UUID,
        canManageTopLevel: Boolean,
        approvableUnitIds: Set<UUID>,
        request: UpdateReservationReturnTimeRequest
    ): Result<ReservationResponse> {
        return transaction {
            val rows = reservationRows(groupId, tuntasId)
            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }
            if (rows.any { it[Reservations.status] != "ACTIVE" }) {
                return@transaction Result.failure(Exception("Return time can be set only for active reservations"))
            }
            if (!canAccessReservation(rows, userId, canManageTopLevel, approvableUnitIds)) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }

            updateTimeProposal(
                rows = rows,
                groupId = groupId,
                tuntasId = tuntasId,
                userId = userId,
                value = request.returnAt,
                locationId = request.returnLocationId,
                response = request.response,
                timeColumn = Reservations.returnAt,
                locationColumn = Reservations.returnLocationId,
                statusColumn = Reservations.returnProposalStatus,
                proposedAtColumn = Reservations.returnProposedAt,
                proposedByColumn = Reservations.returnProposedByUserId,
                respondedAtColumn = Reservations.returnRespondedAt,
                respondedByColumn = Reservations.returnRespondedByUserId,
                label = "return"
            ).getOrElse { return@transaction Result.failure(it) }

            Result.success(toReservationResponse(reservationRows(groupId, tuntasId)))
        }
    }

    fun cancelReservation(
        groupId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<Unit> {
        return transaction {
            val rows = Reservations.selectAll()
                .where {
                    (Reservations.groupId eq groupId) and
                        (Reservations.tuntasId eq tuntasId)
                }
                .toList()

            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }

            if (rows.any { it[Reservations.status] !in listOf("PENDING", "APPROVED") }) {
                return@transaction Result.failure(Exception("Only PENDING or APPROVED reservations can be cancelled"))
            }

            val isOwner = rows.all { it[Reservations.reservedByUserId] == requestingUserId }
            if (!isOwner) {
                return@transaction Result.failure(Exception("You can only cancel your own reservations"))
            }

            Reservations.update({
                (Reservations.groupId eq groupId) and
                    (Reservations.tuntasId eq tuntasId)
            }) {
                it[status] = "CANCELLED"
            }

            Result.success(Unit)
        }
    }

    fun getReservationOwner(groupId: UUID, tuntasId: UUID): UUID? = transaction {
        Reservations.select(Reservations.reservedByUserId)
            .where {
                (Reservations.groupId eq groupId) and
                    (Reservations.tuntasId eq tuntasId)
            }
            .firstOrNull()
            ?.get(Reservations.reservedByUserId)
    }

    private fun overlappingReservedQuantity(
        itemId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): Int {
        return Reservations
            .select(Reservations.quantity)
            .where {
                (Reservations.itemId eq itemId) and
                    (Reservations.status inList listOf("APPROVED", "ACTIVE")) and
                    (Reservations.startDate lessEq endDate) and
                    (Reservations.endDate greaterEq startDate)
            }
            .sumOf { it[Reservations.quantity] }
    }

    private fun parseDate(value: String): LocalDate? {
        return try {
            LocalDate.parse(value)
        } catch (e: Exception) {
            null
        }
    }

    private data class MovementTotals(
        val issued: Int = 0,
        val returnedMarked: Int = 0,
        val returned: Int = 0
    )

    private fun computeOverallStatus(unitReviewStatus: String, topLevelReviewStatus: String): String {
        val statuses = listOf(unitReviewStatus, topLevelReviewStatus)
        return when {
            statuses.any { it == "REJECTED" } -> "REJECTED"
            statuses.all { it == "APPROVED" || it == "NOT_REQUIRED" } -> "APPROVED"
            else -> "PENDING"
        }
    }

    private fun reservationRows(groupId: UUID, tuntasId: UUID): List<ResultRow> {
        return Reservations.selectAll()
            .where {
                (Reservations.groupId eq groupId) and
                    (Reservations.tuntasId eq tuntasId)
            }
            .orderBy(Reservations.createdAt, SortOrder.ASC)
            .toList()
    }

    private fun updateTimeProposal(
        rows: List<ResultRow>,
        groupId: UUID,
        tuntasId: UUID,
        userId: UUID,
        value: String?,
        locationId: String?,
        response: String?,
        timeColumn: Column<Instant?>,
        locationColumn: Column<UUID?>,
        statusColumn: Column<String>,
        proposedAtColumn: Column<Instant?>,
        proposedByColumn: Column<UUID?>,
        respondedAtColumn: Column<Instant?>,
        respondedByColumn: Column<UUID?>,
        label: String
    ): Result<Unit> {
        val normalizedResponse = response?.uppercase()
        val first = rows.first()
        val now = Clock.System.now()
        val itemRows = Items.selectAll()
            .where { Items.id inList rows.map { it[Reservations.itemId] } }
            .associateBy { it[Items.id] }

        if (normalizedResponse == "ACCEPT") {
            if (first[statusColumn] != "PENDING" || first[timeColumn] == null) {
                return Result.failure(Exception("No pending $label proposal"))
            }
            if (first[proposedByColumn] == userId) {
                return Result.failure(Exception("You cannot accept your own $label proposal"))
            }
            Reservations.update({
                (Reservations.groupId eq groupId) and (Reservations.tuntasId eq tuntasId)
            }) {
                it[statusColumn] = "ACCEPTED"
                it[respondedByColumn] = userId
                it[respondedAtColumn] = now
            }
            return Result.success(Unit)
        }

        if (normalizedResponse != null && normalizedResponse != "PROPOSE") {
            return Result.failure(Exception("Invalid $label response"))
        }

        val parsedTime = value?.takeIf { it.isNotBlank() }?.let {
            try {
                Instant.parse(it)
            } catch (e: Exception) {
                return Result.failure(Exception("Invalid $label time format"))
            }
        } ?: return Result.failure(Exception("$label time is required"))
        val parsedLocationId = locationId?.takeIf { it.isNotBlank() }?.let {
            try {
                UUID.fromString(it)
            } catch (e: Exception) {
                return Result.failure(Exception("Invalid $label location ID"))
            }
        }
        validateReservationLocation(
            tuntasId = tuntasId,
            locationId = parsedLocationId,
            itemRows = itemRows,
            reservedByUserId = first[Reservations.reservedByUserId]
        )?.let { return Result.failure(it) }

        Reservations.update({
            (Reservations.groupId eq groupId) and (Reservations.tuntasId eq tuntasId)
        }) {
            it[timeColumn] = parsedTime
            it[locationColumn] = parsedLocationId
            it[statusColumn] = "PENDING"
            it[proposedByColumn] = userId
            it[proposedAtColumn] = now
            it[respondedByColumn] = null
            it[respondedAtColumn] = null
        }

        return Result.success(Unit)
    }

    private fun canAccessReservation(
        rows: List<ResultRow>,
        userId: UUID,
        canViewAll: Boolean,
        approvableUnitIds: Set<UUID>
    ): Boolean {
        if (canViewAll) return true
        val first = rows.first()
        if (first[Reservations.reservedByUserId] == userId) return true
        val requestingUnitId = first[Reservations.requestingUnitId]
        if (requestingUnitId != null && requestingUnitId in approvableUnitIds) return true
        val itemIds = rows.map { it[Reservations.itemId] }
        return Items.select(Items.custodianId)
            .where { Items.id inList itemIds }
            .mapNotNull { it[Items.custodianId] }
            .any { it in approvableUnitIds }
    }

    private fun movementTotals(groupId: UUID): Map<UUID, MovementTotals> {
        return ReservationMovements.selectAll()
            .where { ReservationMovements.reservationGroupId eq groupId }
            .groupBy { it[ReservationMovements.itemId] }
            .mapValues { (_, rows) ->
                MovementTotals(
                    issued = rows.filter { it[ReservationMovements.type] == "ISSUE" }
                        .sumOf { it[ReservationMovements.quantity] },
                    returnedMarked = rows.filter { it[ReservationMovements.type] == "RETURN_MARKED" }
                        .sumOf { it[ReservationMovements.quantity] },
                    returned = rows.filter { it[ReservationMovements.type] == "RETURN" }
                        .sumOf { it[ReservationMovements.quantity] }
                )
            }
    }

    private fun movementsForGroup(groupId: UUID): List<ReservationMovementResponse> {
        val rows = ReservationMovements.selectAll()
            .where { ReservationMovements.reservationGroupId eq groupId }
            .orderBy(ReservationMovements.createdAt, SortOrder.DESC)
            .toList()
        val itemsById = if (rows.isEmpty()) {
            emptyMap()
        } else {
            Items.selectAll()
                .where { Items.id inList rows.map { it[ReservationMovements.itemId] } }
                .associateBy { it[Items.id] }
        }
        val locationRows = rows.mapNotNull { it[ReservationMovements.locationId] }.distinct()
        val locationNodes = if (locationRows.isEmpty()) {
            emptyMap()
        } else {
            Locations.selectAll()
                .where { Locations.id inList locationRows }
                .toList()
                .associate { it[Locations.id] to it.toLocationNodeData() }
        }
        return rows.map { row ->
            val item = itemsById[row[ReservationMovements.itemId]]
            val locationId = row[ReservationMovements.locationId]
            ReservationMovementResponse(
                id = row[ReservationMovements.id].toString(),
                reservationId = row[ReservationMovements.reservationGroupId].toString(),
                itemId = row[ReservationMovements.itemId].toString(),
                itemName = item?.get(Items.name),
                locationId = locationId?.toString(),
                locationPath = locationId?.let { buildLocationPath(it, locationNodes) },
                type = row[ReservationMovements.type],
                quantity = row[ReservationMovements.quantity],
                performedByUserId = row[ReservationMovements.performedByUserId].toString(),
                notes = row[ReservationMovements.notes],
                createdAt = row[ReservationMovements.createdAt].toString()
            )
        }
    }

    private fun toReservationResponse(rows: List<ResultRow>): ReservationResponse {
        val first = rows.first()
        val groupId = first[Reservations.groupId]
        val startDate = first[Reservations.startDate]
        val endDate = first[Reservations.endDate]
        val itemsById = Items.selectAll()
            .where { Items.id inList rows.map { it[Reservations.itemId] } }
            .associateBy { it[Items.id] }
        val reservedByUser = Users.selectAll()
            .where { Users.id eq first[Reservations.reservedByUserId] }
            .firstOrNull()
        val requestingUnit = first[Reservations.requestingUnitId]?.let { unitId ->
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq unitId }
                .firstOrNull()
        }
        val custodianIds = itemsById.values.mapNotNull { it[Items.custodianId] }.distinct()
        val custodiansById = if (custodianIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id inList custodianIds }
                .associateBy { it[OrganizationalUnits.id] }
        }
        val movementTotals = movementTotals(groupId)
        val locationIds = buildSet {
            first[Reservations.pickupLocationId]?.let(::add)
            first[Reservations.returnLocationId]?.let(::add)
        }
        val locationNodes = if (locationIds.isEmpty()) {
            emptyMap()
        } else {
            Locations.selectAll()
                .where { Locations.id inList locationIds.toList() }
                .toList()
                .associate { it[Locations.id] to it.toLocationNodeData() }
        }

        val reservationItems = rows.map { row ->
            val itemId = row[Reservations.itemId]
            val item = itemsById[itemId]
            val custodianId = item?.get(Items.custodianId)
            val totals = movementTotals[itemId] ?: MovementTotals()
            val overlappingOtherQuantity = Reservations
                .select(Reservations.quantity)
                .where {
                    (Reservations.itemId eq itemId) and
                        (Reservations.groupId neq groupId) and
                        (Reservations.status inList listOf("APPROVED", "ACTIVE")) and
                        (Reservations.startDate lessEq endDate) and
                        (Reservations.endDate greaterEq startDate)
                }
                .sumOf { it[Reservations.quantity] }
            val remainingAfterReservation = item?.let {
                (it[Items.quantity] - overlappingOtherQuantity - row[Reservations.quantity]).coerceAtLeast(0)
            }
            ReservationItemResponse(
                itemId = itemId.toString(),
                itemName = item?.get(Items.name) ?: "Unknown",
                quantity = row[Reservations.quantity],
                custodianId = custodianId?.toString(),
                custodianName = custodianId?.let { custodiansById[it]?.get(OrganizationalUnits.name) },
                remainingAfterReservation = remainingAfterReservation,
                issuedQuantity = totals.issued,
                returnedQuantity = totals.returned,
                markedReturnedQuantity = totals.returnedMarked,
                remainingToIssue = (row[Reservations.quantity] - totals.issued).coerceAtLeast(0),
                remainingToReturn = (totals.issued - totals.returned).coerceAtLeast(0),
                remainingToMarkReturned = (totals.issued - totals.returnedMarked).coerceAtLeast(0),
                remainingToReceive = (totals.returnedMarked - totals.returned).coerceAtLeast(0)
            )
        }.sortedBy { it.itemName.lowercase() }

        return ReservationResponse(
            id = first[Reservations.groupId].toString(),
            title = first[Reservations.title],
            tuntasId = first[Reservations.tuntasId].toString(),
            reservedByUserId = first[Reservations.reservedByUserId].toString(),
            reservedByName = reservedByUser?.let { "${it[Users.name]} ${it[Users.surname]}".trim() },
            approvedByUserId = first[Reservations.approvedByUserId]?.toString(),
            requestingUnitId = first[Reservations.requestingUnitId]?.toString(),
            requestingUnitName = requestingUnit?.get(OrganizationalUnits.name),
            eventId = first[Reservations.eventId]?.toString(),
            totalItems = reservationItems.size,
            totalQuantity = reservationItems.sumOf { it.quantity },
            startDate = first[Reservations.startDate].toString(),
            endDate = first[Reservations.endDate].toString(),
            status = first[Reservations.status],
            unitReviewStatus = first[Reservations.unitReviewStatus],
            unitReviewedByUserId = first[Reservations.unitReviewedByUserId]?.toString(),
            unitReviewedAt = first[Reservations.unitReviewedAt]?.toString(),
            topLevelReviewStatus = first[Reservations.topLevelReviewStatus],
            topLevelReviewedByUserId = first[Reservations.topLevelReviewedByUserId]?.toString(),
            topLevelReviewedAt = first[Reservations.topLevelReviewedAt]?.toString(),
            pickupAt = first[Reservations.pickupAt]?.toString(),
            pickupLocationId = first[Reservations.pickupLocationId]?.toString(),
            pickupLocationPath = first[Reservations.pickupLocationId]?.let { buildLocationPath(it, locationNodes) },
            pickupProposalStatus = first[Reservations.pickupProposalStatus],
            pickupProposedAt = first[Reservations.pickupProposedAt]?.toString(),
            pickupProposedByUserId = first[Reservations.pickupProposedByUserId]?.toString(),
            pickupRespondedAt = first[Reservations.pickupRespondedAt]?.toString(),
            pickupRespondedByUserId = first[Reservations.pickupRespondedByUserId]?.toString(),
            returnAt = first[Reservations.returnAt]?.toString(),
            returnLocationId = first[Reservations.returnLocationId]?.toString(),
            returnLocationPath = first[Reservations.returnLocationId]?.let { buildLocationPath(it, locationNodes) },
            returnProposalStatus = first[Reservations.returnProposalStatus],
            returnProposedAt = first[Reservations.returnProposedAt]?.toString(),
            returnProposedByUserId = first[Reservations.returnProposedByUserId]?.toString(),
            returnRespondedAt = first[Reservations.returnRespondedAt]?.toString(),
            returnRespondedByUserId = first[Reservations.returnRespondedByUserId]?.toString(),
            notes = first[Reservations.notes],
            createdAt = first[Reservations.createdAt].toString(),
            updatedAt = rows.maxBy { it[Reservations.updatedAt] }[Reservations.updatedAt].toString(),
            items = reservationItems
        )
    }

    private fun validateReservationLocation(
        tuntasId: UUID,
        locationId: UUID?,
        itemRows: Map<UUID, ResultRow>,
        reservedByUserId: UUID
    ): Exception? {
        if (locationId == null) return null
        val locationRows = Locations.selectAll()
            .where { Locations.tuntasId eq tuntasId }
            .toList()
        val location = locationRows.firstOrNull { it[Locations.id] == locationId }
            ?: return Exception("Location not found")
        val custodianIds = itemRows.values.mapNotNull { it[Items.custodianId] }.toSet()
        return when (location[Locations.visibility]) {
            "PUBLIC" -> null
            "UNIT" -> {
                val ownerUnitId = location[Locations.ownerUnitId]
                if (ownerUnitId == null || ownerUnitId !in custodianIds) {
                    Exception("Selected location does not match reserved unit inventory")
                } else null
            }
            "PRIVATE" -> {
                if (location[Locations.ownerUserId] != reservedByUserId) {
                    Exception("Private reservation locations can only belong to reservation owner")
                } else null
            }
            else -> Exception("Invalid location visibility")
        }
    }
}
