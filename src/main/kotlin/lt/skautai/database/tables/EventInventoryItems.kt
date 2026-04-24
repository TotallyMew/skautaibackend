package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventInventoryItems : Table("event_inventory_items") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val itemId = uuid("item_id").references(Items.id).nullable()
    val bucketId = uuid("bucket_id").references(EventInventoryBuckets.id).nullable()
    val reservationGroupId = uuid("reservation_group_id").nullable()
    val name = varchar("name", 200)
    val plannedQuantity = integer("planned_quantity")
    val availableQuantity = integer("available_quantity").default(0)
    val needsPurchase = bool("needs_purchase").default(false)
    val notes = text("notes").nullable()
    val responsibleUserId = uuid("responsible_user_id").references(Users.id).nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
