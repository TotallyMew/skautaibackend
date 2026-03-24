package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserRoles : Table("user_roles") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val roleId = uuid("role_id").references(Roles.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val organizationalUnitId = uuid("organizational_unit_id")
        .references(OrganizationalUnits.id).nullable()
    val assignedByUserId = uuid("assigned_by_user_id")
        .references(Users.id).nullable()
    val assignedAt = timestamp("assigned_at")
    val expiresAt = timestamp("expires_at").nullable()

    override val primaryKey = PrimaryKey(id)
}