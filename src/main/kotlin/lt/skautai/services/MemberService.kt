package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.AssignLeadershipRoleRequest
import lt.skautai.models.requests.AssignRankRequest
import lt.skautai.models.requests.UpdateLeadershipRoleRequest
import lt.skautai.models.responses.*
import lt.skautai.plugins.resolveUserPermissions
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class MemberService {

    fun canAccessMemberDirectory(userId: UUID, tuntasId: UUID): Boolean = transaction {
        val callerContext = loadCallerContext(userId, tuntasId) ?: return@transaction false
        val hasMembersViewPermission = resolveUserPermissions(userId, tuntasId).any {
            it.permissionName == "members.view"
        }
        hasMembersViewPermission || callerContext.canViewAllMembers
    }

    fun getMembers(tuntasId: UUID, callerUserId: UUID? = null): Result<MemberListResponse> {
        return transaction {
            val callerContext = callerUserId?.let { effectiveCallerUserId ->
                loadCallerContext(effectiveCallerUserId, tuntasId)
                    ?: return@transaction Result.failure(Exception("You are not an active member of this tuntas"))
            }

            val memberRows = UserTuntasMemberships
                .innerJoin(Users, { UserTuntasMemberships.userId }, { Users.id })
                .selectAll()
                .where {
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull()) and
                        Users.deletedAt.isNull()
                }
                .toList()
            val hydration = buildMemberListHydration(
                rows = memberRows,
                tuntasId = tuntasId,
                callerUserId = callerUserId,
                callerVisibleUnitIds = callerContext?.visibleUnitIds.orEmpty()
            )
            val members = memberRows.mapNotNull { row ->
                val userId = row[UserTuntasMemberships.userId]
                val member = buildMemberResponse(
                    userId = userId,
                    tuntasId = tuntasId,
                    membershipRow = row,
                    callerUserId = callerUserId,
                    callerVisibleUnitIds = callerContext?.visibleUnitIds.orEmpty(),
                    hydration = hydration
                )
                when {
                    callerContext == null || callerCanSeeMemberSummary(member, callerContext) -> member
                    isHiddenSeniorCandidate(member, callerContext) -> anonymizeCandidate(member)
                    else -> null
                }
            }

            Result.success(MemberListResponse(members = members, total = members.size))
        }
    }

    fun getMember(userId: UUID, tuntasId: UUID, callerUserId: UUID? = null): Result<MemberResponse> {
        return transaction {
            val membership = UserTuntasMemberships
                .innerJoin(Users, { UserTuntasMemberships.userId }, { Users.id })
                .selectAll()
                .where {
                    (UserTuntasMemberships.userId eq userId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull()) and
                            Users.deletedAt.isNull()
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Member not found in this tuntas"))

            val callerContext = callerUserId?.let { effectiveCallerUserId ->
                loadCallerContext(effectiveCallerUserId, tuntasId)
                    ?: return@transaction Result.failure(Exception("You are not an active member of this tuntas"))
            }

            val member = buildMemberResponse(
                userId = userId,
                tuntasId = tuntasId,
                membershipRow = membership,
                callerUserId = callerUserId,
                callerVisibleUnitIds = callerContext?.visibleUnitIds.orEmpty()
            )

            if (
                callerContext != null &&
                !callerCanSeeMemberSummary(member, callerContext)
            ) {
                return@transaction Result.failure(Exception("Member not found in this tuntas"))
            }

            Result.success(member)
        }
    }

    fun getEventCandidateMembers(tuntasId: UUID): Result<MemberListResponse> {
        return transaction {
            val memberRows = UserTuntasMemberships
                .innerJoin(Users, { UserTuntasMemberships.userId }, { Users.id })
                .selectAll()
                .where {
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull()) and
                        Users.deletedAt.isNull()
                }
                .toList()
            val hydration = buildMemberListHydration(
                rows = memberRows,
                tuntasId = tuntasId,
                callerUserId = null,
                callerVisibleUnitIds = emptySet()
            )
            val members = memberRows.mapNotNull { row ->
                    val userId = row[UserTuntasMemberships.userId]
                    val member = buildMemberResponse(
                        userId = userId,
                        tuntasId = tuntasId,
                        membershipRow = row,
                        callerUserId = null,
                        callerVisibleUnitIds = emptySet(),
                        hydration = hydration
                    )
                    member.takeIf(::isEligibleEventCandidate)
                }

            Result.success(MemberListResponse(members = members, total = members.size))
        }
    }

    fun assignLeadershipRole(
        targetUserId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID?,
        request: AssignLeadershipRoleRequest
    ): Result<MemberLeadershipRoleResponse> {
        return transaction {
            // Verify target user is a member
            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq targetUserId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("User is not a member of this tuntas"))

            val roleUUID = try { UUID.fromString(request.roleId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid role ID"))
            }

            // Verify role exists, belongs to tuntas, and is LEADERSHIP type
            val role = Roles.selectAll()
                .where {
                    (Roles.id eq roleUUID) and
                            (Roles.tuntasId eq tuntasId) and
                            (Roles.roleType eq "LEADERSHIP")
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Leadership role not found in this tuntas"))

            if (LeadershipRoleRules.isTuntininkas(role[Roles.name])) {
                return@transaction Result.failure(Exception("Tuntininkas role can only be transferred"))
            }

            val orgUnitUUID = request.organizationalUnitId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }

            if (orgUnitUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq orgUnitUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Organizational unit not found in this tuntas"))
            }

            LeadershipRoleRules.validatePrincipalUnitLeaderSlot(roleUUID, tuntasId, orgUnitUUID)
                ?.let { return@transaction Result.failure(Exception(it)) }

            val startsAt = request.startsAt?.let {
                try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid startsAt format, use ISO 8601"))
                }
            }

            val expiresAt = request.expiresAt?.let {
                try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid expiresAt format, use ISO 8601"))
                }
            }

            val assignmentId = UserLeadershipRoles.insert {
                it[userId] = targetUserId
                it[roleId] = roleUUID
                it[this.tuntasId] = tuntasId
                it[organizationalUnitId] = orgUnitUUID
                it[this.assignedByUserId] = assignedByUserId
                it[termNumber] = request.termNumber
                it[this.startsAt] = startsAt
                it[this.expiresAt] = expiresAt
                it[termStatus] = "ACTIVE"
            } get UserLeadershipRoles.id

            val assignment = UserLeadershipRoles.selectAll()
                .where { UserLeadershipRoles.id eq assignmentId }
                .first()

            VadovasRankSupport.ensureVadovasRank(
                userId = targetUserId,
                tuntasId = tuntasId,
                assignedByUserId = assignedByUserId ?: targetUserId
            )

            Result.success(
                toLeadershipRoleResponse(
                    assignment,
                    role[Roles.name],
                    orgUnitUUID?.let { getOrgUnitName(it) }
                )
            )
        }
    }

    fun updateLeadershipRole(
        targetUserId: UUID,
        assignmentId: UUID,
        tuntasId: UUID,
        callerUserId: UUID?,
        request: UpdateLeadershipRoleRequest,
        bypassHierarchyValidation: Boolean = false
    ): Result<MemberLeadershipRoleResponse> {
        return transaction {
            val assignment = UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.id eq assignmentId) and
                            (UserLeadershipRoles.userId eq targetUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Leadership role assignment not found"))

            request.termStatus?.let {
                if (it !in listOf("ACTIVE", "COMPLETED", "RESIGNED")) {
                    return@transaction Result.failure(Exception("Invalid term status"))
                }
            }

            val orgUnitUUID = request.organizationalUnitId?.let {
                try { UUID.fromString(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }

            if (orgUnitUUID != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq orgUnitUUID) and
                                (OrganizationalUnits.tuntasId eq tuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Organizational unit not found in this tuntas"))
            }

            val startsAt = request.startsAt?.let {
                try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid startsAt format, use ISO 8601"))
                }
            }

            val expiresAt = request.expiresAt?.let {
                try { kotlinx.datetime.Instant.parse(it) } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid expiresAt format, use ISO 8601"))
                }
            }

            if (!bypassHierarchyValidation && request.termStatus in listOf("COMPLETED", "RESIGNED")) {
                val effectiveCallerUserId = callerUserId
                    ?: return@transaction Result.failure(Exception("Caller user is required"))
                validateCanClosePrincipalUnitLeaderDirectly(
                    roleId = assignment[UserLeadershipRoles.roleId],
                    organizationalUnitId = assignment[UserLeadershipRoles.organizationalUnitId]
                )?.let { return@transaction Result.failure(Exception(it)) }
                validateCanChangeTargetLeadership(
                    callerUserId = effectiveCallerUserId,
                    targetUserId = targetUserId,
                    tuntasId = tuntasId,
                    targetRoleId = assignment[UserLeadershipRoles.roleId]
                )?.let { return@transaction Result.failure(Exception(it)) }
            }

            val finalStatus = request.termStatus ?: assignment[UserLeadershipRoles.termStatus]
            val finalOrgUnit = orgUnitUUID ?: assignment[UserLeadershipRoles.organizationalUnitId]
            if (finalStatus == "ACTIVE") {
                LeadershipRoleRules.validatePrincipalUnitLeaderSlot(
                    roleId = assignment[UserLeadershipRoles.roleId],
                    tuntasId = tuntasId,
                    organizationalUnitId = finalOrgUnit,
                    excludeAssignmentId = assignmentId
                )?.let { return@transaction Result.failure(Exception(it)) }
            }

            UserLeadershipRoles.update({
                (UserLeadershipRoles.id eq assignmentId) and
                        (UserLeadershipRoles.userId eq targetUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId)
            }) {
                request.termStatus?.let { v -> it[termStatus] = v }
                startsAt?.let { v -> it[UserLeadershipRoles.startsAt] = v }
                expiresAt?.let { v -> it[UserLeadershipRoles.expiresAt] = v }
                orgUnitUUID?.let { v -> it[organizationalUnitId] = v }
                when (request.termStatus) {
                    "ACTIVE" -> it[leftAt] = null
                    "COMPLETED", "RESIGNED" -> it[leftAt] = kotlinx.datetime.Clock.System.now()
                }
            }

            val updated = UserLeadershipRoles.selectAll()
                .where { UserLeadershipRoles.id eq assignmentId }
                .first()

            val roleId = updated[UserLeadershipRoles.roleId]
            val roleName = Roles.selectAll()
                .where { Roles.id eq roleId }
                .first()[Roles.name]

            val orgUnit = updated[UserLeadershipRoles.organizationalUnitId]
            Result.success(
                toLeadershipRoleResponse(
                    updated,
                    roleName,
                    orgUnit?.let { getOrgUnitName(it) }
                )
            )
        }
    }

    fun removeLeadershipRole(
        targetUserId: UUID,
        assignmentId: UUID,
        tuntasId: UUID,
        callerUserId: UUID?,
        bypassHierarchyValidation: Boolean = false
    ): Result<Unit> {
        return transaction {
            val assignment = UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.id eq assignmentId) and
                            (UserLeadershipRoles.userId eq targetUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Leadership role assignment not found"))

            if (!bypassHierarchyValidation) {
                val effectiveCallerUserId = callerUserId
                    ?: return@transaction Result.failure(Exception("Caller user is required"))
                validateCanClosePrincipalUnitLeaderDirectly(
                    roleId = assignment[UserLeadershipRoles.roleId],
                    organizationalUnitId = assignment[UserLeadershipRoles.organizationalUnitId]
                )?.let { return@transaction Result.failure(Exception(it)) }
                validateCanChangeTargetLeadership(
                    callerUserId = effectiveCallerUserId,
                    targetUserId = targetUserId,
                    tuntasId = tuntasId,
                    targetRoleId = assignment[UserLeadershipRoles.roleId]
                )?.let { return@transaction Result.failure(Exception(it)) }
            }

            val now = kotlinx.datetime.Clock.System.now()
            UserLeadershipRoles.update({
                (UserLeadershipRoles.id eq assignmentId) and
                        (UserLeadershipRoles.userId eq targetUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId)
            }) {
                it[termStatus] = "RESIGNED"
                it[leftAt] = now
            }

            Result.success(Unit)
        }
    }

    fun stepDownLeadershipRole(
        callerUserId: UUID,
        assignmentId: UUID,
        tuntasId: UUID
    ): Result<Unit> {
        return transaction {
            lockActiveTuntininkasAssignments(tuntasId)

            val assignment = UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.id eq assignmentId) and
                            (UserLeadershipRoles.userId eq callerUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Active leadership role assignment not found"))

            validateCanCloseOwnLeadershipRole(
                callerUserId = callerUserId,
                tuntasId = tuntasId,
                assignmentId = assignmentId,
                roleId = assignment[UserLeadershipRoles.roleId]
            )?.let { return@transaction Result.failure(Exception(it)) }

            val now = kotlinx.datetime.Clock.System.now()
            UserLeadershipRoles.update({
                (UserLeadershipRoles.id eq assignmentId) and
                        (UserLeadershipRoles.userId eq callerUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId)
            }) {
                it[termStatus] = "RESIGNED"
                it[leftAt] = now
            }

            Result.success(Unit)
        }
    }

    fun transferTuntininkas(
        callerUserId: UUID,
        tuntasId: UUID,
        successorUserId: UUID
    ): Result<Unit> {
        return transaction {
            val activeTuntininkasAssignments = lockActiveTuntininkasAssignments(tuntasId)
            val callerAssignments = activeTuntininkasAssignments
                .filter { it[UserLeadershipRoles.userId] == callerUserId }

            if (callerAssignments.isEmpty()) {
                return@transaction Result.failure(Exception("Only active tuntininkas can transfer this role"))
            }

            if (successorUserId == callerUserId) {
                return@transaction Result.failure(Exception("Choose a different member to become tuntininkas"))
            }

            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq successorUserId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId) and
                        (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Successor must be an active member of this tuntas"))

            val now = kotlinx.datetime.Clock.System.now()
            val tuntininkasRoleId = callerAssignments.first()[UserLeadershipRoles.roleId]

            UserLeadershipRoles.update({
                (UserLeadershipRoles.userId eq successorUserId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull())
            }) {
                it[termStatus] = "RESIGNED"
                it[leftAt] = now
            }

            UserLeadershipRoles.insert {
                it[userId] = successorUserId
                it[roleId] = tuntininkasRoleId
                it[this.tuntasId] = tuntasId
                it[assignedByUserId] = callerUserId
                it[startsAt] = now
                it[termStatus] = "ACTIVE"
            }

            UserLeadershipRoles.update({
                (UserLeadershipRoles.id inList callerAssignments.map { it[UserLeadershipRoles.id] }) and
                    (UserLeadershipRoles.tuntasId eq tuntasId)
            }) {
                it[termStatus] = "RESIGNED"
                it[leftAt] = now
            }

            VadovasRankSupport.ensureVadovasRank(
                userId = successorUserId,
                tuntasId = tuntasId,
                assignedByUserId = callerUserId
            )

            Result.success(Unit)
        }
    }

    fun assignRank(
        targetUserId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID?,
        request: AssignRankRequest
    ): Result<MemberRankResponse> {
        return transaction {
            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq targetUserId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("User is not a member of this tuntas"))

            val roleUUID = try { UUID.fromString(request.roleId) } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid role ID"))
            }

            val role = Roles.selectAll()
                .where {
                    (Roles.id eq roleUUID) and
                            (Roles.tuntasId eq tuntasId) and
                            (Roles.roleType eq "RANK")
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Rank role not found in this tuntas"))

            if (role[Roles.name] !in supportedRankNames) {
                return@transaction Result.failure(Exception("Selected rank is not available"))
            }

            val rankId = UserRanks.insert {
                it[userId] = targetUserId
                it[roleId] = roleUUID
                it[this.tuntasId] = tuntasId
                it[this.assignedByUserId] = assignedByUserId
            } get UserRanks.id

            val rank = UserRanks.selectAll()
                .where { UserRanks.id eq rankId }
                .first()

            Result.success(toRankResponse(rank, role[Roles.name]))
        }
    }

    fun removeRank(
        targetUserId: UUID,
        rankId: UUID,
        tuntasId: UUID
    ): Result<Unit> {
        return transaction {
            UserRanks.selectAll()
                .where {
                    (UserRanks.id eq rankId) and
                            (UserRanks.userId eq targetUserId) and
                            (UserRanks.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Rank assignment not found"))

            UserRanks.deleteWhere {
                (UserRanks.id eq rankId) and
                        (UserRanks.userId eq targetUserId) and
                        (UserRanks.tuntasId eq tuntasId)
            }

            Result.success(Unit)
        }
    }

    private fun buildMemberResponse(
        userId: UUID,
        tuntasId: UUID,
        membershipRow: ResultRow,
        callerUserId: UUID?,
        callerVisibleUnitIds: Set<UUID>,
        hydration: MemberListHydration? = null
    ): MemberResponse {
        val activeLeadershipRows = hydration?.activeLeadershipRolesByUserId?.get(userId)
            ?: UserLeadershipRoles
                .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq userId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.leftAt.isNull())
                }
                .toList()
        val leadershipRoles = activeLeadershipRows.map { row ->
                val orgUnitId = row[UserLeadershipRoles.organizationalUnitId]
                toLeadershipRoleResponse(
                    row,
                    row[Roles.name],
                    orgUnitId?.let { hydration?.orgUnitNamesById?.get(it) ?: getOrgUnitName(it) }
                )
            }

        val leadershipHistoryRows = hydration?.leadershipRoleHistoryByUserId?.get(userId)
            ?: UserLeadershipRoles
                .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserLeadershipRoles.userId eq userId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            ((UserLeadershipRoles.termStatus neq "ACTIVE") or UserLeadershipRoles.leftAt.isNotNull())
                }
                .toList()
        val leadershipRoleHistory = leadershipHistoryRows.map { row ->
                val orgUnitId = row[UserLeadershipRoles.organizationalUnitId]
                toLeadershipRoleResponse(
                    row,
                    row[Roles.name],
                    orgUnitId?.let { hydration?.orgUnitNamesById?.get(it) ?: getOrgUnitName(it) }
                )
            }

        val rankRows = hydration?.ranksByUserId?.get(userId)
            ?: UserRanks
                .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserRanks.userId eq userId) and
                            (UserRanks.tuntasId eq tuntasId)
                }
                .toList()
        val ranks = rankRows.map { row -> toRankResponse(row, row[Roles.name]) }

        val unitAssignmentRows = hydration?.unitAssignmentsByUserId?.get(userId)
            ?: UnitAssignments
                .innerJoin(OrganizationalUnits, { UnitAssignments.organizationalUnitId }, { OrganizationalUnits.id })
                .selectAll()
                .where {
                    (UnitAssignments.userId eq userId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.leftAt.isNull())
                }
                .toList()
        val unitAssignments = unitAssignmentRows.map { row ->
                val unitId = row[UnitAssignments.organizationalUnitId]
                MemberUnitAssignmentResponse(
                    id = row[UnitAssignments.id].toString(),
                    organizationalUnitId = unitId.toString(),
                    organizationalUnitName = hydration?.orgUnitNamesById?.get(unitId) ?: row[OrganizationalUnits.name],
                    assignmentType = row[UnitAssignments.assignmentType],
                    isPubliclyVisible = row[UnitAssignments.isPubliclyVisible],
                    joinedAt = row[UnitAssignments.joinedAt].toString()
                )
            }

        val canSeeContacts = hydration?.contactVisibleUserIds?.let { userId in it }
            ?: canSeeMemberContacts(
                targetUserId = userId,
                callerUserId = callerUserId,
                callerVisibleUnitIds = callerVisibleUnitIds,
                tuntasId = tuntasId
            )

        return MemberResponse(
            userId = membershipRow[Users.id].toString(),
            name = membershipRow[Users.name],
            surname = membershipRow[Users.surname],
            email = membershipRow[Users.email].takeIf { canSeeContacts }.orEmpty(),
            phone = membershipRow[Users.phone]?.takeIf { canSeeContacts },
            joinedAt = membershipRow[UserTuntasMemberships.joinedAt].toString(),
            unitAssignments = unitAssignments,
            leadershipRoles = leadershipRoles,
            leadershipRoleHistory = leadershipRoleHistory,
            ranks = ranks
        )
    }

    private data class MemberListHydration(
        val activeLeadershipRolesByUserId: Map<UUID, List<ResultRow>>,
        val leadershipRoleHistoryByUserId: Map<UUID, List<ResultRow>>,
        val ranksByUserId: Map<UUID, List<ResultRow>>,
        val unitAssignmentsByUserId: Map<UUID, List<ResultRow>>,
        val orgUnitNamesById: Map<UUID, String>,
        val contactVisibleUserIds: Set<UUID>
    )

    private fun buildMemberListHydration(
        rows: List<ResultRow>,
        tuntasId: UUID,
        callerUserId: UUID?,
        callerVisibleUnitIds: Set<UUID>
    ): MemberListHydration {
        if (rows.isEmpty()) {
            return MemberListHydration(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet())
        }

        val userIds = rows.map { it[UserTuntasMemberships.userId] }
        val leadershipRows = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId inList userIds) and
                    (UserLeadershipRoles.tuntasId eq tuntasId)
            }
            .toList()
        val activeLeadershipRows = leadershipRows.filter {
            it[UserLeadershipRoles.termStatus] == "ACTIVE" && it[UserLeadershipRoles.leftAt] == null
        }
        val leadershipHistoryRows = leadershipRows.filter {
            it[UserLeadershipRoles.termStatus] != "ACTIVE" || it[UserLeadershipRoles.leftAt] != null
        }
        val rankRows = UserRanks
            .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserRanks.userId inList userIds) and
                    (UserRanks.tuntasId eq tuntasId)
            }
            .toList()
        val assignmentRows = UnitAssignments
            .innerJoin(OrganizationalUnits, { UnitAssignments.organizationalUnitId }, { OrganizationalUnits.id })
            .selectAll()
            .where {
                (UnitAssignments.userId inList userIds) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    UnitAssignments.leftAt.isNull()
            }
            .toList()

        val orgUnitIds = (
            leadershipRows.mapNotNull { it[UserLeadershipRoles.organizationalUnitId] } +
                assignmentRows.map { it[UnitAssignments.organizationalUnitId] }
            ).toSet()
        val orgUnitNamesById = if (orgUnitIds.isEmpty()) {
            emptyMap()
        } else {
            OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.id inList orgUnitIds.toList() }
                .associate { it[OrganizationalUnits.id] to it[OrganizationalUnits.name] }
        }

        val contactVisibleUserIds = when {
            callerUserId == null -> userIds.toSet()
            callerVisibleUnitIds.isEmpty() -> setOf(callerUserId)
            else -> activeLeadershipRows
                .filter { it[UserLeadershipRoles.organizationalUnitId] in callerVisibleUnitIds }
                .map { it[UserLeadershipRoles.userId] }
                .toSet() + callerUserId
        }

        return MemberListHydration(
            activeLeadershipRolesByUserId = activeLeadershipRows.groupBy { it[UserLeadershipRoles.userId] },
            leadershipRoleHistoryByUserId = leadershipHistoryRows.groupBy { it[UserLeadershipRoles.userId] },
            ranksByUserId = rankRows.groupBy { it[UserRanks.userId] },
            unitAssignmentsByUserId = assignmentRows.groupBy { it[UnitAssignments.userId] },
            orgUnitNamesById = orgUnitNamesById,
            contactVisibleUserIds = contactVisibleUserIds
        )
    }

    private fun loadCallerVisibleUnitIds(userId: UUID, tuntasId: UUID): Set<UUID> {
        val membershipUnitIds = UnitAssignments.selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.leftAt.isNull())
            }
            .map { it[UnitAssignments.organizationalUnitId] }

        val leadershipUnitIds = UserLeadershipRoles.selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull()) and
                    (UserLeadershipRoles.organizationalUnitId.isNotNull())
            }
            .mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }

        return (membershipUnitIds + leadershipUnitIds).toSet()
    }

    private fun loadCallerContext(userId: UUID, tuntasId: UUID): CallerContext? {
        val isActiveMember = UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq userId) and
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                    (UserTuntasMemberships.leftAt.isNull())
            }
            .firstOrNull() != null

        if (!isActiveMember) return null

        val resolvedPermissions = resolveUserPermissions(userId, tuntasId)

        return CallerContext(
            userId = userId,
            tuntasId = tuntasId,
            seniorUnitIds = SeniorUnitPrivacyService.seniorUnitIds(tuntasId),
            visibleUnitIds = loadCallerVisibleUnitIds(userId, tuntasId),
            canViewAllMembers = resolvedPermissions.any {
                it.permissionName == "members.view" && it.scope == "ALL"
            }
        )
    }

    private fun callerCanSeeMemberSummary(member: MemberResponse, callerContext: CallerContext): Boolean {
        if (member.userId == callerContext.userId.toString()) return true

        val seniorAssignments = member.unitAssignments.filter { assignment ->
            runCatching { UUID.fromString(assignment.organizationalUnitId) }.getOrNull()
                ?.let { it in callerContext.seniorUnitIds } == true
        }
        if (seniorAssignments.isNotEmpty()) {
            val hasInternalAccess = seniorAssignments.any { assignment ->
                UUID.fromString(assignment.organizationalUnitId) in callerContext.visibleUnitIds
            }
            if (!hasInternalAccess) {
                val isSeniorScout = member.ranks.any { it.roleName == "Vyr. skautas" }
                val isApprovedCandidate = member.ranks.any { it.roleName == "Vyr. skautas kandidatas" } &&
                    seniorAssignments.any { it.isPubliclyVisible }
                if (!isSeniorScout && !isApprovedCandidate) return false
            }
        }

        if (callerContext.canViewAllMembers) return true
        if (callerContext.visibleUnitIds.isEmpty()) return false

        val memberUnitIds = member.unitAssignments
            .mapNotNull { runCatching { UUID.fromString(it.organizationalUnitId) }.getOrNull() }
            .toSet()
        if (memberUnitIds.any { it in callerContext.visibleUnitIds }) return true

        return member.leadershipRoles.any { role ->
            role.organizationalUnitId
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?.let { it in callerContext.visibleUnitIds } == true
        }
    }

    private fun isHiddenSeniorCandidate(member: MemberResponse, callerContext: CallerContext): Boolean {
        if (member.ranks.none { it.roleName == "Vyr. skautas kandidatas" }) return false
        return member.unitAssignments.any { assignment ->
            val unitId = runCatching { UUID.fromString(assignment.organizationalUnitId) }.getOrNull()
                ?: return@any false
            unitId in callerContext.seniorUnitIds &&
                unitId !in callerContext.visibleUnitIds &&
                !assignment.isPubliclyVisible
        }
    }

    private fun anonymizeCandidate(member: MemberResponse): MemberResponse {
        val assignmentId = member.unitAssignments.firstOrNull()?.id ?: member.userId
        return member.copy(
            userId = "hidden-$assignmentId",
            name = "Kandidatas",
            surname = "",
            email = "",
            phone = null,
            leadershipRoles = emptyList(),
            leadershipRoleHistory = emptyList(),
            ranks = emptyList(),
            isIdentityHidden = true
        )
    }

    private fun canSeeMemberContacts(
        targetUserId: UUID,
        callerUserId: UUID?,
        callerVisibleUnitIds: Set<UUID>,
        tuntasId: UUID
    ): Boolean {
        if (callerUserId == null) {
            return true
        }

        if (targetUserId == callerUserId) {
            return true
        }

        if (callerVisibleUnitIds.isEmpty()) {
            return false
        }

        return UserLeadershipRoles.selectAll()
            .where {
                (UserLeadershipRoles.userId eq targetUserId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull()) and
                    (UserLeadershipRoles.organizationalUnitId inList callerVisibleUnitIds.toList())
            }
            .firstOrNull() != null
    }

    private fun getOrgUnitName(orgUnitId: UUID): String? {
        return OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq orgUnitId }
            .firstOrNull()
            ?.get(OrganizationalUnits.name)
    }

    private fun toLeadershipRoleResponse(
        row: ResultRow,
        roleName: String,
        orgUnitName: String?
    ): MemberLeadershipRoleResponse {
        return MemberLeadershipRoleResponse(
            id = row[UserLeadershipRoles.id].toString(),
            roleId = row[UserLeadershipRoles.roleId].toString(),
            roleName = roleName,
            organizationalUnitId = row[UserLeadershipRoles.organizationalUnitId]?.toString(),
            organizationalUnitName = orgUnitName,
            assignedByUserId = row[UserLeadershipRoles.assignedByUserId]?.toString(),
            assignedAt = row[UserLeadershipRoles.assignedAt].toString(),
            startsAt = row[UserLeadershipRoles.startsAt]?.toString(),
            expiresAt = row[UserLeadershipRoles.expiresAt]?.toString(),
            leftAt = row[UserLeadershipRoles.leftAt]?.toString(),
            termNumber = row[UserLeadershipRoles.termNumber],
            termStatus = row[UserLeadershipRoles.termStatus]
        )
    }

    private fun toRankResponse(row: ResultRow, roleName: String): MemberRankResponse {
        return MemberRankResponse(
            id = row[UserRanks.id].toString(),
            roleId = row[UserRanks.roleId].toString(),
            roleName = roleName,
            assignedByUserId = row[UserRanks.assignedByUserId]?.toString(),
            assignedAt = row[UserRanks.assignedAt].toString()
        )
    }
    fun removeMember(targetUserId: UUID, tuntasId: UUID, callerUserId: UUID): Result<Unit> {
        return transaction {
            val membership = UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq targetUserId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Member not found in this tuntas"))

            validateCanRemoveMember(
                callerUserId = callerUserId,
                targetUserId = targetUserId,
                tuntasId = tuntasId
            )?.let { return@transaction Result.failure(Exception(it)) }

            val now = kotlinx.datetime.Clock.System.now()

            UserTuntasMemberships.update({
                (UserTuntasMemberships.userId eq targetUserId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId)
            }) {
                it[leftAt] = now
            }

            UserLeadershipRoles.update({
                (UserLeadershipRoles.userId eq targetUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE")
            }) {
                it[termStatus] = "RESIGNED"
                it[leftAt] = now
            }

            UnitAssignments.update({
                (UnitAssignments.userId eq targetUserId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }) {
                it[leftAt] = now
            }

            Result.success(Unit)
        }
    }

    fun superAdminUpdateLeadershipRole(
        targetUserId: UUID,
        assignmentId: UUID,
        tuntasId: UUID,
        request: UpdateLeadershipRoleRequest
    ): Result<MemberLeadershipRoleResponse> =
        updateLeadershipRole(
            targetUserId = targetUserId,
            assignmentId = assignmentId,
            tuntasId = tuntasId,
            callerUserId = null,
            request = request,
            bypassHierarchyValidation = true
        )

    fun superAdminRemoveLeadershipRole(
        targetUserId: UUID,
        assignmentId: UUID,
        tuntasId: UUID
    ): Result<Unit> =
        removeLeadershipRole(
            targetUserId = targetUserId,
            assignmentId = assignmentId,
            tuntasId = tuntasId,
            callerUserId = null,
            bypassHierarchyValidation = true
        )

    fun resignMember(callerUserId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            lockActiveTuntininkasAssignments(tuntasId)

            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq callerUserId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("You are not an active member of this tuntas"))

            validateCanResignFromTuntas(
                callerUserId = callerUserId,
                tuntasId = tuntasId
            )?.let { return@transaction Result.failure(Exception(it)) }

            val now = kotlinx.datetime.Clock.System.now()

            UserTuntasMemberships.update({
                (UserTuntasMemberships.userId eq callerUserId) and
                        (UserTuntasMemberships.tuntasId eq tuntasId)
            }) {
                it[leftAt] = now
            }

            UserLeadershipRoles.update({
                (UserLeadershipRoles.userId eq callerUserId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE")
            }) {
                it[termStatus] = "RESIGNED"
                it[leftAt] = now
            }

            UnitAssignments.update({
                (UnitAssignments.userId eq callerUserId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }) {
                it[leftAt] = now
            }

            Result.success(Unit)
        }
    }

    private fun validateCanChangeTargetLeadership(
        callerUserId: UUID,
        targetUserId: UUID,
        tuntasId: UUID,
        targetRoleId: UUID
    ): String? {
        if (callerUserId == targetUserId) {
            return "Use step-down to resign your own leadership role"
        }

        val callerRank = highestActiveLeadershipRank(callerUserId, tuntasId)
        val targetRank = roleRank(targetRoleId)
        return if (callerRank > targetRank) null else "Cannot remove equal or higher leadership role"
    }

    private fun validateCanRemoveMember(
        callerUserId: UUID,
        targetUserId: UUID,
        tuntasId: UUID
    ): String? {
        if (callerUserId == targetUserId) {
            return "Use resign to leave this tuntas"
        }

        val callerRank = highestActiveLeadershipRank(callerUserId, tuntasId)
        val targetRank = highestActiveLeadershipRank(targetUserId, tuntasId)
        return if (callerRank > targetRank) null else "Cannot remove member with equal or higher leadership role"
    }

    private fun validateCanCloseOwnLeadershipRole(
        callerUserId: UUID,
        tuntasId: UUID,
        assignmentId: UUID,
        roleId: UUID
    ): String? {
        validateCanClosePrincipalUnitLeaderDirectly(roleId, null)
            ?.let { return it }

        if (!isTuntininkasRole(roleId)) {
            return null
        }

        val anotherActiveTuntininkasExists = lockActiveTuntininkasAssignments(tuntasId)
            .any { it[UserLeadershipRoles.id] != assignmentId }

        return if (anotherActiveTuntininkasExists) {
            null
        } else {
            "Negalite atsistatydinti is tuntininko pareigu, kol ju neperleidote kitam nariui"
        }
    }

    private fun validateCanClosePrincipalUnitLeaderDirectly(
        roleId: UUID,
        organizationalUnitId: UUID?
    ): String? {
        val roleName = Roles.selectAll()
            .where { Roles.id eq roleId }
            .firstOrNull()
            ?.get(Roles.name)
            ?: return null

        return if (LeadershipRoleRules.isPrincipalUnitLeader(roleName)) {
            "Vieneto vadovas negali atsistatydinti ar buti nuimtas be pakeitejo. Sukurkite atsistatydinimo prasyma ir patvirtindami paskirkite nauja vadova."
        } else {
            null
        }
    }

    private fun validateCanResignFromTuntas(
        callerUserId: UUID,
        tuntasId: UUID
    ): String? {
        val activeTuntininkasAssignments = lockActiveTuntininkasAssignments(tuntasId)
            .filter { it[UserLeadershipRoles.userId] == callerUserId }
            .map { it[UserLeadershipRoles.id] }

        if (activeTuntininkasAssignments.isEmpty()) {
            return null
        }

        val anotherActiveTuntininkasExists = lockActiveTuntininkasAssignments(tuntasId)
            .any { it[UserLeadershipRoles.id] !in activeTuntininkasAssignments }

        return if (anotherActiveTuntininkasExists) {
            null
        } else {
            "Negalite atsistatydinti is tuntininko pareigu, kol ju neperleidote kitam nariui"
        }
    }

    private fun highestActiveLeadershipRank(userId: UUID, tuntasId: UUID): Int {
        return UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull())
            }
            .map { leadershipRoleRank(it[Roles.name]) }
            .maxOrNull() ?: 0
    }

    private fun roleRank(roleId: UUID): Int {
        val roleName = Roles.selectAll()
            .where { Roles.id eq roleId }
            .firstOrNull()
            ?.get(Roles.name)
            ?: return 0
        return leadershipRoleRank(roleName)
    }

    private fun isTuntininkasRole(roleId: UUID): Boolean {
        return Roles.selectAll()
            .where { Roles.id eq roleId }
            .firstOrNull()
            ?.get(Roles.name) == "Tuntininkas"
    }

    private fun lockActiveTuntininkasAssignments(tuntasId: UUID): List<ResultRow> {
        return UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull()) and
                    (Roles.name eq "Tuntininkas")
            }
            .forUpdate()
            .toList()
    }

    private fun leadershipRoleRank(roleName: String): Int {
        return when (roleName) {
            "Tuntininkas" -> 5
            "Tuntininko pavaduotojas" -> 4
            "Inventorininkas" -> 3
            "Draugininkas",
            "Gildijos pirmininkas",
            "Vyr. skautu draugoves draugininkas",
            "Vyr. skautu burelio pirmininkas",
            "Vyr. skauciu draugoves draugininkas",
            "Vyr. skauciu burelio pirmininkas",
            "Draugininko pavaduotojas",
            "Gildijos pirmininko pavaduotojas",
            "Vyr. skautu draugoves draugininko pavaduotojas",
            "Vyr. skautu burelio pirmininko pavaduotojas",
            "Vyr. skauciu draugoves draugininko pavaduotojas",
            "Vyr. skauciu burelio pirmininko pavaduotojas" -> 2
            else -> 0
        }
    }

    private fun isEligibleEventCandidate(member: MemberResponse): Boolean {
        val hasVadovasRank = member.ranks.any { it.roleName == "Vadovas" }
        val hasLeadershipRole = member.leadershipRoles.any {
            it.termStatus == "ACTIVE" && leadershipRoleRank(it.roleName) > 0
        }
        return hasVadovasRank || hasLeadershipRole
    }

    private data class CallerContext(
        val userId: UUID,
        val tuntasId: UUID,
        val seniorUnitIds: Set<UUID>,
        val visibleUnitIds: Set<UUID>,
        val canViewAllMembers: Boolean
    )

    private companion object {
        val supportedRankNames = setOf(
            "Skautas",
            "Patyres skautas",
            "Vyr. skautas kandidatas",
            "Vyr. skautas",
            "Vadovas"
        )
    }
}
