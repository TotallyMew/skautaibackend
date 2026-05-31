package lt.skautai.services

import lt.skautai.database.tables.EventInventoryBuckets
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.InventoryKits
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Permissions
import lt.skautai.database.tables.ReservationMovements
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.RolePermissions
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.models.requests.CreateLocationRequest
import lt.skautai.models.requests.UpdateLocationRequest
import lt.skautai.models.responses.LocationListResponse
import lt.skautai.models.responses.LocationResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.util.UUID

class LocationService {

    fun getLocations(tuntasId: UUID, userId: UUID): Result<LocationListResponse> {
        return transaction {
            val context = userContext(userId, tuntasId)
            if (!context.isMember) {
                return@transaction Result.failure(Exception("Not a member of this tuntas"))
            }

            val allRows = loadAllLocationRows(tuntasId)
            val nodesById = allRows.associate { it[Locations.id] to it.toLocationNodeData() }
            val unitNamesById = loadUnitNames(allRows.mapNotNull { it[Locations.ownerUnitId] }.distinct())

            val locations = allRows
                .filter { canViewLocation(it, context) }
                .map { toLocationResponse(it, nodesById, unitNamesById, context) }
                .sortedBy { it.fullPath.lowercase() }

            Result.success(LocationListResponse(locations = locations, total = locations.size))
        }
    }

    fun getLocation(locationId: UUID, tuntasId: UUID, userId: UUID): Result<LocationResponse> {
        return transaction {
            val context = userContext(userId, tuntasId)
            if (!context.isMember) {
                return@transaction Result.failure(Exception("Not a member of this tuntas"))
            }

            val allRows = loadAllLocationRows(tuntasId)
            val locationRow = allRows.firstOrNull { it[Locations.id] == locationId }
                ?: return@transaction Result.failure(Exception("Location not found"))
            if (!canViewLocation(locationRow, context)) {
                return@transaction Result.failure(Exception("Location not found"))
            }

            val nodesById = allRows.associate { it[Locations.id] to it.toLocationNodeData() }
            val unitNamesById = loadUnitNames(allRows.mapNotNull { it[Locations.ownerUnitId] }.distinct())
            Result.success(toLocationResponse(locationRow, nodesById, unitNamesById, context))
        }
    }

    fun createLocation(
        tuntasId: UUID,
        userId: UUID,
        request: CreateLocationRequest
    ): Result<LocationResponse> {
        return transaction {
            val context = userContext(userId, tuntasId)
            if (!context.isMember) {
                return@transaction Result.failure(Exception("Not a member of this tuntas"))
            }
            if (request.name.isBlank()) {
                return@transaction Result.failure(Exception("Name cannot be blank"))
            }

            val visibility = normalizeVisibility(request.visibility)
                ?: return@transaction Result.failure(Exception("Invalid visibility"))
            val parentId = parseOptionalUuid(request.parentLocationId)
                .getOrElse { return@transaction Result.failure(Exception("Invalid parent location ID")) }
            val requestedOwnerUnitId = parseOptionalUuid(request.ownerUnitId)
                .getOrElse { return@transaction Result.failure(Exception("Invalid owner unit ID")) }

            val ownerUnitId = validateCreateScope(
                visibility = visibility,
                userId = userId,
                requestedOwnerUnitId = requestedOwnerUnitId,
                context = context
            ).getOrElse { return@transaction Result.failure(it) }

            val allRows = loadAllLocationRows(tuntasId)
            val parentRow = validateParentForWrite(
                parentId = parentId,
                visibility = visibility,
                ownerUserId = if (visibility == "PRIVATE") userId else null,
                ownerUnitId = ownerUnitId,
                allRows = allRows
            ).getOrElse { return@transaction Result.failure(it) }

            validateDuplicateSibling(
                name = request.name.trim(),
                visibility = visibility,
                parentId = parentId,
                ownerUserId = if (visibility == "PRIVATE") userId else null,
                ownerUnitId = ownerUnitId,
                allRows = allRows
            )?.let { return@transaction Result.failure(it) }

            val createdId = Locations.insert {
                it[this.tuntasId] = tuntasId
                it[name] = request.name.trim()
                it[this.visibility] = visibility
                it[parentLocationId] = parentRow?.get(Locations.id)
                it[ownerUserId] = if (visibility == "PRIVATE") userId else null
                it[this.ownerUnitId] = ownerUnitId
                it[address] = request.address
                it[description] = request.description
                it[latitude] = request.latitude?.toBigDecimalSafe()
                it[longitude] = request.longitude?.toBigDecimalSafe()
            } get Locations.id

            val updatedRows = loadAllLocationRows(tuntasId)
            val created = updatedRows.first { it[Locations.id] == createdId }
            val nodesById = updatedRows.associate { it[Locations.id] to it.toLocationNodeData() }
            val unitNamesById = loadUnitNames(updatedRows.mapNotNull { it[Locations.ownerUnitId] }.distinct())
            Result.success(toLocationResponse(created, nodesById, unitNamesById, context))
        }
    }

