package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Reservations : Table("reservations") {
    val id = uuid("id").autoGenerate()
    val itemId = uuid("item_id").references(Items.id)
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val reservedByUserId = uuid("reserved_by_user_id").references(Users.id)
    val approvedByUserId = uuid("approved_by_user_id").references(Users.id).nullable()
    val eventId = uuid("event_id").references(Events.id).nullable()
    val quantity = integer("quantity").default(1)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val status = varchar("status", 20).default("PENDING")
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}