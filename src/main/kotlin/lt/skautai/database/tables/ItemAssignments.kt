package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemAssignments : Table("item_assignments") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val assignedToUserId = uuid("assigned_to_user_id").references(Users.id)
    val assignedByUserId = uuid("assigned_by_user_id").references(Users.id).nullable()
    val assignedAt = timestamp("assigned_at")
    val unassignedAt = timestamp("unassigned_at").nullable()
    val reason = text("reason").nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}