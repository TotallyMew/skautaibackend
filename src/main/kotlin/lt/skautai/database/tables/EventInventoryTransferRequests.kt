package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventInventoryTransferRequests : Table("event_inventory_transfer_requests") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val sourceCustodyId = uuid("source_custody_id").references(EventInventoryCustody.id)
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val requestedByUserId = uuid("requested_by_user_id").references(Users.id)
    val requestedFromUserId = uuid("requested_from_user_id").references(Users.id)
    val quantity = integer("quantity")
    val status = varchar("status", 20).default("PENDING")
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val respondedAt = timestamp("responded_at").nullable()
    val respondedByUserId = uuid("responded_by_user_id").references(Users.id).nullable()
    val movementId = uuid("movement_id").references(EventInventoryMovements.id).nullable()

    override val primaryKey = PrimaryKey(id)
}
