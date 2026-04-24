package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventPurchases : Table("event_purchases") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val purchasedByUserId = uuid("purchased_by_user_id").references(Users.id).nullable()
    val status = varchar("status", 30).default("DRAFT")
    val purchaseDate = date("purchase_date").nullable()
    val totalAmount = decimal("total_amount", 10, 2).nullable()
    val invoiceFileUrl = text("invoice_file_url").nullable()
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
