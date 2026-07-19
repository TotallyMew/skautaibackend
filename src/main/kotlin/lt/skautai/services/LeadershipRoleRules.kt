package lt.skautai.services

import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UserLeadershipRoles
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

object LeadershipRoleRules {
    private val standardDraugoveTypes = setOf(
        "VILKU_DRAUGOVE",
        "SKAUTU_DRAUGOVE",
        "PATYRUSIU_SKAUTU_DRAUGOVE"
    )
    private val principalUnitLeaderRoleNames = setOf(
        "Draugininkas",
        "Gildijos pirmininkas",
        "Vyr. skautu draugoves draugininkas",
        "Vyr. skautu burelio pirmininkas",
        "Vyr. skauciu draugoves draugininkas",
        "Vyr. skauciu burelio pirmininkas"
    )
    private val unitScopedLeadershipRoleNames = setOf(
        "Draugininkas",
        "Draugininko pavaduotojas",
        "Gildijos pirmininkas",
        "Gildijos pirmininko pavaduotojas",
        "Vyr. skautu draugoves draugininkas",
        "Vyr. skautu draugoves draugininko pavaduotojas",
        "Vyr. skautu burelio pirmininkas",
        "Vyr. skautu burelio pirmininko pavaduotojas",
        "Vyr. skauciu draugoves draugininkas",
        "Vyr. skauciu draugoves draugininko pavaduotojas",
        "Vyr. skauciu burelio pirmininkas",
        "Vyr. skauciu burelio pirmininko pavaduotojas"
    )
    private val allowedUnitTypesByRoleName = mapOf(
        "Draugininkas" to standardDraugoveTypes,
        "Draugininko pavaduotojas" to standardDraugoveTypes,
        "Gildijos pirmininkas" to setOf("GILDIJA"),
        "Gildijos pirmininko pavaduotojas" to setOf("GILDIJA"),
        "Vyr. skautu draugoves draugininkas" to setOf("VYR_SKAUTU_VIENETAS"),
        "Vyr. skautu draugoves draugininko pavaduotojas" to setOf("VYR_SKAUTU_VIENETAS"),
        "Vyr. skautu burelio pirmininkas" to setOf("VYR_SKAUTU_VIENETAS"),
        "Vyr. skautu burelio pirmininko pavaduotojas" to setOf("VYR_SKAUTU_VIENETAS"),
        "Vyr. skauciu draugoves draugininkas" to setOf("VYR_SKAUCIU_VIENETAS"),
        "Vyr. skauciu draugoves draugininko pavaduotojas" to setOf("VYR_SKAUCIU_VIENETAS"),
        "Vyr. skauciu burelio pirmininkas" to setOf("VYR_SKAUCIU_VIENETAS"),
        "Vyr. skauciu burelio pirmininko pavaduotojas" to setOf("VYR_SKAUCIU_VIENETAS")
    )

    fun isPrincipalUnitLeader(roleName: String): Boolean = roleName in principalUnitLeaderRoleNames
    fun requiresOrganizationalUnit(roleName: String): Boolean = roleName in unitScopedLeadershipRoleNames
    fun allowedOrganizationalUnitTypes(roleName: String): Set<String> = allowedUnitTypesByRoleName[roleName].orEmpty()
    fun isTuntininkas(roleName: String): Boolean = roleName == "Tuntininkas"

    fun validateOrganizationalUnitScope(roleName: String, organizationalUnitId: UUID?): String? {
        return when {
            requiresOrganizationalUnit(roleName) && organizationalUnitId == null ->
                "This leadership role must be assigned to an organizational unit"
            !requiresOrganizationalUnit(roleName) && organizationalUnitId != null ->
                "This tuntas-level leadership role cannot be assigned to an organizational unit"
            else -> null
        }
    }

    fun validateOrganizationalUnitType(roleName: String, organizationalUnitType: String?): String? {
        val allowedTypes = allowedOrganizationalUnitTypes(roleName)
        return if (organizationalUnitType != null && allowedTypes.isNotEmpty() && organizationalUnitType !in allowedTypes) {
            "This leadership role cannot be assigned to this organizational unit type"
        } else null
    }

    fun validatePrincipalUnitLeaderSlot(
        roleId: UUID,
        tuntasId: UUID,
        organizationalUnitId: UUID?,
        excludeAssignmentId: UUID? = null
    ): String? {
        if (organizationalUnitId == null) return null

        val role = Roles.selectAll()
            .where {
                (Roles.id eq roleId) and
                    (Roles.tuntasId eq tuntasId)
            }
            .firstOrNull()
            ?: return "Role not found in this tuntas"

        if (!isPrincipalUnitLeader(role[Roles.name])) return null

        val existing = UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.organizationalUnitId eq organizationalUnitId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    (UserLeadershipRoles.leftAt.isNull())
            }
            .firstOrNull { row ->
                row[Roles.name] in principalUnitLeaderRoleNames &&
                    row[UserLeadershipRoles.id] != excludeAssignmentId
            }

        return if (existing == null) {
            null
        } else {
            "This unit already has an active draugininkas or pirmininkas"
        }
    }
}
