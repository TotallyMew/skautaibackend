package lt.skautai.services

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import lt.skautai.database.tables.BendrasInventoryRequests
import lt.skautai.database.tables.DraugoveRequisitions
import lt.skautai.database.tables.EventInventoryCustody
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.EventPurchaseItems
import lt.skautai.database.tables.EventPurchases
import lt.skautai.database.tables.EventRoles
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.ItemCheckSessions
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.ReservationMovements
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.models.responses.MyTaskListResponse
import lt.skautai.models.responses.MyTaskResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class MyTaskService {

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
            addEventLogisticsTask(this, tuntasId, userId, permissionContext, now)
            addEventReconciliationTask(this, tuntasId, userId, permissionContext, now)
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

        val pending = rows.values.count { groupRows ->
            reservationNeedsMyApproval(groupRows, userId, permissionContext)
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

        val overdue = mutableListOf<ResultRow>()
        val dueToday = mutableListOf<ResultRow>()
        groups.values.forEach { rows ->
            val first = rows.first()
            if (remainingToReturn(rows) <= 0) return@forEach
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

        val relevant = groups.values.filter { rows ->
            reservationHasOpenMovement(rows, permissionContext)
        }
        if (relevant.isEmpty()) return

        val dueDates = relevant.mapNotNull { earliestMovementDueDate(it) }
        val hasOverdue = dueDates.any { it < today }
        val hasToday = dueDates.any { it == today }
        val dueAt = relevant.mapNotNull { earliestMovementDueInstant(it) }.minOrNull()
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
        val count = DraugoveRequisitions.selectAll()
            .where { DraugoveRequisitions.tuntasId eq tuntasId }
            .count { row ->
                val waitsForUnit = row[DraugoveRequisitions.createdByUserId] != userId &&
                    row[DraugoveRequisitions.organizationalUnitId] in reviewableUnitIds &&
                    row[DraugoveRequisitions.unitReviewStatus] == "PENDING" &&
                    (permissionContext.has("items.request.approve.unit") || permissionContext.has("items.request.forward.bendras"))
                val waitsForTopLevel = row[DraugoveRequisitions.createdByUserId] != userId &&
                    permissionContext.hasAll("requisitions.approve") &&
                    row[DraugoveRequisitions.topLevelReviewStatus] == "PENDING"
                waitsForUnit || waitsForTopLevel
            }
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
        val count = BendrasInventoryRequests.selectAll()
            .where {
                (BendrasInventoryRequests.tuntasId eq tuntasId) and
                    (BendrasInventoryRequests.topLevelStatus eq "PENDING")
            }
            .count { it[BendrasInventoryRequests.requestedByUserId] != userId }
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

    private fun addEventLogisticsTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        if (!permissionContext.has("events.view")) return
        val eventRows = visibleEventRows(tuntasId, userId, permissionContext)
        val relevant = eventRows.filter { event ->
            event[Events.status] in listOf("PLANNING", "ACTIVE", "WRAP_UP") &&
                eventHasOpenLogistics(event[Events.id])
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

    private fun addEventReconciliationTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        if (!permissionContext.has("events.view")) return
        val eventRows = visibleEventRows(tuntasId, userId, permissionContext)
        val relevant = eventRows.filter { event ->
            event[Events.status] in listOf("WRAP_UP", "COMPLETED") &&
                eventHasOpenReconciliation(event[Events.id])
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

    private fun addAuditSessionTask(
        tasks: MutableList<MyTaskResponse>,
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext,
        now: Instant
    ) {
        if (!permissionContext.has("items.view")) return
        val sessions = ItemCheckSessions.selectAll()
            .where {
                (ItemCheckSessions.tuntasId eq tuntasId) and
                    (ItemCheckSessions.status eq "OPEN")
            }
            .orderBy(ItemCheckSessions.createdAt, SortOrder.ASC)
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
        permissionContext: PermissionContext
    ): Boolean {
        val first = rows.first()
        if (first[Reservations.reservedByUserId] == userId) return false
        val itemCustodianIds = itemCustodianIds(rows)
        val unitPending = (first[Reservations.unitReviewStatus] == "PENDING" ||
            (first[Reservations.unitReviewStatus] == "NOT_REQUIRED" && itemCustodianIds.isNotEmpty())) &&
            itemCustodianIds.any { permissionContext.targetAllowed("reservations.approve", it) }
        val topLevelPending = (first[Reservations.topLevelReviewStatus] == "PENDING" ||
            (first[Reservations.topLevelReviewStatus] == "NOT_REQUIRED" && itemCustodianIds.isEmpty())) &&
            permissionContext.hasAll("reservations.approve")
        return unitPending || topLevelPending
    }

    private fun reservationHasOpenMovement(rows: List<ResultRow>, permissionContext: PermissionContext): Boolean {
        val first = rows.first()
        val groupId = first[Reservations.groupId]
        val totals = movementTotals(groupId)
        val itemRows = rows.associateBy { it[Reservations.itemId] }
        return itemRows.any { (itemId, reservationRow) ->
            val custodianId = itemCustodianId(itemId)
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

    private fun remainingToReturn(rows: List<ResultRow>): Int {
        val totals = movementTotals(rows.first()[Reservations.groupId])
        return rows.sumOf { row ->
            val movement = totals[row[Reservations.itemId]] ?: MovementTotals()
            (movement.issued - movement.returned).coerceAtLeast(0)
        }
    }

    private fun earliestMovementDueDate(rows: List<ResultRow>): kotlinx.datetime.LocalDate? =
        rows.mapNotNull { row ->
            when {
                row[Reservations.pickupAt] != null && remainingToIssueForRow(rows.first()[Reservations.groupId], row) > 0 ->
                    row[Reservations.pickupAt]!!.toLocalDateTime(TimeZone.currentSystemDefault()).date
                row[Reservations.returnAt] != null && remainingToReturnForRow(rows.first()[Reservations.groupId], row) > 0 ->
                    row[Reservations.returnAt]!!.toLocalDateTime(TimeZone.currentSystemDefault()).date
                else -> null
            }
        }.minOrNull()

    private fun earliestMovementDueInstant(rows: List<ResultRow>): Instant? =
        rows.mapNotNull { row ->
            when {
                row[Reservations.pickupAt] != null && remainingToIssueForRow(rows.first()[Reservations.groupId], row) > 0 ->
                    row[Reservations.pickupAt]!!
                row[Reservations.returnAt] != null && remainingToReturnForRow(rows.first()[Reservations.groupId], row) > 0 ->
                    row[Reservations.returnAt]!!
                else -> null
            }
        }.minOrNull()

    private fun remainingToIssueForRow(groupId: UUID, row: ResultRow): Int {
        val movement = movementTotals(groupId)[row[Reservations.itemId]] ?: MovementTotals()
        return (row[Reservations.quantity] - movement.issued).coerceAtLeast(0)
    }

    private fun remainingToReturnForRow(groupId: UUID, row: ResultRow): Int {
        val movement = movementTotals(groupId)[row[Reservations.itemId]] ?: MovementTotals()
        return (movement.issued - movement.returned).coerceAtLeast(0)
    }

    private fun movementTotals(groupId: UUID): Map<UUID, MovementTotals> =
        ReservationMovements.selectAll()
            .where { ReservationMovements.reservationGroupId eq groupId }
            .groupBy { it[ReservationMovements.itemId] }
            .mapValues { (_, rows) ->
                MovementTotals(
                    issued = rows.filter { it[ReservationMovements.type] == "ISSUE" }.sumOf { it[ReservationMovements.quantity] },
                    returnedMarked = rows.filter { it[ReservationMovements.type] == "RETURN_MARKED" }.sumOf { it[ReservationMovements.quantity] },
                    returned = rows.filter { it[ReservationMovements.type] == "RETURN" }.sumOf { it[ReservationMovements.quantity] }
                )
            }

    private fun itemCustodianIds(rows: List<ResultRow>): Set<UUID> {
        val itemIds = rows.map { it[Reservations.itemId] }
        if (itemIds.isEmpty()) return emptySet()
        return Items.select(Items.custodianId)
            .where { Items.id inList itemIds }
            .mapNotNull { it[Items.custodianId] }
            .toSet()
    }

    private fun itemCustodianId(itemId: UUID): UUID? =
        Items.select(Items.custodianId)
            .where { Items.id eq itemId }
            .firstOrNull()
            ?.get(Items.custodianId)

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

    private fun visibleEventRows(
        tuntasId: UUID,
        userId: UUID,
        permissionContext: PermissionContext
    ): List<ResultRow> {
        val allRows = Events.selectAll()
            .where { Events.tuntasId eq tuntasId }
            .toList()
        if (permissionContext.hasAll("events.manage")) return allRows

        val scopedUnitIds = permissionContext.scopedUnitIds("events.manage")
        val eventRoleEventIds = EventRoles
            .select(EventRoles.eventId)
            .where { EventRoles.userId eq userId }
            .map { it[EventRoles.eventId] }
            .toSet()

        return allRows.filter { row ->
            row[Events.id] in eventRoleEventIds ||
                row[Events.organizationalUnitId] in scopedUnitIds
        }
    }

    private fun eventHasOpenLogistics(eventId: UUID): Boolean =
        EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .any { row ->
                row[EventInventoryItems.needsPurchase] ||
                    row[EventInventoryItems.plannedQuantity] > row[EventInventoryItems.availableQuantity]
            }

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

    private data class MovementTotals(
        val issued: Int = 0,
        val returnedMarked: Int = 0,
        val returned: Int = 0
    )
}
