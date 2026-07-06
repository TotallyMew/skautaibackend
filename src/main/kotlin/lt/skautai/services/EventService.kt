package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.*
import lt.skautai.models.responses.*
import lt.skautai.util.UploadStorage
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

class EventService {

    private val validTypes = listOf("STOVYKLA", "SUEIGA", "RENGINYS")
    private val validStatuses = listOf("PLANNING", "ACTIVE", "WRAP_UP", "COMPLETED", "CANCELLED")
    private val readOnlyEventStatuses = listOf("COMPLETED", "CANCELLED")
    private val validEventRoles = listOf(
        "VIRSININKAS", "KOMENDANTAS", "UKVEDYS", "FINANSININKAS", "PASTOVYKLES_GURU",
        "VADOVAS", "SAVANORIS", "PATYRE_SKAUTAS", "SKAUTAS",
        "PROGRAMERIS", "MAISTININKAS"
    )
    private val validTargetGroups = listOf("VILKAI", "SKAUTAI", "PATYRE_SKAUTAI", "VYR_SKAUTAI", "VYR_SKAUTES", "SKAUTAI_VILKAI", "TEVAI", "PROGRAMA")
    private val eventManagerRoles = listOf("VIRSININKAS")
    private val eventInventoryRoles = listOf("VIRSININKAS", "KOMENDANTAS", "UKVEDYS")
    private val eventFinanceRoles = listOf("VIRSININKAS", "KOMENDANTAS", "UKVEDYS", "FINANSININKAS")
    private val eventViewerRoles = listOf("VIRSININKAS", "KOMENDANTAS", "UKVEDYS", "PROGRAMERIS")
    private val eventInventoryRequesterRoles = listOf("VIRSININKAS", "KOMENDANTAS", "UKVEDYS", "PROGRAMERIS")
    private val validBucketTypes = listOf("PROGRAM", "KITCHEN", "ADMIN", "MEDICAL", "PASTOVYKLE", "OTHER")
    private val validPurchaseStatuses = listOf("DRAFT", "PURCHASED", "CANCELLED")
    private val validExtraCostCategories = listOf("FIREWOOD", "TOILETS", "OTHER")
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
    private val vadovasRankName = "Vadovas"
    private val seniorScoutRankNames = setOf("Patyres skautas", "Patyręs skautas", "Vyr. skautas", "Vyr. skautas kandidatas")
    private val globalEventRoleNames = setOf("Tuntininkas", "Tuntininko pavaduotojas")
    private val seniorScoutUnitTypes = setOf("VYR_SKAUTU_VIENETAS", "VYR_SKAUCIU_VIENETAS")

    fun isTuntasMember(userId: UUID, tuntasId: UUID): Boolean = transaction {
        UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null
    }

    private fun ensureUserHasNoEventStaffRole(
        eventId: UUID,
        userId: UUID,
        excludingPastovykleId: UUID? = null
    ): Exception? {
        val existingRole = EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                    (EventRoles.userId eq userId)
            }
            .firstOrNull()

        if (existingRole != null) {
            return Exception("User already has an event staff role")
        }

        val existingPastovykle = Pastovykles.selectAll()
            .where {
                (Pastovykles.eventId eq eventId) and
                    (Pastovykles.responsibleUserId eq userId)
            }
            .firstOrNull { row ->
                excludingPastovykleId == null || row[Pastovykles.id] != excludingPastovykleId
            }

