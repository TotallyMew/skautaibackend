package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object UnitAssignments : Table("unit_assignments") {
    val id = uuid("id").autoGenerate()
    val userId = uuid("user_id").references(Users.id)
    val organizationalUnitId = uuid("organizational_unit_id").references(OrganizationalUnits.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val assignmentType = varchar("assignment_type", 30).default("MEMBER")
    val isPubliclyVisible = bool("is_publicly_visible").default(false)
    val assignedByUserId = uuid("assigned_by_user_id").references(Users.id).nullable()
    val joinedAt = timestamp("joined_at")
    val leftAt = timestamp("left_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