    fun updateLocation(
        locationId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: UpdateLocationRequest
    ): Result<LocationResponse> {
        return transaction {
            val context = userContext(userId, tuntasId)
            if (!context.isMember) {
                return@transaction Result.failure(Exception("Not a member of this tuntas"))
            }

            val allRows = loadAllLocationRows(tuntasId)
            val existing = allRows.firstOrNull { it[Locations.id] == locationId }
                ?: return@transaction Result.failure(Exception("Location not found"))
            if (!canEditLocation(existing, context, userId)) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }

            request.name?.let {
                if (it.isBlank()) return@transaction Result.failure(Exception("Name cannot be blank"))
            }
            val visibility = request.visibility?.let { normalizeVisibility(it) }
                ?: existing[Locations.visibility]
            val parentId = when {
                request.parentLocationId == null -> existing[Locations.parentLocationId]
                else -> parseOptionalUuid(request.parentLocationId)
                    .getOrElse { return@transaction Result.failure(Exception("Invalid parent location ID")) }
            }
            val requestedOwnerUnitId = when {
                request.ownerUnitId == null -> existing[Locations.ownerUnitId]
                else -> parseOptionalUuid(request.ownerUnitId)
                    .getOrElse { return@transaction Result.failure(Exception("Invalid owner unit ID")) }
            }

            val ownerUnitId = validateUpdateScope(
                existing = existing,
                targetVisibility = visibility,
                requestedOwnerUnitId = requestedOwnerUnitId,
                context = context,
                userId = userId
            ).getOrElse { return@transaction Result.failure(it) }

            if (parentId == locationId) {
                return@transaction Result.failure(Exception("Location cannot be its own parent"))
            }
            if (parentId != null && isDescendant(parentId, locationId, allRows.associate { it[Locations.id] to it.toLocationNodeData() })) {
                return@transaction Result.failure(Exception("Location cannot be moved under its own descendant"))
            }

            validateParentForWrite(
                parentId = parentId,
                visibility = visibility,
                ownerUserId = if (visibility == "PRIVATE") existing[Locations.ownerUserId] else null,
                ownerUnitId = ownerUnitId,
                allRows = allRows
            ).getOrElse { return@transaction Result.failure(it) }

            validateDuplicateSibling(
                name = request.name?.trim() ?: existing[Locations.name],
                visibility = visibility,
                parentId = parentId,
                ownerUserId = if (visibility == "PRIVATE") existing[Locations.ownerUserId] else null,
                ownerUnitId = ownerUnitId,
                allRows = allRows,
                excludeId = locationId
            )?.let { return@transaction Result.failure(it) }

            Locations.update({ (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }) {
                request.name?.let { v -> it[name] = v.trim() }
                it[Locations.visibility] = visibility
                it[parentLocationId] = parentId
                it[Locations.ownerUnitId] = ownerUnitId
                if (visibility == "PRIVATE") {
                    it[Locations.ownerUserId] = existing[Locations.ownerUserId]
                } else {
                    it[Locations.ownerUserId] = null
                }
                if (request.address != null) it[address] = request.address
                if (request.description != null) it[description] = request.description
                if (request.latitude != null || request.longitude != null) {
                    it[latitude] = request.latitude?.toBigDecimalSafe()
                    it[longitude] = request.longitude?.toBigDecimalSafe()
                }
            }

            val updatedRows = loadAllLocationRows(tuntasId)
            val updated = updatedRows.first { it[Locations.id] == locationId }
            val nodesById = updatedRows.associate { it[Locations.id] to it.toLocationNodeData() }
            val unitNamesById = loadUnitNames(updatedRows.mapNotNull { it[Locations.ownerUnitId] }.distinct())
            Result.success(toLocationResponse(updated, nodesById, unitNamesById, context))
        }
    }

