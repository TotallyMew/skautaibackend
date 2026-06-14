package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object EventPackingContainers : Table("event_packing_containers") {
    val id = uuid("id").autoGenerate()
    val eventId = uuid("event_id").references(Events.id)
    val name = varchar("name", 120)
    val type = varchar("type", 30).default("BOX")
    val status = varchar("status", 20).default("ACTIVE")
    val sortOrder = integer("sort_order").default(0)
    val notes = text("notes").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
