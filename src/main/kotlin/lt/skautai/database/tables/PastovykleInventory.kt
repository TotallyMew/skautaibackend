package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object PastovykleInventory : Table("pastovykle_inventory") {
    val id = uuid("id").autoGenerate()
    val pastovykleId = uuid("pastovykle_id").references(Pastovykles.id)
    val itemId = uuid("item_id").references(Items.id)
    val distributedByUserId = uuid("distributed_by_user_id").references(Users.id).nullable()
    val recipientUserId = uuid("recipient_user_id").references(Users.id).nullable()
    val recipientType = varchar("recipient_type", 20).nullable()
    val quantityAssigned = integer("quantity_assigned")
    val quantityReturned = integer("quantity_returned").default(0)
    val assignedAt = timestamp("assigned_at")
    val returnedAt = timestamp("returned_at").nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}