    fun deleteLocation(locationId: UUID, tuntasId: UUID, userId: UUID): Result<Unit> {
        return transaction {
            val context = userContext(userId, tuntasId)
            if (!context.isMember) {
                return@transaction Result.failure(Exception("Not a member of this tuntas"))
            }

            val allRows = loadAllLocationRows(tuntasId)
            val existing = allRows.firstOrNull { it[Locations.id] == locationId }
                ?: return@transaction Result.failure(Exception("Location not found"))
            if (!canEditLocation(existing, context, userId)) {
                return@transaction Result.failure(Exception("Insufficient permissions"))
            }

            if (allRows.any { it[Locations.parentLocationId] == locationId }) {
                return@transaction Result.failure(Exception("Cannot delete location that still has sublocations"))
            }

            val activeItems = Items.selectAll()
                .where { (Items.locationId eq locationId) and (Items.status neq "INACTIVE") }
                .count()
            if (activeItems > 0) {
                return@transaction Result.failure(Exception("Cannot delete location that has active items assigned to it"))
            }
            val activeKits = InventoryKits.selectAll()
                .where { (InventoryKits.locationId eq locationId) and (InventoryKits.status neq "INACTIVE") }
                .count()
            if (activeKits > 0) {
                return@transaction Result.failure(Exception("Cannot delete location that has active inventory kits assigned to it"))
            }
            val reservationsUsingPickup = Reservations.selectAll()
                .where { Reservations.pickupLocationId eq locationId }
                .count()
            if (reservationsUsingPickup > 0) {
                return@transaction Result.failure(Exception("Cannot delete location used for reservation pickup"))
            }
            val reservationsUsingReturn = Reservations.selectAll()
                .where { Reservations.returnLocationId eq locationId }
                .count()
            if (reservationsUsingReturn > 0) {
                return@transaction Result.failure(Exception("Cannot delete location used for reservation return"))
            }
            val movementCount = ReservationMovements.selectAll()
                .where { ReservationMovements.locationId eq locationId }
                .count()
            if (movementCount > 0) {
                return@transaction Result.failure(Exception("Cannot delete location used in reservation movements"))
            }
            val eventBucketCount = EventInventoryBuckets.selectAll()
                .where { EventInventoryBuckets.locationId eq locationId }
                .count()
            if (eventBucketCount > 0) {
                return@transaction Result.failure(Exception("Cannot delete location used by event inventory"))
            }
            val eventCount = Events.selectAll()
                .where { Events.locationId eq locationId }
                .count()
            if (eventCount > 0) {
                return@transaction Result.failure(Exception("Cannot delete location assigned to an event"))
            }

            Locations.deleteWhere { (Locations.id eq locationId) and (Locations.tuntasId eq tuntasId) }
            Result.success(Unit)
        }
    }

    private data class UserContext(
        val userId: UUID,
        val isMember: Boolean,
        val unitIds: Set<UUID>,
        val leaderUnitIds: Set<UUID>,
        val canManageLocationsAll: Boolean,
        val canManageLocationsUnit: Boolean
    ) {
        // true for any leader who has locations.manage in any scope
        val canManagePublicOrUnit: Boolean
            get() = canManageLocationsAll || canManageLocationsUnit

        // only tuntas-level roles (tuntininkas, inventorininkas) with ALL scope
        val canViewAllUnitLocations: Boolean
            get() = canManageLocationsAll
    }

