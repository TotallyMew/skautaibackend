package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventInventoryRequestHistory : Table("event_inventory_request_history") {
    val id = uuid("id").autoGenerate()
    val requestId = uuid("request_id").references(EventInventoryRequests.id)
    val fromProvider = varchar("from_provider", 20).nullable()
    val toProvider = varchar("to_provider", 20)
    val changedByUserId = uuid("changed_by_user_id").references(Users.id)
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
