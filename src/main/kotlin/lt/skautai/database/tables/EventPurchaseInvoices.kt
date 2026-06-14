package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventPurchaseInvoices : Table("event_purchase_invoices") {
    val id = uuid("id").autoGenerate()
    val purchaseId = uuid("purchase_id").references(EventPurchases.id)
    val fileUrl = text("file_url")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
