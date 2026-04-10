package lt.skautai.services

import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.models.requests.CreateOrganizationalUnitRequest
import lt.skautai.models.requests.UpdateOrganizationalUnitRequest
import lt.skautai.models.responses.OrganizationalUnitListResponse
import lt.skautai.models.responses.OrganizationalUnitResponse
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserDraugoveMemberships
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.database.tables.UserRanks
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.AssignDraugoveMembershipRequest
import lt.skautai.models.responses.DraugoveMembershipListResponse
import lt.skautai.models.responses.DraugoveMembershipResponse
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

            if (parentUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq parentUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Parent unit not found in this tuntas"))
            }

            val acceptedRankUUID = request.acceptedRankId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid accepted rank ID"))
                }
            }

            if (acceptedRankUUID != null) {
                Roles.selectAll()
                    .where {
                        (Roles.id eq acceptedRankUUID) and
                                (Roles.tuntasId eq tuntasId) and
                                (Roles.roleType eq "RANK")
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Rank role not found in this tuntas"))
            }

            val unitId = OrganizationalUnits.insert {
                it[this.tuntasId] = tuntasId
                it[parentId] = parentUUID
                it[name] = request.name
                it[type] = request.type
                it[acceptedRankId] = acceptedRankUUID
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
                if (parentUUID == unitId) {
                    return@transaction Result.failure(Exception("Unit cannot be its own parent"))
                }

                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq parentUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Parent unit not found in this tuntas"))
            }

            val acceptedRankUUID = request.acceptedRankId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid accepted rank ID"))
                }
            }

            if (acceptedRankUUID != null) {
                Roles.selectAll()
                    .where {
                        (Roles.id eq acceptedRankUUID) and
                                (Roles.tuntasId eq tuntasId) and
                                (Roles.roleType eq "RANK")
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Rank role not found in this tuntas"))
            }

            OrganizationalUnits.update({
                (OrganizationalUnits.id eq unitId) and
                        (OrganizationalUnits.tuntasId eq tuntasId)
            }) { update ->
                request.name?.let { v -> update[name] = v }
                request.parentId?.let { update[OrganizationalUnits.parentId] = parentUUID }
                request.acceptedRankId?.let { update[OrganizationalUnits.acceptedRankId] = acceptedRankUUID }
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

        val acceptedRankId = row[OrganizationalUnits.acceptedRankId]
        val acceptedRankName = acceptedRankId?.let {
            Roles.selectAll()
                .where {Roles.id eq it}
                .firstOrNull()
                ?.get(Roles.name)
        }

        return OrganizationalUnitResponse(
            id = row[OrganizationalUnits.id].toString(),
            tuntasId = row[OrganizationalUnits.tuntasId].toString(),
            parentId = row[OrganizationalUnits.parentId]?.toString(),
            name = row[OrganizationalUnits.name],
            type = row[OrganizationalUnits.type],
            acceptedRankId = acceptedRankId?.toString(),
            acceptedRankName = acceptedRankName,
            createdAt = row[OrganizationalUnits.createdAt].toString()
        )
    }
    fun getDraugoveMembers(unitId: UUID, tuntasId: UUID): Result<DraugoveMembershipListResponse> {
        return transaction {
            OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            val members = UserDraugoveMemberships
                    .innerJoin(Users, { UserDraugoveMemberships.userId }, { Users.id })
                .selectAll()
                .where {
                    (UserDraugoveMemberships.organizationalUnitId eq unitId) and
                            (UserDraugoveMemberships.tuntasId eq tuntasId) and
                            (UserDraugoveMemberships.leftAt.isNull())
                }
                .map { toDraugoveMembershipResponse(it) }

            Result.success(DraugoveMembershipListResponse(members = members, total = members.size))
        }
    }

    fun assignDraugoveMember(
    unitId: UUID,
    tuntasId: UUID,
    assignedByUserId: UUID,
    request: AssignDraugoveMembershipRequest
    ): Result<DraugoveMembershipResponse> {
        return transaction {
            val unit = OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            if (unit[OrganizationalUnits.type] != "DRAUGOVE") {
                return@transaction Result.failure(Exception("Only DRAUGOVE units can have members assigned"))
            }

            val userUUID = try { UUID.fromString(request.userId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid user ID"))
            }

            // Verify user is an active tuntas member
            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq userUUID) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("User is not an active member of this tuntas"))

            // Rank validation — only enforce if draugove has an accepted rank set
            val acceptedRankId = unit[OrganizationalUnits.acceptedRankId]
            if (acceptedRankId != null && !request.isLent) {
                val userHasRank = UserRanks.selectAll()
                    .where {
                        (UserRanks.userId eq userUUID) and
                                (UserRanks.tuntasId eq tuntasId) and
                                (UserRanks.roleId eq acceptedRankId)
                    }
                    .firstOrNull()
                if (userHasRank == null) {
                    return@transaction Result.failure(Exception("User's rank does not match the accepted rank for this draugove"))
                }
            }

            // Check user does not already have an active primary membership in another draugove
            // (only applies to non-lent assignments)
            if (!request.isLent) {
                val existingPrimary = UserDraugoveMemberships.selectAll()
                    .where {
                        (UserDraugoveMemberships.userId eq userUUID) and
                                (UserDraugoveMemberships.tuntasId eq tuntasId) and
                                (UserDraugoveMemberships.isLent eq false) and
                        (UserDraugoveMemberships.leftAt.isNull())
                    }
                    .firstOrNull()

                if (existingPrimary != null) {
                    return@transaction Result.failure(Exception("User already has a primary draugove assignment. Remove it first or use isLent=true"))
                }
            }

            val membershipId = UserDraugoveMemberships.insert {
                it[userId] = userUUID
                it[organizationalUnitId] = unitId
                it[this.tuntasId] = tuntasId
                it[isLent] = request.isLent
                it[this.assignedByUserId] = assignedByUserId
            } get UserDraugoveMemberships.id

            val inserted = UserDraugoveMemberships
                    .innerJoin(Users, { UserDraugoveMemberships.userId }, { Users.id })
                .selectAll()
                .where { UserDraugoveMemberships.id eq membershipId }
                .first()

            Result.success(toDraugoveMembershipResponse(inserted))
        }
    }

    fun removeDraugoveMember(
    unitId: UUID,
    tuntasId: UUID,
    targetUserId: UUID
    ): Result<Unit> {
        return transaction {
            OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            val membership = UserDraugoveMemberships.selectAll()
                .where {
                    (UserDraugoveMemberships.userId eq targetUserId) and
                            (UserDraugoveMemberships.organizationalUnitId eq unitId) and
                            (UserDraugoveMemberships.tuntasId eq tuntasId) and
                            (UserDraugoveMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Active draugove membership not found for this user"))

            UserDraugoveMemberships.update({
                (UserDraugoveMemberships.userId eq targetUserId) and
                        (UserDraugoveMemberships.organizationalUnitId eq unitId) and
                        (UserDraugoveMemberships.tuntasId eq tuntasId) and
                        (UserDraugoveMemberships.leftAt.isNull())
            }) {
                it[leftAt] = kotlinx.datetime.Clock.System.now()
            }

            Result.success(Unit)
        }
    }

    private fun toDraugoveMembershipResponse(row: ResultRow): DraugoveMembershipResponse {
        val unitId = row[UserDraugoveMemberships.organizationalUnitId]
        val unitName = OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq unitId }
            .first()[OrganizationalUnits.name]

        return DraugoveMembershipResponse(
                id = row[UserDraugoveMemberships.id].toString(),
        userId = row[UserDraugoveMemberships.userId].toString(),
        userName = row[Users.name],
        userSurname = row[Users.surname],
        organizationalUnitId = unitId.toString(),
        organizationalUnitName = unitName,
        tuntasId = row[UserDraugoveMemberships.tuntasId].toString(),
        isLent = row[UserDraugoveMemberships.isLent],
        assignedByUserId = row[UserDraugoveMemberships.assignedByUserId]?.toString(),
        joinedAt = row[UserDraugoveMemberships.joinedAt].toString(),
        leftAt = row[UserDraugoveMemberships.leftAt]?.toString()
        )
    }
}