package lt.skautai.services

import lt.skautai.database.tables.Roles
import lt.skautai.database.tables.Tuntai
import lt.skautai.database.tables.UserLeadershipRoles
import lt.skautai.database.tables.UserRanks
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object VadovasRankSupport {

    private const val VADOVAS_ROLE_NAME = "Vadovas"

    fun ensureVadovasRank(
        userId: UUID,
        tuntasId: UUID,
        assignedByUserId: UUID?
    ) {
        val vadovasRole = resolveVadovasRole(tuntasId) ?: return

        val existingRank = UserRanks.selectAll()
            .where {
                (UserRanks.userId eq userId) and
                    (UserRanks.tuntasId eq tuntasId) and
                    (UserRanks.roleId eq vadovasRole[Roles.id])
            }
            .firstOrNull()

        if (existingRank != null) return

        UserRanks.insert {
            it[UserRanks.userId] = userId
            it[roleId] = vadovasRole[Roles.id]
            it[UserRanks.tuntasId] = tuntasId
            it[UserRanks.assignedByUserId] = assignedByUserId
        }
    }

    fun backfillExistingLeadershipUsers() {
        transaction {
            Tuntai.selectAll().forEach { tuntas ->
                val tuntasId = tuntas[Tuntai.id]
                val activeLeadershipUsers = UserLeadershipRoles
                    .selectAll()
                    .where {
                        (UserLeadershipRoles.tuntasId eq tuntasId) and
                            (UserLeadershipRoles.termStatus eq "ACTIVE") and
                            UserLeadershipRoles.leftAt.isNull()
                    }
                    .map { it[UserLeadershipRoles.userId] }
                    .distinct()

                activeLeadershipUsers.forEach { userId ->
                    ensureVadovasRank(
                        userId = userId,
                        tuntasId = tuntasId,
                        assignedByUserId = null
                    )
                }
            }
        }
    }

    private fun resolveVadovasRole(tuntasId: UUID): ResultRow? {
        return Roles.selectAll()
            .where {
                (Roles.tuntasId eq tuntasId) and
                    (Roles.name eq VADOVAS_ROLE_NAME) and
                    (Roles.roleType eq "RANK")
            }
            .firstOrNull()
    }
}
