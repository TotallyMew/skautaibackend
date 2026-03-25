package lt.skautai.services

import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.models.requests.CreateOrganizationalUnitRequest
import lt.skautai.models.requests.UpdateOrganizationalUnitRequest
import lt.skautai.models.responses.OrganizationalUnitListResponse
import lt.skautai.models.responses.OrganizationalUnitResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class OrganizationalUnitService {

    private val validTypes = listOf(
        "DRAUGOVE", "SKILTIS", "GAUJA", "GILDIJA", "BURELI", "VYRESNIUJU_DRAUGOVE"
    )

    fun getUnits(tuntasId: UUID, type: String? = null): Result<OrganizationalUnitListResponse> {
        return transaction {
            var query = OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.tuntasId eq tuntasId }

            type?.let { query = query.andWhere { OrganizationalUnits.type eq it } }

            val units = query.map { toResponse(it) }
            Result.success(OrganizationalUnitListResponse(units = units, total = units.size))
        }
    }

    fun getUnit(unitId: UUID, tuntasId: UUID): Result<OrganizationalUnitResponse> {
        return transaction {
            val unit = OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))
            Result.success(toResponse(unit))
        }
    }

    fun createUnit(
        tuntasId: UUID,
        request: CreateOrganizationalUnitRequest
    ): Result<OrganizationalUnitResponse> {
        return transaction {
            if (request.name.isBlank()) {
                return@transaction Result.failure(Exception("Name cannot be blank"))
            }

            if (request.type !in validTypes) {
                return@transaction Result.failure(Exception("Invalid type. Must be one of: ${validTypes.joinToString()}"))
            }

            val parentUUID = request.parentId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid parent ID"))
                }
            }

            // Verify parent belongs to same tuntas
            if (parentUUID != null) {
                val parent = OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq parentUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Parent unit not found in this tuntas"))
            }

            val unitId = OrganizationalUnits.insert {
                it[this.tuntasId] = tuntasId
                it[parentId] = parentUUID
                it[name] = request.name
                it[type] = request.type
            } get OrganizationalUnits.id

            val unit = OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq unitId }
                .first()

            Result.success(toResponse(unit))
        }
    }

    fun updateUnit(
        unitId: UUID,
        tuntasId: UUID,
        request: UpdateOrganizationalUnitRequest
    ): Result<OrganizationalUnitResponse> {
        return transaction {
            OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            request.name?.let {
                if (it.isBlank()) return@transaction Result.failure(Exception("Name cannot be blank"))
            }

            val parentUUID = request.parentId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid parent ID"))
                }
            }

            if (parentUUID != null) {
                // Cannot set parent to itself
                if (parentUUID == unitId) {
                    return@transaction Result.failure(Exception("Unit cannot be its own parent"))
                }

                val parent = OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq parentUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Parent unit not found in this tuntas"))
            }

            OrganizationalUnits.update({
                (OrganizationalUnits.id eq unitId) and
                        (OrganizationalUnits.tuntasId eq tuntasId)
            }) { update ->

                request.name?.let { v ->
                    update[name] = v
                }

                request.parentId?.let {
                    update[OrganizationalUnits.parentId] = parentUUID
                }
            }

            val updated = OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id eq unitId }
                .first()

            Result.success(toResponse(updated))
        }
    }

    fun deleteUnit(unitId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            // Check if any child units exist
            val childCount = OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.parentId eq unitId }
                .count()

            if (childCount > 0) {
                return@transaction Result.failure(Exception("Cannot delete unit that has child units"))
            }

            // Check if any items are owned by this unit
            val itemCount = lt.skautai.database.tables.Items.selectAll()
                .where {
                    (lt.skautai.database.tables.Items.ownerType eq "DRAUGOVE") and
                            (lt.skautai.database.tables.Items.ownerId eq unitId) and
                            (lt.skautai.database.tables.Items.status neq "INACTIVE")
                }
                .count()

            if (itemCount > 0) {
                return@transaction Result.failure(Exception("Cannot delete unit that has active items"))
            }

            OrganizationalUnits.deleteWhere {
                (OrganizationalUnits.id eq unitId) and
                        (OrganizationalUnits.tuntasId eq tuntasId)
            }

            Result.success(Unit)
        }
    }

    private fun toResponse(row: ResultRow): OrganizationalUnitResponse {
        return OrganizationalUnitResponse(
            id = row[OrganizationalUnits.id].toString(),
            tuntasId = row[OrganizationalUnits.tuntasId].toString(),
            parentId = row[OrganizationalUnits.parentId]?.toString(),
            name = row[OrganizationalUnits.name],
            type = row[OrganizationalUnits.type],
            createdAt = row[OrganizationalUnits.createdAt].toString()
        )
    }
}