package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventInventoryMovements : Table("event_inventory_movements") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val custodyId = uuid("custody_id").references(EventInventoryCustody.id).nullable()
    val inventoryRequestId = uuid("inventory_request_id").references(EventInventoryRequests.id).nullable()
    val movementType = varchar("movement_type", 30)
    val quantity = integer("quantity")
    val fromPastovykleId = uuid("from_pastovykle_id").references(Pastovykles.id).nullable()
    val toPastovykleId = uuid("to_pastovykle_id").references(Pastovykles.id).nullable()
    val fromUserId = uuid("from_user_id").references(Users.id).nullable()
    val toUserId = uuid("to_user_id").references(Users.id).nullable()
    val performedByUserId = uuid("performed_by_user_id").references(Users.id)
    val clientRequestId = varchar("client_request_id", 100).nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
