package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.*
import lt.skautai.models.responses.*
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
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
    private val validInventoryRequestStatuses = listOf(
        "PENDING",
        "APPROVED",
        "REJECTED",
        "FULFILLED",
        "SELF_PROVIDED"
    )
    private val validInventoryMovementTypes = listOf(
        "PASTOVYKLE_REQUEST",
        "ASSIGN_TO_PASTOVYKLE",
        "CHECKOUT_TO_PERSON",
        "RETURN_TO_PASTOVYKLE",
        "RETURN_TO_EVENT_STORAGE",
        "TRANSFER"
    )

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

            EventPurchases.selectAll()
                .where { EventPurchases.eventId eq eventId }
                .mapNotNull { it[EventPurchases.invoiceFileUrl] }
                .forEach { deleteManagedDocument(it) }

            EventInventoryItems.select(EventInventoryItems.reservationGroupId)
                .where { EventInventoryItems.eventId eq eventId }
                .mapNotNull { it[EventInventoryItems.reservationGroupId] }
                .distinct()
                .forEach { cancelReservationGroup(it) }

            EventPurchases.update({
                (EventPurchases.eventId eq eventId) and
                    (EventPurchases.status inList listOf("DRAFT", "PURCHASED"))
            }) {
                it[status] = "CANCELLED"
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

    fun isPastovykleResponsible(eventId: UUID, pastovykleId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        Pastovykles.selectAll()
            .where {
                (Pastovykles.id eq pastovykleId) and
                    (Pastovykles.eventId eq eventId) and
                    (Pastovykles.responsibleUserId eq userId)
            }
            .firstOrNull() != null && verifyStovyklaEvent(eventId, tuntasId) != null
    }

    private fun ensurePastovykle(eventId: UUID, pastovykleId: UUID): ResultRow? {
        return Pastovykles.selectAll()
            .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
            .firstOrNull()
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

    private fun toInventoryRequestResponse(row: ResultRow): EventInventoryRequestResponse {
        val inventoryItem = EventInventoryItems.selectAll()
            .where { EventInventoryItems.id eq row[EventInventoryRequests.eventInventoryItemId] }
            .first()
        val pastovykle = Pastovykles.selectAll()
            .where { Pastovykles.id eq row[EventInventoryRequests.pastovykleId] }
            .first()

        fun userName(id: UUID?): String? = id?.let {
            Users.selectAll()
                .where { Users.id eq it }
                .firstOrNull()
                ?.let { user -> "${user[Users.name]} ${user[Users.surname]}".trim() }
        }

        return EventInventoryRequestResponse(
            id = row[EventInventoryRequests.id].toString(),
            eventId = row[EventInventoryRequests.eventId].toString(),
            eventInventoryItemId = row[EventInventoryRequests.eventInventoryItemId].toString(),
            itemId = inventoryItem[EventInventoryItems.itemId]?.toString(),
            itemName = inventoryItem[EventInventoryItems.name],
            pastovykleId = row[EventInventoryRequests.pastovykleId].toString(),
            pastovykleName = pastovykle[Pastovykles.name],
            requestedByUserId = row[EventInventoryRequests.requestedByUserId].toString(),
            requestedByName = userName(row[EventInventoryRequests.requestedByUserId]),
            quantity = row[EventInventoryRequests.quantity],
            status = row[EventInventoryRequests.status],
            notes = row[EventInventoryRequests.notes],
            createdAt = row[EventInventoryRequests.createdAt].toString(),
            reviewedAt = row[EventInventoryRequests.reviewedAt]?.toString(),
            reviewedByUserId = row[EventInventoryRequests.reviewedByUserId]?.toString(),
            reviewedByUserName = userName(row[EventInventoryRequests.reviewedByUserId]),
            fulfilledAt = row[EventInventoryRequests.fulfilledAt]?.toString(),
            resolvedByUserId = row[EventInventoryRequests.resolvedByUserId]?.toString(),
            resolvedByUserName = userName(row[EventInventoryRequests.resolvedByUserId])
        )
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

    fun getPastovykleRequests(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID
    ): Result<EventInventoryRequestListResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovykle not found"))

            val requests = EventInventoryRequests.selectAll()
                .where {
                    (EventInventoryRequests.eventId eq eventId) and
                        (EventInventoryRequests.pastovykleId eq pastovykleId)
                }
                .orderBy(EventInventoryRequests.createdAt, SortOrder.DESC)
                .map { toInventoryRequestResponse(it) }

            Result.success(EventInventoryRequestListResponse(requests = requests, total = requests.size))
        }
    }

    fun createPastovykleRequest(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID,
        requestedByUserId: UUID,
        request: CreatePastovykleInventoryRequestRequest
    ): Result<EventInventoryRequestResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovykle not found"))
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val eventInventoryItemId = try {
                UUID.fromString(request.eventInventoryItemId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid event inventory item ID"))
            }

            EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq eventInventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Inventory item not found"))

            val requestId = EventInventoryRequests.insert {
                it[this.eventId] = eventId
                it[this.eventInventoryItemId] = eventInventoryItemId
                it[this.pastovykleId] = pastovykleId
                it[this.requestedByUserId] = requestedByUserId
                it[quantity] = request.quantity
                it[status] = "PENDING"
                it[notes] = request.notes
                it[createdAt] = kotlinx.datetime.Clock.System.now()
            } get EventInventoryRequests.id

            Result.success(
                toInventoryRequestResponse(
                    EventInventoryRequests.selectAll()
                        .where { EventInventoryRequests.id eq requestId }
                        .first()
                )
            )
        }
    }

    fun approvePastovykleRequest(
        eventId: UUID,
        pastovykleId: UUID,
        requestId: UUID,
        tuntasId: UUID,
        reviewedByUserId: UUID
    ): Result<EventInventoryRequestResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            val existing = EventInventoryRequests.selectAll()
                .where {
                    (EventInventoryRequests.id eq requestId) and
                        (EventInventoryRequests.eventId eq eventId) and
                        (EventInventoryRequests.pastovykleId eq pastovykleId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[EventInventoryRequests.status] != "PENDING") {
                return@transaction Result.failure(Exception("Only pending requests can be approved"))
            }

            val now = kotlinx.datetime.Clock.System.now()
            EventInventoryRequests.update({ EventInventoryRequests.id eq requestId }) {
                it[status] = "APPROVED"
                it[EventInventoryRequests.reviewedByUserId] = reviewedByUserId
                it[EventInventoryRequests.reviewedAt] = now
            }

            Result.success(
                toInventoryRequestResponse(
                    EventInventoryRequests.selectAll().where { EventInventoryRequests.id eq requestId }.first()
                )
            )
        }
    }

    fun rejectPastovykleRequest(
        eventId: UUID,
        pastovykleId: UUID,
        requestId: UUID,
        tuntasId: UUID,
        reviewedByUserId: UUID
    ): Result<EventInventoryRequestResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            val existing = EventInventoryRequests.selectAll()
                .where {
                    (EventInventoryRequests.id eq requestId) and
                        (EventInventoryRequests.eventId eq eventId) and
                        (EventInventoryRequests.pastovykleId eq pastovykleId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[EventInventoryRequests.status] !in listOf("PENDING", "APPROVED")) {
                return@transaction Result.failure(Exception("Only pending or approved requests can be rejected"))
            }

            val now = kotlinx.datetime.Clock.System.now()
            EventInventoryRequests.update({ EventInventoryRequests.id eq requestId }) {
                it[status] = "REJECTED"
                it[EventInventoryRequests.reviewedByUserId] = reviewedByUserId
                it[EventInventoryRequests.resolvedByUserId] = reviewedByUserId
                it[EventInventoryRequests.reviewedAt] = now
                it[EventInventoryRequests.fulfilledAt] = null
            }

            Result.success(
                toInventoryRequestResponse(
                    EventInventoryRequests.selectAll().where { EventInventoryRequests.id eq requestId }.first()
                )
            )
        }
    }

    fun markPastovykleRequestSelfProvided(
        eventId: UUID,
        pastovykleId: UUID,
        requestId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: MarkPastovykleInventoryRequestSelfProvidedRequest
    ): Result<EventInventoryRequestResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            val existing = EventInventoryRequests.selectAll()
                .where {
                    (EventInventoryRequests.id eq requestId) and
                        (EventInventoryRequests.eventId eq eventId) and
                        (EventInventoryRequests.pastovykleId eq pastovykleId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[EventInventoryRequests.status] in listOf("FULFILLED", "REJECTED", "SELF_PROVIDED")) {
                return@transaction Result.failure(Exception("Request is already closed"))
            }

            val now = kotlinx.datetime.Clock.System.now()
            EventInventoryRequests.update({ EventInventoryRequests.id eq requestId }) {
                it[status] = "SELF_PROVIDED"
                it[EventInventoryRequests.resolvedByUserId] = userId
                it[EventInventoryRequests.reviewedAt] = existing[EventInventoryRequests.reviewedAt] ?: now
                it[EventInventoryRequests.notes] = request.notes ?: existing[EventInventoryRequests.notes]
            }

            Result.success(
                toInventoryRequestResponse(
                    EventInventoryRequests.selectAll().where { EventInventoryRequests.id eq requestId }.first()
                )
            )
        }
    }

    fun fulfillPastovykleRequest(
        eventId: UUID,
        pastovykleId: UUID,
        requestId: UUID,
        tuntasId: UUID,
        fulfilledByUserId: UUID,
        request: FulfillPastovykleInventoryRequestRequest
    ): Result<EventInventoryRequestResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            val existing = EventInventoryRequests.selectAll()
                .where {
                    (EventInventoryRequests.id eq requestId) and
                        (EventInventoryRequests.eventId eq eventId) and
                        (EventInventoryRequests.pastovykleId eq pastovykleId)
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Request not found"))

            if (existing[EventInventoryRequests.status] !in listOf("PENDING", "APPROVED")) {
                return@transaction Result.failure(Exception("Only pending or approved requests can be fulfilled"))
            }

            val inventoryItem = EventInventoryItems.selectAll()
                .where { EventInventoryItems.id eq existing[EventInventoryRequests.eventInventoryItemId] }
                .first()
            val quantity = request.quantity ?: existing[EventInventoryRequests.quantity]
            if (quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val available = eventStorageAvailable(
                existing[EventInventoryRequests.eventInventoryItemId],
                inventoryItem[EventInventoryItems.availableQuantity]
            )
            if (quantity > available) {
                return@transaction Result.failure(Exception("Not enough event storage quantity. Available: $available"))
            }

            val now = kotlinx.datetime.Clock.System.now()
            val custodyId = insertCustody(
                eventInventoryItemId = existing[EventInventoryRequests.eventInventoryItemId],
                parentCustodyId = null,
                pastovykleId = pastovykleId,
                holderUserId = null,
                quantity = quantity,
                createdByUserId = fulfilledByUserId,
                notes = request.notes ?: existing[EventInventoryRequests.notes],
                createdAt = now
            )

            insertInventoryMovement(
                eventId = eventId,
                eventInventoryItemId = existing[EventInventoryRequests.eventInventoryItemId],
                custodyId = custodyId,
                inventoryRequestId = requestId,
                movementType = "ASSIGN_TO_PASTOVYKLE",
                quantity = quantity,
                fromPastovykleId = null,
                toPastovykleId = pastovykleId,
                fromUserId = null,
                toUserId = null,
                performedByUserId = fulfilledByUserId,
                clientRequestId = null,
                notes = request.notes ?: existing[EventInventoryRequests.notes],
                createdAt = now
            )

            inventoryItem[EventInventoryItems.itemId]?.let { sourceItemId ->
                PastovykleInventory.insert {
                    it[this.pastovykleId] = pastovykleId
                    it[itemId] = sourceItemId
                    it[distributedByUserId] = fulfilledByUserId
                    it[quantityAssigned] = quantity
                    it[quantityReturned] = 0
                    it[assignedAt] = now
                    it[notes] = request.notes ?: existing[EventInventoryRequests.notes]
                }
            }

            EventInventoryRequests.update({ EventInventoryRequests.id eq requestId }) {
                it[status] = "FULFILLED"
                it[EventInventoryRequests.reviewedByUserId] = fulfilledByUserId
                it[EventInventoryRequests.resolvedByUserId] = fulfilledByUserId
                it[EventInventoryRequests.reviewedAt] = existing[EventInventoryRequests.reviewedAt] ?: now
                it[EventInventoryRequests.fulfilledAt] = now
                it[EventInventoryRequests.notes] = request.notes ?: existing[EventInventoryRequests.notes]
            }

            Result.success(
                toInventoryRequestResponse(
                    EventInventoryRequests.selectAll().where { EventInventoryRequests.id eq requestId }.first()
                )
            )
        }
    }

    fun assignUnitInventoryToPastovykle(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: AssignUnitInventoryToPastovykleRequest
    ): Result<PastovykleInventoryResponse> {
        return transaction {
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            val pastovykle = ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovykle not found"))
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val sourceItemId = try {
                UUID.fromString(request.itemId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid item ID"))
            }

            val sourceItem = Items.selectAll()
                .where {
                    (Items.id eq sourceItemId) and
                        (Items.tuntasId eq tuntasId) and
                        (Items.status eq "ACTIVE")
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found or not active"))

            val sourceCustodianId = sourceItem[Items.custodianId]
                ?: return@transaction Result.failure(Exception("Only unit inventory items can be assigned directly to a pastovykle"))

            val bucket = EventInventoryBuckets.selectAll()
                .where {
                    (EventInventoryBuckets.eventId eq eventId) and
                        (EventInventoryBuckets.type eq "PASTOVYKLE") and
                        (EventInventoryBuckets.pastovykleId eq pastovykleId)
                }
                .firstOrNull()
                ?: run {
                    val bucketId = EventInventoryBuckets.insert {
                        it[this.eventId] = eventId
                        it[name] = pastovykle[Pastovykles.name]
                        it[type] = "PASTOVYKLE"
                        it[this.pastovykleId] = pastovykleId
                        it[notes] = "Automatiškai sukurta pastovyklės atsivežtam inventoriui"
                    } get EventInventoryBuckets.id
                    EventInventoryBuckets.selectAll().where { EventInventoryBuckets.id eq bucketId }.first()
                }

            val reservableQuantity = availableQuantityForEventItem(
                sourceItemId,
                event[Events.startDate],
                event[Events.endDate]
            ).coerceAtMost(request.quantity)

            if (reservableQuantity < request.quantity) {
                return@transaction Result.failure(
                    Exception("Not enough available unit inventory to reserve. Available: $reservableQuantity")
                )
            }

            val matchingEventItem = EventInventoryItems.selectAll()
                .where {
                    (EventInventoryItems.eventId eq eventId) and
                        (EventInventoryItems.itemId eq sourceItemId) and
                        (EventInventoryItems.bucketId eq bucket[EventInventoryBuckets.id])
                }
                .firstOrNull()

            val now = kotlinx.datetime.Clock.System.now()
            val eventInventoryItemId = if (matchingEventItem == null) {
                val reservation = ReservationService().createReservation(
                    tuntasId = tuntasId,
                    reservedByUserId = userId,
                    request = CreateReservationRequest(
                        title = event[Events.name],
                        itemId = sourceItemId.toString(),
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

                EventInventoryItems.insert {
                    it[this.eventId] = eventId
                    it[itemId] = sourceItemId
                    it[bucketId] = bucket[EventInventoryBuckets.id]
                    it[reservationGroupId] = UUID.fromString(reservation.id)
                    it[name] = sourceItem[Items.name]
                    it[plannedQuantity] = request.quantity
                    it[availableQuantity] = reservableQuantity
                    it[needsPurchase] = false
                    it[notes] = request.notes
                    it[responsibleUserId] = userId
                    it[createdByUserId] = userId
                    it[createdAt] = now
                } get EventInventoryItems.id
            } else {
                val reservationGroupId = matchingEventItem[EventInventoryItems.reservationGroupId]
                if (reservationGroupId != null) {
                    val nextQuantity = matchingEventItem[EventInventoryItems.availableQuantity] + request.quantity
                    syncReservationGroupQuantity(reservationGroupId, nextQuantity)
                }
                EventInventoryItems.update({ EventInventoryItems.id eq matchingEventItem[EventInventoryItems.id] }) {
                    it[plannedQuantity] = matchingEventItem[EventInventoryItems.plannedQuantity] + request.quantity
                    it[availableQuantity] = matchingEventItem[EventInventoryItems.availableQuantity] + request.quantity
                    it[needsPurchase] = false
                    it[notes] = request.notes ?: matchingEventItem[EventInventoryItems.notes]
                }
                matchingEventItem[EventInventoryItems.id]
            }

            val existingAllocation = EventInventoryAllocations.selectAll()
                .where {
                    (EventInventoryAllocations.eventInventoryItemId eq eventInventoryItemId) and
                        (EventInventoryAllocations.bucketId eq bucket[EventInventoryBuckets.id])
                }
                .firstOrNull()

            if (existingAllocation == null) {
                EventInventoryAllocations.insert {
                    it[EventInventoryAllocations.eventInventoryItemId] = eventInventoryItemId
                    it[EventInventoryAllocations.bucketId] = bucket[EventInventoryBuckets.id]
                    it[EventInventoryAllocations.quantity] = request.quantity
                    it[EventInventoryAllocations.notes] = request.notes
                }
            } else {
                EventInventoryAllocations.update({ EventInventoryAllocations.id eq existingAllocation[EventInventoryAllocations.id] }) {
                    it[quantity] = existingAllocation[EventInventoryAllocations.quantity] + request.quantity
                    it[notes] = request.notes ?: existingAllocation[EventInventoryAllocations.notes]
                }
            }

            val custodyId = insertCustody(
                eventInventoryItemId = eventInventoryItemId,
                parentCustodyId = null,
                pastovykleId = pastovykleId,
                holderUserId = null,
                quantity = request.quantity,
                createdByUserId = userId,
                notes = request.notes ?: "Atsivežta iš vieneto inventoriaus ${sourceCustodianId}",
                createdAt = now
            )

            insertInventoryMovement(
                eventId = eventId,
                eventInventoryItemId = eventInventoryItemId,
                custodyId = custodyId,
                inventoryRequestId = null,
                movementType = "ASSIGN_TO_PASTOVYKLE",
                quantity = request.quantity,
                fromPastovykleId = null,
                toPastovykleId = pastovykleId,
                fromUserId = null,
                toUserId = null,
                performedByUserId = userId,
                clientRequestId = null,
                notes = request.notes ?: "Atsivežta iš savo vieneto inventoriaus",
                createdAt = now
            )

            val inventoryId = PastovykleInventory.insert {
                it[this.pastovykleId] = pastovykleId
                it[itemId] = sourceItemId
                it[distributedByUserId] = userId
                it[quantityAssigned] = request.quantity
                it[quantityReturned] = 0
                it[assignedAt] = now
                it[notes] = request.notes ?: "Atsivežta iš savo vieneto inventoriaus"
            } get PastovykleInventory.id

            Result.success(
                toInventoryResponse(
                    PastovykleInventory.selectAll().where { PastovykleInventory.id eq inventoryId }.first()
                )
            )
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
            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
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
            locationUUID?.let {
                Locations.selectAll()
                    .where { Locations.id eq it }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Location not found"))
            }

            val id = EventInventoryBuckets.insert {
                it[this.eventId] = eventId
                it[name] = request.name.trim()
                it[type] = request.type
                it[pastovykleId] = pastovykleUUID
                it[locationId] = locationUUID
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
            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
                }
            }
            pastovykleUUID?.let {
                Pastovykles.selectAll()
                    .where { (Pastovykles.id eq it) and (Pastovykles.eventId eq eventId) }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Pastovykle not found"))
            }
            locationUUID?.let {
                Locations.selectAll()
                    .where { Locations.id eq it }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Location not found"))
            }

            EventInventoryBuckets.update({ (EventInventoryBuckets.id eq bucketId) and (EventInventoryBuckets.eventId eq eventId) }) {
                request.name?.let { v -> it[name] = v.trim() }
                request.type?.let { v -> it[type] = v }
                request.notes?.let { v -> it[notes] = v }
                pastovykleUUID?.let { v -> it[pastovykleId] = v }
                locationUUID?.let { v -> it[locationId] = v }
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
            responsibleUUID?.let {
                if (!isActiveTuntasMember(it, tuntasId)) {
                    return@transaction Result.failure(Exception("Responsible user must be a member of this tuntas"))
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val existing = EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))
            request.plannedQuantity?.let {
                if (it < 1) return@transaction Result.failure(Exception("Planned quantity must be at least 1"))
            }
            val nextPlanned = request.plannedQuantity ?: existing[EventInventoryItems.plannedQuantity]
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
            responsibleUUID?.let {
                if (!isActiveTuntasMember(it, tuntasId)) {
                    return@transaction Result.failure(Exception("Responsible user must be a member of this tuntas"))
                }
            }

            val itemId = existing[EventInventoryItems.itemId]
            val reservationGroupId = existing[EventInventoryItems.reservationGroupId]
            val nextAvailable = if (itemId != null) {
                val reservable = availableQuantityForEventItem(
                    itemId,
                    event[Events.startDate],
                    event[Events.endDate],
                    reservationGroupId
                ).coerceAtMost(nextPlanned).coerceAtLeast(0)

                reservationGroupId?.let { syncReservationGroupQuantity(it, reservable) }
                reservable
            } else {
                existing[EventInventoryItems.availableQuantity]
            }

            EventInventoryItems.update({ (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }) {
                request.name?.let { v -> it[name] = v.trim() }
                request.plannedQuantity?.let { v -> it[plannedQuantity] = v }
                bucketUUID?.let { v -> it[bucketId] = v }
                responsibleUUID?.let { v -> it[responsibleUserId] = v }
                request.notes?.let { v -> it[notes] = v }
                if (itemId != null) {
                    it[availableQuantity] = nextAvailable
                }
                it[needsPurchase] = nextPlanned > nextAvailable
            }

            Result.success(toInventoryItemResponse(EventInventoryItems.selectAll().where { EventInventoryItems.id eq inventoryItemId }.first()))
        }
    }

    fun deleteInventoryItem(eventId: UUID, inventoryItemId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val existing = EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))
            existing[EventInventoryItems.reservationGroupId]?.let { cancelReservationGroup(it) }
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

    fun getInventoryCustody(eventId: UUID, tuntasId: UUID): Result<EventInventoryCustodyListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val rows = EventInventoryCustody
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where { EventInventoryItems.eventId eq eventId }
                .orderBy(EventInventoryCustody.createdAt, SortOrder.DESC)
                .toList()
            val custody = rows.map { toCustodyResponse(it) }
            Result.success(EventInventoryCustodyListResponse(custody = custody, total = custody.size))
        }
    }

    fun getInventoryMovements(eventId: UUID, tuntasId: UUID): Result<EventInventoryMovementListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val movements = EventInventoryMovements.selectAll()
                .where { EventInventoryMovements.eventId eq eventId }
                .orderBy(EventInventoryMovements.createdAt, SortOrder.DESC)
                .map { toMovementResponse(it) }
            Result.success(EventInventoryMovementListResponse(movements = movements, total = movements.size))
        }
    }

    fun createInventoryMovement(
        eventId: UUID,
        tuntasId: UUID,
        performedByUserId: UUID,
        request: CreateEventInventoryMovementRequest,
        canManageInventory: Boolean
    ): Result<EventInventoryMovementResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureMovementAllowedForEvent(event) ?: return@transaction Result.failure(Exception("Inventory movement is allowed only for active events during their scheduled dates"))
            if (request.movementType !in validInventoryMovementTypes) {
                return@transaction Result.failure(Exception("Invalid movement type"))
            }
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val eventInventoryItemId = try {
                UUID.fromString(request.eventInventoryItemId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid event inventory item ID"))
            }
            val item = EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq eventInventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))

            val pastovykleId = request.pastovykleId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid pastovykle ID"))
                }
            }
            pastovykleId?.let {
                Pastovykles.selectAll()
                    .where { (Pastovykles.id eq it) and (Pastovykles.eventId eq eventId) }
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Pastovykle not found"))
            }

            val toUserId = request.toUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid user ID"))
                }
            }
            toUserId?.let {
                UserTuntasMemberships.selectAll()
                    .where {
                        (UserTuntasMemberships.userId eq it) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                    }
                    .firstOrNull() ?: return@transaction Result.failure(Exception("User is not a member of this tuntas"))
            }

            val now = kotlinx.datetime.Clock.System.now()
            val movementType = request.movementType
            val clientRequestId = request.requestId?.trim()?.takeIf { it.isNotBlank() }
            clientRequestId?.let { requestId ->
                EventInventoryMovements.selectAll()
                    .where {
                        (EventInventoryMovements.eventId eq eventId) and
                            (EventInventoryMovements.clientRequestId eq requestId)
                    }
                    .firstOrNull()
                    ?.let { existing ->
                        return@transaction Result.success(toMovementResponse(existing))
                    }
            }
            val sourceCustody = request.fromCustodyId?.let {
                val custodyId = try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid custody ID"))
                }
                EventInventoryCustody
                    .innerJoin(EventInventoryItems, { EventInventoryCustody.eventInventoryItemId }, { id })
                    .selectAll()
                    .where {
                        (EventInventoryCustody.id eq custodyId) and
                            (EventInventoryItems.eventId eq eventId) and
                            (EventInventoryCustody.status eq "OPEN")
                    }
                    .forUpdate()
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Custody record not found"))
            }

            if (!canManageInventory && movementType !in listOf("PASTOVYKLE_REQUEST", "CHECKOUT_TO_PERSON", "RETURN_TO_PASTOVYKLE", "RETURN_TO_EVENT_STORAGE")) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }

            val createdCustodyId: UUID?
            val movementId: UUID
            when (movementType) {
                "PASTOVYKLE_REQUEST" -> {
                    if (pastovykleId == null) return@transaction Result.failure(Exception("Pastovykle is required"))
                    val inventoryRequestId = EventInventoryRequests.insert {
                        it[this.eventId] = eventId
                        it[this.eventInventoryItemId] = eventInventoryItemId
                        it[this.pastovykleId] = pastovykleId
                        it[this.requestedByUserId] = performedByUserId
                        it[this.quantity] = request.quantity
                        it[status] = "PENDING"
                        it[notes] = request.notes
                        it[createdAt] = now
                        it[reviewedByUserId] = null
                        it[reviewedAt] = null
                        it[fulfilledAt] = null
                        it[resolvedByUserId] = null
                    } get EventInventoryRequests.id
                    createdCustodyId = null
                    movementId = insertInventoryMovement(
                        eventId = eventId,
                        eventInventoryItemId = eventInventoryItemId,
                        custodyId = null,
                        inventoryRequestId = inventoryRequestId,
                        movementType = movementType,
                        quantity = request.quantity,
                        fromPastovykleId = null,
                        toPastovykleId = pastovykleId,
                        fromUserId = null,
                        toUserId = null,
                        performedByUserId = performedByUserId,
                        clientRequestId = clientRequestId,
                        notes = request.notes,
                        createdAt = now
                    )
                }
                "ASSIGN_TO_PASTOVYKLE" -> {
                    if (!canManageInventory) return@transaction Result.failure(Exception("Insufficient permissions"))
                    if (pastovykleId == null) return@transaction Result.failure(Exception("Pastovykle is required"))
                    val available = eventStorageAvailable(eventInventoryItemId, item[EventInventoryItems.availableQuantity])
                    if (request.quantity > available) {
                        return@transaction Result.failure(Exception("Not enough event storage quantity. Available: $available"))
                    }
                    createdCustodyId = insertCustody(eventInventoryItemId, null, pastovykleId, null, request.quantity, performedByUserId, request.notes, now)
                    movementId = insertInventoryMovement(
                        eventId, eventInventoryItemId, createdCustodyId, null, movementType, request.quantity,
                        null, pastovykleId, null, null, performedByUserId, clientRequestId, request.notes, now
                    )
                    item[EventInventoryItems.itemId]?.let { sourceItemId ->
                        PastovykleInventory.insert {
                            it[this.pastovykleId] = pastovykleId
                            it[itemId] = sourceItemId
                            it[distributedByUserId] = performedByUserId
                            it[quantityAssigned] = request.quantity
                            it[quantityReturned] = 0
                            it[assignedAt] = now
                            it[notes] = request.notes
                        }
                    }
                }
                "CHECKOUT_TO_PERSON" -> {
                    val targetUserId = if (canManageInventory) (toUserId ?: performedByUserId) else performedByUserId
                    if (!canManageInventory && pastovykleId != null) {
                        val pastovykle = Pastovykles.selectAll()
                            .where { (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }
                            .firstOrNull()
                            ?: return@transaction Result.failure(Exception("Pastovykle not found"))
                        if (pastovykle[Pastovykles.responsibleUserId] != performedByUserId) {
                            return@transaction Result.failure(Exception("You can checkout from a pastovykle only if you are its responsible member"))
                        }
                    }
                    val available = if (pastovykleId != null) {
                        pastovykleAvailable(eventInventoryItemId, pastovykleId)
                    } else {
                        eventStorageAvailable(eventInventoryItemId, item[EventInventoryItems.availableQuantity])
                    }
                    if (request.quantity > available) {
                        return@transaction Result.failure(Exception("Not enough quantity to checkout. Available: $available"))
                    }
                    val parentCustodyId = pastovykleId?.let {
                        findAvailablePastovykleCustody(eventInventoryItemId, it, request.quantity)
                            ?: return@transaction Result.failure(Exception("Not enough quantity assigned to this pastovykle"))
                    }
                    createdCustodyId = insertCustody(
                        eventInventoryItemId = eventInventoryItemId,
                        parentCustodyId = parentCustodyId,
                        pastovykleId = pastovykleId,
                        holderUserId = targetUserId,
                        quantity = request.quantity,
                        createdByUserId = performedByUserId,
                        notes = request.notes,
                        createdAt = now
                    )
                    movementId = insertInventoryMovement(
                        eventId, eventInventoryItemId, createdCustodyId, null, movementType, request.quantity,
                        pastovykleId, pastovykleId, null, targetUserId, performedByUserId, clientRequestId, request.notes, now
                    )
                }
                "RETURN_TO_PASTOVYKLE", "RETURN_TO_EVENT_STORAGE" -> {
                    val source = sourceCustody ?: return@transaction Result.failure(Exception("fromCustodyId is required"))
                    val holderId = source[EventInventoryCustody.holderUserId]
                    if (!canManageInventory && holderId != performedByUserId) {
                        return@transaction Result.failure(Exception("You can return only your own checkout"))
                    }
                    val remaining = source[EventInventoryCustody.quantity] - source[EventInventoryCustody.returnedQuantity]
                    if (request.quantity > remaining) {
                        return@transaction Result.failure(Exception("Return quantity exceeds remaining quantity"))
                    }
                    if (movementType == "RETURN_TO_PASTOVYKLE" && source[EventInventoryCustody.parentCustodyId] == null) {
                        return@transaction Result.failure(Exception("This checkout is not linked to a pastovykle"))
                    }
                    val nextReturned = source[EventInventoryCustody.returnedQuantity] + request.quantity
                    EventInventoryCustody.update({ EventInventoryCustody.id eq source[EventInventoryCustody.id] }) {
                        it[returnedQuantity] = nextReturned
                        if (nextReturned == source[EventInventoryCustody.quantity]) {
                            it[status] = "RETURNED"
                            it[closedAt] = now
                        }
                    }
                    if (movementType == "RETURN_TO_EVENT_STORAGE" && source[EventInventoryCustody.parentCustodyId] != null) {
                        val parentCustody = EventInventoryCustody.selectAll()
                            .where { EventInventoryCustody.id eq source[EventInventoryCustody.parentCustodyId]!! }
                            .forUpdate()
                            .firstOrNull()
                            ?: return@transaction Result.failure(Exception("Parent custody not found"))
                        val parentRemaining = parentCustody[EventInventoryCustody.quantity] - parentCustody[EventInventoryCustody.returnedQuantity]
                        if (request.quantity > parentRemaining) {
                            return@transaction Result.failure(Exception("Return quantity exceeds remaining pastovykle quantity"))
                        }
                        val parentReturned = parentCustody[EventInventoryCustody.returnedQuantity] + request.quantity
                        EventInventoryCustody.update({ EventInventoryCustody.id eq parentCustody[EventInventoryCustody.id] }) {
                            it[returnedQuantity] = parentReturned
                            if (parentReturned == parentCustody[EventInventoryCustody.quantity]) {
                                it[status] = "RETURNED"
                                it[closedAt] = now
                            }
                        }
                    }
                    createdCustodyId = source[EventInventoryCustody.id]
                    movementId = insertInventoryMovement(
                        eventId, eventInventoryItemId, createdCustodyId, null, movementType, request.quantity,
                        source[EventInventoryCustody.pastovykleId],
                        if (movementType == "RETURN_TO_PASTOVYKLE") source[EventInventoryCustody.pastovykleId] else null,
                        source[EventInventoryCustody.holderUserId], null, performedByUserId, clientRequestId, request.notes, now
                    )
                }
                else -> {
                    if (!canManageInventory) return@transaction Result.failure(Exception("Insufficient permissions"))
                    val source = sourceCustody ?: return@transaction Result.failure(Exception("fromCustodyId is required"))
                    val remaining = source[EventInventoryCustody.quantity] - source[EventInventoryCustody.returnedQuantity]
                    if (request.quantity > remaining) {
                        return@transaction Result.failure(Exception("Transfer quantity exceeds remaining quantity"))
                    }
                    val targetPastovykleId = pastovykleId ?: source[EventInventoryCustody.pastovykleId]
                    val targetUserId = toUserId
                    val nextReturned = source[EventInventoryCustody.returnedQuantity] + request.quantity
                    EventInventoryCustody.update({ EventInventoryCustody.id eq source[EventInventoryCustody.id] }) {
                        it[returnedQuantity] = nextReturned
                        if (nextReturned == source[EventInventoryCustody.quantity]) {
                            it[status] = "CLOSED"
                            it[closedAt] = now
                        }
                    }
                    createdCustodyId = when {
                        targetPastovykleId != null && targetUserId != null -> {
                            val targetRootId = insertCustody(
                                eventInventoryItemId = eventInventoryItemId,
                                parentCustodyId = null,
                                pastovykleId = targetPastovykleId,
                                holderUserId = null,
                                quantity = request.quantity,
                                createdByUserId = performedByUserId,
                                notes = "Transfer root",
                                createdAt = now
                            )
                            insertCustody(
                                eventInventoryItemId = eventInventoryItemId,
                                parentCustodyId = targetRootId,
                                pastovykleId = targetPastovykleId,
                                holderUserId = targetUserId,
                                quantity = request.quantity,
                                createdByUserId = performedByUserId,
                                notes = request.notes,
                                createdAt = now
                            )
                        }
                        else -> insertCustody(
                            eventInventoryItemId = eventInventoryItemId,
                            parentCustodyId = if (targetPastovykleId != null && targetUserId == null) null else source[EventInventoryCustody.parentCustodyId],
                            pastovykleId = targetPastovykleId,
                            holderUserId = targetUserId,
                            quantity = request.quantity,
                            createdByUserId = performedByUserId,
                            notes = request.notes,
                            createdAt = now
                        )
                    }
                    movementId = insertInventoryMovement(
                        eventId, eventInventoryItemId, createdCustodyId, null, movementType, request.quantity,
                        source[EventInventoryCustody.pastovykleId], targetPastovykleId,
                        source[EventInventoryCustody.holderUserId], targetUserId, performedByUserId, clientRequestId, request.notes, now
                    )
                }
            }

            Result.success(toMovementResponse(EventInventoryMovements.selectAll().where { EventInventoryMovements.id eq movementId }.first()))
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
            val existing = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            if (request.invoiceFileUrl.isBlank()) {
                return@transaction Result.failure(Exception("Invoice file URL cannot be blank"))
            }
            existing[EventPurchases.invoiceFileUrl]
                ?.takeIf { it != request.invoiceFileUrl }
                ?.let { deleteManagedDocument(it) }
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
                    val lockedItem = EventInventoryItems.selectAll()
                        .where { EventInventoryItems.id eq row[EventInventoryItems.id] }
                        .forUpdate()
                        .first()
                    val nextAvailable = lockedItem[EventInventoryItems.availableQuantity] + row[EventPurchaseItems.purchasedQuantity]
                    val planned = lockedItem[EventInventoryItems.plannedQuantity]
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
                if (line[EventInventoryItems.itemId] == null) {
                    EventInventoryItems.update({ EventInventoryItems.id eq line[EventInventoryItems.id] }) {
                        it[EventInventoryItems.itemId] = itemId
                    }
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
        val locationId = row[EventInventoryBuckets.locationId]
        val locationPath = locationId?.let { id ->
            val locationRows = Locations.selectAll().toList()
            val nodesById = locationRows.associate { it[Locations.id] to it.toLocationNodeData() }
            buildLocationPath(id, nodesById)
        }
        return EventInventoryBucketResponse(
            id = row[EventInventoryBuckets.id].toString(),
            eventId = row[EventInventoryBuckets.eventId].toString(),
            name = row[EventInventoryBuckets.name],
            type = row[EventInventoryBuckets.type],
            pastovykleId = pastovykleId?.toString(),
            pastovykleName = pastovykleName,
            locationId = locationId?.toString(),
            locationPath = locationPath,
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
        endDate: kotlinx.datetime.LocalDate,
        excludeReservationGroupId: UUID? = null
    ): Int {
        val item = Items.selectAll()
            .where { (Items.id eq itemId) and (Items.status eq "ACTIVE") }
            .firstOrNull() ?: return 0
        val reserved = Reservations.select(Reservations.quantity)
            .where {
                (Reservations.itemId eq itemId) and
                    (Reservations.status inList listOf("APPROVED", "ACTIVE")) and
                    (Reservations.startDate lessEq endDate) and
                    (Reservations.endDate greaterEq startDate) and
                    (if (excludeReservationGroupId != null) Reservations.groupId neq excludeReservationGroupId else Op.TRUE)
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

    private fun insertCustody(
        eventInventoryItemId: UUID,
        parentCustodyId: UUID?,
        pastovykleId: UUID?,
        holderUserId: UUID?,
        quantity: Int,
        createdByUserId: UUID,
        notes: String?,
        createdAt: kotlinx.datetime.Instant
    ): UUID {
        return EventInventoryCustody.insert {
            it[this.eventInventoryItemId] = eventInventoryItemId
            it[this.parentCustodyId] = parentCustodyId
            it[this.pastovykleId] = pastovykleId
            it[this.holderUserId] = holderUserId
            it[this.quantity] = quantity
            it[returnedQuantity] = 0
            it[status] = "OPEN"
            it[this.createdByUserId] = createdByUserId
            it[this.createdAt] = createdAt
            it[this.notes] = notes
        } get EventInventoryCustody.id
    }

    private fun insertInventoryMovement(
        eventId: UUID,
        eventInventoryItemId: UUID,
        custodyId: UUID?,
        inventoryRequestId: UUID?,
        movementType: String,
        quantity: Int,
        fromPastovykleId: UUID?,
        toPastovykleId: UUID?,
        fromUserId: UUID?,
        toUserId: UUID?,
        performedByUserId: UUID,
        clientRequestId: String?,
        notes: String?,
        createdAt: kotlinx.datetime.Instant
    ): UUID {
        return EventInventoryMovements.insert {
            it[this.eventId] = eventId
            it[this.eventInventoryItemId] = eventInventoryItemId
            it[this.custodyId] = custodyId
            it[this.inventoryRequestId] = inventoryRequestId
            it[this.movementType] = movementType
            it[this.quantity] = quantity
            it[this.fromPastovykleId] = fromPastovykleId
            it[this.toPastovykleId] = toPastovykleId
            it[this.fromUserId] = fromUserId
            it[this.toUserId] = toUserId
            it[this.performedByUserId] = performedByUserId
            it[this.clientRequestId] = clientRequestId
            it[this.notes] = notes
            it[this.createdAt] = createdAt
        } get EventInventoryMovements.id
    }

    private fun eventStorageAvailable(eventInventoryItemId: UUID, availableQuantity: Int): Int {
        val outOfStorage = EventInventoryCustody.selectAll()
            .where {
                (EventInventoryCustody.eventInventoryItemId eq eventInventoryItemId) and
                    (EventInventoryCustody.status eq "OPEN")
            }
            .sumOf { openQuantity(it) }
        return (availableQuantity - outOfStorage).coerceAtLeast(0)
    }

    private fun pastovykleAvailable(eventInventoryItemId: UUID, pastovykleId: UUID): Int {
        return EventInventoryCustody.selectAll()
            .where {
                (EventInventoryCustody.eventInventoryItemId eq eventInventoryItemId) and
                    (EventInventoryCustody.pastovykleId eq pastovykleId) and
                    (EventInventoryCustody.holderUserId.isNull()) and
                    (EventInventoryCustody.parentCustodyId.isNull()) and
                    (EventInventoryCustody.status eq "OPEN")
            }
            .sumOf { root ->
                val checkedOut = EventInventoryCustody.selectAll()
                    .where {
                        (EventInventoryCustody.parentCustodyId eq root[EventInventoryCustody.id]) and
                            (EventInventoryCustody.status eq "OPEN")
                    }
                    .sumOf { child -> openQuantity(child) }
                (openQuantity(root) - checkedOut).coerceAtLeast(0)
            }
    }

    private fun openQuantity(row: ResultRow): Int {
        return (row[EventInventoryCustody.quantity] - row[EventInventoryCustody.returnedQuantity]).coerceAtLeast(0)
    }

    private fun findAvailablePastovykleCustody(
        eventInventoryItemId: UUID,
        pastovykleId: UUID,
        requiredQuantity: Int
    ): UUID? {
        return EventInventoryCustody.selectAll()
            .where {
                (EventInventoryCustody.eventInventoryItemId eq eventInventoryItemId) and
                    (EventInventoryCustody.pastovykleId eq pastovykleId) and
                    (EventInventoryCustody.holderUserId.isNull()) and
                    (EventInventoryCustody.parentCustodyId.isNull()) and
                    (EventInventoryCustody.status eq "OPEN")
            }
            .orderBy(EventInventoryCustody.createdAt, SortOrder.ASC)
            .firstOrNull { root ->
                val checkedOut = EventInventoryCustody.selectAll()
                    .where {
                        (EventInventoryCustody.parentCustodyId eq root[EventInventoryCustody.id]) and
                            (EventInventoryCustody.status eq "OPEN")
                    }
                    .sumOf { child -> openQuantity(child) }
                (openQuantity(root) - checkedOut) >= requiredQuantity
            }
            ?.get(EventInventoryCustody.id)
    }

    private fun toCustodyResponse(row: ResultRow): EventInventoryCustodyResponse {
        val pastovykle = row[EventInventoryCustody.pastovykleId]?.let { id ->
            Pastovykles.selectAll().where { Pastovykles.id eq id }.firstOrNull()
        }
        val holder = row[EventInventoryCustody.holderUserId]?.let { userId ->
            Users.selectAll().where { Users.id eq userId }.firstOrNull()
        }
        val creator = Users.selectAll().where { Users.id eq row[EventInventoryCustody.createdByUserId] }.firstOrNull()
        return EventInventoryCustodyResponse(
            id = row[EventInventoryCustody.id].toString(),
            eventInventoryItemId = row[EventInventoryCustody.eventInventoryItemId].toString(),
            itemName = row[EventInventoryItems.name],
            pastovykleId = row[EventInventoryCustody.pastovykleId]?.toString(),
            pastovykleName = pastovykle?.get(Pastovykles.name),
            holderUserId = row[EventInventoryCustody.holderUserId]?.toString(),
            holderUserName = holder?.let { "${it[Users.name]} ${it[Users.surname]}".trim() },
            quantity = row[EventInventoryCustody.quantity],
            returnedQuantity = row[EventInventoryCustody.returnedQuantity],
            remainingQuantity = (row[EventInventoryCustody.quantity] - row[EventInventoryCustody.returnedQuantity]).coerceAtLeast(0),
            status = row[EventInventoryCustody.status],
            createdByUserId = row[EventInventoryCustody.createdByUserId].toString(),
            createdByUserName = creator?.let { "${it[Users.name]} ${it[Users.surname]}".trim() },
            createdAt = row[EventInventoryCustody.createdAt].toString(),
            closedAt = row[EventInventoryCustody.closedAt]?.toString(),
            notes = row[EventInventoryCustody.notes]
        )
    }

    private fun toMovementResponse(row: ResultRow): EventInventoryMovementResponse {
        val item = EventInventoryItems.selectAll()
            .where { EventInventoryItems.id eq row[EventInventoryMovements.eventInventoryItemId] }
            .first()
        fun userName(id: UUID?): String? = id?.let {
            Users.selectAll().where { Users.id eq it }.firstOrNull()
                ?.let { user -> "${user[Users.name]} ${user[Users.surname]}".trim() }
        }
        fun pastovykleName(id: UUID?): String? = id?.let {
            Pastovykles.selectAll().where { Pastovykles.id eq it }.firstOrNull()?.get(Pastovykles.name)
        }
        return EventInventoryMovementResponse(
            id = row[EventInventoryMovements.id].toString(),
            eventId = row[EventInventoryMovements.eventId].toString(),
            eventInventoryItemId = row[EventInventoryMovements.eventInventoryItemId].toString(),
            itemName = item[EventInventoryItems.name],
            custodyId = row[EventInventoryMovements.custodyId]?.toString(),
            movementType = row[EventInventoryMovements.movementType],
            quantity = row[EventInventoryMovements.quantity],
            fromPastovykleId = row[EventInventoryMovements.fromPastovykleId]?.toString(),
            fromPastovykleName = pastovykleName(row[EventInventoryMovements.fromPastovykleId]),
            toPastovykleId = row[EventInventoryMovements.toPastovykleId]?.toString(),
            toPastovykleName = pastovykleName(row[EventInventoryMovements.toPastovykleId]),
            fromUserId = row[EventInventoryMovements.fromUserId]?.toString(),
            fromUserName = userName(row[EventInventoryMovements.fromUserId]),
            toUserId = row[EventInventoryMovements.toUserId]?.toString(),
            toUserName = userName(row[EventInventoryMovements.toUserId]),
            performedByUserId = row[EventInventoryMovements.performedByUserId].toString(),
            performedByUserName = userName(row[EventInventoryMovements.performedByUserId]),
            notes = row[EventInventoryMovements.notes],
            createdAt = row[EventInventoryMovements.createdAt].toString()
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

    private fun isActiveTuntasMember(userId: UUID, tuntasId: UUID): Boolean {
        return UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                    (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null
    }

    private fun ensureMovementAllowedForEvent(event: ResultRow): Unit? {
        if (event[Events.status] != "ACTIVE") return null
        val today = kotlinx.datetime.Clock.System.now()
            .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            .date
        if (today < event[Events.startDate] || today > event[Events.endDate]) return null
        return Unit
    }

    private fun cancelReservationGroup(groupId: UUID) {
        Reservations.update({
            (Reservations.groupId eq groupId) and
                (Reservations.status inList listOf("PENDING", "APPROVED", "ACTIVE"))
        }) {
            it[status] = "CANCELLED"
        }
    }

    private fun syncReservationGroupQuantity(groupId: UUID, quantity: Int) {
        if (quantity <= 0) {
            cancelReservationGroup(groupId)
            return
        }
        Reservations.update({
            (Reservations.groupId eq groupId) and
                (Reservations.status inList listOf("PENDING", "APPROVED", "ACTIVE"))
        }) {
            it[Reservations.quantity] = quantity
        }
    }

    private fun deleteManagedDocument(url: String?) {
        val prefix = "/uploads/documents/"
        if (url.isNullOrBlank() || !url.startsWith(prefix)) return

        val fileName = url.removePrefix(prefix)
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) return

        val baseDir = File("uploads/documents").canonicalFile
        val targetFile = File(baseDir, fileName).canonicalFile
        if (targetFile.path.startsWith(baseDir.path + File.separator) && targetFile.exists()) {
            targetFile.delete()
        }
    }
}
