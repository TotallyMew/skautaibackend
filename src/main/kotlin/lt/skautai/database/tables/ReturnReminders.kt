package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ReturnReminders : Table("return_reminders") {
    val id = uuid("id").autoGenerate()
    val reservationId = uuid("reservation_id").references(Reservations.id)
    val reminderDate = date("reminder_date")
    val sentAt = timestamp("sent_at").nullable()
    val acknowledgedAt = timestamp("acknowledged_at").nullable()

    override val primaryKey = PrimaryKey(id)
}