package lt.skautai.services

import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.database.tables.UserRanks
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.Users
import lt.skautai.database.tables.Items
import lt.skautai.database.tables.SeniorUnitAccessAudit
import lt.skautai.models.requests.AssignUnitMemberRequest
import lt.skautai.models.requests.CreateOrganizationalUnitRequest
import lt.skautai.models.requests.UpdateOrganizationalUnitRequest
import lt.skautai.models.requests.UpdateUnitMemberVisibilityRequest
import lt.skautai.models.responses.OrganizationalUnitListResponse
import lt.skautai.models.responses.OrganizationalUnitResponse
import lt.skautai.models.responses.UnitMembershipListResponse
import lt.skautai.models.responses.UnitMembershipResponse
import lt.skautai.models.responses.SeniorUnitAccessAuditListResponse
import lt.skautai.models.responses.SeniorUnitAccessAuditResponse
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class OrganizationalUnitService {

    private val validTypes = listOf(
        "VILKU_DRAUGOVE",
        "SKAUTU_DRAUGOVE",
        "PATYRUSIU_SKAUTU_DRAUGOVE",
        "GILDIJA",
        "VYR_SKAUTU_VIENETAS",
        "VYR_SKAUCIU_VIENETAS"
    )

    private val validSubtypes = listOf("DRAUGOVE", "BURELIS")

    private val validAssignmentTypes = listOf("MEMBER", "VADOVO_PADEJEJAS")

    fun getUnits(tuntasId: UUID, type: String? = null, visibleUnitIds: Set<UUID>? = null): Result<OrganizationalUnitListResponse> {
        return transaction {
            if (visibleUnitIds != null && visibleUnitIds.isEmpty()) {
                return@transaction Result.success(OrganizationalUnitListResponse(units = emptyList(), total = 0))
            }

            var query = OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.tuntasId eq tuntasId }

            type?.let { query = query.andWhere { OrganizationalUnits.type eq it } }
            visibleUnitIds?.let { unitIds ->
                query = query.andWhere { OrganizationalUnits.id inList unitIds.toList() }
            }

            val rows = query.toList()
            val hydration = buildListHydration(rows, tuntasId)
            val units = rows.map { toResponse(it, hydration) }
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

            // subtype only valid for VYR_SKAUTU_VIENETAS and VYR_SKAUCIU_VIENETAS
            val subtype = request.subType
            if (subtype != null) {
                if (request.type !in listOf("VYR_SKAUTU_VIENETAS", "VYR_SKAUCIU_VIENETAS")) {
                    return@transaction Result.failure(Exception("subtype is only valid for VYR_SKAUTU_VIENETAS and VYR_SKAUCIU_VIENETAS"))
                }
                if (subtype !in validSubtypes) {
                    return@transaction Result.failure(Exception("Invalid subtype. Must be one of: ${validSubtypes.joinToString()}"))
                }
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
                it[name] = request.name
                it[type] = request.type
                it[OrganizationalUnits.subtype] = subtype
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

            // Check if any items are in this unit's custody
            val itemCount = Items.selectAll()
                .where {
                    (Items.custodianId eq unitId) and
                            (Items.status neq "INACTIVE")
                }
                .count()

            if (itemCount > 0) {
                return@transaction Result.failure(Exception("Cannot delete unit that has active items in its custody"))
            }

            OrganizationalUnits.deleteWhere {
                (OrganizationalUnits.id eq unitId) and
                        (OrganizationalUnits.tuntasId eq tuntasId)
            }

            Result.success(Unit)
        }
    }

    fun getUnitMembers(
        unitId: UUID,
        tuntasId: UUID,
        callerUserId: UUID
    ): Result<UnitMembershipListResponse> {
        return transaction {
            val unit = OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            val hasInternalAccess =
                unit[OrganizationalUnits.type] !in SeniorUnitPrivacyService.seniorUnitTypes ||
                    SeniorUnitPrivacyService.userHasInternalAccess(callerUserId, tuntasId, unitId)
            if (unit[OrganizationalUnits.type] in SeniorUnitPrivacyService.seniorUnitTypes) {
                SeniorUnitAccessAudit.insert {
                    it[this.tuntasId] = tuntasId
                    it[this.unitId] = unitId
                    it[actorUserId] = callerUserId
                    it[action] = "VIEW_MEMBER_LIST"
                    it[accessMode] = if (hasInternalAccess) "INTERNAL" else "PUBLIC"
                    it[createdAt] = kotlinx.datetime.Clock.System.now()
                }
            }

            val memberRows = UnitAssignments
                .innerJoin(Users, { UnitAssignments.userId }, { Users.id })
                .selectAll()
                .where {
                    (UnitAssignments.organizationalUnitId eq unitId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.leftAt.isNull()) and
                            Users.deletedAt.isNull()
                }
                .toList()
            val rankNamesByUserId = if (hasInternalAccess || memberRows.isEmpty()) {
                emptyMap()
            } else {
                UserRanks
                    .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
                    .select(UserRanks.userId, Roles.name)
                    .where {
                        (UserRanks.userId inList memberRows.map { it[UnitAssignments.userId] }.distinct()) and
                            (UserRanks.tuntasId eq tuntasId)
                    }
                    .groupBy(
                        keySelector = { it[UserRanks.userId] },
                        valueTransform = { it[Roles.name] }
                    )
            }

            val members = memberRows.mapNotNull { row ->
                val rankNames = rankNamesByUserId[row[UnitAssignments.userId]].orEmpty()
                val hideIdentity = !hasInternalAccess &&
                    "Vyr. skautas kandidatas" in rankNames &&
                    !row[UnitAssignments.isPubliclyVisible]
                when {
                    hasInternalAccess -> toUnitMembershipResponse(row)
                    hideIdentity -> toUnitMembershipResponse(row, hideIdentity = true)
                    "Vyr. skautas" in rankNames || row[UnitAssignments.isPubliclyVisible] ->
                        toUnitMembershipResponse(row)
                    else -> null
                }
            }

            Result.success(UnitMembershipListResponse(members = members, total = members.size))
        }
    }

    fun getSeniorUnitAccessAudit(
        unitId: UUID,
        tuntasId: UUID,
        callerUserId: UUID
    ): Result<SeniorUnitAccessAuditListResponse> = transaction {
        if (!SeniorUnitPrivacyService.canManageCandidateVisibility(callerUserId, tuntasId, unitId)) {
            return@transaction Result.failure(Exception("Only this senior unit's active leader can view the access audit"))
        }
        val entries = SeniorUnitAccessAudit
            .innerJoin(Users, { SeniorUnitAccessAudit.actorUserId }, { Users.id })
            .selectAll()
            .where {
                (SeniorUnitAccessAudit.unitId eq unitId) and
                    (SeniorUnitAccessAudit.tuntasId eq tuntasId)
            }
            .orderBy(SeniorUnitAccessAudit.createdAt, SortOrder.DESC)
            .limit(200)
            .map {
                SeniorUnitAccessAuditResponse(
                    id = it[SeniorUnitAccessAudit.id].toString(),
                    actorUserId = it[SeniorUnitAccessAudit.actorUserId].toString(),
                    actorUserName = "${it[Users.name]} ${it[Users.surname]}".trim(),
                    action = it[SeniorUnitAccessAudit.action],
                    accessMode = it[SeniorUnitAccessAudit.accessMode],
                    createdAt = it[SeniorUnitAccessAudit.createdAt].toString()
                )
            }
        Result.success(SeniorUnitAccessAuditListResponse(entries, entries.size))
    }

    fun updateUnitMemberVisibility(
        unitId: UUID,
        targetUserId: UUID,
        tuntasId: UUID,
        callerUserId: UUID,
        request: UpdateUnitMemberVisibilityRequest
    ): Result<UnitMembershipResponse> = transaction {
        if (!SeniorUnitPrivacyService.canManageCandidateVisibility(callerUserId, tuntasId, unitId)) {
            return@transaction Result.failure(Exception("Only this senior unit's active leader can change candidate visibility"))
        }

        val assignment = UnitAssignments
            .innerJoin(Users, { UnitAssignments.userId }, { Users.id })
            .selectAll()
                .where {
                    (UnitAssignments.userId eq targetUserId) and
                        (UnitAssignments.organizationalUnitId eq unitId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        UnitAssignments.leftAt.isNull() and
                        Users.deletedAt.isNull()
                }
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Unit member not found"))

        val isCandidate = UserRanks
            .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserRanks.userId eq targetUserId) and
                    (UserRanks.tuntasId eq tuntasId) and
                    (Roles.name eq "Vyr. skautas kandidatas")
            }
            .firstOrNull() != null
        if (!isCandidate) {
            return@transaction Result.failure(Exception("Visibility can only be changed for a senior scout candidate"))
        }

        UnitAssignments.update({ UnitAssignments.id eq assignment[UnitAssignments.id] }) {
            it[isPubliclyVisible] = request.isPubliclyVisible
        }

            val updated = UnitAssignments
                .innerJoin(Users, { UnitAssignments.userId }, { Users.id })
                .selectAll()
                .where { (UnitAssignments.id eq assignment[UnitAssignments.id]) and Users.deletedAt.isNull() }
                .first()
        Result.success(toUnitMembershipResponse(updated))
    }

    fun assignUnitMember(
        unitId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID,
        request: AssignUnitMemberRequest
    ): Result<UnitMembershipResponse> {
        return transaction {
            val unit = OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            if (request.assignmentType !in validAssignmentTypes) {
                return@transaction Result.failure(Exception("Invalid assignmentType. Must be one of: ${validAssignmentTypes.joinToString()}"))
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

            // Rank validation — only enforce for primary MEMBER assignments
            UnitAssignments.selectAll()
                .where {
                    (UnitAssignments.userId eq userUUID) and
                        (UnitAssignments.organizationalUnitId eq unitId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.assignmentType eq request.assignmentType) and
                        (UnitAssignments.leftAt.isNull())
                }
                .firstOrNull()
                ?.let {
                    return@transaction Result.failure(Exception("User already has this active assignment in the selected unit"))
                }

            val acceptedRankId = unit[OrganizationalUnits.acceptedRankId]
            if (acceptedRankId != null && request.assignmentType == "MEMBER") {
                val userHasRank = UserRanks.selectAll()
                    .where {
                        (UserRanks.userId eq userUUID) and
                                (UserRanks.tuntasId eq tuntasId) and
                                (UserRanks.roleId eq acceptedRankId)
                    }
                    .firstOrNull()
                if (userHasRank == null) {
                    return@transaction Result.failure(Exception("User's rank does not match the accepted rank for this unit"))
                }
            }

            // For primary MEMBER assignments, check user does not already have
            // an active primary membership in another unit of the same type
            if (request.assignmentType == "MEMBER") {
                val unitType = unit[OrganizationalUnits.type]
                val existingPrimary = UnitAssignments
                    .innerJoin(OrganizationalUnits, { UnitAssignments.organizationalUnitId }, { OrganizationalUnits.id })
                    .selectAll()
                    .where {
                        (UnitAssignments.userId eq userUUID) and
                                (UnitAssignments.tuntasId eq tuntasId) and
                                (UnitAssignments.assignmentType eq "MEMBER") and
                                (UnitAssignments.leftAt.isNull()) and
                                (OrganizationalUnits.type eq unitType)
                    }
                    .firstOrNull()

                if (existingPrimary != null) {
                    return@transaction Result.failure(Exception("User already has an active primary membership in a unit of this type"))
                }
            }

            val assignmentId = UnitAssignments.insert {
                it[userId] = userUUID
                it[organizationalUnitId] = unitId
                it[this.tuntasId] = tuntasId
                it[assignmentType] = request.assignmentType
                it[this.assignedByUserId] = assignedByUserId
            } get UnitAssignments.id

            val inserted = UnitAssignments
                .innerJoin(Users, { UnitAssignments.userId }, { Users.id })
                .selectAll()
                .where { (UnitAssignments.id eq assignmentId) and Users.deletedAt.isNull() }
                .first()

            Result.success(toUnitMembershipResponse(inserted))
        }
    }

    fun moveUnitMember(
        targetUnitId: UUID,
        tuntasId: UUID,
        targetUserId: UUID,
        assignedByUserId: UUID
    ): Result<UnitMembershipResponse> {
        return transaction {
            val targetUnit = OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq targetUnitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq targetUserId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("User is not an active member of this tuntas"))

            val activeMemberAssignments = UnitAssignments
                .innerJoin(OrganizationalUnits, { UnitAssignments.organizationalUnitId }, { OrganizationalUnits.id })
                .selectAll()
                .where {
                    (UnitAssignments.userId eq targetUserId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.assignmentType eq "MEMBER") and
                            (UnitAssignments.leftAt.isNull())
                }
                .toList()

            activeMemberAssignments
                .filter { it[UnitAssignments.organizationalUnitId] != targetUnitId }
                .map { it[UnitAssignments.id] }
                .forEach { assignmentId ->
                    UnitAssignments.update({ UnitAssignments.id eq assignmentId }) {
                        it[leftAt] = kotlinx.datetime.Clock.System.now()
                    }
                }

            val existingTargetAssignment = activeMemberAssignments
                .firstOrNull { it[UnitAssignments.organizationalUnitId] == targetUnitId }

            val assignmentId = existingTargetAssignment?.get(UnitAssignments.id)
                ?: UnitAssignments.insert {
                    it[userId] = targetUserId
                    it[organizationalUnitId] = targetUnitId
                    it[this.tuntasId] = tuntasId
                    it[assignmentType] = "MEMBER"
                    it[this.assignedByUserId] = assignedByUserId
                } get UnitAssignments.id

            val moved = UnitAssignments
                .innerJoin(Users, { UnitAssignments.userId }, { Users.id })
                .selectAll()
                .where { (UnitAssignments.id eq assignmentId) and Users.deletedAt.isNull() }
                .first()

            Result.success(toUnitMembershipResponse(moved))
        }
    }

    fun removeUnitMember(
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

            UnitAssignments.selectAll()
                .where {
                    (UnitAssignments.userId eq targetUserId) and
                            (UnitAssignments.organizationalUnitId eq unitId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Active unit membership not found for this user"))

            val now = kotlinx.datetime.Clock.System.now()

            UnitAssignments.update({
                (UnitAssignments.userId eq targetUserId) and
                        (UnitAssignments.organizationalUnitId eq unitId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }) {
                it[leftAt] = now
            }

            UserLeadershipRoles.update({
                (UserLeadershipRoles.userId eq targetUserId) and
                        (UserLeadershipRoles.organizationalUnitId eq unitId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull())
            }) {
                it[termStatus] = "RESIGNED"
                it[leftAt] = now
            }

            Result.success(Unit)
        }
    }

    fun leaveUnit(
        unitId: UUID,
        tuntasId: UUID,
        callerUserId: UUID
    ): Result<Unit> {
        return transaction {
            OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq unitId) and
                            (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Organizational unit not found"))

            UnitAssignments.selectAll()
                .where {
                    (UnitAssignments.userId eq callerUserId) and
                            (UnitAssignments.organizationalUnitId eq unitId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Active unit membership not found for this user"))

            val activeLeadershipRole = UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.userId eq callerUserId) and
                            (UserLeadershipRoles.organizationalUnitId eq unitId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.leftAt.isNull())
                }
                .firstOrNull()

            if (activeLeadershipRole != null) {
                return@transaction Result.failure(Exception("Step down from active leadership roles before leaving this unit"))
            }

            UnitAssignments.update({
                (UnitAssignments.userId eq callerUserId) and
                        (UnitAssignments.organizationalUnitId eq unitId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }) {
                it[leftAt] = kotlinx.datetime.Clock.System.now()
            }

            Result.success(Unit)
        }
    }

    private data class OrganizationalUnitListHydration(
        val acceptedRankNamesById: Map<UUID, String>,
        val memberCountsByUnitId: Map<UUID, Int>,
        val itemCountsByUnitId: Map<UUID, Int>
    )

    private fun buildListHydration(rows: List<ResultRow>, tuntasId: UUID): OrganizationalUnitListHydration {
        if (rows.isEmpty()) {
            return OrganizationalUnitListHydration(emptyMap(), emptyMap(), emptyMap())
        }

        val unitIds = rows.map { it[OrganizationalUnits.id] }
        val acceptedRankIds = rows.mapNotNull { it[OrganizationalUnits.acceptedRankId] }.toSet()
        val acceptedRankNamesById = if (acceptedRankIds.isEmpty()) {
            emptyMap()
        } else {
            Roles.selectAll()
                .where { Roles.id inList acceptedRankIds.toList() }
                .associate { it[Roles.id] to it[Roles.name] }
        }

        val memberCountExpr = UnitAssignments.id.count()
        val memberCountsByUnitId = UnitAssignments
            .select(UnitAssignments.organizationalUnitId, memberCountExpr)
            .where {
                (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.organizationalUnitId inList unitIds) and
                    UnitAssignments.leftAt.isNull()
            }
            .groupBy(UnitAssignments.organizationalUnitId)
            .associate { it[UnitAssignments.organizationalUnitId] to it[memberCountExpr].toInt() }

        val itemCountExpr = Items.id.count()
        val itemCountsByUnitId = Items
            .select(Items.custodianId, itemCountExpr)
            .where {
                (Items.tuntasId eq tuntasId) and
                    (Items.custodianId inList unitIds) and
                    (Items.status neq "INACTIVE")
            }
            .groupBy(Items.custodianId)
            .associateNotNullKeys { it[Items.custodianId] to it[itemCountExpr].toInt() }

        return OrganizationalUnitListHydration(
            acceptedRankNamesById = acceptedRankNamesById,
            memberCountsByUnitId = memberCountsByUnitId,
            itemCountsByUnitId = itemCountsByUnitId
        )
    }

    private fun toResponse(row: ResultRow, hydration: OrganizationalUnitListHydration? = null): OrganizationalUnitResponse {
        val unitId = row[OrganizationalUnits.id]
        val acceptedRankId = row[OrganizationalUnits.acceptedRankId]
        val acceptedRankName = acceptedRankId?.let {
            hydration?.acceptedRankNamesById?.get(it) ?:
            Roles.selectAll()
                .where { Roles.id eq it }
                .firstOrNull()
                ?.get(Roles.name)
        }
        val memberCount = hydration?.memberCountsByUnitId?.get(unitId) ?: UnitAssignments.selectAll()
            .where {
                (UnitAssignments.organizationalUnitId eq unitId) and
                        (UnitAssignments.tuntasId eq row[OrganizationalUnits.tuntasId]) and
                        (UnitAssignments.leftAt.isNull())
            }
            .count()
            .toInt()
        val itemCount = hydration?.itemCountsByUnitId?.get(unitId) ?: Items.selectAll()
            .where {
                (Items.custodianId eq unitId) and
                        (Items.tuntasId eq row[OrganizationalUnits.tuntasId]) and
                        (Items.status neq "INACTIVE")
            }
            .count()
            .toInt()

        return OrganizationalUnitResponse(
            id = unitId.toString(),
            tuntasId = row[OrganizationalUnits.tuntasId].toString(),
            name = row[OrganizationalUnits.name],
            type = row[OrganizationalUnits.type],
            subType = row[OrganizationalUnits.subtype],
            acceptedRankId = acceptedRankId?.toString(),
            acceptedRankName = acceptedRankName,
            memberCount = memberCount,
            itemCount = itemCount,
            createdAt = row[OrganizationalUnits.createdAt].toString()
        )
    }

    private inline fun <T, K : Any, V> Iterable<T>.associateNotNullKeys(transform: (T) -> Pair<K?, V>): Map<K, V> =
        mapNotNull { value ->
            val (key, mappedValue) = transform(value)
            key?.let { it to mappedValue }
        }.toMap()

    private fun toUnitMembershipResponse(
        row: ResultRow,
        hideIdentity: Boolean = false
    ): UnitMembershipResponse {
        val unitId = row[UnitAssignments.organizationalUnitId]
        val unitName = OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq unitId }
            .first()[OrganizationalUnits.name]

        return UnitMembershipResponse(
            id = row[UnitAssignments.id].toString(),
            userId = if (hideIdentity) "hidden-${row[UnitAssignments.id]}" else row[UnitAssignments.userId].toString(),
            userName = if (hideIdentity) "Kandidatas" else row[Users.name],
            userSurname = if (hideIdentity) "" else row[Users.surname],
            organizationalUnitId = unitId.toString(),
            organizationalUnitName = unitName,
            tuntasId = row[UnitAssignments.tuntasId].toString(),
            assignmentType = row[UnitAssignments.assignmentType],
            isPubliclyVisible = row[UnitAssignments.isPubliclyVisible],
            assignedByUserId = row[UnitAssignments.assignedByUserId]?.toString(),
            joinedAt = row[UnitAssignments.joinedAt].toString(),
            leftAt = row[UnitAssignments.leftAt]?.toString(),
            isIdentityHidden = hideIdentity
        )
    }
}
