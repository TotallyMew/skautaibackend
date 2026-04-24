package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object EventPurchaseItems : Table("event_purchase_items") {
    val id = uuid("id").autoGenerate()
    val purchaseId = uuid("purchase_id").references(EventPurchases.id)
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val purchasedQuantity = integer("purchased_quantity")
    val unitPrice = decimal("unit_price", 10, 2).nullable()
    val addedToInventoryItemId = uuid("added_to_inventory_item_id").references(Items.id).nullable()
    val addedToInventory = bool("added_to_inventory").default(false)
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
