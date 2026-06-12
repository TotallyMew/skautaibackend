package lt.skautai.services

import lt.skautai.database.tables.Permissions
import lt.skautai.database.tables.RolePermissions
import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.Tuntai
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserTuntasMemberships
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class NotificationRecipientService {
    fun usersInTuntas(tuntasId: UUID): List<UUID> = transaction {
        UserTuntasMemberships
            .selectAll()
            .where {
                (UserTuntasMemberships.tuntasId eq tuntasId) and
                    UserTuntasMemberships.leftAt.isNull()
            }
            .map { it[UserTuntasMemberships.userId] }
            .distinct()
    }

    fun usersInActiveTuntai(): List<UUID> = transaction {
        UserTuntasMemberships
            .innerJoin(Tuntai)
            .selectAll()
            .where {
                UserTuntasMemberships.leftAt.isNull() and
                    (Tuntai.status eq "ACTIVE")
            }
            .map { it[UserTuntasMemberships.userId] }
            .distinct()
    }

    fun usersWithPermission(
        tuntasId: UUID,
        permissionName: String,
        organizationalUnitId: UUID? = null,
        excludeUserId: UUID? = null
    ): List<UUID> = transaction {
        val scopedCondition = if (organizationalUnitId == null) {
            RolePermissions.scope eq "ALL"
        } else {
            (RolePermissions.scope eq "ALL") or (
                (RolePermissions.scope neq "ALL") and
                    (UserLeadershipRoles.organizationalUnitId eq organizationalUnitId)
                )
        }

        UserLeadershipRoles
            .innerJoin(Roles)
            .innerJoin(RolePermissions)
            .innerJoin(Permissions)
            .selectAll()
            .where {
                (UserLeadershipRoles.tuntasId eq tuntasId) and
                    (UserLeadershipRoles.termStatus eq "ACTIVE") and
                    UserLeadershipRoles.leftAt.isNull() and
                    (Permissions.name eq permissionName) and
                    scopedCondition
            }
            .map { it[UserLeadershipRoles.userId] }
            .filter { it != excludeUserId }
            .distinct()
    }
}
