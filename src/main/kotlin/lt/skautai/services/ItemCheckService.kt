package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.ItemCheckSessions
import lt.skautai.database.tables.ItemChecks
import lt.skautai.database.tables.ItemHistory
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateStorageAuditSessionRequest
import lt.skautai.models.requests.UpsertStorageAuditChecksRequest
import lt.skautai.models.responses.ItemCheckResponse
import lt.skautai.models.responses.ItemCheckSessionListResponse
import lt.skautai.models.responses.ItemCheckSessionResponse
import lt.skautai.models.responses.ItemCheckSummaryResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class ItemCheckService {

    private val validStorageAuditResults = setOf("FOUND", "MISSING", "MISPLACED", "DAMAGED")
    private val validItemConditions = setOf("GOOD", "MISSING", "UNDER_REPAIR", "NEEDS_INSPECTION", "DAMAGED", "WRITTEN_OFF")

    fun createStorageAuditSession(
        tuntasId: UUID,
        userId: UUID,
        request: CreateStorageAuditSessionRequest
    ): Result<ItemCheckSessionResponse> = transaction {
        val scopeCustodianId = request.custodianId?.let { parseUuid(it) }
        if (request.custodianId != null && scopeCustodianId == null) {
            return@transaction Result.failure(Exception("Invalid custodian ID"))
        }
        val personalOwnerUserId = request.personalOwnerUserId?.let { parseUuid(it) }
        if (request.personalOwnerUserId != null && personalOwnerUserId == null) {
            return@transaction Result.failure(Exception("Invalid personal owner user ID"))
        }

        val existing = ItemCheckSessions.selectAll()
            .where {
                (ItemCheckSessions.tuntasId eq tuntasId) and
                    (ItemCheckSessions.contextType eq "STORAGE_AUDIT") and
                    (ItemCheckSessions.status eq "OPEN") and
                    (ItemCheckSessions.startedByUserId eq userId)
            }
            .orderBy(ItemCheckSessions.createdAt to SortOrder.DESC)
            .toList()
            .firstOrNull { session ->
                session[ItemCheckSessions.scopeCustodianId] == scopeCustodianId &&
                    session[ItemCheckSessions.scopeType] == request.type?.trim()?.takeIf { value -> value.isNotBlank() } &&
                    session[ItemCheckSessions.scopeCategory] == request.category?.trim()?.takeIf { value -> value.isNotBlank() } &&
                    session[ItemCheckSessions.scopeSharedOnly] == request.sharedOnly &&
                    session[ItemCheckSessions.scopePersonalOwnerUserId] == personalOwnerUserId
            }
        if (existing != null) {
            return@transaction Result.success(toSessionResponse(existing))
        }

        val now = Clock.System.now()
        val sessionId = ItemCheckSessions.insert {
            it[ItemCheckSessions.tuntasId] = tuntasId
            it[contextType] = "STORAGE_AUDIT"
            it[eventId] = null
            it[ItemCheckSessions.scopeCustodianId] = scopeCustodianId
            it[scopeType] = request.type?.trim()?.takeIf { value -> value.isNotBlank() }
            it[scopeCategory] = request.category?.trim()?.takeIf { value -> value.isNotBlank() }
            it[scopeSharedOnly] = request.sharedOnly
            it[scopePersonalOwnerUserId] = personalOwnerUserId
            it[startedByUserId] = userId
            it[completedByUserId] = null
            it[status] = "OPEN"
            it[scopeItemCount] = countStorageAuditScopeItems(
                tuntasId = tuntasId,
                scopeCustodianId = scopeCustodianId,
                scopeType = request.type,
                scopeCategory = request.category,
                scopeSharedOnly = request.sharedOnly,
                scopePersonalOwnerUserId = personalOwnerUserId
            )
            it[notes] = request.notes?.trim()?.takeIf { value -> value.isNotBlank() }
            it[createdAt] = now
            it[completedAt] = null
        }[ItemCheckSessions.id]

        Result.success(getSessionResponse(sessionId, tuntasId))
    }

    fun listStorageAuditSessions(
        tuntasId: UUID,
        status: String? = null
    ): Result<ItemCheckSessionListResponse> = transaction {
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (normalizedStatus != null && normalizedStatus !in setOf("OPEN", "COMPLETED")) {
            return@transaction Result.failure(Exception("Invalid audit status"))
        }
        val sessions = ItemCheckSessions.selectAll()
            .where {
                (ItemCheckSessions.tuntasId eq tuntasId) and
                    (ItemCheckSessions.contextType eq "STORAGE_AUDIT")
            }
            .orderBy(ItemCheckSessions.createdAt to SortOrder.DESC)
            .toList()
            .filter { session ->
                normalizedStatus == null || session[ItemCheckSessions.status] == normalizedStatus
            }
            .map { session -> toSessionResponse(session) }

        Result.success(
            ItemCheckSessionListResponse(
                sessions = sessions,
                total = sessions.size
            )
        )
    }

    fun getStorageAuditSession(
        sessionId: UUID,
        tuntasId: UUID
    ): Result<ItemCheckSessionResponse> = transaction {
        val session = sessionRow(sessionId, tuntasId, expectedContext = "STORAGE_AUDIT")
            ?: return@transaction Result.failure(Exception("Audit session not found"))
        Result.success(toSessionResponse(session))
    }

    fun upsertStorageAuditChecks(
        sessionId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: UpsertStorageAuditChecksRequest
    ): Result<ItemCheckSessionResponse> = transaction {
        val session = sessionRow(sessionId, tuntasId, expectedContext = "STORAGE_AUDIT")
            ?: return@transaction Result.failure(Exception("Audit session not found"))
        if (session[ItemCheckSessions.status] != "OPEN") {
            return@transaction Result.failure(Exception("Audit session is already completed"))
        }

        val now = Clock.System.now()
        request.checks.forEach { check ->
            val result = check.result.trim().uppercase()
            if (result !in validStorageAuditResults) {
                return@transaction Result.failure(Exception("Invalid audit result"))
            }
            val itemId = parseUuid(check.itemId)
                ?: return@transaction Result.failure(Exception("Invalid item ID"))
            val actualLocationId = check.actualLocationId?.let { parseUuid(it) }
            if (check.actualLocationId != null && actualLocationId == null) {
                return@transaction Result.failure(Exception("Invalid actual location ID"))
            }
            val item = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .firstOrNull() ?: return@transaction Result.failure(Exception("Item not found"))
            if (!itemMatchesStorageAuditScope(item, session)) {
                return@transaction Result.failure(Exception("Item is outside this audit scope"))
            }
            val expectedQuantity = item[Items.quantity]
            val actualQuantity = check.actualQuantity ?: defaultActualQuantity(result, expectedQuantity)
            if (actualQuantity < 0) {
                return@transaction Result.failure(Exception("Actual quantity cannot be negative"))
            }
            val conditionAtCheck = check.conditionAtCheck
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.isNotBlank() }
            if (conditionAtCheck != null && conditionAtCheck !in validItemConditions) {
                return@transaction Result.failure(Exception("Invalid item condition"))
            }
            val resolvedCondition = when {
                conditionAtCheck != null -> conditionAtCheck
                result == "DAMAGED" -> "DAMAGED"
                else -> item[Items.condition]
            }

            val existing = ItemChecks.selectAll()
                .where {
                    (ItemChecks.sessionId eq sessionId) and
                        (ItemChecks.itemId eq itemId)
                }
                .orderBy(ItemChecks.checkedAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()

            if (existing == null) {
                ItemChecks.insert {
                    it[this.sessionId] = sessionId
                    it[this.itemId] = itemId
                    it[eventInventoryItemId] = null
                    it[custodyId] = null
                    it[this.result] = result
                    it[quantity] = expectedQuantity
                    it[this.expectedQuantity] = expectedQuantity
                    it[this.actualQuantity] = actualQuantity
                    it[this.actualLocationId] = actualLocationId
                    it[actualLocationNote] = check.actualLocationNote?.trim()?.takeIf { value -> value.isNotBlank() }
                    it[ItemChecks.conditionAtCheck] = resolvedCondition
                    it[checkedByUserId] = userId
                    it[notes] = check.notes?.trim()?.takeIf { value -> value.isNotBlank() }
                    it[checkedAt] = now
                }
            } else {
                ItemChecks.update({ ItemChecks.id eq existing[ItemChecks.id] }) {
                    it[this.result] = result
                    it[quantity] = expectedQuantity
                    it[this.expectedQuantity] = expectedQuantity
                    it[this.actualQuantity] = actualQuantity
                    it[this.actualLocationId] = actualLocationId
                    it[actualLocationNote] = check.actualLocationNote?.trim()?.takeIf { value -> value.isNotBlank() }
                    it[ItemChecks.conditionAtCheck] = resolvedCondition
                    it[checkedByUserId] = userId
                    it[notes] = check.notes?.trim()?.takeIf { value -> value.isNotBlank() }
                    it[checkedAt] = now
                }
            }
        }

        Result.success(getSessionResponse(sessionId, tuntasId))
    }

    fun completeStorageAuditSession(
        sessionId: UUID,
        tuntasId: UUID,
        userId: UUID
    ): Result<ItemCheckSessionResponse> = transaction {
        val session = sessionRow(sessionId, tuntasId, expectedContext = "STORAGE_AUDIT")
            ?: return@transaction Result.failure(Exception("Audit session not found"))
        if (session[ItemCheckSessions.status] == "COMPLETED") {
            return@transaction Result.success(toSessionResponse(session))
        }
        val checks = ItemChecks.selectAll()
            .where { ItemChecks.sessionId eq sessionId }
            .toList()
        val unchecked = (session[ItemCheckSessions.scopeItemCount] - checks.size).coerceAtLeast(0)
        if (unchecked > 0) {
            return@transaction Result.failure(Exception("Cannot complete audit before every item is checked. Remaining: $unchecked"))
        }
        applyStorageAuditResults(checks)
        recordStorageAuditHistory(sessionId, userId)
        val now = Clock.System.now()
        ItemCheckSessions.update({ ItemCheckSessions.id eq sessionId }) {
            it[status] = "COMPLETED"
            it[completedByUserId] = userId
            it[completedAt] = now
        }
        Result.success(getSessionResponse(sessionId, tuntasId))
    }

    private fun getSessionResponse(sessionId: UUID, tuntasId: UUID): ItemCheckSessionResponse {
        val session = sessionRow(sessionId, tuntasId, expectedContext = "STORAGE_AUDIT")
            ?: error("Audit session not found after write")
        return toSessionResponse(session)
    }

    private fun sessionRow(sessionId: UUID, tuntasId: UUID, expectedContext: String): ResultRow? {
        return ItemCheckSessions.selectAll()
            .where {
                (ItemCheckSessions.id eq sessionId) and
                    (ItemCheckSessions.tuntasId eq tuntasId) and
                    (ItemCheckSessions.contextType eq expectedContext)
            }
            .firstOrNull()
    }

    private fun toSessionResponse(session: ResultRow): ItemCheckSessionResponse {
        val sessionId = session[ItemCheckSessions.id]
        val checks = sessionChecks(sessionId)
        val total = if (session[ItemCheckSessions.contextType] == "STORAGE_AUDIT") {
            session[ItemCheckSessions.scopeItemCount]
        } else {
            checks.size
        }
        return ItemCheckSessionResponse(
            id = sessionId.toString(),
            tuntasId = session[ItemCheckSessions.tuntasId].toString(),
            contextType = session[ItemCheckSessions.contextType],
            status = session[ItemCheckSessions.status],
            eventId = session[ItemCheckSessions.eventId]?.toString(),
            scopeCustodianId = session[ItemCheckSessions.scopeCustodianId]?.toString(),
            scopeCustodianName = organizationalUnitName(session[ItemCheckSessions.scopeCustodianId]),
            scopeType = session[ItemCheckSessions.scopeType],
            scopeCategory = session[ItemCheckSessions.scopeCategory],
            scopeSharedOnly = session[ItemCheckSessions.scopeSharedOnly],
            scopePersonalOwnerUserId = session[ItemCheckSessions.scopePersonalOwnerUserId]?.toString(),
            startedByUserId = session[ItemCheckSessions.startedByUserId].toString(),
            startedByUserName = userDisplayName(session[ItemCheckSessions.startedByUserId]),
            completedByUserId = session[ItemCheckSessions.completedByUserId]?.toString(),
            completedByUserName = userDisplayName(session[ItemCheckSessions.completedByUserId]),
            notes = session[ItemCheckSessions.notes],
            createdAt = session[ItemCheckSessions.createdAt].toString(),
            completedAt = session[ItemCheckSessions.completedAt]?.toString(),
            summary = toSummary(total, checks),
            checks = checks
        )
    }

    private fun sessionChecks(sessionId: UUID): List<ItemCheckResponse> {
        val rows = ItemChecks.selectAll()
            .where { ItemChecks.sessionId eq sessionId }
            .orderBy(ItemChecks.checkedAt to SortOrder.DESC)
            .toList()
        return rows.map { row ->
            val item = row[ItemChecks.itemId]?.let { itemId ->
                Items.selectAll().where { Items.id eq itemId }.firstOrNull()
            }
            ItemCheckResponse(
                id = row[ItemChecks.id].toString(),
                sessionId = row[ItemChecks.sessionId].toString(),
                itemId = row[ItemChecks.itemId]?.toString(),
                eventInventoryItemId = row[ItemChecks.eventInventoryItemId]?.toString(),
                custodyId = row[ItemChecks.custodyId]?.toString(),
                itemName = item?.get(Items.name),
                qrToken = item?.get(Items.qrToken),
                result = row[ItemChecks.result],
                quantity = row[ItemChecks.quantity],
                expectedQuantity = row[ItemChecks.expectedQuantity],
                actualQuantity = row[ItemChecks.actualQuantity],
                quantityDifference = row[ItemChecks.actualQuantity] - row[ItemChecks.expectedQuantity],
                quantityChangeDirection = quantityChangeDirection(
                    expectedQuantity = row[ItemChecks.expectedQuantity],
                    actualQuantity = row[ItemChecks.actualQuantity]
                ),
                actualLocationId = row[ItemChecks.actualLocationId]?.toString(),
                actualLocationPath = buildLocationPath(row[ItemChecks.actualLocationId], item?.get(Items.tuntasId)),
                actualLocationNote = row[ItemChecks.actualLocationNote],
                conditionAtCheck = row[ItemChecks.conditionAtCheck],
                checkedByUserId = row[ItemChecks.checkedByUserId].toString(),
                checkedByUserName = userDisplayName(row[ItemChecks.checkedByUserId]),
                checkedAt = row[ItemChecks.checkedAt].toString(),
                notes = row[ItemChecks.notes]
            )
        }
    }

    private fun toSummary(total: Int, checks: List<ItemCheckResponse>): ItemCheckSummaryResponse {
        val found = checks.count { it.result == "FOUND" }
        val missing = checks.count { it.result == "MISSING" }
        val misplaced = checks.count { it.result == "MISPLACED" }
        val damaged = checks.count { it.result == "DAMAGED" }
        val consumed = checks.count { it.result == "CONSUMED" }
        val returned = checks.count { it.result == "RETURNED" }
        val matched = checks.count { it.quantityDifference == 0 }
        val decreased = checks.count { it.quantityDifference < 0 }
        val increased = checks.count { it.quantityDifference > 0 }
        val checked = checks.size
        return ItemCheckSummaryResponse(
            total = total,
            checked = checked,
            unchecked = (total - checked).coerceAtLeast(0),
            found = found,
            missing = missing,
            misplaced = misplaced,
            damaged = damaged,
            consumed = consumed,
            returned = returned,
            matched = matched,
            decreased = decreased,
            increased = increased,
            expectedQuantityTotal = checks.sumOf { it.expectedQuantity },
            actualQuantityTotal = checks.sumOf { it.actualQuantity },
            shortageQuantityTotal = checks.sumOf { (it.expectedQuantity - it.actualQuantity).coerceAtLeast(0) },
            overageQuantityTotal = checks.sumOf { (it.actualQuantity - it.expectedQuantity).coerceAtLeast(0) }
        )
    }

    private fun countStorageAuditScopeItems(
        tuntasId: UUID,
        scopeCustodianId: UUID?,
        scopeType: String?,
        scopeCategory: String?,
        scopeSharedOnly: Boolean,
        scopePersonalOwnerUserId: UUID?
    ): Int {
        return Items.selectAll().where {
            (Items.tuntasId eq tuntasId) and
                (Items.status eq "ACTIVE")
        }
            .toList()
            .count { row ->
                val custodianMatches = scopeCustodianId?.let { row[Items.custodianId] == it } ?: true
                val typeMatches = scopeType?.trim()?.takeIf { it.isNotBlank() }?.let { row[Items.type] == it } ?: true
                val categoryMatches = scopeCategory?.trim()?.takeIf { it.isNotBlank() }?.let { row[Items.category] == it } ?: true
                val sharedOnlyMatches = !scopeSharedOnly || row[Items.custodianId] == null
                val ownerMatches = scopePersonalOwnerUserId?.let { row[Items.createdByUserId] == it } ?: true
                custodianMatches && typeMatches && categoryMatches && sharedOnlyMatches && ownerMatches
            }
    }

    private fun itemMatchesStorageAuditScope(item: ResultRow, session: ResultRow): Boolean {
        val custodianMatches = session[ItemCheckSessions.scopeCustodianId]?.let { item[Items.custodianId] == it } ?: true
        val typeMatches = session[ItemCheckSessions.scopeType]?.let { item[Items.type] == it } ?: true
        val categoryMatches = session[ItemCheckSessions.scopeCategory]?.let { item[Items.category] == it } ?: true
        val sharedOnlyMatches = !session[ItemCheckSessions.scopeSharedOnly] || item[Items.custodianId] == null
        val ownerMatches = session[ItemCheckSessions.scopePersonalOwnerUserId]?.let { item[Items.createdByUserId] == it } ?: true
        return item[Items.status] == "ACTIVE" &&
            custodianMatches &&
            typeMatches &&
            categoryMatches &&
            sharedOnlyMatches &&
            ownerMatches
    }

    private fun defaultActualQuantity(result: String, expectedQuantity: Int): Int = when (result) {
        "MISSING" -> 0
        else -> expectedQuantity
    }

    private fun quantityChangeDirection(expectedQuantity: Int, actualQuantity: Int): String = when {
        actualQuantity < expectedQuantity -> "DECREASED"
        actualQuantity > expectedQuantity -> "INCREASED"
        else -> "MATCHED"
    }

    private fun recordStorageAuditHistory(sessionId: UUID, userId: UUID) {
        ItemChecks.selectAll()
            .where { ItemChecks.sessionId eq sessionId }
            .toList()
            .forEach { row ->
                val itemId = row[ItemChecks.itemId] ?: return@forEach
                val expectedQuantity = row[ItemChecks.expectedQuantity]
                val actualQuantity = row[ItemChecks.actualQuantity]
                val quantityDifference = actualQuantity - expectedQuantity
                val result = row[ItemChecks.result]
                val eventType = when {
                    result == "MISSING" -> "AUDIT_MISSING"
                    quantityDifference < 0 -> "AUDIT_SHORTAGE"
                    quantityDifference > 0 -> "AUDIT_OVERAGE"
                    result == "MISPLACED" -> "AUDIT_MISPLACED"
                    result == "DAMAGED" -> "AUDIT_DAMAGED"
                    else -> "AUDIT_MATCHED"
                }
                val notes = buildList {
                    add("Inventorizacija: tiketasi $expectedQuantity, rasta $actualQuantity")
                    row[ItemChecks.actualLocationNote]?.takeIf { it.isNotBlank() }?.let { add("Vieta: $it") }
                    row[ItemChecks.notes]?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(". ")

                ItemHistory.insert {
                    it[this.itemId] = itemId
                    it[this.eventType] = eventType
                    it[this.quantityChange] = quantityDifference.takeIf { it != 0 }
                    it[this.performedByUserId] = userId
                    it[this.requisitionId] = null
                    it[this.notes] = notes
                    it[this.createdAt] = row[ItemChecks.checkedAt]
                }
            }
    }

    private fun applyStorageAuditResults(checks: List<ResultRow>) {
        val now = Clock.System.now()
        checks.forEach { row ->
            val itemId = row[ItemChecks.itemId] ?: return@forEach
            val currentItem = Items.selectAll().where { Items.id eq itemId }.firstOrNull()
                ?: return@forEach
            Items.update({ Items.id eq itemId }) {
                it[quantity] = row[ItemChecks.actualQuantity]
                it[condition] = row[ItemChecks.conditionAtCheck] ?: currentItem[Items.condition]
                it[updatedAt] = now
            }
        }
    }

    private fun userDisplayName(userId: UUID?): String? {
        if (userId == null) return null
        return Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
    }

    private fun organizationalUnitName(organizationalUnitId: UUID?): String? {
        if (organizationalUnitId == null) return null
        return OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq organizationalUnitId }
            .firstOrNull()
            ?.get(OrganizationalUnits.name)
    }

    private fun buildLocationPath(locationId: UUID?, tuntasId: UUID?): String? {
        if (locationId == null || tuntasId == null) return null
        val rows = Locations.selectAll()
            .where { Locations.tuntasId eq tuntasId }
            .toList()
        val byId = rows.associateBy { it[Locations.id] }
        val parts = mutableListOf<String>()
        var current = locationId
        while (current != null) {
            val row = byId[current] ?: break
            parts += row[Locations.name]
            current = row[Locations.parentLocationId]
        }
        return parts.asReversed().joinToString(" / ").takeIf { it.isNotBlank() }
    }

    private fun parseUuid(value: String): UUID? {
        return try {
            UUID.fromString(value)
        } catch (_: Exception) {
            null
        }
    }
}
