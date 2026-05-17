package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemChecks : Table("item_checks") {
    val id = uuid("id").autoGenerate()
    val sessionId = uuid("session_id").references(ItemCheckSessions.id)
    val itemId = uuid("item_id").references(Items.id).nullable()
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id).nullable()
    val custodyId = uuid("custody_id").references(EventInventoryCustody.id).nullable()
    val result = varchar("result", 20)
    val quantity = integer("quantity").default(1)
    val expectedQuantity = integer("expected_quantity").default(1)
    val actualQuantity = integer("actual_quantity").default(1)
    val actualLocationId = uuid("actual_location_id").references(Locations.id).nullable()
    val actualLocationNote = varchar("actual_location_note", 255).nullable()
    val conditionAtCheck = varchar("condition_at_check", 30).nullable()
    val checkedByUserId = uuid("checked_by_user_id").references(Users.id)
    val notes = text("notes").nullable()
    val checkedAt = timestamp("checked_at")

    override val primaryKey = PrimaryKey(id)
}
