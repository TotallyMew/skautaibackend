package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.EventPurchaseItems
import lt.skautai.database.tables.EventPurchases
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.EventInventorySources
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.InventoryListTemplateItems
import lt.skautai.database.tables.InventoryListTemplates
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Locations
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Reservations
import lt.skautai.database.tables.Users
import lt.skautai.models.responses.AppliedTemplateShortageResponse
import lt.skautai.models.responses.AppliedTemplateSourceResponse
import lt.skautai.models.requests.CreateInventoryTemplateRequest
import lt.skautai.models.requests.InventoryTemplateItemRequest
import lt.skautai.models.requests.UpdateInventoryTemplateRequest
import lt.skautai.models.responses.AppliedInventoryTemplateResponse
import lt.skautai.models.responses.AppliedTemplatePurchaseItemResponse
import lt.skautai.models.responses.AppliedTemplateReservedItemResponse
import lt.skautai.models.responses.EventInventoryItemListResponse
import lt.skautai.models.responses.EventInventoryItemResponse
import lt.skautai.models.responses.InventoryTemplateItemResponse
import lt.skautai.models.responses.InventoryTemplateListResponse
import lt.skautai.models.responses.InventoryTemplateResponse
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class InventoryTemplateService {
    fun listTemplates(tuntasId: UUID, eventType: String?): Result<InventoryTemplateListResponse> = transaction {
        var query = InventoryListTemplates.selectAll()
            .where { InventoryListTemplates.tuntasId eq tuntasId }
        eventType?.takeIf { it.isNotBlank() }?.let { type ->
            query = query.andWhere {
                (InventoryListTemplates.eventType eq type) or InventoryListTemplates.eventType.isNull()
            }
        }
        val templates = query
            .orderBy(InventoryListTemplates.name to SortOrder.ASC)
            .map { toTemplateResponse(it) }
        Result.success(InventoryTemplateListResponse(templates, templates.size))
    }

    fun createTemplate(
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateInventoryTemplateRequest
    ): Result<InventoryTemplateResponse> = transaction {
        val name = request.name.trim()
        validateTemplate(name, request.items)?.let { return@transaction Result.failure(it) }
        val id = InventoryListTemplates.insert {
            it[this.tuntasId] = tuntasId
            it[this.name] = name
            it[eventType] = request.eventType?.takeIf { value -> value.isNotBlank() }
            it[this.createdByUserId] = createdByUserId
            it[createdAt] = Clock.System.now()
        } get InventoryListTemplates.id
        replaceItems(id, request.items)
        Result.success(toTemplateResponse(loadTemplate(id, tuntasId)!!))
    }

    fun updateTemplate(
        templateId: UUID,
        tuntasId: UUID,
        request: UpdateInventoryTemplateRequest
    ): Result<InventoryTemplateResponse> = transaction {
        val existing = loadTemplate(templateId, tuntasId)
            ?: return@transaction Result.failure(Exception("Template not found"))
        val nextName = request.name?.trim() ?: existing[InventoryListTemplates.name]
        val nextItems = request.items
        validateTemplate(nextName, nextItems ?: emptyList(), validateItems = nextItems != null)?.let {
            return@transaction Result.failure(it)
        }
        InventoryListTemplates.update({
            (InventoryListTemplates.id eq templateId) and (InventoryListTemplates.tuntasId eq tuntasId)
        }) {
            request.name?.let { _ -> it[name] = nextName }
            request.eventType?.let { value -> it[eventType] = value.takeIf { it.isNotBlank() } }
        }
        nextItems?.let { replaceItems(templateId, it) }
        Result.success(toTemplateResponse(loadTemplate(templateId, tuntasId)!!))
    }

    fun deleteTemplate(templateId: UUID, tuntasId: UUID): Result<Unit> = transaction {
        loadTemplate(templateId, tuntasId)
            ?: return@transaction Result.failure(Exception("Template not found"))
        InventoryListTemplateItems.deleteWhere { InventoryListTemplateItems.templateId eq templateId }
        InventoryListTemplates.deleteWhere {
            (InventoryListTemplates.id eq templateId) and (InventoryListTemplates.tuntasId eq tuntasId)
        }
        Result.success(Unit)
    }

    fun applyTemplateToEvent(
        eventId: UUID,
        tuntasId: UUID,
        createdByUserId: UUID,
        templateId: UUID
    ): Result<EventInventoryItemListResponse> = transaction {
        Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Event not found"))
        loadTemplate(templateId, tuntasId)
            ?: return@transaction Result.failure(Exception("Template not found"))

        val now = Clock.System.now()
        val created = InventoryListTemplateItems.selectAll()
            .where { InventoryListTemplateItems.templateId eq templateId }
            .orderBy(InventoryListTemplateItems.itemName to SortOrder.ASC)
            .map { templateItem ->
                val id = EventInventoryItems.insert {
                    it[this.eventId] = eventId
                    it[itemId] = null
                    it[bucketId] = null
                    it[reservationGroupId] = null
                    it[name] = templateItem[InventoryListTemplateItems.itemName]
                    it[plannedQuantity] = templateItem[InventoryListTemplateItems.quantity]
                    it[availableQuantity] = 0
                    it[needsPurchase] = true
                    it[responsibleUserId] = null
                    it[notes] = templateItem[InventoryListTemplateItems.notes]
                    it[this.createdByUserId] = createdByUserId
                    it[createdAt] = now
                } get EventInventoryItems.id
                toEventInventoryItemResponse(EventInventoryItems.selectAll().where { EventInventoryItems.id eq id }.first())
            }
        Result.success(EventInventoryItemListResponse(created, created.size))
    }

    fun applyTemplateWithReservation(
        eventId: UUID,
        tuntasId: UUID,
        createdByUserId: UUID,
        templateId: UUID
    ): Result<AppliedInventoryTemplateResponse> = transaction {
        val event = Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Event not found"))
        loadTemplate(templateId, tuntasId)
            ?: return@transaction Result.failure(Exception("Template not found"))

        val now = Clock.System.now()
        val reservationGroupId = UUID.randomUUID()
        val purchaseId = UUID.randomUUID()
        var hasPurchase = false
        val reserved = mutableListOf<AppliedTemplateReservedItemResponse>()
        val toPurchase = mutableListOf<AppliedTemplatePurchaseItemResponse>()
        val createdSources = mutableListOf<AppliedTemplateSourceResponse>()
        val shortages = mutableListOf<AppliedTemplateShortageResponse>()

        InventoryListTemplateItems.selectAll()
            .where { InventoryListTemplateItems.templateId eq templateId }
            .orderBy(InventoryListTemplateItems.itemName to SortOrder.ASC)
            .forEach { templateItem ->
                val requestedQuantity = templateItem[InventoryListTemplateItems.quantity]
                val templateItemName = templateItem[InventoryListTemplateItems.itemName]
                val candidates = findTemplateSourceItems(
                    tuntasId = tuntasId,
                    itemId = templateItem[InventoryListTemplateItems.itemId],
                    itemName = templateItemName
                )
                val sourceAllocations = mutableListOf<Pair<ResultRow, Int>>()
                var remainingToReserve = requestedQuantity
                candidates.forEach { candidate ->
                    if (remainingToReserve <= 0) return@forEach
                    val available = availableQuantityForEventItem(
                        candidate[Items.id],
                        event[Events.startDate],
                        event[Events.endDate]
                    )
                    val quantity = available.coerceAtMost(remainingToReserve)
                    if (quantity > 0) {
                        sourceAllocations += candidate to quantity
                        remainingToReserve -= quantity
                    }
                }
                val reservableQuantity = sourceAllocations.sumOf { it.second }
                val shortage = (requestedQuantity - reservableQuantity).coerceAtLeast(0)
                val primarySource = sourceAllocations.firstOrNull()?.first

                val eventInventoryItemId = EventInventoryItems.insert {
                    it[this.eventId] = eventId
                    it[itemId] = primarySource?.get(Items.id)
                    it[bucketId] = null
                    it[this.reservationGroupId] = if (reservableQuantity > 0) reservationGroupId else null
                    it[name] = primarySource?.get(Items.name) ?: templateItemName
                    it[plannedQuantity] = requestedQuantity
                    it[availableQuantity] = reservableQuantity
                    it[needsPurchase] = shortage > 0
                    it[responsibleUserId] = null
                    it[notes] = templateItem[InventoryListTemplateItems.notes]
                    it[this.createdByUserId] = createdByUserId
                    it[createdAt] = now
                } get EventInventoryItems.id

                sourceAllocations.forEach { (sourceItem, quantity) ->
                    Reservations.insert {
                        it[this.groupId] = reservationGroupId
                        it[title] = event[Events.name]
                        it[itemId] = sourceItem[Items.id]
                        it[this.tuntasId] = tuntasId
                        it[reservedByUserId] = createdByUserId
                        it[requestingUnitId] = null
                        it[this.eventId] = eventId
                        it[Reservations.quantity] = quantity
                        it[startDate] = event[Events.startDate]
                        it[endDate] = event[Events.endDate]
                        it[unitReviewStatus] = "NOT_REQUIRED"
                        it[topLevelReviewStatus] = "APPROVED"
                        it[topLevelReviewedByUserId] = createdByUserId
                        it[topLevelReviewedAt] = now
                        it[status] = "APPROVED"
                        it[notes] = templateItem[InventoryListTemplateItems.notes]
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                    val snapshot = buildSourceSnapshot(sourceItem)
                    val pickupSummary = buildSourcePickupSummary(
                        snapshot.custodianName,
                        snapshot.locationPath,
                        snapshot.temporaryStorageLabel,
                        snapshot.responsibleUserName
                    )
                    val sourceId = EventInventorySources.insert {
                        it[this.eventInventoryItemId] = eventInventoryItemId
                        it[itemId] = sourceItem[Items.id]
                        it[this.reservationGroupId] = reservationGroupId
                        it[plannedQuantity] = quantity
                        it[reservedQuantity] = quantity
                        it[pickupCustodianName] = snapshot.custodianName
                        it[pickupLocationPath] = snapshot.locationPath
                        it[pickupTemporaryStorageLabel] = snapshot.temporaryStorageLabel
                        it[pickupResponsibleUserName] = snapshot.responsibleUserName
                        it[this.pickupSummary] = pickupSummary
                        it[sourceStatus] = "RESERVED"
                        it[notes] = templateItem[InventoryListTemplateItems.notes]
                        it[createdAt] = now
                    } get EventInventorySources.id
                    reserved += AppliedTemplateReservedItemResponse(
                        templateItemName = templateItemName,
                        itemId = sourceItem[Items.id].toString(),
                        itemName = sourceItem[Items.name],
                        eventInventoryItemId = eventInventoryItemId.toString(),
                        reservationGroupId = reservationGroupId.toString(),
                        quantity = quantity
                    )
                    createdSources += AppliedTemplateSourceResponse(
                        templateItemName = templateItemName,
                        eventInventoryItemId = eventInventoryItemId.toString(),
                        sourceId = sourceId.toString(),
                        itemId = sourceItem[Items.id].toString(),
                        itemName = sourceItem[Items.name],
                        reservedQuantity = quantity,
                        plannedQuantity = quantity,
                        pickupSummary = pickupSummary,
                        sourceStatus = "RESERVED"
                    )
                }

                if (shortage > 0) {
                    shortages += AppliedTemplateShortageResponse(
                        templateItemName = templateItemName,
                        eventInventoryItemId = eventInventoryItemId.toString(),
                        shortageQuantity = shortage
                    )
                    if (!hasPurchase) {
                        EventPurchases.insert {
                            it[id] = purchaseId
                            it[this.eventId] = eventId
                            it[purchasedByUserId] = null
                            it[status] = "DRAFT"
                            it[purchaseDate] = null
                            it[totalAmount] = null
                            it[invoiceFileUrl] = null
                            it[notes] = "Automatiškai sukurta iš inventoriaus šablono"
                            it[createdAt] = now
                            it[updatedAt] = now
                        }
                        hasPurchase = true
                    }
                    val purchaseItemId = EventPurchaseItems.insert {
                        it[this.purchaseId] = purchaseId
                        it[this.eventInventoryItemId] = eventInventoryItemId
                        it[purchasedQuantity] = shortage
                        it[unitPrice] = null
                        it[addedToInventoryItemId] = primarySource?.get(Items.id)
                        it[addedToInventory] = false
                        it[notes] = templateItem[InventoryListTemplateItems.notes]
                    } get EventPurchaseItems.id
                    toPurchase += AppliedTemplatePurchaseItemResponse(
                        templateItemName = templateItemName,
                        eventInventoryItemId = eventInventoryItemId.toString(),
                        purchaseId = purchaseId.toString(),
                        purchaseItemId = purchaseItemId.toString(),
                        quantity = shortage
                    )
                }
            }

        Result.success(
            AppliedInventoryTemplateResponse(
                reserved = reserved,
                toPurchase = toPurchase,
                sources = createdSources,
                shortages = shortages,
                reservedTotal = reserved.sumOf { it.quantity },
                toPurchaseTotal = toPurchase.sumOf { it.quantity }
            )
        )
    }

    private fun replaceItems(templateId: UUID, items: List<InventoryTemplateItemRequest>) {
        InventoryListTemplateItems.deleteWhere { InventoryListTemplateItems.templateId eq templateId }
        items.forEach { item ->
            InventoryListTemplateItems.insert {
                it[this.templateId] = templateId
                it[itemId] = item.itemId
                    ?.takeIf { value -> value.isNotBlank() }
                    ?.let { value -> UUID.fromString(value) }
                it[itemName] = item.itemName.trim()
                it[quantity] = item.quantity
                it[category] = item.category?.takeIf { value -> value.isNotBlank() }
                it[notes] = item.notes?.takeIf { value -> value.isNotBlank() }
            }
        }
    }

    private fun validateTemplate(
        name: String,
        items: List<InventoryTemplateItemRequest>,
        validateItems: Boolean = true
    ): Exception? {
        if (name.isBlank()) return Exception("Template name is required")
        if (name.length > 200) return Exception("Template name must be at most 200 characters")
        if (!validateItems) return null
        items.forEach { item ->
            if (item.itemName.trim().isBlank()) return Exception("Template item name is required")
            if (item.quantity < 1) return Exception("Template item quantity must be at least 1")
        }
        return null
    }

    private fun loadTemplate(templateId: UUID, tuntasId: UUID): ResultRow? =
        InventoryListTemplates.selectAll()
            .where { (InventoryListTemplates.id eq templateId) and (InventoryListTemplates.tuntasId eq tuntasId) }
            .firstOrNull()

    private fun toTemplateResponse(row: ResultRow): InventoryTemplateResponse {
        val templateId = row[InventoryListTemplates.id]
        return InventoryTemplateResponse(
            id = templateId.toString(),
            tuntasId = row[InventoryListTemplates.tuntasId].toString(),
            name = row[InventoryListTemplates.name],
            eventType = row[InventoryListTemplates.eventType],
            createdByUserId = row[InventoryListTemplates.createdByUserId]?.toString(),
            createdByUserName = userDisplayName(row[InventoryListTemplates.createdByUserId]),
            createdAt = row[InventoryListTemplates.createdAt].toString(),
            items = InventoryListTemplateItems.selectAll()
                .where { InventoryListTemplateItems.templateId eq templateId }
                .orderBy(InventoryListTemplateItems.itemName to SortOrder.ASC)
                .map {
                    InventoryTemplateItemResponse(
                        id = it[InventoryListTemplateItems.id].toString(),
                        templateId = it[InventoryListTemplateItems.templateId].toString(),
                        itemId = it[InventoryListTemplateItems.itemId]?.toString(),
                        itemName = it[InventoryListTemplateItems.itemName],
                        quantity = it[InventoryListTemplateItems.quantity],
                        category = it[InventoryListTemplateItems.category],
                        notes = it[InventoryListTemplateItems.notes]
                    )
                }
        )
    }

    private fun toEventInventoryItemResponse(row: ResultRow): EventInventoryItemResponse {
        val planned = row[EventInventoryItems.plannedQuantity]
        val available = row[EventInventoryItems.availableQuantity]
        val allocated = 0
        return EventInventoryItemResponse(
            id = row[EventInventoryItems.id].toString(),
            eventId = row[EventInventoryItems.eventId].toString(),
            itemId = row[EventInventoryItems.itemId]?.toString(),
            bucketId = row[EventInventoryItems.bucketId]?.toString(),
            bucketName = null,
            reservationGroupId = row[EventInventoryItems.reservationGroupId]?.toString(),
            name = row[EventInventoryItems.name],
            plannedQuantity = planned,
            availableQuantity = available,
            shortageQuantity = (planned - available).coerceAtLeast(0),
            allocatedQuantity = allocated,
            unallocatedQuantity = (available - allocated).coerceAtLeast(0),
            needsPurchase = row[EventInventoryItems.needsPurchase],
            notes = row[EventInventoryItems.notes],
            responsibleUserId = row[EventInventoryItems.responsibleUserId]?.toString(),
            responsibleUserName = userDisplayName(row[EventInventoryItems.responsibleUserId]),
            createdByUserId = row[EventInventoryItems.createdByUserId]?.toString(),
            createdAt = row[EventInventoryItems.createdAt].toString()
        )
    }

    private fun userDisplayName(userId: UUID?): String? {
        if (userId == null) return null
        return Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.let { "${it[Users.name]} ${it[Users.surname]}".trim() }
    }

    private fun findTemplateSourceItem(tuntasId: UUID, itemName: String): ResultRow? {
        val needle = itemName.trim().lowercase()
        return Items.selectAll()
            .where {
                (Items.tuntasId eq tuntasId) and
                    (Items.status eq "ACTIVE") and
                    (Items.custodianId.isNull())
            }
            .toList()
            .filter { it[Items.quantity] > 0 }
            .filter { it[Items.name].lowercase().contains(needle) || needle.contains(it[Items.name].lowercase()) }
            .sortedWith(
                compareByDescending<ResultRow> { it[Items.name].equals(itemName, ignoreCase = true) }
                    .thenByDescending { it[Items.quantity] }
                    .thenBy { it[Items.name] }
            )
            .firstOrNull()
    }

    private fun findTemplateSourceItems(tuntasId: UUID, itemId: UUID?, itemName: String): List<ResultRow> {
        val needle = itemName.trim().lowercase()
        val items = Items.selectAll()
            .where {
                (Items.tuntasId eq tuntasId) and
                    (Items.status eq "ACTIVE")
            }
            .toList()
            .filter { it[Items.quantity] > 0 }
        return items
            .filter {
                itemId == null ||
                    it[Items.id] == itemId ||
                    it[Items.name].equals(itemName, ignoreCase = true) ||
                    it[Items.name].lowercase().contains(needle) ||
                    needle.contains(it[Items.name].lowercase())
            }
            .sortedWith(
                compareByDescending<ResultRow> { itemId != null && it[Items.id] == itemId }
                    .thenByDescending { it[Items.name].equals(itemName, ignoreCase = true) }
                    .thenByDescending { it[Items.quantity] }
                    .thenBy { it[Items.name] }
            )
    }

    private data class SourceSnapshot(
        val custodianName: String?,
        val locationPath: String?,
        val temporaryStorageLabel: String?,
        val responsibleUserName: String?
    )

    private fun buildSourceSnapshot(sourceItem: ResultRow): SourceSnapshot {
        val custodianName = sourceItem[Items.custodianId]?.let { custodianId ->
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq custodianId }
                .firstOrNull()
                ?.get(OrganizationalUnits.name)
        }
        val locationPath = sourceItem[Items.locationId]?.let { locationId ->
            Locations.selectAll()
                .where { Locations.tuntasId eq sourceItem[Items.tuntasId] }
                .associate { it[Locations.id] to it[Locations.name] }[locationId]
        }
        val responsibleUserName = sourceItem[Items.responsibleUserId]?.let(::userDisplayName)
        return SourceSnapshot(
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
        return listOfNotNull(
            custodianName?.takeIf { it.isNotBlank() },
            locationPath?.takeIf { it.isNotBlank() },
            temporaryStorageLabel?.takeIf { it.isNotBlank() },
            responsibleUserName?.takeIf { it.isNotBlank() }?.let { "Pas $it" }
        ).joinToString(" / ").takeIf { it.isNotBlank() }
    }

    private fun findTemplateSourceItemById(tuntasId: UUID, itemId: UUID): ResultRow? =
        Items.selectAll()
            .where {
                (Items.id eq itemId) and
                    (Items.tuntasId eq tuntasId) and
                    (Items.status eq "ACTIVE")
            }
            .firstOrNull()

    private fun availableQuantityForEventItem(
        itemId: UUID,
        startDate: kotlinx.datetime.LocalDate,
        endDate: kotlinx.datetime.LocalDate
    ): Int {
        val item = Items.selectAll()
            .where { (Items.id eq itemId) and (Items.status eq "ACTIVE") }
            .firstOrNull() ?: return 0
        val reserved = Reservations.select(Reservations.quantity)
            .where {
                (Reservations.itemId eq itemId) and
                    (Reservations.status inList listOf("APPROVED", "ACTIVE")) and
                    (Reservations.startDate lessEq endDate) and
                    (Reservations.endDate greaterEq startDate)
            }
            .sumOf { it[Reservations.quantity] }
        return (item[Items.quantity] - reserved).coerceAtLeast(0)
    }
}
