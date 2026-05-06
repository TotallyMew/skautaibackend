package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.EventInventoryItems
import lt.skautai.database.tables.Events
import lt.skautai.database.tables.InventoryListTemplateItems
import lt.skautai.database.tables.InventoryListTemplates
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateInventoryTemplateRequest
import lt.skautai.models.requests.InventoryTemplateItemRequest
import lt.skautai.models.requests.UpdateInventoryTemplateRequest
import lt.skautai.models.responses.EventInventoryItemListResponse
import lt.skautai.models.responses.EventInventoryItemResponse
import lt.skautai.models.responses.InventoryTemplateItemResponse
import lt.skautai.models.responses.InventoryTemplateListResponse
import lt.skautai.models.responses.InventoryTemplateResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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

    private fun replaceItems(templateId: UUID, items: List<InventoryTemplateItemRequest>) {
        InventoryListTemplateItems.deleteWhere { InventoryListTemplateItems.templateId eq templateId }
        items.forEach { item ->
            InventoryListTemplateItems.insert {
                it[this.templateId] = templateId
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
}
