package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventExtraCosts : Table("event_extra_costs") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val category = varchar("category", 40)
    val label = varchar("label", 200)
    val quantity = decimal("quantity", 10, 2).nullable()
    val unit = varchar("unit", 40).nullable()
    val unitPrice = decimal("unit_price", 10, 2).nullable()
    val totalAmount = decimal("total_amount", 10, 2)
    val notes = text("notes").nullable()
    val createdByUserId = uuid("created_by_user_id").references(Users.id).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
