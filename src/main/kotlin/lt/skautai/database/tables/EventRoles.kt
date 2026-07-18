package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventRoles : Table("event_roles") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val userId = uuid("user_id").references(Users.id)
    val role = varchar("role", 30)
    val targetGroup = varchar("target_group", 20).nullable()
    val pastovykleId = uuid("pastovykle_id").references(Pastovykles.id).nullable()
    val assignedByUserId = uuid("assigned_by_user_id").references(Users.id).nullable()
    val assignedAt = timestamp("assigned_at")

    override val primaryKey = PrimaryKey(id)

}
