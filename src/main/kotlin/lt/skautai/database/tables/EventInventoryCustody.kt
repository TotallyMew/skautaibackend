package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventInventoryCustody : Table("event_inventory_custody") {
    val id = uuid("id").autoGenerate()
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val parentCustodyId = uuid("parent_custody_id").references(id).nullable()
    val pastovykleId = uuid("pastovykle_id").references(Pastovykles.id).nullable()
    val holderUserId = uuid("holder_user_id").references(Users.id).nullable()
    val quantity = integer("quantity")
    val returnedQuantity = integer("returned_quantity").default(0)
    val status = varchar("status", 20).default("OPEN")
    val createdByUserId = uuid("created_by_user_id").references(Users.id)
    val createdAt = timestamp("created_at")
    val closedAt = timestamp("closed_at").nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
