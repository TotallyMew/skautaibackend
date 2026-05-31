package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object LeadershipChangeRequests : Table("leadership_change_requests") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val requesterUserId = uuid("requester_user_id").references(Users.id)
    val roleAssignmentId = uuid("role_assignment_id").references(UserLeadershipRoles.id)
    val roleId = uuid("role_id").references(Roles.id)
    val organizationalUnitId = uuid("organizational_unit_id").references(OrganizationalUnits.id)
    val status = varchar("status", 20).default("PENDING")
    val reason = text("reason").nullable()
    val reviewedByUserId = uuid("reviewed_by_user_id").references(Users.id).nullable()
    val successorUserId = uuid("successor_user_id").references(Users.id).nullable()
    val reviewNote = text("review_note").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val reviewedAt = timestamp("reviewed_at").nullable()
    val resolvedAssignmentId = uuid("resolved_assignment_id").references(UserLeadershipRoles.id).nullable()

    override val primaryKey = PrimaryKey(id)
}
