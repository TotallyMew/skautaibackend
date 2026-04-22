package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.AcceptInvitationRequest
import lt.skautai.models.requests.CreateInvitationRequest
import lt.skautai.models.responses.InvitationResponse
import lt.skautai.plugins.resolveUserPermissions
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.datetime.Clock
import java.util.*
import kotlin.time.Duration.Companion.hours

class InvitationService {

    fun createInvitation(
        userId: UUID,
        tuntasId: UUID,
        request: CreateInvitationRequest
    ): Result<InvitationResponse> {
        return transaction {
            // Verify tuntas is active
            val tuntas = Tuntai.selectAll()
                .where { Tuntai.id eq tuntasId }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Tuntas not found"))

            if (tuntas[Tuntai.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Tuntas is not active"))
            }

            // Verify role exists and belongs to this tuntas
            val roleUUID = try {
                UUID.fromString(request.roleId)
            } catch (e: Exception) {
                return@transaction Result.failure(Exception("Invalid role ID"))
            }

            val role = Roles.selectAll()
                .where { (Roles.id eq roleUUID) and (Roles.tuntasId eq tuntasId) }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Role not found in this tuntas"))

            // Verify organizational unit if provided
            val orgUnitUUID = request.organizationalUnitId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }

            if (orgUnitUUID != null) {
                OrganizationalUnits.selectAll()
                    .where { (OrganizationalUnits.id eq orgUnitUUID) and (OrganizationalUnits.tuntasId eq tuntasId) }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Organizational unit not found in this tuntas"))
            }

            validateOwnUnitInvitation(userId, tuntasId, roleUUID, orgUnitUUID)
                ?.let { return@transaction Result.failure(Exception(it)) }

            LeadershipRoleRules.validatePrincipalUnitLeaderSlot(roleUUID, tuntasId, orgUnitUUID)
                ?.let { return@transaction Result.failure(Exception(it)) }

            val code = generateCode()
            val expiresAt = Clock.System.now().plus(request.expiresInHours.hours)

            Invitations.insert {
                it[this.tuntasId] = tuntasId
                it[this.code] = code
                it[this.roleId] = roleUUID
                it[organizationalUnitId] = orgUnitUUID
                it[createdByUserId] = userId
                it[this.expiresAt] = expiresAt
            }

            Result.success(
                InvitationResponse(
                    code = code,
                    roleName = role[Roles.name],
                    tuntasName = tuntas[Tuntai.name],
                    expiresAt = expiresAt.toString(),
                    organizationalUnitId = orgUnitUUID?.toString(),
                    organizationalUnitName = orgUnitUUID?.let { getOrgUnitName(it) }
                )
            )
        }
    }

