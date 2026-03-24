package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object ItemCustomFields : Table("item_custom_fields") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val fieldName = varchar("field_name", 100)
    val fieldValue = text("field_value").nullable()

    override val primaryKey = PrimaryKey(id)
}