package lt.skautai.database.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object Locations : Table("locations") {
    val id = uuid("id").autoGenerate()
    val tuntasId = uuid("tuntas_id").references(Tuntai.id)
    val name = varchar("name", 100)
    val address = text("address").nullable()
    val description = text("description").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}