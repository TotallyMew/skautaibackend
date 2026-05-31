package lt.skautai.services

import kotlinx.datetime.Clock
import lt.skautai.database.tables.LeadershipChangeRequests
import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import lt.skautai.database.tables.UserTuntasMemberships
import lt.skautai.database.tables.Users
import lt.skautai.models.requests.CreateLeadershipChangeRequest
import lt.skautai.models.requests.ReviewLeadershipChangeRequest
import lt.skautai.models.responses.LeadershipChangeRequestListResponse
import lt.skautai.models.responses.LeadershipChangeRequestResponse
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

class LeadershipChangeRequestService {

    fun createResignationRequest(
        callerUserId: UUID,
        assignmentId: UUID,
        tuntasId: UUID,
        request: CreateLeadershipChangeRequest
    ): Result<LeadershipChangeRequestResponse> = transaction {
        val assignment = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.id eq assignmentId) and
                    (UserLeadershipRoles.userId eq callerUserId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull()
            }
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Active leadership role assignment not found"))

        val unitId = assignment[UserLeadershipRoles.organizationalUnitId]
            ?: return@transaction Result.failure(Exception("Only unit leadership roles can use this resignation request"))
        val roleName = assignment[Roles.name]
        if (!LeadershipRoleRules.isPrincipalUnitLeader(roleName)) {
            return@transaction Result.failure(Exception("Only draugininkas or pirmininkas roles require a replacement request"))
        }

        val existing = LeadershipChangeRequests.selectAll()
            .where {
                (LeadershipChangeRequests.roleAssignmentId eq assignmentId) and
                    (LeadershipChangeRequests.status eq "PENDING")
            }
            .firstOrNull()
        if (existing != null) {
            return@transaction Result.success(toResponse(existing))
        }

        val id = LeadershipChangeRequests.insert {
            it[this.tuntasId] = tuntasId
            it[requesterUserId] = callerUserId
            it[roleAssignmentId] = assignmentId
            it[roleId] = assignment[UserLeadershipRoles.roleId]
            it[organizationalUnitId] = unitId
            it[reason] = request.reason?.trim()?.takeIf(String::isNotBlank)
            it[status] = "PENDING"
        } get LeadershipChangeRequests.id