    private fun userContext(userId: UUID, tuntasId: UUID): UserContext {
        val isMember = UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                    (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null

        val leadershipRows = UserLeadershipRoles
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull())
            }
            .toList()
        val unitAssignments = UnitAssignments.selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.leftAt.isNull())
            }
            .map { it[UnitAssignments.organizationalUnitId] }
            .toSet()

        val leaderRoleIds = leadershipRows.map { it[UserLeadershipRoles.roleId] }
        val locationManageScopes: List<String> = if (leaderRoleIds.isEmpty()) emptyList()
        else RolePermissions
            .innerJoin(Permissions, { RolePermissions.permissionId }, { Permissions.id })
            .selectAll()
            .where {
                (RolePermissions.roleId inList leaderRoleIds) and
                    (Permissions.name eq "locations.manage")
            }
            .map { it[RolePermissions.scope] }

        val leaderUnitIds = leadershipRows.mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }.toSet()
        return UserContext(
            userId = userId,
            isMember = isMember,
            unitIds = leaderUnitIds + unitAssignments,
            leaderUnitIds = leaderUnitIds,
            canManageLocationsAll = "ALL" in locationManageScopes,
            canManageLocationsUnit = locationManageScopes.isNotEmpty()
        )
    }

    private fun canViewLocation(row: ResultRow, context: UserContext): Boolean {
        return when (row[Locations.visibility]) {
            "PUBLIC" -> true
            "PRIVATE" -> row[Locations.ownerUserId] == context.userId
            "UNIT" -> {
                val ownerUnitId = row[Locations.ownerUnitId]
                ownerUnitId != null && (ownerUnitId in context.unitIds || context.canViewAllUnitLocations)
            }
            else -> false
        }
    }

    private fun canEditLocation(row: ResultRow, context: UserContext, userId: UUID): Boolean {
        return when (row[Locations.visibility]) {
            "PRIVATE" -> row[Locations.ownerUserId] == userId
            "PUBLIC" -> context.canManageLocationsAll
            "UNIT" -> {
                val ownerUnitId = row[Locations.ownerUnitId]
                ownerUnitId != null && (
                    context.canManageLocationsAll ||
                        (context.canManageLocationsUnit && ownerUnitId in context.leaderUnitIds)
                    )
            }
            else -> false
        }
    }

    private fun validateCreateScope(
        visibility: String,
        userId: UUID,
        requestedOwnerUnitId: UUID?,
        context: UserContext
    ): Result<UUID?> {
        return when (visibility) {
            "PRIVATE" -> Result.success(null)
            "PUBLIC" -> {
                if (!context.canManageLocationsAll) {
                    Result.failure(Exception("Only tuntas leaders and inventory managers can create public locations"))
                } else {
                    Result.success(null)
                }
            }
            "UNIT" -> {
                if (!context.canManagePublicOrUnit) {
                    Result.failure(Exception("Only leaders can create unit locations"))
                } else if (requestedOwnerUnitId == null) {
                    Result.failure(Exception("Unit location must have owner unit"))
                } else if (!context.canManageLocationsAll && requestedOwnerUnitId !in context.unitIds) {
                    Result.failure(Exception("You can create unit locations only for your own units"))
                } else {
                    Result.success(requestedOwnerUnitId)
                }
            }
            else -> Result.failure(Exception("Invalid visibility"))
        }
    }

    private fun validateUpdateScope(
        existing: ResultRow,
        targetVisibility: String,
        requestedOwnerUnitId: UUID?,
        context: UserContext,
        userId: UUID
    ): Result<UUID?> {
        return when (targetVisibility) {
            "PRIVATE" -> {
                if (existing[Locations.ownerUserId] != userId) {
                    Result.failure(Exception("Only owner can keep private location private"))
                } else {
                    Result.success(null)
                }
            }
            "PUBLIC" -> {
                if (!context.canManageLocationsAll) {
                    Result.failure(Exception("Only tuntas leaders and inventory managers can manage public locations"))
                } else {
                    Result.success(null)
                }
            }
            "UNIT" -> {
                if (!context.canManagePublicOrUnit) {
                    Result.failure(Exception("Only leaders can manage unit locations"))
                } else if (requestedOwnerUnitId == null) {
                    Result.failure(Exception("Unit location must have owner unit"))
                } else if (!context.canManageLocationsAll && requestedOwnerUnitId !in context.unitIds) {
                    Result.failure(Exception("You can manage unit locations only for your own units"))
                } else {
                    Result.success(requestedOwnerUnitId)
                }
            }
            else -> Result.failure(Exception("Invalid visibility"))
        }
    }

    private fun validateParentForWrite(
        parentId: UUID?,
        visibility: String,
        ownerUserId: UUID?,
        ownerUnitId: UUID?,
        allRows: List<ResultRow>
    ): Result<ResultRow?> {
        if (parentId == null) return Result.success(null)

        val parent = allRows.firstOrNull { it[Locations.id] == parentId }
            ?: return Result.failure(Exception("Parent location not found"))
        if (parent[Locations.visibility] != visibility) {
            return Result.failure(Exception("Parent location visibility must match child visibility"))
        }
        when (visibility) {
            "PRIVATE" -> if (parent[Locations.ownerUserId] != ownerUserId) {
                return Result.failure(Exception("Private sublocation must stay inside the same private tree"))
            }
            "UNIT" -> if (parent[Locations.ownerUnitId] != ownerUnitId) {
                return Result.failure(Exception("Unit sublocation must stay inside the same unit tree"))
            }
            "PUBLIC" -> {
                if (parent[Locations.ownerUserId] != null || parent[Locations.ownerUnitId] != null) {
                    return Result.failure(Exception("Public sublocation must stay inside the public tree"))
                }
            }
        }
        return Result.success(parent)
    }

    private fun validateDuplicateSibling(
        name: String,
        visibility: String,
        parentId: UUID?,
        ownerUserId: UUID?,
        ownerUnitId: UUID?,
        allRows: List<ResultRow>,
        excludeId: UUID? = null
    ): Exception? {
        val normalized = name.trim().lowercase()
        val duplicate = allRows.firstOrNull { row ->
            row[Locations.id] != excludeId &&
                row[Locations.visibility] == visibility &&
                row[Locations.parentLocationId] == parentId &&
                row[Locations.ownerUserId] == ownerUserId &&
                row[Locations.ownerUnitId] == ownerUnitId &&
                row[Locations.name].trim().lowercase() == normalized
        }
        return if (duplicate != null) {
            Exception("Location with this name already exists in the same folder")
        } else {
            null
        }
    }

    private fun loadAllLocationRows(tuntasId: UUID): List<ResultRow> {
        return Locations.selectAll()
            .where { Locations.tuntasId eq tuntasId }
            .toList()
    }

    private fun loadUnitNames(unitIds: List<UUID>): Map<UUID, String> {
        if (unitIds.isEmpty()) return emptyMap()
        return OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id inList unitIds }
            .associate { it[OrganizationalUnits.id] to it[OrganizationalUnits.name] }
    }

    private fun toLocationResponse(
        row: ResultRow,
        nodesById: Map<UUID, LocationNodeData>,
        unitNamesById: Map<UUID, String>,
        context: UserContext
    ): LocationResponse {
        val id = row[Locations.id]
        val hasChildren = nodesById.values.any { it.parentLocationId == id }
        return LocationResponse(
            id = id.toString(),
            tuntasId = row[Locations.tuntasId].toString(),
            name = row[Locations.name],
            visibility = row[Locations.visibility],
            parentLocationId = row[Locations.parentLocationId]?.toString(),
            ownerUserId = row[Locations.ownerUserId]?.toString(),
            ownerUnitId = row[Locations.ownerUnitId]?.toString(),
            ownerUnitName = row[Locations.ownerUnitId]?.let(unitNamesById::get),
            fullPath = buildLocationPath(id, nodesById),
            hasChildren = hasChildren,
            isLeafSelectable = !hasChildren,
            isEditable = canEditLocation(row, context, context.userId),
            address = row[Locations.address],
            description = row[Locations.description],
            latitude = row[Locations.latitude]?.toDouble(),
            longitude = row[Locations.longitude]?.toDouble(),
            createdAt = row[Locations.createdAt].toString()
        )
    }

    private fun normalizeVisibility(value: String): String? {
        val normalized = value.trim().uppercase()
        return normalized.takeIf { it in setOf("PRIVATE", "UNIT", "PUBLIC") }
    }

    private fun parseOptionalUuid(value: String?): Result<UUID?> {
        if (value == null || value.isBlank()) return Result.success(null)
        return runCatching { UUID.fromString(value) }
    }

    private fun Double.toBigDecimalSafe(): BigDecimal = BigDecimal.valueOf(this)

    private fun isDescendant(
        candidateParentId: UUID,
        locationId: UUID,
        nodesById: Map<UUID, LocationNodeData>
    ): Boolean {
        var currentId: UUID? = candidateParentId
        val visited = mutableSetOf<UUID>()
        while (currentId != null && visited.add(currentId)) {
            if (currentId == locationId) return true
            currentId = nodesById[currentId]?.parentLocationId
        }
        return false
    }
}