    fun acceptInvitation(
        userId: UUID,
        activeTuntasId: UUID,
        request: AcceptInvitationRequest
    ): Result<InvitationResponse> {
        return transaction {
            val code = request.code.trim().uppercase()
            if (code.isBlank()) {
                return@transaction Result.failure(Exception("Invite code is required"))
            }

            val invite = Invitations.selectAll()
                .where { Invitations.code eq code }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Invalid invite code"))

            if (invite[Invitations.usedByUserId] != null) {
                return@transaction Result.failure(Exception("Invite code already used"))
            }

            val now = Clock.System.now()
            if (invite[Invitations.expiresAt] < now) {
                return@transaction Result.failure(Exception("Invite code expired"))
            }

            val inviteTuntasId = invite[Invitations.tuntasId]
            if (inviteTuntasId != activeTuntasId) {
                return@transaction Result.failure(Exception("Invite code belongs to another tuntas"))
            }

            val tuntas = Tuntai.selectAll()
                .where { Tuntai.id eq activeTuntasId }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Tuntas not found"))

            if (tuntas[Tuntai.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Tuntas is not active"))
            }

            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq activeTuntasId) and
                        UserTuntasMemberships.leftAt.isNull()
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("You must already be a member of this tuntas"))

            val roleId = invite[Invitations.roleId]
            val role = Roles.selectAll()
                .where {
                    (Roles.id eq roleId) and
                        (Roles.tuntasId eq activeTuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Role not found in this tuntas"))

            val orgUnitId = invite[Invitations.organizationalUnitId]
            if (orgUnitId != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq orgUnitId) and
                            (OrganizationalUnits.tuntasId eq activeTuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Organizational unit not found in this tuntas"))

                validatePrimaryUnitAssignment(userId, activeTuntasId, orgUnitId)
                    ?.let { return@transaction Result.failure(Exception(it)) }
            }

            when (role[Roles.roleType]) {
                "LEADERSHIP" -> {
                    LeadershipRoleRules.validatePrincipalUnitLeaderSlot(roleId, activeTuntasId, orgUnitId)
                        ?.let { return@transaction Result.failure(Exception(it)) }

                    val exists = UserLeadershipRoles.selectAll()
                        .where {
                            (UserLeadershipRoles.userId eq userId) and
                                (UserLeadershipRoles.roleId eq roleId) and
                                (UserLeadershipRoles.tuntasId eq activeTuntasId) and
                                (UserLeadershipRoles.organizationalUnitId eq orgUnitId) and
                                (UserLeadershipRoles.termStatus eq "ACTIVE") and
                                UserLeadershipRoles.leftAt.isNull()
                        }
                        .firstOrNull() != null

                    if (!exists) {
                        UserLeadershipRoles.insert {
                            it[this.userId] = userId
                            it[this.roleId] = roleId
                            it[tuntasId] = activeTuntasId
                            it[organizationalUnitId] = orgUnitId
                            it[assignedByUserId] = invite[Invitations.createdByUserId]
                        }
                    }
                    VadovasRankSupport.ensureVadovasRank(
                        userId = userId,
                        tuntasId = activeTuntasId,
                        assignedByUserId = invite[Invitations.createdByUserId]
                    )
                }
                "RANK" -> {
                    val exists = UserRanks.selectAll()
                        .where {
                            (UserRanks.userId eq userId) and
                                (UserRanks.roleId eq roleId) and
                                (UserRanks.tuntasId eq activeTuntasId)
                        }
                        .firstOrNull() != null

                    if (!exists) {
                        UserRanks.insert {
                            it[this.userId] = userId
                            it[this.roleId] = roleId
                            it[tuntasId] = activeTuntasId
                            it[assignedByUserId] = invite[Invitations.createdByUserId]
                        }
                    }
                }
                else -> return@transaction Result.failure(Exception("Unknown role type"))
            }

            if (orgUnitId != null) {
                val unitAssignmentExists = UnitAssignments.selectAll()
                    .where {
                        (UnitAssignments.userId eq userId) and
                            (UnitAssignments.organizationalUnitId eq orgUnitId) and
                            (UnitAssignments.tuntasId eq activeTuntasId) and
                            (UnitAssignments.assignmentType eq "MEMBER") and
                            UnitAssignments.leftAt.isNull()
                    }
                    .firstOrNull() != null

                if (!unitAssignmentExists) {
                    UnitAssignments.insert {
                        it[this.userId] = userId
                        it[organizationalUnitId] = orgUnitId
                        it[tuntasId] = activeTuntasId
                        it[assignmentType] = "MEMBER"
                        it[assignedByUserId] = invite[Invitations.createdByUserId]
                    }
                }
            }

            Invitations.update({ Invitations.id eq invite[Invitations.id] }) {
                it[usedByUserId] = userId
                it[usedAt] = now
            }

            Result.success(
                InvitationResponse(
                    code = code,
                    roleName = role[Roles.name],
                    tuntasName = tuntas[Tuntai.name],
                    expiresAt = invite[Invitations.expiresAt].toString(),
                    organizationalUnitId = orgUnitId?.toString(),
                    organizationalUnitName = orgUnitId?.let { getOrgUnitName(it) }
                )
            )
        }
    }

    private fun validateOwnUnitInvitation(
        userId: UUID,
        tuntasId: UUID,
        requestedRoleId: UUID,
        requestedOrgUnitId: UUID?
    ): String? {
        val hasAllInvitationScope = resolveUserPermissions(userId, tuntasId)
            .any { it.permissionName == "invitations.create" && it.scope == "ALL" }

        if (hasAllInvitationScope) return null

        val targetOrgUnitId = requestedOrgUnitId
            ?: return "Organizational unit is required for this invitation"

        val leadershipInviterRole = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull()) and
                    (UserLeadershipRoles.organizationalUnitId eq targetOrgUnitId)
            }
            .mapNotNull { row ->
                val roleName = row[Roles.name]
                ownUnitInviteLeadershipTargets[roleName]?.let { deputyRoleName ->
                    row[UserLeadershipRoles.organizationalUnitId]?.let { orgUnitId ->
                        OwnUnitInviteContext(orgUnitId, roleName, deputyRoleName)
                    }
                }
            }
            .firstOrNull()

