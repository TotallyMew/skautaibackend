package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object DirectItemLoans : Table("direct_item_loans") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val issuedToUserId = uuid("issued_to_user_id").references(Users.id)
    val issuedByUserId = uuid("issued_by_user_id").references(Users.id)
    val quantity = integer("quantity")
    val returnedQuantity = integer("returned_quantity").default(0)
    val status = varchar("status", 20).default("ACTIVE")
    val issuedAt = timestamp("issued_at")
    val returnedAt = timestamp("returned_at").nullable()
    val dueAt = timestamp("due_at").nullable()
    val notes = text("notes").nullable()

    override val primaryKey = PrimaryKey(id)
}
