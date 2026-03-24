package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserLeadershipRoles : Table("user_leadership_roles") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val roleId = uuid("role_id").references(Roles.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val organizationalUnitId = uuid("organizational_unit_id")
        .references(OrganizationalUnits.id).nullable()
    val assignedByUserId = uuid("assigned_by_user_id")
        .references(Users.id).nullable()
    val assignedAt = timestamp("assigned_at")
    val startsAt = timestamp("starts_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val leftAt = timestamp("left_at").nullable()
    val termNumber = integer("term_number").default(1)
    val termStatus = varchar("term_status", 20).default("ACTIVE")

    override val primaryKey = PrimaryKey(id)
}