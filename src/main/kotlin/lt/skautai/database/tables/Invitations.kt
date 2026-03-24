package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Invitations : Table("invitations") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val code = varchar("code", 20).uniqueIndex()
    val roleId = uuid("role_id").references(Roles.id)
    val organizationalUnitId = uuid("organizational_unit_id")
        .references(OrganizationalUnits.id).nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id)
    val usedByUserId = uuid("used_by_user_id").references(Users.id).nullable()
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}