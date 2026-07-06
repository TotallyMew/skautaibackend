package lt.skautai.services

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime
import lt.skautai.database.tables.BendrasInventoryRequests
import lt.skautai.database.tables.DraugoveRequisitions
import lt.skautai.database.tables.EventInventoryCustody
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.EventInventoryRequests
import lt.skautai.database.tables.EventPackingLines
import lt.skautai.database.tables.EventPurchaseItems
import lt.skautai.database.tables.EventPurchases
import lt.skautai.database.tables.EventRoles
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.ItemCheckSessions
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.LeadershipChangeRequests
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.ReservationMovements
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.models.responses.MyTaskListResponse
import lt.skautai.models.responses.MyTaskResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.UUID

class MyTaskService {

    private val eventInventoryTaskRoles = setOf("VIRSININKAS", "KOMENDANTAS", "UKVEDYS")

    fun getMyTasks(tuntasId: UUID, userId: UUID): Result<MyTaskListResponse> = transaction {
        val permissionContext = PermissionContextService.resolve(userId, tuntasId)
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val now = Clock.System.now()
        val tasks = buildList {
            addInventoryApprovalTask(this, tuntasId, permissionContext, now)
            addReservationApprovalTask(this, tuntasId, userId, permissionContext, now)
            addMyReturnTasks(this, tuntasId, userId, today, now)
            addReservationMovementTask(this, tuntasId, permissionContext, today, now)
            addRequisitionReviewTask(this, tuntasId, userId, permissionContext, now)
            addSharedPickupReviewTask(this, tuntasId, userId, permissionContext, now)
            addLeadershipChangeReviewTask(this, tuntasId, userId, now)
            addEventLogisticsTask(this, tuntasId, userId, now)
            addAssignedEventInventoryRequestTasks(this, tuntasId, userId, now)
            addEventPackingTask(this, tuntasId, userId, today, now)
            addEventReconciliationTask(this, tuntasId, userId, now)
            addAuditSessionTask(this, tuntasId, userId, permissionContext, now)
        }

        val sorted = tasks.sortedWith(
            compareBy<MyTaskResponse> { bucketOrder(it.bucket) }
                .thenByDescending { urgencyScore(it.urgency) }
                .thenBy { it.priority }
                .thenBy { it.dueAt ?: "9999-12-31T23:59:59Z" }
                .thenBy { it.title }
        )
        Result.success(MyTaskListResponse(tasks = sorted, total = sorted.size))
    }

