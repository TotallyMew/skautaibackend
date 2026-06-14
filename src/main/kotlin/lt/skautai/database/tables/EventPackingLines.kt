package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventPackingLines : Table("event_packing_lines") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val allocationId = uuid("allocation_id").references(EventInventoryAllocations.id).nullable()
    val containerId = uuid("container_id").references(EventPackingContainers.id).nullable()
    val bucketId = uuid("bucket_id").references(EventInventoryBuckets.id).nullable()
    val itemId = uuid("item_id").references(Items.id).nullable()
    val itemName = varchar("item_name", 200)
    val requiredQuantity = integer("required_quantity")
    val status = varchar("status", 20).default("TODO")
    val sourceSummary = varchar("source_summary", 700).nullable()
    val notes = text("notes").nullable()
    val checkedByUserId = uuid("checked_by_user_id").references(Users.id).nullable()
    val checkedAt = timestamp("checked_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
