package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object ExportLogs : Table("export_logs") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id).nullable()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val exportedByUserId = uuid("exported_by_user_id").references(Users.id).nullable()
    val exportFormat = varchar("export_format", 10).nullable()
    val exportedAt = timestamp("exported_at")

    override val primaryKey = PrimaryKey(id)
}