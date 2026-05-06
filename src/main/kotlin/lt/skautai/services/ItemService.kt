package lt.skautai.services

import lt.skautai.database.tables.Items
import lt.skautai.database.tables.ItemAssignments
import lt.skautai.database.tables.ItemConditionLog
import lt.skautai.database.tables.ItemCustomFields
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.ItemCustomFieldRequest
import lt.skautai.models.requests.UpdateItemRequest
import lt.skautai.models.responses.ItemCustomFieldResponse
import lt.skautai.models.responses.ItemAssignmentListResponse
import lt.skautai.models.responses.ItemAssignmentResponse
import lt.skautai.models.responses.ItemConditionLogListResponse
import lt.skautai.models.responses.ItemConditionLogResponse
import lt.skautai.models.responses.ItemListResponse
import lt.skautai.models.responses.ItemResponse
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*

class DuplicateItemConflictException(val duplicateItem: ItemResponse) :
    Exception("Item with the same name already exists")

class ItemService {

    fun getItems(
        tuntasId: UUID,
        requestingUserId: UUID,
        custodianId: String? = null,
        type: String? = null,
        category: String? = null,
        status: String? = null,
        sharedOnly: Boolean = false
    ): Result<ItemListResponse> {
        return transaction {
            val canSeeAll = userCanSeeAllStatuses(requestingUserId, tuntasId)
            val canSeeAllInventory = userCanManageAllInventory(requestingUserId, tuntasId)
            val visibleUnitIds = if (canSeeAllInventory) emptySet() else userVisibleUnitIds(requestingUserId, tuntasId)

            var query = Items.selectAll()
                .where { Items.tuntasId eq tuntasId }

            if (!canSeeAllInventory) {
                query = if (visibleUnitIds.isEmpty()) {
                    query.andWhere { Items.custodianId.isNull() }
                } else {
                    query.andWhere {
                        Items.custodianId.isNull() or (Items.custodianId inList visibleUnitIds.toList())
                    }
                }
                query = query.andWhere {
                    (Items.type neq "INDIVIDUAL") or (Items.createdByUserId eq requestingUserId)
                }
            }

            if (!canSeeAll) {
                query = query.andWhere { Items.status eq "ACTIVE" }
            }

            if (custodianId != null) {
                val uuid = try { UUID.fromString(custodianId) } catch (e: Exception) { null }
                if (!canSeeAllInventory && uuid != null && uuid !in visibleUnitIds) {
                    return@transaction Result.success(ItemListResponse(items = emptyList(), total = 0))
                }
                if (uuid != null) query = query.andWhere { Items.custodianId eq uuid }
            } else if (sharedOnly) {
                query = query.andWhere {
                    Items.custodianId.isNull() and (Items.type neq "INDIVIDUAL")
                }
            }
            type?.let { query = query.andWhere { Items.type eq it } }
            category?.let { query = query.andWhere { Items.category eq it } }
            status?.let {
                if (canSeeAll) {
                    query = query.andWhere { Items.status eq it }
                } else if (it != "ACTIVE") {
                    return@transaction Result.success(ItemListResponse(items = emptyList(), total = 0))
                }
            }

            val items = query.map { toItemResponse(it) }
            Result.success(ItemListResponse(items = items, total = items.size))
        }
    }

