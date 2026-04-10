package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UserDraugoveMemberships : Table("user_draugove_memberships") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val organizationalUnitId = uuid("organizational_unit_id").references(OrganizationalUnits.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val isLent = bool("is_lent").default(false)
    val assignedByUserId = uuid("assigned_by_user_id").references(Users.id).nullable()
    val joinedAt = timestamp("joined_at")
    val leftAt = timestamp("left_at").nullable()

    override val primaryKey = PrimaryKey(id)
}