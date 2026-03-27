package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.AssignEventRoleRequest
import lt.skautai.models.requests.CreateEventRequest
import lt.skautai.models.requests.UpdateEventRequest
import lt.skautai.models.responses.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class EventService {

    private val validTypes = listOf("STOVYKLA", "SUEIGA", "RENGINYS")
    private val validStatuses = listOf("PLANNING", "ACTIVE", "COMPLETED", "CANCELLED")
    private val validEventRoles = listOf(
        "VIRSININKAS", "KOMENDANTAS", "UKVEDYS", "PASTOVYKLES_GURU",
        "VADOVAS", "SAVANORIS", "PATYRE_SKAUTAS", "SKAUTAS",
        "PROGRAMERIS", "MAISTININKAS"
    )
    private val validTargetGroups = listOf("PATYRE_SKAUTAI", "SKAUTAI_VILKAI", "TEVAI")

    fun getEvents(
        tuntasId: UUID,
        type: String? = null,
        status: String? = null
    ): Result<EventListResponse> {
        return transaction {
            var query = Events.selectAll()
                .where { Events.tuntasId eq tuntasId }

            type?.let { query = query.andWhere { Events.type eq it } }
            status?.let { query = query.andWhere { Events.status eq it } }

            val events = query.map { toEventResponse(it) }
            Result.success(EventListResponse(events = events, total = events.size))
        }
    }

    fun getEvent(eventId: UUID, tuntasId: UUID): Result<EventResponse> {
        return transaction {
            val event = Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))

            Result.success(toEventResponse(event))
        }
    }

    fun createEvent(
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateEventRequest
    ): Result<EventResponse> {
        return transaction {
            if (request.name.isBlank()) {
                return@transaction Result.failure(Exception("Name cannot be blank"))
            }

            if (request.type !in validTypes) {
                return@transaction Result.failure(Exception("Invalid type. Must be one of: ${validTypes.joinToString()}"))
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

            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
                }
            }

            val orgUnitUUID = request.organizationalUnitId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }

            val eventId = Events.insert {
                it[this.tuntasId] = tuntasId
                it[name] = request.name
                it[type] = request.type
                it[this.startDate] = startDate
                it[this.endDate] = endDate
                it[this.locationId] = locationUUID
                it[this.organizationalUnitId] = orgUnitUUID
                it[this.createdByUserId] = createdByUserId
                it[status] = "PLANNING"
                it[notes] = request.notes
            } get Events.id

            // Assign creator as VIRSININKAS automatically
            EventRoles.insert {
                it[this.eventId] = eventId
                it[userId] = createdByUserId
                it[role] = "VIRSININKAS"
                it[assignedByUserId] = createdByUserId
            }

            // If STOVYKLA, create stovykla details automatically
            if (request.type == "STOVYKLA") {
                val registrationDeadline = request.registrationDeadline?.let {
                    try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) { null }
                }

                StovyklaDetails.insert {
                    it[this.eventId] = eventId
                    it[this.registrationDeadline] = registrationDeadline
                    it[expectedParticipants] = request.expectedParticipants
                }
            }

            val event = Events.selectAll()
                .where { Events.id eq eventId }
                .first()

            Result.success(toEventResponse(event))
        }
    }

    fun updateEvent(
        eventId: UUID,
        tuntasId: UUID,
        request: UpdateEventRequest
    ): Result<EventResponse> {
        return transaction {
            Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))

            request.status?.let {
                if (it !in validStatuses) {
                    return@transaction Result.failure(Exception("Invalid status"))
                }
            }

            val startDate = request.startDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid start date format"))
                }
            }

            val endDate = request.endDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid end date format"))
                }
            }

            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
                }
            }

            val orgUnitUUID = request.organizationalUnitId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }

            Events.update({
                (Events.id eq eventId) and
                        (Events.tuntasId eq tuntasId)
            }) {
                request.name?.let { v -> it[name] = v }
                request.status?.let { v -> it[status] = v }
                request.notes?.let { v -> it[notes] = v }
                startDate?.let { v -> it[Events.startDate] = v }
                endDate?.let { v -> it[Events.endDate] = v }
                locationUUID?.let { v -> it[Events.locationId] = v }
                orgUnitUUID?.let { v -> it[Events.organizationalUnitId] = v }
            }

            val updated = Events.selectAll()
                .where { Events.id eq eventId }
                .first()

            Result.success(toEventResponse(updated))
        }
    }

    fun deleteEvent(eventId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            val existing = Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))

            if (existing[Events.status] !in listOf("PLANNING", "CANCELLED")) {
                return@transaction Result.failure(Exception("Only PLANNING or CANCELLED events can be deleted"))
            }

            Events.update({
                (Events.id eq eventId) and
                        (Events.tuntasId eq tuntasId)
            }) {
                it[status] = "CANCELLED"
            }

            Result.success(Unit)
        }
    }

    fun assignEventRole(
        eventId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID,
        request: AssignEventRoleRequest
    ): Result<EventRoleResponse> {
        return transaction {
            Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))

            if (request.role !in validEventRoles) {
                return@transaction Result.failure(Exception("Invalid event role"))
            }

            if (request.role == "PROGRAMERIS" && request.targetGroup == null) {
                return@transaction Result.failure(Exception("PROGRAMERIS role requires a target group"))
            }

            request.targetGroup?.let {
                if (it !in validTargetGroups) {
                    return@transaction Result.failure(Exception("Invalid target group. Must be one of: ${validTargetGroups.joinToString()}"))
                }
            }

            val targetUserUUID = try { UUID.fromString(request.userId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid user ID"))
            }

            // Verify user is a tuntas member
            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq targetUserUUID) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("User is not a member of this tuntas"))

            // VIRSININKAS and KOMENDANTAS are unique per event
            if (request.role in listOf("VIRSININKAS", "KOMENDANTAS")) {
                val existing = EventRoles.selectAll()
                    .where {
                        (EventRoles.eventId eq eventId) and
                                (EventRoles.role eq request.role)
                    }
                    .firstOrNull()

                if (existing != null) {
                    // Transfer the role - remove from current holder
                    EventRoles.deleteWhere {
                        (EventRoles.eventId eq eventId) and
                                (EventRoles.role eq request.role)
                    }
                }
            }

            val roleId = EventRoles.insert {
                it[this.eventId] = eventId
                it[userId] = targetUserUUID
                it[role] = request.role
                it[targetGroup] = request.targetGroup
                it[this.assignedByUserId] = assignedByUserId
            } get EventRoles.id

            val roleRow = EventRoles.selectAll()
                .where { EventRoles.id eq roleId }
                .first()

            Result.success(toEventRoleResponse(roleRow))
        }
    }

    fun removeEventRole(
        eventId: UUID,
        roleId: UUID,
        tuntasId: UUID
    ): Result<Unit> {
        return transaction {
            Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))

            EventRoles.selectAll()
                .where {
                    (EventRoles.id eq roleId) and
                            (EventRoles.eventId eq eventId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event role not found"))

            EventRoles.deleteWhere {
                (EventRoles.id eq roleId) and
                        (EventRoles.eventId eq eventId)
            }

            Result.success(Unit)
        }
    }

    private fun toEventResponse(row: ResultRow): EventResponse {
        val eventId = row[Events.id]

        val roles = EventRoles.selectAll()
            .where { EventRoles.eventId eq eventId }
            .map { toEventRoleResponse(it) }

        val stovyklaDetails = if (row[Events.type] == "STOVYKLA") {
            StovyklaDetails.selectAll()
                .where { StovyklaDetails.eventId eq eventId }
                .firstOrNull()
                ?.let {
                    StovyklaDetailsResponse(
                        id = it[StovyklaDetails.id].toString(),
                        registrationDeadline = it[StovyklaDetails.registrationDeadline]?.toString(),
                        expectedParticipants = it[StovyklaDetails.expectedParticipants],
                        actualParticipants = it[StovyklaDetails.actualParticipants]
                    )
                }
        } else null

        return EventResponse(
            id = eventId.toString(),
            tuntasId = row[Events.tuntasId].toString(),
            name = row[Events.name],
            type = row[Events.type],
            startDate = row[Events.startDate].toString(),
            endDate = row[Events.endDate].toString(),
            locationId = row[Events.locationId]?.toString(),
            organizationalUnitId = row[Events.organizationalUnitId]?.toString(),
            createdByUserId = row[Events.createdByUserId]?.toString(),
            status = row[Events.status],
            notes = row[Events.notes],
            createdAt = row[Events.createdAt].toString(),
            eventRoles = roles,
            stovyklaDetails = stovyklaDetails
        )
    }

    private fun toEventRoleResponse(row: ResultRow): EventRoleResponse {
        return EventRoleResponse(
            id = row[EventRoles.id].toString(),
            userId = row[EventRoles.userId].toString(),
            role = row[EventRoles.role],
            targetGroup = row[EventRoles.targetGroup],
            assignedByUserId = row[EventRoles.assignedByUserId]?.toString(),
            assignedAt = row[EventRoles.assignedAt].toString()
        )
    }
}