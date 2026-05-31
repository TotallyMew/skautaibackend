package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventInventorySources : Table("event_inventory_sources") {
    val id = uuid("id").autoGenerate()
    val eventInventoryItemId = uuid("event_inventory_item_id").references(EventInventoryItems.id)
    val itemId = uuid("item_id").references(Items.id).nullable()
    val reservationGroupId = uuid("reservation_group_id").nullable()
    val plannedQuantity = integer("planned_quantity")
    val reservedQuantity = integer("reserved_quantity").default(0)
    val pickupCustodianName = varchar("pickup_custodian_name", 200).nullable()
    val pickupLocationPath = varchar("pickup_location_path", 500).nullable()
    val pickupTemporaryStorageLabel = varchar("pickup_temporary_storage_label", 255).nullable()
    val pickupResponsibleUserName = varchar("pickup_responsible_user_name", 200).nullable()
    val pickupSummary = varchar("pickup_summary", 700).nullable()
    val sourceStatus = varchar("source_status", 30).default("PLANNED")
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
