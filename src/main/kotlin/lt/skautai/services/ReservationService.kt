package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.CreateReservationRequest
import lt.skautai.models.requests.UpdateReservationStatusRequest
import lt.skautai.models.responses.ReservationListResponse
import lt.skautai.models.responses.ReservationResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class ReservationService {

    private val validStatuses = listOf(
        "PENDING", "APPROVED", "ACTIVE", "RETURNED", "CANCELLED", "REJECTED"
    )

    fun getReservations(
        tuntasId: UUID,
        itemId: UUID? = null,
        status: String? = null
    ): Result<ReservationListResponse> {
        return transaction {
            var query = Reservations.selectAll()
                .where { Reservations.tuntasId eq tuntasId }

            itemId?.let { query = query.andWhere { Reservations.itemId eq it } }
            status?.let { query = query.andWhere { Reservations.status eq it } }

            val reservations = query.map { toReservationResponse(it) }
            Result.success(ReservationListResponse(reservations = reservations, total = reservations.size))
        }
    }

    fun getReservation(reservationId: UUID, tuntasId: UUID): Result<ReservationResponse> {
        return transaction {
            val reservation = Reservations.selectAll()
                .where {
                    (Reservations.id eq reservationId) and
                            (Reservations.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Reservation not found"))

            Result.success(toReservationResponse(reservation))
        }
    }

    fun createReservation(
        tuntasId: UUID,
        reservedByUserId: UUID,
        request: CreateReservationRequest
    ): Result<ReservationResponse> {
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

            // Verify item exists, belongs to tuntas and is active
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

            // Conflict detection - check overlapping approved/active reservations
            val conflictingQuantity = Reservations
                .select(Reservations.quantity)
                .where {
                    (Reservations.itemId eq itemUUID) and
                            (Reservations.status inList listOf("APPROVED", "ACTIVE")) and
                            (Reservations.startDate lessEq endDate) and
                            (Reservations.endDate greaterEq startDate)
                }
                .sumOf { it[Reservations.quantity] }

            val availableQuantity = item[Items.quantity] - conflictingQuantity
            if (request.quantity > availableQuantity) {
                return@transaction Result.failure(
                    Exception("Insufficient available quantity. Available: $availableQuantity, requested: ${request.quantity}")
                )
            }

            val eventUUID = request.eventId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid event ID"))
                }
            }

            val reservationId = Reservations.insert {
                it[this.itemId] = itemUUID
                it[this.tuntasId] = tuntasId
                it[this.reservedByUserId] = reservedByUserId
                it[eventId] = eventUUID
                it[quantity] = request.quantity
                it[this.startDate] = startDate
                it[this.endDate] = endDate
                it[status] = "PENDING"
                it[notes] = request.notes
            } get Reservations.id

            val reservation = Reservations.selectAll()
                .where { Reservations.id eq reservationId }
                .first()

            Result.success(toReservationResponse(reservation))
        }
    }

    fun updateReservationStatus(
        reservationId: UUID,
        tuntasId: UUID,
        approvedByUserId: UUID,
        request: UpdateReservationStatusRequest
    ): Result<ReservationResponse> {
        return transaction {
            if (request.status !in validStatuses) {
                return@transaction Result.failure(Exception("Invalid status. Must be one of: ${validStatuses.joinToString()}"))
            }

            val existing = Reservations.selectAll()
                .where {
                    (Reservations.id eq reservationId) and
                            (Reservations.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Reservation not found"))

            // Validate status transitions
            val currentStatus = existing[Reservations.status]
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
                (Reservations.id eq reservationId) and
                        (Reservations.tuntasId eq tuntasId)
            }) {
                it[status] = request.status
                if (request.status in listOf("APPROVED", "REJECTED")) {
                    it[this.approvedByUserId] = approvedByUserId
                }
                request.notes?.let { v -> it[notes] = v }
            }

            val updated = Reservations.selectAll()
                .where { Reservations.id eq reservationId }
                .first()

            Result.success(toReservationResponse(updated))
        }
    }

    fun cancelReservation(
        reservationId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<Unit> {
        return transaction {
            val existing = Reservations.selectAll()
                .where {
                    (Reservations.id eq reservationId) and
                            (Reservations.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Reservation not found"))

            if (existing[Reservations.status] !in listOf("PENDING", "APPROVED")) {
                return@transaction Result.failure(Exception("Only PENDING or APPROVED reservations can be cancelled"))
            }

            // Only the person who made the reservation or an approver can cancel
            val isOwner = existing[Reservations.reservedByUserId] == requestingUserId
            if (!isOwner) {
                return@transaction Result.failure(Exception("You can only cancel your own reservations"))
            }

            Reservations.update({
                (Reservations.id eq reservationId) and
                        (Reservations.tuntasId eq tuntasId)
            }) {
                it[status] = "CANCELLED"
            }

            Result.success(Unit)
        }
    }

    private fun toReservationResponse(row: ResultRow): ReservationResponse {
        val itemId = row[Reservations.itemId]
        val itemName = Items.selectAll()
            .where { Items.id eq itemId }
            .firstOrNull()
            ?.get(Items.name) ?: "Unknown"

        return ReservationResponse(
            id = row[Reservations.id].toString(),
            itemId = itemId.toString(),
            itemName = itemName,
            tuntasId = row[Reservations.tuntasId].toString(),
            reservedByUserId = row[Reservations.reservedByUserId].toString(),
            approvedByUserId = row[Reservations.approvedByUserId]?.toString(),
            eventId = row[Reservations.eventId]?.toString(),
            quantity = row[Reservations.quantity],
            startDate = row[Reservations.startDate].toString(),
            endDate = row[Reservations.endDate].toString(),
            status = row[Reservations.status],
            notes = row[Reservations.notes],
            createdAt = row[Reservations.createdAt].toString(),
            updatedAt = row[Reservations.updatedAt].toString()
        )
    }
}