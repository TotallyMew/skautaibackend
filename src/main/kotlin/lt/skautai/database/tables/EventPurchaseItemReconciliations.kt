package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventPurchaseItemReconciliations : Table("event_purchase_item_reconciliations") {
    val id = uuid("id").autoGenerate()
    val purchaseItemId = uuid("purchase_item_id").references(EventPurchaseItems.id)
    val decision = varchar("decision", 40)
    val quantity = integer("quantity")
    val addedInventoryItemId = uuid("added_inventory_item_id").references(Items.id).nullable()
    val performedByUserId = uuid("performed_by_user_id").references(Users.id)
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