    private fun addInventoryApprovalTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        if (!permissionContext.has("items.review")) return
        val count = Items.selectAll()
            .where {
                (Items.tuntasId eq tuntasId) and
                    (Items.status eq "PENDING_APPROVAL")
            }
            .count()
            .toInt()
        if (count == 0) return
        tasks += task(
            type = "INVENTORY_APPROVAL_PENDING",
            title = "Patvirtink naujus daiktus",
            subtitle = "Laukia naujų inventoriaus įrašų peržiūra.",
            count = count,
            priority = 20,
            urgency = "HIGH",
            bucket = "NEXT",
            routeTarget = "inventory_list",
            createdAt = now,
            entityId = null
        )
    }

    private fun addReservationApprovalTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        val rows = Reservations.selectAll()
            .where {
                (Reservations.tuntasId eq tuntasId) and
                    (Reservations.status eq "PENDING")
            }
            .toList()
            .groupBy { it[Reservations.groupId] }

        val itemCustodians = itemCustodianIds(rows.values.flatten().map { it[Reservations.itemId] }.toSet())
        val pending = rows.values.count { groupRows ->
            reservationNeedsMyApproval(groupRows, userId, permissionContext, itemCustodians)
        }
        if (pending == 0) return
        tasks += task(
            type = "RESERVATION_APPROVAL_PENDING",
            title = "Peržiūrėk rezervacijas",
            subtitle = "Rezervacijos laukia tavo sprendimo.",
            count = pending,
            priority = 30,
            urgency = "HIGH",
            bucket = "NEXT",
            routeTarget = "reservation_list?mode=assigned",
            createdAt = now
        )
    }

    private fun addMyReturnTasks(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        today: kotlinx.datetime.LocalDate,
        now: Instant
    ) {
        val groups = Reservations.selectAll()
            .where {
                (Reservations.tuntasId eq tuntasId) and
                    (Reservations.reservedByUserId eq userId) and
                    (Reservations.status inList listOf("APPROVED", "ACTIVE"))
            }
            .toList()
            .groupBy { it[Reservations.groupId] }

        val movementTotals = movementTotals(groups.keys)
        val overdue = mutableListOf<ResultRow>()
        val dueToday = mutableListOf<ResultRow>()
        groups.values.forEach { rows ->
            val first = rows.first()
            if (remainingToReturn(rows, movementTotals[first[Reservations.groupId]].orEmpty()) <= 0) return@forEach
            val dueDate = first[Reservations.returnAt]?.toLocalDateTime(TimeZone.currentSystemDefault())?.date
                ?: first[Reservations.endDate]
            when {
                dueDate < today -> overdue += first
                dueDate == today -> dueToday += first
            }
        }

        if (overdue.isNotEmpty()) {
            val earliestDue = overdue.mapNotNull { it[Reservations.returnAt] }.minOrNull()
            tasks += task(
                type = "MY_RETURN_OVERDUE",
                title = "Grąžinimai vėluoja",
                subtitle = "Tavo rezervacijose yra negrąžintų daiktų po termino.",
                count = overdue.size,
                priority = 0,
                urgency = "CRITICAL",
                bucket = "URGENT",
                routeTarget = "reservation_list?mode=my_active",
                createdAt = now,
                dueAt = earliestDue
            )
        }

        if (dueToday.isNotEmpty()) {
            val earliestDue = dueToday.mapNotNull { it[Reservations.returnAt] }.minOrNull()
                ?: today.atStartOfDayIn(TimeZone.currentSystemDefault())
            tasks += task(
                type = "MY_RETURN_DUE_TODAY",
                title = "Grąžinimai šiandien",
                subtitle = "Šiandien reikia grąžinti tavo paimtus daiktus.",
                count = dueToday.size,
                priority = 10,
                urgency = "HIGH",
                bucket = "TODAY",
                routeTarget = "reservation_list?mode=my_active",
                createdAt = now,
                dueAt = earliestDue
            )
        }
    }

    private fun addReservationMovementTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        permissionContext: PermissionContext,
        today: kotlinx.datetime.LocalDate,
        now: Instant
    ) {
        val groups = Reservations.selectAll()
            .where {
                (Reservations.tuntasId eq tuntasId) and
                    (Reservations.status inList listOf("PENDING", "APPROVED", "ACTIVE"))
            }
            .toList()
            .groupBy { it[Reservations.groupId] }

        val movementTotals = movementTotals(groups.keys)
        val itemCustodians = itemCustodianIds(groups.values.flatten().map { it[Reservations.itemId] }.toSet())
        val relevant = groups.values.filter { rows ->
            val groupId = rows.first()[Reservations.groupId]
            reservationHasOpenMovement(
                rows = rows,
                permissionContext = permissionContext,
                totals = movementTotals[groupId].orEmpty(),
                itemCustodians = itemCustodians
            )
        }
        if (relevant.isEmpty()) return

        val dueDates = relevant.mapNotNull { rows ->
            val groupId = rows.first()[Reservations.groupId]
            earliestMovementDueDate(rows, movementTotals[groupId].orEmpty())
        }
        val hasOverdue = dueDates.any { it < today }
        val hasToday = dueDates.any { it == today }
        val dueAt = relevant.mapNotNull { rows ->
            val groupId = rows.first()[Reservations.groupId]
            earliestMovementDueInstant(rows, movementTotals[groupId].orEmpty())
        }.minOrNull()
        val bucket = when {
            hasOverdue -> "URGENT"
            hasToday -> "TODAY"
            else -> "NEXT"
        }
        val urgency = when {
            hasOverdue -> "CRITICAL"
            hasToday -> "HIGH"
            else -> "MEDIUM"
        }

        tasks += task(
            type = "RESERVATION_MOVEMENT_OPEN",
            title = "Užbaik išdavimą ir grąžinimą",
            subtitle = "Sekamose rezervacijose dar yra nebaigtų judėjimų.",
            count = relevant.size,
            priority = 40,
            urgency = urgency,
            bucket = bucket,
            routeTarget = "reservation_list?mode=tracked",
            createdAt = now,
            dueAt = dueAt
        )
    }

    private fun addRequisitionReviewTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        val reviewableUnitIds = resolveReviewableUnitIds(userId, tuntasId)
        val clauseArgs = mutableListOf<Pair<IColumnType<*>, Any?>>()
        val clauses = buildList {
            if (
                reviewableUnitIds.isNotEmpty() &&
                (permissionContext.has("items.request.approve.unit") || permissionContext.has("items.request.forward.bendras"))
            ) {
                add(
                    """
                    organizational_unit_id IN (${reviewableUnitIds.sqlPlaceholders()})
                    AND unit_review_status = 'PENDING'
                    """.trimIndent()
                )
                clauseArgs += reviewableUnitIds.uuidArgs()
            }
            if (permissionContext.hasAll("requisitions.approve")) {
                add("top_level_review_status = 'PENDING'")
            }
        }
        if (clauses.isEmpty()) return
        val count = countSql(
            """
            SELECT COUNT(*)
            FROM draugove_requisitions
            WHERE tuntas_id = ?
              AND created_by_user_id <> ?
              AND (${clauses.joinToString(" OR ") { "($it)" }})
            """.trimIndent(),
            listOf(uuidParam(tuntasId), uuidParam(userId)) + clauseArgs
        )
        if (count == 0) return
        tasks += task(
            type = "REQUISITION_REVIEW_PENDING",
            title = "Atsakyk į pirkimo prašymus",
            subtitle = "Vienetų prašymai laukia tavo peržiūros.",
            count = count,
            priority = 50,
            urgency = "HIGH",
            bucket = "NEXT",
            routeTarget = "request_list?mode=assigned",
            createdAt = now
        )
    }

    private fun addSharedPickupReviewTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        if (!permissionContext.hasAll("items.request.approve.bendras")) return
        val count = countSql(
            """
            SELECT COUNT(*)
            FROM bendras_inventory_requests
            WHERE tuntas_id = ?
              AND top_level_status = 'PENDING'
              AND requested_by_user_id <> ?
            """.trimIndent(),
            listOf(uuidParam(tuntasId), uuidParam(userId))
        )
        if (count == 0) return
        tasks += task(
            type = "SHARED_PICKUP_REVIEW_PENDING",
            title = "Peržiūrėk paėmimo prašymus",
            subtitle = "Vienetai laukia sprendimo dėl bendro inventoriaus paėmimo.",
            count = count,
            priority = 60,
            urgency = "HIGH",
            bucket = "NEXT",
            routeTarget = "shared_request_list",
            createdAt = now
        )
    }

    private fun addLeadershipChangeReviewTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        now: Instant
    ) {
        if (!isTopLevelLeader(userId, tuntasId)) return
        val count = countSql(
            """
            SELECT COUNT(*)
            FROM leadership_change_requests
            WHERE tuntas_id = ?
              AND status = 'PENDING'
              AND requester_user_id <> ?
            """.trimIndent(),
            listOf(uuidParam(tuntasId), uuidParam(userId))
        )
        if (count == 0) return
        tasks += task(
            type = "LEADERSHIP_CHANGE_REVIEW_PENDING",
            title = "Perziurek vadovu pasikeitimus",
            subtitle = "Vienetu vadovu atsistatydinimo prasymai laukia sprendimo.",
            count = count,
            priority = 65,
            urgency = "HIGH",
            bucket = "NEXT",
            routeTarget = "my_tasks",
            createdAt = now
        )
    }

    private fun addEventLogisticsTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        now: Instant
    ) {
        val eventRows = actionableEventRows(tuntasId, userId, eventInventoryTaskRoles)
        val openLogisticsEventIds = eventsWithOpenLogistics(eventRows.map { it[Events.id] })
        val relevant = eventRows.filter { event ->
            event[Events.status] in listOf("PLANNING", "ACTIVE", "WRAP_UP") &&
                event[Events.id] in openLogisticsEventIds
        }
        if (relevant.isEmpty()) return
        val single = relevant.singleOrNull()
        tasks += task(
            type = "EVENT_LOGISTICS_OPEN",
            title = if (single != null) "Sutvarkyk renginio logistiką" else "Peržiūrėk renginių logistiką",
            subtitle = if (single != null) single[Events.name] else "${relevant.size} renginiai turi neužbaigtų logistinių darbų.",
            count = relevant.size,
            priority = 70,
            urgency = "LOW",
            bucket = "WATCH",
            routeTarget = single?.let { "event_plan/${it[Events.id]}" } ?: "event_list",
            createdAt = now,
            entityId = single?.get(Events.id)?.toString()
        )
    }

    private fun addAssignedEventInventoryRequestTasks(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        now: Instant
    ) {
        val rows = EventInventoryRequests
            .innerJoin(Events, { EventInventoryRequests.eventId }, { Events.id })
            .selectAll()
            .where {
                (Events.tuntasId eq tuntasId) and
                    (EventInventoryRequests.responsibleUserId eq userId) and
                    (EventInventoryRequests.status inList listOf("PENDING", "APPROVED"))
            }
            .toList()
        if (rows.isEmpty()) return

        rows.groupBy { it[EventInventoryRequests.eventId] }.forEach { (eventId, eventRows) ->
            val dueAt = eventRows.mapNotNull { it[EventInventoryRequests.dueAt] }.minOrNull()
            val overdue = dueAt?.let { it < now } == true
            tasks += task(
                type = "EVENT_INVENTORY_REQUEST_ASSIGNED",
                title = if (eventRows.size == 1) "Parūpink renginio poreikį" else "Parūpink renginio poreikius",
                subtitle = "${eventRows.first()[Events.name]}: ${eventRows.size} atviros eilutės.",
                count = eventRows.size,
                priority = if (overdue) 3 else 35,
                urgency = if (overdue) "CRITICAL" else if (dueAt != null) "HIGH" else "MEDIUM",
                bucket = if (overdue) "URGENT" else if (dueAt != null) "NEXT" else "WATCH",
                routeTarget = "event_plan/$eventId",
                createdAt = now,
                dueAt = dueAt,
                entityId = eventId.toString()
            )
        }
    }

    private fun addEventReconciliationTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        now: Instant
    ) {
        val eventRows = actionableEventRows(tuntasId, userId, eventInventoryTaskRoles)
        val openReconciliationEventIds = eventsWithOpenReconciliation(eventRows.map { it[Events.id] })
        val relevant = eventRows.filter { event ->
            event[Events.status] in listOf("WRAP_UP", "COMPLETED") &&
                event[Events.id] in openReconciliationEventIds
        }
        if (relevant.isEmpty()) return
        val single = relevant.singleOrNull()
        tasks += task(
            type = "EVENT_RECONCILIATION_OPEN",
            title = if (single != null) "Užbaik renginio suvedimą" else "Peržiūrėk renginių suvedimą",
            subtitle = if (single != null) single[Events.name] else "${relevant.size} renginiai dar turi neužbaigtą suvedimą.",
            count = relevant.size,
            priority = 80,
            urgency = "MEDIUM",
            bucket = "WATCH",
            routeTarget = single?.let { "event_reconciliation/${it[Events.id]}" } ?: "event_list",
            createdAt = now,
            entityId = single?.get(Events.id)?.toString()
        )
    }

    private fun addEventPackingTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        today: kotlinx.datetime.LocalDate,
        now: Instant
    ) {
        val eventRows = actionableEventRows(tuntasId, userId, eventInventoryTaskRoles)
        val eventIds = eventRows.map { it[Events.id] }
        val eventsWithInventoryPlan = eventsWithInventoryPlan(eventIds)
        val eventsWithPackingList = eventsWithPackingList(eventIds)
        val eventsWithOpenPacking = eventsWithOpenPacking(eventIds)
        val eventsWithOpenPackingReturns = eventsWithOpenPackingReturns(eventIds)
        val needsGeneration = eventRows.filter { event ->
            event[Events.status] in listOf("PLANNING", "ACTIVE") &&
                today.daysUntil(event[Events.startDate]) in 0..7 &&
                event[Events.id] in eventsWithInventoryPlan &&
                event[Events.id] !in eventsWithPackingList
        }
        val openBeforeStart = eventRows.filter { event ->
            event[Events.status] in listOf("PLANNING", "ACTIVE") &&
                today.daysUntil(event[Events.startDate]) <= 2 &&
                event[Events.id] in eventsWithOpenPacking
        }
        val openReturns = eventRows.filter { event ->
            event[Events.status] in listOf("WRAP_UP", "COMPLETED") &&
                event[Events.id] in eventsWithOpenPackingReturns
        }

        val generationSingle = needsGeneration.singleOrNull()
        if (needsGeneration.isNotEmpty()) {
            tasks += task(
                type = "EVENT_PACKING_GENERATE",
                title = if (generationSingle != null) "Sugeneruok pakavimo sarasa" else "Sugeneruok renginiu pakavimo sarasus",
                subtitle = if (generationSingle != null) generationSingle[Events.name] else "${needsGeneration.size} renginiai arteja be pakavimo saraso.",
                count = needsGeneration.size,
                priority = 68,
                urgency = "MEDIUM",
                bucket = "NEXT",
                routeTarget = generationSingle?.let { "event_packing/${it[Events.id]}" } ?: "event_list",
                createdAt = now,
                dueAt = needsGeneration.minOfOrNull { it[Events.startDate] }?.atStartOfDayIn(TimeZone.currentSystemDefault()),
                entityId = generationSingle?.get(Events.id)?.toString()
            )
        }

        val packingSingle = openBeforeStart.singleOrNull()
        if (openBeforeStart.isNotEmpty()) {
            val hasToday = openBeforeStart.any { today.daysUntil(it[Events.startDate]) <= 0 }
            tasks += task(
                type = "EVENT_PACKING_OPEN",
                title = if (packingSingle != null) "Užbaik renginio pakavima" else "Užbaik renginiu pakavima",
                subtitle = if (packingSingle != null) packingSingle[Events.name] else "${openBeforeStart.size} renginiai turi nebaigtu pakavimo eiluciu.",
                count = openBeforeStart.size,
                priority = 5,
                urgency = if (hasToday) "CRITICAL" else "HIGH",
                bucket = if (hasToday) "URGENT" else "TODAY",
                routeTarget = packingSingle?.let { "event_packing/${it[Events.id]}" } ?: "event_list",
                createdAt = now,
                dueAt = openBeforeStart.minOfOrNull { it[Events.startDate] }?.atStartOfDayIn(TimeZone.currentSystemDefault()),
                entityId = packingSingle?.get(Events.id)?.toString()
            )
        }

        val returnsSingle = openReturns.singleOrNull()
        if (openReturns.isNotEmpty()) {
            tasks += task(
                type = "EVENT_PACKING_RETURN_OPEN",
                title = if (returnsSingle != null) "Sutikrink grąžinta inventoriu" else "Sutikrink renginiu grąžinimus",
                subtitle = if (returnsSingle != null) returnsSingle[Events.name] else "${openReturns.size} renginiai turi pakavimo eiluciu nepažymetu kaip grąžintos.",
                count = openReturns.size,
                priority = 12,
                urgency = "HIGH",
                bucket = "TODAY",
                routeTarget = returnsSingle?.let { "event_packing/${it[Events.id]}" } ?: "event_list",
                createdAt = now,
                entityId = returnsSingle?.get(Events.id)?.toString()
            )
        }
    }

    private fun addAuditSessionTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        if (!permissionContext.has("items.view")) return
        val sessionsQuery = ItemCheckSessions.selectAll()
            .where {
                (ItemCheckSessions.tuntasId eq tuntasId) and
                    (ItemCheckSessions.status eq "OPEN")
            }
            .orderBy(ItemCheckSessions.createdAt, SortOrder.ASC)
        val sessions = sessionsQuery
            .toList()
            .filter { session ->
                session[ItemCheckSessions.startedByUserId] == userId ||
                    permissionContext.hasAll("items.view") ||
                    permissionContext.targetAllowed("items.view", session[ItemCheckSessions.scopeCustodianId])
            }
        if (sessions.isEmpty()) return
        val single = sessions.singleOrNull()
        tasks += task(
            type = "AUDIT_SESSION_OPEN",
            title = if (single != null) "Tęsk inventorizaciją" else "Atviros inventorizacijos sesijos",
            subtitle = if (single != null) "Inventorizacijos sesija dar neužbaigta." else "Yra ${sessions.size} neužbaigtos inventorizacijos sesijos.",
            count = sessions.size,
            priority = 90,
            urgency = "LOW",
            bucket = "WATCH",
            routeTarget = single?.let { "inventory_audit_session/${it[ItemCheckSessions.id]}" } ?: "inventory_audit_history",
            createdAt = now,
            entityId = single?.get(ItemCheckSessions.id)?.toString()
        )
    }

    private fun reservationNeedsMyApproval(
        rows: List<ResultRow>,
        userId: UUID,
        permissionContext: PermissionContext,
        itemCustodians: Map<UUID, UUID?>
    ): Boolean {
        val first = rows.first()
        if (first[Reservations.reservedByUserId] == userId) return false
        val itemCustodianIds = rows.mapNotNull { itemCustodians[it[Reservations.itemId]] }.toSet()
        val unitPending = (first[Reservations.unitReviewStatus] == "PENDING" ||
            (first[Reservations.unitReviewStatus] == "NOT_REQUIRED" && itemCustodianIds.isNotEmpty())) &&
            itemCustodianIds.any { permissionContext.targetAllowed("reservations.approve", it) }
        val topLevelPending = (first[Reservations.topLevelReviewStatus] == "PENDING" ||
            (first[Reservations.topLevelReviewStatus] == "NOT_REQUIRED" && itemCustodianIds.isEmpty())) &&
            permissionContext.hasAll("reservations.approve")
        return unitPending || topLevelPending
    }

    private fun reservationHasOpenMovement(
        rows: List<ResultRow>,
        permissionContext: PermissionContext,
        totals: Map<UUID, MovementTotals>,
        itemCustodians: Map<UUID, UUID?>
    ): Boolean {
        val itemRows = rows.associateBy { it[Reservations.itemId] }
        return itemRows.any { (itemId, reservationRow) ->
            val custodianId = itemCustodians[itemId]
            val canManage = if (custodianId == null) {
                permissionContext.hasAll("reservations.approve")
            } else {
                permissionContext.targetAllowed("reservations.approve", custodianId) || permissionContext.hasAll("reservations.approve")
            }
            if (!canManage) return@any false
            val quantity = reservationRow[Reservations.quantity]
            val movement = totals[itemId] ?: MovementTotals()
            val remainingIssue = (quantity - movement.issued).coerceAtLeast(0)
            val remainingReturn = (movement.issued - movement.returned).coerceAtLeast(0)
            val remainingReceive = (movement.returnedMarked - movement.returned).coerceAtLeast(0)
            remainingIssue > 0 || remainingReturn > 0 || remainingReceive > 0
        }
    }

    private fun remainingToReturn(rows: List<ResultRow>, totals: Map<UUID, MovementTotals>): Int {
        return rows.sumOf { row ->
            val movement = totals[row[Reservations.itemId]] ?: MovementTotals()
            (movement.issued - movement.returned).coerceAtLeast(0)
        }
    }

    private fun earliestMovementDueDate(
        rows: List<ResultRow>,
        totals: Map<UUID, MovementTotals>
    ): kotlinx.datetime.LocalDate? =
        rows.mapNotNull { row ->
            when {
                row[Reservations.pickupAt] != null && remainingToIssueForRow(row, totals) > 0 ->
                    row[Reservations.pickupAt]!!.toLocalDateTime(TimeZone.currentSystemDefault()).date
                row[Reservations.returnAt] != null && remainingToReturnForRow(row, totals) > 0 ->
                    row[Reservations.returnAt]!!.toLocalDateTime(TimeZone.currentSystemDefault()).date
                else -> null
            }
        }.minOrNull()

    private fun earliestMovementDueInstant(
        rows: List<ResultRow>,
        totals: Map<UUID, MovementTotals>
    ): Instant? =
        rows.mapNotNull { row ->
            when {
                row[Reservations.pickupAt] != null && remainingToIssueForRow(row, totals) > 0 ->
                    row[Reservations.pickupAt]!!
                row[Reservations.returnAt] != null && remainingToReturnForRow(row, totals) > 0 ->
                    row[Reservations.returnAt]!!
                else -> null
            }
        }.minOrNull()

    private fun remainingToIssueForRow(row: ResultRow, totals: Map<UUID, MovementTotals>): Int {
        val movement = totals[row[Reservations.itemId]] ?: MovementTotals()
        return (row[Reservations.quantity] - movement.issued).coerceAtLeast(0)
    }

    private fun remainingToReturnForRow(row: ResultRow, totals: Map<UUID, MovementTotals>): Int {
        val movement = totals[row[Reservations.itemId]] ?: MovementTotals()
        return (movement.issued - movement.returned).coerceAtLeast(0)
    }

    private fun movementTotals(groupIds: Set<UUID>): Map<UUID, Map<UUID, MovementTotals>> {
        if (groupIds.isEmpty()) return emptyMap()
        return ReservationMovements.selectAll()
            .where { ReservationMovements.reservationGroupId inList groupIds.toList() }
            .groupBy { it[ReservationMovements.reservationGroupId] }
            .mapValues { (_, groupRows) ->
                groupRows
                    .groupBy { it[ReservationMovements.itemId] }
                    .mapValues { (_, itemRows) ->
                        MovementTotals(
                            issued = itemRows.filter { it[ReservationMovements.type] == "ISSUE" }.sumOf { it[ReservationMovements.quantity] },
                            returnedMarked = itemRows.filter { it[ReservationMovements.type] == "RETURN_MARKED" }.sumOf { it[ReservationMovements.quantity] },
                            returned = itemRows.filter { it[ReservationMovements.type] == "RETURN" }.sumOf { it[ReservationMovements.quantity] }
                        )
                    }
            }
    }

    private fun itemCustodianIds(itemIds: Set<UUID>): Map<UUID, UUID?> {
        if (itemIds.isEmpty()) return emptyMap()
        return Items.select(Items.id, Items.custodianId)
            .where { Items.id inList itemIds.toList() }
            .associate { it[Items.id] to it[Items.custodianId] }
    }

    private fun resolveReviewableUnitIds(userId: UUID, tuntasId: UUID): Set<UUID> {
        val unitLeaderRoles = listOf(
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

        return UserLeadershipRoles
            .innerJoin(Roles)
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    UserLeadershipRoles.organizationalUnitId.isNotNull() and
                    (Roles.name inList unitLeaderRoles)
            }
            .mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }
            .toSet()
    }

    private fun isTopLevelLeader(userId: UUID, tuntasId: UUID): Boolean =
        UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull()
            }
            .any { it[Roles.name] in setOf("Tuntininkas", "Tuntininko pavaduotojas") }

    private fun actionableEventRows(
        tuntasId: UUID,
        userId: UUID,
        roles: Set<String>
    ): List<ResultRow> {
        val eventRoleEventIds = EventRoles
            .select(EventRoles.eventId)
            .where {
                (EventRoles.userId eq userId) and
                    (EventRoles.role inList roles)
            }
            .map { it[EventRoles.eventId] }
            .toSet()
        if (eventRoleEventIds.isEmpty()) return emptyList()

        return Events.selectAll()
            .where {
                (Events.tuntasId eq tuntasId) and
                    (Events.id inList eventRoleEventIds)
            }
            .toList()
    }

    private fun eventsWithOpenLogistics(eventIds: List<UUID>): Set<UUID> {
        if (eventIds.isEmpty()) return emptySet()
        return uuidSetSql(
            """
            SELECT DISTINCT event_id
            FROM event_inventory_items
            WHERE event_id IN (${eventIds.sqlPlaceholders()})
              AND (needs_purchase = TRUE OR planned_quantity > available_quantity)
            """.trimIndent(),
            eventIds.uuidArgs()
        )
    }

    private fun eventsWithInventoryPlan(eventIds: List<UUID>): Set<UUID> {
        if (eventIds.isEmpty()) return emptySet()
        return uuidSetSql(
            """
            SELECT DISTINCT event_id
            FROM event_inventory_items
            WHERE event_id IN (${eventIds.sqlPlaceholders()})
            """.trimIndent(),
            eventIds.uuidArgs()
        )
    }

    private fun eventsWithPackingList(eventIds: List<UUID>): Set<UUID> {
        if (eventIds.isEmpty()) return emptySet()
        return uuidSetSql(
            """
            SELECT DISTINCT event_id
            FROM event_packing_lines
            WHERE event_id IN (${eventIds.sqlPlaceholders()})
            """.trimIndent(),
            eventIds.uuidArgs()
        )
    }

    private fun eventsWithOpenPacking(eventIds: List<UUID>): Set<UUID> {
        if (eventIds.isEmpty()) return emptySet()
        return uuidSetSql(
            """
            SELECT DISTINCT event_id
            FROM event_packing_lines
            WHERE event_id IN (${eventIds.sqlPlaceholders()})
              AND status NOT IN ('PACKED', 'LOADED', 'RETURNED')
            """.trimIndent(),
            eventIds.uuidArgs()
        )
    }

    private fun eventsWithOpenPackingReturns(eventIds: List<UUID>): Set<UUID> {
        if (eventIds.isEmpty()) return emptySet()
        return uuidSetSql(
            """
            SELECT DISTINCT event_id
            FROM event_packing_lines
            WHERE event_id IN (${eventIds.sqlPlaceholders()})
              AND status IN ('PICKED', 'PACKED', 'LOADED')
            """.trimIndent(),
            eventIds.uuidArgs()
        )
    }

    private fun eventsWithOpenReconciliation(eventIds: List<UUID>): Set<UUID> {
        if (eventIds.isEmpty()) return emptySet()
        return uuidSetSql(
            """
            SELECT DISTINCT event_inventory_items.event_id
            FROM event_inventory_custody
            INNER JOIN event_inventory_items
                ON event_inventory_custody.event_inventory_item_id = event_inventory_items.id
            WHERE event_inventory_items.event_id IN (${eventIds.sqlPlaceholders()})
              AND event_inventory_custody.status = 'OPEN'
              AND event_inventory_custody.quantity > event_inventory_custody.returned_quantity
            UNION
            SELECT DISTINCT event_purchases.event_id
            FROM event_purchase_items
            INNER JOIN event_purchases
                ON event_purchase_items.purchase_id = event_purchases.id
            WHERE event_purchases.event_id IN (${eventIds.sqlPlaceholders()})
              AND event_purchase_items.added_to_inventory = FALSE
            """.trimIndent(),
            eventIds.uuidArgs() + eventIds.uuidArgs()
        )
    }

    private fun eventHasOpenLogistics(eventId: UUID): Boolean =
        EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .any { row ->
                row[EventInventoryItems.needsPurchase] ||
                row[EventInventoryItems.plannedQuantity] > row[EventInventoryItems.availableQuantity]
            }

    private fun eventHasInventoryPlan(eventId: UUID): Boolean =
        EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .count() > 0

    private fun eventHasPackingList(eventId: UUID): Boolean =
        EventPackingLines.selectAll()
            .where { EventPackingLines.eventId eq eventId }
            .count() > 0

    private fun eventHasOpenPacking(eventId: UUID): Boolean =
        EventPackingLines.selectAll()
            .where { EventPackingLines.eventId eq eventId }
            .any { it[EventPackingLines.status] !in setOf("PACKED", "LOADED", "RETURNED") }

    private fun eventHasOpenPackingReturns(eventId: UUID): Boolean =
        EventPackingLines.selectAll()
            .where { EventPackingLines.eventId eq eventId }
            .any { it[EventPackingLines.status] in setOf("PICKED", "PACKED", "LOADED") }

    private fun eventHasOpenReconciliation(eventId: UUID): Boolean {
        val openReturns = EventInventoryCustody.selectAll()
            .where {
                (EventInventoryCustody.status eq "OPEN") and
                    (EventInventoryCustody.eventInventoryItemId inList eventInventoryItemIds(eventId))
            }
            .count { it[EventInventoryCustody.quantity] > it[EventInventoryCustody.returnedQuantity] }
        val openPurchases = EventPurchaseItems
            .innerJoin(EventPurchases)
            .selectAll()
            .where {
                (EventPurchases.eventId eq eventId) and
                    (EventPurchaseItems.addedToInventory eq false)
            }
            .count()
        return openReturns > 0 || openPurchases > 0
    }

    private fun eventInventoryItemIds(eventId: UUID): List<UUID> =
        EventInventoryItems.select(EventInventoryItems.id)
            .where { EventInventoryItems.eventId eq eventId }
            .map { it[EventInventoryItems.id] }

    private fun task(
        type: String,
        title: String,
        subtitle: String,
        count: Int? = null,
        priority: Int,
        urgency: String,
        bucket: String,
        routeTarget: String,
        createdAt: Instant,
        dueAt: Instant? = null,
        entityId: String? = null
    ): MyTaskResponse = MyTaskResponse(
        id = "$type:${entityId ?: routeTarget}",
        type = type,
        title = title,
        subtitle = subtitle,
        count = count,
        priority = priority,
        urgency = urgency,
        bucket = bucket,
        routeTarget = routeTarget,
        createdAt = createdAt.toString(),
        dueAt = dueAt?.toString(),
        entityId = entityId
    )

    private fun bucketOrder(bucket: String): Int = when (bucket) {
        "URGENT" -> 0
        "TODAY" -> 1
        "NEXT" -> 2
        "WATCH" -> 3
        else -> 4
    }

    private fun urgencyScore(urgency: String): Int = when (urgency) {
        "CRITICAL" -> 4
        "HIGH" -> 3
        "MEDIUM" -> 2
        "LOW" -> 1
        else -> 0
    }

    private fun countSql(sql: String, args: Iterable<Pair<IColumnType<*>, Any?>> = emptyList()): Int {
        var count = 0
        TransactionManager.current().exec(sql, args) { rs ->
            if (rs.next()) count = rs.getInt(1)
        }
        return count
    }

    private fun uuidSetSql(sql: String, args: Iterable<Pair<IColumnType<*>, Any?>> = emptyList()): Set<UUID> {
        val ids = mutableSetOf<UUID>()
        TransactionManager.current().exec(sql, args) { rs ->
            while (rs.next()) {
                ids += rs.getObject(1, UUID::class.java)
            }
        }
        return ids
    }

    private fun Collection<UUID>.sqlPlaceholders(): String =
        joinToString(",") { "?" }

    private fun Collection<UUID>.uuidArgs(): List<Pair<IColumnType<*>, Any?>> =
        map { uuidParam(it) }

    private fun uuidParam(value: UUID): Pair<IColumnType<*>, Any?> = UUIDColumnType() to value

    private data class MovementTotals(
        val issued: Int = 0,
        val returnedMarked: Int = 0,
        val returned: Int = 0
    )
}
