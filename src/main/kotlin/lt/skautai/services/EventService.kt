package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.*
import lt.skautai.models.responses.*
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
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
    private val eventManagerRoles = listOf("VIRSININKAS")
    private val eventInventoryRoles = listOf("VIRSININKAS", "KOMENDANTAS", "UKVEDYS")
    private val validBucketTypes = listOf("PROGRAM", "KITCHEN", "ADMIN", "MEDICAL", "PASTOVYKLE", "OTHER")
    private val validPurchaseStatuses = listOf("DRAFT", "PURCHASED", "ADDED_TO_INVENTORY", "CANCELLED")

    fun isTuntasMember(userId: UUID, tuntasId: UUID): Boolean = transaction {
        UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null
    }

    fun canViewEvents(userId: UUID, tuntasId: UUID): Boolean = transaction {
        val isMember = UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                    (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null
        if (!isMember) return@transaction false

        val hasLeadership = UserLeadershipRoles.selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull())
            }
            .firstOrNull() != null
        if (hasLeadership) return@transaction true

        val isVadovas = UserRanks
            .innerJoin(Roles, { roleId }, { id })
            .selectAll()
            .where {
                (UserRanks.userId eq userId) and
                    (UserRanks.tuntasId eq tuntasId) and
                    (Roles.name eq "Vadovas")
            }
            .firstOrNull() != null
        if (isVadovas) return@transaction true

        EventRoles
            .innerJoin(Events, { eventId }, { id })
            .selectAll()
            .where {
                (EventRoles.userId eq userId) and
                    (Events.tuntasId eq tuntasId)
            }
            .firstOrNull() != null
    }

    fun canManageEvent(eventId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull() ?: return@transaction false

        EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq userId) and
                        (EventRoles.role inList eventManagerRoles)
            }
            .firstOrNull() != null
    }

    fun canManageEventInventory(eventId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull() ?: return@transaction false

        EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq userId) and
                        (EventRoles.role inList eventInventoryRoles)
            }
            .firstOrNull() != null
    }

    fun canStartEvent(eventId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        ensureEvent(eventId, tuntasId) ?: return@transaction false
        EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                    (EventRoles.userId eq userId) and
                    (EventRoles.role inList listOf("VIRSININKAS", "KOMENDANTAS"))
            }
            .firstOrNull() != null
    }

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
            val isActiveMember = UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq createdByUserId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull() != null

            if (!isActiveMember) {
                return@transaction Result.failure(Exception("User is not a member of this tuntas"))
            }

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

            createDefaultBuckets(eventId)

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

    private val validAgeGroups = listOf("VILKAI", "SKAUTAI", "PATYRE_SKAUTAI", "MIXED")
    private val validRecipientTypes = listOf("DIRECT", "GURU_PROXY")

    private fun verifyStovyklaEvent(eventId: UUID, tuntasId: UUID): ResultRow? {
        val event = Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull() ?: return null
        if (event[Events.type] != "STOVYKLA") return null
        return event
    }

    private fun toPastovykleResponse(row: ResultRow) = PastovykleResponse(
        id = row[Pastovykles.id].toString(),
        eventId = row[Pastovykles.eventId].toString(),
        name = row[Pastovykles.name],
        responsibleUserId = row[Pastovykles.responsibleUserId]?.toString(),
        ageGroup = row[Pastovykles.ageGroup],
        notes = row[Pastovykles.notes]
    )

    private fun toInventoryResponse(row: ResultRow): PastovykleInventoryResponse {
        val itemName = Items.selectAll()
            .where { Items.id eq row[PastovykleInventory.itemId] }
            .firstOrNull()?.get(Items.name) ?: "Unknown"
        return PastovykleInventoryResponse(
            id = row[PastovykleInventory.id].toString(),
            pastovykleId = row[PastovykleInventory.pastovykleId].toString(),
            itemId = row[PastovykleInventory.itemId].toString(),
            itemName = itemName,
            distributedByUserId = row[PastovykleInventory.distributedByUserId]?.toString(),
            recipientUserId = row[PastovykleInventory.recipientUserId]?.toString(),
            recipientType = row[PastovykleInventory.recipientType],
            quantityAssigned = row[PastovykleInventory.quantityAssigned],
            quantityReturned = row[PastovykleInventory.quantityReturned],
            assignedAt = row[PastovykleInventory.assignedAt].toString(),
            returnedAt = row[PastovykleInventory.returnedAt]?.toString(),
            notes = row[PastovykleInventory.notes]
        )
    }

    fun updateStovyklaDetails(
        eventId: UUID,
        tuntasId: UUID,
        request: UpdateStovyklaDetailsRequest
    ): Result<StovyklaDetailsResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            val registrationDeadline = request.registrationDeadline?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid registration deadline format, use YYYY-MM-DD"))
                }
            }

            val existing = StovyklaDetails.selectAll()
                .where { StovyklaDetails.eventId eq eventId }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Stovykla details not found"))

            StovyklaDetails.update({ StovyklaDetails.eventId eq eventId }) {
                registrationDeadline?.let { v -> it[StovyklaDetails.registrationDeadline] = v }
                request.expectedParticipants?.let { v -> it[StovyklaDetails.expectedParticipants] = v }
                request.actualParticipants?.let { v -> it[StovyklaDetails.actualParticipants] = v }
            }

            val updated = StovyklaDetails.selectAll()
                .where { StovyklaDetails.eventId eq eventId }
                .first()

            Result.success(
                StovyklaDetailsResponse(
                    id = updated[StovyklaDetails.id].toString(),
                    registrationDeadline = updated[StovyklaDetails.registrationDeadline]?.toString(),
                    expectedParticipants = updated[StovyklaDetails.expectedParticipants],
                    actualParticipants = updated[StovyklaDetails.actualParticipants]
                )
            )
        }
    }

    fun getPastovykles(eventId: UUID, tuntasId: UUID): Result<PastovykleListResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            val list = Pastovykles.selectAll()
                .where { Pastovykles.eventId eq eventId }
                .map { toPastovykleResponse(it) }

            Result.success(PastovykleListResponse(pastovykles = list, total = list.size))
        }
    }

    fun getPastovykle(eventId: UUID, pastovykleId: UUID, tuntasId: UUID): Result<PastovykleResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            val row = Pastovykles.selectAll()
                .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            Result.success(toPastovykleResponse(row))
        }
    }

    fun createPastovykle(
        eventId: UUID,
        tuntasId: UUID,
        request: CreatePastovykleRequest
    ): Result<PastovykleResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            if (request.name.isBlank()) {
                return@transaction Result.failure(Exception("Name cannot be blank"))
            }

            if (request.ageGroup != null && request.ageGroup !in validAgeGroups) {
                return@transaction Result.failure(Exception("Invalid age group. Must be one of: ${validAgeGroups.joinToString()}"))
            }

            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }

            val newId = Pastovykles.insert {
                it[Pastovykles.eventId] = eventId
                it[name] = request.name
                it[responsibleUserId] = responsibleUUID
                it[ageGroup] = request.ageGroup
                it[notes] = request.notes
            } get Pastovykles.id

            val row = Pastovykles.selectAll().where { Pastovykles.id eq newId }.first()
            Result.success(toPastovykleResponse(row))
        }
    }

    fun updatePastovykle(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID,
        request: UpdatePastovykleRequest
    ): Result<PastovykleResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            Pastovykles.selectAll()
                .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            if (request.ageGroup != null && request.ageGroup !in validAgeGroups) {
                return@transaction Result.failure(Exception("Invalid age group. Must be one of: ${validAgeGroups.joinToString()}"))
            }

            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }

            Pastovykles.update({ (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }) {
                request.name?.let { v -> it[name] = v }
                request.ageGroup?.let { v -> it[ageGroup] = v }
                request.notes?.let { v -> it[notes] = v }
                responsibleUUID?.let { v -> it[responsibleUserId] = v }
            }

            val updated = Pastovykles.selectAll().where { Pastovykles.id eq pastovykleId }.first()
            Result.success(toPastovykleResponse(updated))
        }
    }

    fun deletePastovykle(eventId: UUID, pastovykleId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            Pastovykles.selectAll()
                .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            val hasInventory = PastovykleInventory.selectAll()
                .where { PastovykleInventory.pastovykleId eq pastovykleId }
                .count() > 0

            if (hasInventory) {
                return@transaction Result.failure(Exception("Cannot delete pastovyklė with assigned inventory"))
            }

            Pastovykles.deleteWhere { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
            Result.success(Unit)
        }
    }

    fun getPastovykleInventory(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID
    ): Result<PastovykleInventoryListResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            Pastovykles.selectAll()
                .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            val list = PastovykleInventory.selectAll()
                .where { PastovykleInventory.pastovykleId eq pastovykleId }
                .map { toInventoryResponse(it) }

            Result.success(PastovykleInventoryListResponse(inventory = list, total = list.size))
        }
    }

    fun assignInventory(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID,
        distributedByUserId: UUID,
        request: AssignPastovykleInventoryRequest
    ): Result<PastovykleInventoryResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            Pastovykles.selectAll()
                .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val itemUUID = try { UUID.fromString(request.itemId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid item ID"))
            }

            Items.selectAll()
                .where { (Items.id eq itemUUID) and (Items.tuntasId eq tuntasId) and (Items.status eq "ACTIVE") }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found or not active"))

            if (request.recipientType != null && request.recipientType !in validRecipientTypes) {
                return@transaction Result.failure(Exception("Invalid recipient type. Must be one of: ${validRecipientTypes.joinToString()}"))
            }

            val recipientUUID = request.recipientUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid recipient user ID"))
                }
            }

            val newId = PastovykleInventory.insert {
                it[PastovykleInventory.pastovykleId] = pastovykleId
                it[itemId] = itemUUID
                it[this.distributedByUserId] = distributedByUserId
                it[recipientUserId] = recipientUUID
                it[recipientType] = request.recipientType
                it[quantityAssigned] = request.quantity
                it[quantityReturned] = 0
                it[assignedAt] = kotlinx.datetime.Clock.System.now()
                it[notes] = request.notes
            } get PastovykleInventory.id

            val row = PastovykleInventory.selectAll().where { PastovykleInventory.id eq newId }.first()
            Result.success(toInventoryResponse(row))
        }
    }

    fun updateInventoryAssignment(
        eventId: UUID,
        pastovykleId: UUID,
        inventoryId: UUID,
        tuntasId: UUID,
        request: UpdatePastovykleInventoryRequest
    ): Result<PastovykleInventoryResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            Pastovykles.selectAll()
                .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            val existing = PastovykleInventory.selectAll()
                .where { (PastovykleInventory.id eq inventoryId) and (PastovykleInventory.pastovykleId eq pastovykleId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Inventory assignment not found"))

            if (request.quantityReturned != null) {
                if (request.quantityReturned < 0) {
                    return@transaction Result.failure(Exception("Returned quantity cannot be negative"))
                }
                if (request.quantityReturned > existing[PastovykleInventory.quantityAssigned]) {
                    return@transaction Result.failure(Exception("Returned quantity cannot exceed assigned quantity"))
                }
            }

            val returnedAt = if (request.quantityReturned != null) {
                request.returnedAt?.let {
                    try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) {
                        return@transaction Result.failure(Exception("Invalid returnedAt format, use ISO-8601"))
                    }
                } ?: kotlinx.datetime.Clock.System.now()
            } else null

            PastovykleInventory.update({ (PastovykleInventory.id eq inventoryId) and (PastovykleInventory.pastovykleId eq pastovykleId) }) {
                request.quantityReturned?.let { v -> it[quantityReturned] = v }
                returnedAt?.let { v -> it[PastovykleInventory.returnedAt] = v }
                request.notes?.let { v -> it[notes] = v }
            }

            val updated = PastovykleInventory.selectAll().where { PastovykleInventory.id eq inventoryId }.first()
            Result.success(toInventoryResponse(updated))
        }
    }

    fun removeInventoryAssignment(
        eventId: UUID,
        pastovykleId: UUID,
        inventoryId: UUID,
        tuntasId: UUID
    ): Result<Unit> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            Pastovykles.selectAll()
                .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            val existing = PastovykleInventory.selectAll()
                .where { (PastovykleInventory.id eq inventoryId) and (PastovykleInventory.pastovykleId eq pastovykleId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Inventory assignment not found"))

            if (existing[PastovykleInventory.quantityReturned] != 0) {
                return@transaction Result.failure(Exception("Cannot remove assignment with returned items"))
            }

            PastovykleInventory.deleteWhere { (PastovykleInventory.id eq inventoryId) and (PastovykleInventory.pastovykleId eq pastovykleId) }
            Result.success(Unit)
        }
    }

    fun getEventInventoryPlan(eventId: UUID, tuntasId: UUID): Result<EventInventoryPlanResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            Result.success(toEventInventoryPlanResponse(eventId))
        }
    }

    fun createInventoryBucket(
        eventId: UUID,
        tuntasId: UUID,
        request: CreateEventInventoryBucketRequest
    ): Result<EventInventoryBucketResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (request.name.isBlank()) return@transaction Result.failure(Exception("Name cannot be blank"))
            if (request.type !in validBucketTypes) return@transaction Result.failure(Exception("Invalid bucket type"))

            val pastovykleUUID = request.pastovykleId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid pastovykle ID"))
                }
            }
            if (request.type == "PASTOVYKLE" && pastovykleUUID == null) {
                return@transaction Result.failure(Exception("PASTOVYKLE bucket requires pastovykleId"))
            }
            pastovykleUUID?.let {
                Pastovykles.selectAll()
                    .where { (Pastovykles.id eq it) and (Pastovykles.eventId eq eventId) }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Pastovykle not found"))
            }

            val id = EventInventoryBuckets.insert {
                it[this.eventId] = eventId
                it[name] = request.name.trim()
                it[type] = request.type
                it[pastovykleId] = pastovykleUUID
                it[notes] = request.notes
            } get EventInventoryBuckets.id

            Result.success(toBucketResponse(EventInventoryBuckets.selectAll().where { EventInventoryBuckets.id eq id }.first()))
        }
    }

    fun updateInventoryBucket(
        eventId: UUID,
        bucketId: UUID,
        tuntasId: UUID,
        request: UpdateEventInventoryBucketRequest
    ): Result<EventInventoryBucketResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            EventInventoryBuckets.selectAll()
                .where { (EventInventoryBuckets.id eq bucketId) and (EventInventoryBuckets.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Bucket not found"))

            request.type?.let {
                if (it !in validBucketTypes) return@transaction Result.failure(Exception("Invalid bucket type"))
            }
            val pastovykleUUID = request.pastovykleId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid pastovykle ID"))
                }
            }
            pastovykleUUID?.let {
                Pastovykles.selectAll()
                    .where { (Pastovykles.id eq it) and (Pastovykles.eventId eq eventId) }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Pastovykle not found"))
            }

            EventInventoryBuckets.update({ (EventInventoryBuckets.id eq bucketId) and (EventInventoryBuckets.eventId eq eventId) }) {
                request.name?.let { v -> it[name] = v.trim() }
                request.type?.let { v -> it[type] = v }
                request.notes?.let { v -> it[notes] = v }
                pastovykleUUID?.let { v -> it[pastovykleId] = v }
            }

            Result.success(toBucketResponse(EventInventoryBuckets.selectAll().where { EventInventoryBuckets.id eq bucketId }.first()))
        }
    }

    fun deleteInventoryBucket(eventId: UUID, bucketId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            EventInventoryBuckets.selectAll()
                .where { (EventInventoryBuckets.id eq bucketId) and (EventInventoryBuckets.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Bucket not found"))
            val hasAllocations = EventInventoryAllocations.selectAll()
                .where { EventInventoryAllocations.bucketId eq bucketId }
                .count() > 0
            if (hasAllocations) return@transaction Result.failure(Exception("Cannot delete bucket with inventory allocations"))
            EventInventoryBuckets.deleteWhere { (EventInventoryBuckets.id eq bucketId) and (EventInventoryBuckets.eventId eq eventId) }
            Result.success(Unit)
        }
    }

    fun createInventoryItem(
        eventId: UUID,
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateEventInventoryItemRequest
    ): Result<EventInventoryItemResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (request.plannedQuantity < 1) return@transaction Result.failure(Exception("Planned quantity must be at least 1"))

            val itemUUID = request.itemId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid item ID"))
                }
            }
            val item = itemUUID?.let {
                Items.selectAll()
                    .where { (Items.id eq it) and (Items.tuntasId eq tuntasId) and (Items.status eq "ACTIVE") }
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Item not found or not active"))
            }
            if (item == null && request.name.isBlank()) return@transaction Result.failure(Exception("Name cannot be blank"))

            val bucketUUID = request.bucketId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid bucket ID"))
                }
            }
            bucketUUID?.let {
                EventInventoryBuckets.selectAll()
                    .where { (EventInventoryBuckets.id eq it) and (EventInventoryBuckets.eventId eq eventId) }
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Bucket not found"))
            }

            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }

            val reservableQuantity = itemUUID?.let {
                availableQuantityForEventItem(it, event[Events.startDate], event[Events.endDate])
                    .coerceAtMost(request.plannedQuantity)
                    .coerceAtLeast(0)
            } ?: 0

            val reservationGroupId = itemUUID?.takeIf { reservableQuantity > 0 }?.let {
                val reservation = ReservationService().createReservation(
                    tuntasId = tuntasId,
                    reservedByUserId = createdByUserId,
                    request = CreateReservationRequest(
                        title = event[Events.name],
                        itemId = it.toString(),
                        quantity = reservableQuantity,
                        startDate = event[Events.startDate].toString(),
                        endDate = event[Events.endDate].toString(),
                        eventId = eventId.toString(),
                        notes = request.notes
                    ),
                    canApproveTopLevel = true
                ).getOrElse { error ->
                    return@transaction Result.failure(Exception(error.message ?: "Failed to reserve inventory"))
                }
                UUID.fromString(reservation.id)
            }
            val available = if (itemUUID != null) reservableQuantity else 0
            val itemName = request.name.ifBlank { item?.get(Items.name).orEmpty() }

            val id = EventInventoryItems.insert {
                it[this.eventId] = eventId
                it[itemId] = itemUUID
                it[bucketId] = bucketUUID
                it[this.reservationGroupId] = reservationGroupId
                it[name] = itemName.trim()
                it[plannedQuantity] = request.plannedQuantity
                it[availableQuantity] = available
                it[needsPurchase] = request.plannedQuantity > available
                it[notes] = request.notes
                it[responsibleUserId] = responsibleUUID
                it[this.createdByUserId] = createdByUserId
                it[createdAt] = kotlinx.datetime.Clock.System.now()
            } get EventInventoryItems.id

            Result.success(toInventoryItemResponse(EventInventoryItems.selectAll().where { EventInventoryItems.id eq id }.first()))
        }
    }

    fun createInventoryItemsBulk(
        eventId: UUID,
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateEventInventoryItemsBulkRequest
    ): Result<EventInventoryItemListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (request.items.isEmpty()) {
                return@transaction Result.failure(Exception("At least one inventory item is required"))
            }
            if (request.items.size > 200) {
                return@transaction Result.failure(Exception("Cannot add more than 200 items at once"))
            }

            val created = request.items.map { line ->
                createInventoryItem(eventId, tuntasId, createdByUserId, line).getOrElse { error ->
                    return@transaction Result.failure(Exception(error.message ?: "Failed to create inventory item"))
                }
            }
            Result.success(EventInventoryItemListResponse(items = created, total = created.size))
        }
    }

    fun updateInventoryItem(
        eventId: UUID,
        inventoryItemId: UUID,
        tuntasId: UUID,
        request: UpdateEventInventoryItemRequest
    ): Result<EventInventoryItemResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val existing = EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))
            request.plannedQuantity?.let {
                if (it < 1) return@transaction Result.failure(Exception("Planned quantity must be at least 1"))
            }
            val nextPlanned = request.plannedQuantity ?: existing[EventInventoryItems.plannedQuantity]
            val available = existing[EventInventoryItems.availableQuantity]
            val bucketUUID = request.bucketId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid bucket ID"))
                }
            }
            bucketUUID?.let {
                EventInventoryBuckets.selectAll()
                    .where { (EventInventoryBuckets.id eq it) and (EventInventoryBuckets.eventId eq eventId) }
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Bucket not found"))
            }
            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }

            EventInventoryItems.update({ (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }) {
                request.name?.let { v -> it[name] = v.trim() }
                request.plannedQuantity?.let { v -> it[plannedQuantity] = v }
                bucketUUID?.let { v -> it[bucketId] = v }
                responsibleUUID?.let { v -> it[responsibleUserId] = v }
                request.notes?.let { v -> it[notes] = v }
                it[needsPurchase] = nextPlanned > available
            }

            Result.success(toInventoryItemResponse(EventInventoryItems.selectAll().where { EventInventoryItems.id eq inventoryItemId }.first()))
        }
    }

    fun deleteInventoryItem(eventId: UUID, inventoryItemId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))
            EventInventoryAllocations.deleteWhere { EventInventoryAllocations.eventInventoryItemId eq inventoryItemId }
            EventInventoryItems.deleteWhere { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
            Result.success(Unit)
        }
    }

    fun createInventoryAllocation(
        eventId: UUID,
        tuntasId: UUID,
        request: CreateEventInventoryAllocationRequest
    ): Result<EventInventoryAllocationResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (request.quantity < 1) return@transaction Result.failure(Exception("Quantity must be at least 1"))
            val itemUUID = try { UUID.fromString(request.eventInventoryItemId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid event inventory item ID"))
            }
            val bucketUUID = try { UUID.fromString(request.bucketId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid bucket ID"))
            }
            EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq itemUUID) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))
            EventInventoryBuckets.selectAll()
                .where { (EventInventoryBuckets.id eq bucketUUID) and (EventInventoryBuckets.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Bucket not found"))

            val id = EventInventoryAllocations.insert {
                it[eventInventoryItemId] = itemUUID
                it[bucketId] = bucketUUID
                it[quantity] = request.quantity
                it[notes] = request.notes
            } get EventInventoryAllocations.id

            Result.success(toAllocationResponse(EventInventoryAllocations.selectAll().where { EventInventoryAllocations.id eq id }.first()))
        }
    }

    fun updateInventoryAllocation(
        eventId: UUID,
        allocationId: UUID,
        tuntasId: UUID,
        request: UpdateEventInventoryAllocationRequest
    ): Result<EventInventoryAllocationResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val existing = EventInventoryAllocations
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where { (EventInventoryAllocations.id eq allocationId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Allocation not found"))
            request.quantity?.let {
                if (it < 1) return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            EventInventoryAllocations.update({ EventInventoryAllocations.id eq allocationId }) {
                request.quantity?.let { v -> it[quantity] = v }
                request.notes?.let { v -> it[notes] = v }
            }

            Result.success(toAllocationResponse(EventInventoryAllocations.selectAll().where { EventInventoryAllocations.id eq existing[EventInventoryAllocations.id] }.first()))
        }
    }

    fun deleteInventoryAllocation(eventId: UUID, allocationId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            EventInventoryAllocations
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where { (EventInventoryAllocations.id eq allocationId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Allocation not found"))
            EventInventoryAllocations.deleteWhere { EventInventoryAllocations.id eq allocationId }
            Result.success(Unit)
        }
    }

    fun getPurchases(eventId: UUID, tuntasId: UUID): Result<EventPurchaseListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val purchases = EventPurchases.selectAll()
                .where { EventPurchases.eventId eq eventId }
                .orderBy(EventPurchases.createdAt, SortOrder.DESC)
                .map { toPurchaseResponse(it) }
            Result.success(EventPurchaseListResponse(purchases = purchases, total = purchases.size))
        }
    }

    fun createPurchase(
        eventId: UUID,
        tuntasId: UUID,
        purchasedByUserId: UUID,
        request: CreateEventPurchaseRequest
    ): Result<EventPurchaseResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (request.items.isEmpty()) return@transaction Result.failure(Exception("Purchase must include at least one item"))

            val purchaseDate = request.purchaseDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }
            val now = kotlinx.datetime.Clock.System.now()
            val purchaseId = EventPurchases.insert {
                it[this.eventId] = eventId
                it[this.purchasedByUserId] = purchasedByUserId
                it[status] = "DRAFT"
                it[this.purchaseDate] = purchaseDate
                it[notes] = request.notes
                it[createdAt] = now
                it[updatedAt] = now
            } get EventPurchases.id

            request.items.forEach { line ->
                if (line.purchasedQuantity < 1) {
                    return@transaction Result.failure(Exception("Purchased quantity must be at least 1"))
                }
                val inventoryItemId = try { UUID.fromString(line.eventInventoryItemId) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid event inventory item ID"))
                }
                val inventoryItem = EventInventoryItems.selectAll()
                    .where { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Inventory item not found"))
                val shortage = (inventoryItem[EventInventoryItems.plannedQuantity] - inventoryItem[EventInventoryItems.availableQuantity]).coerceAtLeast(0)
                if (shortage == 0) {
                    return@transaction Result.failure(Exception("Inventory item has no shortage to purchase"))
                }

                EventPurchaseItems.insert {
                    it[this.purchaseId] = purchaseId
                    it[eventInventoryItemId] = inventoryItemId
                    it[purchasedQuantity] = line.purchasedQuantity
                    it[unitPrice] = line.unitPrice?.toBigDecimal()
                    it[notes] = line.notes
                    it[addedToInventory] = false
                }
            }
            recalculatePurchaseTotal(purchaseId)
            Result.success(toPurchaseResponse(EventPurchases.selectAll().where { EventPurchases.id eq purchaseId }.first()))
        }
    }

    fun updatePurchase(
        eventId: UUID,
        purchaseId: UUID,
        tuntasId: UUID,
        request: UpdateEventPurchaseRequest
    ): Result<EventPurchaseResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))

            request.status?.let {
                if (it !in validPurchaseStatuses) return@transaction Result.failure(Exception("Invalid purchase status"))
            }
            val purchaseDate = request.purchaseDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }

            EventPurchases.update({ (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }) {
                request.status?.let { v -> it[status] = v }
                purchaseDate?.let { v -> it[EventPurchases.purchaseDate] = v }
                request.totalAmount?.let { v -> it[totalAmount] = v.toBigDecimal() }
                request.invoiceFileUrl?.let { v -> it[invoiceFileUrl] = v }
                request.notes?.let { v -> it[notes] = v }
            }
            Result.success(toPurchaseResponse(EventPurchases.selectAll().where { EventPurchases.id eq purchaseId }.first()))
        }
    }

    fun attachPurchaseInvoice(
        eventId: UUID,
        purchaseId: UUID,
        tuntasId: UUID,
        request: AttachEventPurchaseInvoiceRequest
    ): Result<EventPurchaseResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            if (request.invoiceFileUrl.isBlank()) {
                return@transaction Result.failure(Exception("Invoice file URL cannot be blank"))
            }
            EventPurchases.update({ (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }) {
                it[invoiceFileUrl] = request.invoiceFileUrl
            }
            Result.success(toPurchaseResponse(EventPurchases.selectAll().where { EventPurchases.id eq purchaseId }.first()))
        }
    }

    fun getPurchaseInvoiceFileName(eventId: UUID, purchaseId: UUID, tuntasId: UUID): Result<String> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val purchase = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            val invoiceUrl = purchase[EventPurchases.invoiceFileUrl]
                ?: return@transaction Result.failure(Exception("Invoice not attached"))
            val prefix = "/uploads/documents/"
            if (!invoiceUrl.startsWith(prefix)) {
                return@transaction Result.failure(Exception("Invoice file URL is not downloadable"))
            }
            val fileName = invoiceUrl.removePrefix(prefix)
            if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
                return@transaction Result.failure(Exception("Invalid invoice file name"))
            }
            Result.success(fileName)
        }
    }

    fun completePurchase(eventId: UUID, purchaseId: UUID, tuntasId: UUID): Result<EventPurchaseResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val purchase = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            if (purchase[EventPurchases.status] == "CANCELLED") {
                return@transaction Result.failure(Exception("Cancelled purchase cannot be completed"))
            }

            EventPurchaseItems
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where { EventPurchaseItems.purchaseId eq purchaseId }
                .forEach { row ->
                    val nextAvailable = row[EventInventoryItems.availableQuantity] + row[EventPurchaseItems.purchasedQuantity]
                    val planned = row[EventInventoryItems.plannedQuantity]
                    EventInventoryItems.update({ EventInventoryItems.id eq row[EventInventoryItems.id] }) {
                        it[availableQuantity] = nextAvailable
                        it[needsPurchase] = planned > nextAvailable
                    }
                }

            EventPurchases.update({ EventPurchases.id eq purchaseId }) {
                it[status] = "PURCHASED"
                if (purchase[EventPurchases.purchaseDate] == null) {
                    it[purchaseDate] = kotlinx.datetime.Clock.System.now()
                        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                        .date
                }
            }
            recalculatePurchaseTotal(purchaseId)
            Result.success(toPurchaseResponse(EventPurchases.selectAll().where { EventPurchases.id eq purchaseId }.first()))
        }
    }

    fun addPurchaseToInventory(
        eventId: UUID,
        purchaseId: UUID,
        tuntasId: UUID,
        userId: UUID
    ): Result<EventPurchaseResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val purchase = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            if (purchase[EventPurchases.status] !in listOf("PURCHASED", "ADDED_TO_INVENTORY")) {
                return@transaction Result.failure(Exception("Purchase must be completed before adding to inventory"))
            }

            val purchaseLines = EventPurchaseItems
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where { EventPurchaseItems.purchaseId eq purchaseId }
                .toList()

            purchaseLines.filter { !it[EventPurchaseItems.addedToInventory] }.forEach { line ->
                val sourceItem = line[EventInventoryItems.itemId]?.let { sourceId ->
                    Items.selectAll().where { (Items.id eq sourceId) and (Items.tuntasId eq tuntasId) }.firstOrNull()
                }
                val unitPrice = line[EventPurchaseItems.unitPrice]
                val itemId = Items.insert {
                    it[this.tuntasId] = tuntasId
                    it[custodianId] = sourceItem?.get(Items.custodianId)
                    it[origin] = "UNIT_ACQUIRED"
                    it[name] = line[EventInventoryItems.name]
                    it[description] = line[EventInventoryItems.notes]
                    it[type] = sourceItem?.get(Items.type) ?: "COLLECTIVE"
                    it[category] = sourceItem?.get(Items.category) ?: "TOOLS"
                    it[condition] = "GOOD"
                    it[quantity] = line[EventPurchaseItems.purchasedQuantity]
                    it[locationId] = sourceItem?.get(Items.locationId)
                    it[temporaryStorageLabel] = "Renginio pirkimas"
                    it[responsibleUserId] = purchase[EventPurchases.purchasedByUserId]
                    it[createdByUserId] = userId
                    it[purchaseDate] = purchase[EventPurchases.purchaseDate]
                    it[purchasePrice] = unitPrice
                    it[notes] = buildString {
                        append("Sukurta is renginio pirkimo.")
                        purchase[EventPurchases.invoiceFileUrl]?.let { invoice -> append(" Saskaita: $invoice") }
                        line[EventPurchaseItems.notes]?.let { note -> append(" $note") }
                    }
                    it[status] = "ACTIVE"
                } get Items.id

                purchase[EventPurchases.invoiceFileUrl]?.let { invoice ->
                    ItemAttachments.insert {
                        it[this.itemId] = itemId
                        it[fileUrl] = invoice
                        it[fileType] = "INVOICE"
                        it[uploadedByUserId] = userId
                        it[uploadedAt] = kotlinx.datetime.Clock.System.now()
                    }
                }

                EventPurchaseItems.update({ EventPurchaseItems.id eq line[EventPurchaseItems.id] }) {
                    it[addedToInventory] = true
                    it[addedToInventoryItemId] = itemId
                }
            }

            EventPurchases.update({ EventPurchases.id eq purchaseId }) {
                it[status] = "ADDED_TO_INVENTORY"
            }
            Result.success(toPurchaseResponse(EventPurchases.selectAll().where { EventPurchases.id eq purchaseId }.first()))
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
            stovyklaDetails = stovyklaDetails,
            inventorySummary = toInventorySummary(eventId)
        )
    }

    private fun toEventRoleResponse(row: ResultRow): EventRoleResponse {
        val userName = Users.selectAll()
            .where { Users.id eq row[EventRoles.userId] }
            .firstOrNull()
            ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
        return EventRoleResponse(
            id = row[EventRoles.id].toString(),
            userId = row[EventRoles.userId].toString(),
            userName = userName,
            role = row[EventRoles.role],
            targetGroup = row[EventRoles.targetGroup],
            assignedByUserId = row[EventRoles.assignedByUserId]?.toString(),
            assignedAt = row[EventRoles.assignedAt].toString()
        )
    }

    private fun ensureEvent(eventId: UUID, tuntasId: UUID): ResultRow? {
        return Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull()
    }

    private fun createDefaultBuckets(eventId: UUID) {
        listOf(
            "Programa" to "PROGRAM",
            "Virtuve" to "KITCHEN",
            "Komendantura" to "ADMIN",
            "Medicina" to "MEDICAL",
            "Kita" to "OTHER"
        ).forEach { (name, type) ->
            EventInventoryBuckets.insert {
                it[this.eventId] = eventId
                it[this.name] = name
                it[this.type] = type
            }
        }
    }

    private fun toEventInventoryPlanResponse(eventId: UUID): EventInventoryPlanResponse {
        val buckets = EventInventoryBuckets.selectAll()
            .where { EventInventoryBuckets.eventId eq eventId }
            .map { toBucketResponse(it) }
        val items = EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .map { toInventoryItemResponse(it) }
        val allocations = EventInventoryAllocations
            .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
            .selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .map { toAllocationResponse(it) }
        return EventInventoryPlanResponse(buckets = buckets, items = items, allocations = allocations)
    }

    private fun toBucketResponse(row: ResultRow): EventInventoryBucketResponse {
        val pastovykleId = row[EventInventoryBuckets.pastovykleId]
        val pastovykleName = pastovykleId?.let {
            Pastovykles.selectAll().where { Pastovykles.id eq it }.firstOrNull()?.get(Pastovykles.name)
        }
        return EventInventoryBucketResponse(
            id = row[EventInventoryBuckets.id].toString(),
            eventId = row[EventInventoryBuckets.eventId].toString(),
            name = row[EventInventoryBuckets.name],
            type = row[EventInventoryBuckets.type],
            pastovykleId = pastovykleId?.toString(),
            pastovykleName = pastovykleName,
            notes = row[EventInventoryBuckets.notes]
        )
    }

    private fun toInventoryItemResponse(row: ResultRow): EventInventoryItemResponse {
        val allocated = EventInventoryAllocations.selectAll()
            .where { EventInventoryAllocations.eventInventoryItemId eq row[EventInventoryItems.id] }
            .sumOf { it[EventInventoryAllocations.quantity] }
        val planned = row[EventInventoryItems.plannedQuantity]
        val available = row[EventInventoryItems.availableQuantity]
        val bucket = row[EventInventoryItems.bucketId]?.let { bucketId ->
            EventInventoryBuckets.selectAll()
                .where { EventInventoryBuckets.id eq bucketId }
                .firstOrNull()
        }
        val responsible = row[EventInventoryItems.responsibleUserId]?.let { userId ->
            Users.selectAll()
                .where { Users.id eq userId }
                .firstOrNull()
        }
        return EventInventoryItemResponse(
            id = row[EventInventoryItems.id].toString(),
            eventId = row[EventInventoryItems.eventId].toString(),
            itemId = row[EventInventoryItems.itemId]?.toString(),
            bucketId = row[EventInventoryItems.bucketId]?.toString(),
            bucketName = bucket?.get(EventInventoryBuckets.name),
            reservationGroupId = row[EventInventoryItems.reservationGroupId]?.toString(),
            name = row[EventInventoryItems.name],
            plannedQuantity = planned,
            availableQuantity = available,
            shortageQuantity = (planned - available).coerceAtLeast(0),
            allocatedQuantity = allocated,
            unallocatedQuantity = (planned - allocated).coerceAtLeast(0),
            needsPurchase = row[EventInventoryItems.needsPurchase],
            notes = row[EventInventoryItems.notes],
            responsibleUserId = row[EventInventoryItems.responsibleUserId]?.toString(),
            responsibleUserName = responsible?.let { "${it[Users.name]} ${it[Users.surname]}".trim() },
            createdByUserId = row[EventInventoryItems.createdByUserId]?.toString(),
            createdAt = row[EventInventoryItems.createdAt].toString()
        )
    }

    private fun availableQuantityForEventItem(
        itemId: UUID,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): Int {
        val item = Items.selectAll()
            .where { (Items.id eq itemId) and (Items.status eq "ACTIVE") }
            .firstOrNull() ?: return 0
        val reserved = Reservations.select(Reservations.quantity)
            .where {
                (Reservations.itemId eq itemId) and
                    (Reservations.status inList listOf("APPROVED", "ACTIVE")) and
                    (Reservations.startDate lessEq endDate) and
                    (Reservations.endDate greaterEq startDate)
            }
            .sumOf { it[Reservations.quantity] }
        return (item[Items.quantity] - reserved).coerceAtLeast(0)
    }

    private fun toAllocationResponse(row: ResultRow): EventInventoryAllocationResponse {
        val bucket = EventInventoryBuckets.selectAll()
            .where { EventInventoryBuckets.id eq row[EventInventoryAllocations.bucketId] }
            .first()
        return EventInventoryAllocationResponse(
            id = row[EventInventoryAllocations.id].toString(),
            eventInventoryItemId = row[EventInventoryAllocations.eventInventoryItemId].toString(),
            bucketId = row[EventInventoryAllocations.bucketId].toString(),
            bucketName = bucket[EventInventoryBuckets.name],
            quantity = row[EventInventoryAllocations.quantity],
            notes = row[EventInventoryAllocations.notes]
        )
    }

    private fun toPurchaseResponse(row: ResultRow): EventPurchaseResponse {
        val user = row[EventPurchases.purchasedByUserId]?.let { userId ->
            Users.selectAll().where { Users.id eq userId }.firstOrNull()
        }
        val items = EventPurchaseItems.selectAll()
            .where { EventPurchaseItems.purchaseId eq row[EventPurchases.id] }
            .map { toPurchaseItemResponse(it) }
        return EventPurchaseResponse(
            id = row[EventPurchases.id].toString(),
            eventId = row[EventPurchases.eventId].toString(),
            purchasedByUserId = row[EventPurchases.purchasedByUserId]?.toString(),
            purchasedByName = user?.let { "${it[Users.name]} ${it[Users.surname]}".trim() },
            status = row[EventPurchases.status],
            purchaseDate = row[EventPurchases.purchaseDate]?.toString(),
            totalAmount = row[EventPurchases.totalAmount]?.toDouble(),
            invoiceFileUrl = row[EventPurchases.invoiceFileUrl],
            notes = row[EventPurchases.notes],
            createdAt = row[EventPurchases.createdAt].toString(),
            updatedAt = row[EventPurchases.updatedAt].toString(),
            items = items
        )
    }

    private fun toPurchaseItemResponse(row: ResultRow): EventPurchaseItemResponse {
        val inventoryItem = EventInventoryItems.selectAll()
            .where { EventInventoryItems.id eq row[EventPurchaseItems.eventInventoryItemId] }
            .first()
        val unitPrice = row[EventPurchaseItems.unitPrice]
        return EventPurchaseItemResponse(
            id = row[EventPurchaseItems.id].toString(),
            purchaseId = row[EventPurchaseItems.purchaseId].toString(),
            eventInventoryItemId = row[EventPurchaseItems.eventInventoryItemId].toString(),
            itemName = inventoryItem[EventInventoryItems.name],
            purchasedQuantity = row[EventPurchaseItems.purchasedQuantity],
            unitPrice = unitPrice?.toDouble(),
            lineTotal = unitPrice?.multiply(BigDecimal(row[EventPurchaseItems.purchasedQuantity]))?.toDouble(),
            addedToInventory = row[EventPurchaseItems.addedToInventory],
            addedToInventoryItemId = row[EventPurchaseItems.addedToInventoryItemId]?.toString(),
            notes = row[EventPurchaseItems.notes]
        )
    }

    private fun recalculatePurchaseTotal(purchaseId: UUID) {
        val total = EventPurchaseItems.selectAll()
            .where { EventPurchaseItems.purchaseId eq purchaseId }
            .mapNotNull { row ->
                row[EventPurchaseItems.unitPrice]?.multiply(BigDecimal(row[EventPurchaseItems.purchasedQuantity]))
            }
            .fold(BigDecimal.ZERO) { acc, value -> acc + value }
        EventPurchases.update({ EventPurchases.id eq purchaseId }) {
            it[totalAmount] = total
        }
    }

    private fun toInventorySummary(eventId: UUID): EventInventorySummaryResponse {
        val items = EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .toList()
        val itemIds = items.map { it[EventInventoryItems.id] }
        val allocated = if (itemIds.isEmpty()) 0 else EventInventoryAllocations.selectAll()
            .where { EventInventoryAllocations.eventInventoryItemId inList itemIds }
            .sumOf { it[EventInventoryAllocations.quantity] }
        return EventInventorySummaryResponse(
            totalPlannedQuantity = items.sumOf { it[EventInventoryItems.plannedQuantity] },
            totalAvailableQuantity = items.sumOf { it[EventInventoryItems.availableQuantity] },
            totalShortageQuantity = items.sumOf {
                (it[EventInventoryItems.plannedQuantity] - it[EventInventoryItems.availableQuantity]).coerceAtLeast(0)
            },
            totalAllocatedQuantity = allocated,
            itemsNeedingPurchase = items.count { it[EventInventoryItems.needsPurchase] }
        )
    }
}
