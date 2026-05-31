package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object InventoryKitItems : Table("inventory_kit_items") {
    val id = uuid("id").autoGenerate()
    val kitId = uuid("kit_id").references(InventoryKits.id)
    val itemId = uuid("item_id").references(Items.id)
    val quantity = integer("quantity").default(1)
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
