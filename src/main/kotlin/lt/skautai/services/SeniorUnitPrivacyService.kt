package lt.skautai.services

import lt.skautai.database.tables.OrganizationalUnits
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.UnitAssignments
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object SeniorUnitPrivacyService {
    val seniorUnitTypes = setOf("VYR_SKAUTU_VIENETAS", "VYR_SKAUCIU_VIENETAS")

    private val seniorUnitLeaderRoleNames = setOf(
        "Vyr. skautu draugoves draugininkas",
        "Vyr. skautu draugoves draugininko pavaduotojas",
        "Vyr. skautu burelio pirmininkas",
        "Vyr. skautu burelio pirmininko pavaduotojas",
        "Vyr. skauciu draugoves draugininkas",
        "Vyr. skauciu draugoves draugininko pavaduotojas",
        "Vyr. skauciu burelio pirmininkas",
        "Vyr. skauciu burelio pirmininko pavaduotojas"
    )

    fun isSeniorUnit(unitId: UUID, tuntasId: UUID): Boolean = transaction {
        OrganizationalUnits.selectAll()
            .where {
                (OrganizationalUnits.id eq unitId) and
                    (OrganizationalUnits.tuntasId eq tuntasId) and
                    (OrganizationalUnits.type inList seniorUnitTypes.toList())
            }
            .firstOrNull() != null
    }

    fun seniorUnitIds(tuntasId: UUID): Set<UUID> = transaction {
        OrganizationalUnits.selectAll()
            .where {
                (OrganizationalUnits.tuntasId eq tuntasId) and
                    (OrganizationalUnits.type inList seniorUnitTypes.toList())
            }
            .map { it[OrganizationalUnits.id] }
            .toSet()
    }

    fun userHasInternalAccess(userId: UUID, tuntasId: UUID, unitId: UUID): Boolean = transaction {
        val isMember = UnitAssignments.selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.organizationalUnitId eq unitId) and
                    UnitAssignments.leftAt.isNull()
            }
            .firstOrNull() != null
        if (isMember) return@transaction true

        UserLeadershipRoles.selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.organizationalUnitId eq unitId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull()
            }
            .firstOrNull() != null
    }

    fun protectedUnitIdsFor(userId: UUID, tuntasId: UUID): Set<UUID> = transaction {
        val seniorIds = seniorUnitIds(tuntasId)
        if (seniorIds.isEmpty()) return@transaction emptySet()

        val memberUnitIds = UnitAssignments.select(UnitAssignments.organizationalUnitId)
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.organizationalUnitId inList seniorIds.toList()) and
                    UnitAssignments.leftAt.isNull()
            }
            .map { it[UnitAssignments.organizationalUnitId] }
            .toSet()
        val ledUnitIds = UserLeadershipRoles.select(UserLeadershipRoles.organizationalUnitId)
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.organizationalUnitId inList seniorIds.toList()) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull()
            }
            .mapNotNull { it[UserLeadershipRoles.organizationalUnitId] }
            .toSet()

        seniorIds - memberUnitIds - ledUnitIds
    }

    fun canManageCandidateVisibility(userId: UUID, tuntasId: UUID, unitId: UUID): Boolean = transaction {
        if (!isSeniorUnit(unitId, tuntasId)) return@transaction false
        UserLeadershipRoles
            .innerJoin(Roles, { UserLeadershipRoles.roleId }, { Roles.id })
            .selectAll()
            .where {
                (UserLeadershipRoles.userId eq userId) and
                    (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.organizationalUnitId eq unitId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    (Roles.name inList seniorUnitLeaderRoleNames.toList())
            }
            .firstOrNull() != null
    }

    fun isPublicSeniorMember(userId: UUID, tuntasId: UUID, unitId: UUID): Boolean = transaction {
        val assignment = UnitAssignments.selectAll()
            .where {
                (UnitAssignments.userId eq userId) and
                    (UnitAssignments.tuntasId eq tuntasId) and
                    (UnitAssignments.organizationalUnitId eq unitId) and
                    UnitAssignments.leftAt.isNull()
            }
            .firstOrNull() ?: return@transaction false

        val rankNames = UserRanks
            .innerJoin(Roles, { UserRanks.roleId }, { Roles.id })
            .select(Roles.name)
            .where {
                (UserRanks.userId eq userId) and
                    (UserRanks.tuntasId eq tuntasId)
            }
            .map { it[Roles.name] }
            .toSet()

        "Vyr. skautas" in rankNames || assignment[UnitAssignments.isPubliclyVisible]
    }
}