        return if (existingPastovykle != null) {
            Exception("User already has an event staff role")
        } else {
            null
        }
    }

    fun canViewEvents(userId: UUID, tuntasId: UUID): Boolean = transaction {
        isActiveTuntasMember(userId, tuntasId)
    }

    fun canCreateEvent(userId: UUID, tuntasId: UUID, targetOrgUnitId: UUID?): Boolean = transaction {
        if (!isActiveTuntasMember(userId, tuntasId)) return@transaction false

        val rankNames = userRankNames(userId, tuntasId)
        if (targetOrgUnitId == null) {
            return@transaction hasAnyLeadershipRole(userId, tuntasId, globalEventRoleNames) ||
                vadovasRankName in rankNames
        }

        val targetUnit = OrganizationalUnits.selectAll()
            .where {
                (OrganizationalUnits.id eq targetOrgUnitId) and
                    (OrganizationalUnits.tuntasId eq tuntasId)
            }
            .firstOrNull() ?: return@transaction false
        val targetType = targetUnit[OrganizationalUnits.type]

        when (targetType) {
            "GILDIJA" -> vadovasRankName in rankNames || userLeadsUnit(userId, tuntasId, targetOrgUnitId)
            in seniorScoutUnitTypes -> {
                val isTargetUnitMember = userBelongsToUnit(userId, tuntasId, targetOrgUnitId) ||
                    userLeadsUnit(userId, tuntasId, targetOrgUnitId)
                isTargetUnitMember && (rankNames.any { it in seniorScoutRankNames } || vadovasRankName in rankNames)
            }
            else -> hasAnyLeadershipRole(userId, tuntasId, globalEventRoleNames)
        }
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

    fun canManageEventFinance(eventId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull() ?: return@transaction false

        EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq userId) and
                        (EventRoles.role inList eventFinanceRoles)
            }
            .firstOrNull() != null
    }

    fun canViewEventInventory(eventId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull() ?: return@transaction false

        EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq userId) and
                        (EventRoles.role inList eventViewerRoles)
            }
            .firstOrNull() != null
    }

    fun canRequestEventInventory(eventId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull() ?: return@transaction false

        EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq userId) and
                        (EventRoles.role inList eventInventoryRequesterRoles)
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
        status: String? = null,
        updatedAfter: kotlinx.datetime.Instant? = null,
        limit: Int? = null,
        offset: Int = 0
    ): Result<EventListResponse> {
        return transaction {
            var query = Events.selectAll()
                .where { Events.tuntasId eq tuntasId }

            if (updatedAfter == null) {
                type?.let { query = query.andWhere { Events.type eq it } }
                status?.let { query = query.andWhere { Events.status eq it } }
            }
            updatedAfter?.let { since -> query = query.andWhere { Events.updatedAt greater since } }

            val rows = sortEventRows(query.toList())
            Result.success(toEventListResponse(rows, limit, offset))
        }
    }

    fun getVisibleEvents(
        tuntasId: UUID,
        userId: UUID,
        type: String? = null,
        status: String? = null,
        updatedAfter: kotlinx.datetime.Instant? = null,
        limit: Int? = null,
        offset: Int = 0
    ): Result<EventListResponse> {
        return transaction {
            var query = Events.selectAll()
                .where { Events.tuntasId eq tuntasId }

            if (updatedAfter == null) {
                type?.let { query = query.andWhere { Events.type eq it } }
                status?.let { query = query.andWhere { Events.status eq it } }
            }
            updatedAfter?.let { since -> query = query.andWhere { Events.updatedAt greater since } }

            val access = resolveEventAccess(userId, tuntasId)
            val rows = sortEventRows(query.filter { canViewEventRow(it, access) })
            Result.success(toEventListResponse(rows, limit, offset))
        }
    }

    fun getResponsibleEvents(
        tuntasId: UUID,
        userId: UUID,
        type: String? = null,
        status: String? = null,
        updatedAfter: kotlinx.datetime.Instant? = null,
        limit: Int? = null,
        offset: Int = 0
    ): Result<EventListResponse> {
        return transaction {
            var query = Events
                .innerJoin(Pastovykles, { id }, { eventId })
                .select(Events.columns)
                .where {
                    (Events.tuntasId eq tuntasId) and
                        (Pastovykles.responsibleUserId eq userId)
                }

            if (updatedAfter == null) {
                type?.let { query = query.andWhere { Events.type eq it } }
                status?.let { query = query.andWhere { Events.status eq it } }
            }
            updatedAfter?.let { since -> query = query.andWhere { Events.updatedAt greater since } }

            val rows = sortEventRows(query.toList().distinctBy { it[Events.id] })
            Result.success(toEventListResponse(rows, limit, offset))
        }
    }

    private fun sortEventRows(rows: List<ResultRow>): List<ResultRow> =
        rows.sortedWith(
            compareByDescending<ResultRow> { it[Events.startDate] }
                .thenByDescending { it[Events.createdAt] }
                .thenBy { it[Events.id].toString() }
        )

    private fun toEventListResponse(
        rows: List<ResultRow>,
        limit: Int?,
        offset: Int
    ): EventListResponse {
        val total = rows.size
        val pageRows = limit?.let { rows.drop(offset).take(it) } ?: rows
        val hydration = buildEventListHydration(pageRows)
        val events = pageRows.map { toEventResponse(it, hydration) }
        return EventListResponse(
            events = events,
            total = total,
            limit = limit,
            offset = offset,
            hasMore = limit != null && offset + events.size < total
        )
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

    fun canViewEvent(userId: UUID, tuntasId: UUID, eventId: UUID): Boolean = transaction {
        val access = resolveEventAccess(userId, tuntasId)
        val event = Events.selectAll()
            .where {
                (Events.id eq eventId) and
                    (Events.tuntasId eq tuntasId)
            }
            .firstOrNull() ?: return@transaction access.isGlobalEventAdmin
        canViewEventRow(event, access)
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

            val normalizedType = normalizeEventType(request.type)
                ?: return@transaction Result.failure(Exception("Event type is required"))
            validateEventType(normalizedType)?.let {
                return@transaction Result.failure(it)
            }
            val normalizedCustomTypeLabel = normalizeCustomTypeLabel(request.customTypeLabel)
            validateCustomTypeLabel(normalizedCustomTypeLabel)?.let {
                return@transaction Result.failure(it)
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
            validateEventLocation(locationUUID, tuntasId)?.let { return@transaction Result.failure(it) }

            val orgUnitUUID = request.organizationalUnitId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }
            validateEventOrgUnit(orgUnitUUID, tuntasId)?.let { return@transaction Result.failure(it) }

            val eventId = Events.insert {
                it[this.tuntasId] = tuntasId
                it[name] = request.name
                it[type] = normalizedType
                it[this.customTypeLabel] = normalizedCustomTypeLabel
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
            val existing = Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(existing)?.let { return@transaction Result.failure(it) }

            request.status?.let {
                if (it !in validStatuses) {
                    return@transaction Result.failure(Exception("Invalid status"))
                }
                if (it == "COMPLETED") {
                    val blocking = reconciliationBlockingCounts(eventId)
                    if (blocking.first > 0 || blocking.second > 0) {
                        return@transaction Result.failure(Exception("Event cannot be completed while reconciliation has unresolved returns or purchases"))
                    }
                }
            }

            val normalizedType = request.type?.let {
                normalizeEventType(it) ?: return@transaction Result.failure(Exception("Event type is required"))
            }
            normalizedType?.let {
                validateEventType(it)?.let { error ->
                    return@transaction Result.failure(error)
                }
            }
            val normalizedCustomTypeLabel = request.customTypeLabel?.let {
                normalizeCustomTypeLabel(it)
            } ?: if (request.type != null) null else null
            if (request.customTypeLabel != null || request.type != null) {
                validateCustomTypeLabel(normalizedCustomTypeLabel)?.let { error ->
                    return@transaction Result.failure(error)
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
            validateEventLocation(locationUUID, tuntasId)?.let { return@transaction Result.failure(it) }

            val orgUnitUUID = request.organizationalUnitId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }
            validateEventOrgUnit(orgUnitUUID, tuntasId)?.let { return@transaction Result.failure(it) }

            Events.update({
                (Events.id eq eventId) and
                        (Events.tuntasId eq tuntasId)
            }) {
                request.name?.let { v -> it[name] = v }
                normalizedType?.let { v -> it[type] = v }
                if (request.customTypeLabel != null || request.type != null) {
                    it[Events.customTypeLabel] = normalizedCustomTypeLabel
                }
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

            if (existing[Events.status] != "PLANNING") {
                return@transaction Result.failure(Exception("Only PLANNING events can be deleted"))
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
            val event = Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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
            val targetPastovykleUUID = request.pastovykleId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid pastovykle ID"))
                }
            }
            targetPastovykleUUID?.let {
                ensurePastovykle(eventId, it)
                    ?: return@transaction Result.failure(Exception("Pastovykle not found"))
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

            val existingUserRole = EventRoles.selectAll()
                .where {
                    (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq targetUserUUID)
                }
                .firstOrNull()

            if (existingUserRole != null) {
                val isSameSlot = existingUserRole[EventRoles.role] == request.role &&
                    existingUserRole[EventRoles.targetGroup] == request.targetGroup &&
                    existingUserRole[EventRoles.pastovykleId] == targetPastovykleUUID
                if (isSameSlot) {
                    return@transaction Result.success(toEventRoleResponse(existingUserRole))
                }
                return@transaction Result.failure(Exception("User already has an event staff role"))
            }

            val existingPastovykleResponsibility = Pastovykles.selectAll()
                .where {
                    (Pastovykles.eventId eq eventId) and
                        (Pastovykles.responsibleUserId eq targetUserUUID)
                }
                .firstOrNull()

            if (existingPastovykleResponsibility != null) {
                return@transaction Result.failure(Exception("User already has an event staff role"))
            }

            val existingSlotRole = EventRoles.selectAll()
                .where {
                    (EventRoles.eventId eq eventId) and
                        (EventRoles.role eq request.role) and
                        (if (request.targetGroup == null) {
                            EventRoles.targetGroup eq null
                        } else {
                            EventRoles.targetGroup eq request.targetGroup
                        }) and
                        (if (targetPastovykleUUID == null) {
                            EventRoles.pastovykleId eq null
                        } else {
                            EventRoles.pastovykleId eq targetPastovykleUUID
                        })
                }
                .firstOrNull()

            if (existingSlotRole != null) {
                EventRoles.deleteWhere {
                    (EventRoles.eventId eq eventId) and
                        (EventRoles.role eq request.role) and
                        (if (request.targetGroup == null) {
                            EventRoles.targetGroup eq null
                        } else {
                            EventRoles.targetGroup eq request.targetGroup
                        }) and
                        (if (targetPastovykleUUID == null) {
                            EventRoles.pastovykleId eq null
                        } else {
                            EventRoles.pastovykleId eq targetPastovykleUUID
                        })
                }
            }

            val roleId = EventRoles.insert {
                it[this.eventId] = eventId
                it[userId] = targetUserUUID
                it[role] = request.role
                it[targetGroup] = request.targetGroup
                targetPastovykleUUID?.let { value -> it[pastovykleId] = value }
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
            val event = Events.selectAll()
                .where {
                    (Events.id eq eventId) and
                            (Events.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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

    private val validAgeGroups = listOf("VILKAI", "SKAUTAI", "PATYRE_SKAUTAI", "VYR_SKAUTAI", "VYR_SKAUTES", "MIXED")
    private val validRecipientTypes = listOf("DIRECT", "GURU_PROXY", "MEMBER")

    private fun verifyStovyklaEvent(eventId: UUID, tuntasId: UUID): ResultRow? {
        val event = Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull() ?: return null
        if (event[Events.type] != "STOVYKLA") return null
        return event
    }

    fun isPastovykleResponsible(eventId: UUID, pastovykleId: UUID, tuntasId: UUID, userId: UUID): Boolean = transaction {
        val isPrimaryLeader = Pastovykles.selectAll()
            .where {
                (Pastovykles.id eq pastovykleId) and
                    (Pastovykles.eventId eq eventId) and
                    (Pastovykles.responsibleUserId eq userId)
            }
            .firstOrNull() != null

        val isCoLeader = EventRoles.selectAll()
            .where {
                (EventRoles.eventId eq eventId) and
                    (EventRoles.pastovykleId eq pastovykleId) and
                    (EventRoles.userId eq userId) and
                    (EventRoles.role eq "PASTOVYKLES_GURU")
            }
            .firstOrNull() != null

        (isPrimaryLeader || isCoLeader) && verifyStovyklaEvent(eventId, tuntasId) != null
    }

    fun hasResponsiblePastovykle(userId: UUID, tuntasId: UUID): Boolean = transaction {
        val primaryLeader = Pastovykles
            .innerJoin(Events, { eventId }, { id })
            .selectAll()
            .where {
                (Pastovykles.responsibleUserId eq userId) and
                    (Events.tuntasId eq tuntasId) and
                    (Events.type eq "STOVYKLA")
            }
            .firstOrNull() != null

        val coLeader = EventRoles
            .innerJoin(Events, { eventId }, { id })
            .selectAll()
            .where {
                (EventRoles.userId eq userId) and
                    (EventRoles.role eq "PASTOVYKLES_GURU") and
                    (EventRoles.pastovykleId.isNotNull()) and
                    (Events.tuntasId eq tuntasId) and
                    (Events.type eq "STOVYKLA")
            }
            .firstOrNull() != null

        primaryLeader || coLeader
    }

    fun hasResponsiblePastovykleForEvent(userId: UUID, tuntasId: UUID, targetEventId: UUID): Boolean = transaction {
        val primaryLeader = Pastovykles
            .innerJoin(Events, { eventId }, { id })
            .selectAll()
            .where {
                (Pastovykles.responsibleUserId eq userId) and
                    (Pastovykles.eventId eq targetEventId) and
                    (Events.tuntasId eq tuntasId) and
                    (Events.type eq "STOVYKLA")
            }
            .firstOrNull() != null

        val coLeader = EventRoles
            .innerJoin(Events, { eventId }, { id })
            .selectAll()
            .where {
                (EventRoles.userId eq userId) and
                    (EventRoles.eventId eq targetEventId) and
                    (EventRoles.role eq "PASTOVYKLES_GURU") and
                    (EventRoles.pastovykleId.isNotNull()) and
                    (Events.tuntasId eq tuntasId) and
                    (Events.type eq "STOVYKLA")
            }
            .firstOrNull() != null

        primaryLeader || coLeader
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

    private fun toPastovykleMemberResponse(
        row: ResultRow,
        userNamesById: Map<UUID, String> = emptyMap()
    ): PastovykleMemberResponse {
        val userName = userNamesById[row[PastovykleMembers.userId]]
            ?: Users.selectAll()
                .where { (Users.id eq row[PastovykleMembers.userId]) and Users.deletedAt.isNull() }
                .firstOrNull()
                ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
            ?: "Unknown"
        return PastovykleMemberResponse(
            id = row[PastovykleMembers.id].toString(),
            pastovykleId = row[PastovykleMembers.pastovykleId].toString(),
            userId = row[PastovykleMembers.userId].toString(),
            userName = userName,
            status = row[PastovykleMembers.status],
            addedAt = row[PastovykleMembers.addedAt].toString(),
            addedByUserId = row[PastovykleMembers.addedByUserId].toString()
        )
    }

    private fun isActivePastovykleMember(pastovykleId: UUID, userId: UUID): Boolean {
        return PastovykleMembers.selectAll()
            .where {
                (PastovykleMembers.pastovykleId eq pastovykleId) and
                    (PastovykleMembers.userId eq userId) and
                    (PastovykleMembers.status eq "ACTIVE")
            }
            .firstOrNull() != null
    }

    private fun toInventoryResponse(
        row: ResultRow,
        itemNamesById: Map<UUID, String> = emptyMap()
    ): PastovykleInventoryResponse {
        val itemName = itemNamesById[row[PastovykleInventory.itemId]]
            ?: Items.selectAll()
                .where { Items.id eq row[PastovykleInventory.itemId] }
                .firstOrNull()?.get(Items.name)
            ?: "Unknown"
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
        val pastovykle = row[EventInventoryRequests.pastovykleId]?.let { pastovykleId ->
            Pastovykles.selectAll()
                .where { Pastovykles.id eq pastovykleId }
                .firstOrNull()
        }

        fun userName(id: UUID?): String? = id?.let {
            Users.selectAll()
                .where { Users.id eq it }
                .firstOrNull()
                ?.let { user -> "${user[Users.name]} ${user[Users.surname]}".trim() }
        }
        val providerHistory = EventInventoryRequestHistory.selectAll()
            .where { EventInventoryRequestHistory.requestId eq row[EventInventoryRequests.id] }
            .orderBy(EventInventoryRequestHistory.createdAt, SortOrder.DESC)
            .map { history ->
                EventInventoryRequestProviderHistoryResponse(
                    id = history[EventInventoryRequestHistory.id].toString(),
                    fromProvider = history[EventInventoryRequestHistory.fromProvider],
                    toProvider = history[EventInventoryRequestHistory.toProvider],
                    changedByUserId = history[EventInventoryRequestHistory.changedByUserId].toString(),
                    changedByUserName = userName(history[EventInventoryRequestHistory.changedByUserId]),
                    notes = history[EventInventoryRequestHistory.notes],
                    createdAt = history[EventInventoryRequestHistory.createdAt].toString()
                )
            }

        return EventInventoryRequestResponse(
            id = row[EventInventoryRequests.id].toString(),
            eventId = row[EventInventoryRequests.eventId].toString(),
            eventInventoryItemId = row[EventInventoryRequests.eventInventoryItemId].toString(),
            itemId = inventoryItem[EventInventoryItems.itemId]?.toString(),
            itemName = inventoryItem[EventInventoryItems.name],
            pastovykleId = row[EventInventoryRequests.pastovykleId]?.toString(),
            pastovykleName = pastovykle?.get(Pastovykles.name),
            targetGroup = row[EventInventoryRequests.targetGroup],
            requestedByUserId = row[EventInventoryRequests.requestedByUserId].toString(),
            requestedByName = userName(row[EventInventoryRequests.requestedByUserId]),
            quantity = row[EventInventoryRequests.quantity],
            provider = row[EventInventoryRequests.provider],
            status = row[EventInventoryRequests.status],
            notes = row[EventInventoryRequests.notes],
            createdAt = row[EventInventoryRequests.createdAt].toString(),
            reviewedAt = row[EventInventoryRequests.reviewedAt]?.toString(),
            reviewedByUserId = row[EventInventoryRequests.reviewedByUserId]?.toString(),
            reviewedByUserName = userName(row[EventInventoryRequests.reviewedByUserId]),
            fulfilledAt = row[EventInventoryRequests.fulfilledAt]?.toString(),
            resolvedByUserId = row[EventInventoryRequests.resolvedByUserId]?.toString(),
            resolvedByUserName = userName(row[EventInventoryRequests.resolvedByUserId]),
            dueAt = row[EventInventoryRequests.dueAt]?.toString(),
            responsibleUserId = row[EventInventoryRequests.responsibleUserId]?.toString(),
            responsibleUserName = userName(row[EventInventoryRequests.responsibleUserId]),
            providerHistory = providerHistory
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

    fun getResponsiblePastovykles(eventId: UUID, tuntasId: UUID, userId: UUID): Result<PastovykleListResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            val coLeaderPastovykleIds = EventRoles.select(EventRoles.pastovykleId)
                .where {
                    (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq userId) and
                        (EventRoles.role eq "PASTOVYKLES_GURU") and
                        (EventRoles.pastovykleId.isNotNull())
                }
                .mapNotNull { it[EventRoles.pastovykleId] }
                .toSet()

            val list = Pastovykles.selectAll()
                .where {
                    (Pastovykles.eventId eq eventId) and
                        (if (coLeaderPastovykleIds.isEmpty()) {
                            Pastovykles.responsibleUserId eq userId
                        } else {
                            (Pastovykles.responsibleUserId eq userId) or
                                (Pastovykles.id inList coLeaderPastovykleIds)
                        })
                }
                .map { toPastovykleResponse(it) }

            Result.success(PastovykleListResponse(pastovykles = list, total = list.size))
        }
    }

    fun assignPastovykleLeader(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID,
        request: AssignPastovykleLeaderRequest
    ): Result<EventRoleResponse> {
        return transaction {
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val pastovykle = ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovykle not found"))

            val targetUserId = try { UUID.fromString(request.userId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid user ID"))
            }
            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq targetUserId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("User is not a member of this tuntas"))

            if (pastovykle[Pastovykles.responsibleUserId] == targetUserId) {
                return@transaction Result.failure(Exception("Pagrindinis vadovas jau turi pastovyklės vadovo teises"))
            }

            val existingRole = EventRoles.selectAll()
                .where {
                    (EventRoles.eventId eq eventId) and
                        (EventRoles.userId eq targetUserId)
                }
                .firstOrNull()

            if (existingRole != null) {
                val isSamePastovykleLeader = existingRole[EventRoles.role] == "PASTOVYKLES_GURU" &&
                    existingRole[EventRoles.pastovykleId] == pastovykleId
                return@transaction if (isSamePastovykleLeader) {
                    Result.success(toEventRoleResponse(existingRole))
                } else {
                    Result.failure(Exception("User already has an event staff role"))
                }
            }

            val existingPastovykleResponsibility = Pastovykles.selectAll()
                .where {
                    (Pastovykles.eventId eq eventId) and
                        (Pastovykles.responsibleUserId eq targetUserId)
                }
                .firstOrNull()
            if (existingPastovykleResponsibility != null) {
                return@transaction Result.failure(Exception("User already has an event staff role"))
            }

            val roleId = EventRoles.insert {
                it[this.eventId] = eventId
                it[userId] = targetUserId
                it[role] = "PASTOVYKLES_GURU"
                it[this.pastovykleId] = pastovykleId
                it[this.assignedByUserId] = assignedByUserId
            } get EventRoles.id

            val role = EventRoles.selectAll()
                .where { EventRoles.id eq roleId }
                .first()
            Result.success(toEventRoleResponse(role))
        }
    }

    fun removePastovykleLeader(
        eventId: UUID,
        pastovykleId: UUID,
        roleId: UUID,
        tuntasId: UUID
    ): Result<Unit> {
        return transaction {
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovykle not found"))

            EventRoles.selectAll()
                .where {
                    (EventRoles.id eq roleId) and
                        (EventRoles.eventId eq eventId) and
                        (EventRoles.pastovykleId eq pastovykleId) and
                        (EventRoles.role eq "PASTOVYKLES_GURU")
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovykle leader not found"))

            EventRoles.deleteWhere {
                (EventRoles.id eq roleId) and
                    (EventRoles.eventId eq eventId) and
                    (EventRoles.pastovykleId eq pastovykleId)
            }
            Result.success(Unit)
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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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
            responsibleUUID?.let {
                if (!isActiveTuntasMember(it, tuntasId)) {
                    return@transaction Result.failure(Exception("Responsible user must be a member of this tuntas"))
                }
                ensureUserHasNoEventStaffRole(eventId, it)?.let { error ->
                    return@transaction Result.failure(error)
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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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
            responsibleUUID?.let {
                if (!isActiveTuntasMember(it, tuntasId)) {
                    return@transaction Result.failure(Exception("Responsible user must be a member of this tuntas"))
                }
                ensureUserHasNoEventStaffRole(eventId, it, excludingPastovykleId = pastovykleId)?.let { error ->
                    return@transaction Result.failure(error)
                }
            }

            Pastovykles.update({ (Pastovykles.id eq pastovykleId) and (Pastovykles.eventId eq eventId) }) {
                request.name?.let { v -> it[name] = v }
                request.ageGroup?.let { v -> it[ageGroup] = v }
                request.notes?.let { v -> it[notes] = v }
                if (request.clearResponsibleUser) {
                    it[responsibleUserId] = null
                } else {
                    responsibleUUID?.let { v -> it[responsibleUserId] = v }
                }
            }

            val updated = Pastovykles.selectAll().where { Pastovykles.id eq pastovykleId }.first()
            Result.success(toPastovykleResponse(updated))
        }
    }

    fun deletePastovykle(eventId: UUID, pastovykleId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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

    fun getPastovykleMembers(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID
    ): Result<PastovykleMemberListResponse> {
        return transaction {
            verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))

            ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            val memberRows = PastovykleMembers.selectAll()
                .where {
                    (PastovykleMembers.pastovykleId eq pastovykleId) and
                        (PastovykleMembers.status eq "ACTIVE")
                }
                .toList()
            val memberUserIds = memberRows.map { it[PastovykleMembers.userId] }.distinct()
            val userNamesById = if (memberUserIds.isEmpty()) {
                emptyMap()
            } else {
                Users.selectAll()
                    .where { (Users.id inList memberUserIds) and Users.deletedAt.isNull() }
                    .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }
            }
            val members = memberRows.map { toPastovykleMemberResponse(it, userNamesById) }

            Result.success(PastovykleMemberListResponse(members = members, total = members.size))
        }
    }

    fun addPastovykleMember(
        eventId: UUID,
        pastovykleId: UUID,
        tuntasId: UUID,
        addedByUserId: UUID,
        request: AddPastovykleMemberRequest
    ): Result<PastovykleMemberResponse> {
        return transaction {
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

            ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            val memberUserId = try {
                UUID.fromString(request.userId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid user ID"))
            }

            if (!isActiveTuntasMember(memberUserId, tuntasId)) {
                return@transaction Result.failure(Exception("User must be an active member of this tuntas"))
            }

            val existing = PastovykleMembers.selectAll()
                .where {
                    (PastovykleMembers.pastovykleId eq pastovykleId) and
                        (PastovykleMembers.userId eq memberUserId)
                }
                .firstOrNull()

            val memberId = if (existing != null) {
                PastovykleMembers.update({ PastovykleMembers.id eq existing[PastovykleMembers.id] }) {
                    it[status] = "ACTIVE"
                    it[addedAt] = kotlinx.datetime.Clock.System.now()
                    it[this.addedByUserId] = addedByUserId
                }
                existing[PastovykleMembers.id]
            } else {
                PastovykleMembers.insert {
                    it[PastovykleMembers.pastovykleId] = pastovykleId
                    it[userId] = memberUserId
                    it[status] = "ACTIVE"
                    it[addedAt] = kotlinx.datetime.Clock.System.now()
                    it[this.addedByUserId] = addedByUserId
                } get PastovykleMembers.id
            }

            val row = PastovykleMembers.selectAll()
                .where { PastovykleMembers.id eq memberId }
                .first()
            Result.success(toPastovykleMemberResponse(row))
        }
    }

    fun removePastovykleMember(
        eventId: UUID,
        pastovykleId: UUID,
        memberId: UUID,
        tuntasId: UUID
    ): Result<Unit> {
        return transaction {
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

            ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovyklė not found"))

            val existing = PastovykleMembers.selectAll()
                .where {
                    (PastovykleMembers.id eq memberId) and
                        (PastovykleMembers.pastovykleId eq pastovykleId) and
                        (PastovykleMembers.status eq "ACTIVE")
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Pastovyklės narys nerastas"))

            val openAssignments = PastovykleInventory.selectAll()
                .where {
                    (PastovykleInventory.pastovykleId eq pastovykleId) and
                        (PastovykleInventory.recipientUserId eq existing[PastovykleMembers.userId]) and
                        (PastovykleInventory.quantityReturned less PastovykleInventory.quantityAssigned)
                }
                .count()
            if (openAssignments > 0) {
                return@transaction Result.failure(Exception("Negalima pašalinti nario, kol jam yra išduotų negrąžintų daiktų"))
            }

            PastovykleMembers.update({ PastovykleMembers.id eq memberId }) {
                it[status] = "REMOVED"
            }
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

            val inventoryRows = PastovykleInventory.selectAll()
                .where { PastovykleInventory.pastovykleId eq pastovykleId }
                .toList()
            val itemIds = inventoryRows.map { it[PastovykleInventory.itemId] }.distinct()
            val itemNamesById = if (itemIds.isEmpty()) {
                emptyMap()
            } else {
                Items.select(Items.id, Items.name)
                    .where { Items.id inList itemIds }
                    .associate { it[Items.id] to it[Items.name] }
            }
            val list = inventoryRows.map { toInventoryResponse(it, itemNamesById) }

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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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
            if (request.recipientType == "MEMBER") {
                val memberUserId = recipientUUID
                    ?: return@transaction Result.failure(Exception("Recipient user is required for member issue"))
                if (!isActivePastovykleMember(pastovykleId, memberUserId)) {
                    return@transaction Result.failure(Exception("Gavėjas nėra aktyvus šios pastovyklės narys"))
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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }

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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            ensurePastovykle(eventId, pastovykleId)
                ?: return@transaction Result.failure(Exception("Pastovykle not found"))
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }
            val provider = request.provider.uppercase()
            if (provider !in listOf("UNIT", "UKVEDYS")) {
                return@transaction Result.failure(Exception("Provider must be UNIT or UKVEDYS"))
            }
            val dueAt = request.dueAt?.let {
                runCatching { kotlinx.datetime.Instant.parse(it) }
                    .getOrElse { return@transaction Result.failure(Exception("Invalid dueAt")) }
            }
            val responsibleUserId = request.responsibleUserId?.let {
                runCatching { UUID.fromString(it) }
                    .getOrElse { return@transaction Result.failure(Exception("Invalid responsible user ID")) }
            }
            if (responsibleUserId != null && !isActiveTuntasMember(responsibleUserId, tuntasId)) {
                return@transaction Result.failure(Exception("Responsible user is not an active tuntas member"))
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
                it[this.provider] = provider
                it[this.dueAt] = dueAt
                it[this.responsibleUserId] = responsibleUserId
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

    fun updateInventoryRequest(
        eventId: UUID,
        requestId: UUID,
        tuntasId: UUID,
        changedByUserId: UUID,
        request: UpdateEventInventoryRequestRequest
    ): Result<EventInventoryRequestResponse> = transaction {
        val event = ensureEvent(eventId, tuntasId)
            ?: return@transaction Result.failure(Exception("Event not found"))
        ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
        val existing = EventInventoryRequests.selectAll()
            .where {
                (EventInventoryRequests.id eq requestId) and
                    (EventInventoryRequests.eventId eq eventId)
            }
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Request not found"))

        val provider = request.provider?.uppercase()
        if (provider != null && provider !in listOf("UNIT", "UKVEDYS")) {
            return@transaction Result.failure(Exception("Provider must be UNIT or UKVEDYS"))
        }
        val dueAt = request.dueAt?.let {
            runCatching { kotlinx.datetime.Instant.parse(it) }
                .getOrElse { return@transaction Result.failure(Exception("Invalid dueAt")) }
        }
        val responsibleUserId = request.responsibleUserId?.let {
            runCatching { UUID.fromString(it) }
                .getOrElse { return@transaction Result.failure(Exception("Invalid responsible user ID")) }
        }
        if (responsibleUserId != null && !isActiveTuntasMember(responsibleUserId, tuntasId)) {
            return@transaction Result.failure(Exception("Responsible user is not an active tuntas member"))
        }

        val previousProvider = existing[EventInventoryRequests.provider]
        EventInventoryRequests.update({ EventInventoryRequests.id eq requestId }) {
            provider?.let { value -> it[EventInventoryRequests.provider] = value }
            when {
                request.clearDueAt -> it[EventInventoryRequests.dueAt] = null
                request.dueAt != null -> it[EventInventoryRequests.dueAt] = dueAt
            }
            when {
                request.clearResponsibleUserId -> it[EventInventoryRequests.responsibleUserId] = null
                request.responsibleUserId != null -> it[EventInventoryRequests.responsibleUserId] = responsibleUserId
            }
            if (
                request.clearDueAt ||
                request.dueAt != null ||
                request.clearResponsibleUserId ||
                request.responsibleUserId != null
            ) {
                it[EventInventoryRequests.reminderSentAt] = null
            }
            request.notes?.let { value -> it[EventInventoryRequests.notes] = value }
        }
        if (provider != null && provider != previousProvider) {
            EventInventoryRequestHistory.insert {
                it[EventInventoryRequestHistory.requestId] = requestId
                it[EventInventoryRequestHistory.fromProvider] = previousProvider
                it[EventInventoryRequestHistory.toProvider] = provider
                it[EventInventoryRequestHistory.changedByUserId] = changedByUserId
                it[EventInventoryRequestHistory.notes] = request.notes
                it[EventInventoryRequestHistory.createdAt] = kotlinx.datetime.Clock.System.now()
            }
        }

        Result.success(
            toInventoryRequestResponse(
                EventInventoryRequests.selectAll().where { EventInventoryRequests.id eq requestId }.first()
            )
        )
    }

    fun getInventoryReadiness(
        eventId: UUID,
        tuntasId: UUID
    ): Result<EventInventoryReadinessResponse> = transaction {
        val event = ensureEvent(eventId, tuntasId)
            ?: return@transaction Result.failure(Exception("Event not found"))
        val requests = EventInventoryRequests.selectAll()
            .where { EventInventoryRequests.eventId eq eventId }
            .toList()
        val totalQuantity = requests.sumOf { it[EventInventoryRequests.quantity] }
        val completedQuantity = requests
            .filter { it[EventInventoryRequests.status] in listOf("FULFILLED", "SELF_PROVIDED") }
            .sumOf { it[EventInventoryRequests.quantity] }
        val now = kotlinx.datetime.Clock.System.now()
        val overdueCount = requests.count {
            it[EventInventoryRequests.status] !in listOf("FULFILLED", "SELF_PROVIDED", "REJECTED") &&
                it[EventInventoryRequests.dueAt]?.let { dueAt -> dueAt < now } == true
        }
        val unassignedCount = requests.count {
            it[EventInventoryRequests.status] !in listOf("FULFILLED", "SELF_PROVIDED", "REJECTED") &&
                it[EventInventoryRequests.responsibleUserId] == null
        }

        val currentItems = EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .filter { it[EventInventoryItems.itemId] != null }
        val physicalItemIds = currentItems.mapNotNull { it[EventInventoryItems.itemId] }.distinct()
        val overlappingEvents = Events.selectAll()
            .where {
                (Events.tuntasId eq tuntasId) and
                    (Events.id neq eventId) and
                    (Events.status notInList listOf("CANCELLED", "COMPLETED"))
            }
            .filter {
                it[Events.startDate] <= event[Events.endDate] &&
                    it[Events.endDate] >= event[Events.startDate]
            }
        val overlappingEventIds = overlappingEvents.map { it[Events.id] }
        val overlappingNames = overlappingEvents.associate { it[Events.id] to it[Events.name] }
        val overlappingItems = if (physicalItemIds.isEmpty() || overlappingEventIds.isEmpty()) {
            emptyList()
        } else {
            EventInventoryItems.selectAll()
                .where {
                    (EventInventoryItems.eventId inList overlappingEventIds) and
                        (EventInventoryItems.itemId inList physicalItemIds)
                }
                .toList()
        }
        val stockByItemId = if (physicalItemIds.isEmpty()) emptyMap() else {
            Items.selectAll()
                .where { Items.id inList physicalItemIds }
                .associate { it[Items.id] to it[Items.quantity] }
        }
        val conflicts = currentItems.groupBy { it[EventInventoryItems.itemId]!! }
            .mapNotNull { (itemId, rows) ->
                val related = overlappingItems.filter { it[EventInventoryItems.itemId] == itemId }
                val requested = rows.sumOf { it[EventInventoryItems.plannedQuantity] } +
                    related.sumOf { it[EventInventoryItems.plannedQuantity] }
                val available = stockByItemId[itemId] ?: return@mapNotNull null
                if (requested <= available) return@mapNotNull null
                EventInventoryConflictResponse(
                    itemId = itemId.toString(),
                    itemName = rows.first()[EventInventoryItems.name],
                    availableQuantity = available,
                    requestedQuantity = requested,
                    overlappingEvents = related.mapNotNull { overlappingNames[it[EventInventoryItems.eventId]] }.distinct()
                )
            }

        Result.success(
            EventInventoryReadinessResponse(
                readinessPercent = if (totalQuantity == 0) 100 else (completedQuantity * 100 / totalQuantity),
                totalQuantity = totalQuantity,
                completedQuantity = completedQuantity,
                openQuantity = totalQuantity - completedQuantity,
                overdueCount = overdueCount,
                unassignedCount = unassignedCount,
                conflicts = conflicts
            )
        )
    }

    fun getEventInventoryRequests(
        eventId: UUID,
        tuntasId: UUID,
        requestedByUserId: UUID?,
        includeAll: Boolean
    ): Result<EventInventoryRequestListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found"))
            val requests = EventInventoryRequests.selectAll()
                .where {
                    (EventInventoryRequests.eventId eq eventId) and
                        (EventInventoryRequests.targetGroup eq "PROGRAMA") and
                        if (includeAll || requestedByUserId == null) {
                            Op.TRUE
                        } else {
                            EventInventoryRequests.requestedByUserId eq requestedByUserId
                        }
                }
                .orderBy(EventInventoryRequests.createdAt, SortOrder.DESC)
                .map { toInventoryRequestResponse(it) }

            Result.success(EventInventoryRequestListResponse(requests = requests, total = requests.size))
        }
    }

    fun createEventInventoryRequest(
        eventId: UUID,
        tuntasId: UUID,
        requestedByUserId: UUID,
        request: CreateEventInventoryRequestRequest
    ): Result<EventInventoryRequestResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            if (!canRequestEventInventory(eventId, tuntasId, requestedByUserId)) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }
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
                it[pastovykleId] = null
                it[targetGroup] = "PROGRAMA"
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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            if (existing[EventInventoryRequests.provider] != "UKVEDYS") {
                return@transaction Result.failure(Exception("Unit-provided needs are not reviewed by the event quartermaster"))
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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            if (existing[EventInventoryRequests.provider] != "UKVEDYS") {
                return@transaction Result.failure(Exception("Unit-provided needs are not reviewed by the event quartermaster"))
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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            val event = verifyStovyklaEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found or not of type STOVYKLA"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            if (existing[EventInventoryRequests.provider] != "UKVEDYS") {
                return@transaction Result.failure(Exception("Unit-provided needs cannot be fulfilled by the event quartermaster"))
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
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
                val reservationGroupId = getOrCreateEventReservationGroup(event, userId)
                syncEventReservationItem(
                    groupId = reservationGroupId,
                    event = event,
                    itemId = sourceItemId,
                    reservedByUserId = userId,
                    quantity = reservableQuantity,
                    notes = request.notes
                )?.let { error -> return@transaction Result.failure(error) }

                EventInventoryItems.insert {
                    it[this.eventId] = eventId
                    it[itemId] = sourceItemId
                    it[bucketId] = bucket[EventInventoryBuckets.id]
                    it[this.reservationGroupId] = reservationGroupId
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
                val groupId = getOrCreateEventReservationGroup(event, createdByUserId)
                syncEventReservationItem(groupId, event, it, createdByUserId, reservableQuantity, request.notes)
                    ?.let { error -> return@transaction Result.failure(error) }
                groupId
            }
            val available = if (itemUUID != null) reservableQuantity else 0
            val itemName = request.name.ifBlank { item?.get(Items.name).orEmpty() }
            val sourceSnapshot = item?.let { buildEventItemSourceSnapshot(it) }

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
                it[sourceCustodianName] = sourceSnapshot?.custodianName
                it[sourceLocationPath] = sourceSnapshot?.locationPath
                it[sourceTemporaryStorageLabel] = sourceSnapshot?.temporaryStorageLabel
                it[sourceResponsibleUserName] = sourceSnapshot?.responsibleUserName
                it[responsibleUserId] = responsibleUUID
                it[this.createdByUserId] = createdByUserId
                it[createdAt] = kotlinx.datetime.Clock.System.now()
            } get EventInventoryItems.id

            if (itemUUID != null) {
                insertInventorySource(
                    eventInventoryItemId = id,
                    itemId = itemUUID,
                    reservationGroupId = reservationGroupId,
                    plannedQuantity = request.plannedQuantity,
                    reservedQuantity = reservableQuantity,
                    snapshot = sourceSnapshot,
                    notes = request.notes
                )
            }

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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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

                reservationGroupId?.let { groupId ->
                    syncEventReservationItem(
                        groupId = groupId,
                        event = event,
                        itemId = itemId,
                        reservedByUserId = existing[EventInventoryItems.createdByUserId],
                        quantity = reservable,
                        notes = request.notes ?: existing[EventInventoryItems.notes]
                    )?.let { error -> return@transaction Result.failure(error) }
                }
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
            EventInventorySources.selectAll()
                .where { EventInventorySources.eventInventoryItemId eq inventoryItemId }
                .singleOrNull()
                ?.let { source ->
                    EventInventorySources.update({ EventInventorySources.id eq source[EventInventorySources.id] }) {
                        it[plannedQuantity] = nextPlanned
                        it[reservedQuantity] = nextAvailable
                        it[sourceStatus] = if (nextAvailable > 0) "RESERVED" else "SHORTAGE"
                        request.notes?.let { note -> it[notes] = note }
                    }
                }

            Result.success(toInventoryItemResponse(EventInventoryItems.selectAll().where { EventInventoryItems.id eq inventoryItemId }.first()))
        }
    }

    fun deleteInventoryItem(eventId: UUID, inventoryItemId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val existing = EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))
            existing[EventInventoryItems.reservationGroupId]?.let { groupId ->
                existing[EventInventoryItems.itemId]?.let { itemId -> cancelEventReservationItem(groupId, itemId) }
                    ?: cancelReservationGroup(groupId)
            }
            EventInventoryAllocations.deleteWhere { EventInventoryAllocations.eventInventoryItemId eq inventoryItemId }
            EventInventorySources.deleteWhere { EventInventorySources.eventInventoryItemId eq inventoryItemId }
            EventInventoryItems.deleteWhere { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
            Result.success(Unit)
        }
    }

    fun createInventorySource(
        eventId: UUID,
        inventoryItemId: UUID,
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateEventInventorySourceRequest
    ): Result<EventInventorySourceResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val need = EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq inventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory item not found"))
            if (request.plannedQuantity < 1) return@transaction Result.failure(Exception("Planned quantity must be at least 1"))
            val sourceItemId = request.itemId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid item ID"))
                }
            }
            val sourceItem = sourceItemId?.let {
                Items.selectAll()
                    .where { (Items.id eq it) and (Items.tuntasId eq tuntasId) and (Items.status eq "ACTIVE") }
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Item not found or not active"))
            }
            val reserved = sourceItem?.let {
                availableQuantityForEventItem(it[Items.id], event[Events.startDate], event[Events.endDate])
                    .coerceAtMost(request.plannedQuantity)
            } ?: 0
            val reservationGroupId = sourceItem?.takeIf { reserved > 0 }?.let {
                val groupId = getOrCreateEventReservationGroup(event, createdByUserId)
                syncEventReservationItem(groupId, event, it[Items.id], createdByUserId, reserved, request.notes)
                    ?.let { error -> return@transaction Result.failure(error) }
                groupId
            }
            val id = insertInventorySource(
                eventInventoryItemId = inventoryItemId,
                itemId = sourceItemId,
                reservationGroupId = reservationGroupId,
                plannedQuantity = request.plannedQuantity,
                reservedQuantity = reserved,
                snapshot = sourceItem?.let { buildEventItemSourceSnapshot(it) },
                notes = request.notes
            )
            refreshInventoryItemCoverage(need)
            Result.success(toInventorySourceResponse(EventInventorySources.selectAll().where { EventInventorySources.id eq id }.first()))
        }
    }

    fun updateInventorySource(
        eventId: UUID,
        sourceId: UUID,
        tuntasId: UUID,
        request: UpdateEventInventorySourceRequest
    ): Result<EventInventorySourceResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val source = EventInventorySources
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where { (EventInventorySources.id eq sourceId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory source not found"))
            request.plannedQuantity?.let {
                if (it < 1) return@transaction Result.failure(Exception("Planned quantity must be at least 1"))
            }
            request.sourceStatus?.let {
                if (it !in listOf("PLANNED", "RESERVED", "PARTIAL", "SHORTAGE", "CANCELLED")) {
                    return@transaction Result.failure(Exception("Invalid source status"))
                }
            }
            EventInventorySources.update({ EventInventorySources.id eq sourceId }) {
                request.plannedQuantity?.let { v -> it[plannedQuantity] = v }
                request.notes?.let { v -> it[notes] = v }
                request.sourceStatus?.let { v -> it[sourceStatus] = v }
            }
            refreshInventoryItemCoverage(source)
            Result.success(toInventorySourceResponse(EventInventorySources.selectAll().where { EventInventorySources.id eq sourceId }.first()))
        }
    }

    fun deleteInventorySource(eventId: UUID, sourceId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val source = EventInventorySources
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where { (EventInventorySources.id eq sourceId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Inventory source not found"))
            source[EventInventorySources.reservationGroupId]?.let { groupId ->
                source[EventInventorySources.itemId]?.let { itemId -> cancelEventReservationItem(groupId, itemId) }
            }
            EventInventorySources.deleteWhere { EventInventorySources.id eq sourceId }
            refreshInventoryItemCoverage(source)
            Result.success(Unit)
        }
    }

    fun createInventoryAllocation(
        eventId: UUID,
        tuntasId: UUID,
        request: CreateEventInventoryAllocationRequest
    ): Result<EventInventoryAllocationResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
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
            val hydration = buildCustodyHydration(rows)
            val custody = rows.map { toCustodyResponse(it, hydration) }
            Result.success(EventInventoryCustodyListResponse(custody = custody, total = custody.size))
        }
    }

    fun getInventoryMovements(eventId: UUID, tuntasId: UUID): Result<EventInventoryMovementListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val movements = EventInventoryMovements.selectAll()
                .where { EventInventoryMovements.eventId eq eventId }
                .orderBy(EventInventoryMovements.createdAt, SortOrder.DESC)
                .toList()
            val hydration = buildMovementHydration(movements)
            val responses = movements.map { toMovementResponse(it, hydration) }
            Result.success(EventInventoryMovementListResponse(movements = responses, total = responses.size))
        }
    }

    fun getInventoryTransferRequests(
        eventId: UUID,
        tuntasId: UUID,
        userId: UUID,
        includeAll: Boolean
    ): Result<EventInventoryTransferRequestListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            var query = EventInventoryTransferRequests.selectAll()
                .where { EventInventoryTransferRequests.eventId eq eventId }
            if (!includeAll) {
                query = query.andWhere {
                    (EventInventoryTransferRequests.requestedByUserId eq userId) or
                        (EventInventoryTransferRequests.requestedFromUserId eq userId)
                }
            }
            val rows = query
                .orderBy(EventInventoryTransferRequests.createdAt, SortOrder.DESC)
                .toList()
            val responses = toInventoryTransferRequestResponses(rows)
            Result.success(EventInventoryTransferRequestListResponse(responses, responses.size))
        }
    }

    fun createInventoryTransferRequest(
        eventId: UUID,
        tuntasId: UUID,
        requestedByUserId: UUID,
        request: CreateEventInventoryTransferRequest
    ): Result<EventInventoryTransferRequestResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found"))
            ensureMovementAllowedForEvent(event)
                ?: return@transaction Result.failure(Exception("Inventory transfers are allowed only during an active event"))
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }
            val custodyId = runCatching { UUID.fromString(request.sourceCustodyId) }.getOrNull()
                ?: return@transaction Result.failure(Exception("Invalid custody ID"))
            val custody = EventInventoryCustody
                .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                .selectAll()
                .where {
                    (EventInventoryCustody.id eq custodyId) and
                        (EventInventoryItems.eventId eq eventId) and
                        (EventInventoryCustody.status eq "OPEN")
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Custody record not found"))
            val holderUserId = custody[EventInventoryCustody.holderUserId]
                ?: return@transaction Result.failure(Exception("The item is not currently held by a person"))
            if (holderUserId == requestedByUserId) {
                return@transaction Result.failure(Exception("You cannot request an item from yourself"))
            }
            val remaining = openQuantity(custody)
            if (request.quantity > remaining) {
                return@transaction Result.failure(Exception("Requested quantity exceeds the holder's remaining quantity"))
            }
            val duplicate = EventInventoryTransferRequests.selectAll()
                .where {
                    (EventInventoryTransferRequests.sourceCustodyId eq custodyId) and
                        (EventInventoryTransferRequests.requestedByUserId eq requestedByUserId) and
                        (EventInventoryTransferRequests.status eq "PENDING")
                }
                .firstOrNull()
            if (duplicate != null) {
                return@transaction Result.failure(Exception("A pending request for this custody already exists"))
            }
            val requestId = EventInventoryTransferRequests.insert {
                it[this.eventId] = eventId
                it[sourceCustodyId] = custodyId
                it[eventInventoryItemId] = custody[EventInventoryCustody.eventInventoryItemId]
                it[this.requestedByUserId] = requestedByUserId
                it[requestedFromUserId] = holderUserId
                it[quantity] = request.quantity
                it[status] = "PENDING"
                it[notes] = request.notes?.trim()?.ifBlank { null }
                it[createdAt] = kotlinx.datetime.Clock.System.now()
            } get EventInventoryTransferRequests.id
            val row = EventInventoryTransferRequests.selectAll()
                .where { EventInventoryTransferRequests.id eq requestId }
                .first()
            Result.success(toInventoryTransferRequestResponses(listOf(row)).first())
        }
    }

    fun respondToInventoryTransferRequest(
        eventId: UUID,
        requestId: UUID,
        tuntasId: UUID,
        respondingUserId: UUID,
        canManageInventory: Boolean,
        request: RespondEventInventoryTransferRequest
    ): Result<EventInventoryTransferRequestResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId)
                ?: return@transaction Result.failure(Exception("Event not found"))
            ensureMovementAllowedForEvent(event)
                ?: return@transaction Result.failure(Exception("Inventory transfers are allowed only during an active event"))
            val transferRequest = EventInventoryTransferRequests.selectAll()
                .where {
                    (EventInventoryTransferRequests.id eq requestId) and
                        (EventInventoryTransferRequests.eventId eq eventId)
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Transfer request not found"))
            if (transferRequest[EventInventoryTransferRequests.status] != "PENDING") {
                return@transaction Result.failure(Exception("Only pending transfer requests can be answered"))
            }
            val requestedFromUserId = transferRequest[EventInventoryTransferRequests.requestedFromUserId]
            if (!canManageInventory && requestedFromUserId != respondingUserId) {
                return@transaction Result.failure(Exception("Only the current holder can answer this request"))
            }
            val now = kotlinx.datetime.Clock.System.now()
            var movementId: UUID? = null
            if (request.approve) {
                val source = EventInventoryCustody
                    .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                    .selectAll()
                    .where {
                        (EventInventoryCustody.id eq transferRequest[EventInventoryTransferRequests.sourceCustodyId]) and
                            (EventInventoryItems.eventId eq eventId) and
                            (EventInventoryCustody.status eq "OPEN")
                    }
                    .forUpdate()
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("The source custody is no longer available"))
                if (source[EventInventoryCustody.holderUserId] != requestedFromUserId) {
                    return@transaction Result.failure(Exception("The item is no longer held by the original holder"))
                }
                val quantity = transferRequest[EventInventoryTransferRequests.quantity]
                if (quantity > openQuantity(source)) {
                    return@transaction Result.failure(Exception("The holder no longer has enough quantity"))
                }
                val nextReturned = source[EventInventoryCustody.returnedQuantity] + quantity
                EventInventoryCustody.update({ EventInventoryCustody.id eq source[EventInventoryCustody.id] }) {
                    it[returnedQuantity] = nextReturned
                    if (nextReturned == source[EventInventoryCustody.quantity]) {
                        it[status] = "CLOSED"
                        it[closedAt] = now
                    }
                }
                val targetCustodyId = insertCustody(
                    eventInventoryItemId = source[EventInventoryCustody.eventInventoryItemId],
                    parentCustodyId = source[EventInventoryCustody.parentCustodyId],
                    pastovykleId = source[EventInventoryCustody.pastovykleId],
                    holderUserId = transferRequest[EventInventoryTransferRequests.requestedByUserId],
                    quantity = quantity,
                    createdByUserId = respondingUserId,
                    notes = request.notes?.trim()?.ifBlank { transferRequest[EventInventoryTransferRequests.notes] },
                    createdAt = now
                )
                movementId = insertInventoryMovement(
                    eventId = eventId,
                    eventInventoryItemId = source[EventInventoryCustody.eventInventoryItemId],
                    custodyId = targetCustodyId,
                    inventoryRequestId = null,
                    movementType = "TRANSFER",
                    quantity = quantity,
                    fromPastovykleId = source[EventInventoryCustody.pastovykleId],
                    toPastovykleId = source[EventInventoryCustody.pastovykleId],
                    fromUserId = requestedFromUserId,
                    toUserId = transferRequest[EventInventoryTransferRequests.requestedByUserId],
                    performedByUserId = respondingUserId,
                    clientRequestId = "transfer-request-$requestId",
                    notes = request.notes?.trim()?.ifBlank { transferRequest[EventInventoryTransferRequests.notes] },
                    createdAt = now
                )
            }
            EventInventoryTransferRequests.update({ EventInventoryTransferRequests.id eq requestId }) {
                it[status] = if (request.approve) "APPROVED" else "REJECTED"
                it[respondedAt] = now
                it[respondedByUserId] = respondingUserId
                it[EventInventoryTransferRequests.movementId] = movementId
                request.notes?.trim()?.takeIf { value -> value.isNotBlank() }?.let { value -> it[notes] = value }
            }
            val updated = EventInventoryTransferRequests.selectAll()
                .where { EventInventoryTransferRequests.id eq requestId }
                .first()
            Result.success(toInventoryTransferRequestResponses(listOf(updated)).first())
        }
    }

    fun getReconciliation(eventId: UUID, tuntasId: UUID): Result<EventReconciliationResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            Result.success(toReconciliationResponse(event))
        }
    }

    fun reconcileReturns(
        eventId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: ReconcileEventReturnsRequest
    ): Result<EventReconciliationResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (event[Events.status] != "WRAP_UP") {
                return@transaction Result.failure(Exception("Returns can be reconciled only during wrap-up"))
            }
            val sessionId = getOrCreateEventReturnSession(eventId, tuntasId, userId)

            request.returns.forEach { line ->
                val decision = line.decision.uppercase()
                if (decision !in listOf("RETURNED", "DAMAGED", "MISSING", "CONSUMED")) {
                    return@transaction Result.failure(Exception("Invalid return decision"))
                }
                if (line.quantity < 1) {
                    return@transaction Result.failure(Exception("Quantity must be at least 1"))
                }
                val custodyId = try { UUID.fromString(line.custodyId) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid custody ID"))
                }
                val custody = EventInventoryCustody
                    .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
                    .selectAll()
                    .where {
                        (EventInventoryCustody.id eq custodyId) and
                            (EventInventoryItems.eventId eq eventId)
                    }
                    .forUpdate()
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Custody record not found"))

                val remaining = openQuantity(custody)
                if (line.quantity > remaining) {
                    return@transaction Result.failure(Exception("Return decision quantity exceeds remaining quantity"))
                }
                val returnToMode = line.returnToMode?.uppercase()
                if (returnToMode != null && returnToMode !in listOf("ORIGINAL_SOURCE", "EVENT_STORAGE", "OTHER_LOCATION")) {
                    return@transaction Result.failure(Exception("Invalid return destination"))
                }
                if (decision in listOf("MISSING", "CONSUMED") && (line.returnLocationId != null || line.returnLocationNote != null || returnToMode != null)) {
                    return@transaction Result.failure(Exception("Missing or consumed items cannot have a return destination"))
                }
                val returnLocationId = line.returnLocationId?.let {
                    try { UUID.fromString(it) } catch (e: Exception) {
                        return@transaction Result.failure(Exception("Invalid return location ID"))
                    }
                }
                returnLocationId?.let {
                    Locations.selectAll()
                        .where { (Locations.id eq it) and (Locations.tuntasId eq tuntasId) }
                        .firstOrNull() ?: return@transaction Result.failure(Exception("Return location not found"))
                }

                val now = kotlinx.datetime.Clock.System.now()
                val nextReturned = custody[EventInventoryCustody.returnedQuantity] + line.quantity
                EventInventoryCustody.update({ EventInventoryCustody.id eq custodyId }) {
                    it[returnedQuantity] = nextReturned
                    if (nextReturned == custody[EventInventoryCustody.quantity]) {
                        it[status] = if (decision == "RETURNED") "RETURNED" else "CLOSED"
                        it[closedAt] = now
                    }
                    it[notes] = listOfNotNull(custody[EventInventoryCustody.notes], "${decision}: ${line.notes.orEmpty()}".trim())
                        .filter { note -> note.isNotBlank() }
                        .joinToString(" | ")
                }

                val sourceItemId = custody[EventInventoryItems.itemId]
                if (sourceItemId != null && decision in listOf("MISSING", "CONSUMED")) {
                    val item = Items.selectAll()
                        .where { (Items.id eq sourceItemId) and (Items.tuntasId eq tuntasId) }
                        .forUpdate()
                        .firstOrNull()
                    if (item != null) {
                        Items.update({ Items.id eq sourceItemId }) {
                            it[quantity] = (item[Items.quantity] - line.quantity).coerceAtLeast(0)
                            it[updatedAt] = now
                        }
                    }
                }
                if (sourceItemId != null && decision == "DAMAGED") {
                    val item = Items.selectAll()
                        .where { (Items.id eq sourceItemId) and (Items.tuntasId eq tuntasId) }
                        .forUpdate()
                        .firstOrNull()
                    if (item != null) {
                        val previousCondition = item[Items.condition]
                        Items.update({ Items.id eq sourceItemId }) {
                            it[condition] = "DAMAGED"
                            it[updatedAt] = now
                        }
                        if (previousCondition != "DAMAGED") {
                            ItemConditionLog.insert {
                                it[this.itemId] = sourceItemId
                                it[this.previousCondition] = previousCondition
                                it[this.newCondition] = "DAMAGED"
                                it[this.reportedByUserId] = userId
                                it[this.reportedAt] = now
                                it[this.notes] = line.notes ?: "Renginio suvedimas: DAMAGED"
                            }
                        }
                    }
                }

                ItemChecks.insert {
                    it[this.sessionId] = sessionId
                    it[itemId] = sourceItemId
                    it[eventInventoryItemId] = custody[EventInventoryCustody.eventInventoryItemId]
                    it[this.custodyId] = custodyId
                    it[result] = when (decision) {
                        "RETURNED" -> "RETURNED"
                        "DAMAGED" -> "DAMAGED"
                        "MISSING" -> "MISSING"
                        else -> "CONSUMED"
                    }
                    it[quantity] = line.quantity
                    it[expectedQuantity] = custody[EventInventoryCustody.quantity]
                    it[actualQuantity] = line.quantity
                    it[actualLocationId] = if (decision in listOf("RETURNED", "DAMAGED")) returnLocationId else null
                    it[actualLocationNote] = if (decision in listOf("RETURNED", "DAMAGED")) {
                        buildReturnLocationNote(returnToMode, line.returnLocationNote)
                    } else null
                    it[conditionAtCheck] = when (decision) {
                        "RETURNED" -> itemConditionLabel(sourceItemId)
                        "DAMAGED" -> "DAMAGED"
                        "MISSING" -> "MISSING"
                        else -> "CONSUMED"
                    }
                    it[checkedByUserId] = userId
                    it[notes] = line.notes
                    it[checkedAt] = now
                }

                insertInventoryMovement(
                    eventId = eventId,
                    eventInventoryItemId = custody[EventInventoryCustody.eventInventoryItemId],
                    custodyId = custodyId,
                    inventoryRequestId = null,
                    movementType = "RECONCILE_$decision",
                    quantity = line.quantity,
                    fromPastovykleId = custody[EventInventoryCustody.pastovykleId],
                    toPastovykleId = null,
                    fromUserId = custody[EventInventoryCustody.holderUserId],
                    toUserId = null,
                    performedByUserId = userId,
                    clientRequestId = null,
                    notes = buildMovementNote(line.notes, returnToMode, line.returnLocationNote),
                    createdAt = now
                )
                sourceItemId?.let { itemId ->
                    val quantityChange = when (decision) {
                        "MISSING", "CONSUMED" -> -line.quantity
                        "RETURNED" -> line.quantity
                        else -> 0
                    }
                    ItemService.recordItemHistory(
                        itemId = itemId,
                        eventType = "EVENT_RECONCILE_$decision",
                        quantityChange = quantityChange,
                        performedByUserId = userId,
                        notes = line.notes ?: "Renginio suvedimas: $decision",
                        createdAt = now
                    )
                }
            }

            Result.success(toReconciliationResponse(event))
        }
    }

    fun reconcilePurchases(
        eventId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: ReconcileEventPurchasesRequest
    ): Result<EventReconciliationResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (event[Events.status] != "WRAP_UP") {
                return@transaction Result.failure(Exception("Purchases can be reconciled only during wrap-up"))
            }

            val touchedPurchaseIds = mutableSetOf<UUID>()
            request.purchases.forEach { line ->
                val decision = line.decision.uppercase()
                if (decision !in listOf("ADD_NEW_ITEM", "INCREASE_EXISTING_ITEM", "CONSUMED", "IGNORE")) {
                    return@transaction Result.failure(Exception("Invalid purchase decision"))
                }
                if (line.quantity < 1) {
                    return@transaction Result.failure(Exception("Quantity must be at least 1"))
                }
                val purchaseItemId = try { UUID.fromString(line.purchaseItemId) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase item ID"))
                }
                val row = EventPurchaseItems
                    .innerJoin(EventPurchases, { purchaseId }, { id })
                    .innerJoin(EventInventoryItems, { EventPurchaseItems.eventInventoryItemId }, { EventInventoryItems.id })
                    .selectAll()
                    .where {
                        (EventPurchaseItems.id eq purchaseItemId) and
                            (EventPurchases.eventId eq eventId)
                    }
                    .forUpdate()
                    .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase item not found"))
                val purchaseId = row[EventPurchaseItems.purchaseId]
                touchedPurchaseIds += purchaseId
                if (row[EventPurchaseItems.addedToInventory]) {
                    return@transaction Result.failure(Exception("Purchase item already reconciled"))
                }
                val alreadyReconciled = reconciledPurchaseQuantity(purchaseItemId)
                val remainingQuantity = row[EventPurchaseItems.purchasedQuantity] - alreadyReconciled
                if (line.quantity > remainingQuantity) {
                    return@transaction Result.failure(Exception("Purchase decision quantity exceeds remaining purchased quantity"))
                }

                val now = kotlinx.datetime.Clock.System.now()
                val addedItemId = when (decision) {
                    "ADD_NEW_ITEM" -> {
                        val createdItemId = Items.insert {
                            it[Items.tuntasId] = tuntasId
                            it[custodianId] = null
                            it[origin] = "UNIT_ACQUIRED"
                            it[name] = line.name?.takeIf { value -> value.isNotBlank() } ?: row[EventInventoryItems.name]
                            it[description] = row[EventInventoryItems.notes]
                            it[type] = "COLLECTIVE"
                            it[category] = "TOOLS"
                            it[condition] = "GOOD"
                            it[quantity] = line.quantity
                            it[temporaryStorageLabel] = "Renginio suvedimas"
                            it[responsibleUserId] = row[EventPurchases.purchasedByUserId]
                            it[createdByUserId] = userId
                            it[qrToken] = UUID.randomUUID().toString()
                            it[purchaseDate] = row[EventPurchases.purchaseDate]
                            it[purchasePrice] = row[EventPurchaseItems.unitPrice]
                            it[notes] = line.notes ?: "Sukurta renginio suvedimo metu"
                            it[status] = "ACTIVE"
                            it[createdAt] = now
                            it[updatedAt] = now
                        } get Items.id
                        ItemService.recordItemHistory(
                            itemId = createdItemId,
                            eventType = "EVENT_PURCHASED_NEW",
                            quantityChange = line.quantity,
                            performedByUserId = userId,
                            notes = line.notes ?: "Nupirkta renginiui ir sukurta inventoriuje",
                            createdAt = now
                        )
                        createdItemId
                    }
                    "INCREASE_EXISTING_ITEM" -> {
                        val existingItemId = try {
                            UUID.fromString(line.existingItemId ?: return@transaction Result.failure(Exception("existingItemId is required")))
                        } catch (e: Exception) {
                            return@transaction Result.failure(Exception("Invalid existing item ID"))
                        }
                        val expectedItemId = row[EventInventoryItems.itemId]
                        if (expectedItemId != null && existingItemId != expectedItemId) {
                            return@transaction Result.failure(
                                Exception("Pasirinktas daiktas neatitinka pirkime nurodyto: '${row[EventInventoryItems.name]}'")
                            )
                        }
                        if (expectedItemId == null) {
                            println("Free-form event purchase '${row[EventInventoryItems.name]}' reconciled into inventory item $existingItemId")
                        }
                        val existing = Items.selectAll()
                            .where { (Items.id eq existingItemId) and (Items.tuntasId eq tuntasId) and (Items.status eq "ACTIVE") }
                            .forUpdate()
                            .firstOrNull() ?: return@transaction Result.failure(Exception("Existing item not found"))
                        Items.update({ Items.id eq existingItemId }) {
                            it[quantity] = existing[Items.quantity] + line.quantity
                            it[updatedAt] = now
                            line.notes?.let { note -> it[notes] = note }
                        }
                        ItemService.recordItemHistory(
                            itemId = existingItemId,
                            eventType = "EVENT_PURCHASE_RESTOCKED",
                            quantityChange = line.quantity,
                            performedByUserId = userId,
                            notes = line.notes ?: "Papildyta po renginio pirkimo",
                            createdAt = now
                        )
                        existingItemId
                    }
                    else -> null
                }

                addedItemId?.let { itemId ->
                    row[EventPurchases.invoiceFileUrl]?.let { invoice ->
                        ItemAttachments.insert {
                            it[this.itemId] = itemId
                            it[fileUrl] = invoice
                            it[fileType] = "INVOICE"
                            it[uploadedByUserId] = userId
                            it[uploadedAt] = now
                        }
                    }
                }

                EventPurchaseItemReconciliations.insert {
                    it[this.purchaseItemId] = purchaseItemId
                    it[this.decision] = decision
                    it[this.quantity] = line.quantity
                    it[addedInventoryItemId] = addedItemId
                    it[performedByUserId] = userId
                    it[notes] = line.notes
                    it[createdAt] = now
                }
                val fullyReconciled = alreadyReconciled + line.quantity >= row[EventPurchaseItems.purchasedQuantity]
                EventPurchaseItems.update({ EventPurchaseItems.id eq purchaseItemId }) {
                    it[addedToInventory] = fullyReconciled
                    if (addedItemId != null) it[addedToInventoryItemId] = addedItemId
                    it[notes] = listOfNotNull(row[EventPurchaseItems.notes], "${decision}: ${line.notes.orEmpty()}".trim())
                        .filter { note -> note.isNotBlank() }
                        .joinToString(" | ")
                }
            }

            touchedPurchaseIds.forEach { purchaseId ->
                val hasOpenLines = EventPurchaseItems.selectAll()
                    .where {
                        (EventPurchaseItems.purchaseId eq purchaseId) and
                            (EventPurchaseItems.addedToInventory eq false)
                    }
                    .any()
                if (!hasOpenLines) {
                    EventPurchases.update({
                        (EventPurchases.id eq purchaseId) and
                            (EventPurchases.status eq "PURCHASED")
                    }) {
                        it[status] = "ADDED_TO_INVENTORY"
                        it[updatedAt] = kotlinx.datetime.Clock.System.now()
                    }
                }
            }

            Result.success(toReconciliationResponse(event))
        }
    }

    fun getPurchaseReconciliationCandidates(
        eventId: UUID,
        tuntasId: UUID,
        purchaseItemId: UUID
    ): Result<EventPurchaseReconciliationCandidateListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))

            val purchaseItem = EventPurchaseItems
                .innerJoin(EventPurchases, { purchaseId }, { id })
                .innerJoin(EventInventoryItems, { EventPurchaseItems.eventInventoryItemId }, { id })
                .selectAll()
                .where {
                    (EventPurchaseItems.id eq purchaseItemId) and
                        (EventPurchases.eventId eq eventId)
                }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase item not found"))

            val purchaseName = purchaseItem[EventInventoryItems.name].trim()
            val expectedItemId = purchaseItem[EventInventoryItems.itemId]
            val candidates = Items.selectAll()
                .where {
                    (Items.tuntasId eq tuntasId) and
                        (Items.status eq "ACTIVE")
                }
                .filter { row ->
                    expectedItemId == row[Items.id] ||
                        purchaseName.isBlank() ||
                        row[Items.name].contains(purchaseName, ignoreCase = true) ||
                        purchaseName.contains(row[Items.name], ignoreCase = true)
                }
                .sortedWith(
                    compareByDescending<ResultRow> { expectedItemId != null && it[Items.id] == expectedItemId }
                        .thenBy { it[Items.name].lowercase() }
                )
                .map { row ->
                    val custodianName = row[Items.custodianId]?.let { custodianId ->
                        OrganizationalUnits.select(OrganizationalUnits.name)
                            .where { OrganizationalUnits.id eq custodianId }
                            .firstOrNull()
                            ?.get(OrganizationalUnits.name)
                    }
                    EventPurchaseReconciliationCandidateResponse(
                        itemId = row[Items.id].toString(),
                        name = row[Items.name],
                        quantity = row[Items.quantity],
                        custodianId = row[Items.custodianId]?.toString(),
                        custodianName = custodianName,
                        recommended = expectedItemId != null && row[Items.id] == expectedItemId
                    )
                }

            Result.success(EventPurchaseReconciliationCandidateListResponse(candidates, candidates.size))
        }
    }

    fun completeEvent(eventId: UUID, tuntasId: UUID): Result<EventResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            if (event[Events.status] != "WRAP_UP") {
                return@transaction Result.failure(Exception("Event can be completed only during wrap-up"))
            }
            val blocking = reconciliationBlockingCounts(eventId)
            if (blocking.first > 0 || blocking.second > 0) {
                return@transaction Result.failure(Exception("Event cannot be completed while reconciliation has unresolved returns or purchases"))
            }
            val now = kotlinx.datetime.Clock.System.now()
            Events.update({ (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }) {
                it[status] = "COMPLETED"
            }
            ItemCheckSessions.update({
                (ItemCheckSessions.eventId eq eventId) and
                    (ItemCheckSessions.contextType eq "EVENT_RETURN") and
                    (ItemCheckSessions.status eq "OPEN")
            }) {
                it[status] = "COMPLETED"
                it[completedAt] = now
            }
            Result.success(toEventResponse(Events.selectAll().where { Events.id eq eventId }.first()))
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
            ensureMovementAllowedForEvent(event) ?: return@transaction Result.failure(Exception("Inventoriaus judėjimas leidžiamas tik aktyvaus renginio metu"))
            if (request.movementType !in validInventoryMovementTypes) {
                return@transaction Result.failure(Exception("Invalid movement type"))
            }
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val requestedInventoryItemId = try {
                UUID.fromString(request.eventInventoryItemId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid event inventory item ID"))
            }
            val item = EventInventoryItems.selectAll()
                .where { (EventInventoryItems.id eq requestedInventoryItemId) and (EventInventoryItems.eventId eq eventId) }
                .firstOrNull()
                ?: createEventInventoryItemForSourceItem(eventId, tuntasId, requestedInventoryItemId, performedByUserId)
                ?: return@transaction Result.failure(Exception("Inventory item not found"))
            val eventInventoryItemId = item[EventInventoryItems.id]

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

            val responsiblePastovykleIds = Pastovykles.selectAll()
                .where {
                    (Pastovykles.eventId eq eventId) and
                        (Pastovykles.responsibleUserId eq performedByUserId)
                }
                .map { it[Pastovykles.id] }
                .toSet()
            fun isResponsiblePastovykle(pastovykleId: UUID?): Boolean =
                pastovykleId != null && pastovykleId in responsiblePastovykleIds

            if (!canManageInventory && movementType !in listOf("PASTOVYKLE_REQUEST", "CHECKOUT_TO_PERSON", "RETURN_TO_PASTOVYKLE", "RETURN_TO_EVENT_STORAGE", "TRANSFER")) {
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
                    val targetUserId = if (canManageInventory || isResponsiblePastovykle(pastovykleId)) {
                        toUserId ?: performedByUserId
                    } else {
                        performedByUserId
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
                    val isResponsibleReturn = isResponsiblePastovykle(source[EventInventoryCustody.pastovykleId])
                    if (!canManageInventory && !isResponsibleReturn && holderId != performedByUserId) {
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
                    val source = sourceCustody ?: return@transaction Result.failure(Exception("fromCustodyId is required"))
                    val sourcePastovykleId = source[EventInventoryCustody.pastovykleId]
                    val isResponsibleTransfer = isResponsiblePastovykle(sourcePastovykleId)
                    if (!canManageInventory && !isResponsibleTransfer) {
                        return@transaction Result.failure(Exception("Insufficient permissions"))
                    }
                    val remaining = source[EventInventoryCustody.quantity] - source[EventInventoryCustody.returnedQuantity]
                    if (request.quantity > remaining) {
                        return@transaction Result.failure(Exception("Transfer quantity exceeds remaining quantity"))
                    }
                    val targetPastovykleId = pastovykleId ?: source[EventInventoryCustody.pastovykleId]
                    if (!canManageInventory && targetPastovykleId != sourcePastovykleId) {
                        return@transaction Result.failure(Exception("Pastovykle responsible member can transfer only within their pastovykle"))
                    }
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

    private fun createEventInventoryItemForSourceItem(
        eventId: UUID,
        tuntasId: UUID,
        sourceItemId: UUID,
        performedByUserId: UUID
    ): ResultRow? {
        val sourceItem = Items.selectAll()
            .where {
                (Items.id eq sourceItemId) and
                    (Items.tuntasId eq tuntasId) and
                    (Items.status eq "ACTIVE")
            }
            .firstOrNull() ?: return null

        val bucket = EventInventoryBuckets.selectAll()
            .where {
                (EventInventoryBuckets.eventId eq eventId) and
                    (EventInventoryBuckets.type eq "OTHER") and
                    (EventInventoryBuckets.name eq "Renginio inventorius")
            }
            .firstOrNull()
            ?: run {
                val bucketId = EventInventoryBuckets.insert {
                    it[this.eventId] = eventId
                    it[name] = "Renginio inventorius"
                    it[type] = "OTHER"
                    it[pastovykleId] = null
                    it[locationId] = null
                    it[notes] = "Automatiškai sukurta inventoriaus judėjimui"
                } get EventInventoryBuckets.id
                EventInventoryBuckets.selectAll().where { EventInventoryBuckets.id eq bucketId }.first()
            }

        val quantity = sourceItem[Items.quantity].coerceAtLeast(1)
        val sourceSnapshot = buildEventItemSourceSnapshot(sourceItem)
        val eventInventoryItemId = EventInventoryItems.insert {
            it[this.eventId] = eventId
            it[itemId] = sourceItemId
            it[bucketId] = bucket[EventInventoryBuckets.id]
            it[reservationGroupId] = null
            it[name] = sourceItem[Items.name]
            it[plannedQuantity] = quantity
            it[availableQuantity] = quantity
            it[needsPurchase] = false
            it[notes] = "FROM_TUNTAS_INVENTORY"
            it[sourceCustodianName] = sourceSnapshot.custodianName
            it[sourceLocationPath] = sourceSnapshot.locationPath
            it[sourceTemporaryStorageLabel] = sourceSnapshot.temporaryStorageLabel
            it[sourceResponsibleUserName] = sourceSnapshot.responsibleUserName
            it[responsibleUserId] = sourceItem[Items.responsibleUserId]
            it[createdByUserId] = performedByUserId
            it[createdAt] = kotlinx.datetime.Clock.System.now()
        } get EventInventoryItems.id

        return EventInventoryItems.selectAll().where { EventInventoryItems.id eq eventInventoryItemId }.first()
    }

    fun getPurchases(
        eventId: UUID,
        tuntasId: UUID,
        limit: Int? = null,
        offset: Int = 0
    ): Result<EventPurchaseListResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val query = EventPurchases.selectAll()
                .where { EventPurchases.eventId eq eventId }
                .orderBy(EventPurchases.createdAt, SortOrder.DESC)
            val total = query.count().toInt()
            val purchases = (limit?.let { query.limit(it, offset.toLong()) } ?: query).toList()
            val hydration = buildPurchaseHydration(purchases)
            val responses = purchases.map { toPurchaseResponse(it, hydration) }
            Result.success(
                EventPurchaseListResponse(
                    purchases = responses,
                    total = total,
                    limit = limit,
                    offset = offset,
                    hasMore = limit != null && offset + responses.size < total
                )
            )
        }
    }

    fun createPurchase(
        eventId: UUID,
        tuntasId: UUID,
        purchasedByUserId: UUID,
        request: CreateEventPurchaseRequest
    ): Result<EventPurchaseResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            if (request.items.isEmpty()) return@transaction Result.failure(Exception("Purchase must include at least one item"))

            val purchaseDate = request.purchaseDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }

            data class ValidatedPurchaseLine(
                val eventInventoryItemId: UUID,
                val purchasedQuantity: Int,
                val unitPrice: BigDecimal?,
                val notes: String?
            )

            val validatedItems = request.items.map { line ->
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
                val alreadyInActivePurchase = EventPurchaseItems
                    .innerJoin(EventPurchases, { EventPurchaseItems.purchaseId }, { EventPurchases.id })
                    .selectAll()
                    .where {
                        (EventPurchaseItems.eventInventoryItemId eq inventoryItemId) and
                            (EventPurchases.eventId eq eventId) and
                            (EventPurchases.status inList listOf("DRAFT", "PURCHASED"))
                    }
                    .any()
                if (alreadyInActivePurchase) {
                    return@transaction Result.failure(Exception("Daiktas jau itrauktas i aktyvu pirkima. Uzbaikite arba atsaukite esama pirkima pries kurdami nauja."))
                }
                ValidatedPurchaseLine(
                    eventInventoryItemId = inventoryItemId,
                    purchasedQuantity = line.purchasedQuantity,
                    unitPrice = line.unitPrice?.toBigDecimal(),
                    notes = line.notes
                )
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

            validatedItems.forEach { line ->
                EventPurchaseItems.insert {
                    it[this.purchaseId] = purchaseId
                    it[eventInventoryItemId] = line.eventInventoryItemId
                    it[purchasedQuantity] = line.purchasedQuantity
                    it[unitPrice] = line.unitPrice
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val existing = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .forUpdate()
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))

            request.status?.let {
                if (it !in validPurchaseStatuses) return@transaction Result.failure(Exception("Invalid purchase status"))
                if (it == "PURCHASED") {
                    return@transaction Result.failure(Exception("Use purchase completion endpoint to mark purchase as purchased"))
                }
                if (existing[EventPurchases.status] == "PURCHASED" && it == "CANCELLED") {
                    return@transaction Result.failure(Exception("Completed purchase cannot be cancelled"))
                }
                if (existing[EventPurchases.status] == "CANCELLED" && it != "CANCELLED") {
                    return@transaction Result.failure(Exception("Cancelled purchase cannot be reopened"))
                }
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
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val existing = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            if (request.invoiceFileUrl.isBlank()) {
                return@transaction Result.failure(Exception("Invoice file URL cannot be blank"))
            }
            val alreadyAttached = EventPurchaseInvoices.selectAll()
                .where {
                    (EventPurchaseInvoices.purchaseId eq purchaseId) and
                        (EventPurchaseInvoices.fileUrl eq request.invoiceFileUrl)
                }
                .firstOrNull() != null
            if (!alreadyAttached) {
                EventPurchaseInvoices.insert {
                    it[id] = UUID.randomUUID()
                    it[EventPurchaseInvoices.purchaseId] = purchaseId
                    it[fileUrl] = request.invoiceFileUrl
                    it[createdAt] = kotlinx.datetime.Clock.System.now()
                }
            }
            EventPurchases.update({ (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }) {
                if (existing[EventPurchases.invoiceFileUrl].isNullOrBlank()) {
                    it[invoiceFileUrl] = request.invoiceFileUrl
                }
                it[updatedAt] = kotlinx.datetime.Clock.System.now()
            }
            Result.success(toPurchaseResponse(EventPurchases.selectAll().where { EventPurchases.id eq purchaseId }.first()))
        }
    }

    fun getPurchaseInvoiceFileName(eventId: UUID, purchaseId: UUID, tuntasId: UUID, invoiceId: UUID? = null): Result<String> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            val purchase = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            val invoiceUrl = if (invoiceId != null) {
                EventPurchaseInvoices.selectAll()
                    .where {
                        (EventPurchaseInvoices.id eq invoiceId) and
                            (EventPurchaseInvoices.purchaseId eq purchaseId)
                    }
                    .firstOrNull()
                    ?.get(EventPurchaseInvoices.fileUrl)
                    ?: return@transaction Result.failure(Exception("Invoice not attached"))
            } else {
                EventPurchaseInvoices.selectAll()
                    .where { EventPurchaseInvoices.purchaseId eq purchaseId }
                    .orderBy(EventPurchaseInvoices.createdAt to SortOrder.ASC)
                    .firstOrNull()
                    ?.get(EventPurchaseInvoices.fileUrl)
                    ?: purchase[EventPurchases.invoiceFileUrl]
                    ?: return@transaction Result.failure(Exception("Invoice not attached"))
            }
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

    fun getEventFinance(eventId: UUID, tuntasId: UUID): Result<EventFinanceResponse> {
        return transaction {
            ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            Result.success(toFinanceResponse(eventId))
        }
    }

    fun updateEventFinanceBudget(
        eventId: UUID,
        tuntasId: UUID,
        request: UpdateEventFinanceBudgetRequest
    ): Result<EventFinanceResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val amount = request.inventoryBudgetAmount?.let {
                if (it < 0.0) return@transaction Result.failure(Exception("Budget cannot be negative"))
                it.toMoney()
            }
            Events.update({ (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }) {
                it[inventoryBudgetAmount] = amount
                it[updatedAt] = kotlinx.datetime.Clock.System.now()
            }
            Result.success(toFinanceResponse(eventId))
        }
    }

    fun createEventExtraCost(
        eventId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: CreateEventExtraCostRequest
    ): Result<EventFinanceResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val category = validateExtraCostCategory(request.category)
                ?: return@transaction Result.failure(Exception("Invalid extra cost category"))
            val label = request.label.trim().ifBlank {
                return@transaction Result.failure(Exception("Extra cost label cannot be blank"))
            }
            val quantity = request.quantity?.let {
                if (it < 0.0) return@transaction Result.failure(Exception("Quantity cannot be negative"))
                it.toMoney()
            }
            val unitPrice = request.unitPrice?.let {
                if (it < 0.0) return@transaction Result.failure(Exception("Unit price cannot be negative"))
                it.toMoney()
            }
            val totalAmount = resolveExtraCostTotal(quantity, unitPrice, request.totalAmount)
                ?: return@transaction Result.failure(Exception("Extra cost total amount is required"))
            val now = kotlinx.datetime.Clock.System.now()
            EventExtraCosts.insert {
                it[id] = UUID.randomUUID()
                it[EventExtraCosts.eventId] = eventId
                it[EventExtraCosts.category] = category
                it[EventExtraCosts.label] = label
                it[EventExtraCosts.quantity] = quantity
                it[unit] = request.unit?.trim()?.ifBlank { null }
                it[EventExtraCosts.unitPrice] = unitPrice
                it[EventExtraCosts.totalAmount] = totalAmount
                it[notes] = request.notes?.trim()?.ifBlank { null }
                it[createdByUserId] = userId
                it[createdAt] = now
                it[updatedAt] = now
            }
            Result.success(toFinanceResponse(eventId))
        }
    }

    fun updateEventExtraCost(
        eventId: UUID,
        tuntasId: UUID,
        costId: UUID,
        request: UpdateEventExtraCostRequest
    ): Result<EventFinanceResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val existing = EventExtraCosts.selectAll()
                .where { (EventExtraCosts.id eq costId) and (EventExtraCosts.eventId eq eventId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Extra cost not found"))
            val category = request.category?.let {
                validateExtraCostCategory(it) ?: return@transaction Result.failure(Exception("Invalid extra cost category"))
            }
            val label = request.label?.trim()?.ifBlank {
                return@transaction Result.failure(Exception("Extra cost label cannot be blank"))
            }
            val quantity = request.quantity?.let {
                if (it < 0.0) return@transaction Result.failure(Exception("Quantity cannot be negative"))
                it.toMoney()
            }
            val unitPrice = request.unitPrice?.let {
                if (it < 0.0) return@transaction Result.failure(Exception("Unit price cannot be negative"))
                it.toMoney()
            }
            val resolvedQuantity = quantity ?: existing[EventExtraCosts.quantity]
            val resolvedUnitPrice = unitPrice ?: existing[EventExtraCosts.unitPrice]
            val totalAmount = resolveExtraCostTotal(resolvedQuantity, resolvedUnitPrice, request.totalAmount)
                ?: existing[EventExtraCosts.totalAmount]
            EventExtraCosts.update({ (EventExtraCosts.id eq costId) and (EventExtraCosts.eventId eq eventId) }) {
                category?.let { value -> it[EventExtraCosts.category] = value }
                label?.let { value -> it[EventExtraCosts.label] = value }
                if (request.quantity != null) it[EventExtraCosts.quantity] = quantity
                if (request.unit != null) it[unit] = request.unit.trim().ifBlank { null }
                if (request.unitPrice != null) it[EventExtraCosts.unitPrice] = unitPrice
                it[EventExtraCosts.totalAmount] = totalAmount
                if (request.notes != null) it[notes] = request.notes.trim().ifBlank { null }
                it[updatedAt] = kotlinx.datetime.Clock.System.now()
            }
            Result.success(toFinanceResponse(eventId))
        }
    }

    fun deleteEventExtraCost(eventId: UUID, tuntasId: UUID, costId: UUID): Result<EventFinanceResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val deleted = EventExtraCosts.deleteWhere {
                (EventExtraCosts.id eq costId) and (EventExtraCosts.eventId eq eventId)
            }
            if (deleted == 0) return@transaction Result.failure(Exception("Extra cost not found"))
            Result.success(toFinanceResponse(eventId))
        }
    }

    fun completePurchase(eventId: UUID, purchaseId: UUID, tuntasId: UUID): Result<EventPurchaseResponse> {
        return transaction {
            val event = ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
            ensureEventIsNotReadOnly(event)?.let { return@transaction Result.failure(it) }
            val purchase = EventPurchases.selectAll()
                .where { (EventPurchases.id eq purchaseId) and (EventPurchases.eventId eq eventId) }
                .forUpdate()
                .firstOrNull() ?: return@transaction Result.failure(Exception("Purchase not found"))
            if (purchase[EventPurchases.status] == "CANCELLED") {
                return@transaction Result.failure(Exception("Cancelled purchase cannot be completed"))
            }
            if (purchase[EventPurchases.status] == "PURCHASED") {
                return@transaction Result.success(toPurchaseResponse(purchase))
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
        return Result.failure(Exception("Purchase inventory addition moved to event reconciliation"))
    }

    private fun validateEventLocation(locationId: UUID?, tuntasId: UUID): Exception? {
        if (locationId == null) return null
        val existsInTuntas = Locations.selectAll()
            .where { (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }
            .firstOrNull() != null
        return if (existsInTuntas) null else Exception("Location not found in this tuntas")
    }

    private fun validateEventOrgUnit(orgUnitId: UUID?, tuntasId: UUID): Exception? {
        if (orgUnitId == null) return null
        val existsInTuntas = OrganizationalUnits.selectAll()
            .where { (OrganizationalUnits.id eq orgUnitId) and (OrganizationalUnits.tuntasId eq tuntasId) }
            .firstOrNull() != null
        return if (existsInTuntas) null else Exception("Organizational unit not found in this tuntas")
    }

    private data class EventAccess(
        val isActiveMember: Boolean,
        val isGlobalEventAdmin: Boolean,
        val rankNames: Set<String>,
        val assignedUnitIds: Set<UUID>,
        val ledUnitIds: Set<UUID>,
        val eventRoleEventIds: Set<UUID>,
        val responsiblePastovykleEventIds: Set<UUID>
    )

    private fun resolveEventAccess(userId: UUID, tuntasId: UUID): EventAccess {
        val rankNames = userRankNames(userId, tuntasId)
        val ledUnitIds = activeLeadershipRows(userId, tuntasId)
            .mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }
            .toSet()
        val assignedUnitIds = UnitAssignments.selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.leftAt.isNull())
            }
            .map { it[UnitAssignments.organizationalUnitId] }
            .toSet()
        val eventRoleEventIds = EventRoles
            .innerJoin(Events, { eventId }, { id })
            .select(EventRoles.eventId)
            .where {
                (EventRoles.userId eq userId) and
                    (Events.tuntasId eq tuntasId)
            }
            .map { it[EventRoles.eventId] }
            .toSet()
        val responsiblePastovykleEventIds = Pastovykles
            .innerJoin(Events, { eventId }, { id })
            .select(Pastovykles.eventId)
            .where {
                (Pastovykles.responsibleUserId eq userId) and
                    (Events.tuntasId eq tuntasId)
            }
            .map { it[Pastovykles.eventId] }
            .toSet()

        return EventAccess(
            isActiveMember = isActiveTuntasMember(userId, tuntasId),
            isGlobalEventAdmin = hasAnyLeadershipRole(userId, tuntasId, globalEventRoleNames),
            rankNames = rankNames,
            assignedUnitIds = assignedUnitIds,
            ledUnitIds = ledUnitIds,
            eventRoleEventIds = eventRoleEventIds,
            responsiblePastovykleEventIds = responsiblePastovykleEventIds
        )
    }

    private fun canViewEventRow(event: ResultRow, access: EventAccess): Boolean {
        if (!access.isActiveMember) return false
        if (event[Events.id] in access.eventRoleEventIds) return true
        if (event[Events.id] in access.responsiblePastovykleEventIds) return true

        val targetOrgUnitId = event[Events.organizationalUnitId]
        if (targetOrgUnitId == null) {
            return access.isGlobalEventAdmin || vadovasRankName in access.rankNames
        }

        val targetUnit = OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq targetOrgUnitId }
            .firstOrNull() ?: return false
        val targetType = targetUnit[OrganizationalUnits.type]

        return when (targetType) {
            "GILDIJA" -> vadovasRankName in access.rankNames ||
                targetOrgUnitId in access.assignedUnitIds ||
                targetOrgUnitId in access.ledUnitIds
            in seniorScoutUnitTypes -> {
                val belongsToTargetUnit = targetOrgUnitId in access.assignedUnitIds || targetOrgUnitId in access.ledUnitIds
                belongsToTargetUnit && access.rankNames.any { it in seniorScoutRankNames }
            }
            else -> access.isGlobalEventAdmin
        }
    }

    private fun userRankNames(userId: UUID, tuntasId: UUID): Set<String> {
        return UserRanks
            .innerJoin(Roles, { roleId }, { id })
            .select(Roles.name)
            .where {
                (UserRanks.userId eq userId) and
                    (UserRanks.tuntasId eq tuntasId) and
                    (Roles.tuntasId eq tuntasId)
            }
            .map { it[Roles.name] }
            .toSet()
    }

    private fun activeLeadershipRows(userId: UUID, tuntasId: UUID): List<ResultRow> {
        return UserLeadershipRoles.selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull())
            }
            .toList()
    }

    private fun hasAnyLeadershipRole(userId: UUID, tuntasId: UUID, roleNames: Set<String>): Boolean {
        return UserLeadershipRoles
            .innerJoin(Roles, { roleId }, { id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull()) and
                    (Roles.name inList roleNames.toList())
            }
            .firstOrNull() != null
    }

    private fun userLeadsUnit(userId: UUID, tuntasId: UUID, orgUnitId: UUID): Boolean {
        return activeLeadershipRows(userId, tuntasId)
            .any { it[UserLeadershipRoles.organizationalUnitId] == orgUnitId }
    }

    private fun userBelongsToUnit(userId: UUID, tuntasId: UUID, orgUnitId: UUID): Boolean {
        return UnitAssignments.selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.organizationalUnitId eq orgUnitId) and
                    (UnitAssignments.leftAt.isNull())
            }
            .firstOrNull() != null
    }

    private data class EventListHydration(
        val rolesByEventId: Map<UUID, List<EventRoleResponse>>,
        val inventorySummaryByEventId: Map<UUID, EventInventorySummaryResponse>,
        val financeSummaryByEventId: Map<UUID, EventFinanceSummaryResponse>
    )

    private fun buildEventListHydration(rows: List<ResultRow>): EventListHydration {
        if (rows.isEmpty()) {
            return EventListHydration(emptyMap(), emptyMap(), emptyMap())
        }

        val eventRowsById = rows.associateBy { it[Events.id] }
        val eventIds = eventRowsById.keys.toList()

        val roleRows = EventRoles.selectAll()
            .where { EventRoles.eventId inList eventIds }
            .toList()
        val roleUserIds = roleRows.map { it[EventRoles.userId] }.toSet()
        val roleUserNamesById = if (roleUserIds.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll()
                .where { (Users.id inList roleUserIds.toList()) and Users.deletedAt.isNull() }
                .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }
        }
        val rolesByEventId = roleRows
            .mapNotNull { row ->
                roleUserNamesById[row[EventRoles.userId]]?.let { userName ->
                    row[EventRoles.eventId] to toEventRoleResponse(row, userName)
                }
            }
            .groupBy({ it.first }, { it.second })

        val inventoryRows = EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId inList eventIds }
            .toList()
        val inventoryItemIds = inventoryRows.map { it[EventInventoryItems.id] }
        val allocatedByItemId = if (inventoryItemIds.isEmpty()) {
            emptyMap()
        } else {
            EventInventoryAllocations.selectAll()
                .where { EventInventoryAllocations.eventInventoryItemId inList inventoryItemIds }
                .groupBy { it[EventInventoryAllocations.eventInventoryItemId] }
                .mapValues { (_, allocationRows) -> allocationRows.sumOf { it[EventInventoryAllocations.quantity] } }
        }
        val inventorySummaryByEventId = inventoryRows
            .groupBy { it[EventInventoryItems.eventId] }
            .mapValues { (_, itemRows) ->
                EventInventorySummaryResponse(
                    totalPlannedQuantity = itemRows.sumOf { it[EventInventoryItems.plannedQuantity] },
                    totalAvailableQuantity = itemRows.sumOf { it[EventInventoryItems.availableQuantity] },
                    totalShortageQuantity = itemRows.sumOf {
                        (it[EventInventoryItems.plannedQuantity] - it[EventInventoryItems.availableQuantity]).coerceAtLeast(0)
                    },
                    totalAllocatedQuantity = itemRows.sumOf { allocatedByItemId[it[EventInventoryItems.id]] ?: 0 },
                    itemsNeedingPurchase = itemRows.count { it[EventInventoryItems.needsPurchase] }
                )
            }

        val purchaseTotalsByEventId = EventPurchases.selectAll()
            .where {
                (EventPurchases.eventId inList eventIds) and
                    (EventPurchases.status neq "CANCELLED")
            }
            .groupBy { it[EventPurchases.eventId] }
            .mapValues { (_, purchaseRows) ->
                purchaseRows.fold(BigDecimal.ZERO) { sum, row -> sum + (row[EventPurchases.totalAmount] ?: BigDecimal.ZERO) }
            }
        val extraCostTotalsByEventId = EventExtraCosts.selectAll()
            .where { EventExtraCosts.eventId inList eventIds }
            .groupBy { it[EventExtraCosts.eventId] }
            .mapValues { (_, costRows) ->
                costRows.fold(BigDecimal.ZERO) { sum, row -> sum + row[EventExtraCosts.totalAmount] }
            }
        val financeSummaryByEventId = eventRowsById.mapValues { (eventId, eventRow) ->
            val purchaseTotal = purchaseTotalsByEventId[eventId] ?: BigDecimal.ZERO
            val extraCostTotal = extraCostTotalsByEventId[eventId] ?: BigDecimal.ZERO
            val spentTotal = purchaseTotal + extraCostTotal
            val budget = eventRow[Events.inventoryBudgetAmount]
            EventFinanceSummaryResponse(
                inventoryBudgetAmount = budget?.toDouble(),
                purchaseTotal = purchaseTotal.toDouble(),
                extraCostTotal = extraCostTotal.toDouble(),
                spentTotal = spentTotal.toDouble(),
                remainingAmount = budget?.subtract(spentTotal)?.toDouble(),
                overBudget = budget != null && spentTotal > budget
            )
        }

        return EventListHydration(
            rolesByEventId = rolesByEventId,
            inventorySummaryByEventId = inventorySummaryByEventId,
            financeSummaryByEventId = financeSummaryByEventId
        )
    }

    private fun toEventResponse(row: ResultRow, hydration: EventListHydration? = null): EventResponse {
        val eventId = row[Events.id]

        val roles = hydration?.rolesByEventId?.get(eventId) ?: activeEventRoleResponses(eventId)

        return EventResponse(
            id = eventId.toString(),
            tuntasId = row[Events.tuntasId].toString(),
            name = row[Events.name],
            type = row[Events.type],
            customTypeLabel = row[Events.customTypeLabel],
            startDate = row[Events.startDate].toString(),
            endDate = row[Events.endDate].toString(),
            locationId = row[Events.locationId]?.toString(),
            organizationalUnitId = row[Events.organizationalUnitId]?.toString(),
            createdByUserId = row[Events.createdByUserId]?.toString(),
            status = row[Events.status],
            inventoryBudgetAmount = row[Events.inventoryBudgetAmount]?.toDouble(),
            notes = row[Events.notes],
            createdAt = row[Events.createdAt].toString(),
            eventRoles = roles,
            inventorySummary = hydration?.inventorySummaryByEventId?.get(eventId) ?: toInventorySummary(eventId),
            financeSummary = hydration?.financeSummaryByEventId?.get(eventId) ?: toFinanceSummary(eventId)
        )
    }

    private fun toFinanceResponse(eventId: UUID): EventFinanceResponse {
        val costs = EventExtraCosts.selectAll()
            .where { EventExtraCosts.eventId eq eventId }
            .orderBy(EventExtraCosts.createdAt to SortOrder.DESC)
            .map { toExtraCostResponse(it) }
        return EventFinanceResponse(
            eventId = eventId.toString(),
            summary = toFinanceSummary(eventId),
            extraCosts = costs
        )
    }

    private fun toFinanceSummary(eventId: UUID): EventFinanceSummaryResponse {
        val event = Events.selectAll()
            .where { Events.id eq eventId }
            .first()
        val purchaseTotal = EventPurchases.selectAll()
            .where {
                (EventPurchases.eventId eq eventId) and
                    (EventPurchases.status neq "CANCELLED")
            }
            .fold(BigDecimal.ZERO) { sum, row -> sum + (row[EventPurchases.totalAmount] ?: BigDecimal.ZERO) }
        val extraCostTotal = EventExtraCosts.selectAll()
            .where { EventExtraCosts.eventId eq eventId }
            .fold(BigDecimal.ZERO) { sum, row -> sum + row[EventExtraCosts.totalAmount] }
        val spentTotal = purchaseTotal + extraCostTotal
        val budget = event[Events.inventoryBudgetAmount]
        return EventFinanceSummaryResponse(
            inventoryBudgetAmount = budget?.toDouble(),
            purchaseTotal = purchaseTotal.toDouble(),
            extraCostTotal = extraCostTotal.toDouble(),
            spentTotal = spentTotal.toDouble(),
            remainingAmount = budget?.subtract(spentTotal)?.toDouble(),
            overBudget = budget != null && spentTotal > budget
        )
    }

    private fun toExtraCostResponse(row: ResultRow): EventExtraCostResponse {
        return EventExtraCostResponse(
            id = row[EventExtraCosts.id].toString(),
            eventId = row[EventExtraCosts.eventId].toString(),
            category = row[EventExtraCosts.category],
            label = row[EventExtraCosts.label],
            quantity = row[EventExtraCosts.quantity]?.toDouble(),
            unit = row[EventExtraCosts.unit],
            unitPrice = row[EventExtraCosts.unitPrice]?.toDouble(),
            totalAmount = row[EventExtraCosts.totalAmount].toDouble(),
            notes = row[EventExtraCosts.notes],
            createdByUserId = row[EventExtraCosts.createdByUserId]?.toString(),
            createdAt = row[EventExtraCosts.createdAt].toString(),
            updatedAt = row[EventExtraCosts.updatedAt].toString()
        )
    }

    private fun validateExtraCostCategory(category: String): String? {
        val normalized = category.trim().uppercase()
        return normalized.takeIf { it in validExtraCostCategories }
    }

    private fun resolveExtraCostTotal(
        quantity: BigDecimal?,
        unitPrice: BigDecimal?,
        totalAmount: Double?
    ): BigDecimal? {
        totalAmount?.let {
            if (it < 0.0) return null
            return it.toMoney()
        }
        return if (quantity != null && unitPrice != null) {
            quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP)
        } else {
            null
        }
    }

    private fun Double.toMoney(): BigDecimal =
        BigDecimal.valueOf(this).setScale(2, RoundingMode.HALF_UP)

    private fun toEventRoleResponse(row: ResultRow): EventRoleResponse {
        val userName = Users.selectAll()
            .where { (Users.id eq row[EventRoles.userId]) and Users.deletedAt.isNull() }
            .firstOrNull()
            ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
        return toEventRoleResponse(row, userName)
    }

    private fun activeEventRoleResponses(eventId: UUID): List<EventRoleResponse> {
        val roleRows = EventRoles.selectAll()
            .where { EventRoles.eventId eq eventId }
            .toList()
        val userIds = roleRows.map { it[EventRoles.userId] }.distinct()
        if (userIds.isEmpty()) return emptyList()

        val userNamesById = Users.selectAll()
            .where { (Users.id inList userIds) and Users.deletedAt.isNull() }
            .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }

        return roleRows.mapNotNull { row ->
            userNamesById[row[EventRoles.userId]]?.let { userName ->
                toEventRoleResponse(row, userName)
            }
        }
    }

    private fun toEventRoleResponse(row: ResultRow, userName: String?): EventRoleResponse {
        return EventRoleResponse(
            id = row[EventRoles.id].toString(),
            userId = row[EventRoles.userId].toString(),
            userName = userName,
            role = row[EventRoles.role],
            targetGroup = row[EventRoles.targetGroup],
            pastovykleId = row[EventRoles.pastovykleId]?.toString(),
            assignedByUserId = row[EventRoles.assignedByUserId]?.toString(),
            assignedAt = row[EventRoles.assignedAt].toString()
        )
    }

    private fun ensureEvent(eventId: UUID, tuntasId: UUID): ResultRow? {
        return Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull()
    }

    private fun ensureEventIsNotReadOnly(event: ResultRow): Exception? {
        return if (event[Events.status] in readOnlyEventStatuses) {
            Exception("Completed or cancelled events are read-only")
        } else {
            null
        }
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

    private data class EventInventoryPlanHydration(
        val pastovykleNamesById: Map<UUID, String>,
        val locationNodesById: Map<UUID, LocationNodeData>,
        val allocatedByItemId: Map<UUID, Int>,
        val bucketNamesById: Map<UUID, String>,
        val responsibleUserNamesById: Map<UUID, String>,
        val sourcesByItemId: Map<UUID, List<EventInventorySourceResponse>>
    )

    private fun toEventInventoryPlanResponse(eventId: UUID): EventInventoryPlanResponse {
        val bucketRows = EventInventoryBuckets.selectAll()
            .where { EventInventoryBuckets.eventId eq eventId }
            .toList()
        val itemRows = EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .toList()
        val allocationRows = EventInventoryAllocations
            .innerJoin(EventInventoryItems, { eventInventoryItemId }, { id })
            .selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .toList()
        val hydration = buildEventInventoryPlanHydration(bucketRows, itemRows)
        val buckets = bucketRows.map { toBucketResponse(it, hydration) }
        val items = itemRows.map { toInventoryItemResponse(it, hydration) }
        val allocations = allocationRows.map { toAllocationResponse(it, hydration.bucketNamesById) }
        return EventInventoryPlanResponse(buckets = buckets, items = items, allocations = allocations)
    }

    private fun buildEventInventoryPlanHydration(
        bucketRows: List<ResultRow>,
        itemRows: List<ResultRow>
    ): EventInventoryPlanHydration {
        val pastovykleIds = bucketRows.mapNotNull { it[EventInventoryBuckets.pastovykleId] }.distinct()
        val pastovykleNamesById = if (pastovykleIds.isEmpty()) {
            emptyMap()
        } else {
            Pastovykles.selectAll()
                .where { Pastovykles.id inList pastovykleIds }
                .associate { it[Pastovykles.id] to it[Pastovykles.name] }
        }
        val locationNodesById = if (bucketRows.any { it[EventInventoryBuckets.locationId] != null }) {
            Locations.selectAll()
                .toList()
                .associate { it[Locations.id] to it.toLocationNodeData() }
        } else {
            emptyMap()
        }
        val itemIds = itemRows.map { it[EventInventoryItems.id] }
        val allocationRows = if (itemIds.isEmpty()) {
            emptyList()
        } else {
            EventInventoryAllocations.selectAll()
                .where { EventInventoryAllocations.eventInventoryItemId inList itemIds }
                .toList()
        }
        val allocatedByItemId = allocationRows
            .groupBy { it[EventInventoryAllocations.eventInventoryItemId] }
            .mapValues { (_, rows) -> rows.sumOf { it[EventInventoryAllocations.quantity] } }
        val bucketNamesById = bucketRows.associate { it[EventInventoryBuckets.id] to it[EventInventoryBuckets.name] }
        val responsibleUserIds = itemRows.mapNotNull { it[EventInventoryItems.responsibleUserId] }.distinct()
        val responsibleUserNamesById = if (responsibleUserIds.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll()
                .where { Users.id inList responsibleUserIds }
                .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }
        }
        val sourceRows = if (itemIds.isEmpty()) {
            emptyList()
        } else {
            EventInventorySources.selectAll()
                .where { EventInventorySources.eventInventoryItemId inList itemIds }
                .orderBy(EventInventorySources.createdAt to SortOrder.ASC)
                .toList()
        }
        val sourcesByItemId = sourceRows
            .map { it[EventInventorySources.eventInventoryItemId] to toInventorySourceResponse(it) }
            .groupBy({ it.first }, { it.second })

        return EventInventoryPlanHydration(
            pastovykleNamesById = pastovykleNamesById,
            locationNodesById = locationNodesById,
            allocatedByItemId = allocatedByItemId,
            bucketNamesById = bucketNamesById,
            responsibleUserNamesById = responsibleUserNamesById,
            sourcesByItemId = sourcesByItemId
        )
    }

    private fun toBucketResponse(
        row: ResultRow,
        hydration: EventInventoryPlanHydration? = null
    ): EventInventoryBucketResponse {
        val pastovykleId = row[EventInventoryBuckets.pastovykleId]
        val pastovykleName = pastovykleId?.let {
            hydration?.pastovykleNamesById?.get(it)
                ?: Pastovykles.selectAll().where { Pastovykles.id eq it }.firstOrNull()?.get(Pastovykles.name)
        }
        val locationId = row[EventInventoryBuckets.locationId]
        val locationPath = locationId?.let { id ->
            val nodesById = hydration?.locationNodesById
                ?: Locations.selectAll().toList().associate { it[Locations.id] to it.toLocationNodeData() }
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

    private fun toInventoryItemResponse(
        row: ResultRow,
        hydration: EventInventoryPlanHydration? = null
    ): EventInventoryItemResponse {
        val itemId = row[EventInventoryItems.id]
        val allocated = hydration?.allocatedByItemId?.get(itemId)
            ?: EventInventoryAllocations.selectAll()
                .where { EventInventoryAllocations.eventInventoryItemId eq itemId }
                .sumOf { it[EventInventoryAllocations.quantity] }
        val planned = row[EventInventoryItems.plannedQuantity]
        val sources = inventorySourcesForItem(row, hydration)
        val available = sources.takeIf { it.isNotEmpty() }
            ?.sumOf { it.reservedQuantity }
            ?: row[EventInventoryItems.availableQuantity]
        val bucketName = row[EventInventoryItems.bucketId]?.let { bucketId ->
            hydration?.bucketNamesById?.get(bucketId)
                ?: EventInventoryBuckets.select(EventInventoryBuckets.name)
                    .where { EventInventoryBuckets.id eq bucketId }
                    .firstOrNull()
                    ?.get(EventInventoryBuckets.name)
        }
        val responsibleUserName = row[EventInventoryItems.responsibleUserId]?.let { userId ->
            hydration?.responsibleUserNamesById?.get(userId)
                ?: Users.selectAll()
                    .where { Users.id eq userId }
                    .firstOrNull()
                    ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
        }
        return EventInventoryItemResponse(
            id = itemId.toString(),
            eventId = row[EventInventoryItems.eventId].toString(),
            itemId = row[EventInventoryItems.itemId]?.toString(),
            bucketId = row[EventInventoryItems.bucketId]?.toString(),
            bucketName = bucketName,
            reservationGroupId = row[EventInventoryItems.reservationGroupId]?.toString(),
            name = row[EventInventoryItems.name],
            plannedQuantity = planned,
            availableQuantity = available,
            shortageQuantity = (planned - available).coerceAtLeast(0),
            allocatedQuantity = allocated,
            unallocatedQuantity = (planned - allocated).coerceAtLeast(0),
            needsPurchase = row[EventInventoryItems.needsPurchase],
            notes = row[EventInventoryItems.notes],
            sourceCustodianName = row[EventInventoryItems.sourceCustodianName],
            sourceLocationPath = row[EventInventoryItems.sourceLocationPath],
            sourceTemporaryStorageLabel = row[EventInventoryItems.sourceTemporaryStorageLabel],
            sourceResponsibleUserName = row[EventInventoryItems.sourceResponsibleUserName],
            sourcePickupSummary = buildSourcePickupSummary(
                row[EventInventoryItems.sourceCustodianName],
                row[EventInventoryItems.sourceLocationPath],
                row[EventInventoryItems.sourceTemporaryStorageLabel],
                row[EventInventoryItems.sourceResponsibleUserName]
            ),
            sources = sources,
            responsibleUserId = row[EventInventoryItems.responsibleUserId]?.toString(),
            responsibleUserName = responsibleUserName,
            createdByUserId = row[EventInventoryItems.createdByUserId]?.toString(),
            createdAt = row[EventInventoryItems.createdAt].toString()
        )
    }

    private fun inventorySourcesForItem(
        row: ResultRow,
        hydration: EventInventoryPlanHydration? = null
    ): List<EventInventorySourceResponse> {
        val sourceRows = hydration?.sourcesByItemId?.get(row[EventInventoryItems.id])
            ?: EventInventorySources.selectAll()
                .where { EventInventorySources.eventInventoryItemId eq row[EventInventoryItems.id] }
                .orderBy(EventInventorySources.createdAt to SortOrder.ASC)
                .map { toInventorySourceResponse(it) }
        if (sourceRows.isNotEmpty()) return sourceRows

        val legacyReserved = row[EventInventoryItems.availableQuantity]
        val hasLegacySource = row[EventInventoryItems.itemId] != null ||
            row[EventInventoryItems.sourceCustodianName] != null ||
            row[EventInventoryItems.sourceLocationPath] != null ||
            row[EventInventoryItems.sourceTemporaryStorageLabel] != null ||
            row[EventInventoryItems.sourceResponsibleUserName] != null
        if (!hasLegacySource) return emptyList()
        return listOf(
            EventInventorySourceResponse(
                id = "legacy-${row[EventInventoryItems.id]}",
                eventInventoryItemId = row[EventInventoryItems.id].toString(),
                itemId = row[EventInventoryItems.itemId]?.toString(),
                reservationGroupId = row[EventInventoryItems.reservationGroupId]?.toString(),
                plannedQuantity = row[EventInventoryItems.plannedQuantity],
                reservedQuantity = legacyReserved,
                pickupCustodianName = row[EventInventoryItems.sourceCustodianName],
                pickupLocationPath = row[EventInventoryItems.sourceLocationPath],
                pickupTemporaryStorageLabel = row[EventInventoryItems.sourceTemporaryStorageLabel],
                pickupResponsibleUserName = row[EventInventoryItems.sourceResponsibleUserName],
                pickupSummary = buildSourcePickupSummary(
                    row[EventInventoryItems.sourceCustodianName],
                    row[EventInventoryItems.sourceLocationPath],
                    row[EventInventoryItems.sourceTemporaryStorageLabel],
                    row[EventInventoryItems.sourceResponsibleUserName]
                ),
                sourceStatus = if (legacyReserved > 0) "RESERVED" else "SHORTAGE",
                notes = row[EventInventoryItems.notes],
                createdAt = row[EventInventoryItems.createdAt].toString()
            )
        )
    }

    private fun toInventorySourceResponse(row: ResultRow): EventInventorySourceResponse {
        return EventInventorySourceResponse(
            id = row[EventInventorySources.id].toString(),
            eventInventoryItemId = row[EventInventorySources.eventInventoryItemId].toString(),
            itemId = row[EventInventorySources.itemId]?.toString(),
            reservationGroupId = row[EventInventorySources.reservationGroupId]?.toString(),
            plannedQuantity = row[EventInventorySources.plannedQuantity],
            reservedQuantity = row[EventInventorySources.reservedQuantity],
            pickupCustodianName = row[EventInventorySources.pickupCustodianName],
            pickupLocationPath = row[EventInventorySources.pickupLocationPath],
            pickupTemporaryStorageLabel = row[EventInventorySources.pickupTemporaryStorageLabel],
            pickupResponsibleUserName = row[EventInventorySources.pickupResponsibleUserName],
            pickupSummary = row[EventInventorySources.pickupSummary],
            sourceStatus = row[EventInventorySources.sourceStatus],
            notes = row[EventInventorySources.notes],
            createdAt = row[EventInventorySources.createdAt].toString()
        )
    }

    private fun insertInventorySource(
        eventInventoryItemId: UUID,
        itemId: UUID?,
        reservationGroupId: UUID?,
        plannedQuantity: Int,
        reservedQuantity: Int,
        snapshot: EventItemSourceSnapshot?,
        notes: String?
    ): UUID {
        val pickupSummary = buildSourcePickupSummary(
            snapshot?.custodianName,
            snapshot?.locationPath,
            snapshot?.temporaryStorageLabel,
            snapshot?.responsibleUserName
        )
        return EventInventorySources.insert {
            it[this.eventInventoryItemId] = eventInventoryItemId
            it[this.itemId] = itemId
            it[this.reservationGroupId] = reservationGroupId
            it[this.plannedQuantity] = plannedQuantity
            it[this.reservedQuantity] = reservedQuantity
            it[pickupCustodianName] = snapshot?.custodianName
            it[pickupLocationPath] = snapshot?.locationPath
            it[pickupTemporaryStorageLabel] = snapshot?.temporaryStorageLabel
            it[pickupResponsibleUserName] = snapshot?.responsibleUserName
            it[this.pickupSummary] = pickupSummary
            it[sourceStatus] = when {
                reservedQuantity >= plannedQuantity -> "RESERVED"
                reservedQuantity > 0 -> "PARTIAL"
                else -> "SHORTAGE"
            }
            it[this.notes] = notes
            it[createdAt] = kotlinx.datetime.Clock.System.now()
        } get EventInventorySources.id
    }

    private fun refreshInventoryItemCoverage(row: ResultRow) {
        val inventoryItemId = row[EventInventoryItems.id]
        val planned = row[EventInventoryItems.plannedQuantity]
        val reserved = EventInventorySources.selectAll()
            .where { (EventInventorySources.eventInventoryItemId eq inventoryItemId) and (EventInventorySources.sourceStatus neq "CANCELLED") }
            .sumOf { it[EventInventorySources.reservedQuantity] }
        EventInventoryItems.update({ EventInventoryItems.id eq inventoryItemId }) {
            it[availableQuantity] = reserved
            it[needsPurchase] = planned > reserved
        }
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

    private fun toAllocationResponse(
        row: ResultRow,
        bucketNamesById: Map<UUID, String>? = null
    ): EventInventoryAllocationResponse {
        val bucketId = row[EventInventoryAllocations.bucketId]
        val bucketName = bucketNamesById?.get(bucketId)
            ?: EventInventoryBuckets.select(EventInventoryBuckets.name)
                .where { EventInventoryBuckets.id eq bucketId }
                .first()[EventInventoryBuckets.name]
        return EventInventoryAllocationResponse(
            id = row[EventInventoryAllocations.id].toString(),
            eventInventoryItemId = row[EventInventoryAllocations.eventInventoryItemId].toString(),
            bucketId = bucketId.toString(),
            bucketName = bucketName,
            quantity = row[EventInventoryAllocations.quantity],
            notes = row[EventInventoryAllocations.notes]
        )
    }

    private data class EventPurchaseHydration(
        val purchasedByNamesByUserId: Map<UUID, String>,
        val purchaseItemsByPurchaseId: Map<UUID, List<ResultRow>>,
        val inventoryItemNamesById: Map<UUID, String>,
        val invoicesByPurchaseId: Map<UUID, List<EventPurchaseInvoiceResponse>>
    )

    private fun buildPurchaseHydration(rows: List<ResultRow>): EventPurchaseHydration {
        if (rows.isEmpty()) {
            return EventPurchaseHydration(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
        val purchaseIds = rows.map { it[EventPurchases.id] }
        val userIds = rows.mapNotNull { it[EventPurchases.purchasedByUserId] }.distinct()
        val purchasedByNamesByUserId = if (userIds.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll()
                .where { Users.id inList userIds }
                .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }
        }
        val purchaseItemRows = EventPurchaseItems.selectAll()
            .where { EventPurchaseItems.purchaseId inList purchaseIds }
            .toList()
        val inventoryItemIds = purchaseItemRows.map { it[EventPurchaseItems.eventInventoryItemId] }.distinct()
        val inventoryItemNamesById = if (inventoryItemIds.isEmpty()) {
            emptyMap()
        } else {
            EventInventoryItems.select(EventInventoryItems.id, EventInventoryItems.name)
                .where { EventInventoryItems.id inList inventoryItemIds }
                .associate { it[EventInventoryItems.id] to it[EventInventoryItems.name] }
        }
        val invoiceRows = EventPurchaseInvoices.selectAll()
            .where { EventPurchaseInvoices.purchaseId inList purchaseIds }
            .orderBy(EventPurchaseInvoices.createdAt to SortOrder.ASC)
            .toList()
        val invoicesByPurchaseId = invoiceRows
            .map { it[EventPurchaseInvoices.purchaseId] to toPurchaseInvoiceResponse(it) }
            .groupBy({ it.first }, { it.second })

        return EventPurchaseHydration(
            purchasedByNamesByUserId = purchasedByNamesByUserId,
            purchaseItemsByPurchaseId = purchaseItemRows.groupBy { it[EventPurchaseItems.purchaseId] },
            inventoryItemNamesById = inventoryItemNamesById,
            invoicesByPurchaseId = invoicesByPurchaseId
        )
    }

    private fun toPurchaseResponse(
        row: ResultRow,
        hydration: EventPurchaseHydration? = null
    ): EventPurchaseResponse {
        val user = row[EventPurchases.purchasedByUserId]?.let { userId ->
            hydration?.purchasedByNamesByUserId?.get(userId)
                ?: Users.selectAll()
                    .where { Users.id eq userId }
                    .firstOrNull()
                    ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
        }
        val purchaseId = row[EventPurchases.id]
        val items = hydration?.purchaseItemsByPurchaseId?.get(purchaseId)
            ?.map { toPurchaseItemResponse(it, hydration) }
            ?: EventPurchaseItems.selectAll()
                .where { EventPurchaseItems.purchaseId eq purchaseId }
                .map { toPurchaseItemResponse(it) }
        val invoices = hydration?.invoicesByPurchaseId?.get(purchaseId)
            ?: EventPurchaseInvoices.selectAll()
                .where { EventPurchaseInvoices.purchaseId eq purchaseId }
                .orderBy(EventPurchaseInvoices.createdAt to SortOrder.ASC)
                .map { toPurchaseInvoiceResponse(it) }
        return EventPurchaseResponse(
            id = purchaseId.toString(),
            eventId = row[EventPurchases.eventId].toString(),
            purchasedByUserId = row[EventPurchases.purchasedByUserId]?.toString(),
            purchasedByName = user,
            status = row[EventPurchases.status],
            purchaseDate = row[EventPurchases.purchaseDate]?.toString(),
            totalAmount = row[EventPurchases.totalAmount]?.toDouble(),
            invoiceFileUrl = invoices.firstOrNull()?.fileUrl ?: row[EventPurchases.invoiceFileUrl],
            invoices = invoices,
            notes = row[EventPurchases.notes],
            createdAt = row[EventPurchases.createdAt].toString(),
            updatedAt = row[EventPurchases.updatedAt].toString(),
            items = items
        )
    }

    private fun toPurchaseInvoiceResponse(row: ResultRow): EventPurchaseInvoiceResponse =
        EventPurchaseInvoiceResponse(
            id = row[EventPurchaseInvoices.id].toString(),
            purchaseId = row[EventPurchaseInvoices.purchaseId].toString(),
            fileUrl = row[EventPurchaseInvoices.fileUrl],
            createdAt = row[EventPurchaseInvoices.createdAt].toString()
        )

    private fun toPurchaseItemResponse(
        row: ResultRow,
        hydration: EventPurchaseHydration? = null
    ): EventPurchaseItemResponse {
        val eventInventoryItemId = row[EventPurchaseItems.eventInventoryItemId]
        val itemName = hydration?.inventoryItemNamesById?.get(eventInventoryItemId)
            ?: EventInventoryItems.select(EventInventoryItems.name)
                .where { EventInventoryItems.id eq eventInventoryItemId }
                .first()[EventInventoryItems.name]
        val unitPrice = row[EventPurchaseItems.unitPrice]
        return EventPurchaseItemResponse(
            id = row[EventPurchaseItems.id].toString(),
            purchaseId = row[EventPurchaseItems.purchaseId].toString(),
            eventInventoryItemId = eventInventoryItemId.toString(),
            itemName = itemName,
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

    private data class CustodyHydration(
        val pastovykleNamesById: Map<UUID, String>,
        val userNamesById: Map<UUID, String>
    )

    private fun buildCustodyHydration(rows: List<ResultRow>): CustodyHydration {
        val pastovykleIds = rows.mapNotNull { it[EventInventoryCustody.pastovykleId] }.distinct()
        val userIds = buildSet {
            rows.mapNotNullTo(this) { it[EventInventoryCustody.holderUserId] }
            rows.mapTo(this) { it[EventInventoryCustody.createdByUserId] }
        }.toList()
        return CustodyHydration(
            pastovykleNamesById = loadPastovykleNames(pastovykleIds),
            userNamesById = loadUserNames(userIds)
        )
    }

    private fun toCustodyResponse(
        row: ResultRow,
        hydration: CustodyHydration? = null
    ): EventInventoryCustodyResponse {
        val pastovykleId = row[EventInventoryCustody.pastovykleId]
        val holderUserId = row[EventInventoryCustody.holderUserId]
        val createdByUserId = row[EventInventoryCustody.createdByUserId]
        return EventInventoryCustodyResponse(
            id = row[EventInventoryCustody.id].toString(),
            eventInventoryItemId = row[EventInventoryCustody.eventInventoryItemId].toString(),
            itemName = row[EventInventoryItems.name],
            pastovykleId = pastovykleId?.toString(),
            pastovykleName = pastovykleId?.let {
                hydration?.pastovykleNamesById?.get(it) ?: loadPastovykleNames(listOf(it))[it]
            },
            holderUserId = holderUserId?.toString(),
            holderUserName = holderUserId?.let {
                hydration?.userNamesById?.get(it) ?: loadUserNames(listOf(it))[it]
            },
            quantity = row[EventInventoryCustody.quantity],
            returnedQuantity = row[EventInventoryCustody.returnedQuantity],
            remainingQuantity = (row[EventInventoryCustody.quantity] - row[EventInventoryCustody.returnedQuantity]).coerceAtLeast(0),
            status = row[EventInventoryCustody.status],
            createdByUserId = createdByUserId.toString(),
            createdByUserName = hydration?.userNamesById?.get(createdByUserId)
                ?: loadUserNames(listOf(createdByUserId))[createdByUserId],
            createdAt = row[EventInventoryCustody.createdAt].toString(),
            closedAt = row[EventInventoryCustody.closedAt]?.toString(),
            notes = row[EventInventoryCustody.notes]
        )
    }

    private data class MovementHydration(
        val itemNamesById: Map<UUID, String>,
        val pastovykleNamesById: Map<UUID, String>,
        val userNamesById: Map<UUID, String>
    )

    private fun buildMovementHydration(rows: List<ResultRow>): MovementHydration {
        val itemIds = rows.map { it[EventInventoryMovements.eventInventoryItemId] }.distinct()
        val pastovykleIds = rows.flatMap {
            listOfNotNull(
                it[EventInventoryMovements.fromPastovykleId],
                it[EventInventoryMovements.toPastovykleId]
            )
        }.distinct()
        val userIds = rows.flatMap {
            listOfNotNull(
                it[EventInventoryMovements.fromUserId],
                it[EventInventoryMovements.toUserId],
                it[EventInventoryMovements.performedByUserId]
            )
        }.distinct()
        val itemNamesById = if (itemIds.isEmpty()) {
            emptyMap()
        } else {
            EventInventoryItems.select(EventInventoryItems.id, EventInventoryItems.name)
                .where { EventInventoryItems.id inList itemIds }
                .associate { it[EventInventoryItems.id] to it[EventInventoryItems.name] }
        }
        return MovementHydration(
            itemNamesById = itemNamesById,
            pastovykleNamesById = loadPastovykleNames(pastovykleIds),
            userNamesById = loadUserNames(userIds)
        )
    }

    private fun toMovementResponse(
        row: ResultRow,
        hydration: MovementHydration? = null
    ): EventInventoryMovementResponse {
        val eventInventoryItemId = row[EventInventoryMovements.eventInventoryItemId]
        fun userName(id: UUID?): String? = id?.let {
            hydration?.userNamesById?.get(it) ?: loadUserNames(listOf(it))[it]
        }
        fun pastovykleName(id: UUID?): String? = id?.let {
            hydration?.pastovykleNamesById?.get(it) ?: loadPastovykleNames(listOf(it))[it]
        }
        return EventInventoryMovementResponse(
            id = row[EventInventoryMovements.id].toString(),
            eventId = row[EventInventoryMovements.eventId].toString(),
            eventInventoryItemId = eventInventoryItemId.toString(),
            itemName = hydration?.itemNamesById?.get(eventInventoryItemId)
                ?: EventInventoryItems.select(EventInventoryItems.name)
                    .where { EventInventoryItems.id eq eventInventoryItemId }
                    .first()[EventInventoryItems.name],
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

    private fun loadUserNames(ids: List<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll()
                .where { Users.id inList ids.distinct() }
                .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }
        }

    private fun loadPastovykleNames(ids: List<UUID>): Map<UUID, String> =
        if (ids.isEmpty()) {
            emptyMap()
        } else {
            Pastovykles.selectAll()
                .where { Pastovykles.id inList ids.distinct() }
                .associate { it[Pastovykles.id] to it[Pastovykles.name] }
        }

    private fun toInventoryTransferRequestResponses(
        rows: List<ResultRow>
    ): List<EventInventoryTransferRequestResponse> {
        if (rows.isEmpty()) return emptyList()
        val userNames = loadUserNames(
            rows.flatMap {
                listOf(
                    it[EventInventoryTransferRequests.requestedByUserId],
                    it[EventInventoryTransferRequests.requestedFromUserId]
                )
            }
        )
        val itemIds = rows.map { it[EventInventoryTransferRequests.eventInventoryItemId] }.distinct()
        val itemNames = EventInventoryItems
            .select(EventInventoryItems.id, EventInventoryItems.name)
            .where { EventInventoryItems.id inList itemIds }
            .associate { it[EventInventoryItems.id] to it[EventInventoryItems.name] }
        return rows.map { row ->
            val requestedByUserId = row[EventInventoryTransferRequests.requestedByUserId]
            val requestedFromUserId = row[EventInventoryTransferRequests.requestedFromUserId]
            val eventInventoryItemId = row[EventInventoryTransferRequests.eventInventoryItemId]
            EventInventoryTransferRequestResponse(
                id = row[EventInventoryTransferRequests.id].toString(),
                eventId = row[EventInventoryTransferRequests.eventId].toString(),
                sourceCustodyId = row[EventInventoryTransferRequests.sourceCustodyId].toString(),
                eventInventoryItemId = eventInventoryItemId.toString(),
                itemName = itemNames[eventInventoryItemId] ?: "Unknown",
                requestedByUserId = requestedByUserId.toString(),
                requestedByUserName = userNames[requestedByUserId],
                requestedFromUserId = requestedFromUserId.toString(),
                requestedFromUserName = userNames[requestedFromUserId],
                quantity = row[EventInventoryTransferRequests.quantity],
                status = row[EventInventoryTransferRequests.status],
                notes = row[EventInventoryTransferRequests.notes],
                createdAt = row[EventInventoryTransferRequests.createdAt].toString(),
                respondedAt = row[EventInventoryTransferRequests.respondedAt]?.toString(),
                respondedByUserId = row[EventInventoryTransferRequests.respondedByUserId]?.toString(),
                movementId = row[EventInventoryTransferRequests.movementId]?.toString()
            )
        }
    }

    private fun toReconciliationResponse(event: ResultRow): EventReconciliationResponse {
        val eventId = event[Events.id]
        val sessionId = latestEventReturnSessionId(eventId)
        val custodyRows = EventInventoryCustody
            .innerJoin(EventInventoryItems, { EventInventoryCustody.eventInventoryItemId }, { id })
            .selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .toList()
        val custodyHydration = buildCustodyHydration(custodyRows)
        val reconciliationChecksByCustodyId = reconciliationChecksByCustodyId(eventId)

        val openReturns = custodyRows
            .filter { it[EventInventoryCustody.status] == "OPEN" && openQuantity(it) > 0 }
            .map { row ->
                toReconciliationReturnLineResponse(
                    row,
                    reconciliationChecksByCustodyId[row[EventInventoryCustody.id]].orEmpty(),
                    custodyHydration
                )
            }
        val returnedToEventStorage = custodyRows
            .filter {
                it[EventInventoryCustody.status] != "OPEN" ||
                    (it[EventInventoryCustody.pastovykleId] == null && it[EventInventoryCustody.holderUserId] == null)
            }
            .map { row ->
                toReconciliationReturnLineResponse(
                    row,
                    reconciliationChecksByCustodyId[row[EventInventoryCustody.id]].orEmpty(),
                    custodyHydration
                )
            }
        val purchaseRows = reconciliationPurchaseRows(eventId)
        val purchaseItemIds = purchaseRows.map { it[EventPurchaseItems.id] }
        val reconciledQuantityByPurchaseItemId = if (purchaseItemIds.isEmpty()) {
            emptyMap()
        } else {
            EventPurchaseItemReconciliations.selectAll()
                .where { EventPurchaseItemReconciliations.purchaseItemId inList purchaseItemIds }
                .groupBy { it[EventPurchaseItemReconciliations.purchaseItemId] }
                .mapValues { (_, rows) -> rows.sumOf { it[EventPurchaseItemReconciliations.quantity] } }
        }
        val unresolvedPurchases = purchaseRows.map {
            toReconciliationPurchaseLineResponse(
                it,
                reconciledQuantityByPurchaseItemId[it[EventPurchaseItems.id]] ?: 0
            )
        }

        return EventReconciliationResponse(
            eventId = eventId.toString(),
            sessionId = sessionId?.toString(),
            status = event[Events.status],
            openReturns = openReturns,
            returnedToEventStorage = returnedToEventStorage,
            unresolvedPurchases = unresolvedPurchases,
            canComplete = openReturns.isEmpty() && unresolvedPurchases.isEmpty()
        )
    }

    private fun toReconciliationReturnLineResponse(
        row: ResultRow,
        reconciliationChecks: List<ResultRow>,
        hydration: CustodyHydration? = null
    ): EventReconciliationReturnLineResponse {
        val pastovykleId = row[EventInventoryCustody.pastovykleId]
        val holderUserId = row[EventInventoryCustody.holderUserId]
        val pastovykleName = pastovykleId?.let {
            hydration?.pastovykleNamesById?.get(it) ?: loadPastovykleNames(listOf(it))[it]
        }
        val holderUserName = holderUserId?.let {
            hydration?.userNamesById?.get(it) ?: loadUserNames(listOf(it))[it]
        }
        val currentHolderSummary = listOfNotNull(
            pastovykleName,
            holderUserName
        ).joinToString(" / ").ifBlank { "Renginio sandėlis" }
        val sourcePickupSummary = buildSourcePickupSummary(
            row[EventInventoryItems.sourceCustodianName],
            row[EventInventoryItems.sourceLocationPath],
            row[EventInventoryItems.sourceTemporaryStorageLabel],
            row[EventInventoryItems.sourceResponsibleUserName]
        )
        val reconciliationCheck = reconciliationChecks.firstOrNull()
        val auditLog = reconciliationChecks.map { toReconciliationAuditResponse(it) }
        val reconciledQuantity = auditLog.sumOf { it.quantity }
        val returnDecision = reconciliationCheck?.get(ItemChecks.result)
        val returnCondition = reconciliationCheck?.get(ItemChecks.conditionAtCheck)
        val returnedToSummary = when (returnDecision) {
            "RETURNED", "DAMAGED" -> sourcePickupSummary ?: "Renginio sandėlis"
            "MISSING", "CONSUMED" -> "Negrįžo"
            else -> null
        }
        return EventReconciliationReturnLineResponse(
            custodyId = row[EventInventoryCustody.id].toString(),
            eventInventoryItemId = row[EventInventoryCustody.eventInventoryItemId].toString(),
            itemId = row[EventInventoryItems.itemId]?.toString(),
            itemName = row[EventInventoryItems.name],
            pastovykleId = pastovykleId?.toString(),
            pastovykleName = pastovykleName,
            holderUserId = holderUserId?.toString(),
            holderUserName = holderUserName,
            quantity = row[EventInventoryCustody.quantity],
            returnedQuantity = row[EventInventoryCustody.returnedQuantity],
            remainingQuantity = openQuantity(row),
            reconciledQuantity = reconciledQuantity,
            pendingQuantity = (row[EventInventoryCustody.quantity] - reconciledQuantity).coerceAtLeast(0),
            status = row[EventInventoryCustody.status],
            isReturned = row[EventInventoryCustody.status] != "OPEN" || openQuantity(row) == 0,
            currentHolderSummary = currentHolderSummary,
            sourcePickupSummary = sourcePickupSummary,
            returnDecision = returnDecision,
            returnedToSummary = returnedToSummary,
            returnCondition = returnCondition,
            auditLog = auditLog,
            notes = reconciliationCheck?.get(ItemChecks.notes) ?: row[EventInventoryCustody.notes]
        )
    }

    private fun toReconciliationAuditResponse(row: ResultRow): EventReconciliationAuditResponse {
        return EventReconciliationAuditResponse(
            id = row[ItemChecks.id].toString(),
            quantity = row[ItemChecks.quantity],
            expectedQuantity = row[ItemChecks.expectedQuantity],
            actualQuantity = row[ItemChecks.actualQuantity],
            result = row[ItemChecks.result],
            actualLocationId = row[ItemChecks.actualLocationId]?.toString(),
            actualLocationNote = row[ItemChecks.actualLocationNote],
            conditionAtCheck = row[ItemChecks.conditionAtCheck],
            checkedByUserId = row[ItemChecks.checkedByUserId].toString(),
            checkedAt = row[ItemChecks.checkedAt].toString(),
            notes = row[ItemChecks.notes]
        )
    }

    private fun toReconciliationPurchaseLineResponse(
        row: ResultRow,
        reconciledQuantity: Int = reconciledPurchaseQuantity(row[EventPurchaseItems.id])
    ): EventReconciliationPurchaseLineResponse {
        val remainingQuantity = (row[EventPurchaseItems.purchasedQuantity] - reconciledQuantity).coerceAtLeast(0)
        return EventReconciliationPurchaseLineResponse(
            purchaseId = row[EventPurchaseItems.purchaseId].toString(),
            purchaseItemId = row[EventPurchaseItems.id].toString(),
            eventInventoryItemId = row[EventPurchaseItems.eventInventoryItemId].toString(),
            itemId = row[EventInventoryItems.itemId]?.toString(),
            itemName = row[EventInventoryItems.name],
            purchasedQuantity = remainingQuantity,
            status = row[EventPurchases.status],
            invoiceFileUrl = row[EventPurchases.invoiceFileUrl],
            notes = row[EventPurchaseItems.notes]
        )
    }

    private data class EventItemSourceSnapshot(
        val custodianName: String?,
        val locationPath: String?,
        val temporaryStorageLabel: String?,
        val responsibleUserName: String?
    )

    private fun buildEventItemSourceSnapshot(sourceItem: ResultRow): EventItemSourceSnapshot {
        val custodianName = sourceItem[Items.custodianId]?.let { custodianId ->
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq custodianId }
                .firstOrNull()
                ?.get(OrganizationalUnits.name)
        }
        val locationPath = sourceItem[Items.locationId]?.let { locationId ->
            val nodesById = Locations.selectAll()
                .where { Locations.tuntasId eq sourceItem[Items.tuntasId] }
                .toList()
                .associate { it[Locations.id] to it.toLocationNodeData() }
            buildLocationPath(locationId, nodesById)
        }
        val responsibleUserName = sourceItem[Items.responsibleUserId]?.let { responsibleUserId ->
            Users.selectAll()
                .where { Users.id eq responsibleUserId }
                .firstOrNull()
                ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
        }
        return EventItemSourceSnapshot(
            custodianName = custodianName,
            locationPath = locationPath,
            temporaryStorageLabel = sourceItem[Items.temporaryStorageLabel],
            responsibleUserName = responsibleUserName
        )
    }

    private fun buildSourcePickupSummary(
        custodianName: String?,
        locationPath: String?,
        temporaryStorageLabel: String?,
        responsibleUserName: String?
    ): String? {
        val parts = listOfNotNull(
            custodianName?.takeIf { it.isNotBlank() },
            locationPath?.takeIf { it.isNotBlank() },
            temporaryStorageLabel?.takeIf { it.isNotBlank() },
            responsibleUserName?.takeIf { it.isNotBlank() }?.let { "Pas $it" }
        )
        return parts.joinToString(" / ").takeIf { it.isNotBlank() }
    }

    private fun buildReturnLocationNote(returnToMode: String?, returnLocationNote: String?): String? {
        val modeLabel = when (returnToMode) {
            "ORIGINAL_SOURCE" -> "Original source"
            "EVENT_STORAGE" -> "Event storage"
            "OTHER_LOCATION" -> "Other location"
            else -> null
        }
        return listOfNotNull(modeLabel, returnLocationNote?.takeIf { it.isNotBlank() })
            .joinToString(" / ")
            .takeIf { it.isNotBlank() }
    }

    private fun buildMovementNote(notes: String?, returnToMode: String?, returnLocationNote: String?): String? {
        val destination = buildReturnLocationNote(returnToMode, returnLocationNote)
        return listOfNotNull(notes?.takeIf { it.isNotBlank() }, destination?.let { "Returned to: $it" })
            .joinToString(" | ")
            .takeIf { it.isNotBlank() }
    }

    private fun itemConditionLabel(itemId: UUID?): String? {
        if (itemId == null) return null
        return Items.select(Items.condition)
            .where { Items.id eq itemId }
            .firstOrNull()
            ?.get(Items.condition)
    }

    private fun reconciledPurchaseQuantity(purchaseItemId: UUID): Int {
        return EventPurchaseItemReconciliations.selectAll()
            .where { EventPurchaseItemReconciliations.purchaseItemId eq purchaseItemId }
            .sumOf { it[EventPurchaseItemReconciliations.quantity] }
    }

    private fun reconciliationPurchaseRows(eventId: UUID): List<ResultRow> {
        return EventPurchaseItems
            .innerJoin(EventPurchases, { purchaseId }, { id })
            .innerJoin(EventInventoryItems, { EventPurchaseItems.eventInventoryItemId }, { EventInventoryItems.id })
            .selectAll()
            .where {
                (EventPurchases.eventId eq eventId) and
                    (EventPurchases.status eq "PURCHASED") and
                    (EventPurchaseItems.addedToInventory eq false)
            }
            .toList()
    }

    private fun reconciliationBlockingCounts(eventId: UUID): Pair<Int, Int> {
        val openReturnCount = EventInventoryCustody
            .innerJoin(EventInventoryItems, { EventInventoryCustody.eventInventoryItemId }, { id })
            .selectAll()
            .where {
                (EventInventoryItems.eventId eq eventId) and
                    (EventInventoryCustody.status eq "OPEN")
            }
            .count { openQuantity(it) > 0 }
        return openReturnCount to reconciliationPurchaseRows(eventId).size
    }

    private fun getOrCreateEventReturnSession(eventId: UUID, tuntasId: UUID, userId: UUID): UUID {
        val existing = ItemCheckSessions.selectAll()
            .where {
                (ItemCheckSessions.eventId eq eventId) and
                    (ItemCheckSessions.tuntasId eq tuntasId) and
                    (ItemCheckSessions.contextType eq "EVENT_RETURN") and
                    (ItemCheckSessions.status eq "OPEN")
            }
            .orderBy(ItemCheckSessions.createdAt to SortOrder.DESC)
            .firstOrNull()
        if (existing != null) return existing[ItemCheckSessions.id]

        val now = kotlinx.datetime.Clock.System.now()
        return ItemCheckSessions.insert {
            it[ItemCheckSessions.tuntasId] = tuntasId
            it[contextType] = "EVENT_RETURN"
            it[ItemCheckSessions.eventId] = eventId
            it[scopeCustodianId] = null
            it[scopeType] = null
            it[scopeCategory] = null
            it[scopeSharedOnly] = false
            it[scopePersonalOwnerUserId] = null
            it[startedByUserId] = userId
            it[completedByUserId] = null
            it[status] = "OPEN"
            it[notes] = null
            it[createdAt] = now
            it[completedAt] = null
        }[ItemCheckSessions.id]
    }

    private fun latestEventReturnSessionId(eventId: UUID): UUID? {
        return ItemCheckSessions.selectAll()
            .where {
                (ItemCheckSessions.eventId eq eventId) and
                    (ItemCheckSessions.contextType eq "EVENT_RETURN")
            }
            .orderBy(ItemCheckSessions.createdAt to SortOrder.DESC)
            .firstOrNull()
            ?.get(ItemCheckSessions.id)
    }

    private fun reconciliationChecksByCustodyId(eventId: UUID): Map<UUID, List<ResultRow>> {
        return ItemChecks
            .innerJoin(ItemCheckSessions, { sessionId }, { ItemCheckSessions.id })
            .selectAll()
            .where {
                (ItemCheckSessions.eventId eq eventId) and
                    (ItemCheckSessions.contextType eq "EVENT_RETURN") and
                    ItemChecks.custodyId.isNotNull()
            }
            .orderBy(ItemChecks.checkedAt to SortOrder.DESC)
            .toList()
            .groupBy { it[ItemChecks.custodyId]!! }
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

    private fun getOrCreateEventReservationGroup(event: ResultRow, reservedByUserId: UUID): UUID {
        val eventId = event[Events.id]
        val existing = EventInventoryItems.select(EventInventoryItems.reservationGroupId)
            .where {
                (EventInventoryItems.eventId eq eventId) and
                    (EventInventoryItems.reservationGroupId.isNotNull())
            }
            .firstOrNull()
            ?.get(EventInventoryItems.reservationGroupId)
        if (existing != null) return existing

        return Reservations.select(Reservations.groupId)
            .where {
                (Reservations.eventId eq eventId) and
                    (Reservations.tuntasId eq event[Events.tuntasId]) and
                    (Reservations.status neq "CANCELLED")
            }
            .orderBy(Reservations.createdAt, SortOrder.ASC)
            .firstOrNull()
            ?.get(Reservations.groupId)
            ?: UUID.randomUUID()
    }

    private fun syncEventReservationItem(
        groupId: UUID,
        event: ResultRow,
        itemId: UUID,
        reservedByUserId: UUID?,
        quantity: Int,
        notes: String?
    ): Exception? {
        if (quantity <= 0) {
            cancelEventReservationItem(groupId, itemId)
            return null
        }

        val item = Items.selectAll()
            .where { (Items.id eq itemId) and (Items.tuntasId eq event[Events.tuntasId]) and (Items.status eq "ACTIVE") }
            .firstOrNull() ?: return Exception("Item not found or not active")
        val available = availableQuantityForEventItem(itemId, event[Events.startDate], event[Events.endDate], groupId)
        if (quantity > available) {
            return Exception("Insufficient available quantity for ${item[Items.name]}. Available: $available, requested: $quantity")
        }

        val now = kotlinx.datetime.Clock.System.now()
        val existing = Reservations.selectAll()
            .where { (Reservations.groupId eq groupId) and (Reservations.itemId eq itemId) }
            .firstOrNull()
        if (existing != null) {
            Reservations.update({ Reservations.id eq existing[Reservations.id] }) {
                it[Reservations.quantity] = quantity
                it[title] = event[Events.name]
                it[startDate] = event[Events.startDate]
                it[endDate] = event[Events.endDate]
                it[status] = "APPROVED"
                it[unitReviewStatus] = if (item[Items.custodianId] == null) "NOT_REQUIRED" else "APPROVED"
                it[topLevelReviewStatus] = "APPROVED"
                it[updatedAt] = now
                it[Reservations.notes] = notes
            }
            return null
        }

        val actorId = reservedByUserId ?: event[Events.createdByUserId] ?: item[Items.createdByUserId]
            ?: return Exception("Cannot create event reservation without a responsible user")
        Reservations.insert {
            it[this.groupId] = groupId
            it[title] = event[Events.name]
            it[this.itemId] = itemId
            it[tuntasId] = event[Events.tuntasId]
            it[this.reservedByUserId] = actorId
            it[requestingUnitId] = item[Items.custodianId]
            it[eventId] = event[Events.id]
            it[this.quantity] = quantity
            it[startDate] = event[Events.startDate]
            it[endDate] = event[Events.endDate]
            it[unitReviewStatus] = if (item[Items.custodianId] == null) "NOT_REQUIRED" else "APPROVED"
            if (item[Items.custodianId] != null) {
                it[unitReviewedByUserId] = actorId
                it[unitReviewedAt] = now
            }
            it[topLevelReviewStatus] = "APPROVED"
            it[topLevelReviewedByUserId] = actorId
            it[topLevelReviewedAt] = now
            it[status] = "APPROVED"
            it[this.notes] = notes
            it[createdAt] = now
            it[updatedAt] = now
        }
        return null
    }

    private fun cancelEventReservationItem(groupId: UUID, itemId: UUID) {
        Reservations.update({
            (Reservations.groupId eq groupId) and
                (Reservations.itemId eq itemId) and
                (Reservations.status inList listOf("PENDING", "APPROVED", "ACTIVE"))
        }) {
            it[status] = "CANCELLED"
            it[updatedAt] = kotlinx.datetime.Clock.System.now()
        }
        val hasActiveRows = Reservations.selectAll()
            .where {
                (Reservations.groupId eq groupId) and
                    (Reservations.status inList listOf("PENDING", "APPROVED", "ACTIVE"))
            }
            .any()
        if (!hasActiveRows) cancelReservationGroup(groupId)
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

    private fun validateEventType(type: String): Exception? {
        if (type !in validTypes) {
            return Exception("Invalid event type")
        }
        return null
    }

    private fun normalizeEventType(type: String): String? =
        type.trim().uppercase().takeIf { it.isNotBlank() }

    private fun normalizeCustomTypeLabel(label: String?): String? =
        label?.trim()?.takeIf { it.isNotBlank() }

    private fun validateCustomTypeLabel(label: String?): Exception? {
        if (label != null && label.length > 100) {
            return Exception("Custom event type label must be at most 100 characters")
        }
        return null
    }

    private fun deleteManagedDocument(url: String?) {
        UploadStorage.deleteManagedUpload(url, UploadStorage.documentUrlPrefix)
    }
}
