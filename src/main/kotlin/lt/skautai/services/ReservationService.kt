package lt.skautai.services

import kotlinx.datetime.LocalDate
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Reservations
import lt.skautai.models.requests.CreateReservationRequest
import lt.skautai.models.requests.UpdateReservationStatusRequest
import lt.skautai.models.responses.ReservationAvailabilityItemResponse
import lt.skautai.models.responses.ReservationAvailabilityResponse
import lt.skautai.models.responses.ReservationItemResponse
import lt.skautai.models.responses.ReservationListResponse
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
        isAdmin: Boolean,
        unitIds: List<UUID>,
        itemId: UUID? = null,
        status: String? = null
    ): Result<ReservationListResponse> {
        return transaction {
            var query = Reservations.selectAll()
                .where { Reservations.tuntasId eq tuntasId }

            when {
                isAdmin -> {}
                unitIds.isNotEmpty() -> query = query.andWhere {
                    (Reservations.requestingUnitId inList unitIds) or
                        (Reservations.reservedByUserId eq userId)
                }
                else -> query = query.andWhere { Reservations.reservedByUserId eq userId }
            }

            itemId?.let { query = query.andWhere { Reservations.itemId eq it } }
            status?.let { query = query.andWhere { Reservations.status eq it } }

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

    fun getReservation(groupId: UUID, tuntasId: UUID): Result<ReservationResponse> {
        return transaction {
            val rows = Reservations.selectAll()
                .where {
                    (Reservations.groupId eq groupId) and
                        (Reservations.tuntasId eq tuntasId)
                }
                .orderBy(Reservations.createdAt, SortOrder.ASC)
                .toList()

            if (rows.isEmpty()) {
                return@transaction Result.failure(Exception("Reservation not found"))
            }

            Result.success(toReservationResponse(rows))
        }
    }

    fun createReservation(
        tuntasId: UUID,
        reservedByUserId: UUID,
        request: CreateReservationRequest
    ): Result<ReservationResponse> {
        return transaction {
            if (request.title.isBlank()) {
                return@transaction Result.failure(Exception("Reservation title is required"))
            }
            if (request.items.isEmpty()) {
                return@transaction Result.failure(Exception("At least one item must be reserved"))
            }

            val normalizedItems = request.items
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

            val itemRows = Items.selectAll()
                .where {
                    (Items.tuntasId eq tuntasId) and
                        (Items.status eq "ACTIVE") and
                        (Items.id inList itemIds)
                }
                .associateBy { it[Items.id] }

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

            val eventUUID = request.eventId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid event ID"))
                }
            }

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
                    it[requestingUnitId] = requestingUnitUUID
                    it[eventId] = eventUUID
                    it[quantity] = requestedQuantity
                    it[this.startDate] = startDate
                    it[this.endDate] = endDate
                    it[status] = "PENDING"
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
        startDate: String,
        endDate: String
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

            val activeItems = Items.selectAll()
                .where {
                    (Items.tuntasId eq tuntasId) and
                        (Items.status eq "ACTIVE")
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

    private fun toReservationResponse(rows: List<ResultRow>): ReservationResponse {
        val first = rows.first()
        val itemsById = Items.selectAll()
            .where { Items.id inList rows.map { it[Reservations.itemId] } }
            .associateBy { it[Items.id] }

        val reservationItems = rows.map { row ->
            val itemId = row[Reservations.itemId]
            ReservationItemResponse(
                itemId = itemId.toString(),
                itemName = itemsById[itemId]?.get(Items.name) ?: "Unknown",
                quantity = row[Reservations.quantity]
            )
        }.sortedBy { it.itemName.lowercase() }

        return ReservationResponse(
            id = first[Reservations.groupId].toString(),
            title = first[Reservations.title],
            tuntasId = first[Reservations.tuntasId].toString(),
            reservedByUserId = first[Reservations.reservedByUserId].toString(),
            approvedByUserId = first[Reservations.approvedByUserId]?.toString(),
            requestingUnitId = first[Reservations.requestingUnitId]?.toString(),
            eventId = first[Reservations.eventId]?.toString(),
            totalItems = reservationItems.size,
            totalQuantity = reservationItems.sumOf { it.quantity },
            startDate = first[Reservations.startDate].toString(),
            endDate = first[Reservations.endDate].toString(),
            status = first[Reservations.status],
            notes = first[Reservations.notes],
            createdAt = first[Reservations.createdAt].toString(),
            updatedAt = rows.maxBy { it[Reservations.updatedAt] }[Reservations.updatedAt].toString(),
            items = reservationItems
        )
    }
}