        Result.success(toResponse(loadRequest(id)!!))
    }

    fun getRequests(tuntasId: UUID, callerUserId: UUID, status: String? = "PENDING"): Result<LeadershipChangeRequestListResponse> =
        transaction {
            if (!isTopLevelLeader(callerUserId, tuntasId)) {
                return@transaction Result.failure(Exception("Only tuntininkas or tuntininko pavaduotojas can review leadership change requests"))
            }

            val rows = LeadershipChangeRequests.selectAll()
                .where {
                    val base = LeadershipChangeRequests.tuntasId eq tuntasId
                    status?.let { base and (LeadershipChangeRequests.status eq it) } ?: base
                }
                .toList()
                .sortedByDescending { it[LeadershipChangeRequests.createdAt] }
                .map(::toResponse)

            Result.success(LeadershipChangeRequestListResponse(rows, rows.size))
        }

    fun reviewRequest(
        requestId: UUID,
        tuntasId: UUID,
        reviewerUserId: UUID,
        request: ReviewLeadershipChangeRequest
    ): Result<LeadershipChangeRequestResponse> = transaction {
        if (!isTopLevelLeader(reviewerUserId, tuntasId)) {
            return@transaction Result.failure(Exception("Only tuntininkas or tuntininko pavaduotojas can review leadership change requests"))
        }

        val pending = LeadershipChangeRequests.selectAll()
            .where {
                (LeadershipChangeRequests.id eq requestId) and
                    (LeadershipChangeRequests.tuntasId eq tuntasId)
            }
            .forUpdate()
            .firstOrNull()
            ?: return@transaction Result.failure(Exception("Leadership change request not found"))

        if (pending[LeadershipChangeRequests.status] != "PENDING") {
            return@transaction Result.failure(Exception("Leadership change request is already resolved"))
        }

        when (request.action.uppercase()) {
            "REJECT" -> reject(requestId, reviewerUserId, request.reviewNote)
            "APPROVE" -> approve(pending, reviewerUserId, request)
            else -> return@transaction Result.failure(Exception("Invalid review action"))
        }?.let { return@transaction Result.failure(Exception(it)) }

        Result.success(toResponse(loadRequest(requestId)!!))
    }

    private fun reject(requestId: UUID, reviewerUserId: UUID, reviewNote: String?): String? {
        val now = Clock.System.now()
        LeadershipChangeRequests.update({ LeadershipChangeRequests.id eq requestId }) {
            it[status] = "REJECTED"
            it[reviewedByUserId] = reviewerUserId
            it[this.reviewNote] = reviewNote?.trim()?.takeIf(String::isNotBlank)
            it[reviewedAt] = now
            it[updatedAt] = now
        }
        return null
    }

    private fun approve(
        pending: ResultRow,
        reviewerUserId: UUID,
        request: ReviewLeadershipChangeRequest
    ): String? {
        val successorId = request.successorUserId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return "Successor user ID required"

        if (successorId == pending[LeadershipChangeRequests.requesterUserId]) {
            return "Choose a different member as successor"
        }

        val tuntasId = pending[LeadershipChangeRequests.tuntasId]
        val unitId = pending[LeadershipChangeRequests.organizationalUnitId]
        val roleId = pending[LeadershipChangeRequests.roleId]
        val assignmentId = pending[LeadershipChangeRequests.roleAssignmentId]

        validateSuccessor(successorId, tuntasId, unitId, roleId)?.let { return it }

        val now = Clock.System.now()
        UserLeadershipRoles.update({ UserLeadershipRoles.id eq assignmentId }) {
            it[termStatus] = "RESIGNED"
            it[leftAt] = now
        }

        val newAssignmentId = UserLeadershipRoles.insert {
            it[userId] = successorId
            it[this.roleId] = roleId
            it[this.tuntasId] = tuntasId
            it[organizationalUnitId] = unitId
            it[assignedByUserId] = reviewerUserId
            it[startsAt] = now
            it[termStatus] = "ACTIVE"
        } get UserLeadershipRoles.id

        VadovasRankSupport.ensureVadovasRank(
            userId = successorId,
            tuntasId = tuntasId,
            assignedByUserId = reviewerUserId
        )

        LeadershipChangeRequests.update({ LeadershipChangeRequests.id eq pending[LeadershipChangeRequests.id] }) {
            it[status] = "APPROVED"
            it[reviewedByUserId] = reviewerUserId
            it[successorUserId] = successorId
            it[reviewNote] = request.reviewNote?.trim()?.takeIf(String::isNotBlank)
            it[reviewedAt] = now
            it[updatedAt] = now
            it[resolvedAssignmentId] = newAssignmentId
        }
        return null
    }

    private fun validateSuccessor(successorId: UUID, tuntasId: UUID, unitId: UUID, roleId: UUID): String? {
        UserTuntasMemberships.selectAll()
            .where {
                (UserTuntasMemberships.userId eq successorId) and
                    (UserTuntasMemberships.tuntasId eq tuntasId) and
                    UserTuntasMemberships.leftAt.isNull()
            }
            .firstOrNull()
            ?: return "Successor must be an active member of this tuntas"

        UnitAssignments.selectAll()
            .where {
                (UnitAssignments.userId eq successorId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.organizationalUnitId eq unitId) and
                    UnitAssignments.leftAt.isNull()
            }
            .firstOrNull()
            ?: return "Successor must be an active member of this unit before taking over"

        val unit = OrganizationalUnits.selectAll()
            .where { (OrganizationalUnits.id eq unitId) and (OrganizationalUnits.tuntasId eq tuntasId) }
            .firstOrNull()
            ?: return "Organizational unit not found in this tuntas"

        val acceptedRankId = unit[OrganizationalUnits.acceptedRankId]
        if (acceptedRankId != null) {
            val hasRank = UserRanks.selectAll()
                .where {
                    (UserRanks.userId eq successorId) and
                        (UserRanks.tuntasId eq tuntasId) and
                        (UserRanks.roleId eq acceptedRankId)
                }
                .firstOrNull() != null
            if (!hasRank) {
                return "Successor does not have the rank required for this unit"
            }
        }

        val activePrincipalElsewhere = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq successorId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    UserLeadershipRoles.organizationalUnitId.isNotNull()
            }
            .firstOrNull { row ->
                row[UserLeadershipRoles.organizationalUnitId] != unitId &&
                    LeadershipRoleRules.isPrincipalUnitLeader(row[Roles.name])
            }
        if (activePrincipalElsewhere != null) {
            return "Successor already leads another unit; resolve that leadership role first"
        }

        val otherActiveLeaderInUnit = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.organizationalUnitId eq unitId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    (UserLeadershipRoles.roleId inList listOf(roleId))
            }
            .toList()
            .filter { it[UserLeadershipRoles.userId] != successorId }
        if (otherActiveLeaderInUnit.size > 1) {
            return "This unit has conflicting active principal leaders"
        }

        return null
    }

    private fun isTopLevelLeader(userId: UUID, tuntasId: UUID): Boolean =
        UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull()
            }
            .any { it[Roles.name] in topLevelReviewerRoles }

    private fun loadRequest(id: UUID): ResultRow? =
        LeadershipChangeRequests.selectAll()
            .where { LeadershipChangeRequests.id eq id }
            .firstOrNull()

    private fun toResponse(row: ResultRow): LeadershipChangeRequestResponse {
        val requester = Users.selectAll()
            .where { Users.id eq row[LeadershipChangeRequests.requesterUserId] }
            .first()
        val roleName = Roles.selectAll()
            .where { Roles.id eq row[LeadershipChangeRequests.roleId] }
            .first()[Roles.name]
        val unitName = OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq row[LeadershipChangeRequests.organizationalUnitId] }
            .first()[OrganizationalUnits.name]
        val successorName = row[LeadershipChangeRequests.successorUserId]?.let { successorId ->
            Users.selectAll()
                .where { Users.id eq successorId }
                .firstOrNull()
                ?.let { "${it[Users.name]} ${it[Users.surname]}" }
        }

        return LeadershipChangeRequestResponse(
            id = row[LeadershipChangeRequests.id].toString(),
            tuntasId = row[LeadershipChangeRequests.tuntasId].toString(),
            requesterUserId = row[LeadershipChangeRequests.requesterUserId].toString(),
            requesterName = "${requester[Users.name]} ${requester[Users.surname]}",
            roleAssignmentId = row[LeadershipChangeRequests.roleAssignmentId].toString(),
            roleId = row[LeadershipChangeRequests.roleId].toString(),
            roleName = roleName,
            organizationalUnitId = row[LeadershipChangeRequests.organizationalUnitId].toString(),
            organizationalUnitName = unitName,
            status = row[LeadershipChangeRequests.status],
            reason = row[LeadershipChangeRequests.reason],
            reviewedByUserId = row[LeadershipChangeRequests.reviewedByUserId]?.toString(),
            successorUserId = row[LeadershipChangeRequests.successorUserId]?.toString(),
            successorName = successorName,
            reviewNote = row[LeadershipChangeRequests.reviewNote],
            createdAt = row[LeadershipChangeRequests.createdAt].toString(),
            updatedAt = row[LeadershipChangeRequests.updatedAt].toString(),
            reviewedAt = row[LeadershipChangeRequests.reviewedAt]?.toString(),
            resolvedAssignmentId = row[LeadershipChangeRequests.resolvedAssignmentId]?.toString()
        )
    }

    private companion object {
        val topLevelReviewerRoles = setOf("Tuntininkas", "Tuntininko pavaduotojas")
    }
}
