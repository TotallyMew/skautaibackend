package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.AssignLeadershipRoleRequest
import lt.skautai.models.requests.AssignRankRequest
import lt.skautai.models.requests.UpdateLeadershipRoleRequest
import lt.skautai.models.responses.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class MemberService {

    fun getMembers(tuntasId: UUID, visibleUnitIds: Set<UUID>? = null): Result<MemberListResponse> {
        return transaction {
            val memberships = UserTuntasMemberships
                .innerJoin(Users, { UserTuntasMemberships.userId }, { Users.id })
                .selectAll()
                .where {
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }

            val visibleUserIds = visibleUnitIds?.let { unitIds ->
                UnitAssignments.selectAll()
                    .where {
                        (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.organizationalUnitId inList unitIds.toList()) and
                            (UnitAssignments.leftAt.isNull())
                    }
                    .map { it[UnitAssignments.userId] }
                    .toSet()
            }

            val members = memberships.filter { row ->
                visibleUserIds == null || row[UserTuntasMemberships.userId] in visibleUserIds
            }.map { row ->
                val userId = row[UserTuntasMemberships.userId]
                buildMemberResponse(userId, tuntasId, row)
            }

            Result.success(MemberListResponse(members = members, total = members.size))
        }
    }

    fun getMember(userId: UUID, tuntasId: UUID, visibleUnitIds: Set<UUID>? = null, callerUserId: UUID? = null): Result<MemberResponse> {
        return transaction {
            val membership = UserTuntasMemberships
                .innerJoin(Users, { UserTuntasMemberships.userId }, { Users.id })
                .selectAll()
                .where {
                    (UserTuntasMemberships.userId eq userId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Member not found in this tuntas"))

            if (visibleUnitIds != null && userId != callerUserId) {
                val isVisible = UnitAssignments.selectAll()
                    .where {
                        (UnitAssignments.userId eq userId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.organizationalUnitId inList visibleUnitIds.toList()) and
                            (UnitAssignments.leftAt.isNull())
                    }
                    .firstOrNull() != null

                if (!isVisible) {
                    return@transaction Result.failure(Exception("Member not found in this tuntas"))
                }
            }

            Result.success(buildMemberResponse(userId, tuntasId, membership))
        }
    }

    fun assignLeadershipRole(
        targetUserId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID,
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
                assignedByUserId = assignedByUserId
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
        callerUserId: UUID,
        request: UpdateLeadershipRoleRequest
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

            if (request.termStatus in listOf("COMPLETED", "RESIGNED")) {
                validateCanChangeTargetLeadership(
                    callerUserId = callerUserId,
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
        callerUserId: UUID
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

            validateCanChangeTargetLeadership(
                callerUserId = callerUserId,
                targetUserId = targetUserId,
                tuntasId = tuntasId,
                targetRoleId = assignment[UserLeadershipRoles.roleId]
            )?.let { return@transaction Result.failure(Exception(it)) }

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
            UserLeadershipRoles.selectAll()
                .where {
                    (UserLeadershipRoles.id eq assignmentId) and
                            (UserLeadershipRoles.userId eq callerUserId) and
                            (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            (UserLeadershipRoles.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Active leadership role assignment not found"))

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

    fun assignRank(
        targetUserId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID,
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
        membershipRow: ResultRow
    ): MemberResponse {
        val leadershipRoles = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        (UserLeadershipRoles.termStatus eq "ACTIVE") and
                        (UserLeadershipRoles.leftAt.isNull())
            }
            .map { row ->
                val orgUnitId = row[UserLeadershipRoles.organizationalUnitId]
                toLeadershipRoleResponse(
                    row,
                    row[Roles.name],
                    orgUnitId?.let { getOrgUnitName(it) }
                )
            }

        val leadershipRoleHistory = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                        ((UserLeadershipRoles.termStatus neq "ACTIVE") or UserLeadershipRoles.leftAt.isNotNull())
            }
            .map { row ->
                val orgUnitId = row[UserLeadershipRoles.organizationalUnitId]
                toLeadershipRoleResponse(
                    row,
                    row[Roles.name],
                    orgUnitId?.let { getOrgUnitName(it) }
                )
            }

        val ranks = UserRanks
            .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserRanks.userId eq userId) and
                        (UserRanks.tuntasId eq tuntasId)
            }
            .map { row -> toRankResponse(row, row[Roles.name]) }

        val unitAssignments = UnitAssignments
            .innerJoin(OrganizationalUnits, { UnitAssignments.organizationalUnitId }, { OrganizationalUnits.id })
            .selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        (UnitAssignments.leftAt.isNull())
            }
            .map { row ->
                MemberUnitAssignmentResponse(
                    id = row[UnitAssignments.id].toString(),
                    organizationalUnitId = row[UnitAssignments.organizationalUnitId].toString(),
                    organizationalUnitName = row[OrganizationalUnits.name],
                    assignmentType = row[UnitAssignments.assignmentType],
                    joinedAt = row[UnitAssignments.joinedAt].toString()
                )
            }

        return MemberResponse(
            userId = membershipRow[Users.id].toString(),
            name = membershipRow[Users.name],
            surname = membershipRow[Users.surname],
            email = membershipRow[Users.email],
            phone = membershipRow[Users.phone],
            joinedAt = membershipRow[UserTuntasMemberships.joinedAt].toString(),
            unitAssignments = unitAssignments,
            leadershipRoles = leadershipRoles,
            leadershipRoleHistory = leadershipRoleHistory,
            ranks = ranks
        )
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

    fun resignMember(callerUserId: UUID, tuntasId: UUID): Result<Unit> {
        return transaction {
            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq callerUserId) and
                            (UserTuntasMemberships.tuntasId eq tuntasId) and
                            (UserTuntasMemberships.leftAt.isNull())
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("You are not an active member of this tuntas"))

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
}