        val advisorInviterUnitId = if (leadershipInviterRole == null) {
            val hasAdvisorRank = UserRanks
                .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
                .selectAll()
                .where {
                    (UserRanks.userId eq userId) and
                        (UserRanks.tuntasId eq tuntasId) and
                        (Roles.name eq advisorRankRoleName)
                }
                .firstOrNull() != null

            if (!hasAdvisorRank) null else {
                UnitAssignments.selectAll()
                    .where {
                        (UnitAssignments.userId eq userId) and
                            (UnitAssignments.tuntasId eq tuntasId) and
                            (UnitAssignments.organizationalUnitId eq targetOrgUnitId) and
                            (UnitAssignments.leftAt.isNull())
                    }
                    .firstOrNull()
                    ?.get(UnitAssignments.organizationalUnitId)
            }
        } else null

        val inviterUnitId = leadershipInviterRole?.organizationalUnitId ?: advisorInviterUnitId
            ?: return "You can only invite members to the unit where you have invitation rights"

        val unit = OrganizationalUnits.selectAll()
            .where {
                (OrganizationalUnits.id eq inviterUnitId) and
                    (OrganizationalUnits.tuntasId eq tuntasId)
            }
            .firstOrNull()
            ?: return "Organizational unit not found in this tuntas"

        val allowedRoleIds = if (leadershipInviterRole != null) {
            resolveAllowedRoleIds(unit, leadershipInviterRole.inviterRoleName, leadershipInviterRole.allowedLeadershipRoleName, tuntasId)
        } else {
            resolveAdvisorAllowedRoleIds(unit, tuntasId)
        }

        if (requestedRoleId !in allowedRoleIds) {
            return "This role cannot be invited from your unit"
        }

