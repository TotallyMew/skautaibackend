package lt.skautai.services

import java.util.UUID
import kotlinx.datetime.Clock
import lt.skautai.database.tables.InventoryKitItems
import lt.skautai.database.tables.InventoryKits
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateInventoryKitRequest
import lt.skautai.models.requests.InventoryKitItemRequest
import lt.skautai.models.requests.UpdateInventoryKitRequest
import lt.skautai.models.responses.InventoryKitItemResponse
import lt.skautai.models.responses.InventoryKitListResponse
import lt.skautai.models.responses.InventoryKitResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class InventoryKitService {
    fun listKits(tuntasId: UUID, includeInactive: Boolean = false): Result<InventoryKitListResponse> = transaction {
        val kits = InventoryKits.selectAll()
            .where {
                if (includeInactive) {
                    InventoryKits.tuntasId eq tuntasId
                } else {
                    (InventoryKits.tuntasId eq tuntasId) and (InventoryKits.status eq "ACTIVE")
                }
            }
            .orderBy(InventoryKits.name to SortOrder.ASC)
            .toList()
        val hydration = buildKitListHydration(kits)
        val responses = kits.map { toResponse(it, hydration) }
        Result.success(InventoryKitListResponse(responses, responses.size))
    }

    fun getKit(kitId: UUID, tuntasId: UUID): Result<InventoryKitResponse> = transaction {
        val kit = InventoryKits.selectAll()
            .where { (InventoryKits.id eq kitId) and (InventoryKits.tuntasId eq tuntasId) }
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Inventory kit not found"))
        Result.success(toResponse(kit))
    }

    fun createKit(tuntasId: UUID, userId: UUID, request: CreateInventoryKitRequest): Result<InventoryKitResponse> = transaction {
        validateName(request.name)?.let { return@transaction Result.failure(it) }
        val itemRows = loadItemRows(tuntasId, request.items).getOrElse { return@transaction Result.failure(it) }
        val custodianId = resolveCustodian(tuntasId, request.custodianId, itemRows).getOrElse { return@transaction Result.failure(it) }
        val locationId = parseAndValidateLocation(tuntasId, request.locationId).getOrElse { return@transaction Result.failure(it) }
        val responsibleUserId = parseAndValidateUser(request.responsibleUserId).getOrElse { return@transaction Result.failure(it) }
        validateItemScope(custodianId, itemRows)?.let { return@transaction Result.failure(it) }
        validateItemsAvailableForKit(null, itemRows)?.let { return@transaction Result.failure(it) }

        val now = Clock.System.now()
        val kitId = UUID.randomUUID()
        InventoryKits.insert {
            it[id] = kitId
            it[this.tuntasId] = tuntasId
            it[this.custodianId] = custodianId
            it[name] = request.name.trim()
            it[description] = request.description?.trim()?.ifBlank { null }
            it[this.locationId] = locationId
            it[temporaryStorageLabel] = request.temporaryStorageLabel?.trim()?.ifBlank { null }
            it[this.responsibleUserId] = responsibleUserId
            it[createdByUserId] = userId
            it[status] = "ACTIVE"
            it[createdAt] = now
            it[updatedAt] = now
        }
        replaceItems(kitId, itemRows, locationId, request.temporaryStorageLabel)
        Result.success(toResponse(InventoryKits.selectAll().where { InventoryKits.id eq kitId }.first()))
    }

    fun updateKit(kitId: UUID, tuntasId: UUID, request: UpdateInventoryKitRequest): Result<InventoryKitResponse> = transaction {
        val existing = InventoryKits.selectAll()
            .where { (InventoryKits.id eq kitId) and (InventoryKits.tuntasId eq tuntasId) }
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Inventory kit not found"))

        request.name?.let { validateName(it)?.let { error -> return@transaction Result.failure(error) } }
        request.status?.let {
            if (it !in listOf("ACTIVE", "INACTIVE")) return@transaction Result.failure(Exception("Invalid kit status"))
        }

        val itemRows = request.items?.let { loadItemRows(tuntasId, it).getOrElse { error -> return@transaction Result.failure(error) } }
        val custodianId = resolveCustodian(
            tuntasId = tuntasId,
            requestedCustodianId = request.custodianId,
            itemRows = itemRows ?: currentItemRows(kitId),
            fallback = existing[InventoryKits.custodianId]
        ).getOrElse { return@transaction Result.failure(it) }
        val locationId = when {
            request.clearLocationId -> null
            request.locationId != null -> parseAndValidateLocation(tuntasId, request.locationId).getOrElse { return@transaction Result.failure(it) }
            else -> existing[InventoryKits.locationId]
        }
        val responsibleUserId = when {
            request.clearResponsibleUserId -> null
            request.responsibleUserId != null -> parseAndValidateUser(request.responsibleUserId).getOrElse { return@transaction Result.failure(it) }
            else -> existing[InventoryKits.responsibleUserId]
        }
        validateItemScope(custodianId, itemRows ?: currentItemRows(kitId))?.let { return@transaction Result.failure(it) }
        itemRows?.let { validateItemsAvailableForKit(kitId, it)?.let { error -> return@transaction Result.failure(error) } }

        val now = Clock.System.now()
        InventoryKits.update({ (InventoryKits.id eq kitId) and (InventoryKits.tuntasId eq tuntasId) }) {
            request.name?.let { v -> it[name] = v.trim() }
            request.description?.let { v -> it[description] = v.trim().ifBlank { null } }
            it[this.custodianId] = custodianId
            it[this.locationId] = locationId
            request.temporaryStorageLabel?.let { v -> it[temporaryStorageLabel] = v.trim().ifBlank { null } }
            it[this.responsibleUserId] = responsibleUserId
            request.status?.let { v -> it[status] = v }
            it[updatedAt] = now
        }
        if (request.status == "INACTIVE") {
            InventoryKitItems.deleteWhere { InventoryKitItems.kitId eq kitId }
        } else if (itemRows != null) {
            replaceItems(kitId, itemRows, locationId, request.temporaryStorageLabel ?: existing[InventoryKits.temporaryStorageLabel])
        } else {
            syncChildItemLocations(kitId, locationId, request.temporaryStorageLabel ?: existing[InventoryKits.temporaryStorageLabel])
        }
        Result.success(toResponse(InventoryKits.selectAll().where { InventoryKits.id eq kitId }.first()))
    }

    fun deleteKit(kitId: UUID, tuntasId: UUID): Result<Unit> = transaction {
        val updated = InventoryKits.update({ (InventoryKits.id eq kitId) and (InventoryKits.tuntasId eq tuntasId) }) {
            it[status] = "INACTIVE"
            it[updatedAt] = Clock.System.now()
        }
        if (updated == 0) {
            Result.failure(Exception("Inventory kit not found"))
        } else {
            InventoryKitItems.deleteWhere { InventoryKitItems.kitId eq kitId }
            Result.success(Unit)
        }
    }

    private fun validateName(name: String): Exception? = when {
        name.isBlank() -> Exception("Kit name is required")
        name.length > 200 -> Exception("Kit name is too long")
        else -> null
    }

    private fun loadItemRows(tuntasId: UUID, items: List<InventoryKitItemRequest>): Result<List<ResultRow>> {
        val itemIds = items.map { request ->
            try {
                UUID.fromString(request.itemId)
            } catch (e: Exception) {
                return Result.failure(Exception("Invalid item ID"))
            }
        }.distinct()
        val rowsById = if (itemIds.isEmpty()) {
            emptyMap()
        } else {
            Items.selectAll()
                .where { (Items.id inList itemIds) and (Items.tuntasId eq tuntasId) and (Items.status eq "ACTIVE") }
                .associateBy { it[Items.id] }
        }
        if (rowsById.size != itemIds.size) {
            return Result.failure(Exception("Kit item not found in this tuntas"))
        }
        return Result.success(itemIds.map { rowsById.getValue(it) })
    }

    private fun currentItemRows(kitId: UUID): List<ResultRow> {
        val itemIds = InventoryKitItems.selectAll()
            .where { InventoryKitItems.kitId eq kitId }
            .map { it[InventoryKitItems.itemId] }
        if (itemIds.isEmpty()) return emptyList()
        val rowsById = Items.selectAll()
            .where { Items.id inList itemIds }
            .associateBy { it[Items.id] }
        return itemIds.mapNotNull { rowsById[it] }
    }

    private fun resolveCustodian(
        tuntasId: UUID,
        requestedCustodianId: String?,
        itemRows: List<ResultRow>,
        fallback: UUID? = null
    ): Result<UUID?> {
        val requested = requestedCustodianId?.let {
            try {
                UUID.fromString(it)
            } catch (e: Exception) {
                return Result.failure(Exception("Invalid custodian ID"))
            }
        }
        if (requested != null) {
            OrganizationalUnits.selectAll()
                .where { (OrganizationalUnits.id eq requested) and (OrganizationalUnits.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return Result.failure(Exception("Kit custodian not found in this tuntas"))
            return Result.success(requested)
        }
        val distinctCustodians = itemRows.map { it[Items.custodianId] }.distinct()
        return when {
            distinctCustodians.isEmpty() -> Result.success(fallback)
            distinctCustodians.size == 1 -> Result.success(distinctCustodians.single())
            else -> Result.failure(Exception("Kit items must belong to one inventory scope"))
        }
    }

    private fun validateItemScope(custodianId: UUID?, itemRows: List<ResultRow>): Exception? {
        val hasMismatch = itemRows.any { it[Items.custodianId] != custodianId }
        return if (hasMismatch) Exception("Kit custodian must match all child items") else null
    }

    private fun validateItemsAvailableForKit(kitId: UUID?, itemRows: List<ResultRow>): Exception? {
        val itemIds = itemRows.map { it[Items.id] }
        if (itemIds.isEmpty()) return null
        val itemNamesById = itemRows.associate { it[Items.id] to it[Items.name] }
        val memberships = InventoryKitItems.selectAll()
            .where { InventoryKitItems.itemId inList itemIds }
            .toList()
        if (memberships.isEmpty()) return null

        val kitIds = memberships.map { it[InventoryKitItems.kitId] }.distinct()
        val activeKitsById = InventoryKits.selectAll()
            .where { (InventoryKits.id inList kitIds) and (InventoryKits.status eq "ACTIVE") }
            .associateBy { it[InventoryKits.id] }

        memberships.forEach { membership ->
            val activeKit = activeKitsById[membership[InventoryKitItems.kitId]]
            if (activeKit != null && activeKit[InventoryKits.id] != kitId) {
                val itemName = itemNamesById[membership[InventoryKitItems.itemId]] ?: "Item"
                return Exception("$itemName is already in another active kit")
            }
        }
        return null
    }

    private fun parseAndValidateLocation(tuntasId: UUID, value: String?): Result<UUID?> {
        if (value.isNullOrBlank()) return Result.success(null)
        val locationId = try {
            UUID.fromString(value)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid location ID"))
        }
        Locations.selectAll()
            .where { (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }
            .firstOrNull()
            ?: return Result.failure(Exception("Kit location not found in this tuntas"))
        return Result.success(locationId)
    }

    private fun parseAndValidateUser(value: String?): Result<UUID?> {
        if (value.isNullOrBlank()) return Result.success(null)
        val userId = try {
            UUID.fromString(value)
        } catch (e: Exception) {
            return Result.failure(Exception("Invalid responsible user ID"))
        }
        Users.selectAll().where { Users.id eq userId }.firstOrNull()
            ?: return Result.failure(Exception("Responsible user not found"))
        return Result.success(userId)
    }

    private fun replaceItems(
        kitId: UUID,
        itemRows: List<ResultRow>,
        locationId: UUID?,
        temporaryStorageLabel: String?
    ) {
        InventoryKitItems.deleteWhere { InventoryKitItems.kitId eq kitId }
        itemRows.forEach { item ->
            InventoryKitItems.insert {
                it[id] = UUID.randomUUID()
                it[this.kitId] = kitId
                it[itemId] = item[Items.id]
                it[quantity] = item[Items.quantity]
                it[notes] = null
            }
        }
        syncChildItemLocations(kitId, locationId, temporaryStorageLabel)
    }

    private fun syncChildItemLocations(kitId: UUID, locationId: UUID?, temporaryStorageLabel: String?) {
        InventoryKitItems.selectAll()
            .where { InventoryKitItems.kitId eq kitId }
            .forEach { kitItem ->
                Items.update({ Items.id eq kitItem[InventoryKitItems.itemId] }) {
                    it[this.locationId] = locationId
                    it[this.temporaryStorageLabel] = temporaryStorageLabel?.trim()?.ifBlank { null }
                    it[updatedAt] = Clock.System.now()
                }
            }
    }

    private data class KitListHydration(
        val locationNodesByTuntasId: Map<UUID, Map<UUID, LocationNode>>,
        val unitNames: Map<UUID, String>,
        val userNames: Map<UUID, String>,
        val itemsByKitId: Map<UUID, List<InventoryKitItemResponse>>
    )

    private fun buildKitListHydration(rows: List<ResultRow>): KitListHydration {
        if (rows.isEmpty()) {
            return KitListHydration(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
        val tuntasIds = rows.map { it[InventoryKits.tuntasId] }.distinct()
        val locationNodesByTuntasId = tuntasIds.associateWith { tuntasId ->
            Locations.selectAll()
                .where { Locations.tuntasId eq tuntasId }
                .associate { it[Locations.id] to LocationNode(it[Locations.name], it[Locations.parentLocationId]) }
        }
        val unitIds = rows.mapNotNull { it[InventoryKits.custodianId] }.distinct()
        val unitNames = if (unitIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id inList unitIds }
                .associate { it[OrganizationalUnits.id] to it[OrganizationalUnits.name] }
        }
        val userIds = rows.flatMap { listOfNotNull(it[InventoryKits.responsibleUserId], it[InventoryKits.createdByUserId]) }.distinct()
        val userNames = if (userIds.isEmpty()) {
            emptyMap()
        } else {
            Users.selectAll()
                .where { Users.id inList userIds }
                .associate { it[Users.id] to "${it[Users.name]} ${it[Users.surname]}".trim() }
        }
        val kitIds = rows.map { it[InventoryKits.id] }
        val kitItemRows = InventoryKitItems.selectAll()
            .where { InventoryKitItems.kitId inList kitIds }
            .toList()
        val itemIds = kitItemRows.map { it[InventoryKitItems.itemId] }.distinct()
        val itemRowsById = if (itemIds.isEmpty()) {
            emptyMap()
        } else {
            Items.selectAll()
                .where { Items.id inList itemIds }
                .associateBy { it[Items.id] }
        }
        val kitTuntasById = rows.associate { it[InventoryKits.id] to it[InventoryKits.tuntasId] }
        val itemsByKitId = kitItemRows.mapNotNull { kitItem ->
            val kitId = kitItem[InventoryKitItems.kitId]
            val item = itemRowsById[kitItem[InventoryKitItems.itemId]] ?: return@mapNotNull null
            val itemLocationId = item[Items.locationId]
            val locationNodes = locationNodesByTuntasId[kitTuntasById[kitId]].orEmpty()
            kitId to InventoryKitItemResponse(
                id = kitItem[InventoryKitItems.id].toString(),
                itemId = item[Items.id].toString(),
                itemName = item[Items.name],
                itemCondition = item[Items.condition],
                itemStatus = item[Items.status],
                availableQuantity = item[Items.quantity],
                quantity = item[Items.quantity],
                locationId = itemLocationId?.toString(),
                locationName = itemLocationId?.let { locationNodes[it]?.name },
                locationPath = itemLocationId?.let { buildLocationPath(it, locationNodes) },
                notes = kitItem[InventoryKitItems.notes]
            )
        }.groupBy({ it.first }, { it.second })
        return KitListHydration(locationNodesByTuntasId, unitNames, userNames, itemsByKitId)
    }

    private fun toResponse(row: ResultRow, hydration: KitListHydration? = null): InventoryKitResponse {
        val kitId = row[InventoryKits.id]
        val locationNodes = hydration?.locationNodesByTuntasId?.get(row[InventoryKits.tuntasId]) ?: Locations.selectAll()
            .where { Locations.tuntasId eq row[InventoryKits.tuntasId] }
            .associate { it[Locations.id] to LocationNode(it[Locations.name], it[Locations.parentLocationId]) }
        val locationId = row[InventoryKits.locationId]
        val locationName = locationId?.let { locationNodes[it]?.name }
        val locationPath = locationId?.let { buildLocationPath(it, locationNodes) }
        val items = hydration?.itemsByKitId?.get(kitId) ?: run {
            val kitItemRows = InventoryKitItems.selectAll()
                .where { InventoryKitItems.kitId eq kitId }
                .toList()
            val itemIds = kitItemRows.map { it[InventoryKitItems.itemId] }.distinct()
            val itemRowsById = if (itemIds.isEmpty()) {
                emptyMap()
            } else {
                Items.selectAll()
                    .where { Items.id inList itemIds }
                    .associateBy { it[Items.id] }
            }
            kitItemRows.mapNotNull { kitItem ->
                val item = itemRowsById[kitItem[InventoryKitItems.itemId]] ?: return@mapNotNull null
                val itemLocationId = item[Items.locationId]
                InventoryKitItemResponse(
                    id = kitItem[InventoryKitItems.id].toString(),
                    itemId = item[Items.id].toString(),
                    itemName = item[Items.name],
                    itemCondition = item[Items.condition],
                    itemStatus = item[Items.status],
                    availableQuantity = item[Items.quantity],
                    quantity = item[Items.quantity],
                    locationId = itemLocationId?.toString(),
                    locationName = itemLocationId?.let { locationNodes[it]?.name },
                    locationPath = itemLocationId?.let { buildLocationPath(it, locationNodes) },
                    notes = kitItem[InventoryKitItems.notes]
                )
            }
        }
        return InventoryKitResponse(
            id = kitId.toString(),
            tuntasId = row[InventoryKits.tuntasId].toString(),
            custodianId = row[InventoryKits.custodianId]?.toString(),
            custodianName = row[InventoryKits.custodianId]?.let { hydration?.unitNames?.get(it) } ?: row[InventoryKits.custodianId]?.let(::unitName),
            name = row[InventoryKits.name],
            description = row[InventoryKits.description],
            locationId = locationId?.toString(),
            locationName = locationName,
            locationPath = locationPath,
            temporaryStorageLabel = row[InventoryKits.temporaryStorageLabel],
            responsibleUserId = row[InventoryKits.responsibleUserId]?.toString(),
            responsibleUserName = row[InventoryKits.responsibleUserId]?.let { hydration?.userNames?.get(it) } ?: userName(row[InventoryKits.responsibleUserId]),
            createdByUserId = row[InventoryKits.createdByUserId]?.toString(),
            createdByUserName = row[InventoryKits.createdByUserId]?.let { hydration?.userNames?.get(it) } ?: userName(row[InventoryKits.createdByUserId]),
            status = row[InventoryKits.status],
            createdAt = row[InventoryKits.createdAt].toString(),
            updatedAt = row[InventoryKits.updatedAt].toString(),
            items = items
        )
    }

    private fun unitName(unitId: UUID): String? =
        OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq unitId }
            .firstOrNull()
            ?.get(OrganizationalUnits.name)

    private fun userName(userId: UUID?): String? {
        if (userId == null) return null
        return Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
    }

    private data class LocationNode(val name: String, val parentId: UUID?)

    private fun buildLocationPath(locationId: UUID, nodes: Map<UUID, LocationNode>): String {
        val names = mutableListOf<String>()
        var current: UUID? = locationId
        val visited = mutableSetOf<UUID>()
        while (current != null && visited.add(current)) {
            val node = nodes[current] ?: break
            names += node.name
            current = node.parentId
        }
        return names.asReversed().joinToString(" / ")
    }

    companion object {
        fun activeKitForItem(itemId: UUID): ResultRow? {
            val memberships = InventoryKitItems.selectAll()
                .where { InventoryKitItems.itemId eq itemId }
            return memberships.firstNotNullOfOrNull { membership ->
                InventoryKits.selectAll()
                    .where { (InventoryKits.id eq membership[InventoryKitItems.kitId]) and (InventoryKits.status eq "ACTIVE") }
                    .firstOrNull()
            }
        }

        fun syncMembershipAfterItemQuantityChange(itemId: UUID, remainingQuantity: Int) {
            if (remainingQuantity <= 0) {
                InventoryKitItems.deleteWhere { InventoryKitItems.itemId eq itemId }
            } else {
                InventoryKitItems.update({ InventoryKitItems.itemId eq itemId }) {
                    it[quantity] = remainingQuantity
                }
            }
        }
    }
}
