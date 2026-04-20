package lt.skautai.services

import lt.skautai.database.tables.*
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
                    expiresAt = expiresAt.toString()
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
