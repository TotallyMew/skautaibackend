package lt.skautai.services

import lt.skautai.database.tables.*
import lt.skautai.models.requests.AcceptInvitationRequest
import lt.skautai.models.requests.CreateInvitationRequest
import lt.skautai.models.responses.*
import lt.skautai.plugins.resolveUserPermissions
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.*
import kotlin.time.Duration.Companion.hours

class InvitationService {

    fun getInvitationOptions(userId: UUID, tuntasId: UUID): Result<InvitationOptionsResponse> {
        return transaction {
            val tuntas = Tuntai.selectAll()
                .where { Tuntai.id eq tuntasId }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Tuntas not found"))

            if (tuntas[Tuntai.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Tuntas is not active"))
            }

            val permissionScope = resolveInvitationPermissionScope(userId, tuntasId)
                ?: return@transaction Result.failure(Exception("Insufficient permissions"))
            val roleRows = Roles.selectAll()
                .where { Roles.tuntasId eq tuntasId }
                .filter(::isSupportedInvitationRole)
            val unitRows = OrganizationalUnits.selectAll()
                .where { OrganizationalUnits.tuntasId eq tuntasId }
                .toList()
            val unitsById = unitRows.associateBy { it[OrganizationalUnits.id] }

            val options = roleRows.mapNotNull { role ->
                val roleId = role[Roles.id]
                val validUnits = if (permissionScope.hasAllScope) {
                    unitRows.filter { unit -> isValidInvitationTarget(role, unit, tuntasId) }
                } else {
                    permissionScope.allowedUnitIdsByRoleId[roleId]
                        .orEmpty()
                        .mapNotNull(unitsById::get)
                        .filter { unit -> isValidInvitationTarget(role, unit, tuntasId) }
                }
                val canInviteWithoutUnit = permissionScope.hasAllScope && canInviteWithoutUnit(role)

                if (!canInviteWithoutUnit && validUnits.isEmpty()) {
                    null
                } else {
                    InvitationRoleOptionResponse(
                        role = role.toInvitationRoleResponse(),
                        organizationalUnits = validUnits
                            .sortedBy { it[OrganizationalUnits.name].lowercase() }
                            .map { it.toInvitationUnitOptionResponse() },
                        canInviteWithoutOrganizationalUnit = canInviteWithoutUnit
                    )
                }
            }.sortedBy { it.role.name.lowercase() }

            Result.success(InvitationOptionsResponse(roles = options))
        }
    }

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

            if (role[Roles.roleType] == "RANK" && role[Roles.name] !in supportedRankRoleNames) {
                return@transaction Result.failure(Exception("Selected rank is not available"))
            }

            if (LeadershipRoleRules.isTuntininkas(role[Roles.name])) {
                return@transaction Result.failure(Exception("Tuntininkas role cannot be invited"))
            }

            // Verify organizational unit if provided
            val orgUnitUUID = request.organizationalUnitId?.let {
                try {
                    UUID.fromString(it)
                } catch (e: Exception) {
                    return@transaction Result.failure(Exception("Invalid organizational unit ID"))
                }
            }

            val orgUnitType = orgUnitUUID?.let {
                OrganizationalUnits.selectAll()
                    .where { (OrganizationalUnits.id eq orgUnitUUID) and (OrganizationalUnits.tuntasId eq tuntasId) }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Organizational unit not found in this tuntas"))
            }?.get(OrganizationalUnits.type)

            if (role[Roles.roleType] == "LEADERSHIP") {
                LeadershipRoleRules.validateOrganizationalUnitScope(role[Roles.name], orgUnitUUID)
                    ?.let { return@transaction Result.failure(Exception(it)) }
                LeadershipRoleRules.validateOrganizationalUnitType(role[Roles.name], orgUnitType)
                    ?.let { return@transaction Result.failure(Exception(it)) }
            }

            validateOwnUnitInvitation(userId, tuntasId, roleUUID, orgUnitUUID)
                ?.let { return@transaction Result.failure(Exception(it)) }

            LeadershipRoleRules.validatePrincipalUnitLeaderSlot(roleUUID, tuntasId, orgUnitUUID)
                ?.let { return@transaction Result.failure(Exception(it)) }

            val code = generateCode()
            val expiresAt = request.expiresAt?.let {
                try {
                    Instant.parse(it)
                } catch (_: Exception) {
                    return@transaction Result.failure(Exception("Invalid expiresAt format, use ISO 8601"))
                }
            } ?: Clock.System.now().plus(request.expiresInHours.hours)

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
                    tuntasId = tuntasId.toString(),
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
        request: AcceptInvitationRequest
    ): Result<InvitationResponse> {
        return transaction {
            val code = request.code.trim().uppercase()
            if (code.isBlank()) {
                return@transaction Result.failure(Exception("Invite code is required"))
            }

            val invite = Invitations.selectAll()
                .where { Invitations.code eq code }
                .forUpdate()
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

            val tuntas = Tuntai.selectAll()
                .where { Tuntai.id eq inviteTuntasId }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Tuntas not found"))

            if (tuntas[Tuntai.status] != "ACTIVE") {
                return@transaction Result.failure(Exception("Tuntas is not active"))
            }

            UserTuntasMemberships.selectAll()
                .where {
                    (UserTuntasMemberships.userId eq userId) and
                        (UserTuntasMemberships.tuntasId eq inviteTuntasId) and
                        UserTuntasMemberships.leftAt.isNull()
                }
                .firstOrNull()
                ?: run {
                    val existingMembership = UserTuntasMemberships.selectAll()
                        .where {
                            (UserTuntasMemberships.userId eq userId) and
                                (UserTuntasMemberships.tuntasId eq inviteTuntasId)
                        }
                        .firstOrNull()

                    if (existingMembership != null) {
                        UserTuntasMemberships.update({ UserTuntasMemberships.id eq existingMembership[UserTuntasMemberships.id] }) {
                            it[joinedAt] = now
                            it[leftAt] = null
                        }
                    } else {
                        UserTuntasMemberships.insert {
                            it[this.userId] = userId
                            it[this.tuntasId] = inviteTuntasId
                            it[joinedAt] = now
                        }
                    }
                }

            val roleId = invite[Invitations.roleId]
            val role = Roles.selectAll()
                .where {
                    (Roles.id eq roleId) and
                        (Roles.tuntasId eq inviteTuntasId)
                }
                .firstOrNull()
                ?: return@transaction Result.failure(Exception("Role not found in this tuntas"))

            val orgUnitId = invite[Invitations.organizationalUnitId]
            if (orgUnitId != null) {
                OrganizationalUnits.selectAll()
                    .where {
                        (OrganizationalUnits.id eq orgUnitId) and
                            (OrganizationalUnits.tuntasId eq inviteTuntasId)
                    }
                    .firstOrNull()
                    ?: return@transaction Result.failure(Exception("Organizational unit not found in this tuntas"))

                validatePrimaryUnitAssignment(userId, inviteTuntasId, orgUnitId)
                    ?.let { return@transaction Result.failure(Exception(it)) }
            }

            when (role[Roles.roleType]) {
                "LEADERSHIP" -> {
                    LeadershipRoleRules.validatePrincipalUnitLeaderSlot(roleId, inviteTuntasId, orgUnitId)
                        ?.let { return@transaction Result.failure(Exception(it)) }

                    val exists = UserLeadershipRoles.selectAll()
                        .where {
                                (UserLeadershipRoles.userId eq userId) and
                                    (UserLeadershipRoles.roleId eq roleId) and
                                (UserLeadershipRoles.tuntasId eq inviteTuntasId) and
                                (UserLeadershipRoles.organizationalUnitId eq orgUnitId) and
                                (UserLeadershipRoles.termStatus eq "ACTIVE") and
                                UserLeadershipRoles.leftAt.isNull()
                        }
                        .firstOrNull() != null

                    if (!exists) {
                        UserLeadershipRoles.insert {
                            it[this.userId] = userId
                            it[this.roleId] = roleId
                            it[tuntasId] = inviteTuntasId
                            it[organizationalUnitId] = orgUnitId
                            it[assignedByUserId] = invite[Invitations.createdByUserId]
                        }
                    }
                    VadovasRankSupport.ensureVadovasRank(
                        userId = userId,
                        tuntasId = inviteTuntasId,
                        assignedByUserId = invite[Invitations.createdByUserId]
                    )
                }
                "RANK" -> {
                    val exists = UserRanks.selectAll()
                        .where {
                                (UserRanks.userId eq userId) and
                                    (UserRanks.roleId eq roleId) and
                                (UserRanks.tuntasId eq inviteTuntasId)
                        }
                        .firstOrNull() != null

                    if (!exists) {
                        UserRanks.insert {
                            it[this.userId] = userId
                            it[this.roleId] = roleId
                            it[tuntasId] = inviteTuntasId
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
                            (UnitAssignments.tuntasId eq inviteTuntasId) and
                            (UnitAssignments.assignmentType eq "MEMBER") and
                            UnitAssignments.leftAt.isNull()
                    }
                    .firstOrNull() != null

                if (!unitAssignmentExists) {
                    UnitAssignments.insert {
                        it[this.userId] = userId
                        it[organizationalUnitId] = orgUnitId
                        it[tuntasId] = inviteTuntasId
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
                    tuntasId = inviteTuntasId.toString(),
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
        val permissionScope = resolveInvitationPermissionScope(userId, tuntasId)
            ?: return "You do not have permission to create invitations"

        if (permissionScope.hasAllScope) return null

        val targetOrgUnitId = requestedOrgUnitId
            ?: return "Organizational unit is required for this invitation"

        if (targetOrgUnitId !in permissionScope.allowedUnitIdsByRoleId[requestedRoleId].orEmpty()) {
            return "This role cannot be invited from your unit"
        }

        return null
    }

    private fun resolveInvitationPermissionScope(
        userId: UUID,
        tuntasId: UUID
    ): InvitationPermissionScope? {
        val invitationPermissions = resolveUserPermissions(userId, tuntasId)
            .filter { it.permissionName == "invitations.create" }
        if (invitationPermissions.isEmpty()) return null
        if (invitationPermissions.any { it.scope == "ALL" }) {
            return InvitationPermissionScope(hasAllScope = true)
        }

        val allowedUnitIdsByRoleId = linkedMapOf<UUID, MutableSet<UUID>>()
        val leadershipContexts = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull()
            }
            .mapNotNull { row ->
                val roleName = row[Roles.name]
                ownUnitInviteLeadershipTargets[roleName]?.let { deputyRoleName ->
                    row[UserLeadershipRoles.organizationalUnitId]?.let { orgUnitId ->
                        OwnUnitInviteContext(orgUnitId, roleName, deputyRoleName)
                    }
                }
            }

        leadershipContexts.forEach { context ->
            val unit = OrganizationalUnits.selectAll()
                .where {
                    (OrganizationalUnits.id eq context.organizationalUnitId) and
                        (OrganizationalUnits.tuntasId eq tuntasId)
                }
                .firstOrNull()
                ?: return@forEach
            resolveAllowedRoleIds(
                unit,
                context.inviterRoleName,
                context.allowedLeadershipRoleName,
                tuntasId
            ).forEach { roleId ->
                allowedUnitIdsByRoleId.getOrPut(roleId, ::linkedSetOf)
                    .add(context.organizationalUnitId)
            }
        }

        val hasAdvisorRank = UserRanks
            .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserRanks.userId eq userId) and
                    (UserRanks.tuntasId eq tuntasId) and
                    (Roles.name eq advisorRankRoleName)
            }
            .firstOrNull() != null

        if (hasAdvisorRank) {
            val leadershipUnitIds = leadershipContexts.mapTo(mutableSetOf()) { it.organizationalUnitId }
            UnitAssignments
                .innerJoin(OrganizationalUnits, { UnitAssignments.organizationalUnitId }, { OrganizationalUnits.id })
                .selectAll()
                .where {
                    (UnitAssignments.userId eq userId) and
                        (UnitAssignments.tuntasId eq tuntasId) and
                        UnitAssignments.leftAt.isNull()
                }
                .filter { it[OrganizationalUnits.id] !in leadershipUnitIds }
                .forEach { unit ->
                    val unitId = unit[OrganizationalUnits.id]
                    resolveAdvisorAllowedRoleIds(unit, tuntasId).forEach { roleId ->
                        allowedUnitIdsByRoleId.getOrPut(roleId, ::linkedSetOf).add(unitId)
                    }
                }
        }

        return InvitationPermissionScope(
            hasAllScope = false,
            allowedUnitIdsByRoleId = allowedUnitIdsByRoleId
        )
    }

    private fun isSupportedInvitationRole(role: ResultRow): Boolean {
        return !LeadershipRoleRules.isTuntininkas(role[Roles.name]) &&
            (role[Roles.roleType] != "RANK" || role[Roles.name] in supportedRankRoleNames)
    }

    private fun isValidInvitationTarget(role: ResultRow, unit: ResultRow, tuntasId: UUID): Boolean {
        if (role[Roles.roleType] == "LEADERSHIP") {
            if (LeadershipRoleRules.validateOrganizationalUnitScope(role[Roles.name], unit[OrganizationalUnits.id]) != null) {
                return false
            }
            if (LeadershipRoleRules.validateOrganizationalUnitType(role[Roles.name], unit[OrganizationalUnits.type]) != null) {
                return false
            }
        }
        return LeadershipRoleRules.validatePrincipalUnitLeaderSlot(
            role[Roles.id],
            tuntasId,
            unit[OrganizationalUnits.id]
        ) == null
    }

    private fun canInviteWithoutUnit(role: ResultRow): Boolean {
        return role[Roles.roleType] != "LEADERSHIP" ||
            LeadershipRoleRules.validateOrganizationalUnitScope(role[Roles.name], null) == null
    }

    private fun ResultRow.toInvitationRoleResponse() = RoleResponse(
        id = this[Roles.id].toString(),
        name = this[Roles.name],
        roleType = this[Roles.roleType],
        isSystemRole = this[Roles.isSystemRole],
        canBeInvited = true,
        requiresOrganizationalUnit = LeadershipRoleRules.requiresOrganizationalUnit(this[Roles.name]),
        allowedOrganizationalUnitTypes = LeadershipRoleRules.allowedOrganizationalUnitTypes(this[Roles.name]).sorted()
    )

    private fun ResultRow.toInvitationUnitOptionResponse() = InvitationUnitOptionResponse(
        id = this[OrganizationalUnits.id].toString(),
        name = this[OrganizationalUnits.name],
        type = this[OrganizationalUnits.type]
    )

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

    private val secureRandom = java.security.SecureRandom()

    private fun generateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..12).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
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

    private data class InvitationPermissionScope(
        val hasAllScope: Boolean,
        val allowedUnitIdsByRoleId: Map<UUID, Set<UUID>> = emptyMap()
    )

    private companion object {
        const val advisorRankRoleName = "Vadovas"
        val seniorScoutAllowedRoleNames = setOf(
            "Vyr. skautas",
            "Vyr. skautas kandidatas"
        )
        val guildRestrictedRoleNames = setOf(
            "Skautas",
            "Patyres skautas",
            "Tuntininkas",
            "Tuntininko pavaduotojas"
        )
        val fallbackRankRoleNamesByUnitType = mapOf(
            "SKAUTU_DRAUGOVE" to "Skautas",
            "PATYRUSIU_SKAUTU_DRAUGOVE" to "Patyres skautas"
        )
        val supportedRankRoleNames = setOf(
            "Skautas",
            "Patyres skautas",
            "Vyr. skautas kandidatas",
            "Vyr. skautas",
            "Vadovas"
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
