package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventInventoryRequests : Table("event_inventory_requests") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val pastovykleId = uuid("pastovykle_id").references(Pastovykles.id)
    val requestedByUserId = uuid("requested_by_user_id").references(Users.id)
    val quantity = integer("quantity")
    val status = varchar("status", 20).default("PENDING")
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val reviewedByUserId = uuid("reviewed_by_user_id").references(Users.id).nullable()
    val reviewedAt = timestamp("reviewed_at").nullable()
    val fulfilledAt = timestamp("fulfilled_at").nullable()
    val resolvedByUserId = uuid("resolved_by_user_id").references(Users.id).nullable()

    override val primaryKey = PrimaryKey(id)
}
