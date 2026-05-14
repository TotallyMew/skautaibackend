package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object InventoryListTemplateItems : Table("inventory_list_template_items") {
    val id = uuid("id").autoGenerate()
    val templateId = uuid("template_id").references(InventoryListTemplates.id)
    val itemId = uuid("item_id").references(Items.id).nullable()
    val itemName = varchar("item_name", 200)
    val quantity = integer("quantity").default(1)
    val category = varchar("category", 100).nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
