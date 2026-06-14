package lt.skautai.services

import lt.skautai.database.tables.Items
import lt.skautai.database.tables.ItemAssignments
import lt.skautai.database.tables.ItemConditionLog
import lt.skautai.database.tables.ItemHistory
import lt.skautai.database.tables.ItemCustomFields
import lt.skautai.database.tables.ItemTransfers
import lt.skautai.database.tables.InventoryKits
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.ConsumeItemRequest
import lt.skautai.models.requests.ReviewItemAdditionRequest
import lt.skautai.plugins.ResolvedPermission
import lt.skautai.models.requests.ItemCustomFieldRequest
import lt.skautai.models.requests.ReturnItemToSharedRequest
import lt.skautai.models.requests.RestockItemRequest
import lt.skautai.models.requests.TransferItemToUnitRequest
import lt.skautai.models.requests.UpdateItemRequest
import lt.skautai.models.requests.WriteOffItemRequest
import lt.skautai.models.responses.ItemCustomFieldResponse
import lt.skautai.models.responses.ItemAssignmentListResponse
import lt.skautai.models.responses.ItemAssignmentResponse
import lt.skautai.models.responses.ItemConditionLogListResponse
import lt.skautai.models.responses.ItemConditionLogResponse
import lt.skautai.models.responses.ItemHistoryListResponse
import lt.skautai.models.responses.ItemHistoryResponse
import lt.skautai.models.responses.ItemListResponse
import lt.skautai.models.responses.ItemResponse
import lt.skautai.models.responses.ItemTransferListResponse
import lt.skautai.models.responses.ItemTransferResponse
import lt.skautai.util.UploadStorage
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
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
        sharedOnly: Boolean = false,
        createdByUserId: String? = null,
        updatedAfter: Instant? = null
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
            }

            if (!canSeeAll) {
                val reviewUnitIds = userLeadershipUnitIds(requestingUserId, tuntasId)
                query = query.andWhere {
                    val activeClause = Items.status eq "ACTIVE"
                    val submitterClause = Items.submittedByUserId eq requestingUserId
                    if (reviewUnitIds.isEmpty()) {
                        activeClause or submitterClause
                    } else {
                        val reviewUnitList = reviewUnitIds.toList()
                        activeClause or submitterClause or
                            (Items.custodianId inList reviewUnitList) or
                            (Items.custodianId.isNull() and (Items.targetScope eq "SHARED"))
                    }
                }
            }

            if (updatedAfter == null) {
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
                createdByUserId?.let {
                    val uuid = try { UUID.fromString(it) } catch (e: Exception) { null }
                    if (uuid != null) query = query.andWhere { Items.createdByUserId eq uuid }
                }
                status?.let {
                    if (canSeeAll) {
                        query = query.andWhere { Items.status eq it }
                    } else if (it != "ACTIVE") {
                        return@transaction Result.success(ItemListResponse(items = emptyList(), total = 0))
                    }
                }
            }
            updatedAfter?.let { since ->
                query = query.andWhere { Items.updatedAt greater since }
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
                val isSubmitter = item[Items.submittedByUserId] == requestingUserId
                val reviewUnitIds = userLeadershipUnitIds(requestingUserId, tuntasId)
                val custodianIdForCheck = item[Items.custodianId]
                val isReviewer = reviewUnitIds.isNotEmpty() &&
                    (custodianIdForCheck != null && custodianIdForCheck in reviewUnitIds ||
                     custodianIdForCheck == null && item[Items.targetScope] == "SHARED")
                if (!isSubmitter && !isReviewer) {
                    return@transaction Result.failure(Exception("Item not found"))
                }
            }
            val custodianId = item[Items.custodianId]
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

            validateInventoryCategory(request.category)?.let {
                return@transaction Result.failure(it)
            }

            validateItemCondition(request.condition)?.let {
                return@transaction Result.failure(it)
            }

            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }
            validateConsumableFields(request.isConsumable, request.unitOfMeasure, request.minimumQuantity)?.let {
                return@transaction Result.failure(it)
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
                            it[isConsumable] = duplicateItem[Items.isConsumable] || request.isConsumable
                            it[unitOfMeasure] = request.unitOfMeasure.trim().ifBlank { duplicateItem[Items.unitOfMeasure] }
                            request.minimumQuantity?.let { value -> it[minimumQuantity] = value }
                            it[updatedAt] = now
                        }
                        recordItemHistory(
                            itemId = duplicateItem[Items.id],
                            eventType = "RESTOCKED",
                            quantityChange = request.quantity,
                            performedByUserId = createdByUserId,
                            requisitionId = null,
                            notes = request.notes ?: "Papildyta kuriant inventoriaus įrašą",
                            createdAt = now
                        )
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
                it[isConsumable] = request.isConsumable
                it[unitOfMeasure] = request.unitOfMeasure.trim().ifBlank { "vnt." }
                it[minimumQuantity] = request.minimumQuantity
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
                it[submittedByUserId] = if (isPendingApproval) createdByUserId else null
                it[targetScope] = if (isPendingApproval) {
                    if (custodianUUID == null) "SHARED" else "UNIT"
                } else null
                it[createdAt] = now
                it[updatedAt] = now
            } get Items.id

            replaceCustomFields(itemId, request.customFields)
            recordItemHistory(
                itemId = itemId,
                eventType = "CREATED",
                quantityChange = request.quantity,
                performedByUserId = createdByUserId,
                requisitionId = null,
                notes = request.notes ?: "Sukurtas inventoriaus įrašas",
                createdAt = now
            )
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

    fun restockItem(
        itemId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: RestockItemRequest
    ): Result<ItemResponse> {
        return transaction {
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }
            val item = Items.selectAll()
                .where {
                    (Items.id eq itemId) and
                        (Items.tuntasId eq tuntasId) and
                        (Items.status eq "ACTIVE")
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            val purchaseDate = request.purchaseDate?.let {
                try { LocalDate.parse(it) } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }
            val now = Clock.System.now()
            Items.update({ Items.id eq itemId }) {
                it[quantity] = item[Items.quantity] + request.quantity
                request.purchaseDate?.let { _ -> it[Items.purchaseDate] = purchaseDate }
                request.purchasePrice?.let { value -> it[purchasePrice] = value.toBigDecimal() }
                if (!request.notes.isNullOrBlank()) it[notes] = request.notes
                it[updatedAt] = now
            }
            InventoryKitService.syncMembershipAfterItemQuantityChange(itemId, item[Items.quantity] + request.quantity)
            recordItemHistory(
                itemId = itemId,
                eventType = "MANUAL_RESTOCKED",
                quantityChange = request.quantity,
                performedByUserId = userId,
                notes = request.notes ?: "Papildyta tiesiogiai daikto korteleje",
                createdAt = now
            )
            Result.success(toItemResponse(Items.selectAll().where { Items.id eq itemId }.first()))
        }
    }

    fun consumeItem(
        itemId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: ConsumeItemRequest
    ): Result<ItemResponse> {
        return transaction {
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }
            val item = Items.selectAll()
                .where {
                    (Items.id eq itemId) and
                        (Items.tuntasId eq tuntasId) and
                        (Items.status eq "ACTIVE")
                }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            if (!item[Items.isConsumable]) {
                return@transaction Result.failure(Exception("Only consumable items can be consumed"))
            }
            if (request.quantity > item[Items.quantity]) {
                return@transaction Result.failure(Exception("Consumed quantity cannot exceed available quantity"))
            }

            val remaining = item[Items.quantity] - request.quantity
            val now = Clock.System.now()
            Items.update({ Items.id eq itemId }) {
                it[quantity] = remaining
                it[updatedAt] = now
            }
            InventoryKitService.syncMembershipAfterItemQuantityChange(itemId, remaining)
            recordItemHistory(
                itemId = itemId,
                eventType = "CONSUMED",
                quantityChange = -request.quantity,
                performedByUserId = userId,
                notes = request.notes,
                createdAt = now
            )
            Result.success(toItemResponse(Items.selectAll().where { Items.id eq itemId }.first()))
        }
    }

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
                validateInventoryCategory(it)?.let { error -> return@transaction Result.failure(error) }
            }

            request.condition?.let {
                validateItemCondition(it)?.let { error -> return@transaction Result.failure(error) }
            }
            validateConsumableFields(
                isConsumable = request.isConsumable ?: existing[Items.isConsumable],
                unitOfMeasure = request.unitOfMeasure ?: existing[Items.unitOfMeasure],
                minimumQuantity = if (request.clearMinimumQuantity) null else request.minimumQuantity ?: existing[Items.minimumQuantity]
            )?.let { return@transaction Result.failure(it) }

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
            val activeKit = InventoryKitService.activeKitForItem(itemId)
            if (activeKit != null) {
                val changesKitManagedLocation = request.clearLocationId ||
                    request.locationId != null ||
                    request.temporaryStorageLabel != null
                val changesKitScope = request.clearCustodianId || request.custodianId != null
                if (changesKitManagedLocation || changesKitScope) {
                    return@transaction Result.failure(
                        Exception("Item is inside an inventory kit. Update the kit location or remove the item from the kit first.")
                    )
                }
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
            val previousQuantity = existing[Items.quantity]
            val previousResponsibleUserId = existing[Items.responsibleUserId]
            val now = Clock.System.now()

            Items.update({ (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }) {
                request.name?.let { v -> it[name] = v }
                request.description?.let { v -> it[description] = v }
                request.type?.let { v -> it[type] = v }
                request.category?.let { v -> it[category] = v }
                request.condition?.let { v -> it[condition] = v }
                request.quantity?.let { v -> it[quantity] = v }
                request.isConsumable?.let { v -> it[isConsumable] = v }
                request.unitOfMeasure?.let { v -> it[unitOfMeasure] = v.trim().ifBlank { "vnt." } }
                when {
                    request.clearMinimumQuantity -> it[minimumQuantity] = null
                    request.minimumQuantity != null -> it[minimumQuantity] = request.minimumQuantity
                }
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
            request.quantity?.takeIf { it != previousQuantity }?.let { nextQuantity ->
                InventoryKitService.syncMembershipAfterItemQuantityChange(itemId, nextQuantity)
                recordItemHistory(
                    itemId = itemId,
                    eventType = "QUANTITY_ADJUSTED",
                    quantityChange = nextQuantity - previousQuantity,
                    performedByUserId = updatedByUserId,
                    notes = request.notes ?: "Kiekis pakoreguotas rankiniu budu",
                    createdAt = now
                )
            }
            if (request.status == "INACTIVE") {
                InventoryKitService.syncMembershipAfterItemQuantityChange(itemId, 0)
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

    fun transferSharedItemToUnit(
        itemId: UUID,
        tuntasId: UUID,
        request: TransferItemToUnitRequest,
        initiatedByUserId: UUID
    ): Result<ItemResponse> {
        return transaction {
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val targetUnitId = try {
                UUID.fromString(request.targetUnitId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid target unit ID"))
            }

            OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq targetUnitId) and
                        (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Target unit not found in this tuntas"))

            val sharedItem = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Shared item not found"))

            if (sharedItem[Items.custodianId] != null || sharedItem[Items.type] == "INDIVIDUAL") {
                return@transaction Result.failure(Exception("Only shared tuntas inventory can be transferred"))
            }
            if (sharedItem[Items.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Only active shared inventory can be transferred"))
            }
            if (sharedItem[Items.quantity] < request.quantity) {
                return@transaction Result.failure(Exception("Not enough quantity left"))
            }

            val now = Clock.System.now()
            val remaining = sharedItem[Items.quantity] - request.quantity
            Items.update({ Items.id eq sharedItem[Items.id] }) {
                it[quantity] = remaining
                it[status] = if (remaining == 0) "INACTIVE" else "ACTIVE"
                it[updatedAt] = now
            }
            InventoryKitService.syncMembershipAfterItemQuantityChange(sharedItem[Items.id], remaining)

            val existingUnitItem = Items.selectAll()
                .where {
                    (Items.tuntasId eq tuntasId) and
                        (Items.custodianId eq targetUnitId) and
                        (Items.sourceSharedItemId eq sharedItem[Items.id]) and
                        (Items.status eq "ACTIVE")
                }
                .forUpdate()
                .firstOrNull()

            val unitItemId = if (existingUnitItem != null) {
                Items.update({ Items.id eq existingUnitItem[Items.id] }) {
                    it[quantity] = existingUnitItem[Items.quantity] + request.quantity
                    it[updatedAt] = now
                }
                existingUnitItem[Items.id]
            } else {
                Items.insert {
                    it[this.tuntasId] = tuntasId
                    it[custodianId] = targetUnitId
                    it[origin] = "TRANSFERRED_FROM_TUNTAS"
                    it[name] = sharedItem[Items.name]
                    it[description] = sharedItem[Items.description]
                    it[type] = "COLLECTIVE"
                    it[category] = sharedItem[Items.category]
                    it[condition] = sharedItem[Items.condition]
                    it[quantity] = request.quantity
                    it[isConsumable] = sharedItem[Items.isConsumable]
                    it[unitOfMeasure] = sharedItem[Items.unitOfMeasure]
                    it[minimumQuantity] = sharedItem[Items.minimumQuantity]
                    it[locationId] = null
                    it[temporaryStorageLabel] = null
                    it[sourceSharedItemId] = sharedItem[Items.id]
                    it[createdByUserId] = initiatedByUserId
                    it[qrToken] = UUID.randomUUID().toString()
                    it[photoUrl] = sharedItem[Items.photoUrl]
                    it[purchaseDate] = sharedItem[Items.purchaseDate]
                    it[purchasePrice] = sharedItem[Items.purchasePrice]
                    it[notes] = request.notes ?: sharedItem[Items.notes]
                    it[status] = "ACTIVE"
                    it[createdAt] = now
                    it[updatedAt] = now
                } get Items.id
            }

            ItemTransfers.insert {
                it[ItemTransfers.itemId] = sharedItem[Items.id]
                it[fromCustodianId] = null
                it[toCustodianId] = targetUnitId
                it[ItemTransfers.initiatedByUserId] = initiatedByUserId
                it[approvedByUserId] = initiatedByUserId
                it[notes] = request.notes
                it[status] = "COMPLETED"
                it[createdAt] = now
                it[completedAt] = now
            }
            recordItemHistory(
                itemId = sharedItem[Items.id],
                eventType = "TRANSFERRED_TO_UNIT",
                quantityChange = -request.quantity,
                performedByUserId = initiatedByUserId,
                notes = request.notes ?: "Perduota vienetui",
                createdAt = now
            )
            recordItemHistory(
                itemId = unitItemId,
                eventType = "RECEIVED_FROM_SHARED",
                quantityChange = request.quantity,
                performedByUserId = initiatedByUserId,
                notes = request.notes ?: "Gauta is bendro inventoriaus",
                createdAt = now
            )

            val updatedUnitItem = Items.selectAll()
                .where { Items.id eq unitItemId }
                .first()

            Result.success(toItemResponse(updatedUnitItem))
        }
    }

    fun returnTransferredItemToShared(
        itemId: UUID,
        tuntasId: UUID,
        request: ReturnItemToSharedRequest,
        initiatedByUserId: UUID
    ): Result<ItemResponse> {
        return transaction {
            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val unitItem = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Unit item not found"))

            val sourceSharedItemId = unitItem[Items.sourceSharedItemId]
                ?: return@transaction Result.failure(Exception("Item is not linked to shared inventory"))
            val fromCustodianId = unitItem[Items.custodianId]
                ?: return@transaction Result.failure(Exception("Only unit inventory can be returned"))

            if (unitItem[Items.origin] != "TRANSFERRED_FROM_TUNTAS") {
                return@transaction Result.failure(Exception("Only transferred shared inventory can be returned"))
            }
            if (unitItem[Items.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Only active unit inventory can be returned"))
            }
            if (unitItem[Items.quantity] < request.quantity) {
                return@transaction Result.failure(Exception("Return quantity exceeds unit inventory quantity"))
            }

            val sharedItem = Items.selectAll()
                .where { (Items.id eq sourceSharedItemId) and (Items.tuntasId eq tuntasId) }
                .forUpdate()
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Source shared item not found"))

            val now = Clock.System.now()
            Items.update({ Items.id eq sharedItem[Items.id] }) {
                it[quantity] = sharedItem[Items.quantity] + request.quantity
                it[status] = "ACTIVE"
                it[updatedAt] = now
            }

            val remaining = unitItem[Items.quantity] - request.quantity
            Items.update({ Items.id eq unitItem[Items.id] }) {
                it[quantity] = remaining
                it[status] = if (remaining == 0) "INACTIVE" else "ACTIVE"
                it[updatedAt] = now
            }
            InventoryKitService.syncMembershipAfterItemQuantityChange(unitItem[Items.id], remaining)

            ItemTransfers.insert {
                it[ItemTransfers.itemId] = sharedItem[Items.id]
                it[ItemTransfers.fromCustodianId] = fromCustodianId
                it[toCustodianId] = null
                it[ItemTransfers.initiatedByUserId] = initiatedByUserId
                it[approvedByUserId] = initiatedByUserId
                it[notes] = request.notes
                it[status] = "COMPLETED"
                it[createdAt] = now
                it[completedAt] = now
            }
            recordItemHistory(
                itemId = unitItem[Items.id],
                eventType = "RETURNED_TO_SHARED",
                quantityChange = -request.quantity,
                performedByUserId = initiatedByUserId,
                notes = request.notes ?: "Grazinta i bendra inventoriu",
                createdAt = now
            )
            recordItemHistory(
                itemId = sharedItem[Items.id],
                eventType = "RECEIVED_FROM_UNIT",
                quantityChange = request.quantity,
                performedByUserId = initiatedByUserId,
                notes = request.notes ?: "Gauta atgal is vieneto",
                createdAt = now
            )

            val updatedUnitItem = Items.selectAll()
                .where { Items.id eq unitItem[Items.id] }
                .first()

            Result.success(toItemResponse(updatedUnitItem))
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

    fun getItemHistory(
        itemId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<ItemHistoryListResponse> {
        return transaction {
            getVisibleItemRow(itemId, tuntasId, requestingUserId)
                ?: return@transaction Result.failure(Exception("Item not found"))

            val entries = ItemHistory.selectAll()
                .where { ItemHistory.itemId eq itemId }
                .orderBy(ItemHistory.createdAt to SortOrder.DESC)
                .map { row ->
                    ItemHistoryResponse(
                        id = row[ItemHistory.id].toString(),
                        itemId = row[ItemHistory.itemId].toString(),
                        eventType = row[ItemHistory.eventType],
                        quantityChange = row[ItemHistory.quantityChange],
                        performedByUserId = row[ItemHistory.performedByUserId]?.toString(),
                        performedByUserName = userDisplayName(row[ItemHistory.performedByUserId]),
                        requisitionId = row[ItemHistory.requisitionId]?.toString(),
                        notes = row[ItemHistory.notes],
                        createdAt = row[ItemHistory.createdAt].toString()
                    )
                }
            Result.success(ItemHistoryListResponse(entries, entries.size))
        }
    }

    companion object {
        fun recordItemHistory(
            itemId: UUID,
            eventType: String,
            quantityChange: Int?,
            performedByUserId: UUID?,
            requisitionId: UUID? = null,
            notes: String?,
            createdAt: kotlinx.datetime.Instant = Clock.System.now()
        ) {
            ItemHistory.insert {
                it[this.itemId] = itemId
                it[this.eventType] = eventType
                it[this.quantityChange] = quantityChange
                it[this.performedByUserId] = performedByUserId
                it[this.requisitionId] = requisitionId
                it[this.notes] = notes
                it[this.createdAt] = createdAt
            }
        }
    }

    private fun getVisibleItemRow(itemId: UUID, tuntasId: UUID, requestingUserId: UUID): ResultRow? {
        val canSeeAll = userCanSeeAllStatuses(requestingUserId, tuntasId)
        val canSeeAllInventory = userCanManageAllInventory(requestingUserId, tuntasId)
        val item = Items.selectAll()
            .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
            .firstOrNull() ?: return null
        if (!canSeeAll && item[Items.status] != "ACTIVE") return null
        val custodianId = item[Items.custodianId]
        if (!canSeeAllInventory && custodianId != null && custodianId !in userVisibleUnitIds(requestingUserId, tuntasId)) {
            return null
        }
        return item
    }

    fun getItemTransfers(
        itemId: UUID,
        tuntasId: UUID,
        requestingUserId: UUID
    ): Result<ItemTransferListResponse> {
        return transaction {
            val item = getItem(itemId, tuntasId, requestingUserId).getOrElse {
                return@transaction Result.failure(it)
            }
            val linkedItemIds = Items.selectAll()
                .where {
                    ((Items.id eq itemId) or (Items.sourceSharedItemId eq itemId)) and
                        (Items.tuntasId eq tuntasId)
                }
                .map { it[Items.id] }
                .toSet() + item.sourceSharedItemId?.let { UUID.fromString(it) }

            val transfers = ItemTransfers.selectAll()
                .where { ItemTransfers.itemId inList linkedItemIds.filterNotNull() }
                .orderBy(ItemTransfers.createdAt to SortOrder.DESC)
                .map { row ->
                    ItemTransferResponse(
                        id = row[ItemTransfers.id].toString(),
                        itemId = row[ItemTransfers.itemId].toString(),
                        fromCustodianId = row[ItemTransfers.fromCustodianId]?.toString(),
                        fromCustodianName = orgUnitName(row[ItemTransfers.fromCustodianId]) ?: "Bendras sandėlis",
                        toCustodianId = row[ItemTransfers.toCustodianId]?.toString(),
                        toCustodianName = orgUnitName(row[ItemTransfers.toCustodianId]) ?: "Bendras sandėlis",
                        initiatedByUserId = row[ItemTransfers.initiatedByUserId]?.toString(),
                        initiatedByUserName = userDisplayName(row[ItemTransfers.initiatedByUserId]),
                        approvedByUserId = row[ItemTransfers.approvedByUserId]?.toString(),
                        approvedByUserName = userDisplayName(row[ItemTransfers.approvedByUserId]),
                        notes = row[ItemTransfers.notes],
                        status = row[ItemTransfers.status],
                        createdAt = row[ItemTransfers.createdAt].toString(),
                        completedAt = row[ItemTransfers.completedAt]?.toString()
                    )
                }
            Result.success(ItemTransferListResponse(transfers, transfers.size))
        }
    }

    fun deleteItem(
        itemId: UUID,
        tuntasId: UUID,
        userId: UUID? = null
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

            UploadStorage.deleteManagedUpload(existing[Items.photoUrl], UploadStorage.imageUrlPrefix)

            Items.update({ (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }) {
                it[status] = "INACTIVE"
            }
            recordItemHistory(
                itemId = itemId,
                eventType = "DEACTIVATED",
                quantityChange = null,
                performedByUserId = userId,
                notes = "Daiktas deaktyvuotas",
                createdAt = Clock.System.now()
            )

            Result.success(Unit)
        }
    }

    fun writeOffItem(
        itemId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: WriteOffItemRequest
    ): Result<ItemResponse> {
        return transaction {
            val reason = request.reason.trim()
            if (reason.isBlank()) {
                return@transaction Result.failure(Exception("Write-off reason is required"))
            }

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
                return@transaction Result.failure(Exception("Item cannot be written off while it has active reservations"))
            }

            val previousCondition = existing[Items.condition]
            val now = Clock.System.now()
            Items.update({ (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }) {
                it[condition] = "WRITTEN_OFF"
                it[status] = "INACTIVE"
                it[updatedAt] = now
            }
            if (previousCondition != "WRITTEN_OFF") {
                ItemConditionLog.insert {
                    it[this.itemId] = itemId
                    it[this.previousCondition] = previousCondition
                    it[this.newCondition] = "WRITTEN_OFF"
                    it[this.reportedByUserId] = userId
                    it[this.reportedAt] = now
                    it[this.notes] = reason
                }
            }
            recordItemHistory(
                itemId = itemId,
                eventType = "WRITTEN_OFF",
                quantityChange = null,
                performedByUserId = userId,
                notes = reason,
                createdAt = now
            )

            Result.success(toItemResponse(Items.selectAll().where { Items.id eq itemId }.first()))
        }
    }

    fun reviewItemAddition(
        itemId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: ReviewItemAdditionRequest,
        reviewerPermissions: List<ResolvedPermission>
    ): Result<ItemResponse> {
        if (request.decision !in listOf("APPROVED", "REJECTED")) {
            return Result.failure(Exception("Decision must be APPROVED or REJECTED"))
        }
        if (request.decision == "REJECTED" && request.rejectionReason.isNullOrBlank()) {
            return Result.failure(Exception("Rejection reason is required"))
        }

        return transaction {
            val item = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            if (item[Items.status] != "PENDING_APPROVAL") {
                return@transaction Result.failure(Exception("Item is not pending approval"))
            }

            val scope = item[Items.targetScope]
            val custodianId = item[Items.custodianId]

            val canReview = if (scope == "SHARED") {
                reviewerPermissions.any { it.permissionName == "items.review" && it.scope == "ALL" }
            } else {
                reviewerPermissions.any { it.permissionName == "items.review" && it.scope == "ALL" } ||
                    (custodianId != null && reviewerPermissions.any {
                        it.permissionName == "items.review" && it.scope == "OWN_UNIT" &&
                            custodianId in it.userOrgUnitIds
                    })
            }

            if (!canReview) {
                return@transaction Result.failure(Exception("Insufficient permissions to review this item"))
            }

            val now = Clock.System.now()
            val newStatus = if (request.decision == "APPROVED") "ACTIVE" else "INACTIVE"

            Items.update({ Items.id eq itemId }) {
                it[status] = newStatus
                it[reviewedByUserId] = reviewerUserId
                it[reviewedAt] = now
                if (request.decision == "REJECTED") {
                    it[rejectionReason] = request.rejectionReason
                }
                it[updatedAt] = now
            }

            recordItemHistory(
                itemId = itemId,
                eventType = request.decision,
                quantityChange = null,
                performedByUserId = reviewerUserId,
                requisitionId = null,
                notes = request.rejectionReason,
                createdAt = now
            )

            val updated = Items.selectAll().where { Items.id eq itemId }.first()
            Result.success(toItemResponse(updated))
        }
    }

    private fun userLeadershipUnitIds(userId: UUID, tuntasId: UUID): Set<UUID> {
        return UserLeadershipRoles
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
        val activeKit = InventoryKitService.activeKitForItem(row[Items.id])
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
            isConsumable = row[Items.isConsumable],
            unitOfMeasure = row[Items.unitOfMeasure],
            minimumQuantity = row[Items.minimumQuantity],
            isLowStock = row[Items.isConsumable] &&
                row[Items.minimumQuantity]?.let { row[Items.quantity] <= it } == true,
            locationId = locationId?.toString(),
            locationName = locationName,
            locationPath = locationPath,
              temporaryStorageLabel = row[Items.temporaryStorageLabel],
            kitId = activeKit?.get(InventoryKits.id)?.toString(),
            kitName = activeKit?.get(InventoryKits.name),
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
            submittedByUserId = row[Items.submittedByUserId]?.toString(),
            submittedByUserName = userDisplayName(row[Items.submittedByUserId]),
            targetScope = row[Items.targetScope],
            reviewedByUserId = row[Items.reviewedByUserId]?.toString(),
            rejectionReason = row[Items.rejectionReason],
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

    private fun validateInventoryCategory(category: String): Exception? {
        val normalized = category.trim()
        if (normalized.isBlank()) return Exception("Inventory category is required")
        if (normalized.length > 30) return Exception("Inventory category must be at most 30 characters")
        return null
    }

    private fun validateConsumableFields(
        isConsumable: Boolean,
        unitOfMeasure: String,
        minimumQuantity: Int?
    ): Exception? {
        val normalizedUnit = unitOfMeasure.trim()
        if (isConsumable && normalizedUnit.isBlank()) return Exception("Unit of measure is required for consumable items")
        if (normalizedUnit.length > 30) return Exception("Unit of measure must be at most 30 characters")
        if (minimumQuantity != null && minimumQuantity < 0) return Exception("Minimum quantity cannot be negative")
        return null
    }

    private fun validateItemCondition(condition: String): Exception? {
        val normalized = condition.trim()
        if (normalized.isBlank()) return Exception("Item condition is required")
        if (normalized.length > 30) return Exception("Item condition must be at most 30 characters")
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

    private fun orgUnitName(orgUnitId: UUID?): String? {
        if (orgUnitId == null) return null
        return OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq orgUnitId }
            .firstOrNull()
            ?.get(OrganizationalUnits.name)
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

}
