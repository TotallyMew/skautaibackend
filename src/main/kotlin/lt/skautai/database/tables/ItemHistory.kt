package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ItemHistory : Table("item_history") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val eventType = varchar("event_type", 40)
    val quantityChange = integer("quantity_change").nullable()
    val performedByUserId = uuid("performed_by_user_id").references(Users.id).nullable()
    val requisitionId = uuid("requisition_id").references(DraugoveRequisitions.id).nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
