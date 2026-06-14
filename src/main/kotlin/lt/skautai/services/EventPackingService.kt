package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.EventInventoryAllocations
import lt.skautai.database.tables.EventInventoryBuckets
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.EventPackingContainers
import lt.skautai.database.tables.EventPackingLines
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateEventPackingContainerRequest
import lt.skautai.models.requests.UpdateEventPackingLineRequest
import lt.skautai.models.responses.EventPackingContainerResponse
import lt.skautai.models.responses.EventPackingLineResponse
import lt.skautai.models.responses.EventPackingListResponse
import lt.skautai.models.responses.EventPackingSummaryResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class EventPackingService {
    private val validContainerTypes = setOf("BOX", "BAG", "TRAILER", "VEHICLE", "OTHER")
    private val validLineStatuses = setOf("TODO", "PICKED", "PACKED", "LOADED", "RETURNED")
    private val doneStatuses = setOf("PACKED", "LOADED", "RETURNED")

    fun getPackingList(eventId: UUID, tuntasId: UUID): Result<EventPackingListResponse> = transaction {
        ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
        Result.success(buildResponse(eventId))
    }

    fun generateFromPlan(eventId: UUID, tuntasId: UUID): Result<EventPackingListResponse> = transaction {
        ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
        val defaultContainerId = ensureDefaultContainer(eventId)
        val existingAllocationIds = EventPackingLines.selectAll()
            .where {
                (EventPackingLines.eventId eq eventId) and
                    EventPackingLines.allocationId.isNotNull()
            }
            .mapNotNull { it[EventPackingLines.allocationId] }
            .toSet()
        val existingUnallocatedItemIds = EventPackingLines.selectAll()
            .where {
                (EventPackingLines.eventId eq eventId) and
                    EventPackingLines.allocationId.isNull()
            }
            .map { it[EventPackingLines.eventInventoryItemId] }
            .toSet()

        val allocationRows = EventInventoryAllocations
            .innerJoin(EventInventoryItems, { EventInventoryAllocations.eventInventoryItemId }, { EventInventoryItems.id })
            .selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .toList()

        allocationRows
            .filterNot { it[EventInventoryAllocations.id] in existingAllocationIds }
            .forEach { row ->
                insertPackingLine(
                    eventId = eventId,
                    inventoryItem = row,
                    allocationId = row[EventInventoryAllocations.id],
                    bucketId = row[EventInventoryAllocations.bucketId],
                    containerId = defaultContainerId,
                    quantity = row[EventInventoryAllocations.quantity],
                    notes = row[EventInventoryAllocations.notes]
                )
            }

        val allocatedItemIds = allocationRows.map { it[EventInventoryItems.id] }.toSet()
        EventInventoryItems.selectAll()
            .where { EventInventoryItems.eventId eq eventId }
            .filterNot { it[EventInventoryItems.id] in allocatedItemIds }
            .filterNot { it[EventInventoryItems.id] in existingUnallocatedItemIds }
            .forEach { row ->
                insertPackingLine(
                    eventId = eventId,
                    inventoryItem = row,
                    allocationId = null,
                    bucketId = row[EventInventoryItems.bucketId],
                    containerId = defaultContainerId,
                    quantity = row[EventInventoryItems.plannedQuantity],
                    notes = row[EventInventoryItems.notes]
                )
            }

        Result.success(buildResponse(eventId))
    }

    fun createContainer(
        eventId: UUID,
        tuntasId: UUID,
        request: CreateEventPackingContainerRequest
    ): Result<EventPackingListResponse> = transaction {
        ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
        val name = request.name.trim()
        if (name.isBlank()) return@transaction Result.failure(Exception("Container name cannot be blank"))
        val type = request.type.trim().uppercase().ifBlank { "BOX" }
        if (type !in validContainerTypes) return@transaction Result.failure(Exception("Invalid container type"))
        val now = Clock.System.now()
        val nextOrder = EventPackingContainers.selectAll()
            .where { EventPackingContainers.eventId eq eventId }
            .maxOfOrNull { it[EventPackingContainers.sortOrder] + 1 } ?: 1
        EventPackingContainers.insert {
            it[this.eventId] = eventId
            it[this.name] = name
            it[this.type] = type
            it[status] = "ACTIVE"
            it[sortOrder] = nextOrder
            it[notes] = request.notes
            it[createdAt] = now
            it[updatedAt] = now
        }
        Result.success(buildResponse(eventId))
    }

    fun updateLine(
        eventId: UUID,
        lineId: UUID,
        tuntasId: UUID,
        userId: UUID,
        request: UpdateEventPackingLineRequest
    ): Result<EventPackingListResponse> = transaction {
        ensureEvent(eventId, tuntasId) ?: return@transaction Result.failure(Exception("Event not found"))
        val existing = EventPackingLines.selectAll()
            .where { (EventPackingLines.id eq lineId) and (EventPackingLines.eventId eq eventId) }
            .firstOrNull() ?: return@transaction Result.failure(Exception("Packing line not found"))

        val nextStatus = request.status?.trim()?.uppercase()
        if (nextStatus != null && nextStatus !in validLineStatuses) {
            return@transaction Result.failure(Exception("Invalid packing status"))
        }

        val nextContainerId = when {
            request.clearContainer -> null
            request.containerId != null -> {
                val parsed = runCatching { UUID.fromString(request.containerId) }.getOrNull()
                    ?: return@transaction Result.failure(Exception("Invalid container ID"))
                val containerExists = EventPackingContainers.selectAll()
                    .where {
                        (EventPackingContainers.id eq parsed) and
                            (EventPackingContainers.eventId eq eventId) and
                            (EventPackingContainers.status eq "ACTIVE")
                    }
                    .any()
                if (!containerExists) return@transaction Result.failure(Exception("Container not found"))
                parsed
            }
            else -> existing[EventPackingLines.containerId]
        }

        val now = Clock.System.now()
        EventPackingLines.update({ (EventPackingLines.id eq lineId) and (EventPackingLines.eventId eq eventId) }) {
            nextStatus?.let { status ->
                it[EventPackingLines.status] = status
                it[checkedByUserId] = userId
                it[checkedAt] = now
            }
            it[containerId] = nextContainerId
            request.notes?.let { notes -> it[EventPackingLines.notes] = notes }
            it[updatedAt] = now
        }

        Result.success(buildResponse(eventId))
    }

    private fun ensureEvent(eventId: UUID, tuntasId: UUID): ResultRow? =
        Events.selectAll()
            .where { (Events.id eq eventId) and (Events.tuntasId eq tuntasId) }
            .firstOrNull()

    private fun ensureDefaultContainer(eventId: UUID): UUID {
        val existing = EventPackingContainers.selectAll()
            .where {
                (EventPackingContainers.eventId eq eventId) and
                    (EventPackingContainers.name eq "Nesupakuota")
            }
            .firstOrNull()
        if (existing != null) return existing[EventPackingContainers.id]

        val now = Clock.System.now()
        return EventPackingContainers.insert {
            it[this.eventId] = eventId
            it[name] = "Nesupakuota"
            it[type] = "OTHER"
            it[status] = "ACTIVE"
            it[sortOrder] = 0
            it[notes] = "Pradinis pakavimo sarasas"
            it[createdAt] = now
            it[updatedAt] = now
        }[EventPackingContainers.id]
    }

    private fun insertPackingLine(
        eventId: UUID,
        inventoryItem: ResultRow,
        allocationId: UUID?,
        bucketId: UUID?,
        containerId: UUID,
        quantity: Int,
        notes: String?
    ) {
        if (quantity <= 0) return
        val now = Clock.System.now()
        EventPackingLines.insert {
            it[this.eventId] = eventId
            it[eventInventoryItemId] = inventoryItem[EventInventoryItems.id]
            it[this.allocationId] = allocationId
            it[this.containerId] = containerId
            it[this.bucketId] = bucketId
            it[itemId] = inventoryItem[EventInventoryItems.itemId]
            it[itemName] = inventoryItem[EventInventoryItems.name]
            it[requiredQuantity] = quantity
            it[status] = "TODO"
            it[sourceSummary] = buildSourceSummary(inventoryItem)
            it[this.notes] = notes
            it[checkedByUserId] = null
            it[checkedAt] = null
            it[createdAt] = now
            it[updatedAt] = now
        }
    }

    private fun buildResponse(eventId: UUID): EventPackingListResponse {
        val containers = EventPackingContainers.selectAll()
            .where { EventPackingContainers.eventId eq eventId }
            .orderBy(EventPackingContainers.sortOrder to SortOrder.ASC, EventPackingContainers.name to SortOrder.ASC)
            .toList()
        val containersById = containers.associateBy { it[EventPackingContainers.id] }
        val bucketRows = EventInventoryBuckets.selectAll()
            .where { EventInventoryBuckets.eventId eq eventId }
            .toList()
            .associateBy { it[EventInventoryBuckets.id] }
        val userIds = EventPackingLines.selectAll()
            .where { EventPackingLines.eventId eq eventId }
            .mapNotNull { it[EventPackingLines.checkedByUserId] }
            .toSet()
        val usersById = if (userIds.isEmpty()) emptyMap() else Users.selectAll()
            .where { Users.id inList userIds }
            .associateBy { it[Users.id] }

        val lines = EventPackingLines.selectAll()
            .where { EventPackingLines.eventId eq eventId }
            .orderBy(EventPackingLines.status to SortOrder.ASC, EventPackingLines.itemName to SortOrder.ASC)
            .toList()
            .map { row -> toLineResponse(row, containersById, bucketRows, usersById) }

        val totalQuantity = lines.sumOf { it.requiredQuantity }
        val doneQuantity = lines.filter { it.status in doneStatuses }.sumOf { it.requiredQuantity }
        return EventPackingListResponse(
            eventId = eventId.toString(),
            containers = containers.map(::toContainerResponse),
            lines = lines,
            summary = EventPackingSummaryResponse(
                totalLines = lines.size,
                doneLines = lines.count { it.status in doneStatuses },
                totalQuantity = totalQuantity,
                doneQuantity = doneQuantity,
                progressPercent = if (totalQuantity == 0) 0 else ((doneQuantity * 100) / totalQuantity).coerceIn(0, 100)
            )
        )
    }

    private fun toContainerResponse(row: ResultRow): EventPackingContainerResponse =
        EventPackingContainerResponse(
            id = row[EventPackingContainers.id].toString(),
            eventId = row[EventPackingContainers.eventId].toString(),
            name = row[EventPackingContainers.name],
            type = row[EventPackingContainers.type],
            status = row[EventPackingContainers.status],
            sortOrder = row[EventPackingContainers.sortOrder],
            notes = row[EventPackingContainers.notes],
            createdAt = row[EventPackingContainers.createdAt].toString(),
            updatedAt = row[EventPackingContainers.updatedAt].toString()
        )

    private fun toLineResponse(
        row: ResultRow,
        containersById: Map<UUID, ResultRow>,
        bucketsById: Map<UUID, ResultRow>,
        usersById: Map<UUID, ResultRow>
    ): EventPackingLineResponse {
        val container = row[EventPackingLines.containerId]?.let(containersById::get)
        val bucket = row[EventPackingLines.bucketId]?.let(bucketsById::get)
        val checker = row[EventPackingLines.checkedByUserId]?.let(usersById::get)
        return EventPackingLineResponse(
            id = row[EventPackingLines.id].toString(),
            eventId = row[EventPackingLines.eventId].toString(),
            eventInventoryItemId = row[EventPackingLines.eventInventoryItemId].toString(),
            allocationId = row[EventPackingLines.allocationId]?.toString(),
            containerId = row[EventPackingLines.containerId]?.toString(),
            containerName = container?.get(EventPackingContainers.name),
            bucketId = row[EventPackingLines.bucketId]?.toString(),
            bucketName = bucket?.get(EventInventoryBuckets.name),
            itemId = row[EventPackingLines.itemId]?.toString(),
            itemName = row[EventPackingLines.itemName],
            requiredQuantity = row[EventPackingLines.requiredQuantity],
            status = row[EventPackingLines.status],
            sourceSummary = row[EventPackingLines.sourceSummary],
            notes = row[EventPackingLines.notes],
            checkedByUserId = row[EventPackingLines.checkedByUserId]?.toString(),
            checkedByUserName = checker?.let { "${it[Users.name]} ${it[Users.surname]}".trim() },
            checkedAt = row[EventPackingLines.checkedAt]?.toString(),
            createdAt = row[EventPackingLines.createdAt].toString(),
            updatedAt = row[EventPackingLines.updatedAt].toString()
        )
    }

    private fun buildSourceSummary(row: ResultRow): String? =
        listOfNotNull(
            row[EventInventoryItems.sourceCustodianName],
            row[EventInventoryItems.sourceLocationPath],
            row[EventInventoryItems.sourceTemporaryStorageLabel],
            row[EventInventoryItems.sourceResponsibleUserName]?.let { "Pas $it" }
        ).joinToString(" / ").takeIf { it.isNotBlank() }
}