        return null
    }

    private fun resolveAllowedRoleIds(
        unit: ResultRow,
        inviterRoleName: String,
        allowedLeadershipRoleName: String,
        tuntasId: UUID
    ): Set<UUID> {
        val canInviteDeputy = inviterRoleName !in deputyInviterRoleNames
        return when (unit[OrganizationalUnits.type]) {
            "GILDIJA" -> Roles.selectAll()
                .where { Roles.tuntasId eq tuntasId }
                .filterNot {
                    it[Roles.name] in guildRestrictedRoleNames ||
                        (!canInviteDeputy && it[Roles.name] == allowedLeadershipRoleName)
                }
                .mapTo(linkedSetOf()) { it[Roles.id] }

            "VYR_SKAUTU_VIENETAS", "VYR_SKAUCIU_VIENETAS" -> Roles.selectAll()
                .where { Roles.tuntasId eq tuntasId }
                .filter {
                    it[Roles.name] in seniorScoutAllowedRoleNames ||
                        (canInviteDeputy && it[Roles.name] == allowedLeadershipRoleName)
                }
                .mapTo(linkedSetOf()) { it[Roles.id] }

            else -> buildSet {
                resolveAllowedRankRoleId(unit, tuntasId)?.let(::add)
                resolveRoleIdByName(advisorRankRoleName, tuntasId)?.let(::add)
                if (canInviteDeputy) {
                    resolveRoleIdByName(allowedLeadershipRoleName, tuntasId)?.let(::add)
                }
            }
        }
    }

    private fun resolveAdvisorAllowedRoleIds(unit: ResultRow, tuntasId: UUID): Set<UUID> {
        if (unit[OrganizationalUnits.type] == "GILDIJA") return emptySet()
        return buildSet {
            resolveAllowedRankRoleId(unit, tuntasId)?.let(::add)
        }
    }

    private fun resolveAllowedRankRoleId(unit: ResultRow, tuntasId: UUID): UUID? {
        unit[OrganizationalUnits.acceptedRankId]?.let { return it }

        val fallbackRoleName = fallbackRankRoleNamesByUnitType[unit[OrganizationalUnits.type]] ?: return null
        return resolveRoleIdByName(fallbackRoleName, tuntasId)
    }

    private fun resolveRoleIdByName(roleName: String, tuntasId: UUID): UUID? {
        return Roles.selectAll()
            .where {
                (Roles.tuntasId eq tuntasId) and
                    (Roles.name eq roleName)
            }
            .firstOrNull()
            ?.get(Roles.id)
    }

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun validatePrimaryUnitAssignment(
        userId: UUID,
        tuntasId: UUID,
        targetOrgUnitId: UUID
    ): String? {
        val targetUnit = OrganizationalUnits.selectAll()
            .where {
                (OrganizationalUnits.id eq targetOrgUnitId) and
                    (OrganizationalUnits.tuntasId eq tuntasId)
            }
            .firstOrNull()
            ?: return "Organizational unit not found in this tuntas"

        val existingPrimary = UnitAssignments
            .innerJoin(OrganizationalUnits, { UnitAssignments.organizationalUnitId }, { OrganizationalUnits.id })
            .selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.assignmentType eq "MEMBER") and
                    UnitAssignments.leftAt.isNull() and
                    (OrganizationalUnits.type eq targetUnit[OrganizationalUnits.type]) and
                    (UnitAssignments.organizationalUnitId neq targetOrgUnitId)
            }
            .firstOrNull()

        return if (existingPrimary != null) {
            "User already has an active primary membership in a unit of this type"
        } else {
            null
        }
    }

    private fun getOrgUnitName(orgUnitId: UUID): String? {
        return OrganizationalUnits.selectAll()
            .where { OrganizationalUnits.id eq orgUnitId }
            .firstOrNull()
            ?.get(OrganizationalUnits.name)
    }

    private data class OwnUnitInviteContext(
        val organizationalUnitId: UUID,
        val inviterRoleName: String,
        val allowedLeadershipRoleName: String
    )

    private companion object {
        const val advisorRankRoleName = "Vadovas"
        val seniorScoutAllowedRoleNames = setOf(
            "Vyr. skautas",
            "Vyr. skautas kandidatas"
        )
        val guildRestrictedRoleNames = setOf(
            "Vilkas",
            "Skautas",
            "Patyres skautas",
            "Tuntininkas",
            "Tuntininko pavaduotojas"
        )
        val fallbackRankRoleNamesByUnitType = mapOf(
            "VILKU_DRAUGOVE" to "Vilkas",
            "SKAUTU_DRAUGOVE" to "Skautas",
            "PATYRUSIU_SKAUTU_DRAUGOVE" to "Patyres skautas"
        )

        val ownUnitInviteLeadershipTargets = mapOf(
            "Draugininkas" to "Draugininko pavaduotojas",
            "Draugininko pavaduotojas" to "Draugininko pavaduotojas",
            "Gildijos pirmininkas" to "Gildijos pirmininko pavaduotojas",
            "Gildijos pirmininko pavaduotojas" to "Gildijos pirmininko pavaduotojas",
            "Vyr. skautu draugoves draugininkas" to "Vyr. skautu draugoves draugininko pavaduotojas",
            "Vyr. skautu draugoves draugininko pavaduotojas" to "Vyr. skautu draugoves draugininko pavaduotojas",
            "Vyr. skautu burelio pirmininkas" to "Vyr. skautu burelio pirmininko pavaduotojas",
            "Vyr. skautu burelio pirmininko pavaduotojas" to "Vyr. skautu burelio pirmininko pavaduotojas",
            "Vyr. skauciu draugoves draugininkas" to "Vyr. skauciu draugoves draugininko pavaduotojas",
            "Vyr. skauciu draugoves draugininko pavaduotojas" to "Vyr. skauciu draugoves draugininko pavaduotojas",
            "Vyr. skauciu burelio pirmininkas" to "Vyr. skauciu burelio pirmininko pavaduotojas",
            "Vyr. skauciu burelio pirmininko pavaduotojas" to "Vyr. skauciu burelio pirmininko pavaduotojas"
        )
        val deputyInviterRoleNames = setOf(
            "Draugininko pavaduotojas",
            "Gildijos pirmininko pavaduotojas",
            "Vyr. skautu draugoves draugininko pavaduotojas",
            "Vyr. skautu burelio pirmininko pavaduotojas",
            "Vyr. skauciu draugoves draugininko pavaduotojas",
            "Vyr. skauciu burelio pirmininko pavaduotojas"
        )
    }
}
