package lt.skautai.services

import lt.skautai.database.tables.Items
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.models.requests.CreateItemRequest
import lt.skautai.models.requests.UpdateItemRequest
import lt.skautai.models.responses.ItemListResponse
import lt.skautai.models.responses.ItemResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class ItemService {

    fun getItems(
        tuntasId: UUID,
        requestingUserId: UUID,
        ownerType: String? = null,
        category: String? = null,
        status: String? = null
    ): Result<ItemListResponse> {
        return transaction {
            val canSeeAll = userCanSeeAllStatuses(requestingUserId, tuntasId)

            var query = Items.selectAll()
                .where { Items.tuntasId eq tuntasId }

            if (!canSeeAll) {
                query = query.andWhere { Items.status eq "ACTIVE" }
            }

            ownerType?.let { query = query.andWhere { Items.ownerType eq it } }
            category?.let { query = query.andWhere { Items.category eq it } }
            status?.let {
                if (canSeeAll) query = query.andWhere { Items.status eq it }
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

            val item = Items.selectAll()
                .where {
                    (Items.id eq itemId) and (Items.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            if (!canSeeAll && item[Items.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Item not found"))
            }

            Result.success(toItemResponse(item))
        }
    }

    fun createItem(
        tuntasId: UUID,
        createdByUserId: UUID,
        request: CreateItemRequest
    ): Result<ItemResponse> {
        return transaction {
            // Validate category
            if (request.category !in listOf("COLLECTIVE", "ASSIGNED", "INDIVIDUAL")) {
                return@transaction Result.failure(Exception("Invalid category"))
            }

            // Validate ownerType
            if (request.ownerType !in listOf("TUNTAS", "DRAUGOVE")) {
                return@transaction Result.failure(Exception("Invalid owner type"))
            }

            if (request.quantity < 1) {
                return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            val ownerUUID = try {
                UUID.fromString(request.ownerId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid owner ID"))
            }

            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
                }
            }

            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }

            val purchaseDate = request.purchaseDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }

            val itemId = Items.insert {
                it[this.tuntasId] = tuntasId
                it[ownerType] = request.ownerType
                it[ownerId] = ownerUUID
                it[name] = request.name
                it[description] = request.description
                it[category] = request.category
                it[quantity] = request.quantity
                it[locationId] = locationUUID
                it[responsibleUserId] = responsibleUUID
                it[Items.createdByUserId] = createdByUserId
                it[photoUrl] = request.photoUrl
                it[this.purchaseDate] = purchaseDate
                it[purchasePrice] = request.purchasePrice?.toBigDecimal()
                it[notes] = request.notes
                it[status] = "ACTIVE"
            } get Items.id

            val item = Items.selectAll()
                .where { Items.id eq itemId }
                .first()

            Result.success(toItemResponse(item))
        }
    }

    fun updateItem(
        itemId: UUID,
        tuntasId: UUID,
        request: UpdateItemRequest
    ): Result<ItemResponse> {
        return transaction {
            val existing = Items.selectAll()
                .where { (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Item not found"))

            if (existing[Items.status] == "INACTIVE") {
                return@transaction Result.failure(Exception("Cannot update an inactive item"))
            }

            request.quantity?.let {
                if (it < 1) return@transaction Result.failure(Exception("Quantity must be at least 1"))
            }

            request.category?.let {
                if (it !in listOf("COLLECTIVE", "ASSIGNED", "INDIVIDUAL")) {
                    return@transaction Result.failure(Exception("Invalid category"))
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

            val locationUUID = request.locationId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid location ID"))
                }
            }

            val responsibleUUID = request.responsibleUserId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid responsible user ID"))
                }
            }

            val purchaseDate = request.purchaseDate?.let {
                try { kotlinx.datetime.LocalDate.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid purchase date format, use YYYY-MM-DD"))
                }
            }

            Items.update({ (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }) {
                request.name?.let { v -> it[name] = v }
                request.description?.let { v -> it[description] = v }
                request.category?.let { v -> it[category] = v }
                request.condition?.let { v -> it[condition] = v }
                request.quantity?.let { v -> it[quantity] = v }
                request.photoUrl?.let { v -> it[photoUrl] = v }
                request.purchasePrice?.let { v -> it[purchasePrice] = v.toBigDecimal() }
                request.notes?.let { v -> it[notes] = v }
                request.status?.let { v -> it[status] = v }
                locationUUID?.let { v -> it[locationId] = v }
                responsibleUUID?.let { v -> it[responsibleUserId] = v }
                purchaseDate?.let { v -> it[this.purchaseDate] = v }
            }

            val updated = Items.selectAll()
                .where { Items.id eq itemId }
                .first()

            Result.success(toItemResponse(updated))
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

            Items.update({ (Items.id eq itemId) and (Items.tuntasId eq tuntasId) }) {
                it[status] = "INACTIVE"
            }

            Result.success(Unit)
        }
    }

    // Tuntininkas, Tuntininko pavaduotojas, Inventorininkas can see all statuses
    private fun userCanSeeAllStatuses(userId: UUID, tuntasId: UUID): Boolean {
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

    private fun toItemResponse(row: ResultRow): ItemResponse {
        return ItemResponse(
            id = row[Items.id].toString(),
            tuntasId = row[Items.tuntasId].toString(),
            ownerType = row[Items.ownerType],
            ownerId = row[Items.ownerId].toString(),
            name = row[Items.name],
            description = row[Items.description],
            category = row[Items.category],
            condition = row[Items.condition],
            quantity = row[Items.quantity],
            locationId = row[Items.locationId]?.toString(),
            responsibleUserId = row[Items.responsibleUserId]?.toString(),
            photoUrl = row[Items.photoUrl],
            purchaseDate = row[Items.purchaseDate]?.toString(),
            purchasePrice = row[Items.purchasePrice]?.toDouble(),
            notes = row[Items.notes],
            status = row[Items.status],
            createdAt = row[Items.createdAt].toString(),
            updatedAt = row[Items.updatedAt].toString()
        )
    }
}