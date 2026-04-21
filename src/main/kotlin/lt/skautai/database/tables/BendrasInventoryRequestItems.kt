package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object BendrasInventoryRequestItems : Table("bendras_inventory_request_items") {
    val id = uuid("id").autoGenerate()
    val requestId = uuid("request_id").references(BendrasInventoryRequests.id)
    val itemId = uuid("item_id").references(Items.id)
    val quantity = integer("quantity").default(1)

    override val primaryKey = PrimaryKey(id)
}
