package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table

object EventInventoryAllocations : Table("event_inventory_allocations") {
    val id = uuid("id").autoGenerate()
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val bucketId = uuid("bucket_id").references(EventInventoryBuckets.id)
    val quantity = integer("quantity")
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
