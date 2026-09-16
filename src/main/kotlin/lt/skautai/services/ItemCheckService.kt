package lt.skautai.services

import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import lt.skautai.database.tables.*
import lt.skautai.models.requests.*
import lt.skautai.models.responses.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class ItemCheckService {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val results = setOf("FOUND", "MISSING", "MISPLACED", "DAMAGED")
    private val conditions = setOf("GOOD", "MISSING", "UNDER_REPAIR", "NEEDS_INSPECTION", "DAMAGED", "WRITTEN_OFF")

    fun createStorageAuditSession(tuntasId: UUID, userId: UUID, request: CreateStorageAuditSessionRequest): Result<ItemCheckSessionResponse> = atomicResultTransaction {
        if (!PermissionContextService.resolve(userId, tuntasId).has("items.update")) return@atomicResultTransaction rejected("Neturite teisės inventorizuoti.")
        if (listOf(request.custodianId, request.personalOwnerUserId, request.locationId).any { it != null && runCatching { UUID.fromString(it) }.isFailure })
            return@atomicResultTransaction rejected("Neteisingas identifikatorius.")
        val custodian = uuid(request.custodianId)
        val owner = uuid(request.personalOwnerUserId)
        val location = uuid(request.locationId)
        if (request.title.orEmpty().length > 160) return@atomicResultTransaction rejected("Pavadinimas per ilgas.")
        if (custodian != null && OrganizationalUnits.selectAll().where { (OrganizationalUnits.id eq custodian) and (OrganizationalUnits.tuntasId eq tuntasId) }.empty())
            return@atomicResultTransaction rejected("Vienetas nerastas.")
        val locations = Locations.selectAll().where { Locations.tuntasId eq tuntasId }.toList()
        if (location != null && locations.none { it[Locations.id] == location }) return@atomicResultTransaction rejected("Vieta nerasta.")
        val locationIds = mutableSetOf<UUID>()
        if (location != null) {
            locationIds += location
            do {
                val before = locationIds.size
                locations.filter { it[Locations.parentLocationId] in locationIds }.forEach { locationIds += it[Locations.id] }
            } while (before != locationIds.size)
        }
        Tuntai.selectAll().where { Tuntai.id eq tuntasId }.forUpdate().first()
        val existing = ItemCheckSessions.selectAll().where {
            (ItemCheckSessions.tuntasId eq tuntasId) and (ItemCheckSessions.contextType eq "STORAGE_AUDIT") and
                (ItemCheckSessions.status eq "OPEN") and (ItemCheckSessions.startedByUserId eq userId)
        }.firstOrNull {
            it[ItemCheckSessions.snapshotJson] != null && it[ItemCheckSessions.scopeCustodianId] == custodian &&
                it[ItemCheckSessions.scopeType] == request.type.clean() && it[ItemCheckSessions.scopeCategory] == request.category.clean() &&
                it[ItemCheckSessions.scopeSharedOnly] == request.sharedOnly && it[ItemCheckSessions.scopePersonalOwnerUserId] == owner &&
                it[ItemCheckSessions.scopeLocationId] == location && it[ItemCheckSessions.title] == request.title.clean()
        }
        if (existing != null && canAccess(existing, userId)) return@atomicResultTransaction Result.success(response(existing, userId))
        val candidates = ItemService().getItems(tuntasId, userId, request.custodianId, request.type, request.category,
            status = "ACTIVE", sharedOnly = request.sharedOnly, createdByUserId = request.personalOwnerUserId).getOrThrow().items
            .filter { it.capabilities?.canEdit == true && (location == null || uuid(it.locationId) in locationIds) }
        if (candidates.isEmpty()) return@atomicResultTransaction rejected("Pasirinktoje srityje nėra inventoriaus, kurį galite tvarkyti.")
        val locked = Items.selectAll().where { Items.id inList candidates.map { UUID.fromString(it.id) } }.orderBy(Items.id).forUpdate().toList()
        val entries = locked.mapNotNull { row ->
            val fresh = ItemService().getItem(row[Items.id], tuntasId, userId).getOrNull()
            if (fresh?.capabilities?.canEdit != true || fresh.status != "ACTIVE" ||
                (custodian != null && fresh.custodianId != custodian.toString()) ||
                (request.sharedOnly && fresh.custodianId != null) ||
                (location != null && uuid(fresh.locationId) !in locationIds) ||
                (request.type.clean() != null && fresh.type != request.type.clean()) ||
                (request.category.clean() != null && fresh.category != request.category.clean())) null else snapshot(fresh, row)
        }
        if (entries.isEmpty()) return@atomicResultTransaction rejected("Inventoriaus sąrašas pasikeitė. Bandykite dar kartą.")
        val id = ItemCheckSessions.insert {
            it[ItemCheckSessions.tuntasId] = tuntasId; it[contextType] = "STORAGE_AUDIT"
            it[scopeCustodianId] = custodian; it[scopeType] = request.type.clean(); it[scopeCategory] = request.category.clean()
            it[scopeSharedOnly] = request.sharedOnly; it[scopePersonalOwnerUserId] = owner; it[scopeLocationId] = location
            it[title] = request.title.clean(); it[startedByUserId] = userId; it[status] = "OPEN"; it[scopeItemCount] = entries.size
            it[snapshotJson] = json.encodeToString(entries); it[notes] = request.notes.clean(); it[createdAt] = Clock.System.now()
        }[ItemCheckSessions.id]
        Result.success(response(session(id, tuntasId)!!, userId))
    }

    fun listStorageAuditSessions(tuntasId: UUID, userId: UUID, status: String? = null): Result<ItemCheckSessionListResponse> = transaction {
        val filter = status.clean()?.uppercase()
        if (filter != null && filter !in setOf("OPEN", "COMPLETED", "CANCELLED")) return@transaction rejected("Nežinoma sesijos būsena.")
        val rows = ItemCheckSessions.selectAll().where {
            (ItemCheckSessions.tuntasId eq tuntasId) and (ItemCheckSessions.contextType eq "STORAGE_AUDIT")
        }.orderBy(ItemCheckSessions.createdAt to SortOrder.DESC).filter {
            (filter == null || it[ItemCheckSessions.status] == filter) && canAccess(it, userId)
        }
        Result.success(ItemCheckSessionListResponse(rows.map { response(it, userId, includeItems = false) }, rows.size))
    }

    fun getStorageAuditSession(sessionId: UUID, tuntasId: UUID, userId: UUID): Result<ItemCheckSessionResponse> = transaction {
        val row = session(sessionId, tuntasId)
        if (row == null || !canAccess(row, userId)) return@transaction rejected("Inventorizacija nerasta arba neturite prieigos.")
        Result.success(response(row, userId))
    }

    fun upsertStorageAuditChecks(sessionId: UUID, tuntasId: UUID, userId: UUID, request: UpsertStorageAuditChecksRequest): Result<ItemCheckSessionResponse> = atomicResultTransaction {
        val row = writableSession(sessionId, tuntasId, userId) ?: return@atomicResultTransaction rejected("Inventorizacija nepasiekiama.")
        if (request.mutationId != null && runCatching { UUID.fromString(request.mutationId) }.isFailure)
            return@atomicResultTransaction rejected("Neteisingas operacijos identifikatorius.")
        val mutation = uuid(request.mutationId)
        if (mutation != null && mutation == row[ItemCheckSessions.lastMutationId]) return@atomicResultTransaction Result.success(response(row, userId))
        revisionError(row, request.expectedRevision)?.let { return@atomicResultTransaction rejected(it) }
        if (request.checks.size + request.removeItemIds.size > 1000) return@atomicResultTransaction rejected("Per daug patikrų vienoje užklausoje.")
        val entries = snapshots(row).associateBy { it.item.id }
        val allIds = request.checks.map { it.itemId } + request.removeItemIds
        if (allIds.distinct().size != allIds.size) return@atomicResultTransaction rejected("Daiktas užklausoje kartojasi.")
        if (allIds.any { it !in entries }) return@atomicResultTransaction rejected("Daiktas nepatenka į šią inventorizaciją.")
        val now = Clock.System.now()
        request.checks.forEach { check ->
            val entry = entries.getValue(check.itemId)
            val current = currentEditable(entry.item.id, tuntasId, userId) ?: return@atomicResultTransaction rejected("Nebegalite tvarkyti daikto: ${entry.item.name}.")
            if (current[Items.auditVersion] != entry.stockVersion) return@atomicResultTransaction rejected("Inventorius pasikeitė: ${entry.item.name}. Pasirinkite pakartotinę patikrą.")
            val result = check.result.trim().uppercase()
            val actual = check.actualQuantity ?: if (result == "MISSING") 0 else entry.expectedStorageQuantity
            val condition = check.conditionAtCheck.clean()?.uppercase() ?: if (result == "DAMAGED") "DAMAGED" else entry.item.condition
            if (result !in results || condition !in conditions || actual < 0) return@atomicResultTransaction rejected("Neteisingas patikros rezultatas.")
            if (result == "MISSING" && actual != 0) return@atomicResultTransaction rejected("Nerasto daikto faktinis kiekis turi būti 0.")
            if (result == "DAMAGED" && condition != "DAMAGED") return@atomicResultTransaction rejected("Sugadinto daikto būklė nesutampa.")
            if (actual.toLong() + entry.outstandingQuantity > Int.MAX_VALUE) return@atomicResultTransaction rejected("Kiekis per didelis.")
            if (check.actualLocationNote.orEmpty().length > 255) return@atomicResultTransaction rejected("Vietos pastaba per ilga.")
            if (result == "MISPLACED" && check.actualLocationNote.clean() == null && check.actualLocationId == null) return@atomicResultTransaction rejected("Nurodykite, kur radote daiktą.")
            if (check.actualLocationId != null && runCatching { UUID.fromString(check.actualLocationId) }.isFailure)
                return@atomicResultTransaction rejected("Neteisingas vietos identifikatorius.")
            val actualLocation = uuid(check.actualLocationId)
            if (actualLocation != null && !validLocation(actualLocation, current, userId)) return@atomicResultTransaction rejected("Ši vieta daiktui neprieinama.")
            val existing = ItemChecks.selectAll().where { (ItemChecks.sessionId eq sessionId) and (ItemChecks.itemId eq UUID.fromString(check.itemId)) }.firstOrNull()
            fun fill(stmt: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>) {
                stmt[ItemChecks.result] = result; stmt[ItemChecks.quantity] = entry.expectedStorageQuantity
                stmt[ItemChecks.expectedQuantity] = entry.expectedStorageQuantity; stmt[ItemChecks.actualQuantity] = actual
                stmt[ItemChecks.conditionAtCheck] = condition; stmt[ItemChecks.actualLocationId] = actualLocation
                stmt[ItemChecks.actualLocationNote] = check.actualLocationNote.clean(); stmt[ItemChecks.notes] = check.notes.clean()
                stmt[ItemChecks.checkedByUserId] = userId; stmt[ItemChecks.checkedAt] = now
            }
            if (existing == null) ItemChecks.insert { it[ItemChecks.sessionId] = sessionId; it[itemId] = UUID.fromString(check.itemId); fill(it) }
            else ItemChecks.update({ ItemChecks.id eq existing[ItemChecks.id] }) { fill(it) }
        }
        request.removeItemIds.forEach { id -> ItemChecks.deleteWhere { (ItemChecks.sessionId eq sessionId) and (itemId eq UUID.fromString(id)) } }
        bump(row, mutation)
        Result.success(response(session(sessionId, tuntasId)!!, userId))
    }

    fun completeStorageAuditSession(sessionId: UUID, tuntasId: UUID, userId: UUID, expectedRevision: Int? = null): Result<ItemCheckSessionResponse> = atomicResultTransaction {
        val row = session(sessionId, tuntasId, lock = true)
        if (row == null || !canAccess(row, userId)) return@atomicResultTransaction rejected("Inventorizacija nepasiekiama.")
        if (row[ItemCheckSessions.status] == "COMPLETED") return@atomicResultTransaction Result.success(response(row, userId))
        if (row[ItemCheckSessions.status] != "OPEN" || row[ItemCheckSessions.snapshotJson] == null) return@atomicResultTransaction rejected("Šios sesijos užbaigti negalima. Pradėkite naują inventorizaciją.")
        revisionError(row, expectedRevision)?.let { return@atomicResultTransaction rejected(it) }
        val entries = snapshots(row)
        val checks = ItemChecks.selectAll().where { ItemChecks.sessionId eq sessionId }.associateBy { it[ItemChecks.itemId]?.toString() }
        if (entries.any { it.item.id !in checks }) return@atomicResultTransaction rejected("Pirmiausia patikrinkite visus sesijos daiktus.")
        val locked = Items.selectAll().where { Items.id inList entries.map { UUID.fromString(it.item.id) } }.orderBy(Items.id).forUpdate().associateBy { it[Items.id].toString() }
        entries.forEach { entry ->
            val item = locked[entry.item.id]
            if (item == null || currentEditable(entry.item.id, tuntasId, userId) == null) return@atomicResultTransaction rejected("Daiktas nebeprieinamas: ${entry.item.name}.")
            if (item[Items.auditVersion] != entry.stockVersion) return@atomicResultTransaction rejected("Inventorius pasikeitė: ${entry.item.name}. Reikia pakartotinės patikros.")
            if (entry.outstandingQuantity > item[Items.quantity]) return@atomicResultTransaction rejected("Išduotas kiekis viršija apskaitinį: ${entry.item.name}.")
            val check = checks.getValue(entry.item.id)
            val discrepancy = check[ItemChecks.actualQuantity] != entry.expectedStorageQuantity || check[ItemChecks.conditionAtCheck] != entry.item.condition || check[ItemChecks.result] == "MISPLACED"
            if (discrepancy && check[ItemChecks.notes].clean() == null) return@atomicResultTransaction rejected("Paaiškinkite neatitikimą: ${entry.item.name}.")
        }
        val now = Clock.System.now()
        entries.forEach { entry ->
            val item = locked.getValue(entry.item.id)
            val check = checks.getValue(entry.item.id)
            val nextQuantity = check[ItemChecks.actualQuantity] + entry.outstandingQuantity
            val nextCondition = check[ItemChecks.conditionAtCheck] ?: item[Items.condition]
            Items.update({ Items.id eq item[Items.id] }) { it[quantity] = nextQuantity; it[condition] = nextCondition; it[updatedAt] = now }
            InventoryKitService.syncMembershipAfterItemQuantityChange(item[Items.id], nextQuantity)
            if (nextCondition != item[Items.condition]) ItemConditionLog.insert {
                it[itemId] = item[Items.id]; it[previousCondition] = item[Items.condition]; it[newCondition] = nextCondition
                it[reportedByUserId] = userId; it[reportedAt] = now; it[notes] = "Inventorizacija ${sessionId}: ${check[ItemChecks.notes].orEmpty()}"
            }
            ItemHistory.insert {
                it[itemId] = item[Items.id]
                it[eventType] = when {
                    nextQuantity < item[Items.quantity] -> "AUDIT_SHORTAGE"
                    nextQuantity > item[Items.quantity] -> "AUDIT_OVERAGE"
                    check[ItemChecks.result] == "DAMAGED" -> "AUDIT_DAMAGED"
                    check[ItemChecks.result] == "MISPLACED" -> "AUDIT_MISPLACED"
                    else -> "AUDIT_MATCHED"
                }
                it[quantityChange] = (nextQuantity - item[Items.quantity]).takeIf { delta -> delta != 0 }; it[performedByUserId] = userId
                it[notes] = "Inventorizacija ${sessionId}: buvo ${item[Items.quantity]}, sandėlyje ${check[ItemChecks.actualQuantity]}, išduota ${entry.outstandingQuantity}, po korekcijos $nextQuantity. ${check[ItemChecks.actualLocationNote].orEmpty()} ${check[ItemChecks.notes].orEmpty()}"
                it[createdAt] = now
            }
        }
        ItemCheckSessions.update({ ItemCheckSessions.id eq sessionId }) {
            it[status] = "COMPLETED"; it[completedByUserId] = userId; it[completedAt] = now; it[revision] = row[ItemCheckSessions.revision] + 1
        }
        Result.success(response(session(sessionId, tuntasId)!!, userId))
    }

    fun cancelStorageAuditSession(sessionId: UUID, tuntasId: UUID, userId: UUID, expectedRevision: Int?): Result<ItemCheckSessionResponse> = atomicResultTransaction {
        val row = session(sessionId, tuntasId, lock = true)
        if (row == null || !canAccess(row, userId)) return@atomicResultTransaction rejected("Inventorizacija nepasiekiama.")
        if (row[ItemCheckSessions.status] != "OPEN") return@atomicResultTransaction rejected("Atšaukti galima tik atvirą sesiją.")
        revisionError(row, expectedRevision)?.let { return@atomicResultTransaction rejected(it) }
        ItemCheckSessions.update({ ItemCheckSessions.id eq sessionId }) {
            it[status] = "CANCELLED"; it[completedByUserId] = userId; it[completedAt] = Clock.System.now(); it[revision] = row[ItemCheckSessions.revision] + 1
        }
        Result.success(response(session(sessionId, tuntasId)!!, userId))
    }

    fun refreshStorageAuditItem(sessionId: UUID, tuntasId: UUID, userId: UUID, itemId: UUID, expectedRevision: Int?): Result<ItemCheckSessionResponse> = atomicResultTransaction {
        val row = writableSession(sessionId, tuntasId, userId) ?: return@atomicResultTransaction rejected("Inventorizacija nepasiekiama.")
        revisionError(row, expectedRevision)?.let { return@atomicResultTransaction rejected(it) }
        val entries = snapshots(row)
        val previous = entries.firstOrNull { it.item.id == itemId.toString() } ?: return@atomicResultTransaction rejected("Daiktas nėra sesijoje.")
        val item = Items.selectAll().where { Items.id eq itemId }.forUpdate().firstOrNull() ?: return@atomicResultTransaction rejected("Daiktas pašalintas. Atšaukite sesiją.")
        val fresh = ItemService().getItem(itemId, tuntasId, userId).getOrNull()
        if (fresh?.capabilities?.canEdit != true || fresh.status != "ACTIVE") return@atomicResultTransaction rejected("Daikto nebegalite tvarkyti. Atšaukite sesiją.")
        val next = snapshot(fresh, item)
        val previousCheck = ItemChecks.selectAll().where { (ItemChecks.sessionId eq sessionId) and (ItemChecks.itemId eq itemId) }.firstOrNull()
        val previousDetail = previousCheck?.let { " Ankstesnė patikra: ${it[ItemChecks.result]}, kiekis ${it[ItemChecks.actualQuantity]}, būklė ${it[ItemChecks.conditionAtCheck]}, vieta ${it[ItemChecks.actualLocationNote]}, pastabos ${it[ItemChecks.notes]}, tikrino ${it[ItemChecks.checkedByUserId]}, laikas ${it[ItemChecks.checkedAt]}." }.orEmpty()
        ItemHistory.insert {
            it[ItemHistory.itemId] = itemId; it[eventType] = "AUDIT_RECOUNT"; it[performedByUserId] = userId; it[createdAt] = Clock.System.now()
            it[notes] = "Pakartotinė patikra ${sessionId}: ankstesnis laukiamas sandėlio kiekis ${previous.expectedStorageQuantity}, naujas ${next.expectedStorageQuantity}.$previousDetail"
        }
        ItemChecks.deleteWhere { (ItemChecks.sessionId eq sessionId) and (ItemChecks.itemId eq itemId) }
        ItemCheckSessions.update({ ItemCheckSessions.id eq sessionId }) {
            it[snapshotJson] = json.encodeToString(entries.map { old -> if (old.item.id == fresh.id) next else old })
            it[revision] = row[ItemCheckSessions.revision] + 1; it[lastMutationId] = null
        }
        Result.success(response(session(sessionId, tuntasId)!!, userId))
    }

    private fun snapshot(item: ItemResponse, row: ResultRow): StorageAuditItemResponse {
        val id = UUID.fromString(item.id)
        val details = mutableListOf<String>()
        var outstanding = 0L
        DirectItemLoans.selectAll().where { (DirectItemLoans.itemId eq id) and (DirectItemLoans.status eq "ACTIVE") }.forEach {
            val count = (it[DirectItemLoans.quantity] - it[DirectItemLoans.returnedQuantity]).coerceAtLeast(0)
            outstanding += count
            if (count > 0) details += "${userName(it[DirectItemLoans.issuedToUserId]).orEmpty()}: $count ${item.unitOfMeasure}"
        }
        ReservationMovements.selectAll().where { ReservationMovements.itemId eq id }.groupBy { it[ReservationMovements.reservationGroupId] }.forEach { (group, moves) ->
            val count = moves.sumOf { when(it[ReservationMovements.type]) { "ISSUE" -> it[ReservationMovements.quantity].toLong(); "RETURN" -> -it[ReservationMovements.quantity].toLong(); else -> 0L } }.coerceAtLeast(0)
            outstanding += count
            if (count > 0) {
                val reservation = Reservations.selectAll().where { Reservations.groupId eq group }.firstOrNull()
                details += "${reservation?.get(Reservations.title) ?: "Rezervacija"}: $count ${item.unitOfMeasure}"
            }
        }
        // Root custody already contains quantities handed on to sub-camp members.
        // Reservation-linked event stock is counted by the reservation ledger above.
        EventInventoryItems.selectAll().where { (EventInventoryItems.itemId eq id) and EventInventoryItems.reservationGroupId.isNull() }.forEach { eventItem ->
            val count = EventInventoryCustody.selectAll().where {
                (EventInventoryCustody.eventInventoryItemId eq eventItem[EventInventoryItems.id]) and
                    EventInventoryCustody.parentCustodyId.isNull() and (EventInventoryCustody.status eq "OPEN")
            }.sumOf { (it[EventInventoryCustody.quantity].toLong() - it[EventInventoryCustody.returnedQuantity]).coerceAtLeast(0) }
            outstanding += count
            if (count > 0) details += "Renginyje: $count ${item.unitOfMeasure}"
        }
        val safeOutstanding = outstanding.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return StorageAuditItemResponse(item, (item.quantity - safeOutstanding).coerceAtLeast(0), safeOutstanding, details, row[Items.auditVersion])
    }

    private fun currentEditable(id: String, tuntasId: UUID, userId: UUID): ResultRow? {
        val item = ItemService().getItem(UUID.fromString(id), tuntasId, userId).getOrNull()
        if (item?.capabilities?.canEdit != true || item.status != "ACTIVE") return null
        return Items.selectAll().where { Items.id eq UUID.fromString(id) }.firstOrNull()
    }

    private fun canAccess(row: ResultRow, userId: UUID): Boolean {
        val permission = PermissionContextService.resolve(userId, row[ItemCheckSessions.tuntasId])
        if (!permission.has("items.update")) return false
        val protected = SeniorUnitPrivacyService.protectedUnitIdsFor(userId, row[ItemCheckSessions.tuntasId])
        val entries = snapshots(row)
        if (entries.isEmpty()) {
            val scope = row[ItemCheckSessions.scopeCustodianId]
            return permission.targetAllowed("items.update", scope) && scope !in protected
        }
        return entries.all {
            val custodian = uuid(it.item.custodianId)
            permission.targetAllowed("items.update", custodian) &&
                (it.item.origin !in setOf("TRANSFERRED_FROM_TUNTAS", "from_shared") || permission.hasAll("items.update")) &&
                (custodian !in protected || it.item.origin == "TRANSFERRED_FROM_TUNTAS")
        }
    }

    private fun writableSession(id: UUID, tuntasId: UUID, userId: UUID): ResultRow? = session(id, tuntasId, true)?.takeIf {
        canAccess(it, userId) && it[ItemCheckSessions.status] == "OPEN" && it[ItemCheckSessions.snapshotJson] != null
    }
    private fun session(id: UUID, tuntasId: UUID, lock: Boolean = false): ResultRow? {
        val query = ItemCheckSessions.selectAll().where { (ItemCheckSessions.id eq id) and (ItemCheckSessions.tuntasId eq tuntasId) and (ItemCheckSessions.contextType eq "STORAGE_AUDIT") }
        val row = (if (lock) query.forUpdate() else query).firstOrNull()
        if (lock && row != null) org.jetbrains.exposed.sql.transactions.TransactionManager.current().exec("SELECT set_config('skautai.audit_session', '$id', true)")
        return row
    }
    private fun snapshots(row: ResultRow): List<StorageAuditItemResponse> = row[ItemCheckSessions.snapshotJson]?.let { json.decodeFromString<List<StorageAuditItemResponse>>(it) }.orEmpty()
    private fun revisionError(row: ResultRow, expected: Int?): String? = if (expected != row[ItemCheckSessions.revision]) "Sesija pasikeitė kitame įrenginyje. Atnaujinkite ir peržiūrėkite rezultatus." else null
    private fun bump(row: ResultRow, mutation: UUID?) = ItemCheckSessions.update({ ItemCheckSessions.id eq row[ItemCheckSessions.id] }) { it[revision] = row[ItemCheckSessions.revision] + 1; it[lastMutationId] = mutation }

    private fun response(row: ResultRow, userId: UUID, includeItems: Boolean = true): ItemCheckSessionResponse {
        val entries = snapshots(row)
        val byId = entries.associateBy { it.item.id }
        val checks = ItemChecks.selectAll().where { ItemChecks.sessionId eq row[ItemCheckSessions.id] }.orderBy(ItemChecks.checkedAt to SortOrder.DESC).map { check ->
            val entry = byId[check[ItemChecks.itemId]?.toString()]
            val item = entry?.item ?: check[ItemChecks.itemId]?.let { ItemService().getItem(it, row[ItemCheckSessions.tuntasId], userId).getOrNull() }
            val difference = check[ItemChecks.actualQuantity] - check[ItemChecks.expectedQuantity]
            ItemCheckResponse(id = check[ItemChecks.id].toString(), sessionId = row[ItemCheckSessions.id].toString(), itemId = check[ItemChecks.itemId]?.toString(),
                itemName = item?.name, qrToken = item?.qrToken, result = check[ItemChecks.result], quantity = check[ItemChecks.quantity],
                expectedQuantity = check[ItemChecks.expectedQuantity], actualQuantity = check[ItemChecks.actualQuantity], quantityDifference = difference,
                quantityChangeDirection = if(difference < 0) "DECREASED" else if(difference > 0) "INCREASED" else "MATCHED",
                actualLocationId = check[ItemChecks.actualLocationId]?.toString(), actualLocationPath = check[ItemChecks.actualLocationId]?.let { LocationService().getLocation(it, row[ItemCheckSessions.tuntasId], userId).getOrNull()?.fullPath }, actualLocationNote = check[ItemChecks.actualLocationNote],
                conditionAtCheck = check[ItemChecks.conditionAtCheck], checkedByUserId = check[ItemChecks.checkedByUserId].toString(),
                checkedByUserName = userName(check[ItemChecks.checkedByUserId]), checkedAt = check[ItemChecks.checkedAt].toString(), notes = check[ItemChecks.notes],
                unitOfMeasure = item?.unitOfMeasure ?: "vnt.", outstandingQuantity = entry?.outstandingQuantity ?: 0,
                resultingTotalQuantity = check[ItemChecks.actualQuantity] + (entry?.outstandingQuantity ?: 0))
        }
        val total = if (entries.isNotEmpty()) entries.size else row[ItemCheckSessions.scopeItemCount]
        val open = row[ItemCheckSessions.status] == "OPEN"
        val conflicts = if (includeItems && open) entries.map { entry ->
            val current = currentEditable(entry.item.id, row[ItemCheckSessions.tuntasId], userId)
            entry.copy(conflict = when {
                current == null -> "Daiktas nebeprieinamas. Atšaukite sesiją."
                current[Items.auditVersion] != entry.stockVersion -> "Inventorius pasikeitė. Reikia pakartotinės patikros."
                entry.outstandingQuantity > entry.item.quantity -> "Išduotas kiekis viršija apskaitinį."
                else -> null
            })
        } else entries
        return ItemCheckSessionResponse(title = row[ItemCheckSessions.title], scopeLocationId = row[ItemCheckSessions.scopeLocationId]?.toString(),
            revision = row[ItemCheckSessions.revision], requiresRestart = open && row[ItemCheckSessions.snapshotJson] == null,
            canEdit = open && row[ItemCheckSessions.snapshotJson] != null, canCancel = open, items = if(includeItems) conflicts else emptyList(),
            id = row[ItemCheckSessions.id].toString(), tuntasId = row[ItemCheckSessions.tuntasId].toString(),
            contextType = row[ItemCheckSessions.contextType], status = row[ItemCheckSessions.status],
            scopeCustodianId = row[ItemCheckSessions.scopeCustodianId]?.toString(),
            scopeCustodianName = row[ItemCheckSessions.scopeCustodianId]?.let { id -> OrganizationalUnits.selectAll().where { OrganizationalUnits.id eq id }.firstOrNull()?.get(OrganizationalUnits.name) },
            scopeType = row[ItemCheckSessions.scopeType], scopeCategory = row[ItemCheckSessions.scopeCategory], scopeSharedOnly = row[ItemCheckSessions.scopeSharedOnly],
            scopePersonalOwnerUserId = row[ItemCheckSessions.scopePersonalOwnerUserId]?.toString(), startedByUserId = row[ItemCheckSessions.startedByUserId].toString(),
            startedByUserName = userName(row[ItemCheckSessions.startedByUserId]), completedByUserId = row[ItemCheckSessions.completedByUserId]?.toString(),
            completedByUserName = userName(row[ItemCheckSessions.completedByUserId]), notes = row[ItemCheckSessions.notes],
            createdAt = row[ItemCheckSessions.createdAt].toString(), completedAt = row[ItemCheckSessions.completedAt]?.toString(),
            summary = ItemCheckSummaryResponse(total, checks.size, (total-checks.size).coerceAtLeast(0), checks.count { it.result == "FOUND" },
                checks.count { it.result == "MISSING" }, checks.count { it.result == "MISPLACED" }, checks.count { it.result == "DAMAGED" }, 0, 0,
                checks.count { it.quantityDifference == 0 }, checks.count { it.quantityDifference < 0 }, checks.count { it.quantityDifference > 0 },
                checks.sumOf { it.expectedQuantity }, checks.sumOf { it.actualQuantity }, checks.sumOf { (-it.quantityDifference).coerceAtLeast(0) },
                checks.sumOf { it.quantityDifference.coerceAtLeast(0) }), checks = if(includeItems) checks else emptyList())
    }

    private fun validLocation(id: UUID, item: ResultRow, userId: UUID): Boolean {
        val location = Locations.selectAll().where { (Locations.id eq id) and (Locations.tuntasId eq item[Items.tuntasId]) }.firstOrNull() ?: return false
        return when(location[Locations.visibility]) { "PRIVATE" -> location[Locations.ownerUserId] == userId; "UNIT" -> location[Locations.ownerUnitId] == item[Items.custodianId]; else -> true }
    }
    private fun userName(id: UUID?): String? = id?.let { Users.selectAll().where { Users.id eq id }.firstOrNull()?.let { "${it[Users.name]} ${it[Users.surname]}".trim() } }
    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    private fun uuid(value: String?): UUID? = value?.let { UUID.fromString(it) }
    private fun <T> rejected(message: String): Result<T> = Result.failure(IllegalArgumentException(message))
}