    fun getItem(
        itemId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<ItemResponse> {
        return transaction {
            val canSeeAll = userCanSeeAllStatuses(requestingUserId, tuntasId)
            val canSeeAllInventory = userCanManageAllInventory(requestingUserId, tuntasId)

            val item = Items.selectAll()
                .where {
                    (Items.id eq itemId) and (Items.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            if (!canSeeAll && item[Items.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Item not found"))
            }
            val custodianId = item[Items.custodianId]
            if (!canSeeAllInventory &&
                item[Items.type] == "INDIVIDUAL" &&
                item[Items.createdByUserId] != requestingUserId
            ) {
                return@transaction Result.failure(Exception("Item not found"))
            }
            if (!canSeeAllInventory && custodianId != null && custodianId !in userVisibleUnitIds(requestingUserId, tuntasId)) {
                return@transaction Result.failure(Exception("Item not found"))
            }

            Result.success(toItemResponse(item))
        }
    }

    fun resolveItemIdByQrToken(
        qrToken: String,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<UUID> {
        val itemId = transaction {
            val itemId = Items.select(Items.id)
                .where {
                    (Items.qrToken eq qrToken) and
                        (Items.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?.get(Items.id)
                ?: return@transaction null

            itemId
        }

        if (itemId == null) {
            return Result.failure(Exception("Item not found"))
        }

        return getItem(itemId, tuntasId, requestingUserId)
            .map { itemId }
    }

    fun createItem(
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateItemRequest,
        isPendingApproval: Boolean = false
    ): Result<ItemResponse> {
        return transaction {
            val normalizedName = request.name.trim()
            if (normalizedName.isBlank()) {
                return@transaction Result.failure(Exception("Item name is required"))
            }

            if (request.type !in listOf("COLLECTIVE", "ASSIGNED", "INDIVIDUAL")) {
                return@transaction Result.failure(Exception("Invalid inventory type"))
            }

            if (request.category !in listOf("CAMPING", "TOOLS", "COOKING", "FIRST_AID", "UNIFORMS", "BOOKS", "PERSONAL_LOANS")) {
                return@transaction Result.failure(Exception("Invalid inventory category"))
            }

            if (request.condition !in listOf("GOOD", "DAMAGED", "WRITTEN_OFF")) {
                return@transaction Result.failure(Exception("Invalid condition"))
            }

            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            if (request.duplicateHandling !in listOf("ASK", "ADD_TO_EXISTING", "CREATE_NEW")) {
                return@transaction Result.failure(Exception("Invalid duplicate handling option"))
            }

            validateCustomFields(request.customFields)?.let {
                return@transaction Result.failure(it)
            }

            val custodianUUID = request.custodianId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid custodian ID"))
                }
            }

            if (request.type == "INDIVIDUAL" && custodianUUID != null) {
                return@transaction Result.failure(Exception("Personal inventory items cannot be assigned to a unit"))
            }

            // Validate custodian belongs to this tuntas if provided
            if (custodianUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq custodianUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Custodian unit not found in this tuntas"))
            }

            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
                }
            }
            validateItemLocation(
                locationId = locationUUID,
                tuntasId = tuntasId,
                itemType = request.type,
                custodianId = custodianUUID,
                ownerUserId = createdByUserId
            )?.let { return@transaction Result.failure(it) }

            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }
            validateResponsibleUser(responsibleUUID, tuntasId)?.let {
                return@transaction Result.failure(it)
            }

            val purchaseDate = request.purchaseDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }

            val duplicateTargetItemId = request.duplicateTargetItemId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid duplicate target item ID"))
                }
            }

            val duplicateItem = findDuplicateItem(
                tuntasId = tuntasId,
                custodianId = custodianUUID,
                name = normalizedName,
                type = request.type,
                category = request.category,
                duplicateTargetItemId = duplicateTargetItemId
            )

            if (request.duplicateHandling == "ADD_TO_EXISTING" &&
                duplicateTargetItemId != null &&
                duplicateItem == null
            ) {
                return@transaction Result.failure(Exception("Duplicate target item not found"))
            }

            if (duplicateItem != null) {
                when (request.duplicateHandling) {
                    "ASK" -> return@transaction Result.failure(
                        DuplicateItemConflictException(toItemResponse(duplicateItem))
                    )
                    "ADD_TO_EXISTING" -> {
                        val now = Clock.System.now()
                        Items.update({ Items.id eq duplicateItem[Items.id] }) {
                            it[quantity] = duplicateItem[Items.quantity] + request.quantity
                            it[updatedAt] = now
                        }
                        val updatedItem = Items.selectAll()
                            .where { Items.id eq duplicateItem[Items.id] }
                            .first()
                        return@transaction Result.success(toItemResponse(updatedItem))
                    }
                }
            }

            val now = Clock.System.now()

            val itemId = Items.insert {
                it[this.tuntasId] = tuntasId
                it[Items.custodianId] = custodianUUID
                it[origin] = "UNIT_ACQUIRED"
                it[name] = normalizedName
                it[description] = request.description
                it[type] = request.type
                it[category] = request.category
                it[condition] = request.condition
                it[quantity] = request.quantity
                it[locationId] = locationUUID
                it[temporaryStorageLabel] = request.temporaryStorageLabel
                it[sourceSharedItemId] = null
                it[responsibleUserId] = responsibleUUID
                it[Items.createdByUserId] = createdByUserId
                it[qrToken] = UUID.randomUUID().toString()
                it[photoUrl] = request.photoUrl
                it[this.purchaseDate] = purchaseDate
                it[purchasePrice] = request.purchasePrice?.toBigDecimal()
                it[notes] = request.notes
                it[status] = if (isPendingApproval) "PENDING_APPROVAL" else "ACTIVE"
                it[createdAt] = now
                it[updatedAt] = now
            } get Items.id

            replaceCustomFields(itemId, request.customFields)
            upsertResponsibleAssignment(
                itemId = itemId,
                previousResponsibleUserId = null,
                nextResponsibleUserId = responsibleUUID,
                assignedByUserId = createdByUserId,
                reason = "INITIAL_ASSIGNMENT",
                notes = null
            )

            val item = Items.selectAll()
                .where { Items.id eq itemId }
                .first()

            Result.success(toItemResponse(item))
        }
    }

    private fun findDuplicateItem(
        tuntasId: UUID,
        custodianId: UUID?,
        name: String,
        type: String,
        category: String,
        duplicateTargetItemId: UUID?
    ): ResultRow? {
        val normalizedName = normalizeItemName(name)
        val candidates = Items.selectAll()
            .where {
                (Items.tuntasId eq tuntasId) and
                    (Items.status eq "ACTIVE") and
                    (Items.type eq type) and
                    (Items.category eq category) and
                    if (custodianId == null) {
                        Items.custodianId.isNull()
                    } else {
                        Items.custodianId eq custodianId
                    }
            }
            .toList()
            .filter { normalizeItemName(it[Items.name]) == normalizedName }

        if (candidates.isEmpty()) {
            return null
        }

        if (duplicateTargetItemId != null) {
            return candidates.firstOrNull { it[Items.id] == duplicateTargetItemId }
        }

        return candidates.maxByOrNull { it[Items.updatedAt].toString() }
    }

    private fun normalizeItemName(value: String): String = value.trim().lowercase()

    fun updateItem(
        itemId: UUID,
        tuntasId: UUID,
        request: UpdateItemRequest,
        updatedByUserId: UUID? = null
    ): Result<ItemResponse> {
        return transaction {
            val existing = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            if (existing[Items.status] == "INACTIVE" && request.status == null) {
                return@transaction Result.failure(Exception("Cannot update an inactive item"))
            }

            request.quantity?.let {
                if (it < 1) return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            request.type?.let {
                if (it !in listOf("COLLECTIVE", "ASSIGNED", "INDIVIDUAL")) {
                    return@transaction Result.failure(Exception("Invalid inventory type"))
                }
            }

            request.category?.let {
                if (it !in listOf("CAMPING", "TOOLS", "COOKING", "FIRST_AID", "UNIFORMS", "BOOKS", "PERSONAL_LOANS")) {
                    return@transaction Result.failure(Exception("Invalid inventory category"))
                }
            }

            request.condition?.let {
                if (it !in listOf("GOOD", "DAMAGED", "WRITTEN_OFF")) {
                    return@transaction Result.failure(Exception("Invalid condition"))
                }
            }

            request.status?.let {
                if (it !in listOf("ACTIVE", "PENDING_APPROVAL", "INACTIVE")) {
                    return@transaction Result.failure(Exception("Invalid status"))
                }
            }

            val custodianUUID = request.custodianId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid custodian ID"))
                }
            }

            if (custodianUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq custodianUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Custodian unit not found in this tuntas"))
            }

            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
                }
            }
            val effectiveType = request.type ?: existing[Items.type]
            val effectiveCustodianId = when {
                request.clearCustodianId -> null
                request.custodianId != null -> custodianUUID
                else -> existing[Items.custodianId]
            }
            if (effectiveType == "INDIVIDUAL" && effectiveCustodianId != null) {
                return@transaction Result.failure(Exception("Personal inventory items cannot be assigned to a unit"))
            }
            val effectiveLocationId = when {
                request.clearLocationId -> null
                request.locationId != null -> locationUUID
                else -> existing[Items.locationId]
            }
            validateItemLocation(
                locationId = effectiveLocationId,
                tuntasId = tuntasId,
                itemType = effectiveType,
                custodianId = effectiveCustodianId,
                ownerUserId = existing[Items.createdByUserId]
            )?.let { return@transaction Result.failure(it) }

            val sourceSharedItemUUID = request.sourceSharedItemId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid source shared item ID"))
                }
            }

            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }
            val nextResponsibleUserId = when {
                request.clearResponsibleUserId -> null
                request.responsibleUserId != null -> responsibleUUID
                else -> existing[Items.responsibleUserId]
            }
            validateResponsibleUser(nextResponsibleUserId, tuntasId)?.let {
                return@transaction Result.failure(it)
            }

            val purchaseDate = request.purchaseDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }

            request.customFields?.let { fields ->
                validateCustomFields(fields)?.let {
                    return@transaction Result.failure(it)
                }
            }

            val previousCondition = existing[Items.condition]
            val previousResponsibleUserId = existing[Items.responsibleUserId]
            val now = Clock.System.now()

            Items.update({ (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }) {
                request.name?.let { v -> it[name] = v }
                request.description?.let { v -> it[description] = v }
                request.type?.let { v -> it[type] = v }
                request.category?.let { v -> it[category] = v }
                request.condition?.let { v -> it[condition] = v }
                request.quantity?.let { v -> it[quantity] = v }
                request.photoUrl?.let { v -> it[photoUrl] = v }
                request.purchasePrice?.let { v -> it[purchasePrice] = v.toBigDecimal() }
                request.notes?.let { v -> it[notes] = v }
                request.status?.let { v -> it[status] = v }
                when {
                    request.clearCustodianId -> it[custodianId] = null
                    request.custodianId != null -> it[custodianId] = custodianUUID
                }
                when {
                    request.clearLocationId -> it[locationId] = null
                    request.locationId != null -> it[locationId] = locationUUID
                }
                request.temporaryStorageLabel?.let { v -> it[temporaryStorageLabel] = v }
                when {
                    request.clearSourceSharedItemId -> it[sourceSharedItemId] = null
                    request.sourceSharedItemId != null -> it[sourceSharedItemId] = sourceSharedItemUUID
                }
                when {
                    request.clearResponsibleUserId -> it[responsibleUserId] = null
                    request.responsibleUserId != null -> it[responsibleUserId] = responsibleUUID
                }
                purchaseDate?.let { v -> it[this.purchaseDate] = v }
                it[updatedAt] = now
            }

            request.customFields?.let { replaceCustomFields(itemId, it) }
            val nextCondition = request.condition ?: previousCondition
            if (nextCondition != previousCondition) {
                ItemConditionLog.insert {
                    it[this.itemId] = itemId
                    it[this.previousCondition] = previousCondition
                    it[this.newCondition] = nextCondition
                    it[this.reportedByUserId] = updatedByUserId
                    it[this.reportedAt] = now
                    it[this.notes] = request.notes
                }
            }
            upsertResponsibleAssignment(
                itemId = itemId,
                previousResponsibleUserId = previousResponsibleUserId,
                nextResponsibleUserId = nextResponsibleUserId,
                assignedByUserId = updatedByUserId,
                reason = "RESPONSIBLE_USER_CHANGED",
                notes = request.notes
            )

            val updated = Items.selectAll()
                .where { Items.id eq itemId }
                .first()

            Result.success(toItemResponse(updated))
        }
    }

    fun getItemAssignments(
        itemId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<ItemAssignmentListResponse> {
        return transaction {
            getItem(itemId, tuntasId, requestingUserId).getOrElse {
                return@transaction Result.failure(it)
            }
            val assignments = ItemAssignments.selectAll()
                .where { ItemAssignments.itemId eq itemId }
                .orderBy(ItemAssignments.assignedAt to SortOrder.DESC)
                .map { row ->
                    ItemAssignmentResponse(
                        id = row[ItemAssignments.id].toString(),
                        itemId = row[ItemAssignments.itemId].toString(),
                        assignedToUserId = row[ItemAssignments.assignedToUserId].toString(),
                        assignedToUserName = userDisplayName(row[ItemAssignments.assignedToUserId]),
                        assignedByUserId = row[ItemAssignments.assignedByUserId]?.toString(),
                        assignedByUserName = userDisplayName(row[ItemAssignments.assignedByUserId]),
                        assignedAt = row[ItemAssignments.assignedAt].toString(),
                        unassignedAt = row[ItemAssignments.unassignedAt]?.toString(),
                        reason = row[ItemAssignments.reason],
                        notes = row[ItemAssignments.notes]
                    )
                }
            Result.success(ItemAssignmentListResponse(assignments, assignments.size))
        }
    }

    fun getItemConditionLog(
        itemId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<ItemConditionLogListResponse> {
        return transaction {
            getItem(itemId, tuntasId, requestingUserId).getOrElse {
                return@transaction Result.failure(it)
            }
            val entries = ItemConditionLog.selectAll()
                .where { ItemConditionLog.itemId eq itemId }
                .orderBy(ItemConditionLog.reportedAt to SortOrder.DESC)
                .map { row ->
                    ItemConditionLogResponse(
                        id = row[ItemConditionLog.id].toString(),
                        itemId = row[ItemConditionLog.itemId].toString(),
                        previousCondition = row[ItemConditionLog.previousCondition],
                        newCondition = row[ItemConditionLog.newCondition],
                        reportedByUserId = row[ItemConditionLog.reportedByUserId]?.toString(),
                        reportedByUserName = userDisplayName(row[ItemConditionLog.reportedByUserId]),
                        reportedAt = row[ItemConditionLog.reportedAt].toString(),
                        notes = row[ItemConditionLog.notes]
                    )
                }
            Result.success(ItemConditionLogListResponse(entries, entries.size))
        }
    }

    fun deleteItem(
        itemId: UUID,
        tuntasId: UUID
    ): Result<Unit> {
        return transaction {
            val existing = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            if (existing[Items.status] == "INACTIVE") {
                return@transaction Result.failure(Exception("Item is already inactive"))
            }

            val hasActiveReservations = Reservations.selectAll()
                .where {
                    (Reservations.itemId eq itemId) and
                        (Reservations.tuntasId eq tuntasId) and
                        (Reservations.status inList listOf("PENDING", "APPROVED", "ACTIVE"))
                }
                .firstOrNull() != null

            if (hasActiveReservations) {
                return@transaction Result.failure(Exception("Item cannot be deactivated while it has active reservations"))
            }

            deleteManagedUpload(existing[Items.photoUrl], "/uploads/images/", "uploads/images")

            Items.update({ (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }) {
                it[status] = "INACTIVE"
            }

            Result.success(Unit)
        }
    }

    // Tuntininkas, Tuntininko pavaduotojas, Inventorininkas can see all statuses
    private fun userCanSeeAllStatuses(userId: UUID, tuntasId: UUID): Boolean {
        return userCanManageAllInventory(userId, tuntasId)
    }

    private fun userCanManageAllInventory(userId: UUID, tuntasId: UUID): Boolean {
        return UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull()) and
                        (Roles.name inList listOf(
                            "Tuntininkas",
                            "Tuntininko pavaduotojas",
                            "Inventorininkas"
                        ))
            }
            .firstOrNull() != null
    }

    private fun userVisibleUnitIds(userId: UUID, tuntasId: UUID): Set<UUID> {
        val leadershipUnitIds = UserLeadershipRoles
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull()) and
                        (UserLeadershipRoles.organizationalUnitId.isNotNull())
            }
            .mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }
            .toSet()

        val memberUnitIds = UnitAssignments
            .selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }
            .map { it[UnitAssignments.organizationalUnitId] }
            .toSet()

        return leadershipUnitIds + memberUnitIds
    }

    private fun toItemResponse(row: ResultRow): ItemResponse {
        val custodianId = row[Items.custodianId]
        val custodianName = custodianId?.let {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq it }
                .firstOrNull()
                ?.get(OrganizationalUnits.name)
        }
        val createdByUserName = row[Items.createdByUserId]?.let { userId ->
            userDisplayName(userId)
        }
        val responsibleUserName = userDisplayName(row[Items.responsibleUserId])

        val quantityBreakdown = if (custodianId == null) {
            Items.selectAll()
                .where {
                    (Items.sourceSharedItemId eq row[Items.id]) and
                        (Items.status eq "ACTIVE")
                }
                .mapNotNull { linked ->
                    val linkedCustodianId = linked[Items.custodianId] ?: return@mapNotNull null
                    val holderName = OrganizationalUnits.selectAll()
                        .where { OrganizationalUnits.id eq linkedCustodianId }
                        .firstOrNull()
                        ?.get(OrganizationalUnits.name)
                        ?: return@mapNotNull null
                    lt.skautai.models.responses.ItemDistributionResponse(
                        holderName = holderName,
                        quantity = linked[Items.quantity]
                    )
                }
        } else {
            emptyList()
        }
        val totalQuantityAcrossCustodians = row[Items.quantity] + quantityBreakdown.sumOf { it.quantity }
        val locationId = row[Items.locationId]
        val locationRows = if (locationId != null) {
            Locations.selectAll()
                .where { Locations.tuntasId eq row[Items.tuntasId] }
                .toList()
        } else {
            emptyList()
        }
        val locationNodes = locationRows.associate { it[Locations.id] to it.toLocationNodeData() }
        val locationName = locationId?.let { id -> locationNodes[id]?.name }
        val locationPath = locationId?.let { id -> buildLocationPath(id, locationNodes) }
        val customFields = ItemCustomFields.selectAll()
            .where { ItemCustomFields.itemId eq row[Items.id] }
            .orderBy(ItemCustomFields.fieldName to SortOrder.ASC)
            .map {
                ItemCustomFieldResponse(
                    id = it[ItemCustomFields.id].toString(),
                    fieldName = it[ItemCustomFields.fieldName],
                    fieldValue = it[ItemCustomFields.fieldValue]
                )
            }

        return ItemResponse(
            id = row[Items.id].toString(),
            qrToken = row[Items.qrToken],
            tuntasId = row[Items.tuntasId].toString(),
            custodianId = custodianId?.toString(),
            custodianName = custodianName,
            origin = row[Items.origin],
            name = row[Items.name],
            description = row[Items.description],
                type = row[Items.type],
                category = row[Items.category],
            condition = row[Items.condition],
            quantity = row[Items.quantity],
            locationId = locationId?.toString(),
            locationName = locationName,
            locationPath = locationPath,
              temporaryStorageLabel = row[Items.temporaryStorageLabel],
              sourceSharedItemId = row[Items.sourceSharedItemId]?.toString(),
              responsibleUserId = row[Items.responsibleUserId]?.toString(),
              responsibleUserName = responsibleUserName,
              createdByUserId = row[Items.createdByUserId]?.toString(),
              createdByUserName = createdByUserName,
              photoUrl = row[Items.photoUrl],
            purchaseDate = row[Items.purchaseDate]?.toString(),
            purchasePrice = row[Items.purchasePrice]?.toDouble(),
            notes = row[Items.notes],
            customFields = customFields,
            quantityBreakdown = quantityBreakdown,
            totalQuantityAcrossCustodians = totalQuantityAcrossCustodians,
            status = row[Items.status],
            createdAt = row[Items.createdAt].toString(),
            updatedAt = row[Items.updatedAt].toString()
        )
    }

    private fun validateCustomFields(fields: List<ItemCustomFieldRequest>): Exception? {
        val names = mutableSetOf<String>()
        fields.forEach { field ->
            val normalizedName = field.fieldName.trim()
            if (normalizedName.isBlank()) {
                return Exception("Custom field name is required")
            }
            if (normalizedName.length > 100) {
                return Exception("Custom field name must be at most 100 characters")
            }
            if (!names.add(normalizedName.lowercase())) {
                return Exception("Duplicate custom field name")
            }
        }
        return null
    }

    private fun replaceCustomFields(itemId: UUID, fields: List<ItemCustomFieldRequest>) {
        ItemCustomFields.deleteWhere { ItemCustomFields.itemId eq itemId }
        fields.forEach { field ->
            val normalizedName = field.fieldName.trim()
            ItemCustomFields.insert {
                it[this.itemId] = itemId
                it[fieldName] = normalizedName
                it[fieldValue] = field.fieldValue?.takeIf { value -> value.isNotBlank() }
            }
        }
    }

    private fun validateResponsibleUser(userId: UUID?, tuntasId: UUID): Exception? {
        if (userId == null) return null
        Users.selectAll()
            .where {
                Users.id eq userId
            }
            .firstOrNull()
            ?: return Exception("Responsible user not found")

        val belongsToTuntas = UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                    UserTuntasMemberships.leftAt.isNull()
            }
            .firstOrNull() != null

        return if (!belongsToTuntas) {
            Exception("Responsible user must belong to this tuntas")
        } else {
            null
        }
    }

    private fun upsertResponsibleAssignment(
        itemId: UUID,
        previousResponsibleUserId: UUID?,
        nextResponsibleUserId: UUID?,
        assignedByUserId: UUID?,
        reason: String,
        notes: String?
    ) {
        if (previousResponsibleUserId == nextResponsibleUserId) return
        val now = Clock.System.now()
        ItemAssignments.update({
            (ItemAssignments.itemId eq itemId) and
                ItemAssignments.unassignedAt.isNull()
        }) {
            it[unassignedAt] = now
        }
        if (nextResponsibleUserId != null) {
            ItemAssignments.insert {
                it[this.itemId] = itemId
                it[assignedToUserId] = nextResponsibleUserId
                it[this.assignedByUserId] = assignedByUserId
                it[assignedAt] = now
                it[this.reason] = reason
                it[this.notes] = notes
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

    private fun validateItemLocation(
        locationId: UUID?,
        tuntasId: UUID,
        itemType: String,
        custodianId: UUID?,
        ownerUserId: UUID?
    ): Exception? {
        if (locationId == null) return null
        val locationRows = Locations.selectAll()
            .where { Locations.tuntasId eq tuntasId }
            .toList()
        val location = locationRows.firstOrNull { it[Locations.id] == locationId }
            ?: return Exception("Location not found")
        return when {
            itemType == "INDIVIDUAL" -> {
                if (location[Locations.visibility] != "PRIVATE" || location[Locations.ownerUserId] != ownerUserId) {
                    Exception("Personal items can only use your private locations")
                } else null
            }
            custodianId != null -> {
                if (location[Locations.visibility] != "UNIT" || location[Locations.ownerUnitId] != custodianId) {
                    Exception("Unit inventory items can only use their unit locations")
                } else null
            }
            else -> {
                if (location[Locations.visibility] != "PUBLIC") {
                    Exception("Shared inventory items can only use public locations")
                } else null
            }
        }
    }

    private fun deleteManagedUpload(url: String?, prefix: String, baseDir: String) {
        val fileName = url?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return
        val root = File(baseDir).canonicalFile
        val candidate = File(root, fileName).canonicalFile
        if (candidate.toPath().startsWith(root.toPath()) && candidate.exists()) {
            candidate.delete()
        }
    }
}
